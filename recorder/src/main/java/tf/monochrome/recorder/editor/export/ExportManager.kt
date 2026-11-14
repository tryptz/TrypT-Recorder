@file:OptIn(UnstableApi::class)

package tf.monochrome.recorder.editor.export

import android.annotation.SuppressLint
import android.app.Application
import android.os.storage.StorageManager
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.core.net.toUri
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.recorder.R
import tf.monochrome.recorder.capture.SavedRecording
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.export.gif.GifStats
import tf.monochrome.recorder.editor.export.gif.GifTranscoder
import tf.monochrome.recorder.media.MediaStoreFile
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.roundToInt

sealed interface ExportState {
    data object Idle : ExportState
    /** [percent] is null until Transformer can estimate it. */
    data class Running(val mode: ExportMode, val percent: Int?) : ExportState
    /** [gif] describes the result of a GIF export, which the media inspector cannot read. */
    data class Done(val mode: ExportMode, val saved: SavedRecording, val gif: GifStats? = null) : ExportState
    data class Failed(val message: String) : ExportState
}

/**
 * Runs one export at a time, app-wide, so leaving the editor does not cancel
 * it. Transformer renders into the cache; the finished file is then copied
 * into MediaStore — Movies/ for video, Music/ for an extracted soundtrack,
 * Pictures/ for a GIF.
 *
 * A GIF takes one more step: Transformer renders a silent MP4 at the GIF's
 * size, and [GifTranscoder] makes the GIF from that, off the main thread.
 *
 * Transformer must be driven from one Looper thread; everything but the GIF
 * encoding itself is main-thread, state changes included.
 */
object ExportManager {
    private const val TAG = "ExportManager"
    /** Share of a GIF export's progress that rendering takes; making the GIF is the rest. */
    private const val GIF_RENDER_SHARE = 0.4f
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()
    private var activeContext: Application? = null
    private var activeJob: ExportJob? = null

    // Built on the *application* context and dropped when the export ends, so
    // nothing short-lived is held — lint cannot tell which context it got.
    @SuppressLint("StaticFieldLeak")
    private var transformer: Transformer? = null
    private var poll: Job? = null
    /** What runs after Transformer: making the GIF, saving the file. */
    private var work: Job? = null
    private val tempFiles = mutableListOf<File>()

    /** Progress, 0..100, that [poll] publishes; null while unknown. */
    private var progress: () -> Int? = { null }

    /** How far [GifTranscoder] is, 0..1. Written from its thread, read by [poll]. */
    @Volatile private var gifProgress = 0f

    fun refreshQueue(context: Context) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { ExportQueue.read(context).size } }
                .onSuccess { _pendingCount.value = it }
        }
    }

    @MainThread
    fun enqueue(context: Context, project: Project, settings: List<ExportSettings>) {
        if (_state.value is ExportState.Running || settings.isEmpty()) return
        val jobs = settings.map { preset ->
            val suffix = preset.aspectOverride?.name?.lowercase()?.let { "_$it" }.orEmpty()
            ExportJob(UUID.randomUUID().toString(), project, preset, project.name + suffix)
        }
        startQueue(context, settings.first().mode) { ExportQueue.edit(context) { it + jobs } }
    }

    @MainThread
    fun resume(context: Context) {
        if (_state.value is ExportState.Running) return
        startQueue(context, ExportMode.VIDEO) { ExportQueue.read(context) }
    }

    @MainThread
    fun discardNext(context: Context) {
        if (_state.value is ExportState.Running) return
        val app = context.applicationContext
        _state.value = ExportState.Running(ExportMode.VIDEO, null)
        scope.launch {
            try {
                _pendingCount.value = withContext(Dispatchers.IO) {
                    ExportQueue.edit(app) { jobs ->
                        jobs.firstOrNull()?.let { deletePendingOutput(app, it) }
                        jobs.drop(1)
                    }.size
                }
                activeJob = null
                activeContext = null
                _state.value = ExportState.Idle
            } catch (e: Exception) {
                _state.value = ExportState.Failed(e.message ?: app.getString(R.string.pro_export_invalid))
            }
        }
    }

    private fun deletePendingOutput(context: Context, job: ExportJob) {
        val uri = job.outputUri?.toUri() ?: return
        val resolver = context.contentResolver
        if (MediaStoreFile.describe(resolver, uri)?.first == true) resolver.delete(uri, null, null)
    }

    private fun startQueue(context: Context, mode: ExportMode, load: () -> List<ExportJob>) {
        val app = context.applicationContext
        activeJob = null
        activeContext = null
        _state.value = ExportState.Running(mode, null)
        work = scope.launch {
            try {
                val jobs = withContext(Dispatchers.IO) { load() }
                _pendingCount.value = jobs.size
                val job = jobs.firstOrNull()
                if (job == null) {
                    _state.value = ExportState.Idle
                    return@launch
                }
                activeContext = app as Application
                activeJob = job
                val published = withContext(Dispatchers.IO) {
                    job.outputUri?.let { MediaStoreFile.describe(app.contentResolver, it.toUri()) }
                        ?.takeUnless { it.first }?.second
                }
                if (published != null) {
                    completeJob(app, job)
                    _state.value = ExportState.Done(job.settings.mode, published)
                    return@launch
                }
                val plan = (ExportPlanner.plan(job.project, job.settings) as? PlanResult.Ok)?.plan
                if (plan == null) {
                    _state.value = ExportState.Failed(app.getString(R.string.pro_export_invalid))
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    File(app.cacheDir, "export").listFiles()?.forEach { it.delete() }
                    val storage = app.getSystemService(StorageManager::class.java)
                    val available = storage.getAllocatableBytes(storage.getUuidForPath(app.cacheDir))
                    val bitrate = plan.videoBitrate + if (plan.hasAudio) plan.audioBitrate else 0
                    val expectedBytes = bitrate.toLong() * plan.durationUs / 8_000_000L
                    check(available >= expectedBytes) { app.getString(R.string.error_no_storage) }
                    val uris = (plan.primary + plan.lanes.flatten()).mapNotNull { (it as? Segment.Media)?.media?.uri } +
                        plan.overlays.map { it.source.uri }
                    for (uri in uris.distinct()) {
                        requireNotNull(app.contentResolver.openFileDescriptor(uri.toUri(), "r")) {
                            app.getString(R.string.pro_export_missing_media)
                        }.close()
                    }
                }
                val music = if (plan.mode.picture) MusicLinkAnalysis.prepare(app, job.project) else null
                work = null
                render(app, plan, job.name, music)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "export queue", e)
                _state.value = ExportState.Failed(e.message ?: app.getString(R.string.pro_export_invalid))
            }
        }
    }

    private suspend fun completeJob(context: Context, job: ExportJob) {
        _pendingCount.value = withContext(Dispatchers.IO) {
            ExportQueue.edit(context) { jobs -> jobs.filterNot { it.id == job.id } }.size
        }
        activeJob = null
        activeContext = null
    }

    @MainThread
    fun start(context: Context, plan: ExportPlan, name: String) {
        if (_state.value is ExportState.Running) return
        activeJob = null
        activeContext = null
        render(context.applicationContext, plan, name)
    }

    private fun render(app: Context, plan: ExportPlan, name: String, music: MusicLinkTimeline? = null) {
        val audioOnly = plan.mode == ExportMode.AUDIO_ONLY
        val gifFps = plan.gifFrameRate

        val composition = runCatching { CompositionFactory.build(app, plan, music = music) }.getOrElse {
            Log.e(TAG, "composition", it)
            _state.value = ExportState.Failed(it.message ?: it.javaClass.simpleName)
            return
        }
        // A GIF's frames are rendered to an MP4 first.
        val tmp = tempFile(app, if (audioOnly) ".m4a" else ".mp4")
        val encoders = DefaultEncoderFactory.Builder(app)
            .setEnableFallback(true)
            .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(plan.audioBitrate).build())
            .apply {
                if (plan.videoBitrate > 0) {
                    setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(plan.videoBitrate).build())
                }
            }
            .build()
        val t = Transformer.Builder(app)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .apply {
                if (!audioOnly) setVideoMimeType(MimeTypes.VIDEO_H264)
                plan.mixer?.let { setAudioMixerFactory(RecorderAudioMixerFactory(it)) }
            }
            .setEncoderFactory(AacEncoderFactory(app, encoders, plan.audioBitrate, plan.frameRate?.fps))
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (gifFps != null) makeGif(app, tmp, gifFps, plan.durationUs, name) else finish(app, plan.mode, tmp, name, expectAudio = plan.hasAudio)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    Log.e(TAG, "export failed", exportException)
                    cleanup()
                    _state.value = ExportState.Failed(exportException.message ?: exportException.errorCodeName)
                }
            })
            .build()
        transformer = t
        _state.value = ExportState.Running(plan.mode, null)
        try {
            t.start(composition, tmp.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "start", e)
            cleanup()
            _state.value = ExportState.Failed(e.message ?: e.javaClass.simpleName)
            return
        }
        val holder = ProgressHolder()
        val renderShare = if (gifFps != null) GIF_RENDER_SHARE else 1f
        progress = {
            transformer?.takeIf { it.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE }
                ?.let { (holder.progress * renderShare).roundToInt() }
        }
        poll = scope.launch {
            while (isActive) {
                progress()?.let { _state.value = ExportState.Running(plan.mode, it) }
                delay(250)
            }
        }
    }

    @MainThread
    fun cancel() {
        val app = activeContext
        val job = activeJob
        transformer?.cancel()
        cleanup()
        if (app != null && job != null) {
            _state.value = ExportState.Running(job.settings.mode, null)
            scope.launch {
                try {
                    _pendingCount.value = withContext(Dispatchers.IO) {
                        ExportQueue.edit(app) { jobs ->
                            jobs.firstOrNull { it.id == job.id }?.let { deletePendingOutput(app, it) }
                            jobs.filterNot { it.id == job.id }
                        }.size
                    }
                    activeJob = null
                    activeContext = null
                    _state.value = ExportState.Idle
                } catch (e: Exception) {
                    _state.value = ExportState.Failed(e.message ?: app.getString(R.string.pro_export_invalid))
                }
            }
        } else {
            _state.value = ExportState.Idle
        }
    }

    private fun tempFile(context: Context, extension: String): File {
        val f = File(context.cacheDir, "export/${UUID.randomUUID()}$extension")
        f.parentFile?.mkdirs()
        tempFiles += f
        return f
    }

    /** Turns the rendered [video] into a GIF, then saves that. */
    private fun makeGif(context: Context, video: File, fps: Int, durationUs: Long, name: String) {
        transformer = null
        val gif = tempFile(context, ".gif")
        gifProgress = 0f
        progress = { ((GIF_RENDER_SHARE + (1 - GIF_RENDER_SHARE) * gifProgress) * 100).roundToInt() }
        work = scope.launch {
            val stats = try {
                withContext(Dispatchers.Default) {
                    GifTranscoder.transcode(video, gif, fps, durationUs) { gifProgress = it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "gif failed", e)
                _state.value = ExportState.Failed(e.message ?: e.javaClass.simpleName)
                cleanup()
                return@launch
            }
            video.delete()
            finish(context, ExportMode.GIF, gif, name, stats)
        }
    }

    /** Clears a finished or failed result once the UI has shown it. */
    fun acknowledge() {
        if (_state.value !is ExportState.Running) _state.value = ExportState.Idle
    }

    private fun finish(
        context: Context, mode: ExportMode, tmp: File, name: String, gif: GifStats? = null,
        expectAudio: Boolean = false,
    ) {
        poll?.cancel()
        transformer = null
        val job = activeJob
        work = scope.launch {
            val saved = try {
                withContext(Dispatchers.IO) {
                    verifyContainer(tmp, mode, expectAudio)
                    val kind = when (mode) {
                        ExportMode.AUDIO_ONLY -> MediaStoreFile.Kind.AUDIO
                        ExportMode.GIF -> MediaStoreFile.Kind.GIF
                        ExportMode.VIDEO, ExportMode.VIDEO_ONLY -> MediaStoreFile.Kind.VIDEO
                    }
                    val resolver = context.contentResolver
                    val existing = job?.outputUri?.toUri()
                    val out = existing?.let { MediaStoreFile.reopen(resolver, it) }
                        ?: MediaStoreFile.create(resolver, kind, name) ?: return@withContext null
                    try {
                        if (job != null) {
                            ExportQueue.edit(context) { jobs ->
                                check(jobs.any { it.id == job.id })
                                jobs.map { if (it.id == job.id) it.copy(outputUri = out.uri.toString()) else it }
                            }
                        }
                        FileOutputStream(out.pfd.fileDescriptor).use { dst ->
                            tmp.inputStream().use { input ->
                                val bytes = ByteArray(64 * 1024)
                                while (isActive) {
                                    val n = input.read(bytes)
                                    if (n == -1) break
                                    dst.write(bytes, 0, n)
                                }
                            }
                        }
                        if (isActive) out.publish(resolver) else { out.discard(resolver); null }
                    } catch (e: Exception) {
                        out.discard(resolver)
                        throw e
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "publish export", e)
                null
            } finally {
                tmp.delete()
            }
            if (saved != null && job != null) {
                try {
                    completeJob(context, job)
                } catch (e: Exception) {
                    Log.w(TAG, "remove completed job", e)
                }
            }
            tempFiles.clear()
            work = null
            _state.value = if (saved != null) ExportState.Done(mode, saved, gif) else ExportState.Failed("Could not save the exported file")
        }
    }

    private fun verifyContainer(file: File, mode: ExportMode, expectAudio: Boolean) {
        require(file.length() > 0)
        if (mode == ExportMode.GIF) {
            java.io.RandomAccessFile(file, "r").use {
                val header = ByteArray(6)
                it.readFully(header)
                check(header.toString(Charsets.US_ASCII) == "GIF89a")
                it.seek(it.length() - 1)
                check(it.read() == 0x3b)
            }
            return
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val types = (0 until extractor.trackCount).map {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
            }
            check(if (mode == ExportMode.AUDIO_ONLY) types.any { it.startsWith("audio/") }
                else types.any { it.startsWith("video/") })
            if (!mode.sound) check(types.none { it.startsWith("audio/") })
            if (expectAudio) check(types.any { it.startsWith("audio/") })
        } finally {
            extractor.release()
        }
    }

    private fun cleanup() {
        poll?.cancel()
        poll = null
        work?.cancel()
        work = null
        transformer = null
        progress = { null }
        tempFiles.forEach { it.delete() }
        tempFiles.clear()
    }
}
