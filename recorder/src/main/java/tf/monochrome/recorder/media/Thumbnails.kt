package tf.monochrome.recorder.media

import androidx.core.net.toUri
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

/**
 * Frames for the timeline and thumbnails for the library, cached in memory.
 *
 * Timeline frames come from a few long-lived retrievers (opening one per frame
 * would re-parse the file every time) on a single thread, since a retriever
 * is not safe to share across threads.
 */
object Thumbnails {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val decode = Dispatchers.IO.limitedParallelism(1)

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private val retrievers = object : LruCache<String, MediaMetadataRetriever>(3) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: MediaMetadataRetriever, newValue: MediaMetadataRetriever?) {
            runCatching { oldValue.release() }
        }
    }

    suspend fun frame(context: Context, uri: String, timeUs: Long, heightPx: Int): Bitmap? {
        // Rounded to a quarter second so neighbouring requests share a frame.
        val t = timeUs / 250_000 * 250_000
        val key = "$uri@$t@$heightPx"
        cache.get(key)?.let { return it }
        return withContext(decode) {
            cache.get(key) ?: runCatching {
                val r = retrievers.get(uri) ?: MediaMetadataRetriever().also {
                    it.setDataSource(context, uri.toUri())
                    retrievers.put(uri, it)
                }
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 16
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 9
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val aspect = if (rot % 180 != 0) h.toFloat() / w else w.toFloat() / h
                r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, (heightPx * aspect).toInt().coerceAtLeast(8), heightPx)
            }.getOrNull()?.also { cache.put(key, it) }
        }
    }

    /** MediaStore's own thumbnail; cheap, and cached by the system too. */
    suspend fun library(context: Context, uri: Uri, size: Size): Bitmap? {
        val key = "lib:$uri@${size.width}"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(uri, size, null) }.getOrNull()?.also { cache.put(key, it) }
        }
    }
}
