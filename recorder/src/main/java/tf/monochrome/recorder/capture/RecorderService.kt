package tf.monochrome.recorder.capture

import androidx.core.net.toUri
import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.recorder.MainActivity
import tf.monochrome.recorder.R
import tf.monochrome.recorder.RecorderApp
import tf.monochrome.recorder.media.MediaStoreFile
import tf.monochrome.recorder.settings.AudioSource
import tf.monochrome.recorder.settings.RecorderSettings

/**
 * Foreground service that owns the capture and the floating record button.
 *
 * Descended from Tryptify's DebugScreenRecordService. One service holds both
 * jobs because they share a lifetime and a notification: while only the
 * button is up it runs as `specialUse`; once the user has consented to
 * capture it adds `mediaProjection` — which Android 10+ requires *before*
 * getMediaProjection() — and drops it again when the recording is saved.
 *
 * Every start of a capture comes through [CaptureConsentActivity] with a fresh
 * consent result: on Android 14+ a consent token is good for one projection,
 * so there is nothing to cache.
 *
 * Nothing slow runs on the main thread. The projection is taken and its
 * callback registered here, in that order (Android 14 requires both before
 * any capture); everything after — encoder queries, the MediaStore row,
 * codecs, AudioRecord and the VirtualDisplay — is set up on a background
 * thread, and stopping (joining the encoder threads, publishing) runs there
 * too, in the app's scope so it finishes even if the service is destroyed.
 */
class RecorderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val settings get() = RecorderApp.instance.settings

    private var button: FloatingButton? = null
    private var projection: MediaProjection? = null
    private var recorder: ScreenRecorder? = null
    private var output: MediaStoreFile? = null
    private var countdownJob: Job? = null
    private var storageJob: Job? = null
    private var stopping = false
    private var inForeground = false
    private var destroyed = false
    /** A recording is being set up off the main thread. */
    private var preparing = false
    /** Bumped whenever a start in progress is abandoned; a setup that finishes into an old one is torn down. */
    private var startGeneration = 0
    /** The button was taken away for a recording that has not produced its first frame yet. */
    private var preRollHidden = false

    private val projectionCallback = object : MediaProjection.Callback() {
        // The user stopped sharing from the status bar, or another app took
        // the projection over.
        override fun onStop() {
            main.post { stopCapture() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Content only. Whether the service runs, and with which foreground
        // type, is decided by the explicit transitions below — reacting to the
        // first (Idle) emission here would stop the service before
        // onStartCommand had even run.
        scope.launch {
            combine(RecorderState.status, settings.settings) { status, s -> status to s }.collect { (status, s) ->
                button?.setHidden(hideWanted(status, s))
                if (inForeground) {
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_SHOW_BUTTON -> {
                showButton()
                // Started with startForegroundService: go foreground whatever
                // happens next, or the system kills the app for it — and never
                // with a type that drops mediaProjection from a capture in
                // progress (the button can be switched on mid-recording).
                if (!inForeground) { if (isCapturing()) enterCaptureForeground() else enterForeground(idleType()) }
                refreshForeground()
            }
            ACTION_HIDE_BUTTON -> {
                hideButton()
                refreshForeground()
            }
            ACTION_PAUSE -> pauseCapture()
            ACTION_RESUME -> resumeCapture()
            ACTION_STOP -> stopCapture()
            ACTION_MARK -> if (recorder?.mark() == true) toast(getString(R.string.pro_capture_marker_saved))
            else -> refreshForeground()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        button?.onConfigurationChanged()
    }

    override fun onDestroy() {
        destroyed = true
        countdownJob?.cancel()
        storageJob?.cancel()
        // A setup still running is torn down when it finishes (onPrepared).
        startGeneration++
        preparing = false
        val rec = recorder
        val out = output
        val proj = projection
        recorder = null
        output = null
        projection = null
        when {
            // Joining the encoder threads takes up to seconds: never on the
            // main thread. The save carries on in the app's scope.
            rec != null -> {
                stopping = true
                RecorderState.setStatus(CaptureStatus.Saving)
                finishRecording(rec, out, proj, reason = null)
            }
            else -> {
                proj?.let { releaseProjection(it) }
                // A save already under way sets Idle itself when it is done.
                if (!stopping) RecorderState.setStatus(CaptureStatus.Idle)
            }
        }
        button?.dismiss()
        button = null
        RecorderState.setButtonShown(false)
        scope.cancel()
        super.onDestroy()
    }

    // ── Capture ────────────────────────────────────────────────────────

    private fun startCapture(intent: Intent) {
        if (RecorderState.status.value.isActive || projection != null || preparing || stopping) {
            refreshForeground()
            return
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (data == null) {
            enterForeground(idleType())
            refreshForeground()
            return
        }
        // mediaProjection type first: getMediaProjection() refuses without it.
        if (!enterCaptureForeground()) {
            // Still honour startForegroundService's contract before stopping.
            enterForeground(idleType())
            fail(getString(R.string.error_capture_not_allowed))
            refreshForeground()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = runCatching { mpm.getMediaProjection(resultCode, data) }
            .onFailure { Log.w(TAG, "projection rejected", it) }
            .getOrNull()
        if (proj == null) {
            fail(getString(R.string.error_capture_not_allowed))
            refreshForeground()
            return
        }
        projection = proj
        // Android 14+ requires the callback before any capture starts.
        proj.registerCallback(projectionCallback, main)
        stopping = false

        countdownJob = scope.launch {
            val secs = settings.current.countdownSeconds
            for (s in secs downTo 1) {
                RecorderState.setStatus(CaptureStatus.Countdown(s))
                delay(1000)
            }
            if (settings.current.hideButtonWhileRecording && button != null) {
                preRollHidden = true
                applyButtonVisibility()
                // Let SurfaceFlinger compose one frame without it before the
                // first frame is captured.
                delay(150)
            }
            beginRecording()
        }
    }

    /** Sets the recording up on a background thread; [onPrepared] takes it from there on main. */
    private fun beginRecording() {
        val proj = projection ?: return
        val s = settings.current
        val audioPermitted = s.audioSource.needsRecordPermission &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val generation = startGeneration
        preparing = true
        RecorderApp.instance.appScope.launch {
            val prepared = withContext(Dispatchers.IO) { prepareRecording(proj, s, audioPermitted) }
            onPrepared(generation, prepared)
        }
    }

    private sealed interface Prepared {
        class Started(val recorder: ScreenRecorder, val output: MediaStoreFile, val sessionMissing: Boolean) : Prepared
        class Failed(val message: String) : Prepared
    }

    /** Off the main thread: everything from the encoder queries to the VirtualDisplay. */
    private fun prepareRecording(proj: MediaProjection, s: RecorderSettings, audioPermitted: Boolean): Prepared {
        val resolver = applicationContext.contentResolver
        if (StorageGuard.isLow(allocatableBytes())) return Prepared.Failed(getString(R.string.error_storage_low_start))
        val spec = captureSpec(s)
        val out = MediaStoreFile.create(resolver, MediaStoreFile.Kind.VIDEO, "Recording")
            ?: return Prepared.Failed(getString(R.string.error_no_storage))
        val session = if (s.editReady) runCatching {
            EditReadySession.open(applicationContext, out.uri.toString())
        }.onFailure { Log.w(TAG, "edit-ready storage unavailable", it) }.getOrNull() else null
        val rec = ScreenRecorder(proj, spec, session)
        rec.onFatalError = { e -> main.post { onRecorderFailed(rec, e) } }
        return try {
            rec.start(out.pfd.fileDescriptor, audioPermitted)
            Prepared.Started(rec, out, sessionMissing = s.editReady && session == null)
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            runCatching { rec.stop() }
            rec.finishSession(false)
            out.discard(resolver)
            Prepared.Failed(getString(R.string.error_recording_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun onPrepared(generation: Int, prepared: Prepared) {
        val current = generation == startGeneration && !destroyed
        if (current) preparing = false
        when (prepared) {
            is Prepared.Failed -> if (current) {
                fail(prepared.message)
                stopCapture()
            }
            is Prepared.Started -> {
                val rec = prepared.recorder
                if (!current || projection == null) {
                    // Cancelled (or the projection ended) while it was being set up.
                    val resolver = applicationContext.contentResolver
                    RecorderApp.instance.appScope.launch(Dispatchers.IO) {
                        rec.stop()
                        rec.finishSession(false)
                        prepared.output.discard(resolver)
                    }
                    return
                }
                recorder = rec
                output = prepared.output
                preRollHidden = false
                RecorderState.setStatus(CaptureStatus.Recording(startedAtMs = SystemClock.elapsedRealtime()))
                if (prepared.sessionMissing) toast(getString(R.string.pro_capture_session_unavailable))
                val wanted = settings.current.audioSource
                if (wanted != AudioSource.NONE && rec.effectiveAudio != wanted) {
                    toast(getString(R.string.toast_audio_degraded))
                }
                watchStorage(rec)
                // A worker that failed before the recorder was installed here.
                if (rec.hasFailed) onRecorderFailed(rec, IllegalStateException("capture failed while starting"))
            }
        }
    }

    private fun onRecorderFailed(rec: ScreenRecorder, e: Throwable) {
        if (recorder !== rec) return
        fail(getString(R.string.error_recording_failed, e.message ?: e.javaClass.simpleName))
        stopCapture()
    }

    /**
     * A full disk mid-recording leaves the muxer unable to finish the file,
     * and the whole recording is lost. Stopping while there is still room
     * keeps it.
     */
    private fun watchStorage(rec: ScreenRecorder) {
        storageJob?.cancel()
        storageJob = scope.launch {
            while (isActive) {
                delay(StorageGuard.CHECK_MS)
                val free = withContext(Dispatchers.IO) { allocatableBytes() }
                if (recorder === rec && StorageGuard.isLow(free)) {
                    Log.w(TAG, "storage almost full ($free bytes); stopping")
                    stopCapture(reason = getString(R.string.error_storage_low))
                    return@launch
                }
            }
        }
    }

    /** Bytes that can still be written to shared storage, or null if it cannot be told. */
    private fun allocatableBytes(): Long? = runCatching {
        val storage = getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(getExternalFilesDir(null) ?: filesDir))
    }.onFailure { Log.w(TAG, "free space unknown", it) }.getOrNull()

    private fun captureSpec(s: RecorderSettings): CaptureSpec {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display.mode
        val (w, h) = CapturePlanner.orientedSize(mode.physicalWidth, mode.physicalHeight, display.rotation)
        val maxHz = CapturePlanner.maxRefreshHz(
            display.supportedModes.map { Triple(it.physicalWidth, it.physicalHeight, it.refreshRate) },
            mode.physicalWidth,
            mode.physicalHeight,
            mode.refreshRate,
        )
        val plan = CapturePlanner.plan(w, h, maxHz, s.resolution, s.frameRate, s.quality)
        return CaptureSpec(EncoderFit.fit(plan, s.quality), resources.configuration.densityDpi, s.audioSource)
    }

    private fun pauseCapture() {
        val rec = recorder ?: return
        val st = RecorderState.status.value as? CaptureStatus.Recording ?: return
        rec.pause()
        RecorderState.setStatus(st.paused(SystemClock.elapsedRealtime()))
    }

    private fun resumeCapture() {
        val rec = recorder ?: return
        val st = RecorderState.status.value as? CaptureStatus.Recording ?: return
        rec.resume()
        RecorderState.setStatus(st.resumed(SystemClock.elapsedRealtime()))
    }

    /** [reason], when given, is why the recording was stopped for the user (shown once it is saved). */
    private fun stopCapture(reason: String? = null) {
        if (stopping || destroyed) return
        countdownJob?.cancel()
        countdownJob = null
        storageJob?.cancel()
        storageJob = null
        val rec = recorder
        if (rec == null) {
            // Nothing recorded yet (cancelled countdown, a failed start, or
            // one still being set up — onPrepared tears that down).
            startGeneration++
            preparing = false
            preRollHidden = false
            projection?.let { releaseProjection(it) }
            projection = null
            RecorderState.setStatus(CaptureStatus.Idle)
            // Setting Idle when it already was emits nothing: show the button
            // here, or a start that failed during the pre-roll leaves it hidden.
            applyButtonVisibility()
            refreshForeground()
            return
        }
        stopping = true
        recorder = null
        val out = output
        output = null
        val proj = projection
        projection = null
        RecorderState.setStatus(CaptureStatus.Saving)
        finishRecording(rec, out, proj, reason)
    }

    private sealed interface Finished {
        class Saved(val recording: SavedRecording) : Finished
        data object PublishFailed : Finished
        data object NothingRecorded : Finished
    }

    /**
     * Stops, publishes (or discards) and lets the projection go, off the main
     * thread and in the app's scope, so a save started here completes even if
     * the service is destroyed meanwhile.
     */
    private fun finishRecording(rec: ScreenRecorder, out: MediaStoreFile?, proj: MediaProjection?, reason: String?) {
        val app = applicationContext
        val resolver = app.contentResolver
        RecorderApp.instance.appScope.launch {
            // Joins the encoder threads; seconds at worst, never on main.
            val finished = withContext(Dispatchers.IO) {
                val ok = rec.stop()
                val result = when {
                    out == null -> Finished.NothingRecorded
                    !ok -> { out.discard(resolver); Finished.NothingRecorded }
                    else -> out.publish(resolver)?.let { Finished.Saved(it.copy(audioKbps = rec.audioBitrate / 1000)) }
                        // Never leave the file pending (invisible, and deleted by the system a week later).
                        ?: run { out.discard(resolver); Finished.PublishFailed }
                }
                rec.finishSession(result is Finished.Saved)
                proj?.let { releaseProjection(it) }
                result
            }
            stopping = false
            preRollHidden = false
            RecorderState.setStatus(CaptureStatus.Idle)
            when (finished) {
                is Finished.Saved -> {
                    val saved = finished.recording
                    RecorderState.setLastSaved(saved)
                    postSavedNotification(app, saved)
                    if (reason != null) fail(reason) else toast(getString(R.string.toast_saved, saved.relativePath + saved.displayName))
                }
                Finished.PublishFailed -> fail(getString(R.string.error_publish_failed))
                Finished.NothingRecorded -> fail(getString(R.string.error_nothing_recorded))
            }
            if (!destroyed) {
                applyButtonVisibility()
                refreshForeground()
            }
        }
    }

    private fun releaseProjection(p: MediaProjection) {
        runCatching { p.unregisterCallback(projectionCallback) }
        runCatching { p.stop() }
    }

    private fun fail(message: String) {
        RecorderState.lastError.value = message
        toast(message)
    }

    // ── Floating button ────────────────────────────────────────────────

    private fun showButton() {
        if (!Settings.canDrawOverlays(this)) {
            RecorderState.setButtonShown(false)
            return
        }
        if (button == null) {
            // Hidden first, then shown: switched on mid-recording, it must
            // not appear in the video even for a frame.
            button = FloatingButton(this, buttonActions).also {
                it.setHidden(hideWanted())
                it.show()
            }
        }
        applyButtonVisibility()
        RecorderState.setButtonShown(true)
    }

    private fun hideWanted(
        status: CaptureStatus = RecorderState.status.value,
        s: RecorderSettings = settings.current,
    ): Boolean = ButtonVisibility.hidden(s.hideButtonWhileRecording, preRollHidden, status)

    private fun applyButtonVisibility() {
        button?.setHidden(hideWanted())
    }

    private fun hideButton() {
        button?.dismiss()
        button = null
        RecorderState.setButtonShown(false)
    }

    private val buttonActions = object : FloatingButton.Actions {
        override fun record() {
            startActivity(CaptureConsentActivity.intent(this@RecorderService).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        override fun pause() = pauseCapture()
        override fun resume() = resumeCapture()
        override fun stop() = stopCapture()
        override fun openApp() {
            startActivity(Intent(this@RecorderService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        override fun hide() {
            settings.update { it.copy(floatingButton = false) }
            hideButton()
            refreshForeground()
        }
    }

    // ── Foreground and notifications ───────────────────────────────────

    private fun projectionType(withMic: Boolean = wantsMic()): Int {
        var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (button != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        // Android 11+ gives a foreground service silence from the mic once the
        // user leaves the app, unless the service is of the microphone type.
        if (withMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return t
    }

    /** The recording will use the mic, and may (Android 14 refuses the type without the permission). */
    private fun wantsMic(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            RecorderApp.instance.settings.current.audioSource.usesMic &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Foreground with the capture types. Android 14 can refuse the microphone
     * type when the app is not in use (a recording started from the floating
     * button over another app); the recording then still starts, as it did
     * before the type was asked for, rather than failing.
     */
    private fun enterCaptureForeground(): Boolean =
        enterForeground(projectionType()) || (wantsMic() && enterForeground(projectionType(withMic = false)))

    /** Below 14 there is no special-use type, and a typeless foreground service is fine. */
    private fun idleType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

    private fun enterForeground(types: Int): Boolean = try {
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), types)
        inForeground = true
        true
    } catch (e: Exception) {
        Log.w(TAG, "startForeground($types) refused", e)
        false
    }

    private fun isCapturing() = projection != null || RecorderState.status.value.isActive

    /** Re-evaluates whether the service still has a reason to run, and with which type. */
    private fun refreshForeground() {
        when {
            isCapturing() -> enterCaptureForeground()
            button != null -> enterForeground(idleType())
            else -> {
                inForeground = false
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun buildNotification(): Notification {
        val b = NotificationCompat.Builder(this, RecorderApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
        when (val st = RecorderState.status.value) {
            CaptureStatus.Idle -> b
                .setContentTitle(getString(R.string.notif_button_title))
                .setContentText(getString(R.string.notif_button_text))
                .addAction(0, getString(R.string.action_record), consentIntent())
                .addAction(0, getString(R.string.action_hide_button), serviceIntent(ACTION_HIDE_BUTTON))
            is CaptureStatus.Countdown -> b
                .setContentTitle(getString(R.string.notif_countdown, st.secondsLeft))
                .addAction(0, getString(R.string.action_cancel), serviceIntent(ACTION_STOP))
            is CaptureStatus.Recording -> if (st.isPaused) {
                b.setContentTitle(getString(R.string.notif_paused))
                    .setContentText(formatDuration(st.elapsedMs(SystemClock.elapsedRealtime())))
                    .addAction(0, getString(R.string.action_resume), serviceIntent(ACTION_RESUME))
                    .addAction(0, getString(R.string.action_stop), serviceIntent(ACTION_STOP))
            } else {
                b.setContentTitle(getString(R.string.notif_recording))
                    .setUsesChronometer(true)
                    .setShowWhen(true)
                    .setWhen(System.currentTimeMillis() - st.elapsedMs(SystemClock.elapsedRealtime()))
                    .addAction(0, getString(R.string.action_pause), serviceIntent(ACTION_PAUSE))
                    .addAction(0, getString(R.string.action_stop), serviceIntent(ACTION_STOP))
                if (settings.current.editReady) {
                    b.addAction(0, getString(R.string.pro_capture_mark), serviceIntent(ACTION_MARK))
                }
            }
            CaptureStatus.Saving -> b
                .setContentTitle(getString(R.string.notif_saving))
                .setProgress(0, 0, true)
        }
        return b.build()
    }

    private fun postSavedNotification(context: Context, saved: SavedRecording) {
        val uri = saved.uri.toUri()
        val edit = PendingIntent.getActivity(
            context, 1,
            MainActivity.editIntent(context, uri),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val send = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(saved.displayName, uri)
        val share = PendingIntent.getActivity(
            context, 2,
            Intent.createChooser(send, context.getString(R.string.action_share)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, RecorderApp.CHANNEL_SAVED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_saved_title))
            .setContentText(
                if (saved.audioKbps > 0) getString(R.string.notif_saved_text, saved.displayName, saved.audioKbps)
                else saved.displayName,
            )
            .setAutoCancel(true)
            .setContentIntent(edit)
            .addAction(0, getString(R.string.action_edit), edit)
            .addAction(0, getString(R.string.action_share), share)
            .build()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            context.getSystemService(NotificationManager::class.java).notify(NOTIF_SAVED_ID, n)
        }
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun consentIntent() = PendingIntent.getActivity(
        this, 3,
        CaptureConsentActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun serviceIntent(action: String) = PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, RecorderService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun toast(message: String) {
        main.post { Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val TAG = "RecorderService"
        private const val NOTIF_ID = 0x7EC0
        private const val NOTIF_SAVED_ID = 0x7EC1
        private const val ACTION_START = "tf.monochrome.recorder.START"
        private const val ACTION_MARK = "tf.monochrome.recorder.MARK"
        private const val ACTION_STOP = "tf.monochrome.recorder.STOP"
        private const val ACTION_PAUSE = "tf.monochrome.recorder.PAUSE"
        private const val ACTION_RESUME = "tf.monochrome.recorder.RESUME"
        private const val ACTION_SHOW_BUTTON = "tf.monochrome.recorder.SHOW_BUTTON"
        private const val ACTION_HIDE_BUTTON = "tf.monochrome.recorder.HIDE_BUTTON"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"

        fun start(context: Context, resultCode: Int, data: Intent) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecorderService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, data),
            )
        }

        fun showButton(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RecorderService::class.java).setAction(ACTION_SHOW_BUTTON))
        }

        fun hideButton(context: Context) = control(context, ACTION_HIDE_BUTTON)
        fun pause(context: Context) = control(context, ACTION_PAUSE)
        fun resume(context: Context) = control(context, ACTION_RESUME)
        fun stop(context: Context) = control(context, ACTION_STOP)
        fun mark(context: Context) = control(context, ACTION_MARK)

        /** Controls only ever reach a service that is already running in the foreground. */
        private fun control(context: Context, action: String) {
            runCatching { context.startService(Intent(context, RecorderService::class.java).setAction(action)) }
                .onFailure { Log.w(TAG, "control $action not delivered", it) }
        }
    }
}
