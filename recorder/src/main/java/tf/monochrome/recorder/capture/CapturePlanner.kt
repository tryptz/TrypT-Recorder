package tf.monochrome.recorder.capture

import tf.monochrome.recorder.settings.QualityPreset
import tf.monochrome.recorder.settings.ResolutionPreset
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Width, height, frame rate and bitrate the encoder is configured with. */
data class VideoPlan(val width: Int, val height: Int, val fps: Int, val bitrate: Int)

/**
 * Pure sizing arithmetic for a capture, kept free of android.* so it is
 * covered by JVM tests. [EncoderFit] applies the device's encoder limits on
 * top of what this produces.
 */
object CapturePlanner {
    /** Rotation constants as in android.view.Surface.ROTATION_*. */
    private const val ROTATION_90 = 1
    private const val ROTATION_270 = 3

    /** Short sides tried, in order, when an encoder refuses a size. */
    val FALLBACK_SHORT_SIDES = listOf(1440, 1080, 720, 540, 480, 360)

    /** Frame rates stepped down through when an encoder refuses the one asked for. */
    val FALLBACK_RATES = listOf(144, 120, 90, 60, 30)

    /** Below this short side, frame rate gives way before resolution does. */
    private const val KEEP_RATE_DOWN_TO = 720

    /**
     * The panel's mode size is reported in its natural orientation. The
     * debug recorder in :app always used it as-is, so a phone held sideways
     * still produced a portrait file with the picture squeezed into it. The
     * capture is oriented the way the screen is when recording starts.
     */
    fun orientedSize(physicalWidth: Int, physicalHeight: Int, rotation: Int): Pair<Int, Int> =
        if (rotation == ROTATION_90 || rotation == ROTATION_270) {
            physicalHeight to physicalWidth
        } else {
            physicalWidth to physicalHeight
        }

    /** Scales [width]x[height] so its short side is at most [shortSide], keeping the aspect. Never upscales. */
    fun capShortSide(width: Int, height: Int, shortSide: Int?, alignment: Int = 2): Pair<Int, Int> {
        val currentShort = min(width, height)
        if (shortSide == null || shortSide >= currentShort) {
            return align(width, alignment) to align(height, alignment)
        }
        val scale = shortSide.toDouble() / currentShort
        return align((width * scale).roundToInt(), alignment) to align((height * scale).roundToInt(), alignment)
    }

    /** Rounds down to a multiple of [alignment], never below one step. Encoders want even sizes at least. */
    fun align(value: Int, alignment: Int): Int {
        val a = max(alignment, 2)
        return max(a, value / a * a)
    }

    /** The requested rate, capped by the fastest the panel can refresh. */
    fun frameRate(requested: Int, displayRefreshHz: Float): Int {
        val panel = displayRefreshHz.roundToInt().coerceAtLeast(24)
        return requested.coerceIn(15, panel)
    }

    /**
     * The fastest refresh the panel offers at its current resolution. Not the
     * *current* rate: adaptive 120/144/165 Hz panels idle at 60 (or lower)
     * whenever nothing moves — on the consent dialog, say — and capping by
     * that would quietly turn a 165 fps setting into 60. [modes] are
     * (width, height, refresh Hz).
     */
    fun maxRefreshHz(modes: List<Triple<Int, Int, Float>>, width: Int, height: Int, current: Float): Float =
        modes.filter { (w, h, _) -> w == width && h == height }.maxOfOrNull { it.third }?.coerceAtLeast(current) ?: current

    /**
     * Bits per pixel per frame, capped. The debug recorder's 0.08 is the
     * Medium preset; it is plenty for UI content at native resolution.
     */
    fun bitrate(width: Int, height: Int, fps: Int, quality: QualityPreset): Int =
        (width.toLong() * height * fps * quality.bitsPerPixel)
            .toLong()
            .coerceIn(1_000_000L, 60_000_000L)
            .toInt()

    /** First plan for this display and these settings, before encoder limits. */
    fun plan(
        displayWidth: Int,
        displayHeight: Int,
        displayRefreshHz: Float,
        resolution: ResolutionPreset,
        requestedFps: Int,
        quality: QualityPreset,
    ): VideoPlan {
        val (w, h) = capShortSide(displayWidth, displayHeight, resolution.shortSide)
        val fps = frameRate(requestedFps, displayRefreshHz)
        return VideoPlan(w, h, fps, bitrate(w, h, fps, quality))
    }

    /**
     * Lesser versions of [plan] to try, in order, when the encoder refuses it.
     *
     * A high frame rate is the point of asking for one, so it is held while
     * resolution comes down to 720p; only then does the rate step down
     * (144, 120, 90, 60, 30), each again from full size. Below 720p only 30
     * fps is tried — the last resort every encoder manages.
     */
    fun fallbacks(plan: VideoPlan, quality: QualityPreset): List<VideoPlan> {
        val short = min(plan.width, plan.height)
        val smaller = FALLBACK_SHORT_SIDES.filter { it < short }.map { capShortSide(plan.width, plan.height, it) }
        val full = plan.width to plan.height
        val keepRate = listOf(full) + smaller.filter { (w, h) -> min(w, h) >= KEEP_RATE_DOWN_TO }
        val small = smaller.filter { (w, h) -> min(w, h) < KEEP_RATE_DOWN_TO }
        val rates = listOf(plan.fps) + FALLBACK_RATES.filter { it < plan.fps }
        val candidates = rates.flatMap { fps -> keepRate.map { (w, h) -> Triple(w, h, fps) } } +
            small.map { (w, h) -> Triple(w, h, min(30, plan.fps)) }
        return candidates
            .distinct()
            .filterNot { (w, h, fps) -> w == plan.width && h == plan.height && fps == plan.fps }
            .map { (w, h, fps) -> VideoPlan(w, h, fps, bitrate(w, h, fps, quality)) }
    }
}
