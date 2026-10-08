package tf.monochrome.recorder.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.recorder.editor.model.CanvasSettings
import tf.monochrome.recorder.editor.model.CropBox
import tf.monochrome.recorder.editor.model.CropHandle
import tf.monochrome.recorder.editor.model.CropInsets
import tf.monochrome.recorder.editor.model.CropRect

class CropBoxTest {
    private val eps = 1e-4f

    private fun assertRect(expected: CropRect, actual: CropRect) {
        val e = listOf(expected.left, expected.top, expected.right, expected.bottom)
        val a = listOf(actual.left, actual.top, actual.right, actual.bottom)
        e.zip(a).forEach { (x, y) -> assertEquals("expected $expected, got $actual", x, y, eps) }
    }

    private fun assertInside(r: CropRect) {
        assertTrue("$r", r.left >= 0f && r.top >= 0f && r.right <= 1f + eps && r.bottom <= 1f + eps)
        assertTrue("$r", r.width >= CropBox.MIN - eps && r.height >= CropBox.MIN - eps)
    }

    private val orientations = (0..3).flatMap { q ->
        listOf(false, true).flatMap { h -> listOf(false, true).map { v -> CanvasSettings(rotation = q * 90, flipHorizontal = h, flipVertical = v) } }
    }

    @Test
    fun `no crop is the whole picture, however it is turned`() {
        for (c in orientations) {
            assertRect(CropRect.FULL, CropBox.shown(c))
            assertTrue("$c", CropBox.insets(CropRect.FULL, c).isNone)
        }
    }

    @Test
    fun `a crop survives the round trip through every turn and mirror`() {
        val crop = CropInsets(left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.05f)
        for (c in orientations) {
            val canvas = c.copy(crop = crop)
            assertEquals("$c", crop, CropBox.insets(CropBox.shown(canvas), canvas))
        }
    }

    @Test
    fun `the box is where the cut shows`() {
        // Turned clockwise, the source's top is on the right.
        assertRect(CropRect(0f, 0f, 0.8f, 1f), CropBox.shown(CanvasSettings(rotation = 90, crop = CropInsets(top = 0.2f))))
        // Mirrored, the source's left is on the right.
        assertRect(CropRect(0f, 0f, 0.75f, 1f), CropBox.shown(CanvasSettings(flipHorizontal = true, crop = CropInsets(left = 0.25f))))
        // Mirrored and then turned, as Media3 does: the source's left ends up at the bottom.
        assertRect(
            CropRect(0f, 0f, 1f, 0.75f),
            CropBox.shown(CanvasSettings(rotation = 90, flipHorizontal = true, crop = CropInsets(left = 0.25f))),
        )
        assertRect(CropRect(0.1f, 0f, 1f, 1f), CropBox.shown(CanvasSettings(rotation = 270, crop = CropInsets(top = 0.1f))))
    }

    @Test
    fun `free dragging makes any size and shape`() {
        assertRect(CropRect(0f, 0f, 0.3f, 0.8f), CropBox.drag(CropRect.FULL, CropHandle.BOTTOM_RIGHT, -0.7f, -0.2f))
        assertRect(CropRect(0.5f, 0f, 1f, 1f), CropBox.drag(CropRect.FULL, CropHandle.LEFT, 0.5f, 0.3f))
        assertRect(CropRect(0f, 0.9f, 1f, 1f), CropBox.drag(CropRect.FULL, CropHandle.TOP, 0f, 0.9f))
        // Far past what used to be the limit of 45 % an edge.
        assertRect(CropRect(0.1f, 0.1f, 0.2f, 0.95f), CropBox.drag(CropRect(0.1f, 0.1f, 1f, 1f), CropHandle.BOTTOM_RIGHT, -0.8f, -0.05f))
    }

    @Test
    fun `dragging stops at the picture's edge and at the smallest size`() {
        val r = CropRect(0.2f, 0.2f, 0.6f, 0.6f)
        assertRect(CropRect(0f, 0.2f, 0.6f, 0.6f), CropBox.drag(r, CropHandle.LEFT, -1f, 0f))
        assertRect(CropRect(0.2f, 0.2f, 0.25f, 0.6f), CropBox.drag(r, CropHandle.RIGHT, -2f, 0f))
        assertRect(CropRect(0.2f, 0.55f, 0.6f, 0.6f), CropBox.drag(r, CropHandle.TOP, 0f, 5f))
    }

    @Test
    fun `moving keeps the size and stays inside`() {
        assertRect(CropRect(0.6f, 0f, 1f, 0.3f), CropBox.drag(CropRect(0.2f, 0.2f, 0.6f, 0.5f), CropHandle.MOVE, 0.9f, -0.5f))
    }

    @Test
    fun `a held shape scales from the opposite corner`() {
        assertRect(CropRect(0f, 0f, 0.7f, 0.7f), CropBox.drag(CropRect(0f, 0f, 0.5f, 0.5f), CropHandle.BOTTOM_RIGHT, 0.3f, 0.1f, ratio = 1f))
        assertRect(CropRect(0.3f, 0.3f, 1f, 1f), CropBox.drag(CropRect(0.5f, 0.5f, 1f, 1f), CropHandle.TOP_LEFT, -0.2f, -0.2f, ratio = 1f))
        // Held at the picture's edge.
        assertRect(CropRect(0f, 0f, 1f, 1f), CropBox.drag(CropRect(0f, 0f, 0.5f, 0.5f), CropHandle.BOTTOM_RIGHT, 1f, 1f, ratio = 1f))
        for ((dx, dy) in listOf(0.6f to 0f, -0.3f to 0.4f, 2f to 2f, -2f to -2f)) {
            val r = CropBox.drag(CropRect(0f, 0f, 0.4f, 0.2f), CropHandle.BOTTOM_RIGHT, dx, dy, ratio = 2f)
            assertEquals("$dx,$dy → $r", 2f, r.width / r.height, 1e-3f)
            assertInside(r)
        }
    }

    @Test
    fun `a held shape resizes from an edge about the middle`() {
        val r = CropRect(0.25f, 0.25f, 0.75f, 0.75f)
        assertRect(CropRect(0.25f, 0.2f, 0.85f, 0.8f), CropBox.drag(r, CropHandle.RIGHT, 0.1f, 0f, ratio = 1f))
        assertRect(CropRect(0.25f, 0.125f, 1f, 0.875f), CropBox.drag(r, CropHandle.RIGHT, 1f, 0f, ratio = 1f))
        assertRect(CropRect(0.275f, 0.3f, 0.725f, 0.75f), CropBox.drag(r, CropHandle.TOP, 0f, 0.05f, ratio = 1f))
        for (h in listOf(CropHandle.LEFT, CropHandle.TOP, CropHandle.RIGHT, CropHandle.BOTTOM)) {
            for (d in listOf(-2f, -0.1f, 0.1f, 2f)) {
                val out = CropBox.drag(CropRect(0.1f, 0.4f, 0.5f, 0.6f), h, d, d, ratio = 2f)
                assertEquals("$h $d → $out", 2f, out.width / out.height, 1e-3f)
                assertInside(out)
            }
        }
    }

    @Test
    fun `a held shape is never smaller than the smallest size either way`() {
        val r = CropBox.drag(CropRect(0f, 0f, 0.8f, 0.2f), CropHandle.BOTTOM_RIGHT, -5f, -5f, ratio = 4f)
        assertEquals(CropBox.MIN, r.height, eps)
        assertEquals(4 * CropBox.MIN, r.width, eps)
    }

    @Test
    fun `picking a shape fits the largest box of it where the crop is`() {
        assertRect(CropRect(0f, 0f, 0.5f, 1f), CropBox.fitRatio(CropRect(0.1f, 0.1f, 0.3f, 0.3f), 0.5f))
        assertRect(CropRect(0f, 0.25f, 1f, 0.75f), CropBox.fitRatio(CropRect.FULL, 2f))
        assertRect(CropRect.FULL, CropBox.fitRatio(CropRect(0.4f, 0.4f, 0.5f, 0.5f), 1f))
    }

    @Test
    fun `pixel ratios become ratios of fractions`() {
        // A square box on a 9:16 picture is 16/9 as wide, in fractions, as it is high.
        assertEquals(16f / 9f, CropBox.normalizedRatio(1f, 9f / 16f), 1e-5f)
        assertEquals(1f, CropBox.normalizedRatio(9f / 16f, 9f / 16f), 1e-5f)
    }

    @Test
    fun `a touch takes the nearest corner, then an edge, then the inside`() {
        val r = CropRect(0.2f, 0.2f, 0.8f, 0.8f)
        fun at(x: Float, y: Float) = CropBox.handleAt(r, x, y, 0.05f, 0.05f)
        assertEquals(CropHandle.TOP_LEFT, at(0.21f, 0.19f))
        assertEquals(CropHandle.BOTTOM_RIGHT, at(0.84f, 0.83f))
        assertEquals(CropHandle.TOP, at(0.5f, 0.23f))
        assertEquals(CropHandle.RIGHT, at(0.77f, 0.5f))
        assertEquals(CropHandle.MOVE, at(0.5f, 0.5f))
        assertNull(at(0.05f, 0.05f))
        assertNull(at(0.5f, 0.95f))
        // A box smaller than a fingertip: the nearest corner.
        val tiny = CropRect(0.5f, 0.5f, 0.52f, 0.52f)
        assertEquals(CropHandle.BOTTOM_RIGHT, CropBox.handleAt(tiny, 0.519f, 0.518f, 0.05f, 0.05f))
    }

    @Test
    fun `a box dragged on a turned picture lands on the right part of the source`() {
        val canvas = CanvasSettings(rotation = 90)
        // Keep the left half of what is shown: for a picture turned clockwise that is its bottom half.
        val insets = CropBox.insets(CropRect(0f, 0f, 0.5f, 1f), canvas)
        assertEquals(CropInsets(top = 0.5f), insets)
        assertRect(CropRect(0f, 0f, 0.5f, 1f), CropBox.shown(canvas.copy(crop = insets)))
    }
}
