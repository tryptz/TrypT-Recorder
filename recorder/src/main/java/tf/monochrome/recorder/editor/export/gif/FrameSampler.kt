package tf.monochrome.recorder.editor.export.gif

import kotlin.math.max

/**
 * Lays a video's frames onto a GIF's fixed grid of frame times, k / [fps].
 *
 * A screen recording has a variable frame rate: a frame stays on screen until
 * the next one arrives, however long that takes. So GIF frame k shows the
 * latest video frame at or before its time — never the nearest one, which
 * would show a change before it happened.
 *
 * Frames are fed in presentation order. How many GIF frames a video frame
 * fills is only known once the next one's time is, so [arrive] answers for
 * the *previous* frame, and [finish] for the last.
 */
class FrameSampler(private val fps: Int, durationUs: Long) {
    init {
        require(fps in 1..100) { "fps $fps" }
    }

    /** GIF frames in the whole export: enough to cover [durationUs], and at least one. */
    val frameCount: Int = max(1L, (durationUs * fps + 999_999) / 1_000_000).toInt()

    private var next = 0
    private var started = false

    fun timeUs(k: Int): Long = k * 1_000_000L / fps

    /**
     * A video frame at [ptsUs] has arrived. Returns the GIF frames the
     * previous one fills, empty if it fills none (it was replaced before the
     * next GIF frame's time). GIF frames before the very first video frame
     * show that frame.
     */
    fun arrive(ptsUs: Long): IntRange {
        if (!started) {
            started = true
            return IntRange.EMPTY
        }
        val from = next
        while (next < frameCount && timeUs(next) < ptsUs) next++
        return from until next
    }

    /** End of the video: the GIF frames the last video frame fills. */
    fun finish(): IntRange {
        if (!started) return IntRange.EMPTY
        val from = next
        next = frameCount
        return from until next
    }

    /**
     * How long [frames] stay on screen, in the hundredths of a second GIF
     * counts in. Rounded on the running total, so 15 fps comes out 7, 7, 6, …
     * and the whole GIF lasts as long as the video.
     */
    fun delayCs(frames: IntRange): Int = centis(frames.last + 1) - centis(frames.first)

    private fun centis(k: Int): Int = ((k * 100L * 2 + fps) / (fps * 2)).toInt()
}
