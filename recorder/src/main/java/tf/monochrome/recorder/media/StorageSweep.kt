package tf.monochrome.recorder.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import tf.monochrome.recorder.capture.EditReadySession
import tf.monochrome.recorder.capture.SessionKeys
import tf.monochrome.recorder.editor.export.ExportQueue
import java.io.File

/**
 * Clears what an interrupted process leaves behind. Run once per process
 * start, off the main thread ([tf.monochrome.recorder.RecorderApp]).
 *
 *  - **Pending files.** A recording or export is a pending MediaStore row
 *    until it is finished; if the process dies first (killed, crashed, phone
 *    off), the row stays pending — invisible, unplayable (MediaMuxer writes
 *    the index last), and taking space until the system's week-long expiry.
 *    This app's own pending rows in its folders are deleted, except those an
 *    export queued for a retry still writes into ([ExportQueue]).
 *  - **Edit-ready sessions** whose recording no longer exists (deleted from
 *    another app, or before deleting took its stems along).
 *
 * Every rule is in [CleanupRules]; a query that fails deletes nothing.
 */
object StorageSweep {
    private const val TAG = "StorageSweep"

    fun run(context: Context, processStartMs: Long) {
        runCatching { sweepPending(context, processStartMs / 1000) }.onFailure { Log.w(TAG, "pending sweep", it) }
        runCatching { sweepSessions(context) }.onFailure { Log.w(TAG, "session sweep", it) }
    }

    private fun sweepPending(context: Context, processStartSec: Long) {
        val resolver = context.contentResolver
        // Unreadable queue: nothing is known to be safe to delete.
        val queued = ExportQueue.read(context).mapNotNull { job ->
            job.outputUri?.let { runCatching { ContentUris.parseId(it.toUri()) }.getOrNull() }
        }.toSet()
        for (kind in MediaStoreFile.Kind.entries) {
            val rows = query(
                resolver, kind.collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED),
                "${MediaStore.MediaColumns.IS_PENDING} = 1 AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf(context.packageName, MediaStoreFile.relativePath(kind) + "%"),
            ) { CleanupRules.PendingRow(it.getLong(0), it.getLong(1)) } ?: continue
            for (id in CleanupRules.pendingToDelete(rows, queued, processStartSec)) {
                val uri = ContentUris.withAppendedId(kind.collection, id)
                val deleted = runCatching { resolver.delete(uri, null, null) }.getOrDefault(0)
                Log.i(TAG, "unfinished ${kind.name.lowercase()} $id from an earlier run: deleted=$deleted")
                if (kind == MediaStoreFile.Kind.VIDEO) EditReadySession.delete(context, uri.toString())
            }
        }
    }

    private fun sweepSessions(context: Context) {
        val root = EditReadySession.root(context)
        // Listed before the query: a folder made after this belongs to a row the query will see.
        val folders = root.list()?.toList().orEmpty()
        if (folders.isEmpty()) return
        val resolver = context.contentResolver
        val video = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val ids = folders.mapNotNull(SessionKeys::folderRowId)
        val liveIds = mutableSetOf<Long>()
        for (chunk in ids.chunked(200)) {
            liveIds += query(
                resolver, video, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns._ID} IN (${chunk.joinToString(",")})", null,
            ) { it.getLong(0) } ?: return
        }
        val liveUris = if (folders.none(SessionKeys::isLegacyFolder)) emptyList() else query(
            resolver, video, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(MediaStoreFile.relativePath(MediaStoreFile.Kind.VIDEO) + "%"),
        ) { ContentUris.withAppendedId(video, it.getLong(0)).toString() } ?: return
        for (folder in CleanupRules.sessionFoldersToDelete(folders, liveIds, liveUris)) {
            Log.i(TAG, "edit-ready session $folder has no recording any more; deleting")
            File(root, folder).deleteRecursively()
        }
    }

    /** Rows matching [selection], pending and trashed ones included; null if the query failed. */
    private fun <T> query(
        resolver: ContentResolver,
        collection: Uri,
        projection: Array<String>,
        selection: String,
        args: Array<String>?,
        map: (Cursor) -> T,
    ): List<T>? {
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val extras = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                args?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }
            resolver.query(collection, projection, extras, null)
        } else {
            @Suppress("DEPRECATION")
            resolver.query(MediaStore.setIncludePending(collection), projection, selection, args, null)
        }
        return cursor?.use { c -> buildList { while (c.moveToNext()) add(map(c)) } }
    }
}
