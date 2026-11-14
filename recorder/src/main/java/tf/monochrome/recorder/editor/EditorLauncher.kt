package tf.monochrome.recorder.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tf.monochrome.recorder.R
import tf.monochrome.recorder.capture.EditReadySession
import tf.monochrome.recorder.editor.model.AudioClip
import tf.monochrome.recorder.editor.model.AudioRole
import tf.monochrome.recorder.editor.model.EditMarker
import tf.monochrome.recorder.editor.model.MediaRef
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
        val now = System.currentTimeMillis()
        val draft = Project(
            id = newId(), name = name, createdAt = now, updatedAt = now,
            musicLinks = RecorderApp.instance.settings.musicLinks,
        ).addClips(refs)
        val restored = withContext(Dispatchers.IO) {
            var positionUs = 0L
            val audio = mutableListOf<AudioClip>()
            val markers = mutableListOf<EditMarker>()
            val clips = draft.clips.map { clip ->
                val position = positionUs
                positionUs += clip.durationUs
                val manifest = EditReadySession.load(context, clip.source.uri) ?: return@map clip
                markers += manifest.markerTimesUs.filter { it in 0 until clip.durationUs }
                    .map { EditMarker(newId(), position + it) }
                if (!manifest.isolatedAudioComplete || manifest.stems.isEmpty()) return@map clip
                val stems = manifest.stems.mapNotNull { stem ->
                    val file = EditReadySession.stemFile(context, clip.source.uri, stem.role)
                    if (file.length() <= 44) return@mapNotNull null
                    val start = stem.startUs.coerceAtLeast(0)
                    val trim = (-stem.startUs).coerceAtLeast(0)
                    val duration = minOf(stem.durationUs - trim, clip.durationUs - start)
                    if (duration <= 0) return@mapNotNull null
                    val label = context.getString(if (stem.role == AudioRole.DIALOGUE) R.string.pro_dialogue else R.string.pro_device)
                    AudioClip(
                        id = newId(), source = MediaRef(file.toUri().toString(), label, stem.durationUs,
                            hasVideo = false, hasAudio = true),
                        trimStartUs = trim, trimEndUs = trim + duration, startUs = position + start,
                        role = stem.role,
                    )
                }
                if (stems.size != manifest.stems.size) return@map clip
                audio += stems
                clip.copy(volume = 0f)
            }
            draft.copy(clips = clips, audio = audio, markers = markers)
        }
        RecorderApp.instance.projects.save(restored)
        return restored.id
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
