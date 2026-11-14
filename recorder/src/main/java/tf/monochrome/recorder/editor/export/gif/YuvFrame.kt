package tf.monochrome.recorder.editor.export.gif

/**
 * One decoded YUV 4:2:0 frame, copied out of the decoder so the decoder's
 * buffer can go back at once. Planar (I420) and semi-planar (NV12, NV21)
 * layouts differ only in the strides, which are kept as the decoder gave them.
 *
 * The arrays are reused from frame to frame; they only ever grow.
 */
class YuvFrame {
    var y = ByteArray(0)
    var u = ByteArray(0)
    var v = ByteArray(0)
    var yRowStride = 0
    var yPixelStride = 1
    var uvRowStride = 0
    var uvPixelStride = 1

    /** The visible picture inside the (possibly padded) planes. */
    var left = 0
    var top = 0
    var width = 0
    var height = 0

    fun ensureCapacity(ySize: Int, uSize: Int, vSize: Int) {
        if (y.size < ySize) y = ByteArray(ySize)
        if (u.size < uSize) u = ByteArray(uSize)
        if (v.size < vSize) v = ByteArray(vSize)
    }

    /** Width of the picture once [rotation] is applied. */
    fun outputWidth(rotation: Int): Int = if (rotation % 180 == 0) width else height

    fun outputHeight(rotation: Int): Int = if (rotation % 180 == 0) height else width

    /**
     * Converts to 0xAARRGGBB in [out], turned clockwise by [rotation] (0, 90,
     * 180 or 270) — the way a player would show it. [out] holds at least
     * width × height pixels, row by row at the output's width.
     */
    fun toArgb(out: IntArray, rotation: Int, colors: YuvColors) {
        val w = width
        val h = height
        val outW = outputWidth(rotation)
        val yScale = colors.yScale
        val yOffset = colors.yOffset
        for (row in 0 until h) {
            val yRow = (top + row) * yRowStride
            val uvRow = ((top + row) / 2) * uvRowStride
            for (col in 0 until w) {
                val luma = (y[yRow + (left + col) * yPixelStride].toInt() and 0xFF) - yOffset
                val uvAt = uvRow + ((left + col) / 2) * uvPixelStride
                val cb = (u[uvAt].toInt() and 0xFF) - 128
                val cr = (v[uvAt].toInt() and 0xFF) - 128
                val l = luma * yScale + HALF
                val r = clamp((l + colors.crToR * cr) shr SHIFT)
                val g = clamp((l - colors.cbToG * cb - colors.crToG * cr) shr SHIFT)
                val b = clamp((l + colors.cbToB * cb) shr SHIFT)
                val at = when (rotation) {
                    90 -> col * outW + (h - 1 - row)
                    180 -> (h - 1 - row) * outW + (w - 1 - col)
                    270 -> (w - 1 - col) * outW + row
                    else -> row * outW + col
                }
                out[at] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun clamp(c: Int): Int = if (c < 0) 0 else if (c > 255) 255 else c

    internal companion object {
        const val SHIFT = 16
        const val HALF = 1 shl (SHIFT - 1)
    }
}

/**
 * YUV to RGB coefficients, fixed point (16 fractional bits), for one colour
 * standard and range.
 */
class YuvColors private constructor(
    val yOffset: Int,
    val yScale: Int,
    val crToR: Int,
    val cbToG: Int,
    val crToG: Int,
    val cbToB: Int,
) {
    companion object {
        private fun fixed(v: Double): Int = Math.round(v * (1 shl YuvFrame.SHIFT)).toInt()

        /**
         * [kr] and [kb] are the standard's red and blue luma weights.
         * Limited range is 16–235 for luma and 16–240 for chroma.
         */
        private fun of(kr: Double, kb: Double, fullRange: Boolean): YuvColors {
            val ys = if (fullRange) 1.0 else 255.0 / 219.0
            val cs = if (fullRange) 1.0 else 255.0 / 224.0
            val kg = 1.0 - kr - kb
            return YuvColors(
                yOffset = if (fullRange) 0 else 16,
                yScale = fixed(ys),
                crToR = fixed(2 * (1 - kr) * cs),
                cbToG = fixed(2 * (1 - kb) * kb / kg * cs),
                crToG = fixed(2 * (1 - kr) * kr / kg * cs),
                cbToB = fixed(2 * (1 - kb) * cs),
            )
        }

        val BT709_LIMITED = of(0.2126, 0.0722, fullRange = false)
        val BT709_FULL = of(0.2126, 0.0722, fullRange = true)
        val BT601_LIMITED = of(0.299, 0.114, fullRange = false)
        val BT601_FULL = of(0.299, 0.114, fullRange = true)

        /**
         * From a decoder's colour keys. Media3 tags the SDR video it encodes
         * BT.709, limited range, so that is what an unmarked file is taken to be.
         */
        fun pick(bt601: Boolean, fullRange: Boolean): YuvColors = when {
            bt601 && fullRange -> BT601_FULL
            bt601 -> BT601_LIMITED
            fullRange -> BT709_FULL
            else -> BT709_LIMITED
        }
    }
}
