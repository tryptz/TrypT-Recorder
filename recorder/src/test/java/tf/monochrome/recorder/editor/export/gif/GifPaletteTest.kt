package tf.monochrome.recorder.editor.export.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class GifPaletteTest {
    private fun histogramOf(vararg pixels: IntArray) = ColorHistogram().apply { pixels.forEach { add(it) } }

    private fun distance(a: Int, b: Int): Double {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    /** Every colour of a 256-step ramp through each pair of channels: 3 × 65536 pixels, ~49k distinct. */
    private val ramp = IntArray(3 * 65536) { i ->
        val a = (i shr 8) and 0xFF
        val b = i and 0xFF
        when (i shr 16) {
            0 -> (a shl 16) or (b shl 8)
            1 -> (a shl 8) or b
            else -> (a shl 16) or b
        }
    }

    @Test
    fun `a few flat colours are kept exactly, not as their cell's centre`() {
        val colors = intArrayOf(0x1E1E1E, 0xFF5722, 0x2196F3)
        val palette = MedianCut.palette(histogramOf(IntArray(300) { colors[it % 3] }), 255)
        assertEquals(colors.toSet(), palette.toSet())
    }

    @Test
    fun `many colours are cut down to the limit`() {
        val palette = MedianCut.palette(histogramOf(ramp), 255)
        assertEquals(255, palette.size)
        assertEquals(255, palette.toSet().size)
        // Median cut spreads the colours: nothing is far from its nearest.
        val p = GifPalette(palette)
        val worst = ramp.maxOf { c -> distance(c, palette[p.nearestIndex((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)]) }
        assertTrue("worst error $worst", worst < 24)
    }

    @Test
    fun `a colour covering much of the picture gets its own entry`() {
        val background = 0x121212
        val palette = MedianCut.palette(histogramOf(ramp, IntArray(200_000) { background }), 255)
        assertTrue(background in palette)
    }

    @Test
    fun `no colours gives a palette of one`() {
        assertEquals(1, MedianCut.palette(ColorHistogram(), 255).size)
    }

    @Test
    fun `table size leaves room for the transparent index`() {
        assertEquals(2, GifPalette(IntArray(3)).tableBits)
        assertEquals(3, GifPalette(IntArray(4)).tableBits)
        assertEquals(8, GifPalette(IntArray(255) { it }).tableBits)
        assertEquals(255, GifPalette(IntArray(255) { it }).transparentIndex)
    }

    @Test
    fun `a pixel close to a palette colour is not dithered`() {
        val palette = GifPalette(intArrayOf(0x000000, 0x808080, 0xFFFFFF))
        for (y in 0 until 8) for (x in 0 until 8) assertEquals(1, palette.indexFor(0x838383, x, y))
    }

    @Test
    fun `colours between two entries are dithered in proportion`() {
        val palette = GifPalette(intArrayOf(0x707070, 0x909090))
        fun lighter(rgb: Int) = (0 until 64).count { palette.indexFor(rgb, it % 8, it / 8) == 1 }
        val nearDark = lighter(0x787878)
        val middle = lighter(0x808080)
        val nearLight = lighter(0x888888)
        assertTrue("$nearDark < $middle < $nearLight", nearDark < middle && middle < nearLight)
        assertTrue("middle $middle", middle in 24..40)
    }

    @Test
    fun `dithering depends only on position, so a still picture stays still`() {
        val palette = GifPalette(MedianCut.palette(histogramOf(ramp), 255))
        for (c in listOf(0x3A7F11, 0x808080, 0xC0FFEE)) {
            for (y in 0 until 8) for (x in 0 until 8) assertEquals(palette.indexFor(c, x, y), palette.indexFor(c, x + 8, y + 16))
        }
    }
}
