@file:OptIn(UnstableApi::class)

package tf.monochrome.recorder.editor.export

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.Matrix
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.annotation.OptIn
import androidx.core.graphics.createBitmap
import androidx.media3.common.C
import androidx.media3.common.OverlaySettings
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.GlMatrixTransformation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import tf.monochrome.recorder.editor.model.CropRect
import tf.monochrome.recorder.editor.model.ClipTiming
import tf.monochrome.recorder.editor.model.TextPosition
import java.nio.ByteBuffer
import kotlin.math.pow

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

        fun whiteBalance(temperature: Float, exposure: Float, tint: Float): ColorMatrix {
            val gain = 2f.pow(exposure)
            return diagonal(
                gain * (1f + 0.16f * temperature + 0.08f * tint),
                gain * (1f - 0.16f * tint),
                gain * (1f - 0.16f * temperature + 0.08f * tint),
            )
        }

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
    private val balance: Float = 0f,
) : BaseAudioProcessor() {
    private var framesSeen = 0L

    private val isIdentity: Boolean get() = gain == 1f && balance == 0f && fadeInUs <= 0 && fadeOutUs <= 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        // NOT_SET marks the processor inactive: unity gain, no fades, no work.
        return when {
            isIdentity -> AudioFormat.NOT_SET
            balance != 0f && inputAudioFormat.channelCount == 1 ->
                AudioFormat(inputAudioFormat.sampleRate, 2, inputAudioFormat.encoding)
            else -> inputAudioFormat
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val format = inputAudioFormat
        val duplicateMono = format.channelCount == 1 && balance != 0f
        val out = replaceOutputBuffer(if (duplicateMono) remaining * 2 else remaining)
        val left = 1f - balance.coerceAtLeast(0f)
        val right = 1f + balance.coerceAtMost(0f)
        val channels = format.channelCount
        val rate = format.sampleRate
        while (inputBuffer.remaining() >= format.bytesPerFrame) {
            val posUs = framesSeen * 1_000_000L / rate
            val g = gain * ExportPlanner.fadeGain(posUs, durationUs, fadeInUs, fadeOutUs)
            if (duplicateMono) {
                if (format.encoding == C.ENCODING_PCM_16BIT) {
                    val sample = inputBuffer.getShort()
                    out.putShort(ExportPlanner.scaleSample(sample, g * left))
                    out.putShort(ExportPlanner.scaleSample(sample, g * right))
                } else {
                    val sample = inputBuffer.getFloat()
                    out.putFloat((sample * g * left).coerceIn(-1f, 1f))
                    out.putFloat((sample * g * right).coerceIn(-1f, 1f))
                }
            } else repeat(channels) { channel ->
                val channelGain = g * when {
                    channels != 2 -> 1f
                    channel == 0 -> left
                    else -> right
                }
                if (format.encoding == C.ENCODING_PCM_16BIT) {
                    out.putShort(ExportPlanner.scaleSample(inputBuffer.getShort(), channelGain))
                } else {
                    out.putFloat((inputBuffer.getFloat() * channelGain).coerceIn(-1f, 1f))
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

/** Media3 requests times relative to the start of the clipped item. */
class RampSpeed(private val timing: ClipTiming, private val startUs: Long) : SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = timing.rateAt(startUs + timeUs)

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long =
        timing.nextChangeAfter(startUs + timeUs)?.minus(startUs) ?: C.TIME_UNSET
}

/** The keys are anchored to source time so a split retains the shot's motion. */
class ClipMotion(private val clip: PlannedMedia) : GlMatrixTransformation {
    private var aspect = 1f
    private val matrix = FloatArray(16)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        aspect = inputWidth.toFloat() / inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun getGlMatrixArray(presentationTimeUs: Long): FloatArray {
        val local = (presentationTimeUs - clip.timelineStartUs).coerceIn(0, clip.durationUs)
        val source = clip.timing.sourceAt(local)
        val transform = clip.picture.at(source)
        Matrix.setIdentityM(matrix, 0)
        Matrix.translateM(matrix, 0, transform.x, transform.y, 0f)
        Matrix.scaleM(matrix, 0, 1f / aspect, 1f, 1f)
        Matrix.rotateM(matrix, 0, -transform.rotation, 0f, 0f, 1f)
        Matrix.scaleM(matrix, 0, aspect * transform.zoom, transform.zoom, 1f)
        return matrix
    }
}

/** An opaque texture placed in either source or canvas coordinates by the effect order. */
class SolidCover(private val boxAt: (Long) -> CropRect) : BitmapOverlay() {
    private val pixel = createBitmap(1, 1).apply { eraseColor(Color.BLACK) }
    private lateinit var frameSize: Size

    override fun configure(videoSize: Size) {
        frameSize = videoSize
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap = pixel

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val box = boxAt(presentationTimeUs)
        return StaticOverlaySettings.Builder()
            .setScale(box.width * frameSize.width, box.height * frameSize.height)
            .setBackgroundFrameAnchor(
                (box.left + box.right - 1f).coerceIn(-1f, 1f),
                (1f - box.top - box.bottom).coerceIn(-1f, 1f),
            ).build()
    }
}
