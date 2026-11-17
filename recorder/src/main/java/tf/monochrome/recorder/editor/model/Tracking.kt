package tf.monochrome.recorder.editor.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

@Serializable
data class CoverSample(val sourceUs: Long, val box: CropRect, val matched: Boolean = true)

/**
 * A cover box that follows something through a clip. Only the samples the
 * tracker was sure of (and the user's corrections) say where it is: between
 * two of those the box moves from one to the other, before the first and
 * after the last it stays where it was seen, and a sample the tracker lost
 * is passed over. Only with no sure sample at all does it cover the whole
 * frame — blacking out the picture wherever one match failed hid far more
 * than the thing being covered.
 */
@Serializable
data class TrackedCover(val samples: List<CoverSample>) {
    // Not serialised: a delegated property has no backing field for the serializer to see.
    private val confident: List<CoverSample> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        samples.filter { it.matched }.sortedBy { it.sourceUs }
    }

    fun at(sourceUs: Long): CropRect {
        val known = confident
        if (known.isEmpty()) return CropRect.FULL
        val i = known.binarySearchBy(sourceUs) { it.sourceUs }
        if (i >= 0) return known[i].box
        val next = -i - 1
        val before = known.getOrNull(next - 1) ?: return known.first().box
        val after = known.getOrNull(next) ?: return known.last().box
        val t = (sourceUs - before.sourceUs).toDouble() / (after.sourceUs - before.sourceUs)
        fun mix(a: Float, b: Float) = (a + (b - a) * t).toFloat()
        return CropRect(
            mix(before.box.left, after.box.left), mix(before.box.top, after.box.top),
            mix(before.box.right, after.box.right), mix(before.box.bottom, after.box.bottom),
        )
    }

    fun correct(sourceUs: Long, box: CropRect): TrackedCover =
        copy(samples = (samples.filterNot { it.sourceUs == sourceUs } + CoverSample(sourceUs, box))
            .sortedBy { it.sourceUs })
}

/**
 * Luma template matching in source pixels. Search in coarse steps, then refine the best candidate.
 * Frames are full-range luma, one unsigned byte a pixel, row by row ([lumaOf]).
 */
class CoverTracker(seed: ByteArray, private val width: Int, private val height: Int, box: CropRect) {
    private val patchW = (box.width * width).roundToInt().coerceIn(2, width)
    private val patchH = (box.height * height).roundToInt().coerceIn(2, height)
    private val originX = (box.left * width).roundToInt().coerceIn(0, width - patchW)
    private val originY = (box.top * height).roundToInt().coerceIn(0, height - patchH)
    private val offsets = IntArray(GRID * GRID) { i ->
        (i % GRID) * (patchW - 1) / (GRID - 1) + (i / GRID) * (patchH - 1) / (GRID - 1) * width
    }
    private val template = IntArray(offsets.size) { seed[originY * width + originX + offsets[it]].toInt() and 255 }
    private val usable = template.max() - template.min() >= 22

    fun find(pixels: ByteArray, around: CropRect): CropRect? {
        if (!usable) return null
        val x = (around.left * width).roundToInt()
        val y = (around.top * height).roundToInt()
        val radius = (minOf(width, height) / 5).coerceAtLeast(4)
        val left = (x - radius).coerceIn(0, width - patchW)
        val right = (x + radius).coerceIn(left, width - patchW)
        val top = (y - radius).coerceIn(0, height - patchH)
        val bottom = (y + radius).coerceIn(top, height - patchH)
        var bestX = x.coerceIn(left, right)
        var bestY = y.coerceIn(top, bottom)
        var best = score(pixels, bestX, bestY)
        var distantMatch = Int.MAX_VALUE
        fun consider(a: Int, b: Int) {
            val value = score(pixels, a, b)
            val distant = abs(a - bestX) > patchW / 3 || abs(b - bestY) > patchH / 3
            if (value < best) {
                if (distant) distantMatch = minOf(distantMatch, best)
                best = value
                bestX = a
                bestY = b
            } else if (distant) distantMatch = minOf(distantMatch, value)
        }
        for (b in top..bottom step 3) for (a in left..right step 3) consider(a, b)
        for (b in maxOf(top, bestY - 2)..minOf(bottom, bestY + 2)) {
            for (a in maxOf(left, bestX - 2)..minOf(right, bestX + 2)) consider(a, b)
        }
        if (best > offsets.size * 20 || distantMatch - best < offsets.size * 3) return null
        return CropRect(
            bestX.toFloat() / width, bestY.toFloat() / height,
            (bestX + patchW).toFloat() / width, (bestY + patchH).toFloat() / height,
        )
    }

    private fun score(pixels: ByteArray, x: Int, y: Int): Int {
        val start = y * width + x
        var cost = 0
        for (i in offsets.indices) cost += abs((pixels[start + offsets[i]].toInt() and 255) - template[i])
        return cost
    }

    companion object {
        private const val GRID = 12

        /** The luma the tracker matches on, from an ARGB pixel. */
        fun lumaOf(argb: Int): Int =
            ((argb ushr 16 and 255) * 54 + (argb ushr 8 and 255) * 183 + (argb and 255) * 19) ushr 8

        /** [argb] pixels as the tracker's luma bytes, into [out]. */
        fun lumaOf(argb: IntArray, out: ByteArray) {
            for (i in argb.indices) out[i] = lumaOf(argb[i]).toByte()
        }
    }
}
