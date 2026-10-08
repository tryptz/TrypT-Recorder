package tf.monochrome.recorder.editor.store

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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
    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects: StateFlow<List<ProjectSummary>> = _projects.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _projects.value = dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f -> read(f)?.summary() }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun load(id: String): Project? = withContext(Dispatchers.IO) { read(file(id)) }

    suspend fun save(project: Project) = withContext(Dispatchers.IO) {
        // Write-then-rename, so a crash mid-save never leaves half a draft.
        val tmp = File(dir, "${project.id}.json.tmp")
        tmp.writeText(JSON.encodeToString(Project.serializer(), project))
        tmp.renameTo(file(project.id))
        _projects.value = (_projects.value.filterNot { it.id == project.id } + project.summary())
            .sortedByDescending { it.updatedAt }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        file(id).delete()
        _projects.value = _projects.value.filterNot { it.id == id }
    }

    suspend fun create(name: String, clips: List<MediaRef>): Project {
        val now = System.currentTimeMillis()
        val p = Project(id = newId(), name = name, createdAt = now, updatedAt = now).addClips(clips)
        save(p)
        return p
    }

    private fun file(id: String) = File(dir, "$id.json")

    private fun read(f: File): Project? = runCatching {
        if (f.exists()) JSON.decodeFromString(Project.serializer(), f.readText()) else null
    }.onFailure { Log.w("ProjectStore", "unreadable draft ${f.name}", it) }.getOrNull()

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
