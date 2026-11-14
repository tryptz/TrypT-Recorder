package tf.monochrome.recorder.editor.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.recorder.editor.model.GrainDetail
import tf.monochrome.recorder.editor.model.GrainMotion
import tf.monochrome.recorder.editor.model.VideoLook
import kotlin.math.sqrt

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
    fun `the default tone curve is the first grain's, unchanged`() {
        for (i in 0..100) {
            val l = i / 100f
            val first = 0.2f + 0.8f * sqrt(4f * l * (1f - l))
            assertEquals("luma $l", first, Grain.response(l), 1e-5f)
        }
    }

    @Test
    fun `shadows, midtones and highlights set the curve at black, grey and white`() {
        assertEquals(0.7f, Grain.response(0f, shadows = 0.7f, midtones = 0.4f, highlights = 0.1f), 1e-6f)
        assertEquals(0.4f, Grain.response(0.5f, shadows = 0.7f, midtones = 0.4f, highlights = 0.1f), 1e-6f)
        assertEquals(0.1f, Grain.response(1f, shadows = 0.7f, midtones = 0.4f, highlights = 0.1f), 1e-6f)
        // Between them it moves one way only: a digital-noise look, strongest in the shadows.
        val falling = (0..50).map { Grain.response(it / 100f, shadows = 1f, midtones = 0.3f, highlights = 0f) }
        assertTrue(falling.zipWithNext().all { (a, b) -> b <= a })
    }

    @Test
    fun `sharpness steepens the grain's contrast, down to a power of 0_4`() {
        assertEquals(1f, Grain.sharpPower(0f), 1e-6f)
        assertEquals(0.4f, Grain.sharpPower(1f), 1e-6f)
        assertEquals(0.4f, Grain.sharpPower(3f), 1e-6f)
        assertTrue(Grain.sharpPower(0.3f) > Grain.sharpPower(0.6f))
    }

    @Test
    fun `sharpening keeps the strength`() {
        // Against the closed form, 1 / sqrt(2^p Γ(p + ½) / √π).
        assertEquals(1f, Grain.sharpNorm(1f), 1e-4f)
        assertEquals(1.0900984f, Grain.sharpNorm(0.7f), 1e-4f)
        assertEquals(1.1211612f, Grain.sharpNorm(0.4f), 1e-4f)
    }

    @Test
    fun `motion sets how often the grain changes`() {
        // Every frame: as before.
        assertEquals(Grain.seed(33_333), Grain.seed(33_333, GrainMotion.EVERY_FRAME))
        assertNotEquals(Grain.seed(0, GrainMotion.EVERY_FRAME), Grain.seed(16_667, GrainMotion.EVERY_FRAME))
        // 24 a second: the same through each 1/24 s, whatever the video's frame rate, then new.
        val film = GrainMotion.FILM_24
        assertEquals(Grain.seed(0, film), Grain.seed(6_061, film))
        assertEquals(Grain.seed(0, film), Grain.seed(41_666, film))
        assertNotEquals(Grain.seed(41_666, film), Grain.seed(41_667, film))
        assertEquals(24, (0 until 1_000_000 step 1_000).map { Grain.seed(it.toLong(), film) }.toSet().size)
        assertEquals(12, (0 until 1_000_000 step 1_000).map { Grain.seed(it.toLong(), GrainMotion.FILM_12) }.toSet().size)
        // Still: one pattern throughout.
        assertEquals(1, (0 until 10_000_000 step 33_333).map { Grain.seed(it.toLong(), GrainMotion.STILL) }.toSet().size)
    }

    @Test
    fun `no grain plans to nothing, and every control is held to its range`() {
        assertNull(Grain.plan(VideoLook()))
        val wild = VideoLook(
            grain = 3f,
            grainSize = 2f,
            grainDetail = GrainDetail(sharpness = -1f, texture = 5f, colour = 2f, shadows = -0.5f, midtones = 9f, highlights = 1.2f, red = -1f, green = 7f, blue = 1.5f),
        )
        val g = Grain.plan(wild)!!
        assertEquals(listOf(1f, 1f, 0f, 1f, 1f, 0f, 1f, 1f), listOf(g.amount, g.size, g.sharpness, g.texture, g.colour, g.shadows, g.midtones, g.highlights))
        assertEquals(listOf(0f, Grain.MAX_CHANNEL, 1.5f), listOf(g.red, g.green, g.blue))
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
