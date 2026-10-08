@file:OptIn(UnstableApi::class)

package tf.monochrome.recorder.editor.export

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.OverlaySettings
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import tf.monochrome.recorder.editor.model.TextPosition
import java.nio.ByteBuffer

/*
 * The handful of effects Media3 does not ship in the shape the editor needs.
 *
 * Time-based ones read the frame's presentation time. For per-item effects
 * that is *composition* time in both the preview (CompositionPlayer converts
 * to it) and the export (VideoEncoderGraphInput adds each item's offset), so
 * a fade or a title placed on the timeline lands at the same moment in both.
 */

/** Fade in from black and out to black over the whole timeline. */
class FadeToBlack(
    private val fadeInUs: Long,
    private val fadeOutUs: Long,
    private val totalUs: Long,
) : RgbMatrix {
    private val matrix = FloatArray(16)

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val g = ExportPlanner.fadeGain(presentationTimeUs, totalUs, fadeInUs, fadeOutUs)
        matrix.fill(0f)
        matrix[0] = g
        matrix[5] = g
        matrix[10] = g
        matrix[15] = 1f
        return matrix
    }
}

/** A fixed colour matrix (column-major, as android.opengl.Matrix). */
class ColorMatrix(private val matrix: FloatArray) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = matrix

    companion object {
        val SEPIA = ColorMatrix(
            floatArrayOf(
                0.393f, 0.349f, 0.272f, 0f,
                0.769f, 0.686f, 0.534f, 0f,
                0.189f, 0.168f, 0.131f, 0f,
                0f, 0f, 0f, 1f,
            ),
        )
        val WARM = diagonal(1.08f, 1.0f, 0.86f)
        val COOL = diagonal(0.88f, 1.0f, 1.10f)

        private fun diagonal(r: Float, g: Float, b: Float) = ColorMatrix(
            floatArrayOf(
                r, 0f, 0f, 0f,
                0f, g, 0f, 0f,
                0f, 0f, b, 0f,
                0f, 0f, 0f, 1f,
            ),
        )
    }
}

/**
 * A title shown between two timeline times. Outside them it is drawn fully
 * transparent rather than swapped for empty text, which Media3's text overlay
 * cannot lay out.
 */
class TimedText(private val spec: PlannedText) : TextOverlay() {
    private val text: SpannableString = run {
        // Spaces pad the background box so the text is not flush to its edge.
        val body = if (spec.background) " ${spec.text} " else spec.text
        SpannableString(body).apply {
            setSpan(ForegroundColorSpan(spec.color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (spec.background) {
                setSpan(BackgroundColorSpan(Color.argb(150, 0, 0, 0)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private val visible = settings(alpha = 1f)
    private val hidden = settings(alpha = 0f)

    override fun getText(presentationTimeUs: Long): SpannableString = text

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        if (presentationTimeUs >= spec.startUs && presentationTimeUs < spec.endUs) visible else hidden

    private fun settings(alpha: Float): StaticOverlaySettings {
        // Anchors are in normalised device coordinates: y = 1 is the top edge.
        val (frameY, overlayY) = when (spec.position) {
            TextPosition.TOP -> 0.88f to 1f
            TextPosition.CENTER -> 0f to 0f
            TextPosition.BOTTOM -> -0.88f to -1f
        }
        return StaticOverlaySettings.Builder()
            .setAlphaScale(alpha)
            .setScale(spec.scale, spec.scale)
            .setBackgroundFrameAnchor(0f, frameY)
            .setOverlayFrameAnchor(0f, overlayY)
            .build()
    }
}

/**
 * Volume (0..2) with fades, for one item's audio. Item-local time, after
 * speed: the speed change runs before user processors.
 *
 * Media3's GainProcessor is documented for gains in [0, 1] and casts straight
 * back to short — above 1 a loud sample wraps round to its opposite sign.
 * Boosting is a basic editing need, so this one saturates instead.
 */
class VolumeEnvelope(
    private val gain: Float,
    private val fadeInUs: Long,
    private val fadeOutUs: Long,
    private val durationUs: Long,
) : BaseAudioProcessor() {
    private var framesSeen = 0L

    private val isIdentity: Boolean get() = gain == 1f && fadeInUs <= 0 && fadeOutUs <= 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        // NOT_SET marks the processor inactive: unity gain, no fades, no work.
        return if (isIdentity) AudioFormat.NOT_SET else inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val format = inputAudioFormat
        val out = replaceOutputBuffer(remaining)
        val channels = format.channelCount
        val rate = format.sampleRate
        while (inputBuffer.remaining() >= format.bytesPerFrame) {
            val posUs = framesSeen * 1_000_000L / rate
            val g = gain * ExportPlanner.fadeGain(posUs, durationUs, fadeInUs, fadeOutUs)
            repeat(channels) {
                if (format.encoding == C.ENCODING_PCM_16BIT) {
                    out.putShort(ExportPlanner.scaleSample(inputBuffer.getShort(), g))
                } else {
                    out.putFloat((inputBuffer.getFloat() * g).coerceIn(-1f, 1f))
                }
            }
            framesSeen++
        }
        out.flip()
    }

    override fun onFlush(streamMetadata: StreamMetadata) {
        // After a seek the stream resumes part-way into the item.
        val rate = inputAudioFormat.sampleRate
        framesSeen = if (rate > 0) streamMetadata.positionOffsetUs * rate / 1_000_000L else 0
    }

    override fun onReset() {
        framesSeen = 0
    }
}

/** One speed for the whole item. A data class, so equal speeds compare equal. */
data class ConstantSpeed(private val speed: Float) : SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = speed
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
}
