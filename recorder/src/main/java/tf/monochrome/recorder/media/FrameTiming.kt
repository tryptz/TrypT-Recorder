package tf.monochrome.recorder.media

/**
 * Frame-rate estimation from frame timestamps. Pure, for the JVM tests.
 *
 * A screen recording has a variable frame rate: 165 fps while anything moves,
 * next to nothing while the screen is still. Its average (frames ÷ duration)
 * therefore understates the rate the motion was recorded at, and a bitrate
 * sized from the average starves exactly the parts that need it. The short
 * gaps between consecutive frames tell the real rate.
 */
object FrameTiming {
    /**
     * The frame rate of the moving parts: the gap a tenth of the way up the
     * sorted gaps (the shortest few can be encoder jitter). 0 if there are not
     * enough frames to tell.
     */
    fun peakFrameRate(sampleTimesUs: List<Long>): Float =
        peakFrameRate(sampleTimesUs.toLongArray(), sampleTimesUs.size)

    /** The same over the first [count] entries of [timesUs], without boxing — a long recording has a lot of frames. */
    fun peakFrameRate(timesUs: LongArray, count: Int): Float {
        val sorted = timesUs.copyOf(count).also { it.sort() }
        var n = 0
        val gaps = LongArray(maxOf(count - 1, 0))
        for (i in 1 until count) {
            val gap = sorted[i] - sorted[i - 1]
            if (gap > 0) gaps[n++] = gap
        }
        if (n < 2) return 0f
        gaps.sort(0, n)
        return 1_000_000f / gaps[n / 10]
    }
}
