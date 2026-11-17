package tf.monochrome.recorder.editor.analysis

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tf.monochrome.recorder.editor.model.CoverTracker
import tf.monochrome.recorder.editor.model.CropRect
import tf.monochrome.recorder.editor.model.MediaRef
import tf.monochrome.recorder.editor.model.TrackedCover
import kotlin.math.roundToInt

object TrackAnalysis {
    private const val TAG = "TrackAnalysis"

    suspend fun sourceFrame(context: Context, source: MediaRef, timeUs: Long): Bitmap? =
        withContext(Dispatchers.IO) {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, source.uri.toUri())
                val (w, h) = size(source, 720)
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
            }
        }

    /**
     * Tracks [box] through the clip. The video is decoded once, front to back
     * ([DecodedLuma]), rather than asking MediaMetadataRetriever for each of up
     * to 600 frames — each of which it decoded from the keyframe before it
     * into a new bitmap. If the decoder cannot be used here, it falls back to
     * the retriever. [progress] gets 0..1 as it goes, on the IO thread.
     */
    suspend fun analyze(
        context: Context, source: MediaRef, startUs: Long, endUs: Long, seedUs: Long, box: CropRect,
        progress: (Float) -> Unit = {},
    ): TrackedCover = withContext(Dispatchers.IO) {
        val (w, h) = size(source, 256)
        val tracking = CoverTracking(w, h)
        val scope = coroutineContext
        val active = { scope.ensureActive() }
        try {
            DecodedLuma(context, source, w, h, active).use { frames ->
                tracking.track(frames, startUs, endUs, seedUs, box, progress, active)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "decoding pass failed, using the retriever", e)
            progress(0f)
            RetrievedLuma(context, source, w, h).use { frames ->
                tracking.track(frames, startUs, endUs, seedUs, box, progress, active)
            }
        }
    }

    private fun size(source: MediaRef, longSide: Int): Pair<Int, Int> {
        val w = source.width.takeIf { it > 0 } ?: 1920
        val h = source.height.takeIf { it > 0 } ?: 1080
        val scale = longSide.toDouble() / maxOf(w, h)
        return (w * scale).roundToInt().coerceAtLeast(2) to (h * scale).roundToInt().coerceAtLeast(2)
    }
}

/**
 * The clip's frames from MediaCodec, decoded forwards from the keyframe
 * before each [read]'s first time. Each output frame is held, undrawn,
 * until the next one arrives — only then is it known which times it is the
 * frame for ([FramePicker]) — and only those frames are shrunk to luma
 * ([LumaGrid]), into one reused buffer.
 */
private class DecodedLuma(
    context: Context,
    source: MediaRef,
    private val width: Int,
    private val height: Int,
    private val checkActive: () -> Unit,
) : LumaSource, AutoCloseable {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val rotation: Int
    private val luma = ByteArray(width * height)
    private var grid: LumaGrid? = null
    private var gridKey: List<Int> = emptyList()
    private val info = MediaCodec.BufferInfo()
    private var started = false

    init {
        try {
            extractor.setDataSource(context, source.uri.toUri(), null)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            rotation = when {
                format.containsKey(MediaFormat.KEY_ROTATION) -> format.getInteger(MediaFormat.KEY_ROTATION)
                else -> MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, source.uri.toUri())
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                }
            }
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                started = true
            } catch (e: Exception) {
                codec.release()
                throw e
            }
        } catch (e: Exception) {
            extractor.release()
            throw e
        }
    }

    override fun read(timesUs: LongArray, onFrame: (index: Int, luma: ByteArray) -> Unit) {
        if (timesUs.isEmpty()) return
        val picker = FramePicker(timesUs)
        extractor.seekTo(timesUs.first(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        var inputDone = false
        var held = -1
        var idle = 0
        try {
            while (!picker.done) {
                checkActive()
                if (!inputDone) {
                    val input = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (input >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(input))
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val output = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (output < 0) {
                    // A decoder that has had everything and still gives nothing is stuck: stop waiting.
                    if (inputDone && ++idle > MAX_IDLE) break
                    continue
                }
                idle = 0
                val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0) {
                    if (held >= 0) {
                        // The held frame was on screen until this one: it is the frame for every time before it.
                        deliver(held, picker, picker.claim(info.presentationTimeUs), onFrame)
                        codec.releaseOutputBuffer(held, false)
                    }
                    held = output
                } else {
                    codec.releaseOutputBuffer(output, false)
                }
                if (ended) {
                    if (held >= 0) {
                        deliver(held, picker, picker.claim(Long.MAX_VALUE), onFrame)
                        codec.releaseOutputBuffer(held, false)
                        held = -1
                    }
                    break
                }
            }
        } finally {
            if (held >= 0) codec.releaseOutputBuffer(held, false)
        }
    }

    private fun deliver(index: Int, picker: FramePicker, count: Int, onFrame: (Int, ByteArray) -> Unit) {
        if (count == 0) return
        val image = codec.getOutputImage(index) ?: return
        try {
            val plane = image.planes[0]
            val crop = image.cropRect
            val key = listOf(crop.left, crop.top, crop.width(), crop.height(), plane.rowStride, plane.pixelStride)
            val g = grid?.takeIf { key == gridKey } ?: LumaGrid(
                width, height, crop.left, crop.top, crop.width(), crop.height(),
                plane.rowStride, plane.pixelStride, rotation, fullRange = fullRange(),
            ).also { grid = it; gridKey = key }
            g.sample(plane.buffer, luma)
        } finally {
            image.close()
        }
        for (i in picker.first until picker.first + count) onFrame(i, luma)
    }

    private fun fullRange(): Boolean = runCatching {
        codec.outputFormat.let {
            it.containsKey(MediaFormat.KEY_COLOR_RANGE) &&
                it.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
        }
    }.getOrDefault(false)

    override fun close() {
        if (started) runCatching { codec.stop() }
        codec.release()
        extractor.release()
    }

    private companion object {
        const val TIMEOUT_US = 10_000L

        /** Empty waits for output after the end of input before giving up: two seconds. */
        const val MAX_IDLE = 200
    }
}

/** The old way, kept for when no decoder can be had: one retriever call per time, into reused buffers. */
private class RetrievedLuma(
    private val context: Context,
    private val source: MediaRef,
    private val width: Int,
    private val height: Int,
) : LumaSource, AutoCloseable {
    private val retriever = MediaMetadataRetriever().apply { setDataSource(context, source.uri.toUri()) }
    private val pixels = IntArray(width * height)
    private val luma = ByteArray(width * height)

    override fun read(timesUs: LongArray, onFrame: (index: Int, luma: ByteArray) -> Unit) {
        timesUs.forEachIndexed { index, time ->
            val bitmap = retriever.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST, width, height)
                ?: return@forEachIndexed
            try {
                if (bitmap.width != width || bitmap.height != height) return@forEachIndexed
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            } finally {
                bitmap.recycle()
            }
            CoverTracker.lumaOf(pixels, luma)
            onFrame(index, luma)
        }
    }

    override fun close() = retriever.release()
}
