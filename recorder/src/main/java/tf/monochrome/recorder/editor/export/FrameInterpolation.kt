@file:OptIn(UnstableApi::class)

package tf.monochrome.recorder.editor.export

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Puts the picture on a fixed frame rate: [FrameRetimer] says what each
 * output frame is made from, and this draws it. A frame between two source
 * frames is made by optical flow — the motion between the two is measured,
 * and each is moved along it to the moment asked for — so a 30 fps clip
 * exported at 60, or a clip slowed to a quarter, moves smoothly instead of
 * showing every frame twice.
 *
 * It is the one effect here that gives out a different number of frames than
 * it takes in, so it implements [GlShaderProgram] itself rather than
 * Media3's one-in-one-out base class.
 */
class FrameInterpolation(
    private val rate: PlannedFrameRate,
    private val startUs: Long,
    private val endUs: Long,
    private val holdGapUs: Long,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = try {
        // The flow shaders need OpenGL ES 3; on a 2.0 context frames are repeated instead.
        val flow = rate.opticalFlow && GlUtil.getContextMajorVersion() >= 3
        FrameInterpolationShaderProgram(
            useHdr,
            FrameRetimer(rate.numerator, rate.denominator, startUs, endUs, holdGapUs, interpolate = flow),
            flow,
        )
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }
}

private class FrameInterpolationShaderProgram(
    private val useHdr: Boolean,
    private val retimer: FrameRetimer,
    withFlow: Boolean,
) : GlShaderProgram {
    private var inputListener: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
    private var outputListener: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
    private var errorExecutor: Executor = Executor { it.run() }
    private var errorListener: GlShaderProgram.ErrorListener = GlShaderProgram.ErrorListener { }

    private val copy = GlProgram(COPY_VERTEX_SHADER, COPY_FRAGMENT_SHADER).also(::fullFrame)
    private val flow: OpticalFlow? = if (withFlow) OpticalFlow(useHdr) else null

    private var width = 0
    private var height = 0

    /** This program's own copies of the two newest source frames; index [newest] is the later one. */
    private val frames = arrayOfNulls<GlTextureInfo>(2)
    private var newest = 0
    private var flowReady = false

    private val free = ArrayDeque<GlTextureInfo>()
    private val inUse = HashSet<GlTextureInfo>()

    /** A source frame was taken and the upstream has not been told to send the next. */
    private var owesReady = false
    private var ending = false
    private var draining = false

    override fun setInputListener(inputListener: GlShaderProgram.InputListener) {
        this.inputListener = inputListener
        // One source frame at a time: it is copied, so the upstream gets its texture straight back.
        if (!owesReady) inputListener.onReadyToAcceptInputFrame()
    }

    override fun setOutputListener(outputListener: GlShaderProgram.OutputListener) {
        this.outputListener = outputListener
    }

    override fun setErrorListener(executor: Executor, errorListener: GlShaderProgram.ErrorListener) {
        errorExecutor = executor
        this.errorListener = errorListener
    }

    override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, inputTexture: GlTextureInfo, presentationTimeUs: Long) {
        try {
            configure(glObjectsProvider, inputTexture.width, inputTexture.height)
            // A new stream's first frame while the last one's tail is still waiting for
            // textures: the tail gives way, since its frames are about to be overwritten.
            if (ending) endStream()
            if (retimer.offer(presentationTimeUs)) {
                newest = 1 - newest
                draw(copy, frames[newest]!!) { copy.setSamplerTexIdUniform("uTexSampler", inputTexture.texId, 0) }
                flow?.invalidate(newest)
                flowReady = false
            }
            inputListener.onInputFrameProcessed(inputTexture)
            owesReady = true
            drain()
        } catch (e: Exception) {
            fail(e, presentationTimeUs)
        }
    }

    override fun releaseOutputFrame(outputTexture: GlTextureInfo) {
        if (!inUse.remove(outputTexture)) return
        free.addLast(outputTexture)
        try {
            drain()
        } catch (e: Exception) {
            fail(e)
        }
    }

    override fun signalEndOfCurrentInputStream() {
        retimer.finish()
        ending = true
        try {
            drain()
        } catch (e: Exception) {
            fail(e)
        }
    }

    override fun flush() {
        free.addAll(inUse)
        inUse.clear()
        retimer.reset()
        ending = false
        owesReady = false
        flowReady = false
        inputListener.onFlush()
        inputListener.onReadyToAcceptInputFrame()
    }

    override fun release() {
        try {
            (free + inUse + frames.filterNotNull()).forEach { it.release() }
            free.clear()
            inUse.clear()
            frames.fill(null)
            flow?.release()
            copy.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    /**
     * Draws every output frame the retimer has ready, as far as there are
     * free textures to draw into; [releaseOutputFrame] picks up where this
     * stopped. Only once a source frame's output is all drawn is the next
     * asked for, or the end of the stream passed on.
     */
    private fun drain() {
        if (draining) return
        draining = true
        try {
            while (true) {
                if (width > 0 && free.isEmpty()) return
                val frame = retimer.poll() ?: break
                val target = free.removeFirst()
                inUse += target
                render(frame, target)
                outputListener.onOutputFrameAvailable(target, frame.timeUs)
            }
            if (ending) endStream()
            if (owesReady) {
                owesReady = false
                inputListener.onReadyToAcceptInputFrame()
            }
        } finally {
            draining = false
        }
    }

    private fun endStream() {
        ending = false
        retimer.reset()
        flowReady = false
        outputListener.onCurrentOutputStreamEnded()
    }

    private fun render(frame: RetimedFrame, target: GlTextureInfo) {
        val later = frames[newest]!!
        val earlier = frames[1 - newest]!!
        val flow = flow
        when {
            frame is RetimedFrame.Between && flow != null -> {
                if (!flowReady) {
                    flow.estimate(earlier, 1 - newest, later, newest)
                    flowReady = true
                }
                flow.blend(earlier, later, frame.fraction, target)
            }
            frame is RetimedFrame.Earlier -> draw(copy, target) { copy.setSamplerTexIdUniform("uTexSampler", earlier.texId, 0) }
            else -> draw(copy, target) { copy.setSamplerTexIdUniform("uTexSampler", later.texId, 0) }
        }
    }

    private fun configure(gl: GlObjectsProvider, w: Int, h: Int) {
        if (w == width && h == height) return
        // A new size is a new picture: nothing made at the old one can be reused.
        check(inUse.isEmpty()) { "frame size changed with output frames still in use" }
        (free + frames.filterNotNull()).forEach { it.release() }
        free.clear()
        width = w
        height = h
        for (i in frames.indices) frames[i] = texture(gl, w, h, useHdr)
        repeat(OUTPUT_FRAMES) { free.addLast(texture(gl, w, h, useHdr)) }
        flow?.configure(gl, w, h)
        flowReady = false
    }

    private fun fail(e: Exception, presentationTimeUs: Long = androidx.media3.common.C.TIME_UNSET) {
        val wrapped = e as? VideoFrameProcessingException ?: VideoFrameProcessingException(e, presentationTimeUs)
        errorExecutor.execute { errorListener.onError(wrapped) }
    }

    private companion object {
        /** Output frames that may be waiting downstream at once. */
        const val OUTPUT_FRAMES = 2
    }
}

/**
 * Measures the motion between two frames and draws frames between them.
 *
 * The motion is found on small greyscale copies, coarse to fine: a pyramid
 * of halved pictures, and at each size a few rounds of Lucas–Kanade, each
 * correcting the motion handed up from the size below. It is measured
 * *symmetrically* — for a point in the frame halfway between, where it came
 * from in the earlier frame and where it goes in the later — so the field is
 * laid out on the frame being made and has no holes where things moved away.
 *
 * Motion is kept as a fraction of the frame in two 16-bit numbers packed into
 * an ordinary 8-bit texture, so nothing here needs float render targets,
 * which not every phone's GPU can draw to.
 *
 * Where the two frames still disagree after being moved, the motion is not
 * to be trusted (something was uncovered, or moved too far to follow): there
 * the nearer source frame is shown as it is. If they disagree nearly
 * everywhere, it is a cut, and the whole frame is the nearer one.
 */
private class OpticalFlow(private val useHdr: Boolean) {
    private class Level(val w: Int, val h: Int, val luma: GlTextureInfo, val picture: Array<GlTextureInfo>, val motion: Array<GlTextureInfo>) {
        val texel = floatArrayOf(1f / w, 1f / h)
        fun all() = listOf(luma) + picture + motion
    }

    private val lumaProgram = GlProgram(FLOW_VERTEX_SHADER, FLOW_LUMA_SHADER).also(::fullFrame)
    private val halveProgram = GlProgram(FLOW_VERTEX_SHADER, FLOW_HALVE_SHADER).also(::fullFrame)
    private val gradientProgram = GlProgram(FLOW_VERTEX_SHADER, FLOW_GRADIENT_SHADER).also(::fullFrame)
    private val motionProgram = GlProgram(FLOW_VERTEX_SHADER, FLOW_MOTION_SHADER).also(::fullFrame)
    private val residualProgram = GlProgram(FLOW_VERTEX_SHADER, FLOW_RESIDUAL_SHADER).also(::fullFrame)
    private val meanProgram = GlProgram(FLOW_VERTEX_SHADER, FLOW_MEAN_SHADER).also(::fullFrame)
    private val blendProgram = GlProgram(FLOW_VERTEX_SHADER, FLOW_BLEND_SHADER).also(::fullFrame)

    private var levels = emptyList<Level>()
    private var residual: GlTextureInfo? = null
    private var mean: GlTextureInfo? = null
    private val pictureValid = booleanArrayOf(false, false)

    /** The finished motion field, at the largest level. */
    private var motion: GlTextureInfo? = null

    fun configure(gl: GlObjectsProvider, width: Int, height: Int) {
        releaseTextures()
        val sizes = levelSizes(width, height)
        levels = sizes.map { (w, h) ->
            Level(
                w, h,
                luma = texture(gl, w, h, false),
                picture = Array(2) { texture(gl, w, h, false) },
                motion = Array(2) { texture(gl, w, h, false) },
            )
        }
        val r = levels[min(RESIDUAL_LEVEL, levels.lastIndex)]
        residual = texture(gl, r.w, r.h, false)
        mean = texture(gl, 1, 1, false)
        pictureValid.fill(false)
    }

    /** Frame slot [slot] holds a new picture. */
    fun invalidate(slot: Int) {
        pictureValid[slot] = false
    }

    fun estimate(earlier: GlTextureInfo, earlierSlot: Int, later: GlTextureInfo, laterSlot: Int) {
        prepare(earlier, earlierSlot)
        prepare(later, laterSlot)
        var from: GlTextureInfo? = null
        for (i in levels.indices.reversed()) {
            val level = levels[i]
            val rounds = if (i == levels.lastIndex) COARSEST_ROUNDS else ROUNDS
            for (round in 0 until rounds) {
                val target = level.motion[round % 2]
                // The first round at the smallest size starts from no motion; the texture bound is not read.
                val source = from ?: level.motion[1 - round % 2]
                val known = from != null
                draw(motionProgram, target) {
                    motionProgram.setSamplerTexIdUniform("uEarlier", level.picture[earlierSlot].texId, 0)
                    motionProgram.setSamplerTexIdUniform("uLater", level.picture[laterSlot].texId, 1)
                    motionProgram.setSamplerTexIdUniform("uMotion", source.texId, 2)
                    motionProgram.setFloatUniform("uKnown", if (known) 1f else 0f)
                    motionProgram.setFloatsUniform("uTexel", level.texel)
                }
                from = target
            }
        }
        motion = from
        val r = levels[min(RESIDUAL_LEVEL, levels.lastIndex)]
        draw(residualProgram, residual!!) {
            residualProgram.setSamplerTexIdUniform("uEarlier", r.picture[earlierSlot].texId, 0)
            residualProgram.setSamplerTexIdUniform("uLater", r.picture[laterSlot].texId, 1)
            residualProgram.setSamplerTexIdUniform("uMotion", motion!!.texId, 2)
            residualProgram.setFloatsUniform("uTexel", r.texel)
        }
        draw(meanProgram, mean!!) { meanProgram.setSamplerTexIdUniform("uSource", residual!!.texId, 0) }
    }

    fun blend(earlier: GlTextureInfo, later: GlTextureInfo, fraction: Float, target: GlTextureInfo) {
        draw(blendProgram, target) {
            blendProgram.setSamplerTexIdUniform("uEarlier", earlier.texId, 0)
            blendProgram.setSamplerTexIdUniform("uLater", later.texId, 1)
            blendProgram.setSamplerTexIdUniform("uMotion", motion!!.texId, 2)
            blendProgram.setSamplerTexIdUniform("uResidual", residual!!.texId, 3)
            blendProgram.setSamplerTexIdUniform("uMean", mean!!.texId, 4)
            blendProgram.setFloatUniform("uFraction", fraction)
            blendProgram.setFloatUniform("uLinear", if (useHdr) 1f else 0f)
        }
    }

    /** The greyscale pyramid of one frame, with its slopes: what the motion is measured on. */
    private fun prepare(frame: GlTextureInfo, slot: Int) {
        if (pictureValid[slot]) return
        levels.forEachIndexed { i, level ->
            if (i == 0) {
                draw(lumaProgram, level.luma) {
                    lumaProgram.setSamplerTexIdUniform("uSource", frame.texId, 0)
                    lumaProgram.setFloatsUniform("uStep", floatArrayOf(level.texel[0] / 4f, level.texel[1] / 4f))
                    lumaProgram.setFloatUniform("uLinear", if (useHdr) 1f else 0f)
                }
            } else {
                draw(halveProgram, level.luma) {
                    halveProgram.setSamplerTexIdUniform("uSource", levels[i - 1].luma.texId, 0)
                    halveProgram.setFloatsUniform("uStep", floatArrayOf(level.texel[0] / 4f, level.texel[1] / 4f))
                }
            }
            draw(gradientProgram, level.picture[slot]) {
                gradientProgram.setSamplerTexIdUniform("uSource", level.luma.texId, 0)
                gradientProgram.setFloatsUniform("uTexel", level.texel)
            }
        }
        pictureValid[slot] = true
    }

    private fun releaseTextures() {
        (levels.flatMap { it.all() } + listOfNotNull(residual, mean)).forEach { it.release() }
        levels = emptyList()
        residual = null
        mean = null
        motion = null
    }

    fun release() {
        releaseTextures()
        listOf(lumaProgram, halveProgram, gradientProgram, motionProgram, residualProgram, meanProgram, blendProgram)
            .forEach { it.delete() }
    }

    companion object {
        /** Lucas–Kanade rounds at each size, and at the smallest, where the motion starts from nothing. */
        private const val ROUNDS = 3
        private const val COARSEST_ROUNDS = 8

        /** The level the leftover difference is judged at: fine enough to be local, coarse enough to be smooth. */
        private const val RESIDUAL_LEVEL = 1

        /**
         * The sizes motion is measured at, largest first: the frame scaled so
         * its long side is at most [FLOW_LONG_SIDE], then halved while the
         * short side stays at least [FLOW_MIN_SIDE]. Measuring at full size
         * would cost far more and follow motion no better.
         */
        fun levelSizes(width: Int, height: Int): List<Pair<Int, Int>> {
            val scale = min(1f, FLOW_LONG_SIDE.toFloat() / max(width, height))
            var w = max(FLOW_MIN_SIDE, (width * scale).roundToInt())
            var h = max(FLOW_MIN_SIDE, (height * scale).roundToInt())
            return buildList {
                add(w to h)
                while (size < FLOW_MAX_LEVELS && min(w, h) / 2 >= FLOW_MIN_SIDE) {
                    w /= 2
                    h /= 2
                    add(w to h)
                }
            }
        }

        private const val FLOW_LONG_SIDE = 640
        private const val FLOW_MIN_SIDE = 10
        private const val FLOW_MAX_LEVELS = 6
    }
}

private fun texture(gl: GlObjectsProvider, w: Int, h: Int, highPrecision: Boolean): GlTextureInfo =
    gl.createBuffersForTexture(GlUtil.createTexture(w, h, highPrecision), w, h)

/** The whole frame, -1..1 on each axis. */
private fun fullFrame(program: GlProgram) {
    program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
}

/** Draws [program] over the whole of [target]. Every pixel is written, so nothing is cleared first. */
private inline fun draw(program: GlProgram, target: GlTextureInfo, uniforms: () -> Unit) {
    GlUtil.focusFramebufferUsingCurrentContext(target.fboId, target.width, target.height)
    // An overlay effect earlier in the chain may have left blending on.
    GLES20.glDisable(GLES20.GL_BLEND)
    program.use()
    uniforms()
    program.bindAttributesAndUniforms()
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    GlUtil.checkGlError()
}

internal const val COPY_VERTEX_SHADER = """#version 100
attribute vec4 aFramePosition;
varying vec2 vUv;
void main() {
  gl_Position = aFramePosition;
  vUv = aFramePosition.xy * 0.5 + 0.5;
}
"""

internal const val COPY_FRAGMENT_SHADER = """#version 100
precision highp float;
uniform sampler2D uTexSampler;
varying vec2 vUv;
void main() {
  gl_FragColor = texture2D(uTexSampler, vUv);
}
"""

internal const val FLOW_VERTEX_SHADER = """#version 300 es
in vec4 aFramePosition;
out vec2 vUv;
void main() {
  gl_Position = aFramePosition;
  vUv = aFramePosition.xy * 0.5 + 0.5;
}
"""

/**
 * The frame, shrunk to greyscale. Sixteen taps spread over each output
 * pixel's footprint, each of them averaging four source pixels, so a 4K frame
 * comes down without shimmer. Linear-light (HDR) input is square-rooted:
 * motion is matched on something close to what the eye sees.
 */
internal const val FLOW_LUMA_SHADER = """#version 300 es
precision highp float;
uniform sampler2D uSource;
uniform vec2 uStep;
uniform float uLinear;
in vec2 vUv;
out vec4 outColor;
void main() {
  float sum = 0.0;
  for (int j = 0; j < 4; j++) {
    for (int i = 0; i < 4; i++) {
      vec3 c = clamp(texture(uSource, vUv + (vec2(float(i), float(j)) - 1.5) * uStep).rgb, 0.0, 1.0);
      float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
      sum += uLinear > 0.5 ? sqrt(y) : y;
    }
  }
  outColor = vec4(vec3(sum / 16.0), 1.0);
}
"""

/** Half the size: four taps, each averaging four pixels of the level above. */
internal const val FLOW_HALVE_SHADER = """#version 300 es
precision highp float;
uniform sampler2D uSource;
uniform vec2 uStep;
in vec2 vUv;
out vec4 outColor;
void main() {
  float sum = texture(uSource, vUv + vec2(-uStep.x, -uStep.y)).r
      + texture(uSource, vUv + vec2(uStep.x, -uStep.y)).r
      + texture(uSource, vUv + vec2(-uStep.x, uStep.y)).r
      + texture(uSource, vUv + vec2(uStep.x, uStep.y)).r;
  outColor = vec4(vec3(sum * 0.25), 1.0);
}
"""

/**
 * Brightness in red, and its slope across and down in green and blue, offset
 * by a half to fit an unsigned texture. Slopes are per pixel of this level.
 * Kept together so one filtered read gives all three at any point.
 */
internal const val FLOW_GRADIENT_SHADER = """#version 300 es
precision highp float;
uniform sampler2D uSource;
uniform vec2 uTexel;
in vec2 vUv;
out vec4 outColor;
void main() {
  float c = texture(uSource, vUv).r;
  float dx = texture(uSource, vUv + vec2(uTexel.x, 0.0)).r - texture(uSource, vUv - vec2(uTexel.x, 0.0)).r;
  float dy = texture(uSource, vUv + vec2(0.0, uTexel.y)).r - texture(uSource, vUv - vec2(0.0, uTexel.y)).r;
  outColor = vec4(c, dx * 0.5 + 0.5, dy * 0.5 + 0.5, 1.0);
}
"""

/** Shared by the shaders that read the packed motion field. */
private const val FLOW_MOTION_READ = """
// Two 16-bit numbers in four bytes: motion as a fraction of the frame, -0.5..0.5.
vec2 unpackMotion(vec4 p) {
  vec4 b = floor(p * 255.0 + 0.5);
  return vec2(b.r * 256.0 + b.g, b.b * 256.0 + b.a) / 65535.0 - 0.5;
}
// Packed numbers cannot be filtered by the GPU, so the four neighbours are read and mixed here.
vec2 motionAt(sampler2D field, vec2 uv) {
  ivec2 size = textureSize(field, 0);
  vec2 p = uv * vec2(size) - 0.5;
  vec2 base = floor(p);
  vec2 f = p - base;
  ivec2 lo = clamp(ivec2(base), ivec2(0), size - 1);
  ivec2 hi = clamp(ivec2(base) + 1, ivec2(0), size - 1);
  vec2 a = unpackMotion(texelFetch(field, lo, 0));
  vec2 b = unpackMotion(texelFetch(field, ivec2(hi.x, lo.y), 0));
  vec2 c = unpackMotion(texelFetch(field, ivec2(lo.x, hi.y), 0));
  vec2 d = unpackMotion(texelFetch(field, hi, 0));
  return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
"""

/**
 * One round of symmetric Lucas–Kanade. With motion m so far, a point q of the
 * frame halfway between is q - m/2 in the earlier frame and q + m/2 in the
 * later. Over a 5×5 window the correction that best cancels the difference
 * between those two is the solution of a 2×2 system built from the slopes;
 * `DAMPING` keeps it from running away where the picture is flat and the
 * slopes say nothing, and each round moves at most `MAX_STEP` pixels.
 */
internal const val FLOW_MOTION_SHADER = """#version 300 es
precision highp float;
uniform sampler2D uEarlier;
uniform sampler2D uLater;
uniform sampler2D uMotion;
uniform float uKnown;
uniform vec2 uTexel;
in vec2 vUv;
out vec4 outColor;
const float DAMPING = 0.0001;
const float MAX_STEP = 2.0;
""" + FLOW_MOTION_READ + """
vec4 packMotion(vec2 m) {
  vec2 v = floor(clamp(m + 0.5, 0.0, 1.0) * 65535.0 + 0.5);
  vec2 hi = floor(v / 256.0);
  vec2 lo = v - hi * 256.0;
  return vec4(hi.x, lo.x, hi.y, lo.y) / 255.0;
}
void main() {
  vec2 m = uKnown > 0.5 ? motionAt(uMotion, vUv) : vec2(0.0);
  float sxx = DAMPING;
  float syy = DAMPING;
  float sxy = 0.0;
  float bx = 0.0;
  float by = 0.0;
  for (int j = -2; j <= 2; j++) {
    for (int i = -2; i <= 2; i++) {
      vec2 q = vUv + vec2(float(i), float(j)) * uTexel;
      vec3 a = texture(uEarlier, q - 0.5 * m).rgb;
      vec3 b = texture(uLater, q + 0.5 * m).rgb;
      // The difference changes by the mean of the two slopes for each pixel of motion.
      vec2 g = 0.5 * (a.gb + b.gb) - 0.5;
      float d = b.r - a.r;
      sxx += g.x * g.x;
      syy += g.y * g.y;
      sxy += g.x * g.y;
      bx += g.x * d;
      by += g.y * d;
    }
  }
  float det = sxx * syy - sxy * sxy;
  vec2 step = -vec2(syy * bx - sxy * by, sxx * by - sxy * bx) / det;
  float len = length(step);
  if (len > MAX_STEP) {
    step *= MAX_STEP / len;
  }
  outColor = packMotion(m + step * uTexel);
}
"""

/**
 * How far the two frames still disagree once each has been moved to the
 * halfway point, as a share of how much the picture varies there: `DOUBT_SCALE`
 * of the leftover difference over the local slope, lightly blurred. A
 * difference of 0.05 is nothing across a hard edge and everything on a plain
 * wall, so the difference alone cannot say whether the motion was followed.
 * `FLOOR` stands in for the slope of camera noise, which is in every frame
 * and is not a mistake.
 */
internal const val FLOW_RESIDUAL_SHADER = """#version 300 es
precision highp float;
uniform sampler2D uEarlier;
uniform sampler2D uLater;
uniform sampler2D uMotion;
uniform vec2 uTexel;
in vec2 vUv;
out vec4 outColor;
const float FLOOR = 0.03;
const float DOUBT_SCALE = 0.25;
""" + FLOW_MOTION_READ + """
// x: leftover difference; y: slope of the picture.
vec2 left(vec2 uv) {
  vec2 m = motionAt(uMotion, uv);
  vec3 a = texture(uEarlier, uv - 0.5 * m).rgb;
  vec3 b = texture(uLater, uv + 0.5 * m).rgb;
  return vec2(abs(b.r - a.r), 0.5 * (length(a.gb - 0.5) + length(b.gb - 0.5)));
}
void main() {
  vec2 o = uTexel * 1.5;
  vec2 r = left(vUv) * 2.0 + left(vUv + vec2(o.x, 0.0)) + left(vUv - vec2(o.x, 0.0))
      + left(vUv + vec2(0.0, o.y)) + left(vUv - vec2(0.0, o.y));
  r /= 6.0;
  outColor = vec4(vec3(clamp(DOUBT_SCALE * r.x / (r.y + FLOOR), 0.0, 1.0)), 1.0);
}
"""

/** The whole of the leftover difference as one number, into a single pixel: 256 filtered taps on an even grid. */
internal const val FLOW_MEAN_SHADER = """#version 300 es
precision highp float;
uniform sampler2D uSource;
out vec4 outColor;
void main() {
  float sum = 0.0;
  for (int j = 0; j < 16; j++) {
    for (int i = 0; i < 16; i++) {
      sum += texture(uSource, (vec2(float(i), float(j)) + 0.5) / 16.0).r;
    }
  }
  outColor = vec4(vec3(sum / 256.0), 1.0);
}
"""

/**
 * The frame at `uFraction` of the way from the earlier to the later. Each is
 * read where the motion says this point was, or will be, and the two are
 * mixed by how near each is in time.
 *
 *  - Where the two reads disagree, one of them is showing something the other
 *    cannot see; the nearer frame is believed.
 *  - A read from outside the frame is not used at all.
 *  - Where the leftover difference says the motion is wrong (`uResidual`), or
 *    the frames are a cut (`uMean`), the nearer source frame is shown as it
 *    is: a repeated frame is a smaller fault than a smeared one.
 */
internal const val FLOW_BLEND_SHADER = """#version 300 es
precision highp float;
uniform sampler2D uEarlier;
uniform sampler2D uLater;
uniform sampler2D uMotion;
uniform sampler2D uResidual;
uniform sampler2D uMean;
uniform float uFraction;
uniform float uLinear;
in vec2 vUv;
out vec4 outColor;
const float DOUBT_FROM = 0.03;
const float DOUBT_TO = 0.08;
const float CUT = 0.035;
""" + FLOW_MOTION_READ + """
float seen(vec4 c) {
  float y = dot(clamp(c.rgb, 0.0, 1.0), vec3(0.2126, 0.7152, 0.0722));
  return uLinear > 0.5 ? sqrt(y) : y;
}
float inside(vec2 uv) {
  vec2 s = step(vec2(0.0), uv) * step(uv, vec2(1.0));
  return s.x * s.y;
}
void main() {
  vec2 m = motionAt(uMotion, vUv);
  vec2 from = vUv - uFraction * m;
  vec2 to = vUv + (1.0 - uFraction) * m;
  vec4 a = texture(uEarlier, from);
  vec4 b = texture(uLater, to);
  float nearer = step(0.5, uFraction);
  float w = mix(uFraction, nearer, smoothstep(0.08, 0.30, abs(seen(a) - seen(b))));
  float ia = inside(from);
  float ib = inside(to);
  if (ia + ib == 1.0) {
    w = ib;
  }
  vec4 moved = mix(a, b, w);
  vec4 still = mix(texture(uEarlier, vUv), texture(uLater, vUv), nearer);
  float doubt = smoothstep(DOUBT_FROM, DOUBT_TO, texture(uResidual, vUv).r);
  doubt = max(doubt, step(CUT, texture(uMean, vec2(0.5)).r));
  outColor = mix(moved, still, doubt);
}
"""
