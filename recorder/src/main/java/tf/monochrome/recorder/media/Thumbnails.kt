package tf.monochrome.recorder.media

import androidx.core.net.toUri
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Frames for the timeline and thumbnails for the library, cached in memory.
 *
 * Timeline frames come from a few long-lived retrievers (opening one per frame
 * would re-parse the file every time) on a single thread, since a retriever
 * is not safe to share across threads.
 *
 * A timeline frame is the key frame nearest the time asked for, so frames are
 * cached by *that* key frame's time, found from the file's index: a recording
 * has one a second, and keying by the time asked for decoded the same key
 * frame again for every request that rounded differently.
 */
object Thumbnails {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val decode = Dispatchers.IO.limitedParallelism(1)
    private val housekeeping = CoroutineScope(SupervisorJob() + decode)

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** A file opened for frames: the retriever, and an extractor over its video track for key frame times. */
    private class Source(val retriever: MediaMetadataRetriever, val extractor: MediaExtractor?, val aspect: Float) {
        fun release() {
            runCatching { retriever.release() }
            runCatching { extractor?.release() }
        }
    }

    // Only touched on `decode`.
    private val sources = object : LruCache<String, Source>(3) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Source, newValue: Source?) {
            oldValue.release()
        }
    }

    suspend fun frame(context: Context, uri: String, timeUs: Long, heightPx: Int): Bitmap? {
        // Rounded to a quarter second for the quick look-up before the decode thread.
        val bucket = timeUs / 250_000 * 250_000
        val quickKey = "$uri@$bucket@$heightPx"
        syncKeys.get(quickKey)?.let { key -> cache.get(key)?.let { return it } }
        return withContext(decode) {
            syncKeys.get(quickKey)?.let { key -> cache.get(key) } ?: runCatching {
                val source = sources.get(uri) ?: open(context, uri)?.also { sources.put(uri, it) } ?: return@runCatching null
                val syncUs = source.extractor?.let { ex ->
                    ex.seekTo(bucket, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    ex.sampleTime.takeIf { it >= 0 }
                } ?: bucket
                val key = "$uri@sync$syncUs@$heightPx"
                syncKeys.put(quickKey, key)
                cache.get(key) ?: source.retriever.getScaledFrameAtTime(
                    syncUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    (heightPx * source.aspect).toInt().coerceAtLeast(8), heightPx,
                )?.also { cache.put(key, it) }
            }.getOrNull()
        }
    }

    /** Quarter-second request → the key frame it resolved to (the bitmap is cached once, under the latter). */
    private val syncKeys = LruCache<String, String>(4096)

    private fun open(context: Context, uri: String): Source? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri.toUri())
        } catch (e: Exception) {
            r.release()
            return null
        }
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 16
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 9
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val aspect = if (rot % 180 != 0) h.toFloat() / w else w.toFloat() / h
        val ex = MediaExtractor()
        val extractor = try {
            ex.setDataSource(context, uri.toUri(), null)
            val track = (0 until ex.trackCount).first {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            ex.selectTrack(track)
            ex
        } catch (e: Exception) {
            // Without the index, frames are keyed by the rounded time as before.
            ex.release()
            null
        }
        return Source(r, extractor, aspect)
    }

    /** MediaStore's own thumbnail; cheap, and cached by the system too. */
    suspend fun library(context: Context, uri: Uri, size: Size): Bitmap? {
        val key = "lib:$uri@${size.width}"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(uri, size, null) }.getOrNull()?.also { cache.put(key, it) }
        }
    }

    /**
     * From Application.onTrimMemory: once the UI is hidden the open files go
     * (each retriever holds a decoder and the file open); in the background
     * the cached frames go too.
     */
    fun trimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) cache.evictAll()
        // On the decode thread, so a retriever is never released mid-frame.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) housekeeping.launch { sources.evictAll() }
    }
}
