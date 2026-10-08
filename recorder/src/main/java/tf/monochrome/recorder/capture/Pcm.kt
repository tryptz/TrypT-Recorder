package tf.monochrome.recorder.capture

/**
 * 16-bit PCM helpers for the audio pump. The output track is always 48 kHz
 * interleaved stereo, whatever mix of sources feeds it.
 */
object Pcm {
    /** Duplicates [frames] mono samples into interleaved stereo in [out]. */
    fun monoToStereo(mono: ShortArray, frames: Int, out: ShortArray) {
        for (i in 0 until frames) {
            val s = mono[i]
            out[2 * i] = s
            out[2 * i + 1] = s
        }
    }

    /** Adds [src] into [dst] sample by sample, saturating instead of wrapping. */
    fun mixInto(dst: ShortArray, src: ShortArray, samples: Int) {
        for (i in 0 until samples) {
            val sum = dst[i] + src[i]
            dst[i] = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}

/**
 * Bounded FIFO between the device-audio capture and the microphone, which is
 * the master clock when both are recorded.
 *
 * The two run off different clocks and deliver in different-sized bursts, so
 * neither can simply be read in lockstep with the other. Device audio is
 * drained into this buffer whenever it has data; each microphone block takes
 * the same number of frames back out. An underrun pads with silence; an
 * overrun drops the *oldest* samples, which keeps latency bounded instead of
 * letting it creep up for the length of the recording.
 */
class ShortFifo(capacity: Int) {
    private val buf = ShortArray(capacity)
    private var head = 0 // next read
    private var size = 0

    val available: Int get() = size

    fun write(src: ShortArray, count: Int) {
        var from = 0
        var n = count
        if (n > buf.size) {
            // Only the newest capacity's worth can survive anyway.
            from = n - buf.size
            n = buf.size
        }
        val overflow = size + n - buf.size
        if (overflow > 0) drop(overflow)
        var tail = (head + size) % buf.size
        for (i in 0 until n) {
            buf[tail] = src[from + i]
            tail = if (tail + 1 == buf.size) 0 else tail + 1
        }
        size += n
    }

    /** Reads exactly [count] samples into [dst], padding with zeros past what is buffered. Returns how many were real. */
    fun readPadded(dst: ShortArray, count: Int): Int {
        val real = minOf(count, size)
        for (i in 0 until real) {
            dst[i] = buf[head]
            head = if (head + 1 == buf.size) 0 else head + 1
        }
        size -= real
        for (i in real until count) dst[i] = 0
        return real
    }

    private fun drop(n: Int) {
        val d = minOf(n, size)
        head = (head + d) % buf.size
        size -= d
    }
}
