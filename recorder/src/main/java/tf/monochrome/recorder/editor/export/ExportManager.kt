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
import tf.monochrome.recorder.media.MediaStoreFile
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

sealed interface ExportState {
    data object Idle : ExportState
    /** [percent] is null until Transformer can estimate it. */
    data class Running(val mode: ExportMode, val percent: Int?) : ExportState
    data class Done(val mode: ExportMode, val saved: SavedRecording) : ExportState
    data class Failed(val message: String) : ExportState
}

/**
 * Runs one export at a time, app-wide, so leaving the editor does not cancel
 * it. Transformer renders into the cache; the finished file is then copied
 * into MediaStore — Movies/ for video, Music/ for an extracted soundtrack.
 *
 * Transformer must be driven from one Looper thread; this is all main-thread.
 */
object ExportManager {
    private const val TAG = "ExportManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    // Built on the *application* context and dropped when the export ends, so
    // nothing short-lived is held — lint cannot tell which context it got.
    @SuppressLint("StaticFieldLeak")
    private var transformer: Transformer? = null
    private var poll: Job? = null
    private var tempFile: File? = null

    @MainThread
    fun start(context: Context, plan: ExportPlan, name: String) {
        if (_state.value is ExportState.Running) return
        val app = context.applicationContext
        val audioOnly = plan.mode == ExportMode.AUDIO_ONLY
        val tmp = File(app.cacheDir, "export/${UUID.randomUUID()}${if (audioOnly) ".m4a" else ".mp4"}")
        tmp.parentFile?.mkdirs()
        tempFile = tmp

        val composition = runCatching { CompositionFactory.build(plan) }.getOrElse {
            Log.e(TAG, "composition", it)
            _state.value = ExportState.Failed(it.message ?: it.javaClass.simpleName)
            return
        }
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
            .setEncoderFactory(encoders)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finish(app, plan.mode, tmp, name)
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
        poll = scope.launch {
            val holder = ProgressHolder()
            while (isActive) {
                val current = transformer ?: break
                if (current.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    _state.value = ExportState.Running(plan.mode, holder.progress)
                }
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

    /** Clears a finished or failed result once the UI has shown it. */
    fun acknowledge() {
        if (_state.value !is ExportState.Running) _state.value = ExportState.Idle
    }

    private fun finish(context: Context, mode: ExportMode, tmp: File, name: String) {
        poll?.cancel()
        transformer = null
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                val kind = if (mode == ExportMode.AUDIO_ONLY) MediaStoreFile.Kind.AUDIO else MediaStoreFile.Kind.VIDEO
                val out = MediaStoreFile.create(context.contentResolver, kind, name) ?: return@withContext null
                val copied = runCatching {
                    FileOutputStream(out.pfd.fileDescriptor).use { dst -> tmp.inputStream().use { it.copyTo(dst) } }
                }.isSuccess
                tmp.delete()
                if (copied) out.publish(context.contentResolver) else { out.discard(context.contentResolver); null }
            }
            tempFile = null
            _state.value = if (saved != null) ExportState.Done(mode, saved) else ExportState.Failed("Could not save the exported file")
        }
    }

    private fun cleanup() {
        poll?.cancel()
        poll = null
        transformer = null
        tempFile?.delete()
        tempFile = null
    }
}
