package tf.monochrome.recorder.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureStatusTest {
    @Test
    fun `elapsed time excludes pauses`() {
        val start = CaptureStatus.Recording(startedAtMs = 1_000)
        assertEquals(4_000L, start.elapsedMs(5_000))
        val paused = start.paused(5_000)
        assertEquals(4_000L, paused.elapsedMs(9_000)) // frozen while paused
        val resumed = paused.resumed(9_000)
        assertEquals(4_000L, resumed.pausedTotalMs)
        assertEquals(6_000L, resumed.elapsedMs(11_000))
    }

    @Test
    fun `durations format as m-ss and h-mm-ss`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("1:00:01", formatDuration(3_601_000))
    }
}
