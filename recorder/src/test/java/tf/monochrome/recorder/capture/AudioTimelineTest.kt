package tf.monochrome.recorder.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioTimelineTest {
    private val rate = 48_000
    // 20 ms exactly, so the test's own arithmetic has no rounding drift.
    private val block = 960
    private val blockUs = block * 1_000_000L / rate
    private val latencyUs = 5_000L

    /** A block as captured: when its first sample was really captured, and when the pump got it. */
    private data class Read(val trueStartUs: Long, val readAtUs: Long)

    private class Outcome(val maxErrorUs: Long, val lastErrorUs: Long, val silenceFrames: Long, val droppedFrames: Long)

    /** Runs [reads] through a timeline the way the pump does; error is stamp minus true capture time. */
    private fun run(reads: List<Read>, timeline: AudioTimeline = AudioTimeline(rate)): Outcome {
        var maxError = 0L
        var last = 0L
        var silence = 0L
        var dropped = 0L
        var baseline: Long? = null
        for (r in reads) {
            val c = timeline.onBlock(r.readAtUs, block, Long.MIN_VALUE)
            var skip = 0
            when {
                c > 0 -> { silence += c; timeline.advance(c) }
                c < 0 -> { skip = -c; dropped += skip }
            }
            if (skip == block) continue
            val stamp = timeline.nextUs()
            val truth = r.trueStartUs + skip * 1_000_000L / rate
            // The anchor carries the first read's latency; only changes from it are errors.
            val error = stamp - truth - (baseline ?: (stamp - truth).also { baseline = it })
            maxError = maxOf(maxError, abs(error))
            last = error
            timeline.advance(block - skip)
        }
        return Outcome(maxError, last, silence, dropped)
    }

    private fun steady(count: Int, startUs: Long = 1_000_000, durationUs: Long = blockUs) =
        List(count) { i -> Read(startUs + i * durationUs, startUs + (i + 1) * durationUs + latencyUs) }

    @Test fun steadyCaptureIsCountedWithoutCorrections() {
        val o = run(steady(5_000))
        assertEquals(0L, o.silenceFrames)
        assertEquals(0L, o.droppedFrames)
        assertTrue(o.maxErrorUs < 1_000)
    }

    @Test fun samplesLostInAnOverrunAreReplacedWithSilence() {
        val all = steady(400)
        // Ten blocks (213 ms) overwritten in the capture buffer: never read.
        val reads = all.take(100) + all.drop(110)
        val o = run(reads)
        assertTrue("silence ${o.silenceFrames} for ${10 * block} lost", abs(o.silenceFrames - 10L * block) < 1_500)
        assertTrue("later audio back in step: ${o.lastErrorUs}", abs(o.lastErrorUs) < 1_000)
        assertEquals(0L, o.droppedFrames)
    }

    @Test fun aStallWithNothingLostIsNotCorrected() {
        val all = steady(400).toMutableList()
        // The pump is held up for 300 ms; the buffer holds it, and the backlog is read in a burst.
        val stalledAt = all[100].readAtUs
        for (i in 100 until 115) {
            all[i] = all[i].copy(readAtUs = stalledAt + 300_000 + (i - 100) * 100L)
        }
        val o = run(all)
        assertEquals(0L, o.silenceFrames)
        assertEquals(0L, o.droppedFrames)
        assertEquals(0L, o.lastErrorUs)
    }

    @Test fun anAudioClockSlowerThanTheMonotonicOneIsKeptWithinTolerance() {
        // 200 ppm slow: 1024 samples take a little longer than 21.333 ms. One hour.
        val reads = List(170_000) { i ->
            val start = 1_000_000 + (i * blockUs * 1.0002).toLong()
            Read(start, start + (blockUs * 1.0002).toLong() + latencyUs)
        }
        val o = run(reads)
        assertTrue("silence inserted to keep up", o.silenceFrames > 0)
        assertTrue("error stays near the tolerance: ${o.maxErrorUs}", o.maxErrorUs < 30_000 + 2 * blockUs)
    }

    @Test fun anAudioClockFasterThanTheMonotonicOneDropsFrames() {
        val reads = List(170_000) { i ->
            val start = 1_000_000 + (i * blockUs * 0.9998).toLong()
            Read(start, start + (blockUs * 0.9998).toLong() + latencyUs)
        }
        val o = run(reads)
        assertTrue("frames dropped to stay in step", o.droppedFrames > 0)
        assertEquals(0L, o.silenceFrames)
        assertTrue("error stays near the tolerance: ${o.maxErrorUs}", o.maxErrorUs < 30_000 + 2 * blockUs)
    }

    @Test fun anchorIsNeverBeforeTheResume() {
        val t = AudioTimeline(rate)
        assertEquals(0, t.onBlock(nowUs = 1_000_000, blockFrames = block, notBeforeUs = 990_000))
        assertEquals(990_000L, t.nextUs())
    }

    @Test fun resetAnchorsAfresh() {
        val t = AudioTimeline(rate)
        t.onBlock(1_000_000, block, Long.MIN_VALUE)
        t.advance(block)
        t.reset()
        assertTrue(!t.isAnchored)
        t.onBlock(5_000_000, block, Long.MIN_VALUE)
        assertEquals(5_000_000L - blockUs, t.nextUs())
    }

    @Test fun anImpossiblyLateStampDropsTheDifference() {
        val big = 4_800 // 100 ms
        val t = AudioTimeline(rate)
        t.onBlock(1_000_000, big, Long.MIN_VALUE)
        t.advance(big)
        // The next block is read 40 ms earlier than the count says it could have been captured.
        val c = t.onBlock(1_000_000 + 100_000 - 40_000, big, Long.MIN_VALUE)
        assertEquals(-(40_000L * rate / 1_000_000).toInt(), c)
    }

    @Test fun aDropIsNeverMoreThanTheBlock() {
        val t = AudioTimeline(rate)
        t.onBlock(1_000_000, block, Long.MIN_VALUE)
        t.advance(block)
        assertEquals(-block, t.onBlock(1_000_000 + blockUs - 200_000, block, Long.MIN_VALUE))
    }

    @Test fun aVeryLongGapStartsTheStampsAgainInsteadOfFillingIt() {
        val t = AudioTimeline(rate, windowBlocks = 4)
        t.onBlock(1_000_000, block, Long.MIN_VALUE)
        t.advance(block)
        var now = 1_000_000L + 60_000_000
        repeat(4) {
            now += blockUs
            val c = t.onBlock(now, block, Long.MIN_VALUE)
            assertEquals(0, c)
            if (t.nextUs() == now - blockUs) return
            t.advance(block)
        }
        assertEquals(now - blockUs, t.nextUs())
    }
}
