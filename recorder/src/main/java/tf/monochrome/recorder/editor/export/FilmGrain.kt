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
 * Film grain over the whole picture: [amount] 0..1, [size] 0 (fine) .. 1
 * (coarse). See [Grain] for the rules and [GRAIN_FRAGMENT_SHADER] for how
 * it is drawn.
 */
class FilmGrain(private val amount: Float, private val size: Float) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        FilmGrainShaderProgram(useHdr, amount, size)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = amount <= 0f
}

private class FilmGrainShaderProgram(useHdr: Boolean, private val amount: Float, private val size: Float) :
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
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val shortSide = min(inputWidth, inputHeight)
        cell = Grain.cellPixels(size, shortSide)
        std = Grain.pixelStd(amount, size, shortSide)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val (sx, sy) = Grain.seed(presentationTimeUs)
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
 * Gaussian luminance grain. Noise is laid on a grid of [Grain.cellPixels]
 * cells shifted by a fresh [Grain.seed] each frame, read with smooth
 * interpolation and rescaled so its strength is even across each cell (plain
 * interpolation is weaker between cell centres, which shows as a mesh); a
 * second, twice-as-fine layer gives the clumps some texture. The same value
 * goes on red, green and blue, weighted by [Grain.response].
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

void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 c = src.rgb;
#ifdef LINEAR_INPUT
  c = pow(max(c, 0.0), vec3(1.0 / 2.4));
#endif
  float luma = dot(clamp(c, 0.0, 1.0), vec3(0.2126, 0.7152, 0.0722));
  float response = 0.2 + 0.8 * sqrt(clamp(4.0 * luma * (1.0 - luma), 0.0, 1.0));
  float n = (layer(gl_FragCoord.xy, uCell, uSeed) + 0.5 * layer(gl_FragCoord.xy, uCell * 0.5, uSeed.yx + 0.37)) * 0.8944272;
  c += n * uStd * response;
#ifdef LINEAR_INPUT
  c = pow(max(c, 0.0), vec3(2.4));
#else
  c = clamp(c, 0.0, 1.0);
#endif
  gl_FragColor = vec4(c, src.a);
}
"""
