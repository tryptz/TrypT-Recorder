package tf.monochrome.recorder.editor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timeline ruler is as wide as the whole project, up to 150,000 px and
 * ~850 marks, and it used to draw and measure every one of them on every
 * redraw. rulerTicks picks the marks on screen.
 */
class RulerTicksTest {
    @Test
    fun `only the marks on screen, plus the one whose label runs onto it`() {
        // A mark every 2 s at 50 px/s: one per 100 px. The screen shows
        // 1,000-1,400 px of the ruler; a label is 60 px wide.
        val ticks = rulerTicks(totalSec = 600f, stepSec = 2f, pxPerSec = 50f, fromPx = 1000f, widthPx = 400f, labelPx = 60f)
        assertEquals(10..14, ticks)
    }

    @Test
    fun `a long project at the widest zoom draws a screenful, not all of it`() {
        val all = rulerTicks(1700f, 2f, 88f, fromPx = -1e9f, widthPx = 1e12f, labelPx = 0f)
        assertEquals(0..850, all)
        val onScreen = rulerTicks(1700f, 2f, 88f, fromPx = 70_000f, widthPx = 1080f, labelPx = 64f)
        assertTrue("${onScreen.count()} marks", onScreen.count() in 6..8)
    }

    @Test
    fun `the first and last marks stay within the project`() {
        // Scrolled to the very start (the ruler begins half a screen in) and past the end.
        assertEquals(0..2, rulerTicks(10f, 1f, 100f, fromPx = -540f, widthPx = 800f, labelPx = 60f))
        assertEquals(9..10, rulerTicks(10f, 1f, 100f, fromPx = 900f, widthPx = 800f, labelPx = 60f))
        assertTrue(rulerTicks(10f, 1f, 100f, fromPx = 5000f, widthPx = 800f, labelPx = 60f).isEmpty())
    }

    @Test
    fun `nonsense in gives nothing, not a crash`() {
        assertTrue(rulerTicks(10f, 0f, 100f, 0f, 800f, 60f).isEmpty())
        assertTrue(rulerTicks(10f, 1f, 0f, 0f, 800f, 60f).isEmpty())
        assertTrue(rulerTicks(Float.NaN, 1f, 100f, 0f, 800f, 60f).isEmpty())
        assertTrue(rulerTicks(10f, 1f, 100f, Float.NaN, 800f, 60f).isEmpty())
    }
}
