package tf.monochrome.recorder.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmTest {
    @Test
    fun `mono is duplicated into both channels`() {
        val out = ShortArray(6)
        Pcm.monoToStereo(shortArrayOf(1, -2, 3), 3, out)
        assertArrayEquals(shortArrayOf(1, 1, -2, -2, 3, 3), out)
    }

    @Test
    fun `mixing saturates instead of wrapping`() {
        val dst = shortArrayOf(30_000, -30_000, 100)
        Pcm.mixInto(dst, shortArrayOf(10_000, -10_000, 23), 3)
        assertArrayEquals(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 123), dst)
    }

    @Test
    fun `fifo pads underruns with silence`() {
        val f = ShortFifo(8)
        f.write(shortArrayOf(1, 2, 3), 3)
        val dst = ShortArray(5) { 9 }
        assertEquals(3, f.readPadded(dst, 5))
        assertArrayEquals(shortArrayOf(1, 2, 3, 0, 0), dst)
        assertEquals(0, f.available)
    }

    @Test
    fun `fifo overrun drops the oldest samples`() {
        val f = ShortFifo(4)
        f.write(shortArrayOf(1, 2, 3), 3)
        f.write(shortArrayOf(4, 5, 6), 3)
        val dst = ShortArray(4)
        assertEquals(4, f.readPadded(dst, 4))
        assertArrayEquals(shortArrayOf(3, 4, 5, 6), dst)
    }

    @Test
    fun `fifo keeps order across the wrap point`() {
        val f = ShortFifo(4)
        val dst = ShortArray(3)
        f.write(shortArrayOf(1, 2, 3), 3)
        f.readPadded(dst, 2)
        f.write(shortArrayOf(4, 5, 6), 3)
        assertEquals(4, f.available)
        val all = ShortArray(4)
        f.readPadded(all, 4)
        assertArrayEquals(shortArrayOf(3, 4, 5, 6), all)
    }

    @Test
    fun `a write larger than the fifo keeps only the newest`() {
        val f = ShortFifo(3)
        f.write(shortArrayOf(1, 2, 3, 4, 5), 5)
        val dst = ShortArray(3)
        f.readPadded(dst, 3)
        assertArrayEquals(shortArrayOf(3, 4, 5), dst)
    }
}
