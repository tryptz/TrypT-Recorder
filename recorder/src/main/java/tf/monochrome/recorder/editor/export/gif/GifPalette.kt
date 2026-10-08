package tf.monochrome.recorder.editor.export.gif

import kotlin.math.max

/**
 * How often each colour occurs, over a grid of 32 levels per channel — with
 * each cell's exact mean colour kept beside its count, so a flat colour
 * (a toolbar, a background) comes back exactly, not as its cell's centre.
 */
class ColorHistogram {
    internal val counts = LongArray(CELLS)
    internal val sumR = LongArray(CELLS)
    internal val sumG = LongArray(CELLS)
    internal val sumB = LongArray(CELLS)

    /** Adds every [step]th pixel of [argb] (only the first [size] are read). */
    fun add(argb: IntArray, size: Int = argb.size, step: Int = 1) {
        var i = 0
        while (i < size) {
            val c = argb[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val cell = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            counts[cell]++
            sumR[cell] += r.toLong()
            sumG[cell] += g.toLong()
            sumB[cell] += b.toLong()
            i += step
        }
    }

    internal companion object {
        const val CELLS = 1 shl 15

        fun channel(cell: Int, axis: Int): Int = (cell shr (10 - 5 * axis)) and 31
    }
}

/**
 * Median cut: the box of colours with the most squared error is split in two
 * along its widest channel at its weighted median, until there are
 * [maxColors] boxes; each box becomes its mean colour. One palette for the
 * whole GIF keeps colours from flickering between frames.
 */
object MedianCut {
    fun palette(h: ColorHistogram, maxColors: Int): IntArray {
        require(maxColors in 1..256)
        val cells = (0 until ColorHistogram.CELLS).filter { h.counts[it] > 0 }.toIntArray()
        if (cells.isEmpty()) return intArrayOf(0)
        if (cells.size <= maxColors) return IntArray(cells.size) { mean(h, cells, it, it + 1) }

        val boxes = mutableListOf(Box(h, cells, 0, cells.size))
        while (boxes.size < maxColors) {
            val worst = boxes.filter { it.to - it.from > 1 }.maxByOrNull { it.error } ?: break
            boxes.remove(worst)
            boxes += worst.split()
        }
        return IntArray(boxes.size) { mean(h, cells, boxes[it].from, boxes[it].to) }
    }

    private fun mean(h: ColorHistogram, cells: IntArray, from: Int, to: Int): Int {
        var n = 0L
        var r = 0L
        var g = 0L
        var b = 0L
        for (i in from until to) {
            val c = cells[i]
            n += h.counts[c]
            r += h.sumR[c]
            g += h.sumG[c]
            b += h.sumB[c]
        }
        return (((r + n / 2) / n).toInt() shl 16) or (((g + n / 2) / n).toInt() shl 8) or ((b + n / 2) / n).toInt()
    }

    /** Cells [from, to) of [cells]; splitting reorders them in place. */
    private class Box(val h: ColorHistogram, val cells: IntArray, val from: Int, val to: Int) {
        /** Squared error per channel of the cells' means about the box's. */
        private val channelError = DoubleArray(3)
        val error: Double

        init {
            var n = 0.0
            val sum = DoubleArray(3)
            val sumSq = DoubleArray(3)
            for (i in from until to) {
                val c = cells[i]
                val count = h.counts[c].toDouble()
                n += count
                for (axis in 0..2) {
                    val m = cellMean(c, axis)
                    sum[axis] += m * count
                    sumSq[axis] += m * m * count
                }
            }
            for (axis in 0..2) channelError[axis] = max(0.0, sumSq[axis] - sum[axis] * sum[axis] / n)
            error = channelError.sum()
        }

        private fun cellMean(c: Int, axis: Int): Double {
            val s = when (axis) {
                0 -> h.sumR[c]
                1 -> h.sumG[c]
                else -> h.sumB[c]
            }
            return s.toDouble() / h.counts[c]
        }

        fun split(): List<Box> {
            // The channel with the most error that the cells actually spread over.
            val axis = (0..2).filter { spread(it) }.maxBy { channelError[it] }
            val weight = LongArray(32)
            for (i in from until to) weight[ColorHistogram.channel(cells[i], axis)] += h.counts[cells[i]]
            val lo = weight.indexOfFirst { it > 0 }
            val hi = weight.indexOfLast { it > 0 }
            val half = (from until to).sumOf { h.counts[cells[it]] } / 2
            // The weighted median level, kept below the top so neither half is empty.
            var cut = lo
            var acc = weight[lo]
            while (cut + 1 < hi && acc < half) acc += weight[++cut]
            // Partition: levels up to the cut first.
            var i = from
            var j = to - 1
            while (i <= j) {
                if (ColorHistogram.channel(cells[i], axis) <= cut) {
                    i++
                } else {
                    val t = cells[i]
                    cells[i] = cells[j]
                    cells[j] = t
                    j--
                }
            }
            return listOf(Box(h, cells, from, i), Box(h, cells, i, to))
        }

        private fun spread(axis: Int): Boolean {
            val first = ColorHistogram.channel(cells[from], axis)
            return (from + 1 until to).any { ColorHistogram.channel(cells[it], axis) != first }
        }
    }
}

/**
 * Up to 255 colours (0xRRGGBB), and which of them each pixel becomes. The
 * index after the last colour is left for transparency.
 *
 * Pixels close to a palette colour take it as they are, so flat areas —
 * most of a screen recording — stay clean; the rest get an ordered (Bayer)
 * dither. Ordered rather than error diffusion because its pattern is fixed to
 * the pixel's position: an unchanged area dithers the same way every frame,
 * so it neither shimmers nor costs bytes to re-send.
 */
class GifPalette(val colors: IntArray) {
    init {
        require(colors.size in 1..255) { "${colors.size} colours" }
    }

    val size: Int get() = colors.size

    /** The index transparent pixels use. */
    val transparentIndex: Int get() = colors.size

    /** Bits per entry of the colour table, which holds the colours and the transparent index. */
    val tableBits: Int = max(2, 32 - Integer.numberOfLeadingZeros(colors.size))

    /** Nearest colour per cell of 64 levels a channel; [UNKNOWN] until first asked. */
    private val nearest = ByteArray(1 shl 18).also { it.fill(UNKNOWN) }

    /** The palette index for [rgb] at pixel ([x], [y]). */
    fun indexFor(rgb: Int, x: Int, y: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        val plain = nearestIndex(r, g, b)
        val p = colors[plain]
        val dr = r - ((p shr 16) and 0xFF)
        val dg = g - ((p shr 8) and 0xFF)
        val db = b - (p and 0xFF)
        if (dr * dr + dg * dg + db * db <= SNAP_SQ) return plain
        val d = BAYER[((y and 7) shl 3) or (x and 7)]
        return nearestIndex(clamp(r + d), clamp(g + d), clamp(b + d))
    }

    /** The palette colour closest to (r, g, b), by squared distance. */
    fun nearestIndex(r: Int, g: Int, b: Int): Int {
        val cell = ((r shr 2) shl 12) or ((g shr 2) shl 6) or (b shr 2)
        val cached = nearest[cell]
        if (cached != UNKNOWN) return cached.toInt() and 0xFF
        // The cell's centre stands for every colour in it.
        val cr = (r and 0xFC) + 2
        val cg = (g and 0xFC) + 2
        val cb = (b and 0xFC) + 2
        var best = 0
        var bestDistance = Int.MAX_VALUE
        for (i in colors.indices) {
            val c = colors[i]
            val dr = cr - ((c shr 16) and 0xFF)
            val dg = cg - ((c shr 8) and 0xFF)
            val db = cb - (c and 0xFF)
            val dist = dr * dr + dg * dg + db * db
            if (dist < bestDistance) {
                bestDistance = dist
                best = i
            }
        }
        nearest[cell] = best.toByte()
        return best
    }

    private fun clamp(c: Int): Int = if (c < 0) 0 else if (c > 255) 255 else c

    companion object {
        /** Index 255 is never a colour (it would be the transparent one), so it marks "not looked up yet". */
        private const val UNKNOWN: Byte = -1

        /** Within this distance of a palette colour a pixel is not dithered. */
        private const val SNAP_SQ = 6 * 6

        /** Dither spread: offsets run about ±[SPREAD] / 2. */
        private const val SPREAD = 24

        /** 8×8 Bayer thresholds, centred on zero and scaled to [SPREAD]. */
        private val BAYER: IntArray = run {
            val m = intArrayOf(
                0, 32, 8, 40, 2, 34, 10, 42,
                48, 16, 56, 24, 50, 18, 58, 26,
                12, 44, 4, 36, 14, 46, 6, 38,
                60, 28, 52, 20, 62, 30, 54, 22,
                3, 35, 11, 43, 1, 33, 9, 41,
                51, 19, 59, 27, 49, 17, 57, 25,
                15, 47, 7, 39, 13, 45, 5, 37,
                63, 31, 55, 23, 61, 29, 53, 21,
            )
            IntArray(64) { ((m[it] * 2 - 63) * SPREAD) / 128 }
        }

        /** A palette for [h]: as many colours as it needs, up to 255. */
        fun from(h: ColorHistogram): GifPalette = GifPalette(MedianCut.palette(h, 255))
    }
}
