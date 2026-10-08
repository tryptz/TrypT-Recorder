package tf.monochrome.recorder.media

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

data class Recording(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
)

/** The videos in Movies/TryptifyRecorder — recordings and exported edits alike. */
class RecordingsRepository(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver
    private val collection: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /**
     * The observer only signals; the query runs in [map], on IO. (Querying in
     * onChange would run it on the observer's main-thread handler — flowOn
     * moves the flow, not a callback's thread.)
     */
    fun observe(): Flow<List<Recording>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(collection, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate().map { query() }.flowOn(Dispatchers.IO)

    private fun query(): List<Recording> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
        )
        val folder = MediaStoreFile.relativePath(MediaStoreFile.Kind.VIDEO)
        val out = mutableListOf<Recording>()
        runCatching {
            resolver.query(
                collection,
                projection,
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("$folder%"),
                "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out += Recording(
                        uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                        name = c.getString(1) ?: "",
                        durationMs = c.getLong(2),
                        sizeBytes = c.getLong(3),
                        dateAddedSec = c.getLong(4),
                    )
                }
            }
        }
        return out
    }

    sealed interface Outcome {
        data object Done : Outcome
        data object Failed : Outcome
        /** The file belongs to an earlier install; the system must ask the user. */
        data class NeedsConsent(val request: PendingIntent) : Outcome
    }

    suspend fun delete(uri: Uri): Outcome = withContext(Dispatchers.IO) {
        try {
            if (resolver.delete(uri, null, null) > 0) Outcome.Done else Outcome.Failed
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Outcome.NeedsConsent(MediaStore.createDeleteRequest(resolver, listOf(uri)))
            } else {
                Outcome.Failed
            }
        }
    }

    suspend fun rename(uri: Uri, newName: String): Outcome = withContext(Dispatchers.IO) {
        val name = MediaStoreFile.safeName(newName.removeSuffix(".mp4")) + ".mp4"
        try {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }
            if (resolver.update(uri, values, null, null) > 0) Outcome.Done else Outcome.Failed
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Outcome.NeedsConsent(MediaStore.createWriteRequest(resolver, listOf(uri)))
            } else {
                Outcome.Failed
            }
        }
    }
}
