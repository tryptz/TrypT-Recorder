package tf.monochrome.recorder.editor.analysis

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tf.monochrome.recorder.editor.model.CoverSample
import tf.monochrome.recorder.editor.model.CoverTracker
import tf.monochrome.recorder.editor.model.CropRect
import tf.monochrome.recorder.editor.model.MediaRef
import tf.monochrome.recorder.editor.model.TrackedCover
import kotlin.math.roundToInt

object TrackAnalysis {
    suspend fun sourceFrame(context: Context, source: MediaRef, timeUs: Long): Bitmap? =
        withContext(Dispatchers.IO) {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, source.uri.toUri())
                val (w, h) = size(source, 720)
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
            }
        }

    suspend fun analyze(
        context: Context, source: MediaRef, startUs: Long, endUs: Long, seedUs: Long, box: CropRect,
    ): TrackedCover = withContext(Dispatchers.IO) {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, source.uri.toUri())
            val (w, h) = size(source, 256)
            fun pixels(time: Long): IntArray? {
                val bitmap = retriever.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
                    ?: return null
                return try {
                    if (bitmap.width != w || bitmap.height != h) return null
                    IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
                } finally {
                    bitmap.recycle()
                }
            }
            val tracker = CoverTracker(requireNotNull(pixels(seedUs)), w, h, box)
            val samples = mutableListOf(CoverSample(seedUs, box))
            val step = maxOf(166_667L, (endUs - startUs) / 600)
            for (direction in listOf(-1, 1)) {
                var time = seedUs
                var last = box
                val edge = if (direction < 0) startUs else endUs - 1
                while (time != edge) {
                    coroutineContext.ensureActive()
                    time = (time + direction * step).coerceIn(startUs, endUs - 1)
                    val match = pixels(time)?.let { tracker.find(it, last) }
                    if (match != null) last = match
                    samples += CoverSample(time, last, match != null)
                }
            }
            TrackedCover(samples.sortedBy { it.sourceUs })
        }
    }

    private fun size(source: MediaRef, longSide: Int): Pair<Int, Int> {
        val w = source.width.takeIf { it > 0 } ?: 1920
        val h = source.height.takeIf { it > 0 } ?: 1080
        val scale = longSide.toDouble() / maxOf(w, h)
        return (w * scale).roundToInt().coerceAtLeast(2) to (h * scale).roundToInt().coerceAtLeast(2)
    }
}
