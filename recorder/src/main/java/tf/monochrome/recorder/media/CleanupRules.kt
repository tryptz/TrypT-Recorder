package tf.monochrome.recorder.media

import tf.monochrome.recorder.capture.SessionKeys

/**
 * What the startup sweep may delete. Pure, so the rules are JVM-tested; the
 * Android side ([StorageSweep]) only queries and deletes.
 */
object CleanupRules {
    data class PendingRow(val id: Long, val dateAddedSec: Long)

    /**
     * Pending (unfinished) rows of this app's own that a process which died
     * mid-write left behind. Not the ones an export queued for a retry still
     * writes into ([queuedIds]), and not any added since this process started
     * (a recording or export of this process; a second of slack for rounding).
     */
    fun pendingToDelete(rows: List<PendingRow>, queuedIds: Set<Long>, processStartSec: Long): List<Long> =
        rows.filter { it.id !in queuedIds && it.dateAddedSec < processStartSec - 1 }.map { it.id }

    /**
     * Edit-ready session folders whose recording is gone. [liveIds] are the
     * MediaStore rows that still exist (pending and trashed ones included);
     * [liveUris] the URIs of this app's recordings, for folders named the old
     * way. Folders that fit neither naming are left alone.
     */
    fun sessionFoldersToDelete(folders: List<String>, liveIds: Set<Long>, liveUris: List<String>): List<String> {
        val legacy = liveUris.flatMap { SessionKeys.candidates(it) }.toSet()
        return folders.filter { folder ->
            val id = SessionKeys.folderRowId(folder)
            when {
                id != null -> id !in liveIds
                SessionKeys.isLegacyFolder(folder) -> folder !in legacy
                else -> false
            }
        }
    }
}
