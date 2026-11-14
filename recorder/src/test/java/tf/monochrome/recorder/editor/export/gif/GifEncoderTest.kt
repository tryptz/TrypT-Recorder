package tf.monochrome.recorder.editor.export.gif

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * Every GIF here is read back with [GifReader], a decoder written from the
 * GIF89a specification, so the encoder is checked against the format rather
 * than against itself.
 */
class GifEncoderTest {
    private fun decode(bytes: ByteArray) = GifReader(bytes)

    private fun encode(w: Int, h: Int, palette: GifPalette, vararg frames: Pair<IntArray, Int>): ByteArray {
        val bytes = ByteArrayOutputStream()
        GifEncoder(bytes, w, h, palette).use { e -> frames.forEach { (px, delay) -> e.addFrame(px, delay) } }
        return bytes.toByteArray()
    }

    private val red = 0xE53935
    private val blue = 0x1E88E5
    private val grey = 0x1E1E1E
    private val white = 0xFAFAFA
    private val flat = GifPalette(intArrayOf(red, blue, grey, white))

    private fun fill(w: Int, h: Int, rgb: Int) = IntArray(w * h) { rgb }

    @Test
    fun `flat colours come back exactly`() {
        val w = 16
        val h = 8
        val px = IntArray(w * h) { i -> listOf(red, blue, grey, white)[(i % w) / 4] }
        val gif = decode(encode(w, h, flat, px to 10))
        assertEquals(w, gif.width)
        assertEquals(h, gif.height)
        assertArrayEquals(px, gif.frames.single().pixels)
        assertEquals(10, gif.frames.single().delayCs)
    }

    @Test
    fun `later frames send only the box that changed`() {
        val w = 20
        val h = 12
        val a = fill(w, h, grey)
        val b = a.copyOf().also { for (y in 3..4) for (x in 5..7) it[y * w + x] = red }
        val gif = decode(encode(w, h, flat, a to 7, b to 7))
        val second = gif.frames[1]
        assertEquals(listOf(5, 3, 3, 2), listOf(second.left, second.top, second.width, second.height))
        assertArrayEquals(b, second.pixels)
    }

    @Test
    fun `an unchanged frame lengthens the one before instead of being written`() {
        val w = 8
        val h = 8
        val a = fill(w, h, grey)
        val b = fill(w, h, white)
        val gif = decode(encode(w, h, flat, a to 7, a.copyOf() to 6, a.copyOf() to 7, b to 7))
        assertEquals(listOf(20, 7), gif.frames.map { it.delayCs })
        assertArrayEquals(b, gif.frames.last().pixels)
    }

    @Test
    fun `a pixel that comes out as the colour already shown is not sent again`() {
        // Decoder noise of a level or two on a flat colour quantizes to the same entry.
        val w = 8
        val h = 8
        val a = fill(w, h, grey)
        val nudged = fill(w, h, grey + 0x020202)
        val gif = decode(encode(w, h, flat, a to 5, nudged to 5))
        assertEquals(1, gif.frames.size)
        assertEquals(10, gif.frames.single().delayCs)
    }

    @Test
    fun `loops forever`() {
        // A NETSCAPE2.0 loop count of 0 means forever.
        assertEquals(0, decode(encode(4, 4, flat, fill(4, 4, red) to 10)).loopCount)
    }

    @Test
    fun `noisy frames survive dictionary resets and every code width`() {
        // Random pixels fill LZW's 4096-code dictionary over and over.
        val rnd = Random(7)
        // 255 colours far enough apart that each one is its own nearest.
        val levels = (0..6).map { it * 40 + 2 }
        val colors = levels.flatMap { r -> levels.flatMap { g -> levels.map { b -> (r shl 16) or (g shl 8) or b } } }
            .take(255).toIntArray()
        val palette = GifPalette(colors)
        val w = 211
        val h = 157
        val a = IntArray(w * h) { colors[rnd.nextInt(colors.size)] }
        val b = IntArray(w * h) { colors[rnd.nextInt(colors.size)] }
        val gif = decode(encode(w, h, palette, a to 4, b to 4))
        assertArrayEquals(a, gif.frames[0].pixels)
        assertArrayEquals(b, gif.frames[1].pixels)
    }

    @Test
    fun `two-colour palettes use the smallest code size`() {
        val rnd = Random(11)
        val palette = GifPalette(intArrayOf(0x000000, 0xFFFFFF))
        assertEquals(2, palette.tableBits)
        val w = 97
        val h = 61
        val a = IntArray(w * h) { if (rnd.nextBoolean()) 0xFFFFFF else 0 }
        assertArrayEquals(a, decode(encode(w, h, palette, a to 10)).frames.single().pixels)
    }

    @Test
    fun `a single pixel is a valid gif`() {
        val gif = decode(encode(1, 1, flat, intArrayOf(blue) to 3))
        assertArrayEquals(intArrayOf(blue), gif.frames.single().pixels)
    }

    @Test
    fun `counts the frames it wrote`() {
        val bytes = ByteArrayOutputStream()
        val e = GifEncoder(bytes, 4, 4, flat)
        e.addFrame(fill(4, 4, red), 5)
        e.addFrame(fill(4, 4, red), 5)
        e.addFrame(fill(4, 4, blue), 5)
        e.close()
        assertEquals(2, e.frameCount)
    }
}
