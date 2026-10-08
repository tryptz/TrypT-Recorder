package tf.monochrome.recorder.capture

/** One AAC encoder on the phone, as far as choosing one is concerned. */
data class AacCandidate(
    val name: String,
    /** Accepts the sample rate and channel count wanted. */
    val formatOk: Boolean,
    val minBitrate: Int,
    val maxBitrate: Int,
    /** Declares constant-bitrate mode. */
    val cbr: Boolean,
)

/** The encoder to create by name (null: none qualified, use the platform default) and how to set it up. */
data class AacChoice(val name: String?, val bitrate: Int, val cbr: Boolean)

/**
 * Picks the AAC encoder for recordings and exports. Pure, for the JVM tests.
 *
 * Creating "the" AAC encoder by type takes whichever the phone lists first —
 * often a vendor hardware encoder, and some of those cap the bitrate below
 * 320 kbps, so asking for 320 would quietly produce less. Instead: only an
 * encoder that takes the format and whose range includes the bitrate; among
 * those, Android's own FDK-AAC encoder first (it applies the bitrate exactly,
 * and AAC costs next to nothing on the CPU), then the phone's order. If none
 * reaches the bitrate, the one that comes closest, at its maximum — so the
 * file says what it really is.
 */
object AacPicker {
    /** Android's FDK-AAC encoder: Codec2 and its older OMX name. */
    val PREFERRED = listOf("c2.android.aac.encoder", "OMX.google.aac.encoder")

    fun pick(candidates: List<AacCandidate>, bitrate: Int): AacChoice {
        val usable = candidates.filter { it.formatOk }
        val honouring = usable.filter { bitrate in it.minBitrate..it.maxBitrate }
        honouring.minByOrNull { c -> PREFERRED.indexOf(c.name).let { if (it < 0) PREFERRED.size else it } }
            ?.let { return AacChoice(it.name, bitrate, it.cbr) }
        usable.maxByOrNull { it.maxBitrate }
            ?.let { return AacChoice(it.name, bitrate.coerceIn(it.minBitrate, it.maxBitrate), it.cbr) }
        return AacChoice(null, bitrate, cbr = false)
    }
}
