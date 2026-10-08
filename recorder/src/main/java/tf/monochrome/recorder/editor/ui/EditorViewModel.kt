@file:OptIn(UnstableApi::class, ExperimentalApi::class)

package tf.monochrome.recorder.editor.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tf.monochrome.recorder.R
import tf.monochrome.recorder.RecorderApp
import tf.monochrome.recorder.editor.export.CompositionFactory
import tf.monochrome.recorder.editor.export.ExportManager
import tf.monochrome.recorder.editor.export.ExportPlan
import tf.monochrome.recorder.editor.export.ExportPlanner
import tf.monochrome.recorder.editor.export.ExportResolution
import tf.monochrome.recorder.editor.export.ExportSettings
import tf.monochrome.recorder.editor.export.PlanResult
import tf.monochrome.recorder.editor.model.AspectPreset
import tf.monochrome.recorder.editor.model.CropInsets
import tf.monochrome.recorder.editor.model.History
import tf.monochrome.recorder.editor.model.MediaRef
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.model.addAudio
import tf.monochrome.recorder.editor.model.addClips
import tf.monochrome.recorder.editor.model.addText
import tf.monochrome.recorder.editor.model.durationUs
import tf.monochrome.recorder.media.MediaProbe

/** What the tools act on. */
sealed interface Selection {
    data object None : Selection
    data class Clip(val id: String) : Selection
    data class Audio(val id: String) : Selection
    data class Text(val id: String) : Selection
}

/**
 * The editor's state: the project under undo, what is selected, where the
 * playhead is, and the preview player.
 *
 * The preview is a CompositionPlayer playing exactly the Composition an export
 * would render (at a lighter 720p canvas), rebuilt only when the export plan
 * actually changes — selecting things or renaming the project does not
 * re-prepare decoders, and slider drags are debounced.
 */
class EditorViewModel(app: Application, private val projectId: String) : AndroidViewModel(app) {
    private val store = RecorderApp.instance.projects

    private val _history = MutableStateFlow<History<Project>?>(null)
    val history: StateFlow<History<Project>?> = _history.asStateFlow()

    val selection = MutableStateFlow<Selection>(Selection.None)

    private val _playheadUs = MutableStateFlow(0L)
    val playheadUs: StateFlow<Long> = _playheadUs.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    /** Width / height of the output frame, for sizing the preview. */
    private val _aspect = MutableStateFlow(9f / 16f)
    val aspect: StateFlow<Float> = _aspect.asStateFlow()

    private val _previewError = MutableStateFlow<String?>(null)
    val previewError: StateFlow<String?> = _previewError.asStateFlow()

    /** Shown once, e.g. "that file could not be read". */
    val message = MutableStateFlow<String?>(null)

    /** While the crop tool is open the preview shows the whole picture, for the crop box to sit over. */
    private var cropEditing = false

    // No reference to the SurfaceView is kept: a view model outlives its views.
    private var player: CompositionPlayer? = null
    private var lastPlan: ExportPlan? = null
    private var rebuildJob: Job? = null
    private var saveJob: Job? = null
    private var tickJob: Job? = null

    val project: Project? get() = _history.value?.present

    init {
        viewModelScope.launch {
            val p = store.load(projectId)
            if (p == null) {
                message.value = app.getString(R.string.editor_draft_missing)
                return@launch
            }
            _history.value = History(p)
            schedulePreview(immediate = true)
        }
    }

    // ── Editing ────────────────────────────────────────────────────────

    /** A discrete edit: one undo step. */
    fun edit(transform: (Project) -> Project) {
        val h = _history.value ?: return
        val next = transform(h.present)
        if (next == h.present) return
        _history.value = h.push(next)
        afterChange()
    }

    /** A continuous edit (slider mid-drag): previewed, not yet a step. */
    fun preview(transform: (Project) -> Project) {
        val h = _history.value ?: return
        _history.value = h.replace(transform(h.present))
        afterChange()
    }

    /** Ends a continuous edit as one step. */
    fun commit() {
        _history.value = _history.value?.commit()
    }

    fun undo() {
        _history.value = _history.value?.undo()
        afterChange()
    }

    fun redo() {
        _history.value = _history.value?.redo()
        afterChange()
    }

    fun rename(name: String) = edit { it.copy(name = name.trim().ifEmpty { it.name }) }

    /** Adds a title at the playhead and selects it for editing. */
    fun addText(label: String) {
        edit { it.addText(label, _playheadUs.value) }
        project?.texts?.lastOrNull()?.let { selection.value = Selection.Text(it.id) }
    }

    private fun afterChange() {
        val p = project ?: return
        // Drop a selection whose target no longer exists (deleted, undone).
        when (val s = selection.value) {
            is Selection.Clip -> if (p.clips.none { it.id == s.id }) selection.value = Selection.None
            is Selection.Audio -> if (p.audio.none { it.id == s.id }) selection.value = Selection.None
            is Selection.Text -> if (p.texts.none { it.id == s.id }) selection.value = Selection.None
            Selection.None -> Unit
        }
        _playheadUs.value = _playheadUs.value.coerceIn(0, p.durationUs)
        schedulePreview(immediate = false)
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500)
            saveNow()
        }
    }

    private suspend fun saveNow() {
        val p = project ?: return
        store.save(p.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun addClips(uris: List<Uri>) {
        val refs = probeAll(uris).filter { it.hasVideo }
        if (refs.size < uris.size) message.value = getApplication<Application>().getString(R.string.editor_some_files_skipped)
        if (refs.isNotEmpty()) edit { it.addClips(refs) }
    }

    suspend fun addAudio(uri: Uri) {
        val ref = probeAll(listOf(uri)).firstOrNull { it.hasAudio }
        if (ref == null) {
            message.value = getApplication<Application>().getString(R.string.editor_no_audio_in_file)
            return
        }
        edit { it.addAudio(ref, _playheadUs.value) }
    }

    private suspend fun probeAll(uris: List<Uri>): List<MediaRef> {
        val resolver = getApplication<Application>().contentResolver
        return uris.mapNotNull { uri ->
            // Keep access across restarts; the draft refers to these files.
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            MediaProbe.probe(getApplication(), uri)
        }
    }

    // ── Preview ────────────────────────────────────────────────────────

    fun attachSurface(view: SurfaceView) {
        val p = ensurePlayer()
        p.setVideoSurfaceView(view)
        lastPlan = null
        schedulePreview(immediate = true)
    }

    fun detachSurface(view: SurfaceView) {
        player?.let { p ->
            runCatching { p.clearVideoSurfaceView(view) }
            p.pause()
        }
        releasePlayer()
        viewModelScope.launch { saveNow() }
    }

    private fun ensurePlayer(): CompositionPlayer = player ?: CompositionPlayer.Builder(getApplication()).build().also { p ->
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playing.value = isPlaying
                if (isPlaying) startTicking() else tickJob?.cancel()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    _playheadUs.value = project?.durationUs ?: 0
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "preview error", error)
                _previewError.value = error.errorCodeName
            }
        })
        player = p
    }

    private fun releasePlayer() {
        tickJob?.cancel()
        rebuildJob?.cancel()
        player?.release()
        player = null
        lastPlan = null
        _playing.value = false
    }

    private fun schedulePreview(immediate: Boolean) {
        rebuildJob?.cancel()
        rebuildJob = viewModelScope.launch {
            if (!immediate) delay(180)
            rebuildPreview()
        }
    }

    /**
     * Shows the picture uncropped, in its own shape, while the crop tool is
     * open. The plan then ignores the crop, so dragging the box does not
     * rebuild the player; leaving the tool does, once.
     */
    fun setCropEditing(on: Boolean) {
        if (cropEditing == on) return
        cropEditing = on
        schedulePreview(immediate = true)
    }

    private fun rebuildPreview() {
        val p = project?.let {
            if (cropEditing) it.copy(canvas = it.canvas.copy(crop = CropInsets(), aspect = AspectPreset.ORIGINAL, fill = false)) else it
        } ?: return
        val result = ExportPlanner.plan(p, PREVIEW_SETTINGS)
        val plan = (result as? PlanResult.Ok)?.plan
        plan?.visuals?.let { _aspect.value = it.width.toFloat() / it.height }
        val pl = player ?: return
        if (plan == lastPlan) return
        lastPlan = plan
        _previewError.value = null
        if (plan == null) {
            pl.stop()
            return
        }
        try {
            val wasPlaying = pl.isPlaying
            pl.setComposition(CompositionFactory.build(plan), _playheadUs.value / 1000)
            pl.prepare()
            if (wasPlaying) pl.play()
        } catch (e: Exception) {
            Log.w(TAG, "preview rebuild", e)
            _previewError.value = e.message ?: e.javaClass.simpleName
        }
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive) {
                player?.let { _playheadUs.value = it.currentPosition * 1000 }
                delay(33)
            }
        }
    }

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            return
        }
        val total = project?.durationUs ?: 0
        // From the end, play from the start again.
        if (_playheadUs.value >= total - 50_000) seekTo(0)
        p.play()
    }

    /** The user moved the playhead (scrubbing the timeline). */
    fun scrubTo(timeUs: Long) {
        player?.takeIf { it.isPlaying }?.pause()
        seekTo(timeUs)
    }

    fun seekTo(timeUs: Long) {
        val t = timeUs.coerceIn(0, project?.durationUs ?: 0)
        _playheadUs.value = t
        player?.seekTo(t / 1000)
    }

    // ── Export ─────────────────────────────────────────────────────────

    /** Returns null if the export started, or why it could not. */
    fun export(settings: ExportSettings): PlanResult.Reason? {
        val p = project ?: return PlanResult.Reason.NO_CLIPS
        return when (val r = ExportPlanner.plan(p, settings)) {
            is PlanResult.Nothing -> r.reason
            is PlanResult.Ok -> {
                // Free the decoders the preview holds; phones have only so many.
                player?.pause()
                player?.stop()
                lastPlan = null
                ExportManager.start(getApplication(), r.plan, p.name)
                null
            }
        }
    }

    /** After an export the preview needs preparing again. */
    fun restorePreview() = schedulePreview(immediate = true)

    override fun onCleared() {
        releasePlayer()
        super.onCleared()
    }

    companion object {
        private const val TAG = "EditorViewModel"

        /** Same plan as an export, on a lighter canvas. */
        private val PREVIEW_SETTINGS = ExportSettings(resolution = ExportResolution.P720)

        fun factory(projectId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                EditorViewModel(app, projectId)
            }
        }
    }
}
