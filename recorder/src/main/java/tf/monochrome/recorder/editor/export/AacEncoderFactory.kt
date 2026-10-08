@file:OptIn(UnstableApi::class)

package tf.monochrome.recorder.editor.export

import android.content.Context
import android.media.metrics.LogSessionId
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Codec
import androidx.media3.transformer.DefaultCodec
import androidx.media3.transformer.ExportException
import tf.monochrome.recorder.capture.EncoderFit

/**
 * Exports' encoders: video exactly as Media3's [delegate] makes it; audio on
 * the AAC encoder [EncoderFit.aacEncoder] picks, at [bitrate], constant where
 * the encoder allows.
 *
 * Media3's own factory takes the first AAC encoder the phone lists and sets
 * the requested bitrate on it whether or not that encoder can reach it — and
 * it offers no way to choose the audio encoder. An edit of a 320 kbps
 * recording should not come out at less.
 */
class AacEncoderFactory(
    private val context: Context,
    private val delegate: Codec.EncoderFactory,
    private val bitrate: Int,
) : Codec.EncoderFactory {

    override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec {
        if (format.sampleMimeType != MimeTypes.AUDIO_AAC || format.sampleRate <= 0 || format.channelCount <= 0) {
            return delegate.createForAudioEncoding(format, logSessionId)
        }
        val choice = EncoderFit.aacEncoder(bitrate, format.sampleRate, format.channelCount)
        val name = choice.name ?: return delegate.createForAudioEncoding(format, logSessionId)
        val configured = format.buildUpon().setAverageBitrate(choice.bitrate).build()
        val mediaFormat = EncoderFit.aacFormat(choice, format.sampleRate, format.channelCount)
        return try {
            DefaultCodec(context, configured, mediaFormat, name, /* isDecoder= */ false, /* outputSurface= */ null)
        } catch (e: ExportException) {
            Log.w(TAG, "AAC encoder $name refused; falling back to Media3's choice", e)
            delegate.createForAudioEncoding(format.buildUpon().setAverageBitrate(choice.bitrate).build(), logSessionId)
        }
    }

    override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec =
        delegate.createForVideoEncoding(format, logSessionId)

    override fun audioNeedsEncoding(): Boolean = delegate.audioNeedsEncoding()

    override fun videoNeedsEncoding(): Boolean = delegate.videoNeedsEncoding()

    private companion object {
        const val TAG = "AacEncoderFactory"
    }
}
