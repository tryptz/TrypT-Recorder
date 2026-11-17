package tf.monochrome.recorder.media

import org.junit.Assert.assertEquals
import org.junit.Test
import tf.monochrome.recorder.capture.SessionKeys
import tf.monochrome.recorder.media.CleanupRules.PendingRow

class CleanupRulesTest {
    @Test fun leftoverPendingRowsGoButQueuedAndCurrentOnesStay() {
        val rows = listOf(
            PendingRow(1, 1_000), // a recording whose process died
            PendingRow(2, 1_000), // an export queued for a retry still writes into it
            PendingRow(3, 5_000), // added by this process
            PendingRow(4, 4_998),
        )
        assertEquals(listOf(1L, 4L), CleanupRules.pendingToDelete(rows, queuedIds = setOf(2), processStartSec = 5_000))
    }

    @Test fun sessionFoldersFollowTheirRecordings() {
        val kept = "content://media/external/video/media/7"
        val folders = listOf(
            "m5", // recording deleted
            "m6", // still there
            SessionKeys.legacyHash("content://media/external_primary/video/media/7"), // old naming, still there
            SessionKeys.legacyHash("content://media/external_primary/video/media/8"), // old naming, gone
            "notes", // not a session folder
        )
        assertEquals(
            listOf("m5", SessionKeys.legacyHash("content://media/external_primary/video/media/8")),
            CleanupRules.sessionFoldersToDelete(folders, liveIds = setOf(6, 7), liveUris = listOf(kept)),
        )
    }
}
