package tf.monochrome.recorder.editor.store

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import tf.monochrome.recorder.editor.export.ExportQueue
import tf.monochrome.recorder.editor.model.MediaRef
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.model.durationUs
import tf.monochrome.recorder.editor.model.addClips
import tf.monochrome.recorder.editor.model.newId
import java.io.File

data class ProjectSummary(
    val id: String,
    val name: String,
    val updatedAt: Long,
    val clipCount: Int,
    val durationUs: Long,
    val coverUri: String?,
)

/**
 * Drafts, one JSON file each in the app's private storage, with the revision
 * before the latest kept beside it (`.previous`) in case the latest is
 * unreadable.
 *
 * The editor saves half a second after every change, so a save does as little
 * as it can: a revision that differs only in its time is not written at all,
 * and the revision being replaced is moved to `.previous` from memory — this
 * store wrote it — instead of being read back and parsed. The library's
 * summaries are kept by file stamp, so listing drafts reads only those that
 * changed.
 */
class ProjectStore(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "projects").apply { mkdirs() }
    private val lock = Mutex()
    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects: StateFlow<List<ProjectSummary>> = _projects.asStateFlow()

    // Guarded by `lock`.
    private val summaries = mutableMapOf<String, Pair<String, ProjectSummary>>()
    /** The revision this store last wrote and its exact JSON — what is on disk for that draft now. */
    private var lastWritten: Pair<Project, String>? = null

    suspend fun refresh() = withContext(Dispatchers.IO) {
        lock.withLock {
            val fresh = mutableMapOf<String, Pair<String, ProjectSummary>>()
            for (id in ids()) {
                val stamp = stamp(id)
                val summary = summaries[id]?.takeIf { it.first == stamp }?.second
                    ?: read(file(id))?.summary()
                    ?: continue
                fresh[id] = stamp to summary
            }
            summaries.clear()
            summaries.putAll(fresh)
            _projects.value = fresh.values.map { it.second }.sortedByDescending { it.updatedAt }
        }
    }

    suspend fun load(id: String): Project? = withContext(Dispatchers.IO) { lock.withLock { read(file(id)) } }

    suspend fun save(project: Project) = withContext(Dispatchers.IO) {
        lock.withLock {
            val target = file(project.id)
            val last = lastWritten?.takeIf { it.first.id == project.id && target.isFile }
            // Nothing but the save time changed: the file, and the revision before it, stay as they are.
            if (last != null && last.first.copy(updatedAt = 0) == project.copy(updatedAt = 0)) return@withLock
            val json = JSON.encodeToString(Project.serializer(), project)
            // The revision being replaced becomes the previous one. When this
            // store wrote it, its JSON is in memory; otherwise it is read once,
            // and kept only if it is a readable draft.
            val current = last?.second ?: readText(target)?.takeIf { decode(it, target) != null }
            if (current != null) writeText(backup(target), current)
            writeText(target, json)
            lastWritten = project to json
            val summary = project.summary()
            summaries[project.id] = stamp(project.id) to summary
            _projects.value = (_projects.value.filterNot { it.id == project.id } + summary)
                .sortedByDescending { it.updatedAt }
        }
    }

    /**
     * Deletes a draft, and with it what only it used: an imported bundle's
     * media (`bundles/<id>`), and the persisted read access to documents it
     * referred to. Anything another draft or a queued export still uses is
     * kept.
     */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val target = file(id)
            val doomed = read(target)
            AtomicFile(backup(target)).delete()
            AtomicFile(target).delete()
            summaries.remove(id)
            if (lastWritten?.first?.id == id) lastWritten = null
            _projects.value = _projects.value.filterNot { it.id == id }
            runCatching { releaseUnused(id, doomed) }.onFailure { Log.w(TAG, "cleanup after deleting draft $id", it) }
        }
    }

    suspend fun create(name: String, clips: List<MediaRef>): Project {
        val now = System.currentTimeMillis()
        val p = Project(id = newId(), name = name, createdAt = now, updatedAt = now).addClips(clips)
        save(p)
        return p
    }

    private fun releaseUnused(id: String, doomed: Project?) {
        val inUse = mutableSetOf<String>()
        for (other in ids()) if (other != id) read(file(other))?.let { inUse += it.mediaUris() }
        // If the export queue cannot be read, nothing is known to be unused.
        ExportQueue.read(app).forEach { inUse += it.project.mediaUris() }

        val bundle = File(app.filesDir, "bundles/$id")
        if (bundle.isDirectory) {
            val prefix = Uri.fromFile(bundle).toString() + "/"
            if (inUse.none { it.startsWith(prefix) }) bundle.deleteRecursively()
        }

        val released = doomed?.mediaUris().orEmpty() - inUse
        if (released.isEmpty()) return
        val resolver = app.contentResolver
        for (grant in resolver.persistedUriPermissions) {
            if (grant.uri.toString() in released) {
                runCatching { resolver.releasePersistableUriPermission(grant.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        }
    }

    private fun ids(): List<String> = dir.list().orEmpty().mapNotNull { name ->
        when {
            name.endsWith(".json") -> name.removeSuffix(".json")
            name.endsWith(".json.previous") -> name.removeSuffix(".json.previous")
            else -> null
        }
    }.distinct()

    /** Changes whenever either revision of the draft is rewritten. */
    private fun stamp(id: String): String {
        val target = file(id)
        return listOf(target, backup(target)).joinToString("|") { "${it.lastModified()}:${it.length()}" }
    }

    private fun file(id: String) = File(dir, "$id.json")

    private fun backup(file: File) = File(file.parentFile, "${file.name}.previous")

    private fun read(file: File): Project? = readRevision(file) ?: readRevision(backup(file))

    private fun readRevision(file: File): Project? = readText(file)?.let { decode(it, file) }

    private fun readText(file: File): String? = try {
        AtomicFile(file).readFully().toString(Charsets.UTF_8)
    } catch (_: java.io.FileNotFoundException) {
        null
    }

    private fun decode(text: String, file: File): Project? = try {
        JSON.decodeFromString(Project.serializer(), text)
    } catch (e: Exception) {
        Log.w(TAG, "unreadable draft ${file.name}", e)
        null
    }

    private fun writeText(file: File, text: String) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(text.toByteArray())
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    private fun Project.summary() = ProjectSummary(
        id = id,
        name = name,
        updatedAt = updatedAt,
        clipCount = clips.size,
        durationUs = durationUs,
        coverUri = clips.firstOrNull()?.source?.uri,
    )

    companion object {
        private const val TAG = "ProjectStore"

        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Every media file a draft refers to. */
        internal fun Project.mediaUris(): Set<String> = buildSet {
            clips.forEach { clip -> add(clip.source.uri); clip.angles.forEach { add(it.source.uri) } }
            audio.forEach { add(it.source.uri) }
            overlays.forEach { add(it.source.uri) }
        }
    }
}
