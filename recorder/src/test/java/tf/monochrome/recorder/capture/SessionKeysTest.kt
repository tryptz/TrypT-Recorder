package tf.monochrome.recorder.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionKeysTest {
    private val recorded = "content://media/external_primary/video/media/4711"
    private val listed = "content://media/external/video/media/4711"

    @Test fun recorderAndLibrarySpellingsShareOneFolder() {
        assertEquals("m4711", SessionKeys.primary(recorded))
        assertEquals(SessionKeys.primary(recorded), SessionKeys.primary(listed))
    }

    @Test fun oldSessionsAreFoundFromEitherSpelling() {
        // Sessions written before the row-id folders were keyed by the hash of the insert URI.
        val old = SessionKeys.legacyHash(recorded)
        assertTrue(old in SessionKeys.candidates(listed))
        assertTrue(old in SessionKeys.candidates(recorded))
        assertEquals("m4711", SessionKeys.candidates(listed).first())
    }

    @Test fun otherUrisFallBackToTheHash() {
        val doc = "content://com.android.providers.media.documents/document/video%3A12"
        assertNull(SessionKeys.rowId(doc))
        assertEquals(SessionKeys.legacyHash(doc), SessionKeys.primary(doc))
        assertEquals(listOf(SessionKeys.legacyHash(doc)), SessionKeys.candidates(doc))
    }

    @Test fun folderNamesAreRecognised() {
        assertEquals(4711L, SessionKeys.folderRowId("m4711"))
        assertNull(SessionKeys.folderRowId("m47x"))
        assertTrue(SessionKeys.isLegacyFolder(SessionKeys.legacyHash(recorded)))
        assertFalse(SessionKeys.isLegacyFolder("m4711"))
    }
}
