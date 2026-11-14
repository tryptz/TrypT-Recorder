package tf.monochrome.recorder.editor.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** 3D .cube samples in file order, red changes fastest. */
@Serializable
data class CubeLut(val name: String, val size: Int, val colors: List<Int>) {
    fun toRgbCube(mix: Float): Array<Array<IntArray>> {
        val amount = mix.coerceIn(0f, 1f)
        fun blend(index: Int, mapped: Int): Int {
            val identity = index * 255f / (size - 1)
            return (identity + (mapped - identity) * amount).roundToInt()
        }
        return Array(size) { r -> Array(size) { g -> IntArray(size) { b ->
            val rgb = colors[r + size * (g + size * b)]
            (255 shl 24) or (blend(r, rgb ushr 16 and 255) shl 16) or
                (blend(g, rgb ushr 8 and 255) shl 8) or blend(b, rgb and 255)
        } } }
    }

    companion object {
        fun parse(contents: String, fileName: String): CubeLut {
            var dimension = 0
            var name = fileName
            val colors = mutableListOf<Int>()
            for (line in contents.removePrefix("\uFEFF").lineSequence()) {
                val clean = line.substringBefore('#').trim()
                if (clean.isEmpty()) continue
                val tokens = clean.split(Regex("\\s+"))
                when (tokens.first()) {
                    "TITLE" -> name = clean.substringAfter(' ').trim().trim('"').take(80).ifBlank { fileName }
                    "LUT_3D_SIZE" -> {
                        require(dimension == 0 && tokens.size == 2)
                        dimension = tokens[1].toInt().also { require(it in 2..33) }
                    }
                    "DOMAIN_MIN", "DOMAIN_MAX" -> {
                        val expected = if (tokens[0] == "DOMAIN_MIN") 0f else 1f
                        require(tokens.size == 4 && tokens.drop(1).all { it.toFloat() == expected })
                    }
                    else -> {
                        require(dimension > 0 && colors.size < dimension * dimension * dimension && tokens.size == 3)
                        val rgb = tokens.map {
                            val value = it.toFloat()
                            require(value.isFinite())
                            (value.coerceIn(0f, 1f) * 255f).roundToInt()
                        }
                        colors += (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
                    }
                }
            }
            require(dimension > 0 && colors.size == dimension * dimension * dimension)
            return CubeLut(name, dimension, colors)
        }
    }
}
