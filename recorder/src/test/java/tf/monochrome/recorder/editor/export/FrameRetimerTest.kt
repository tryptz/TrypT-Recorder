package tf.monochrome.recorder.editor.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.recorder.editor.export.RetimedFrame.Between
import tf.monochrome.recorder.editor.export.RetimedFrame.Earlier
import tf.monochrome.recorder.editor.export.RetimedFrame.Later

class FrameRetimerTest {
    private fun retimer(
        fps: Int, endUs: Long, startUs: Long = 0, den: Int = 1,
        holdGapUs: Long = FrameRetimer.HOLD_GAP_US, interpolate: Boolean = true,
    ) = FrameRetimer(fps, den, startUs, endUs, holdGapUs, interpolate)

    private fun FrameRetimer.drain(): List<RetimedFrame> = generateSequence { poll() }.toList()

    /** Every output frame for source frames at [times], the stream ending after the last. */
    private fun FrameRetimer.run(vararg times: Long): List<RetimedFrame> = buildList {
        for (t in times) {
            offer(t)
            addAll(drain())
        }
        finish()
        addAll(drain())
    }

    /** Source frame times at [fps] for [count] frames, truncated to whole microseconds as a container stores them. */
    private fun source(fps: Int, count: Int, fromUs: Long = 0): LongArray =
        LongArray(count) { fromUs + it * 1_000_000L / fps }

    @Test
    fun `the same rate passes every frame through untouched`() {
        val out = retimer(60, endUs = 100_000).run(*source(60, 6))
        assertEquals(listOf(0L, 16_666, 33_333, 50_000, 66_666, 83_333), out.map { it.timeUs })
        assertTrue(out.toString(), out.all { it is Later })
    }

    @Test
    fun `doubling the rate makes one frame halfway between each pair`() {
        val out = retimer(60, endUs = 100_000).run(*source(30, 3))
        assertEquals(listOf(0L, 16_666, 33_333, 50_000, 66_666, 83_333), out.map { it.timeUs })
        assertEquals(Later(0), out[0])
        assertEquals(0.5f, (out[1] as Between).fraction, 0.001f)
        assertEquals(Later(33_333), out[2])
        assertEquals(0.5f, (out[3] as Between).fraction, 0.001f)
        assertEquals(Later(66_666), out[4])
        // Past the last source frame there is nothing to move towards: it is held.
        assertEquals(Later(83_333), out[5])
    }

    @Test
    fun `halving the rate keeps every other frame and makes none`() {
        val out = retimer(30, endUs = 200_000).run(*source(60, 12))
        assertEquals(listOf(0L, 33_333, 66_666, 100_000, 133_333, 166_666), out.map { it.timeUs })
        assertTrue(out.toString(), out.none { it is Between })
    }

    @Test
    fun `an uneven ratio is spaced exactly on the grid`() {
        // 24 from 60: a grid time falls between source frames two times in five.
        val r = retimer(24, endUs = 1_000_000)
        val out = r.run(*source(60, 60))
        assertEquals(24, out.size)
        assertEquals((0 until 24).map { it * 1_000_000L / 24 }, out.map { it.timeUs })
        val fractions = out.filterIsInstance<Between>().map { it.fraction }
        assertTrue(fractions.isNotEmpty())
        assertTrue(fractions.toString(), fractions.all { it > 0f && it < 1f })
    }

    @Test
    fun `a fractional rate keeps its exact cadence`() {
        val r = retimer(30_000, den = 1_001, endUs = 1_001_000)
        val out = r.run(*source(60, 61))
        assertEquals(30, out.size)
        assertEquals(33_366L, out[1].timeUs)
        assertEquals(967_633L, out.last().timeUs)
    }

    @Test
    fun `frames far apart are a held picture, not motion`() {
        // A screen that stood still for a second and then changed.
        val out = retimer(10, endUs = 1_300_000).run(0, 1_000_000, 1_100_000, 1_200_000)
        val still = out.filter { it.timeUs in 1..999_999 }
        assertEquals(9, still.size)
        assertTrue(still.toString(), still.all { it is Earlier })
        assertEquals(Later(1_000_000), out.first { it.timeUs == 1_000_000L })
    }

    @Test
    fun `a slowed clip's frames are still motion`() {
        // 30 fps at quarter speed: 133 ms apart on the timeline.
        val gap = FrameRetimer.holdGapUs(0.25f)
        assertEquals(500_000L, gap)
        val out = retimer(30, endUs = 400_000, holdGapUs = gap).run(0, 133_333, 266_666)
        listOf(0.25f, 0.5f, 0.75f).forEachIndexed { i, expected ->
            assertEquals(expected, (out[i + 1] as Between).fraction, 0.001f)
        }
        // At normal speed the same spacing is a held picture.
        assertEquals(FrameRetimer.HOLD_GAP_US, FrameRetimer.holdGapUs(1f))
        assertTrue(retimer(30, endUs = 400_000).run(0, 133_333, 266_666).none { it is Between })
    }

    @Test
    fun `without interpolation each frame is the nearer source frame`() {
        val out = retimer(90, endUs = 66_000, interpolate = false).run(*source(30, 3))
        // 0, 11111, 22222, 33333, 44444, 55555
        assertEquals(
            listOf(Later(0), Earlier(11_111), Later(22_222), Later(33_333), Earlier(44_444), Later(55_555)),
            out,
        )
    }

    @Test
    fun `a clip owns the grid from its start up to its end`() {
        // Two clips cut at 100 ms, off the 24 fps grid (41.67 ms).
        val first = retimer(24, startUs = 0, endUs = 100_000).run(*source(60, 6))
        val second = retimer(24, startUs = 100_000, endUs = 200_000).run(*source(60, 6, fromUs = 100_000))
        assertEquals(listOf(0L, 41_666, 83_333), first.map { it.timeUs })
        assertEquals(listOf(125_000L, 166_666), second.map { it.timeUs })
    }

    @Test
    fun `the picture covers the clip from its first moment to its last`() {
        // First frame late, last frame early: both ends are filled with what there is.
        val out = retimer(50, startUs = 0, endUs = 200_000).run(45_000, 65_000, 85_000)
        assertEquals((0 until 10).map { it * 20_000L }, out.map { it.timeUs })
        assertEquals(listOf(Later(0), Later(20_000), Later(40_000)), out.take(3))
        assertTrue(out.drop(5).all { it is Later })
    }

    @Test
    fun `nothing comes out before the frame it depends on arrives`() {
        val r = retimer(60, endUs = 1_000_000)
        assertNull(r.poll())
        r.offer(0)
        assertEquals(listOf<RetimedFrame>(Later(0)), r.drain())
        r.offer(33_333)
        assertEquals(2, r.drain().size)
        assertNull(r.poll())
    }

    @Test
    fun `a frame that does not move time forward is refused`() {
        val r = retimer(60, endUs = 1_000_000)
        assertTrue(r.offer(0))
        assertTrue(r.offer(33_333))
        assertFalse(r.offer(33_333))
        assertFalse(r.offer(20_000))
        assertEquals(listOf(0L, 16_666, 33_333), r.drain().map { it.timeUs })
    }

    @Test
    fun `output times never repeat or go back, even across a reset`() {
        val r = retimer(60, endUs = 500_000)
        val times = mutableListOf<Long>()
        for (t in source(25, 5)) { r.offer(t); times += r.drain().map { it.timeUs } }
        r.reset()
        for (t in source(25, 5, fromUs = 100_000)) { r.offer(t); times += r.drain().map { it.timeUs } }
        r.finish()
        times += r.drain().map { it.timeUs }
        assertEquals(times.sorted().distinct(), times)
        assertEquals((0 until 30).map { it * 1_000_000L / 60 }, times)
    }

    @Test
    fun `a long still tail is counted out one frame at a time`() {
        val r = retimer(165, endUs = 600 * 1_000_000L)
        r.offer(0)
        r.finish()
        var n = 0
        while (r.poll() != null) n++
        assertEquals(165 * 600, n)
    }
}
