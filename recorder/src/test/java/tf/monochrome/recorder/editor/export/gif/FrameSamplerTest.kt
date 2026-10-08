package tf.monochrome.recorder.editor.export.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSamplerTest {
    private val ms = 1_000L

    @Test
    fun `enough frames to cover the length, at least one`() {
        assertEquals(15, FrameSampler(15, 1_000 * ms).frameCount)
        assertEquals(16, FrameSampler(15, 1_010 * ms).frameCount)
        assertEquals(1, FrameSampler(10, 0).frameCount)
    }

    @Test
    fun `a still frame fills every gif frame until the next one arrives`() {
        // A variable-rate recording: nothing moves for half a second.
        val s = FrameSampler(10, 1_000 * ms)
        assertTrue(s.arrive(0).isEmpty())
        assertEquals(0..4, s.arrive(500 * ms))
        assertEquals(5..5, s.arrive(520 * ms))
        assertEquals(6..9, s.finish())
    }

    @Test
    fun `a change is never shown before it happens`() {
        val s = FrameSampler(10, 1_000 * ms)
        s.arrive(0)
        // The frame at 0 is still the one on screen at 100 ms, though 190 ms is nearer.
        assertEquals(0..1, s.arrive(190 * ms))
    }

    @Test
    fun `a frame replaced before the next gif frame's time fills none`() {
        val s = FrameSampler(10, 1_000 * ms)
        s.arrive(0)
        assertEquals(0..0, s.arrive(10 * ms))
        assertTrue(s.arrive(20 * ms).isEmpty())
        assertEquals(1..9, s.finish())
    }

    @Test
    fun `gif frames before the first video frame show it`() {
        val s = FrameSampler(10, 1_000 * ms)
        assertTrue(s.arrive(250 * ms).isEmpty())
        assertEquals(0..3, s.arrive(400 * ms))
    }

    @Test
    fun `frames past the end fill nothing`() {
        val s = FrameSampler(10, 300 * ms)
        s.arrive(0)
        assertEquals(0..2, s.arrive(900 * ms))
        assertTrue(s.arrive(950 * ms).isEmpty())
        assertTrue(s.finish().isEmpty())
    }

    @Test
    fun `no video frames, nothing to fill`() {
        assertTrue(FrameSampler(10, 1_000 * ms).finish().isEmpty())
    }

    @Test
    fun `delays keep time with the video`() {
        val s = FrameSampler(15, 2_000 * ms)
        val each = (0 until s.frameCount).map { s.delayCs(it..it) }
        assertTrue(each.all { it == 6 || it == 7 })
        assertEquals(200, each.sum())
        assertEquals(200, s.delayCs(0 until s.frameCount))
        assertEquals(each.subList(3, 9).sum(), s.delayCs(3..8))
        assertEquals(listOf(10), FrameSampler(10, 100 * ms).let { f -> listOf(f.delayCs(0..0)) })
        assertEquals(4, FrameSampler(25, 1_000 * ms).delayCs(7..7))
    }
}
