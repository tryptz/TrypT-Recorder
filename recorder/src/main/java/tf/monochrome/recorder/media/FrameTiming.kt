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
    fun peakFrameRate(sampleTimesUs: List<Long>): Float {
        val gaps = sampleTimesUs.sorted().zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
        if (gaps.size < 2) return 0f
        val gap = gaps[gaps.size / 10]
        return 1_000_000f / gap
    }
}
