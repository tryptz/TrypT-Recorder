package tf.monochrome.recorder.editor.store

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import tf.monochrome.recorder.editor.model.Captions
import tf.monochrome.recorder.editor.model.MediaRef
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.model.durationUs
import tf.monochrome.recorder.editor.model.newId
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

@Serializable
private data class ProjectBundleManifest(val version: Int = 1, val hashes: Map<String, String>)

object ProjectBundle {
    private const val MAX_JSON = 32 * 1024 * 1024
    private val mediaPath = Regex("""media/[0-9]{6}\.[a-z0-9]{1,8}""")

    suspend fun export(context: Context, project: Project, destination: Uri) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val refs = linkedMapOf<String, String>()
        val portable = project.mapMedia { media ->
            val name = refs.getOrPut(media.uri) {
                val ext = media.displayName.substringAfterLast('.', "").lowercase()
                    .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
                    ?: if (media.hasVideo) "mp4" else "wav"
                "media/${(refs.size + 1).toString().padStart(6, '0')}.$ext"
            }
            media.copy(uri = name)
        }
        val json = ProjectStore.JSON.encodeToString(Project.serializer(), portable)
        require(json.toByteArray().size <= MAX_JSON)
        val output = requireNotNull(resolver.openOutputStream(destination, "wt"))
        ZipOutputStream(output.buffered()).use { zip ->
            zip.writeText("project.json", json)
            val srt = Captions.write(project.texts, project.durationUs)
            if (srt.isNotBlank()) zip.writeText("captions.srt", srt)
            zip.writeText("timeline.otio", TimelineHandoff.create(portable))
            zip.writeText("HANDOFF.txt", TimelineHandoff.notes)
            zip.setLevel(Deflater.NO_COMPRESSION)
            val hashes = linkedMapOf<String, String>()
            for ((uri, entry) in refs) {
                coroutineContext.ensureActive()
                zip.putNextEntry(ZipEntry(entry))
                hashes[entry] = requireNotNull(resolver.openInputStream(uri.toUri())).use { input ->
                    copyAndHash(input, zip)
                }
                zip.closeEntry()
            }
            zip.writeText("manifest.json", ProjectStore.JSON.encodeToString(
                ProjectBundleManifest.serializer(), ProjectBundleManifest(hashes = hashes),
            ))
        }
    }

    /** Restores media to app-owned storage and returns a new draft without changing existing drafts. */
    suspend fun import(context: Context, source: Uri, store: ProjectStore): Project = withContext(Dispatchers.IO) {
        val id = newId()
        val root = File(context.filesDir, "bundles/$id")
        check(root.mkdirs())
        val hashes = linkedMapOf<String, String>()
        val names = mutableSetOf<String>()
        var portable: Project? = null
        var manifest: ProjectBundleManifest? = null
        var committed = false
        try {
            val input = requireNotNull(context.contentResolver.openInputStream(source))
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    require(!entry.isDirectory && names.add(name))
                    when (name) {
                        "project.json" -> portable = ProjectStore.JSON.decodeFromString(Project.serializer(), zip.readTextBounded())
                        "manifest.json" -> manifest = ProjectStore.JSON.decodeFromString(
                            ProjectBundleManifest.serializer(), zip.readTextBounded(),
                        )
                        "captions.srt", "timeline.otio", "HANDOFF.txt" -> zip.readTextBounded()
                        else -> {
                            require(mediaPath.matches(name))
                            val file = File(root, name.substringAfter('/'))
                            hashes[name] = file.outputStream().buffered().use { copyAndHash(zip, it) }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val info = requireNotNull(manifest)
            require(info.version == 1 && info.hashes == hashes)
            val used = mutableSetOf<String>()
            val restored = requireNotNull(portable).mapMedia { ref ->
                require(mediaPath.matches(ref.uri) && ref.uri in hashes && ref.durationUs > 0)
                used += ref.uri
                ref.copy(uri = File(root, ref.uri.substringAfter('/')).toUri().toString())
            }
            require(used == hashes.keys && restored.clips.isNotEmpty())
            restored.clips.forEach { require(it.trimStartUs in 0 until it.trimEndUs && it.trimEndUs <= it.source.durationUs) }
            restored.overlays.forEach { require(it.trimStartUs in 0 until it.trimEndUs && it.trimEndUs <= it.source.durationUs) }
            restored.audio.forEach { require(it.trimStartUs in 0 until it.trimEndUs && it.trimEndUs <= it.source.durationUs) }
            val now = System.currentTimeMillis()
            val draft = restored.copy(id = id, createdAt = now, updatedAt = now)
            withContext(NonCancellable) {
                store.save(draft)
                committed = true
            }
            draft
        } finally {
            if (!committed) root.deleteRecursively()
        }
    }

    private fun Project.mapMedia(map: (MediaRef) -> MediaRef): Project = copy(
        clips = clips.map { clip -> clip.copy(
            source = map(clip.source), angles = clip.angles.map { it.copy(source = map(it.source)) },
        ) },
        audio = audio.map { it.copy(source = map(it.source)) },
        overlays = overlays.map { it.copy(source = map(it.source)) },
    )

    private fun ZipOutputStream.writeText(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray())
        closeEntry()
    }

    private fun InputStream.readTextBounded(): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count == -1) break
            require(bytes.size() + count <= MAX_JSON)
            bytes.write(buffer, 0, count)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private suspend fun copyAndHash(input: InputStream, output: OutputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count == -1) break
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
