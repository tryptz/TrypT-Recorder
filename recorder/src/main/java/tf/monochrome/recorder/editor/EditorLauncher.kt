package tf.monochrome.recorder.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import tf.monochrome.recorder.RecorderApp
import tf.monochrome.recorder.editor.export.ExportManager
import tf.monochrome.recorder.editor.export.ExportMode
import tf.monochrome.recorder.editor.export.ExportPlanner
import tf.monochrome.recorder.editor.export.ExportSettings
import tf.monochrome.recorder.editor.export.PlanResult
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.model.addClips
import tf.monochrome.recorder.editor.model.newId
import tf.monochrome.recorder.media.MediaProbe

/** Starting points into the editor from outside it. */
object EditorLauncher {
    /** A new draft holding [uris] as clips. Returns its id, or null if none could be read. */
    suspend fun newProject(context: Context, uris: List<Uri>): String? {
        val refs = uris.mapNotNull { uri ->
            if (uri.authority != MediaStore.AUTHORITY) {
                // Picked documents: keep access, the draft refers to them.
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            MediaProbe.probe(context, uri)
        }.filter { it.hasVideo }
        if (refs.isEmpty()) return null
        val name = refs.first().displayName.substringBeforeLast('.')
        return RecorderApp.instance.projects.create(name, refs).id
    }

    /**
     * One tap from the library: the soundtrack of a video as an M4A, nothing
     * else changed. Must be called on the main thread (Transformer).
     * Returns null if the export started, or why it could not.
     */
    suspend fun extractAudio(context: Context, uri: Uri): PlanResult.Reason? {
        val ref = MediaProbe.probe(context, uri) ?: return PlanResult.Reason.NO_CLIPS
        val project = Project(id = newId(), name = ref.displayName, createdAt = 0, updatedAt = 0).addClips(listOf(ref))
        return when (val r = ExportPlanner.plan(project, ExportSettings(mode = ExportMode.AUDIO_ONLY))) {
            is PlanResult.Nothing -> r.reason
            is PlanResult.Ok -> {
                ExportManager.start(context, r.plan, ref.displayName.substringBeforeLast('.'))
                null
            }
        }
    }
}
