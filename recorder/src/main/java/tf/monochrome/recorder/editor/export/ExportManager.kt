@file:OptIn(UnstableApi::class)

package tf.monochrome.recorder.editor.export

import android.annotation.SuppressLint
import android.content.Context
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
import tf.monochrome.recorder.capture.SavedRecording
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

    @MainThread
    fun start(context: Context, plan: ExportPlan, name: String) {
        if (_state.value is ExportState.Running) return
        val app = context.applicationContext
        val audioOnly = plan.mode == ExportMode.AUDIO_ONLY
        val gifFps = plan.gifFrameRate

        val composition = runCatching { CompositionFactory.build(plan) }.getOrElse {
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
            .apply { if (!audioOnly) setVideoMimeType(MimeTypes.VIDEO_H264) }
            .setEncoderFactory(AacEncoderFactory(app, encoders, plan.audioBitrate))
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (gifFps != null) makeGif(app, tmp, gifFps, plan.durationUs, name) else finish(app, plan.mode, tmp, name)
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
        transformer?.cancel()
        cleanup()
        _state.value = ExportState.Idle
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

    private fun finish(context: Context, mode: ExportMode, tmp: File, name: String, gif: GifStats? = null) {
        poll?.cancel()
        transformer = null
        work = scope.launch {
            val saved = withContext(Dispatchers.IO) {
                val kind = when (mode) {
                    ExportMode.AUDIO_ONLY -> MediaStoreFile.Kind.AUDIO
                    ExportMode.GIF -> MediaStoreFile.Kind.GIF
                    ExportMode.VIDEO, ExportMode.VIDEO_ONLY -> MediaStoreFile.Kind.VIDEO
                }
                val out = MediaStoreFile.create(context.contentResolver, kind, name) ?: return@withContext null
                val copied = runCatching {
                    FileOutputStream(out.pfd.fileDescriptor).use { dst -> tmp.inputStream().use { it.copyTo(dst) } }
                }.isSuccess
                tmp.delete()
                // Cancelled while copying: the file is not wanted any more.
                if (copied && isActive) out.publish(context.contentResolver) else { out.discard(context.contentResolver); null }
            }
            tempFiles.clear()
            work = null
            _state.value = if (saved != null) ExportState.Done(mode, saved, gif) else ExportState.Failed("Could not save the exported file")
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
