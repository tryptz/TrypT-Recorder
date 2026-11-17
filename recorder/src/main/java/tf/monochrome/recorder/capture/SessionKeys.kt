package tf.monochrome.recorder.capture

import java.security.MessageDigest

/**
 * Names the folder an edit-ready session lives in, from its recording's URI.
 *
 * The recorder inserts into `content://media/external_primary/video/media/<id>`,
 * while the library lists the same row as `content://media/external/video/media/<id>`.
 * Hashing the URI string, as sessions first did, gave the two different
 * folders, so stems and markers were not found when a recording was opened
 * from the library. The MediaStore row id is the same either way, so the
 * folder is named after it. Folders written before this are found through
 * [candidates], which also tries the old hashes of every volume spelling.
 *
 * Pure string work: no android.net.Uri, so it is JVM-tested.
 */
object SessionKeys {
    private val mediaUri = Regex("""^content://media/([^/]+)/video/media/(\d+)(?:[/?#].*)?$""")
    private val idFolder = Regex("""^m(\d+)$""")
    private val hashFolder = Regex("""^[0-9a-f]{64}$""")

    /** The MediaStore row id of a video URI, or null for anything else. */
    fun rowId(uri: String): Long? = mediaUri.find(uri)?.groupValues?.get(2)?.toLongOrNull()

    /** The folder a new session for [uri] is written to. */
    fun primary(uri: String): String = rowId(uri)?.let { "m$it" } ?: legacyHash(uri)

    /** Every folder a session for [uri] may be in, newest naming first. */
    fun candidates(uri: String): List<String> = buildList {
        add(primary(uri))
        add(legacyHash(uri))
        val m = mediaUri.find(uri)
        if (m != null) {
            val volume = m.groupValues[1]
            for (v in listOf("external_primary", "external")) {
                if (v != volume) add(legacyHash(uri.replaceFirst("content://media/$volume/", "content://media/$v/")))
            }
        }
    }.distinct()

    /** The row id a folder is named after, or null for a folder named the old way. */
    fun folderRowId(folder: String): Long? = idFolder.find(folder)?.groupValues?.get(1)?.toLongOrNull()

    fun isLegacyFolder(folder: String): Boolean = hashFolder.matches(folder)

    fun legacyHash(uri: String): String =
        MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
}
