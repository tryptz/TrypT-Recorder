package tf.monochrome.recorder.editor.export.gif

/**
 * A GIF89a decoder for the tests, written from the specification rather
 * than from [GifEncoder]: it grows the code width when its own dictionary
 * fills, as a viewer does, so an encoder that widens at the wrong moment
 * fails here. (The JDK's ImageIO would do, but unit tests compile against
 * android.jar, which has no javax.imageio.)
 */
class GifReader(private val bytes: ByteArray) {
    class Frame(val pixels: IntArray, val delayCs: Int, val left: Int, val top: Int, val width: Int, val height: Int)

    var width = 0
        private set
    var height = 0
        private set

    /** The NETSCAPE2.0 loop count, or null if the file does not loop. */
    var loopCount: Int? = null
        private set

    /** Each frame laid over the ones before it, as a viewer shows it. */
    val frames = mutableListOf<Frame>()

    private var at = 0

    init {
        check(String(bytes, 0, 6, Charsets.US_ASCII) == "GIF89a")
        at = 6
        width = short()
        height = short()
        val packed = u8()
        at += 2
        val global = if (packed and 0x80 != 0) table(packed and 7) else IntArray(0)
        val canvas = IntArray(width * height)
        var delay = 0
        var transparent = -1
        loop@ while (true) {
            when (u8()) {
                0x21 -> when (u8()) {
                    0xF9 -> {
                        check(u8() == 4)
                        val flags = u8()
                        delay = short()
                        val index = u8()
                        transparent = if (flags and 1 != 0) index else -1
                        check(u8() == 0)
                    }
                    0xFF -> {
                        val data = subBlocks()
                        if (String(data, 0, 11, Charsets.US_ASCII) == "NETSCAPE2.0" && data[11].toInt() == 1) {
                            loopCount = (data[12].toInt() and 0xFF) or ((data[13].toInt() and 0xFF) shl 8)
                        }
                    }
                    else -> subBlocks()
                }
                0x2C -> {
                    val left = short()
                    val top = short()
                    val w = short()
                    val h = short()
                    val flags = u8()
                    check(flags and 0x40 == 0) { "interlaced" }
                    val colors = if (flags and 0x80 != 0) table(flags and 7) else global
                    val indices = lzw(u8(), w * h)
                    for (y in 0 until h) for (x in 0 until w) {
                        val i = indices[y * w + x]
                        if (i != transparent) canvas[(top + y) * width + left + x] = colors[i]
                    }
                    frames += Frame(canvas.copyOf(), delay, left, top, w, h)
                    delay = 0
                    transparent = -1
                }
                0x3B -> break@loop
                else -> error("unexpected block at ${at - 1}")
            }
        }
    }

    private fun u8(): Int = bytes[at++].toInt() and 0xFF

    private fun short(): Int = u8() or (u8() shl 8)

    private fun table(sizeBits: Int): IntArray = IntArray(1 shl (sizeBits + 1)) { (u8() shl 16) or (u8() shl 8) or u8() }

    private fun subBlocks(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val n = u8()
            if (n == 0) return out.toByteArray()
            out.write(bytes, at, n)
            at += n
        }
    }

    private fun lzw(minCodeSize: Int, count: Int): IntArray {
        val data = subBlocks()
        val clear = 1 shl minCodeSize
        val end = clear + 1
        val prefix = IntArray(4096)
        val suffix = IntArray(4096)
        val length = IntArray(4096)
        for (i in 0 until clear) {
            suffix[i] = i
            length[i] = 1
        }
        val out = IntArray(count)
        var written = 0
        var codeSize = minCodeSize + 1
        var next = end + 1
        var previous = -1
        var bitPos = 0

        fun firstOf(code: Int): Int {
            var c = code
            while (length[c] > 1) c = prefix[c]
            return suffix[c]
        }

        fun emit(code: Int) {
            val n = length[code]
            var c = code
            for (i in n - 1 downTo 0) {
                if (written + i < count) out[written + i] = suffix[c]
                c = prefix[c]
            }
            written += n
        }

        while (true) {
            check(bitPos + codeSize <= data.size * 8) { "ran out of data without an end code" }
            var code = 0
            for (b in 0 until codeSize) {
                val bit = (data[(bitPos + b) shr 3].toInt() shr ((bitPos + b) and 7)) and 1
                code = code or (bit shl b)
            }
            bitPos += codeSize
            when {
                code == clear -> {
                    codeSize = minCodeSize + 1
                    next = end + 1
                    previous = -1
                }
                code == end -> break
                previous == -1 -> {
                    check(code < clear) { "first code $code after a clear" }
                    emit(code)
                    previous = code
                }
                else -> {
                    check(code <= next) { "code $code before it exists (next $next)" }
                    val first = if (code < next) firstOf(code) else firstOf(previous)
                    if (next < 4096) {
                        prefix[next] = previous
                        suffix[next] = first
                        length[next] = length[previous] + 1
                        next++
                    }
                    emit(code)
                    if (next == 1 shl codeSize && codeSize < 12) codeSize++
                    previous = code
                }
            }
        }
        check(written == count) { "decoded $written pixels, expected $count" }
        return out
    }
}
