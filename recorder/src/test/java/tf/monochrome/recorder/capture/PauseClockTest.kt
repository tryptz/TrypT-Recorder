package tf.monochrome.recorder.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseClockTest {
    @Test
    fun `no pause leaves timestamps alone`() {
        val c = PauseClock()
        assertEquals(1_000L, c.adjust(1_000L))
        assertEquals(0L, c.pausedTotalUs(5_000L))
    }

    @Test
    fun `samples after a pause are pulled back by its length`() {
        val c = PauseClock()
        c.pause(1_000)
        c.resume(4_000)
        assertEquals(900L, c.adjust(900))
        assertEquals(1_500L, c.adjust(4_500))
        assertEquals(3_000L, c.pausedTotalUs(10_000))
    }

    @Test
    fun `a frame inside a pause clamps to its start rather than being dropped`() {
        val c = PauseClock()
        c.pause(1_000)
        c.resume(4_000)
        assertEquals(1_000L, c.adjust(2_500))
    }

    @Test
    fun `frames during an open pause clamp too`() {
        val c = PauseClock()
        c.pause(1_000)
        assertTrue(c.isPaused)
        assertEquals(1_000L, c.adjust(1_700))
        assertEquals(700L, c.pausedTotalUs(1_700))
    }

    @Test
    fun `several pauses accumulate and output stays monotonic`() {
        val c = PauseClock()
        c.pause(1_000); c.resume(2_000)
        c.pause(5_000); c.resume(9_000)
        val input = listOf(500L, 999L, 1_500L, 2_000L, 4_999L, 6_000L, 9_000L, 9_500L)
        val out = input.map(c::adjust)
        assertEquals(listOf(500L, 999L, 1_000L, 1_000L, 3_999L, 4_000L, 4_000L, 4_500L), out)
        out.zipWithNext().forEach { (a, b) -> assertTrue("$a then $b", b >= a) }
        assertEquals(9_000L, c.lastResumeUs())
    }

    @Test
    fun `pause and resume are idempotent`() {
        val c = PauseClock()
        c.resume(10) // not paused: ignored
        c.pause(100); c.pause(200)
        c.resume(300); c.resume(400)
        assertFalse(c.isPaused)
        assertEquals(200L, c.pausedTotalUs(1_000))
    }
}
