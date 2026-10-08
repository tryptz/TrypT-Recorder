package tf.monochrome.recorder.editor.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.recorder.editor.model.VideoLook

class GrainTest {
    private val fine = VideoLook.DEFAULT_GRAIN_SIZE

    @Test
    fun `grains run from 1 to 4 pixels on a 1080-pixel short side`() {
        assertEquals(1f, Grain.cellPixels(0f, 1080), 1e-5f)
        assertEquals(4f, Grain.cellPixels(1f, 1080), 1e-5f)
        assertEquals(4f, Grain.cellPixels(3f, 1080), 1e-5f)
        assertEquals(1f, Grain.cellPixels(-1f, 1080), 1e-5f)
    }

    @Test
    fun `grain is sized to the frame, so the 720p preview matches the export`() {
        for (size in listOf(0f, fine, 0.5f, 1f)) {
            val preview = Grain.cellPixels(size, 720) / 720
            for (export in listOf(1080, 1440, 2160)) assertEquals(preview, Grain.cellPixels(size, export) / export, 1e-6f)
        }
    }

    @Test
    fun `amount sets the strength, up to a tenth of full scale`() {
        assertEquals(0f, Grain.pixelStd(0f, fine, 1080), 0f)
        assertEquals(0.05f, Grain.pixelStd(0.5f, fine, 1080), 1e-6f)
        assertEquals(0.10f, Grain.pixelStd(1f, fine, 1080), 1e-6f)
        assertEquals(0.10f, Grain.pixelStd(2f, fine, 1080), 1e-6f)
    }

    @Test
    fun `grains smaller than a pixel average out, so a small gif is not grainier`() {
        val big = Grain.pixelStd(1f, fine, 1080)
        val small = Grain.pixelStd(1f, fine, 320)
        assertEquals(big * Grain.cellPixels(fine, 320), small, 1e-6f)
        assertTrue(small < big)
        // Coarse grain stays above a pixel even at 480p and keeps its full strength.
        assertEquals(0.10f, Grain.pixelStd(1f, 1f, 480), 1e-6f)
    }

    @Test
    fun `strongest in the midtones, a fifth at black and white`() {
        assertEquals(1f, Grain.response(0.5f), 1e-6f)
        assertEquals(0.2f, Grain.response(0f), 1e-6f)
        assertEquals(0.2f, Grain.response(1f), 1e-6f)
        assertEquals(Grain.response(0.2f), Grain.response(0.8f), 1e-6f)
        val rising = (0..50).map { Grain.response(it / 100f) }
        assertTrue(rising.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun `every frame gets its own grain, the same one each time`() {
        assertEquals(Grain.seed(1_234_567), Grain.seed(1_234_567))
        // Frame times at 165, 60 and 24 fps from the start.
        val times = listOf(0L, 6_061, 12_121, 16_667, 33_333, 41_667, 83_333)
        val seeds = times.map { Grain.seed(it) }
        assertEquals(seeds.size, seeds.toSet().size)
        seeds.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
    }

    @Test
    fun `seeds stay in range and spread evenly`() {
        val xs = (0 until 10_000).map { Grain.seed(it * 16_667L) }
        assertTrue(xs.all { (x, y) -> x in 0f..999.999f && y in 0f..999.999f })
        val deciles = IntArray(10)
        xs.forEach { (x, _) -> deciles[(x / 100).toInt()]++ }
        assertTrue(deciles.joinToString(), deciles.all { it in 900..1100 })
    }
}
