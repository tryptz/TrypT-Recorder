package tf.monochrome.recorder.editor

import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.recorder.editor.analysis.CoverTracking
import tf.monochrome.recorder.editor.analysis.FramePicker
import tf.monochrome.recorder.editor.analysis.LumaGrid
import tf.monochrome.recorder.editor.analysis.LumaSource
import tf.monochrome.recorder.editor.model.CoverSample
import tf.monochrome.recorder.editor.model.CropRect
import tf.monochrome.recorder.editor.model.TrackedCover
import tf.monochrome.recorder.editor.store.ProjectStore

class CoverTrackingTest {
    @Test fun samplesStepOutFromTheSeedToBothEndsOfTheClip() {
        val (before, after) = CoverTracking.times(0, 10 * SEC, 5 * SEC)
        val step = CoverTracking.MIN_STEP_US
        assertEquals(5 * SEC - step, before.first())
        assertEquals(0L, before.last())
        assertEquals(5 * SEC + step, after.first())
        assertEquals(10 * SEC - 1, after.last())
        assertTrue(before.zipWithNext().all { (a, b) -> b < a })
        assertTrue(after.zipWithNext().all { (a, b) -> b > a })
        // A long clip is held to the most samples it may have.
        val (b, a) = CoverTracking.times(0, 3_600 * SEC, 0)
        assertTrue(b.isEmpty())
        assertTrue(a.size <= CoverTracking.MAX_SAMPLES + 1)
    }

    @Test fun eachTimeGetsTheFrameOnScreenThenNotTheNearest() {
        val picker = FramePicker(longArrayOf(-10, 50_000, 100_000, 150_000, 900_000, 2_000_000))
        // The frame at 0 is shown until the one at 100 ms: the first two times.
        assertEquals(2, picker.claim(100_000))
        assertEquals(0, picker.first)
        // The frame at 100 ms until 1 s, even though 900 ms is nearer the next one.
        assertEquals(3, picker.claim(1_000_000))
        assertEquals(2, picker.first)
        assertFalse(picker.done)
        // The last frame holds to the end.
        assertEquals(1, picker.claim(Long.MAX_VALUE))
        assertEquals(5, picker.first)
        assertTrue(picker.done)
        assertEquals(0, picker.claim(Long.MAX_VALUE))
    }

    @Test fun theLumaGridTurnsTheFrameUprightAndWidensVideoLevels() {
        // A 3×2 coded picture inside a padded, cropped buffer: rows of 8 bytes, crop at (1, 1).
        val coded = arrayOf(intArrayOf(10, 20, 30), intArrayOf(40, 50, 60))
        val buffer = ByteBuffer.allocate(8 * 4)
        for (y in 0..1) for (x in 0..2) buffer.put((1 + y) * 8 + 1 + x, coded[y][x].toByte())
        fun shown(rotation: Int, w: Int, h: Int): List<Int> {
            val out = ByteArray(w * h)
            LumaGrid(w, h, 1, 1, 3, 2, 8, 1, rotation, fullRange = true).sample(buffer, out)
            return out.map { it.toInt() and 255 }
        }
        assertEquals(listOf(10, 20, 30, 40, 50, 60), shown(0, 3, 2))
        // Turned a quarter clockwise: the left column, bottom up, is the top row.
        assertEquals(listOf(40, 10, 50, 20, 60, 30), shown(90, 2, 3))
        assertEquals(listOf(60, 50, 40, 30, 20, 10), shown(180, 3, 2))
        assertEquals(listOf(30, 60, 20, 50, 10, 40), shown(270, 2, 3))
        // Video levels: 16 is black and 235 white.
        val levels = ByteBuffer.wrap(byteArrayOf(16, 235.toByte(), 126, 0))
        val out = ByteArray(4)
        LumaGrid(4, 1, 0, 0, 4, 1, 4, 1, 0, fullRange = false).sample(levels, out)
        assertArrayEquals(byteArrayOf(0, 255.toByte(), 128.toByte(), 0), out)
    }

    @Test fun theGridAveragesWhenItShrinks() {
        val plane = ByteBuffer.wrap(ByteArray(16) { (if (it % 2 == 0) 100 else 200).toByte() })
        val out = ByteArray(2 * 2)
        LumaGrid(2, 2, 0, 0, 4, 4, 4, 1, 0, fullRange = true).sample(plane, out)
        assertTrue(out.all { (it.toInt() and 255) == 150 })
    }

    /**
     * A patterned square moving right a pixel a sample over a still, gently
     * shaded background. Smooth, as pictures are: the tracker's coarse search
     * relies on a near miss scoring better than a far one.
     */
    private class MovingSquare(val w: Int, val h: Int, val lost: Set<Long> = emptySet()) : LumaSource {
        val reads = mutableListOf<LongArray>()
        private val background = ByteArray(w * h) { i ->
            (70 + 30 * sin((i % w) * 0.11 + (i / w) * 0.07)).roundToInt().toByte()
        }
        private val square = ByteArray(SIZE * SIZE) { i ->
            (150 + 90 * sin((i % SIZE) * 0.55) * cos((i / SIZE) * 0.45 + 0.3)).roundToInt().coerceIn(0, 255).toByte()
        }

        fun x(timeUs: Long) = 20 + (timeUs / CoverTracking.MIN_STEP_US).toInt()

        override fun read(timesUs: LongArray, onFrame: (index: Int, luma: ByteArray) -> Unit) {
            reads += timesUs
            val frame = ByteArray(w * h)
            timesUs.forEachIndexed { index, time ->
                if (time in lost) return@forEachIndexed
                background.copyInto(frame)
                val left = x(time)
                for (y in 0 until SIZE) for (x in 0 until SIZE) frame[(Y + y) * w + left + x] = square[y * SIZE + x]
                onFrame(index, frame)
            }
        }

        companion object {
            const val SIZE = 12
            const val Y = 18
        }
    }

    @Test fun aMovingSquareIsFollowedBothWaysFromTheSeed() {
        val w = 80
        val h = 48
        val seed = SEC
        val lostAt = SEC + 3 * CoverTracking.MIN_STEP_US
        val source = MovingSquare(w, h, lost = setOf(lostAt))
        val box = CropRect(
            source.x(seed).toFloat() / w, MovingSquare.Y.toFloat() / h,
            (source.x(seed) + MovingSquare.SIZE).toFloat() / w, (MovingSquare.Y + MovingSquare.SIZE).toFloat() / h,
        )
        val progress = mutableListOf<Float>()
        val track = CoverTracking(w, h, chunk = 2).track(source, 0, 2 * SEC, seed, box, progress = { progress += it })
        // Every read asks in time order; the times before the seed come in chunks.
        assertTrue(source.reads.all { r -> r.toList().zipWithNext().all { (a, b) -> a < b } })
        assertTrue(source.reads.size > 2)
        assertEquals(1f, progress.last(), 0f)
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
        for (sample in track.samples) {
            if (sample.sourceUs == lostAt) {
                assertFalse(sample.matched)
                continue
            }
            assertTrue("matched at ${sample.sourceUs}", sample.matched)
            assertEquals(source.x(sample.sourceUs).toFloat() / w, sample.box.left, 0.0001f)
        }
        // The lost frame is passed over: the box is where the sure ones around it put it.
        assertEquals(source.x(lostAt).toFloat() / w, track.at(lostAt).left, 0.5f / w)
    }

    @Test fun aTrackedCoverSavesOnlyItsSamples() {
        val track = TrackedCover(listOf(CoverSample(0, CropRect.FULL, false), CoverSample(SEC, CropRect(0f, 0f, .5f, .5f))))
        track.at(0)
        val json = ProjectStore.JSON.encodeToString(TrackedCover.serializer(), track)
        assertFalse(json.contains("confident"))
        assertEquals(track, ProjectStore.JSON.decodeFromString(TrackedCover.serializer(), json))
    }
}
