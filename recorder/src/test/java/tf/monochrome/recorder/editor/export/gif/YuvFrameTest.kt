package tf.monochrome.recorder.editor.export.gif

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class YuvFrameTest {
    /** A planar frame of [lumas] (row by row) with one chroma value throughout. */
    private fun frame(w: Int, h: Int, lumas: IntArray, cb: Int = 128, cr: Int = 128) = YuvFrame().apply {
        width = w
        height = h
        y = ByteArray(w * h) { lumas[it].toByte() }
        u = ByteArray((w / 2) * (h / 2)) { cb.toByte() }
        v = ByteArray((w / 2) * (h / 2)) { cr.toByte() }
        yRowStride = w
        uvRowStride = w / 2
    }

    private fun one(lumaY: Int, cb: Int, cr: Int, colors: YuvColors): Int {
        val out = IntArray(4)
        frame(2, 2, IntArray(4) { lumaY }, cb, cr).toArgb(out, 0, colors)
        return out[0]
    }

    private fun assertRgb(expected: Int, actual: Int) {
        for (shift in listOf(16, 8, 0)) {
            val e = (expected shr shift) and 0xFF
            val a = (actual shr shift) and 0xFF
            assertTrue("expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}", abs(e - a) <= 2)
        }
        assertEquals(0xFF, actual ushr 24)
    }

    @Test
    fun `limited range runs from 16 to 235`() {
        assertEquals(0xFF000000.toInt(), one(16, 128, 128, YuvColors.BT709_LIMITED))
        assertEquals(0xFFFFFFFF.toInt(), one(235, 128, 128, YuvColors.BT709_LIMITED))
        assertEquals(0xFF000000.toInt(), one(0, 128, 128, YuvColors.BT709_LIMITED))
        assertEquals(0xFFFFFFFF.toInt(), one(255, 128, 128, YuvColors.BT709_FULL))
    }

    @Test
    fun `primaries decode under their own standard`() {
        // Pure red, green and blue as BT.709 and BT.601 encode them, limited range.
        assertRgb(0xFF0000, one(63, 102, 240, YuvColors.BT709_LIMITED))
        assertRgb(0x00FF00, one(173, 42, 26, YuvColors.BT709_LIMITED))
        assertRgb(0x0000FF, one(32, 240, 118, YuvColors.BT709_LIMITED))
        assertRgb(0xFF0000, one(81, 90, 240, YuvColors.BT601_LIMITED))
        assertRgb(0x00FF00, one(145, 54, 34, YuvColors.BT601_LIMITED))
    }

    @Test
    fun `turns the picture clockwise as asked`() {
        // 4 × 2, luma counting up row by row: a b c d / e f g h.
        val lumas = intArrayOf(16, 36, 56, 76, 96, 116, 136, 156)
        val f = frame(4, 2, lumas)
        fun turned(rotation: Int): List<Int> {
            val out = IntArray(8)
            f.toArgb(out, rotation, YuvColors.BT709_FULL)
            return out.map { it and 0xFF }
        }
        assertEquals(lumas.toList(), turned(0))
        // 90°: 2 wide, 4 high — e a / f b / g c / h d.
        assertEquals(listOf(96, 16, 116, 36, 136, 56, 156, 76), turned(90))
        assertEquals(lumas.reversed(), turned(180))
        // 270°: d h / c g / b f / a e.
        assertEquals(listOf(76, 156, 56, 136, 36, 116, 16, 96), turned(270))
        assertEquals(2, f.outputWidth(90))
        assertEquals(4, f.outputHeight(90))
    }

    @Test
    fun `reads interleaved chroma, padded rows and a crop`() {
        // NV12: chroma in one interleaved plane, rows padded to 8, the picture
        // 2 × 2 at (2, 2) of a 4 × 4 buffer. Only that corner is red.
        val stride = 8
        val f = YuvFrame().apply {
            y = ByteArray(stride * 4) { 16 }
            for (row in 2..3) for (col in 2..3) y[row * stride + col] = 63
            val uv = ByteArray(stride * 2) { 128.toByte() }
            uv[1 * stride + 2] = 102
            uv[1 * stride + 3] = 240.toByte()
            u = uv
            v = uv.copyOfRange(1, uv.size)
            yRowStride = stride
            uvRowStride = stride
            uvPixelStride = 2
            left = 2
            top = 2
            width = 2
            height = 2
        }
        val out = IntArray(4)
        f.toArgb(out, 0, YuvColors.BT709_LIMITED)
        out.forEach { assertRgb(0xFF0000, it) }
    }

    @Test
    fun `picks the standard from the decoder's keys`() {
        val grey = intArrayOf(126, 126, 126, 126)
        val out601 = IntArray(4).also { frame(2, 2, grey, 90, 240).toArgb(it, 0, YuvColors.pick(bt601 = true, fullRange = false)) }
        val out709 = IntArray(4).also { frame(2, 2, grey, 90, 240).toArgb(it, 0, YuvColors.pick(bt601 = false, fullRange = false)) }
        assertTrue(!out601.contentEquals(out709))
        assertArrayEquals(out601, IntArray(4).also { frame(2, 2, grey, 90, 240).toArgb(it, 0, YuvColors.BT601_LIMITED) })
    }
}
