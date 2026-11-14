package tf.monochrome.recorder.editor.export.gif

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/** What a finished GIF came out as. */
data class GifStats(val width: Int, val height: Int, val fps: Int, val frames: Int, val bytes: Long)

/**
 * Makes a GIF out of the MP4 Transformer rendered for it. The MP4 already
 * has every edit in it — cuts, speed, crop, filters, titles, fades — at the
 * GIF's size, so this only has to turn pictures into palette colours.
 *
 * It decodes the video twice: once to gather the colours a sample of frames
 * use, for the palette, and once to write every frame with it. One palette
 * fitted to the whole video looks better than one per frame, and it does not
 * flicker from frame to frame.
 */
object GifTranscoder {
    /** Frames the palette is gathered from, spread across the video. */
    private const val PALETTE_FRAMES = 120
    /** Share of the work the palette pass takes; writing is the rest. */
    private const val PALETTE_SHARE = 0.2f
    private const val TIMEOUT_US = 10_000L

    /**
     * Writes [gif] from [video], [fps] frames a second over [durationUs].
     * [onProgress] gets 0..1, from whichever thread this runs on.
     */
    suspend fun transcode(video: File, gif: File, fps: Int, durationUs: Long, onProgress: (Float) -> Unit): GifStats {
        val histogram = ColorHistogram()
        val gather = FrameSampler(fps, durationUs)
        val every = max(1, (gather.frameCount + PALETTE_FRAMES - 1) / PALETTE_FRAMES)
        decode(
            video,
            gather,
            wants = { frames -> (frames.first + every - 1) / every * every <= frames.last },
            progress = { onProgress(it * PALETTE_SHARE) },
        ) { pixels, w, h, _ -> histogram.add(pixels, w * h) }
        val palette = GifPalette.from(histogram)

        val sampler = FrameSampler(fps, durationUs)
        var encoder: GifEncoder? = null
        gif.outputStream().buffered(1 shl 16).use { stream ->
            decode(
                video,
                sampler,
                wants = { true },
                progress = { onProgress(PALETTE_SHARE + it * (1 - PALETTE_SHARE)) },
            ) { pixels, w, h, frames ->
                val e = encoder ?: GifEncoder(stream, w, h, palette).also { encoder = it }
                if (w != e.width || h != e.height) throw IOException("Frame size changed from ${e.width}x${e.height} to ${w}x$h")
                e.addFrame(pixels, sampler.delayCs(frames))
            }
            encoder?.close()
        }
        val e = encoder ?: throw IOException("The rendered video had no frames")
        return GifStats(e.width, e.height, fps, e.frameCount, gif.length())
    }

    /**
     * Decodes [video] in order and hands [onFrames] each video frame that
     * fills some GIF frames (the ones [wants]), as 0xAARRGGBB pixels the way
     * a player would show them. Each frame is copied out and held until the
     * next one's time says which GIF frames it fills; only those that fill
     * any are converted.
     */
    private suspend fun decode(
        video: File,
        sampler: FrameSampler,
        wants: (IntRange) -> Boolean,
        progress: (Float) -> Unit,
        onFrames: (pixels: IntArray, width: Int, height: Int, frames: IntRange) -> Unit,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(video.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IOException("The rendered file has no video")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            // Transformer encodes a portrait picture lying on its side and
            // marks the file to be turned upright on playback; a GIF has no
            // such mark, so the turn is done here.
            val rotation = ((format.getInteger(MediaFormat.KEY_ROTATION, 0) % 360) + 360) % 360
            val trackWidth = format.getInteger(MediaFormat.KEY_WIDTH, 0)
            val trackHeight = format.getInteger(MediaFormat.KEY_HEIGHT, 0)
            var colors = colorsOf(format, YuvColors.BT709_LIMITED)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            val held = YuvFrame()
            var holding = false
            var pixels = IntArray(0)
            fun deliver(frames: IntRange) {
                if (frames.isEmpty() || !holding) return
                if (wants(frames)) {
                    val w = held.outputWidth(rotation)
                    val h = held.outputHeight(rotation)
                    if (pixels.size < w * h) pixels = IntArray(w * h)
                    held.toArgb(pixels, rotation, colors)
                    onFrames(pixels, w, h, frames)
                }
                progress((frames.last + 1).toFloat() / sampler.frameCount)
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buffer = c.getInputBuffer(i) ?: throw IOException("Decoder gave no input buffer")
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    colors = colorsOf(c.outputFormat, colors)
                } else if (o >= 0) {
                    if (info.size > 0) {
                        val image = c.getOutputImage(o) ?: throw IOException("Decoder output is not readable YUV")
                        image.use {
                            deliver(sampler.arrive(info.presentationTimeUs))
                            copy(it, held, trackWidth, trackHeight)
                            holding = true
                        }
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            deliver(sampler.finish())
        } finally {
            codec?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            extractor.release()
        }
    }

    private fun copy(image: Image, into: YuvFrame, trackWidth: Int, trackHeight: Int) {
        val planes = image.planes
        val y = planes[0].buffer
        val u = planes[1].buffer
        val v = planes[2].buffer
        into.ensureCapacity(y.remaining(), u.remaining(), v.remaining())
        y.duplicate().get(into.y, 0, y.remaining())
        u.duplicate().get(into.u, 0, u.remaining())
        v.duplicate().get(into.v, 0, v.remaining())
        into.yRowStride = planes[0].rowStride
        into.yPixelStride = planes[0].pixelStride
        // YUV_420_888 promises the two chroma planes share their strides.
        into.uvRowStride = planes[1].rowStride
        into.uvPixelStride = planes[1].pixelStride
        val crop = image.cropRect
        into.left = crop.left
        into.top = crop.top
        // Some decoders report the padded size as the crop; the track knows the real one.
        into.width = if (trackWidth > 0) min(crop.width(), trackWidth) else crop.width()
        into.height = if (trackHeight > 0) min(crop.height(), trackHeight) else crop.height()
    }

    private fun colorsOf(format: MediaFormat, fallback: YuvColors): YuvColors {
        val standard = format.getInteger(MediaFormat.KEY_COLOR_STANDARD, 0)
        val range = format.getInteger(MediaFormat.KEY_COLOR_RANGE, 0)
        if (standard == 0 && range == 0) return fallback
        val bt601 = standard == MediaFormat.COLOR_STANDARD_BT601_PAL || standard == MediaFormat.COLOR_STANDARD_BT601_NTSC
        return YuvColors.pick(bt601, range == MediaFormat.COLOR_RANGE_FULL)
    }
}
