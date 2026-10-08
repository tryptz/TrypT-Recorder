package tf.monochrome.recorder.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.recorder.settings.QualityPreset
import tf.monochrome.recorder.settings.ResolutionPreset

class CapturePlannerTest {
    @Test
    fun `sideways phone records landscape`() {
        assertEquals(1080 to 2400, CapturePlanner.orientedSize(1080, 2400, rotation = 0))
        assertEquals(2400 to 1080, CapturePlanner.orientedSize(1080, 2400, rotation = 1))
        assertEquals(1080 to 2400, CapturePlanner.orientedSize(1080, 2400, rotation = 2))
        assertEquals(2400 to 1080, CapturePlanner.orientedSize(1080, 2400, rotation = 3))
    }

    @Test
    fun `short side cap keeps aspect and never upscales`() {
        assertEquals(720 to 1600, CapturePlanner.capShortSide(1080, 2400, 720))
        assertEquals(1080 to 2400, CapturePlanner.capShortSide(1080, 2400, 1440))
        assertEquals(1080 to 2400, CapturePlanner.capShortSide(1080, 2400, null))
        assertEquals(1600 to 720, CapturePlanner.capShortSide(2400, 1080, 720))
    }

    @Test
    fun `sizes are aligned down and stay even`() {
        assertEquals(1078 to 2398, CapturePlanner.capShortSide(1079, 2399, null))
        assertEquals(1072, CapturePlanner.align(1080, 16))
        assertEquals(16, CapturePlanner.align(3, 16))
    }

    @Test
    fun `frame rate is capped by the panel`() {
        assertEquals(60, CapturePlanner.frameRate(120, 60f))
        assertEquals(90, CapturePlanner.frameRate(90, 120f))
        assertEquals(30, CapturePlanner.frameRate(30, 59.94f))
    }

    @Test
    fun `medium quality matches the debug recorder's bits per pixel`() {
        // 0.08 bpp·fps, as in Tryptify's DebugScreenRecorder.
        assertEquals((1080L * 2400 * 60 * 0.08).toInt(), CapturePlanner.bitrate(1080, 2400, 60, QualityPreset.MEDIUM))
        assertEquals(60_000_000, CapturePlanner.bitrate(3200, 1440, 120, QualityPreset.HIGH))
    }

    @Test
    fun `frame rate cap follows the panel's fastest mode, not its current one`() {
        val modes = listOf(
            Triple(1080, 2400, 60f),
            Triple(1080, 2400, 120f),
            Triple(1080, 2400, 165f),
            Triple(1440, 3200, 60f), // another resolution's modes do not count
        )
        // Idling at 60 Hz on the consent dialog must not cap a 165 fps setting.
        assertEquals(165f, CapturePlanner.maxRefreshHz(modes, 1080, 2400, current = 60f), 0f)
        assertEquals(60f, CapturePlanner.maxRefreshHz(modes, 1440, 3200, current = 60f), 0f)
        assertEquals(90f, CapturePlanner.maxRefreshHz(emptyList(), 1080, 2400, current = 90f), 0f)
        assertEquals(165, CapturePlanner.frameRate(165, 165f))
        assertEquals(120, CapturePlanner.frameRate(165, 120f))
    }

    @Test
    fun `fallbacks hold the frame rate down to 720p before stepping it down`() {
        val plan = CapturePlanner.plan(1080, 2400, 165f, ResolutionPreset.NATIVE, 165, QualityPreset.MEDIUM)
        assertEquals(165, plan.fps)
        val fb = CapturePlanner.fallbacks(plan, QualityPreset.MEDIUM)
        // First give up resolution, not smoothness.
        assertEquals(listOf(720, 165), listOf(fb[0].width, fb[0].fps))
        // Then the rate steps down, each time from full size again.
        assertEquals(listOf(1080, 144), listOf(fb[1].width, fb[1].fps))
        assertEquals(listOf(720, 144), listOf(fb[2].width, fb[2].fps))
        assertEquals(listOf(1080, 120), listOf(fb[3].width, fb[3].fps))
        // Below 720p only 30 fps is tried, and that is where it ends.
        assertTrue(fb.filter { minOf(it.width, it.height) < 720 }.all { it.fps == 30 })
        assertEquals(30, fb.last().fps)
        assertTrue(fb.none { it == plan })
        assertEquals(fb.size, fb.toSet().size)
    }

    @Test
    fun `a 30 fps plan only falls back in size`() {
        val plan = CapturePlanner.plan(1080, 2400, 60f, ResolutionPreset.NATIVE, 30, QualityPreset.LOW)
        val fb = CapturePlanner.fallbacks(plan, QualityPreset.LOW)
        assertTrue(fb.all { it.fps == 30 })
        assertEquals(listOf(720, 540, 480, 360), fb.map { it.width })
    }
}
