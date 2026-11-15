package tf.monochrome.recorder.ui.glass

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure parts of the glass: it is Tryptify's Float material number for
 * number, the lens rim sits where the pane's edge is, the frost follows the
 * ground, and the fallback surface never goes thinner than its floor.
 */
class GlassMaterialTest {

    private val density = Density(3f)

    @Test
    fun `the default glass is Tryptify's Float, number for number`() {
        val g = GlassSettings.FLOAT
        assertEquals(GlassSettings(), g)
        assertEquals(g, GlassSettings.DEFAULT)
        assertTrue(g.enabled)
        assertEquals(0.48956832f, g.bodyOpacity, 0f)
        assertEquals(0.25042957f, g.refraction, 0f)
        assertEquals(1.2720146f, g.rimBrightness, 0f)
        assertEquals(1.0006076f, g.dispersion, 0f)
        assertEquals(3, g.sampleRings)
        assertEquals(1.9778082f, g.roundness, 0f)
        assertEquals(0.99957055f, g.depth, 0f)
        assertEquals(1.0004491f, g.reflection, 0f)
        assertEquals(1f, g.gloss, 0f)
        assertEquals(0f, g.surfaceMotion, 0f)
        assertEquals(112.78995f, g.lightAngleDeg, 0f)
        assertEquals(0.9997863f, g.edgeWidth, 0f)
        assertEquals(0f, g.frost, 0f)
        assertEquals(0, g.tintColor)
        assertEquals(31.726803f, g.hazeBlurDp, 0f)
        assertEquals(0f, g.hazeTint, 0f)
    }

    @Test
    fun `no rim without a corner`() {
        val (r, w) = with(density) { lensRimPx(Dp.Unspecified, Size(600f, 180f), 1f) }
        assertEquals(0f, r, 0f)
        assertEquals(0f, w, 0f)
    }

    @Test
    fun `an infinite corner is clamped to the short half-side`() {
        // A pill 180px tall: its corner is 90px, and the band stops at the
        // 24dp (72px) cap, widened by roundness 2 to all of it.
        val (r, w) = with(density) { lensRimPx(Dp.Infinity, Size(600f, 180f), 2f) }
        assertEquals(90f, r, 0.001f)
        assertEquals(72f, w, 0.001f)
    }

    @Test
    fun `the band is never wider than the corner, so the field has no crease`() {
        // 16dp = 48px: under the cap, so the band is the corner at roundness 2.
        val (r, w) = with(density) { lensRimPx(16.dp, Size(1020f, 600f), 2f) }
        assertEquals(48f, r, 0.001f)
        assertEquals(48f, w, 0.001f)
        for (roundness in listOf(0.5f, 1f, 1.5f, 2f)) {
            for (corner in listOf(4.dp, 16.dp, 40.dp, Dp.Infinity)) {
                val (cr, cw) = with(density) { lensRimPx(corner, Size(900f, 192f), roundness) }
                assertTrue("band $cw over corner $cr", cw <= cr + 0.001f)
                assertTrue("band $cw over the cap", cw <= with(density) { LensRimMax.toPx() } + 0.001f)
                assertTrue("corner $cr past the short half-side", cr <= 96f + 0.001f)
            }
        }
    }

    @Test
    fun `roundness widens the band from five eighths to all of it`() {
        val narrow = with(density) { lensRimPx(16.dp, Size(1020f, 600f), 0.5f) }.second
        val wide = with(density) { lensRimPx(16.dp, Size(1020f, 600f), 2f) }.second
        assertEquals(48f * 0.625f, narrow, 0.001f)
        assertEquals(48f, wide, 0.001f)
    }

    @Test
    fun `the frost washes toward the ground, and Float's is clear`() {
        val tinted = GlassSettings.FLOAT.copy(hazeTint = 1f)
        assertEquals(Color.Black.copy(alpha = 0.32f), glassFrostTint(tinted, isDark = true))
        assertEquals(Color.White.copy(alpha = 0.26f), glassFrostTint(tinted, isDark = false))
        assertEquals(0f, glassFrostTint(GlassSettings.FLOAT, isDark = true).alpha, 0f)
        assertEquals(1f, glassFrostTint(GlassSettings.FLOAT.copy(hazeTint = 9f), isDark = true).alpha, 0f)
    }

    @Test
    fun `the fallback surface is never thinner than its floor`() {
        val surface = Color(0xFF2A2A2A)
        val ground = Color.White
        val clear = Color.Transparent
        val thin = glassFace(ground, clear, Color.Red, bodyOpacity = 0f, shaded = false, fallback = surface)
        val floor = glassFace(ground, clear, Color.Red, bodyOpacity = 0.55f, shaded = false, fallback = surface)
        assertEquals(floor, thin)
        assertEquals(surface.copy(alpha = 0.55f).compositeOver(ground), thin)
        assertEquals(0.55f, FALLBACK_MIN_ALPHA, 0f)
    }
}
