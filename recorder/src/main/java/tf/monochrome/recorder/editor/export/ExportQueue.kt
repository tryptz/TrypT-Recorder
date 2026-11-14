package tf.monochrome.recorder.editor.export

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.store.ProjectStore
import java.io.File

@Serializable
data class ExportJob(
    val id: String,
    val project: Project,
    val settings: ExportSettings,
    val name: String,
    val outputUri: String? = null,
)

/** The next export can be restarted from its immutable edit and delivery settings. */
object ExportQueue {
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "export-queue.json"))

    @Synchronized
    fun read(context: Context): List<ExportJob> {
        val file = file(context)
        return try {
            ProjectStore.JSON.decodeFromString<List<ExportJob>>(file.readFully().toString(Charsets.UTF_8))
        } catch (_: java.io.FileNotFoundException) {
            emptyList()
        }
    }

    @Synchronized
    fun edit(context: Context, change: (List<ExportJob>) -> List<ExportJob>): List<ExportJob> {
        val jobs = change(read(context))
        val atomic = file(context)
        val output = atomic.startWrite()
        try {
            output.write(ProjectStore.JSON.encodeToString(jobs).toByteArray())
            atomic.finishWrite(output)
        } catch (e: Exception) {
            atomic.failWrite(output)
            throw e
        }
        return jobs
    }
}
