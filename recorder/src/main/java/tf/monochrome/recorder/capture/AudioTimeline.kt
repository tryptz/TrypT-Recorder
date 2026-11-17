package tf.monochrome.recorder.capture

/**
 * Stamps captured audio on the monotonic clock and keeps it there.
 *
 * Audio is stamped by counting samples from an anchor, as Android's own
 * MediaRecorder does: sample counts are exact where the monotonic clock seen by
 * a thread is not. Counting alone has two failure modes, and both shift every
 * later sample against the picture: a stall longer than AudioRecord's buffer
 * loses samples (later audio comes out early), and the audio clock drifts from
 * the monotonic one by tens of ppm (hundreds of milliseconds over hours).
 *
 * The rule that catches both: a block cannot be read before it was captured,
 * so its first sample was captured no later than `now − its length`
 * (the *read start*). Then
 *  - a stamp more than [toleranceUs] *after* the read start is impossible,
 *    and frames are dropped from the block until it fits;
 *  - a stamp consistently more than [toleranceUs] *before* the read start —
 *    for every block of a window longer than AudioRecord's buffer, so a
 *    backlog being read after a short stall (late reads, nothing lost) does not
 *    count — means samples are missing, and that much silence is written
 *    first.
 * The stream stays continuous, which every player handles; a jump in the
 * timestamps alone would be smoothed over by players that clock from the
 * audio. Pure arithmetic, JVM-tested.
 */
class AudioTimeline(
    private val sampleRate: Int,
    private val toleranceUs: Long = 30_000,
    /** Blocks the "consistently behind" verdict looks back over; must span more than the capture buffer. */
    private val windowBlocks: Int = 48,
    /** A gap longer than this is not filled with silence; the stamps start again from the read. */
    private val maxGapUs: Long = 10_000_000,
) {
    private var anchorUs = -1L
    private var frames = 0L
    private val lags = LongArray(windowBlocks)
    private var lagCount = 0
    private var lagNext = 0

    val isAnchored: Boolean get() = anchorUs >= 0

    /** Forget the anchor (a pause); the next block anchors afresh. */
    fun reset() {
        anchorUs = -1L
        frames = 0
        clearWindow()
    }

    /** Timestamp of the next frame to be written. */
    fun nextUs(): Long = anchorUs + frames * 1_000_000L / sampleRate

    /** [n] frames were written at [nextUs]. */
    fun advance(n: Int) {
        frames += n
    }

    /**
     * A block of [blockFrames] frames was read at [nowUs]; the anchor is never
     * put before [notBeforeUs] (the end of the last pause). Returns how to
     * keep it in step: above 0, write that many frames of silence before it;
     * below 0, drop that many frames from its start; 0, write it as it is.
     */
    fun onBlock(nowUs: Long, blockFrames: Int, notBeforeUs: Long): Int {
        val readStartUs = nowUs - blockFrames * 1_000_000L / sampleRate
        if (anchorUs < 0) {
            anchorUs = maxOf(readStartUs, notBeforeUs)
            frames = 0
            clearWindow()
            return 0
        }
        val lagUs = readStartUs - nextUs()
        if (lagUs < -toleranceUs) {
            clearWindow()
            return -minOf(blockFrames.toLong(), framesIn(-lagUs)).toInt()
        }
        lags[lagNext] = lagUs
        lagNext = (lagNext + 1) % windowBlocks
        if (lagCount < windowBlocks) lagCount++
        if (lagCount < windowBlocks) return 0
        val least = lags.min()
        if (least <= toleranceUs) return 0
        clearWindow()
        if (least > maxGapUs) {
            anchorUs = readStartUs
            frames = 0
            return 0
        }
        return framesIn(least).toInt()
    }

    private fun framesIn(us: Long): Long = us * sampleRate / 1_000_000L

    private fun clearWindow() {
        lagCount = 0
        lagNext = 0
    }
}
