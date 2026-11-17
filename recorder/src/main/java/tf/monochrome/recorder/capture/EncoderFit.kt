package tf.monochrome.recorder.capture

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import tf.monochrome.recorder.settings.QualityPreset

/**
 * Bends a [VideoPlan] to what this device's H.264 encoder accepts.
 *
 * The debug recorder could assume the panel's native mode because it only
 * ever ran on the developer's phone. A screen recorder runs on everyone's:
 * plenty of encoders refuse 1440p, or 120 fps at any size worth having, and
 * MediaCodec.configure() fails outright when asked.
 */
object EncoderFit {
    private const val TAG = "EncoderFit"

    /**
     * [realtime] asks the encoder to clock itself for live input at the full
     * frame rate. Above 60 fps that is what keeps a 120–165 fps capture from
     * dropping frames on encoders that otherwise budget for 30 or 60. Some
     * encoders refuse the hints; the caller then retries without them.
     */
    fun videoFormat(plan: VideoPlan, realtime: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, plan.width, plan.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, plan.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, plan.fps)
            if (realtime) {
                setInteger(MediaFormat.KEY_PRIORITY, 0) // 0 = real-time
                if (plan.fps > 60) setInteger(MediaFormat.KEY_OPERATING_RATE, plan.fps)
            }
            // One key frame a second keeps trimming in the editor precise.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Deliberately no KEY_REPEAT_PREVIOUS_FRAME_AFTER: while paused the
            // display is detached from this surface, and a repeating encoder
            // would keep inventing frames stamped inside the pause.
        }

    /**
     * The sizes/rates to try, best first, each with the encoder that said it
     * supports them. The recorder creates that encoder *by name*: creating
     * "the" AVC encoder by type can pick a different codec from the one whose
     * limits were checked here. If configure() still refuses one, the
     * recorder moves on to the next.
     */
    fun fit(plan: VideoPlan, quality: QualityPreset): List<VideoEncoderChoice> {
        val (name, caps) = avcEncoder() ?: return listOf(VideoEncoderChoice(plan, null))
        val choices = (listOf(plan) + CapturePlanner.fallbacks(plan, quality))
            .map { align(it, caps) }
            .filter { supports(caps, it) }
            .map { it.copy(bitrate = it.bitrate.coerceIn(caps.bitrateRange.lower, caps.bitrateRange.upper)) }
            .distinct()
            .map { VideoEncoderChoice(it, name) }
        if (choices.isEmpty()) {
            Log.w(TAG, "no candidate reported supported by $name; trying the request as-is")
            return listOf(VideoEncoderChoice(plan, name))
        }
        val first = choices.first().plan
        if (first.width != plan.width || first.height != plan.height || first.fps != plan.fps) {
            Log.i(TAG, "encoder fit ($name): ${plan.width}x${plan.height}@${plan.fps} -> ${first.width}x${first.height}@${first.fps}")
        }
        return choices
    }

    private fun align(plan: VideoPlan, caps: MediaCodecInfo.VideoCapabilities) = plan.copy(
        width = CapturePlanner.align(plan.width, caps.widthAlignment),
        height = CapturePlanner.align(plan.height, caps.heightAlignment),
    )

    /**
     * The size/rate table is conservative: plenty of encoders that record
     * 1080p at 240 fps for slow motion still report 60 there. Performance
     * points are what the vendor actually declares the hardware sustains, so
     * either one saying yes is enough.
     */
    private fun supports(caps: MediaCodecInfo.VideoCapabilities, plan: VideoPlan): Boolean {
        val byTable = runCatching { caps.areSizeAndRateSupported(plan.width, plan.height, plan.fps.toDouble()) }
            .getOrDefault(false)
        if (byTable) return true
        val wanted = MediaCodecInfo.VideoCapabilities.PerformancePoint(plan.width, plan.height, plan.fps)
        return caps.supportedPerformancePoints.orEmpty().any { it.covers(wanted) }
    }

    /**
     * The AAC encoder to use for [bitrate] at this format — see [AacPicker]
     * for the rule. Shared by the recorder and the exporter, so a recording
     * and its edit get the same encoder and the same 320 kbps.
     */
    fun aacEncoder(bitrate: Int, sampleRate: Int, channels: Int): AacChoice = runCatching {
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true) } }
            .mapNotNull { info ->
                val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC) }.getOrNull()
                val audio = caps?.audioCapabilities ?: return@mapNotNull null
                AacCandidate(
                    name = info.name,
                    formatOk = audio.isSampleRateSupported(sampleRate) && audio.maxInputChannelCount >= channels,
                    minBitrate = audio.bitrateRange.lower,
                    maxBitrate = audio.bitrateRange.upper,
                    cbr = caps.encoderCapabilities?.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                    ) == true,
                )
            }
        AacPicker.pick(candidates, bitrate).also {
            Log.i(TAG, "AAC: ${it.name ?: "platform default"} at ${it.bitrate} bps, cbr=${it.cbr} (from ${candidates.map { c -> c.name }})")
        }
    }.getOrElse {
        Log.w(TAG, "AAC encoder query failed", it)
        AacChoice(null, bitrate, cbr = false)
    }

    /** The AAC MediaFormat for [choice]. [cbr] false leaves the mode to the encoder. */
    fun aacFormat(choice: AacChoice, sampleRate: Int, channels: Int, cbr: Boolean = choice.cbr): MediaFormat =
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, choice.bitrate)
            // Constant bitrate: 320 kbps stays 320 through quiet passages too.
            if (cbr) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }

    /** The AVC encoder the platform would pick for a typical capture, and its limits. */
    private fun avcEncoder(): Pair<String, MediaCodecInfo.VideoCapabilities>? = runCatching {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
        val name = list.findEncoderForFormat(format) ?: return@runCatching null
        val caps = list.codecInfos.first { it.name == name }
            .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities ?: return@runCatching null
        name to caps
    }.getOrNull()
}

/** A size/rate and the encoder, by name, that reported supporting it (null: let the platform choose). */
data class VideoEncoderChoice(val plan: VideoPlan, val codecName: String?)
