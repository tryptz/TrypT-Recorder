package tf.monochrome.recorder.editor.export

import tf.monochrome.recorder.editor.model.GrainMotion
import tf.monochrome.recorder.editor.model.VideoLook
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Film grain as the shader takes it, every value already in range; see [Grain]. */
data class PlannedGrain(
    val amount: Float,
    val size: Float,
    val sharpness: Float,
    val texture: Float,
    val colour: Float,
    val shadows: Float,
    val midtones: Float,
    val highlights: Float,
    val red: Float,
    val green: Float,
    val blue: Float,
    val motion: GrainMotion,
    /** Preview only: the grain alone, on mid grey, to judge it by. Never exported. */
    val solo: Boolean = false,
)

/**
 * The numbers behind the film grain effect ([FilmGrain] draws it). Pure, so
 * the rules are held by JVM tests; the shader computes the same response.
 *
 * Grain is Gaussian, sized against the frame rather than in pixels — so the
 * 720p preview carries the same grain as a full-size export — and seeded from
 * the frame's time, so preview and export grain alike.
 */
object Grain {
    /** Standard deviation at full amount, as a share of full scale, where the response peaks. */
    const val MAX_STD = 0.10f

    /** Grain size at 0 and 1, in pixels of a 1080-pixel short side. */
    private const val FINEST = 1f
    private const val COARSEST = 4f
    private const val REFERENCE_SHORT_SIDE = 1080f

    /** Per-channel strength runs 0..this. */
    const val MAX_CHANNEL = 2f

    /** [look]'s grain held to its ranges, or null if there is none. */
    fun plan(look: VideoLook): PlannedGrain? {
        if (look.grain <= 0f) return null
        val d = look.grainDetail
        return PlannedGrain(
            amount = look.grain.coerceAtMost(1f),
            size = look.grainSize.coerceIn(0f, 1f),
            sharpness = d.sharpness.coerceIn(0f, 1f),
            texture = d.texture.coerceIn(0f, 1f),
            colour = d.colour.coerceIn(0f, 1f),
            shadows = d.shadows.coerceIn(0f, 1f),
            midtones = d.midtones.coerceIn(0f, 1f),
            highlights = d.highlights.coerceIn(0f, 1f),
            red = d.red.coerceIn(0f, MAX_CHANNEL),
            green = d.green.coerceIn(0f, MAX_CHANNEL),
            blue = d.blue.coerceIn(0f, MAX_CHANNEL),
            motion = d.motion,
        )
    }

    /** Grain cell size in pixels for a frame [shortSide] pixels on its short side. */
    fun cellPixels(size: Float, shortSide: Int): Float =
        (FINEST + (COARSEST - FINEST) * size.coerceIn(0f, 1f)) * shortSide / REFERENCE_SHORT_SIDE

    /**
     * The standard deviation each pixel gets. A pixel smaller than a grain
     * shows it whole; a pixel larger than one averages several, which is why a
     * scaled-down copy of grainy film looks smoother — so below a pixel the
     * grain fades in proportion, and a small GIF is not grainier than its video.
     */
    fun pixelStd(amount: Float, size: Float, shortSide: Int): Float =
        MAX_STD * amount.coerceIn(0f, 1f) * min(1f, cellPixels(size, shortSide))

    /**
     * How much grain shows at gamma-encoded [luma]: [midtones] at mid grey,
     * [shadows] at black, [highlights] at white, rising between them along a
     * quarter circle — quickly out of the extremes, flat through the middle.
     * The defaults are the first grain's curve: a fifth at black and white,
     * as film shows it, so shadows and highlights are not pushed into clipping.
     */
    fun response(luma: Float, shadows: Float = 0.2f, midtones: Float = 1f, highlights: Float = 0.2f): Float {
        val l = luma.coerceIn(0f, 1f)
        return if (l < 0.5f) shadows + (midtones - shadows) * arc(2f * l)
        else highlights + (midtones - highlights) * arc(2f - 2f * l)
    }

    /** 0 at 0, 1 at 1, along a quarter circle. */
    private fun arc(t: Float): Float = sqrt((t * (2f - t)).coerceIn(0f, 1f))

    /**
     * Sharpness works on the grain's contrast, not its shape: each noise
     * value is raised to this power (keeping its sign). Below 1 that steepens
     * the edges between light and dark grains and flattens their insides —
     * crisp, well-defined grains that stay round. (Sharpening the grid's
     * interpolation instead turns coarse grains into squares.)
     */
    fun sharpPower(sharpness: Float): Float = 1f - 0.6f * sharpness.coerceIn(0f, 1f)

    /**
     * What a standard normal value raised to [power] must be multiplied by to
     * keep a standard deviation of 1, so sharpness does not change strength:
     * 1 / sqrt(E|n|^(2·power)), integrated by Simpson's rule over ±8.
     */
    fun sharpNorm(power: Float): Float {
        val steps = 4_000
        val step = 16.0 / steps
        var sum = 0.0
        for (i in 0..steps) {
            val x = -8.0 + i * step
            val f = abs(x).pow(2.0 * power) * exp(-x * x / 2) / sqrt(2 * PI)
            sum += f * when {
                i == 0 || i == steps -> 1
                i % 2 == 1 -> 4
                else -> 2
            }
        }
        return (1 / sqrt(sum * step / 3)).toFloat()
    }

    /**
     * Where the noise is read from for the frame at [presentationTimeUs]: the
     * same for the same time, so preview and export grain alike, and fresh
     * as often as [motion] says. Each coordinate is in [0, 1000).
     */
    fun seed(presentationTimeUs: Long, motion: GrainMotion = GrainMotion.EVERY_FRAME): Pair<Float, Float> {
        val key = when (val fps = motion.fps) {
            null -> presentationTimeUs
            0 -> 0L
            else -> Math.floorDiv(presentationTimeUs * fps, 1_000_000L)
        }
        // SplitMix64: neighbouring keys land far apart.
        var z = key + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        val x = (z ushr 40).toFloat() / (1 shl 24)
        val y = ((z ushr 16) and 0xFFFFFF).toFloat() / (1 shl 24)
        return x * 1000f to y * 1000f
    }
}
