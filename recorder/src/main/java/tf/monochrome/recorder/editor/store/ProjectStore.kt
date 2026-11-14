package tf.monochrome.recorder.editor.store

import android.content.Context
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

/** Drafts, one JSON file each in the app's private storage. */
class ProjectStore(context: Context) {
    private val dir = File(context.filesDir, "projects").apply { mkdirs() }
    private val lock = Mutex()
    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects: StateFlow<List<ProjectSummary>> = _projects.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        lock.withLock {
            val ids = dir.list().orEmpty().mapNotNull { name ->
                when {
                    name.endsWith(".json") -> name.removeSuffix(".json")
                    name.endsWith(".json.previous") -> name.removeSuffix(".json.previous")
                    else -> null
                }
            }.distinct()
            _projects.value = ids.mapNotNull { read(file(it))?.summary() }.sortedByDescending { it.updatedAt }
        }
    }

    suspend fun load(id: String): Project? = withContext(Dispatchers.IO) { lock.withLock { read(file(id)) } }

    suspend fun save(project: Project) = withContext(Dispatchers.IO) {
        lock.withLock {
            val target = file(project.id)
            readRevision(target)?.let { previous -> writeRevision(backup(target), previous) }
            writeRevision(target, project)
            _projects.value = (_projects.value.filterNot { it.id == project.id } + project.summary())
                .sortedByDescending { it.updatedAt }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val target = file(id)
            AtomicFile(backup(target)).delete()
            AtomicFile(target).delete()
            _projects.value = _projects.value.filterNot { it.id == id }
        }
    }

    suspend fun create(name: String, clips: List<MediaRef>): Project {
        val now = System.currentTimeMillis()
        val p = Project(id = newId(), name = name, createdAt = now, updatedAt = now).addClips(clips)
        save(p)
        return p
    }

    private fun file(id: String) = File(dir, "$id.json")

    private fun backup(file: File) = File(file.parentFile, "${file.name}.previous")

    private fun read(file: File): Project? = readRevision(file) ?: readRevision(backup(file))

    private fun readRevision(file: File): Project? {
        return try {
            val text = AtomicFile(file).readFully().toString(Charsets.UTF_8)
            JSON.decodeFromString(Project.serializer(), text)
        } catch (_: java.io.FileNotFoundException) {
            null
        } catch (e: Exception) {
            Log.w("ProjectStore", "unreadable draft ${file.name}", e)
            null
        }
    }

    private fun writeRevision(file: File, project: Project) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(JSON.encodeToString(Project.serializer(), project).toByteArray())
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
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
