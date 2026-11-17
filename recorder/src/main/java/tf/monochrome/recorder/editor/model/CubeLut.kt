package tf.monochrome.recorder.editor.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.IntArraySerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.math.roundToInt

/** 3D .cube samples in file order, red changes fastest. */
@Serializable
data class CubeLut(val name: String, val size: Int, val colors: LutColors) {
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
        private val MAGIC = "TLUT".toByteArray(Charsets.US_ASCII)

        fun parse(contents: String, fileName: String): CubeLut {
            var dimension = 0
            var name = fileName
            var colors = IntArray(0)
            var count = 0
            for (line in contents.removePrefix("﻿").lineSequence()) {
                val clean = line.substringBefore('#').trim()
                if (clean.isEmpty()) continue
                val tokens = clean.split(Regex("\\s+"))
                when (tokens.first()) {
                    "TITLE" -> name = clean.substringAfter(' ').trim().trim('"').take(80).ifBlank { fileName }
                    "LUT_3D_SIZE" -> {
                        require(dimension == 0 && tokens.size == 2)
                        dimension = tokens[1].toInt().also { require(it in 2..33) }
                        colors = IntArray(dimension * dimension * dimension)
                    }
                    "DOMAIN_MIN", "DOMAIN_MAX" -> {
                        val expected = if (tokens[0] == "DOMAIN_MIN") 0f else 1f
                        require(tokens.size == 4 && tokens.drop(1).all { it.toFloat() == expected })
                    }
                    else -> {
                        require(dimension > 0 && count < colors.size && tokens.size == 3)
                        val rgb = tokens.map {
                            val value = it.toFloat()
                            require(value.isFinite())
                            (value.coerceIn(0f, 1f) * 255f).roundToInt()
                        }
                        colors[count++] = (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
                    }
                }
            }
            require(dimension > 0 && count == colors.size)
            return CubeLut(name, dimension, LutColors(colors))
        }

        /**
         * A bundled look as `tools/lut_to_binary.py` writes it: the same
         * colours [parse] makes of the .cube it came from, already rounded to
         * 8 bits, so nothing is parsed. [fileName] names it if it has no title.
         */
        fun readBinary(bytes: ByteArray, fileName: String): CubeLut {
            require(bytes.size >= 10 && bytes.copyOfRange(0, 4).contentEquals(MAGIC) && bytes[4].toInt() == 1)
            val size = bytes[5].toInt() and 255
            require(size in 2..33)
            var at = 6
            fun text(): String {
                val length = ((bytes[at].toInt() and 255) shl 8) or (bytes[at + 1].toInt() and 255)
                require(at + 2 + length <= bytes.size)
                return String(bytes, at + 2, length, Charsets.UTF_8).also { at += 2 + length }
            }
            val name = text().ifBlank { fileName }
            text() // The licence notice: carried in the file for whoever reads it, not needed here.
            val count = size * size * size
            require(bytes.size == at + count * 3)
            val colors = IntArray(count) { i ->
                val p = at + i * 3
                ((bytes[p].toInt() and 255) shl 16) or ((bytes[p + 1].toInt() and 255) shl 8) or (bytes[p + 2].toInt() and 255)
            }
            return CubeLut(name, size, LutColors(colors))
        }
    }
}

/**
 * A LUT's colours, 0xRRGGBB, packed in an IntArray rather than a list of
 * boxed Integers: up to 33³ of them sit in every draft, every undo step and
 * every plan, and plans are compared on every preview rebuild. Equality
 * checks the cached hash first, so two different looks differ at once.
 * Saved as the same JSON list of numbers as before, so older drafts load.
 */
@Serializable(with = LutColors.Serializer::class)
class LutColors(private val values: IntArray) {
    private val hash = values.contentHashCode()

    val size: Int get() = values.size

    operator fun get(index: Int): Int = values[index]

    fun toIntArray(): IntArray = values.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || other is LutColors && other.hash == hash && other.values.contentEquals(values)

    override fun hashCode(): Int = hash

    override fun toString(): String = "LutColors(${values.size})"

    object Serializer : KSerializer<LutColors> {
        private val list = IntArraySerializer()
        override val descriptor = list.descriptor
        override fun serialize(encoder: Encoder, value: LutColors) = list.serialize(encoder, value.values)
        override fun deserialize(decoder: Decoder): LutColors = LutColors(list.deserialize(decoder))
    }
}
