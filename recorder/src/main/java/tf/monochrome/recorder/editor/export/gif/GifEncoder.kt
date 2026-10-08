package tf.monochrome.recorder.editor.export.gif

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.OutputStream

/**
 * Writes an animated GIF89a to [out], one frame at a time: one palette for
 * the whole file, looping forever.
 *
 * Every frame after the first carries only what changed. A pixel is left
 * transparent — "keep what is there" — when it would come out as the colour
 * already shown, and the frame is cut to the box around what is left. A frame
 * where nothing changed is not written at all: the one before stays up
 * longer. A screen recording is mostly still, so this is most of a GIF's size.
 *
 * Only an exact match counts as unchanged. Letting pixels within a few levels
 * of what is shown stand would save bytes, but in a slowly changing gradient
 * the ones that have drifted past the limit update in patches, and the edges
 * between patches show as contours the source does not have.
 */
class GifEncoder(
    private val out: OutputStream,
    val width: Int,
    val height: Int,
    private val palette: GifPalette,
) : Closeable {
    init {
        require(width in 1..0xFFFF && height in 1..0xFFFF) { "${width}x$height" }
    }

    /** The picture as it stands after the frames so far: 0xRRGGBB per pixel. */
    private val shown = IntArray(width * height)
    private val indices = ByteArray(width * height)
    private val lzw = LzwEncoder(palette.tableBits)

    /** The last frame, held back until we know how long it stays up. */
    private val pending = ByteArrayOutputStream()
    private var pendingDelayCs = 0
    private var pendingTransparent = false
    private var hasPending = false

    /** Frames written, after merging unchanged ones. */
    var frameCount = 0
        private set

    init {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(width)
        writeShort(height)
        val bits = palette.tableBits
        out.write(0x80 or ((bits - 1) shl 4) or (bits - 1)) // global colour table of 2^bits entries
        out.write(0) // background colour index
        out.write(0) // square pixels
        for (i in 0 until (1 shl bits)) {
            val c = if (i < palette.size) palette.colors[i] else 0
            out.write((c shr 16) and 0xFF)
            out.write((c shr 8) and 0xFF)
            out.write(c and 0xFF)
        }
        // NETSCAPE2.0 application extension: loop forever.
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(3, 1, 0, 0, 0))
    }

    /**
     * Adds a frame of 0xAARRGGBB pixels ([width] × [height], row by row) that
     * stays up for [delayCs] hundredths of a second.
     */
    fun addFrame(argb: IntArray, delayCs: Int) {
        require(argb.size >= width * height)
        val first = !hasPending && frameCount == 0
        val transparent = palette.transparentIndex
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val at = row + x
                val i = palette.indexFor(argb[at] and 0xFFFFFF, x, y)
                val c = palette.colors[i]
                val index = if (!first && c == shown[at]) {
                    transparent
                } else {
                    shown[at] = c
                    i
                }
                indices[at] = index.toByte()
                if (index != transparent) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) {
            // Nothing changed: the frame before stays up for this one's time too.
            pendingDelayCs += delayCs
            return
        }
        flushPending()
        pending.reset()
        pending.write(0x2C) // image descriptor
        writeShort(pending, minX)
        writeShort(pending, minY)
        writeShort(pending, maxX - minX + 1)
        writeShort(pending, maxY - minY + 1)
        pending.write(0) // no local colour table, not interlaced
        lzw.encode(indices, width, minX, minY, maxX - minX + 1, maxY - minY + 1, pending)
        pendingDelayCs = delayCs
        pendingTransparent = !first
        hasPending = true
    }

    /** Writes the held frame, then the trailer. Does not close [out]. */
    override fun close() {
        flushPending()
        out.write(0x3B)
        out.flush()
    }

    private fun flushPending() {
        if (!hasPending) return
        // Graphic control extension: leave the frame in place, how long, which index is see-through.
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4))
        out.write((1 shl 2) or (if (pendingTransparent) 1 else 0))
        writeShort(pendingDelayCs.coerceIn(0, 0xFFFF))
        out.write(if (pendingTransparent) palette.transparentIndex else 0)
        out.write(0)
        pending.writeTo(out)
        hasPending = false
        frameCount++
    }

    private fun writeShort(v: Int) = writeShort(out, v)

    private fun writeShort(o: OutputStream, v: Int) {
        o.write(v and 0xFF)
        o.write((v shr 8) and 0xFF)
    }
}

/**
 * GIF's LZW: codes from [minCodeSize] + 1 bits up to 12, packed least
 * significant bit first into sub-blocks of at most 255 bytes. When the
 * dictionary fills, a clear code starts it again. The code-size steps match
 * the reference encoder's, which every decoder expects.
 */
internal class LzwEncoder(private val minCodeSize: Int) {
    init {
        require(minCodeSize in 2..8)
    }

    private val clearCode = 1 shl minCodeSize
    private val endCode = clearCode + 1

    // Open-addressed (prefix code, next index) → code. 4096 codes at most, so 8192 slots.
    private val keys = IntArray(TABLE_SIZE)
    private val values = ShortArray(TABLE_SIZE)

    private var codeSize = 0
    private var nextCode = 0
    private var resetAfterWrite = false

    private var bitBuffer = 0L
    private var bitCount = 0
    private val block = ByteArray(255)
    private var blockLength = 0
    private lateinit var sink: OutputStream

    /** Encodes the [w] × [h] box at ([x], [y]) of [pixels], whose rows are [stride] wide. */
    fun encode(pixels: ByteArray, stride: Int, x: Int, y: Int, w: Int, h: Int, out: OutputStream) {
        sink = out
        out.write(minCodeSize)
        bitBuffer = 0
        bitCount = 0
        blockLength = 0
        codeSize = minCodeSize + 1
        resetTable()
        resetAfterWrite = false
        emit(clearCode)

        var prefix = pixels[y * stride + x].toInt() and 0xFF
        var first = true
        for (row in y until y + h) {
            val base = row * stride
            for (col in x until x + w) {
                if (first) {
                    first = false
                    continue
                }
                val k = pixels[base + col].toInt() and 0xFF
                val key = (prefix shl 8) or k
                val found = find(key)
                if (found >= 0) {
                    prefix = found
                    continue
                }
                emit(prefix)
                if (nextCode < MAX_CODES) {
                    put(key, nextCode++)
                } else {
                    resetTable()
                    resetAfterWrite = true
                    emit(clearCode)
                }
                prefix = k
            }
        }
        emit(prefix)
        emit(endCode)
        if (bitCount > 0) {
            byte((bitBuffer and 0xFF).toInt())
            bitBuffer = 0
            bitCount = 0
        }
        flushBlock()
        out.write(0) // block terminator
    }

    private fun resetTable() {
        keys.fill(0)
        nextCode = endCode + 1
    }

    private fun emit(code: Int) {
        bitBuffer = bitBuffer or (code.toLong() shl bitCount)
        bitCount += codeSize
        while (bitCount >= 8) {
            byte((bitBuffer and 0xFF).toInt())
            bitBuffer = bitBuffer ushr 8
            bitCount -= 8
        }
        // Widen once the next code to be assigned no longer fits — as the decoder will.
        if (resetAfterWrite) {
            codeSize = minCodeSize + 1
            resetAfterWrite = false
        } else if (nextCode > (1 shl codeSize) - 1 && codeSize < MAX_BITS) {
            codeSize++
        }
    }

    private fun byte(b: Int) {
        block[blockLength++] = b.toByte()
        if (blockLength == block.size) flushBlock()
    }

    private fun flushBlock() {
        if (blockLength == 0) return
        sink.write(blockLength)
        sink.write(block, 0, blockLength)
        blockLength = 0
    }

    private fun slot(key: Int): Int = ((key * -0x61c88647) ushr (32 - TABLE_BITS))

    private fun find(key: Int): Int {
        var i = slot(key)
        while (true) {
            val k = keys[i]
            if (k == 0) return -1
            if (k == key + 1) return values[i].toInt()
            i = (i + 1) and (TABLE_SIZE - 1)
        }
    }

    private fun put(key: Int, code: Int) {
        var i = slot(key)
        while (keys[i] != 0) i = (i + 1) and (TABLE_SIZE - 1)
        keys[i] = key + 1
        values[i] = code.toShort()
    }

    private companion object {
        const val MAX_BITS = 12
        const val MAX_CODES = 1 shl MAX_BITS
        const val TABLE_BITS = 13
        const val TABLE_SIZE = 1 shl TABLE_BITS
    }
}
