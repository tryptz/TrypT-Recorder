package tf.monochrome.recorder.editor.export

import kotlin.math.min
import kotlin.math.sqrt

/**
 * The numbers behind the film grain effect ([FilmGrain] draws it). Pure, so
 * the rules are held by JVM tests; the shader computes the same response.
 *
 * Grain is luminance only, Gaussian, strongest in the midtones, a fresh
 * pattern every frame, and sized against the frame rather than in pixels —
 * so the 720p preview carries the same grain as a full-size export.
 */
object Grain {
    /** Standard deviation at full amount, as a share of full scale, where the response peaks. */
    const val MAX_STD = 0.10f

    /** Grain size at 0 and 1, in pixels of a 1080-pixel short side. */
    private const val FINEST = 1f
    private const val COARSEST = 4f
    private const val REFERENCE_SHORT_SIDE = 1080f

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
     * How much grain shows at gamma-encoded [luma]: all of it in the
     * midtones, a fifth at pure black and white — as film shows it, and so
     * highlights and shadows are not pushed into clipping.
     */
    fun response(luma: Float): Float {
        val l = luma.coerceIn(0f, 1f)
        return 0.2f + 0.8f * sqrt((4f * l * (1f - l)).coerceIn(0f, 1f))
    }

    /**
     * Where the noise is read from for the frame at [presentationTimeUs]: a
     * fresh offset per frame, the same for the same time, so preview and
     * export grain alike. Each coordinate is in [0, 1000).
     */
    fun seed(presentationTimeUs: Long): Pair<Float, Float> {
        // SplitMix64: neighbouring times land far apart.
        var z = presentationTimeUs + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        val x = (z ushr 40).toFloat() / (1 shl 24)
        val y = ((z ushr 16) and 0xFFFFFF).toFloat() / (1 shl 24)
        return x * 1000f to y * 1000f
    }
}
