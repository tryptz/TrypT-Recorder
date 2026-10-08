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

    fun fit(plan: VideoPlan, quality: QualityPreset): VideoPlan {
        val caps = avcEncoderCaps() ?: return plan
        val candidates = listOf(plan) + CapturePlanner.fallbacks(plan, quality)
        for (c in candidates) {
            val aligned = align(c, caps)
            if (supports(caps, aligned)) {
                val bitrate = aligned.bitrate.coerceIn(caps.bitrateRange.lower, caps.bitrateRange.upper)
                if (aligned != plan) Log.i(TAG, "encoder fit: ${plan.width}x${plan.height}@${plan.fps} -> ${aligned.width}x${aligned.height}@${aligned.fps}")
                return aligned.copy(bitrate = bitrate)
            }
        }
        Log.w(TAG, "no candidate reported supported; trying the request as-is")
        return plan
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

    /** [requested] AAC bitrate, held to what this phone's AAC encoder accepts. */
    fun aacBitrate(requested: Int): Int = runCatching {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, ScreenRecorder.SAMPLE_RATE, 2)
        val range = encoderCaps(format, MediaFormat.MIMETYPE_AUDIO_AAC)?.audioCapabilities?.bitrateRange
            ?: return@runCatching requested
        requested.coerceIn(range.lower, range.upper).also {
            if (it != requested) Log.i(TAG, "AAC bitrate $requested -> $it (encoder limit)")
        }
    }.getOrDefault(requested)

    private fun avcEncoderCaps(): MediaCodecInfo.VideoCapabilities? =
        encoderCaps(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720), MediaFormat.MIMETYPE_VIDEO_AVC)
            ?.videoCapabilities

    private fun encoderCaps(format: MediaFormat, mime: String): MediaCodecInfo.CodecCapabilities? = runCatching {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val name = list.findEncoderForFormat(format) ?: return@runCatching null
        list.codecInfos.first { it.name == name }.getCapabilitiesForType(mime)
    }.getOrNull()
}
