package tf.monochrome.recorder.editor.export

/** One frame of a fixed-rate export, and which source frames it is made from. */
sealed interface RetimedFrame {
    /** Where the frame lands on the output's fixed grid. */
    val timeUs: Long

    /** The older of the two newest source frames, unchanged. */
    data class Earlier(override val timeUs: Long) : RetimedFrame

    /** The newest source frame, unchanged. */
    data class Later(override val timeUs: Long) : RetimedFrame

    /** A frame synthesised between the two, [fraction] of the way from the earlier to the later. */
    data class Between(override val timeUs: Long, val fraction: Float) : RetimedFrame
}

/**
 * Lays source frames, which arrive whenever the source has them, onto a fixed
 * grid of output times k · [denominator] / [numerator] seconds — the chosen
 * export frame rate. Pure, so every rule below is held by JVM tests;
 * [FrameInterpolation] does what it says on the GPU.
 *
 * The grid counts from zero on the timeline, not from the clip, so every clip
 * of an export lands on the same grid and a cut does not disturb the cadence.
 * A clip owns the grid times from [startUs] up to, not including, [endUs].
 *
 * For each grid time, once the source frame after it has arrived:
 *  - within [snapUs] of a source frame it *is* that frame. Rounding must not
 *    turn a 60 fps source exported at 60 into sixty blends a second;
 *  - when the two source frames around it are more than [holdGapUs] apart the
 *    earlier one is held. A screen recording only has a frame when the screen
 *    changed: two frames a second apart are a still picture and then a
 *    change, not one slow movement to invent frames for;
 *  - otherwise it is made between the two ([RetimedFrame.Between]), or, with
 *    [interpolate] off, it is the nearer of them.
 *
 * Grid times before the first source frame show the first frame, and those
 * after the last show the last, so the picture covers the clip's whole length.
 *
 * Call [offer] for each source frame and [finish] after the last, and drain
 * [poll] after either. Frames come out one at a time because a still tail can
 * be thousands of them.
 */
class FrameRetimer(
    private val numerator: Int,
    private val denominator: Int,
    private val startUs: Long,
    private val endUs: Long,
    private val holdGapUs: Long,
    private val interpolate: Boolean,
) {
    init {
        require(numerator > 0 && denominator > 0) { "rate $numerator/$denominator" }
    }

    /** How close a grid time must be to a source frame to simply be that frame. */
    val snapUs: Long = 1_000_000L * denominator / numerator / 8

    private var earlierUs = NONE
    private var laterUs = NONE
    private var finished = false
    private var next = indexAtOrAfter(startUs - snapUs)

    fun gridUs(index: Long): Long = index * 1_000_000L * denominator / numerator

    private fun indexAtOrAfter(timeUs: Long): Long {
        var k = (timeUs.coerceAtLeast(0) * numerator / (1_000_000L * denominator) - 1).coerceAtLeast(0)
        while (gridUs(k) < timeUs) k++
        return k
    }

    /**
     * Takes the source frame at [timeUs]. False when it is no later than the
     * one before it, which is then kept instead: time only moves forward.
     */
    fun offer(timeUs: Long): Boolean {
        check(!finished) { "offer after finish" }
        if (laterUs != NONE && timeUs <= laterUs) return false
        earlierUs = laterUs
        laterUs = timeUs
        return true
    }

    /** No more source frames: the last one is held to the end of the clip. */
    fun finish() {
        finished = true
    }

    /** The next output frame, or null when it needs a source frame that has not arrived (or the clip is done). */
    fun poll(): RetimedFrame? {
        val later = laterUs
        if (later == NONE) return null
        val g = gridUs(next)
        if (g >= endUs - snapUs) return null
        if (g > later + snapUs && !finished) return null
        next++
        val earlier = earlierUs
        return when {
            earlier == NONE || later - g <= snapUs -> RetimedFrame.Later(g)
            g - earlier <= snapUs -> RetimedFrame.Earlier(g)
            later - earlier > holdGapUs -> RetimedFrame.Earlier(g)
            interpolate -> RetimedFrame.Between(g, (g - earlier).toFloat() / (later - earlier))
            g - earlier < later - g -> RetimedFrame.Earlier(g)
            else -> RetimedFrame.Later(g)
        }
    }

    /**
     * Forgets the source frames, for a new stream. The grid position is kept:
     * an output time is never produced twice.
     */
    fun reset() {
        earlierUs = NONE
        laterUs = NONE
        finished = false
    }

    companion object {
        private const val NONE = Long.MIN_VALUE

        /**
         * Source frames further apart than this, at normal speed, are a held
         * picture rather than motion: slower than 8 frames a second.
         */
        const val HOLD_GAP_US = 125_000L

        /**
         * [HOLD_GAP_US] for a clip whose slowest speed is [lowestRate]: slowed
         * to a quarter, a 30 fps clip's frames arrive 133 ms apart and are
         * exactly the ones worth making frames between.
         */
        fun holdGapUs(lowestRate: Float): Long =
            (HOLD_GAP_US / lowestRate.coerceIn(0.01f, 100f)).toLong()
    }
}
