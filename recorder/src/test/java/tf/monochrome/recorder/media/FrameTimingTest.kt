package tf.monochrome.recorder.media

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameTimingTest {
    private fun frames(fps: Int, count: Int, from: Long = 0) = (0 until count).map { from + it * 1_000_000L / fps }

    @Test
    fun `a steady stream reads at its rate`() {
        assertEquals(165f, FrameTiming.peakFrameRate(frames(165, 300)), 1f)
        assertEquals(60f, FrameTiming.peakFrameRate(frames(60, 300)), 0.5f)
    }

    @Test
    fun `idle stretches in a screen recording do not drag the rate down`() {
        // 2 s of motion at 165 fps, 10 s of a still screen (one frame), more motion.
        val times = frames(165, 330) + listOf(12_000_000L) + frames(165, 330, from = 12_100_000)
        // The average would be ~55 fps; the motion was 165.
        assertEquals(165f, FrameTiming.peakFrameRate(times), 1f)
    }

    @Test
    fun `order and duplicates do not matter`() {
        val t = frames(120, 100)
        assertEquals(120f, FrameTiming.peakFrameRate((t + t.take(5)).shuffled()), 1f)
    }

    @Test
    fun `too few frames to tell`() {
        assertEquals(0f, FrameTiming.peakFrameRate(emptyList()), 0f)
        assertEquals(0f, FrameTiming.peakFrameRate(listOf(0L, 33_000L)), 0f)
    }
}
