@file:OptIn(UnstableApi::class)

package tf.monochrome.recorder.editor.export

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import kotlin.math.min

/**
 * Film grain over the whole picture. See [Grain] for the rules and
 * [GRAIN_FRAGMENT_SHADER] for how it is drawn.
 */
class FilmGrain(private val grain: PlannedGrain) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        FilmGrainShaderProgram(useHdr, grain)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = grain.amount <= 0f
}

private class FilmGrainShaderProgram(useHdr: Boolean, private val grain: PlannedGrain) :
    BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

    private val program: GlProgram = try {
        // HDR frames arrive in linear light; the grain is added to a gamma-encoded copy.
        val fragment = if (useHdr) GRAIN_FRAGMENT_SHADER.replaceFirst("\n", "\n#define LINEAR_INPUT\n") else GRAIN_FRAGMENT_SHADER
        GlProgram(GRAIN_VERTEX_SHADER, fragment)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    private var std = 0f
    private var cell = 1f

    init {
        // The whole frame, -1..1 on each axis, sampled straight through.
        program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        val identity = GlUtil.create4x4IdentityMatrix()
        program.setFloatsUniform("uTransformationMatrix", identity)
        program.setFloatsUniform("uTexTransformationMatrix", identity)
        val power = Grain.sharpPower(grain.sharpness)
        program.setFloatUniform("uSharpPower", power)
        program.setFloatUniform("uSharpNorm", Grain.sharpNorm(power))
        program.setFloatUniform("uTexture", grain.texture)
        program.setFloatUniform("uColour", grain.colour)
        program.setFloatsUniform("uChannels", floatArrayOf(grain.red, grain.green, grain.blue))
        program.setFloatsUniform("uResponse", floatArrayOf(grain.shadows, grain.midtones, grain.highlights))
        program.setFloatUniform("uSolo", if (grain.solo) 1f else 0f)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val shortSide = min(inputWidth, inputHeight)
        cell = Grain.cellPixels(grain.size, shortSide)
        std = Grain.pixelStd(grain.amount, grain.size, shortSide)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val (sx, sy) = Grain.seed(presentationTimeUs, grain.motion)
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            program.setFloatUniform("uStd", std)
            program.setFloatUniform("uCell", cell)
            program.setFloatsUniform("uSeed", floatArrayOf(sx, sy))
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}

/** Media3's own pass-through vertex shader (vertex_shader_transformation_es2). */
internal const val GRAIN_VERTEX_SHADER = """#version 100
attribute vec4 aFramePosition;
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = uTransformationMatrix * aFramePosition;
  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
}
"""

/**
 * Gaussian film grain. Noise is laid on a grid of [Grain.cellPixels] cells
 * shifted by a fresh [Grain.seed] each frame, read with smooth interpolation
 * and rescaled so its strength is even across each cell (interpolation is
 * weaker between cell centres, which shows as a mesh). `uTexture` mixes in a
 * second, half-size layer for grit; `uSharpPower` and `uSharpNorm` crisp the
 * grains' edges without changing their strength ([Grain.sharpPower]).
 *
 * One grain field goes on all three channels; `uColour` blends in a field of
 * each channel's own, keeping the strength, and `uChannels` scales each.
 * `uResponse` (shadows, midtones, highlights) weights it by brightness as
 * [Grain.response] does. With `uSolo` the picture is replaced by mid grey.
 * The texture and colour layers cost nothing when set to zero.
 *
 * The hash is Dave Hoskins' "hash without sine": sin-based hashes lose
 * precision on mobile GPUs and turn into visible patterns.
 */
internal const val GRAIN_FRAGMENT_SHADER = """#version 100
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform float uStd;
uniform float uCell;
uniform vec2 uSeed;
uniform float uSharpPower;
uniform float uSharpNorm;
uniform float uTexture;
uniform float uColour;
uniform vec3 uChannels;
uniform vec3 uResponse;
uniform float uSolo;
varying vec2 vTexSamplingCoord;

float hash(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

// Four uniforms summed: close to Gaussian, mean 0, standard deviation 1.
float gauss(vec2 cell) {
  float s = hash(cell) + hash(cell + vec2(17.13, 3.71)) + hash(cell + vec2(5.27, 29.63)) + hash(cell + vec2(41.19, 11.57));
  return (s - 2.0) * 1.7320508;
}

float layer(vec2 px, float cellSize, vec2 seed) {
  vec2 g = px / cellSize + seed;
  vec2 i = floor(g);
  vec2 f = g - i;
  vec2 w = f * f * (3.0 - 2.0 * f);
  float n = mix(
      mix(gauss(i), gauss(i + vec2(1.0, 0.0)), w.x),
      mix(gauss(i + vec2(0.0, 1.0)), gauss(i + vec2(1.0, 1.0)), w.x),
      w.y);
  vec2 v = (1.0 - w) * (1.0 - w) + w * w;
  return n * inversesqrt(v.x * v.y);
}

// One grain field, standard deviation 1.
float field(vec2 px, vec2 seed) {
  float n = layer(px, uCell, seed);
  if (uTexture > 0.0) {
    n = (n + uTexture * layer(px, uCell * 0.5, seed.yx + 0.37)) * inversesqrt(1.0 + uTexture * uTexture);
  }
  if (uSharpPower < 1.0) {
    n = sign(n) * pow(abs(n), uSharpPower) * uSharpNorm;
  }
  return n;
}

float arc(float t) {
  return sqrt(clamp(t * (2.0 - t), 0.0, 1.0));
}

void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 c = src.rgb;
#ifdef LINEAR_INPUT
  c = pow(max(c, 0.0), vec3(1.0 / 2.4));
#endif
  if (uSolo > 0.5) {
    c = vec3(0.5);
  }
  float luma = dot(clamp(c, 0.0, 1.0), vec3(0.2126, 0.7152, 0.0722));
  float response = luma < 0.5
      ? mix(uResponse.x, uResponse.y, arc(2.0 * luma))
      : mix(uResponse.z, uResponse.y, arc(2.0 - 2.0 * luma));
  float mono = field(gl_FragCoord.xy, uSeed);
  vec3 n = vec3(mono);
  if (uColour > 0.0) {
    vec3 own = vec3(
        field(gl_FragCoord.xy, uSeed + vec2(101.3, 47.9)),
        field(gl_FragCoord.xy, uSeed + vec2(13.7, 211.1)),
        field(gl_FragCoord.xy, uSeed + vec2(307.9, 157.3)));
    float k = 1.0 - uColour;
    n = (n * k + own * uColour) * inversesqrt(k * k + uColour * uColour);
  }
  c += n * uChannels * (uStd * response);
#ifdef LINEAR_INPUT
  c = pow(max(c, 0.0), vec3(2.4));
#else
  c = clamp(c, 0.0, 1.0);
#endif
  gl_FragColor = vec4(c, src.a);
}
"""
