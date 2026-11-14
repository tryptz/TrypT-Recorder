@file:OptIn(UnstableApi::class, ExperimentalApi::class)

package tf.monochrome.recorder.editor.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.graphics.Bitmap
import android.view.SurfaceView
import java.lang.ref.WeakReference
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
import androidx.media3.effect.MultipleInputVideoGraph
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tf.monochrome.recorder.R
import tf.monochrome.recorder.RecorderApp
import tf.monochrome.recorder.editor.export.CompositionFactory
import tf.monochrome.recorder.editor.export.MusicLinkAnalysis
import tf.monochrome.recorder.editor.model.VisualLink
import tf.monochrome.recorder.editor.export.RecorderAudioMixerFactory
import tf.monochrome.recorder.editor.export.ExportManager
import tf.monochrome.recorder.editor.export.ExportPlan
import tf.monochrome.recorder.editor.export.ExportPlanner
import tf.monochrome.recorder.editor.export.ExportResolution
import tf.monochrome.recorder.editor.export.ExportSettings
import tf.monochrome.recorder.editor.export.PlanResult
import tf.monochrome.recorder.editor.analysis.TrackAnalysis
import tf.monochrome.recorder.editor.model.AspectPreset
import tf.monochrome.recorder.editor.model.CameraAngle
import tf.monochrome.recorder.editor.model.Captions
import tf.monochrome.recorder.editor.model.CropRect
import tf.monochrome.recorder.editor.model.CubeLut
import tf.monochrome.recorder.editor.model.VideoClip
import tf.monochrome.recorder.editor.model.OVERLAY_LAYERS
import tf.monochrome.recorder.editor.model.newId
import tf.monochrome.recorder.editor.model.updateAngles
import tf.monochrome.recorder.editor.model.updateClipPicture
import tf.monochrome.recorder.editor.model.cutToAngle
import tf.monochrome.recorder.editor.model.clipStartUs
import tf.monochrome.recorder.editor.store.ProjectBundle
import tf.monochrome.recorder.editor.model.CropInsets
import tf.monochrome.recorder.editor.model.History
import tf.monochrome.recorder.editor.model.MediaRef
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.model.addAudio
import tf.monochrome.recorder.editor.model.addClips
import tf.monochrome.recorder.editor.model.addOverlay
import tf.monochrome.recorder.editor.model.addText
import tf.monochrome.recorder.editor.model.durationUs
import tf.monochrome.recorder.media.MediaProbe
import tf.monochrome.recorder.media.CreativeAssets

/** What the tools act on. */
sealed interface Selection {
    data object None : Selection
    data class Clip(val id: String) : Selection
    data class Audio(val id: String) : Selection
    data class Text(val id: String) : Selection
    data class Overlay(val id: String) : Selection
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
    val bundleBusy = MutableStateFlow(false)

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

    private val _grainSolo = MutableStateFlow(false)
    /** The preview shows the grain alone, on mid grey. Never exported. */
    val grainSolo: StateFlow<Boolean> = _grainSolo.asStateFlow()

    private var surfaceView: WeakReference<SurfaceView>? = null
    private var playerUsesOverlayGraph = false
    private var player: CompositionPlayer? = null
    private var lastPlan: ExportPlan? = null
    private var rebuildJob: Job? = null
    private var saveJob: Job? = null
    private var tickJob: Job? = null
    private var trackingJob: Job? = null

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
    fun addText(label: String, caption: Boolean = false) {
        edit { it.addText(label, _playheadUs.value, caption = caption) }
        project?.texts?.lastOrNull()?.let { selection.value = Selection.Text(it.id) }
    }

    private fun afterChange() {
        val p = project ?: return
        // Drop a selection whose target no longer exists (deleted, undone).
        when (val s = selection.value) {
            is Selection.Clip -> if (p.clips.none { it.id == s.id }) selection.value = Selection.None
            is Selection.Audio -> if (p.audio.none { it.id == s.id }) selection.value = Selection.None
            is Selection.Overlay -> if (p.overlays.none { it.id == s.id }) selection.value = Selection.None
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

    suspend fun addPictureInPicture(uri: Uri) {
        val ref = probeAll(listOf(uri)).firstOrNull { it.hasVideo }
        val current = project ?: return
        val next = ref?.let { media ->
            (0 until OVERLAY_LAYERS).asSequence().map { current.addOverlay(media, _playheadUs.value, it) }
                .firstOrNull { it != current }
        }
        if (next == null || next == current) {
            message.value = getApplication<Application>().getString(R.string.editor_overlay_unavailable)
            return
        }
        edit { next }
        val added = next.overlays.first { item -> current.overlays.none { it.id == item.id } }
        selection.value = Selection.Overlay(added.id)
        scrubTo(added.startUs)
    }

    suspend fun addAudio(uri: Uri, title: String? = null) {
        val ref = probeAll(listOf(uri)).firstOrNull { it.hasAudio }
        if (ref == null) {
            message.value = getApplication<Application>().getString(R.string.editor_no_audio_in_file)
            return
        }
        edit { it.addAudio(if (title == null) ref else ref.copy(displayName = title), _playheadUs.value) }
    }

    internal suspend fun addLibraryMusic(track: CreativeAssets.Track) {
        try {
            addAudio(CreativeAssets.fileFor(getApplication(), track), track.title)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            message.value = getApplication<Application>().getString(R.string.editor_no_audio_in_file)
        }
    }

    internal suspend fun applyLibraryLut(clipId: String, preset: CreativeAssets.Lut) {
        try {
            val lut = CreativeAssets.readLut(getApplication(), preset)
            applyLut(clipId, lut)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            message.value = getApplication<Application>().getString(R.string.pro_lut_error)
        }
    }

    private fun applyLut(clipId: String, lut: CubeLut) {
        edit { p -> p.updateClipPicture(clipId) { it.copy(grade = it.grade.copy(lut = lut, bypass = false)) } }
    }

    suspend fun addAngle(clipId: String, uri: Uri) {
        val ref = probeAll(listOf(uri)).firstOrNull { it.hasVideo }
        if (ref == null) {
            message.value = getApplication<Application>().getString(R.string.editor_some_files_skipped)
            return
        }
        edit { draft -> draft.updateAngles(clipId) { it.copy(angles = it.angles + CameraAngle(newId(), ref)) } }
    }

    fun switchAngle(clipId: String, angleId: String?) {
        val current = project ?: return
        val time = _playheadUs.value
        val next = current.cutToAngle(clipId, angleId, time)
        applyPrecision(next)
        if (next != null && next !== current) {
            val originalIndex = current.clips.indexOfFirst { it.id == clipId }
            val newIndex = originalIndex + if (time > current.clipStartUs(originalIndex)) 1 else 0
            next.clips.getOrNull(newIndex)?.let { selection.value = Selection.Clip(it.id) }
        }
    }

    fun applyPrecision(next: Project?) {
        if (next == null) message.value = getApplication<Application>().getString(R.string.pro_edit_unavailable)
        else edit { next }
    }

    private suspend fun readDocument(uri: Uri, limit: Int): String = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        requireNotNull(resolver.openInputStream(uri)).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                require(out.size() + n <= limit)
                out.write(buffer, 0, n)
            }
            out.toString(Charsets.UTF_8.name())
        }
    }

    suspend fun importLut(clipId: String, uri: Uri) {
        val context = getApplication<Application>()
        try {
            val text = readDocument(uri, 4 * 1024 * 1024)
            val lut = withContext(Dispatchers.Default) {
                CubeLut.parse(text, context.getString(R.string.pro_lut_default))
            }
            applyLut(clipId, lut)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            message.value = context.getString(R.string.pro_lut_error)
        }
    }

    suspend fun importCaptions(uri: Uri) {
        val context = getApplication<Application>()
        try {
            val text = readDocument(uri, 1_048_576)
            val captions = withContext(Dispatchers.Default) { Captions.parse(text) }
            require(captions.isNotEmpty())
            edit { it.copy(texts = it.texts + captions) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            message.value = context.getString(R.string.pro_caption_import_error)
        }
    }

    suspend fun exportCaptions(uri: Uri) {
        val draft = project ?: return
        val context = getApplication<Application>()
        val srt = Captions.write(draft.texts, draft.durationUs)
        try {
            withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use {
                    it.write(srt.toByteArray(Charsets.UTF_8))
                }
            }
            message.value = context.getString(R.string.pro_caption_saved)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            message.value = context.getString(R.string.pro_caption_export_error)
        }
    }

    suspend fun saveBundle(uri: Uri) {
        val draft = project ?: return
        if (bundleBusy.value) return
        val context = getApplication<Application>()
        bundleBusy.value = true
        try {
            ProjectBundle.export(context, draft, uri)
            message.value = context.getString(R.string.pro_bundle_saved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "save project bundle", e)
            message.value = context.getString(R.string.pro_bundle_error)
        } finally {
            bundleBusy.value = false
        }
    }

    suspend fun trackingFrame(clip: VideoClip, sourceUs: Long): Bitmap? =
        TrackAnalysis.sourceFrame(getApplication(), clip.source, sourceUs)

    fun trackCover(clip: VideoClip, sourceUs: Long, box: CropRect) {
        trackingJob?.cancel()
        val context = getApplication<Application>()
        message.value = context.getString(R.string.pro_track_running)
        trackingJob = viewModelScope.launch {
            try {
                val track = TrackAnalysis.analyze(context, clip.source, clip.trimStartUs, clip.trimEndUs, sourceUs, box)
                val latest = project?.clips?.firstOrNull { it.id == clip.id && it.source == clip.source && it.angleId == null }
                if (latest != null) {
                    edit { p -> p.updateClipPicture(clip.id) { it.copy(trackedCover = track) } }
                    val misses = track.samples.count { !it.matched }
                    message.value = context.resources.getQuantityString(R.plurals.pro_track_done, misses, misses)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "cover tracking", e)
                message.value = context.getString(R.string.pro_track_failed)
            }
        }
    }

    fun correctCover(id: String, sourceUs: Long, box: CropRect) = edit { p ->
        p.updateClipPicture(id) { it.copy(trackedCover = it.trackedCover?.correct(sourceUs, box)) }
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
        surfaceView = WeakReference(view)
        player?.setVideoSurfaceView(view)
        lastPlan = null
        schedulePreview(immediate = true)
    }

    fun detachSurface(view: SurfaceView) {
        if (surfaceView?.get() !== view) return
        surfaceView = null
        rebuildJob?.cancel()
        player?.let { p ->
            runCatching { p.clearVideoSurfaceView(view) }
            p.pause()
        }
        releasePlayer()
        viewModelScope.launch { saveNow() }
    }

    private val _mixerPeaks = MutableStateFlow(FloatArray(0))
    val mixerPeaks = _mixerPeaks.asStateFlow()
    private val previewMixer = RecorderAudioMixerFactory(publishPeaks = { _mixerPeaks.value = it })

    private fun createPlayer(view: SurfaceView, useOverlayGraph: Boolean): CompositionPlayer {
        releasePlayer()
        val p = CompositionPlayer.Builder(getApplication()).apply {
            setAudioMixerFactory(previewMixer)
            if (useOverlayGraph) setVideoGraphFactory(MultipleInputVideoGraph.Factory())
        }.build()
        p.setVideoSurfaceView(view)
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
        playerUsesOverlayGraph = useOverlayGraph
        return p
    }

    private fun releasePlayer() {
        tickJob?.cancel()
        player?.release()
        player = null
        lastPlan = null
        _playing.value = false
        _mixerPeaks.value = FloatArray(0)
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

    fun setGrainSolo(on: Boolean) {
        if (_grainSolo.value == on) return
        _grainSolo.value = on
        schedulePreview(immediate = true)
    }

    private var lastPreviewLinks: List<VisualLink> = emptyList()

    private suspend fun rebuildPreview() {
        val p = project?.let {
            if (cropEditing) it.copy(canvas = it.canvas.copy(crop = CropInsets(), aspect = AspectPreset.ORIGINAL, fill = false)) else it
        } ?: return
        val result = ExportPlanner.plan(p, PREVIEW_SETTINGS, retainMutedInputs = true)
        val plan = (result as? PlanResult.Ok)?.plan?.let { pl ->
            if (!_grainSolo.value) pl else pl.copy(visuals = pl.visuals?.let { v -> v.copy(grain = v.grain?.copy(solo = true)) })
        }
        plan?.visuals?.let { _aspect.value = it.width.toFloat() / it.height }
        val view = surfaceView?.get() ?: return
        if (plan == lastPlan && p.musicLinks == lastPreviewLinks) return
        val previousPlan = lastPlan
        plan?.mixer?.let { previewMixer.settings = it }
        if (p.musicLinks.isEmpty() && lastPreviewLinks.isEmpty() &&
            plan?.mixer != null && previousPlan?.mixer != null && plan == previousPlan.copy(mixer = plan.mixer)
        ) {
            lastPlan = plan
            return
        }
        _previewError.value = null
        if (plan == null) {
            releasePlayer()
            return
        }
        try {
            val music = MusicLinkAnalysis.prepare(getApplication(), p)
            if (surfaceView?.get() !== view) return
            val wasPlaying = player?.isPlaying == true
            val usesOverlay = plan.overlays.isNotEmpty()
            val pl = player?.takeIf { playerUsesOverlayGraph == usesOverlay } ?: createPlayer(view, usesOverlay)
            pl.setComposition(CompositionFactory.build(getApplication(), plan, preview = true, music = music), _playheadUs.value / 1000)
            pl.prepare()
            lastPlan = plan
            lastPreviewLinks = p.musicLinks
            if (wasPlaying) pl.play()
        } catch (e: CancellationException) {
            throw e
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

    fun refreshExportQueue() = ExportManager.refreshQueue(getApplication())

    fun resumeExport() {
        releasePlayer()
        ExportManager.resume(getApplication())
    }

    fun discardQueuedExport() = ExportManager.discardNext(getApplication())

    /** Returns null if the export was queued, or why it could not be queued. */
    fun export(settings: ExportSettings, bothAspects: Boolean = false): PlanResult.Reason? {
        val draft = project ?: return PlanResult.Reason.NO_CLIPS
        val result = ExportPlanner.plan(draft, settings)
        if (result is PlanResult.Nothing) return result.reason
        val variants = if (bothAspects) listOf(
            settings.copy(aspectOverride = AspectPreset.WIDE),
            settings.copy(aspectOverride = AspectPreset.TALL),
        ) else listOf(settings)
        releasePlayer()
        ExportManager.enqueue(getApplication(), draft, variants)
        return null
    }

    /** After an export the preview needs preparing again. */
    fun restorePreview() = schedulePreview(immediate = true)

    override fun onCleared() {
        rebuildJob?.cancel()
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
