package tf.monochrome.recorder.editor.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

@Serializable
data class CoverSample(val sourceUs: Long, val box: CropRect, val matched: Boolean = true)

@Serializable
data class TrackedCover(val samples: List<CoverSample>) {
    fun at(sourceUs: Long): CropRect {
        if (samples.isEmpty()) return CropRect.FULL
        val i = samples.binarySearchBy(sourceUs) { it.sourceUs }
        if (i >= 0) return samples[i].let { if (it.matched) it.box else CropRect.FULL }
        val next = -i - 1
        val before = samples.getOrNull(next - 1) ?: return CropRect.FULL
        val after = samples.getOrNull(next) ?: return CropRect.FULL
        if (!before.matched || !after.matched) return CropRect.FULL
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

/** Luma template matching in source pixels. Search in coarse steps, then refine the best candidate. */
class CoverTracker(seed: IntArray, private val width: Int, private val height: Int, box: CropRect) {
    private val patchW = (box.width * width).roundToInt().coerceIn(2, width)
    private val patchH = (box.height * height).roundToInt().coerceIn(2, height)
    private val originX = (box.left * width).roundToInt().coerceIn(0, width - patchW)
    private val originY = (box.top * height).roundToInt().coerceIn(0, height - patchH)
    private val offsets = IntArray(GRID * GRID) { i ->
        (i % GRID) * (patchW - 1) / (GRID - 1) + (i / GRID) * (patchH - 1) / (GRID - 1) * width
    }
    private val template = IntArray(offsets.size) { luma(seed[originY * width + originX + offsets[it]]) }
    private val usable = template.max() - template.min() >= 22

    fun find(pixels: IntArray, around: CropRect): CropRect? {
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

    private fun score(pixels: IntArray, x: Int, y: Int): Int {
        val start = y * width + x
        var cost = 0
        for (i in offsets.indices) cost += abs(luma(pixels[start + offsets[i]]) - template[i])
        return cost
    }

    private companion object {
        const val GRID = 12
        fun luma(argb: Int): Int =
            ((argb ushr 16 and 255) * 54 + (argb ushr 8 and 255) * 183 + (argb and 255) * 19) ushr 8
    }
}
