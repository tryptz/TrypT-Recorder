package tf.monochrome.recorder.media

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import tf.monochrome.recorder.RecorderApp
import tf.monochrome.recorder.capture.SavedRecording
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A pending MediaStore row and a descriptor to write it through. Pending rows
 * are invisible to other apps until [publish], so a gallery never shows a
 * half-written file.
 */
class MediaStoreFile private constructor(
    val uri: Uri,
    val displayName: String,
    val relativePath: String,
    val pfd: ParcelFileDescriptor,
) {
    enum class Kind(val mime: String, val extension: String, val root: String) {
        VIDEO("video/mp4", ".mp4", "Movies"),
        AUDIO("audio/mp4", ".m4a", "Music"),
        // An image: MediaStore only takes those under Pictures/ or DCIM/.
        GIF("image/gif", ".gif", "Pictures"),
        ;

        val collection: Uri
            get() = when (this) {
                VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                GIF -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
    }

    /** Closes the descriptor and makes the file visible to other apps. */
    fun publish(resolver: ContentResolver): SavedRecording? {
        runCatching { pfd.close() }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        return runCatching { resolver.update(uri, done, null, null) }
            .map { SavedRecording(uri.toString(), displayName, "$relativePath/") }
            .getOrNull()
    }

    fun discard(resolver: ContentResolver) {
        runCatching { pfd.close() }
        runCatching { resolver.delete(uri, null, null) }
    }

    companion object {
        /** Folder under Movies/, Music/ or Pictures/ everything this app writes goes into. */
        fun relativePath(kind: Kind) = "${kind.root}/${RecorderApp.FOLDER}"

        /** Keeps a user-typed name to characters every file system accepts. */
        fun safeName(name: String): String =
            name.replace(Regex("[^A-Za-z0-9 _.-]"), "").trim().take(40).ifEmpty { "Video" }

        fun create(resolver: ContentResolver, kind: Kind, prefix: String): MediaStoreFile? {
            val name = safeName(prefix) + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + kind.extension
            val path = relativePath(kind)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, kind.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = runCatching { resolver.insert(kind.collection, values) }.getOrNull() ?: return null
            val pfd = runCatching { resolver.openFileDescriptor(uri, "w") }.getOrNull()
            if (pfd == null) {
                runCatching { resolver.delete(uri, null, null) }
                return null
            }
            return MediaStoreFile(uri, name, path, pfd)
        }
    }
}
