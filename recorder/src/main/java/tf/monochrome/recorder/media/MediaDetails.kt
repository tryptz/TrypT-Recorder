package tf.monochrome.recorder.media

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * What a media file actually contains, as read back from it (MediaInspector)
 * — not what the recorder was set to. Pure data and arithmetic, covered by
 * JVM tests; MediaInspector does the reading.
 */

data class VideoDetails(
    /** "H.264", "H.265", … */
    val codec: String,
    /** "High", "Main", … or null when the file does not say. */
    val profile: String?,
    /** "5.2", "4", … or null. */
    val level: String?,
    /** As displayed (rotation applied). */
    val width: Int,
    val height: Int,
    /** Rate while the picture moves ([FrameTiming]); 0 if unknown. */
    val peakFps: Float,
    /** Frames ÷ duration; lower than [peakFps] when the screen sat still. */
    val averageFps: Float,
    val frames: Long,
    /** Bits per second, measured from the samples in the file. */
    val bitrate: Long,
    /** True when only part of a very long track was measured. */
    val approximate: Boolean,
)

data class AudioDetails(
    /** "AAC-LC", "HE-AAC", "Opus", … */
    val codec: String,
    val sampleRate: Int,
    val channels: Int,
    val bitrate: Long,
    val approximate: Boolean,
)

data class MediaDetails(
    /** "MP4", "Matroska", … */
    val container: String,
    val durationUs: Long,
    val sizeBytes: Long,
    val video: VideoDetails?,
    val audio: AudioDetails?,
) {
    val overallBitrate: Long get() = Rates.bitrate(sizeBytes, durationUs)

    /** One line: `1080×2400 · 165 fps · H.264 High · 31.2 Mbps · AAC-LC 320 kbps stereo`. */
    fun summary(): String = buildList {
        video?.let { v ->
            add("${v.width}×${v.height}")
            val fps = if (v.peakFps > 0f) v.peakFps else v.averageFps
            if (fps > 0f) add("${Rates.formatFps(fps)} fps")
            add(listOfNotNull(v.codec, v.profile).joinToString(" "))
            if (v.bitrate > 0) add(Rates.formatBitrate(v.bitrate))
        }
        audio?.let { a ->
            add(listOfNotNull(a.codec, Rates.formatBitrate(a.bitrate).takeIf { a.bitrate > 0 }, Rates.channels(a.channels)).joinToString(" "))
        }
    }.joinToString(" · ")
}

object Rates {
    fun bitrate(bytes: Long, durationUs: Long): Long =
        if (durationUs <= 0 || bytes <= 0) 0 else (bytes * 8.0 * 1_000_000 / durationUs).roundToLong()

    fun averageFps(frames: Long, durationUs: Long): Float =
        if (durationUs <= 0 || frames <= 0) 0f else (frames * 1_000_000.0 / durationUs).toFloat()

    /** "320 kbps", "31.2 Mbps". */
    fun formatBitrate(bps: Long): String =
        if (bps < 1_000_000) "${(bps / 1000.0).roundToInt()} kbps" else "%.1f Mbps".format(bps / 1_000_000.0)

    /** "165", "60", "59.94". */
    fun formatFps(fps: Float): String =
        if (abs(fps - fps.roundToInt()) < 0.05f) fps.roundToInt().toString() else "%.2f".format(fps).trimEnd('0').trimEnd('.')

    /** "48 kHz", "44.1 kHz". */
    fun formatSampleRate(hz: Int): String =
        if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(hz / 1000.0)

    fun channels(n: Int): String = when (n) {
        1 -> "mono"
        2 -> "stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "$n ch"
    }

    /** Peak and average differ by more than this when the file has a variable frame rate. */
    fun isVariable(peak: Float, average: Float): Boolean = peak > 0f && average > 0f && peak > average * 1.1f
}

/** Codec names, and the profile/level and object type out of codec config bytes. */
object Codecs {
    fun container(mime: String?): String = when (mime?.lowercase()) {
        "video/mp4", "audio/mp4", "video/quicktime" -> "MP4"
        "video/x-matroska", "video/webm", "audio/webm" -> "Matroska / WebM"
        "video/3gpp" -> "3GP"
        "video/mp2ts" -> "MPEG-TS"
        null -> "?"
        else -> mime
    }

    fun video(mime: String): String = when (mime.lowercase()) {
        "video/avc" -> "H.264"
        "video/hevc" -> "H.265"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/mp4v-es" -> "MPEG-4 Part 2"
        "video/3gpp" -> "H.263"
        "video/dolby-vision" -> "Dolby Vision"
        else -> mime
    }

    fun audio(mime: String, csd0: ByteArray?): String = when (mime.lowercase()) {
        "audio/mp4a-latm" -> aacName(csd0?.let(::aacObjectType))
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/mpeg" -> "MP3"
        "audio/flac" -> "FLAC"
        "audio/raw" -> "PCM"
        "audio/ac3" -> "AC-3"
        "audio/eac3" -> "E-AC-3"
        "audio/3gpp" -> "AMR-NB"
        "audio/amr-wb" -> "AMR-WB"
        else -> mime
    }

    /**
     * (profile_idc, constraint flags, level_idc) from an H.264 sequence
     * parameter set, given either as Annex-B (start code + NAL, which is what
     * MediaExtractor hands out as csd-0) or as an avcC record.
     */
    fun avcProfileLevel(csd0: ByteArray): Triple<Int, Int, Int>? {
        if (csd0.size >= 4 && csd0[0].toInt() == 1) {
            // avcC: version, profile, compatibility, level.
            return Triple(u(csd0[1]), u(csd0[2]), u(csd0[3]))
        }
        var i = 0
        while (i + 2 < csd0.size) {
            if (csd0[i].toInt() == 0 && csd0[i + 1].toInt() == 0 && csd0[i + 2].toInt() == 1) {
                val nal = i + 3
                if (nal + 3 < csd0.size && (u(csd0[nal]) and 0x1F) == 7) {
                    return Triple(u(csd0[nal + 1]), u(csd0[nal + 2]), u(csd0[nal + 3]))
                }
                i = nal
            } else {
                i++
            }
        }
        return null
    }

    fun avcProfile(profileIdc: Int, constraints: Int): String = when (profileIdc) {
        66 -> if (constraints and 0x40 != 0) "Constrained Baseline" else "Baseline"
        77 -> "Main"
        88 -> "Extended"
        100 -> "High"
        110 -> "High 10"
        122 -> "High 4:2:2"
        244 -> "High 4:4:4"
        else -> "profile $profileIdc"
    }

    /** level_idc 52 → "5.2", 40 → "4", 9 → "1b". */
    fun avcLevel(levelIdc: Int): String = when {
        levelIdc == 9 -> "1b"
        levelIdc % 10 == 0 -> (levelIdc / 10).toString()
        else -> "${levelIdc / 10}.${levelIdc % 10}"
    }

    /** MPEG-4 audio object type from an AudioSpecificConfig. */
    fun aacObjectType(csd0: ByteArray): Int? {
        if (csd0.isEmpty()) return null
        val first = (u(csd0[0]) shr 3) and 0x1F
        if (first != 31) return first
        if (csd0.size < 2) return null
        return 32 + (((u(csd0[0]) and 0x07) shl 3) or (u(csd0[1]) shr 5))
    }

    fun aacName(objectType: Int?): String = when (objectType) {
        1 -> "AAC Main"
        2 -> "AAC-LC"
        3 -> "AAC SSR"
        4 -> "AAC LTP"
        5 -> "HE-AAC"
        23 -> "AAC-LD"
        29 -> "HE-AACv2"
        39 -> "AAC-ELD"
        42 -> "xHE-AAC"
        else -> "AAC"
    }

    private fun u(b: Byte) = b.toInt() and 0xFF
}
