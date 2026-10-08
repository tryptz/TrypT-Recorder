package tf.monochrome.recorder.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tf.monochrome.recorder.editor.model.MediaRef

/** Reads what the editor needs to know about a file before it goes on the timeline. */
object MediaProbe {
    suspend fun probe(context: Context, uri: Uri): MediaRef? = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return@withContext null
            val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            // Players and Media3 show the frame upright; so does the editor.
            if (rotation % 180 != 0) w = h.also { h = w }
            MediaRef(
                uri = uri.toString(),
                displayName = displayName(context, uri),
                durationUs = durationMs * 1000,
                width = w,
                height = h,
                hasVideo = hasVideo,
                hasAudio = hasAudio,
                frameRate = if (hasVideo) frameRate(context, uri) else 0f,
            )
        } catch (e: Exception) {
            Log.w("MediaProbe", "cannot read $uri", e)
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * Reads the timestamps of the first few hundred video frames — sample
     * metadata only, nothing is decoded — and estimates the rate the motion
     * was recorded at ([FrameTiming]).
     */
    private fun frameRate(context: Context, uri: Uri): Float {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return 0f
            ex.selectTrack(track)
            val times = ArrayList<Long>(FRAMES_SAMPLED)
            while (times.size < FRAMES_SAMPLED && ex.sampleTime >= 0) {
                times += ex.sampleTime
                if (!ex.advance()) break
            }
            FrameTiming.peakFrameRate(times)
        } catch (e: Exception) {
            Log.w("MediaProbe", "frame timing unreadable for $uri", e)
            0f
        } finally {
            runCatching { ex.release() }
        }
    }

    private const val FRAMES_SAMPLED = 600

    fun displayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "Clip"
}
