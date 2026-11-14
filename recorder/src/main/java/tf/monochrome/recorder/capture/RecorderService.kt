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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.recorder.MainActivity
import tf.monochrome.recorder.R
import tf.monochrome.recorder.RecorderApp
import tf.monochrome.recorder.media.MediaStoreFile
import tf.monochrome.recorder.settings.AudioSource

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
    private var stopping = false
    private var inForeground = false

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
                button?.setHidden(s.hideButtonWhileRecording && (status is CaptureStatus.Recording || status is CaptureStatus.Saving))
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
                if (!inForeground) enterForeground(if (isCapturing()) projectionType() else idleType())
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
        countdownJob?.cancel()
        recorder?.let { rec ->
            recorder = null
            val ok = rec.stop()
            val saved = output?.let { if (ok) it.publish(contentResolver) else { it.discard(contentResolver); null } }
            rec.finishSession(saved != null)
            output = null
        }
        releaseProjection()
        button?.dismiss()
        button = null
        RecorderState.setButtonShown(false)
        RecorderState.setStatus(CaptureStatus.Idle)
        scope.cancel()
        super.onDestroy()
    }

    // ── Capture ────────────────────────────────────────────────────────

    private fun startCapture(intent: Intent) {
        if (RecorderState.status.value.isActive) {
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
        if (!enterForeground(projectionType())) {
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
                button?.setHidden(true)
                // Let SurfaceFlinger compose one frame without it before the
                // first frame is captured.
                delay(150)
            }
            beginRecording()
        }
    }

    private fun beginRecording() {
        val proj = projection ?: return
        val s = settings.current
        val spec = captureSpec()
        val out = MediaStoreFile.create(contentResolver, MediaStoreFile.Kind.VIDEO, "Recording")
        if (out == null) {
            fail(getString(R.string.error_no_storage))
            stopCapture()
            return
        }
        val audioPermitted = s.audioSource.needsRecordPermission &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val session = if (s.editReady) runCatching {
            EditReadySession.open(applicationContext, out.uri.toString())
        }.onFailure { Log.w(TAG, "edit-ready storage unavailable", it) }.getOrNull() else null
        if (s.editReady && session == null) toast(getString(R.string.pro_capture_session_unavailable))
        val rec = ScreenRecorder(proj, spec, session)
        rec.onFatalError = { e ->
            main.post {
                fail(getString(R.string.error_recording_failed, e.message ?: e.javaClass.simpleName))
                stopCapture()
            }
        }
        try {
            rec.start(out.pfd.fileDescriptor, audioPermitted)
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            runCatching { rec.stop() }
            rec.finishSession(false)
            out.discard(contentResolver)
            fail(getString(R.string.error_recording_failed, e.message ?: e.javaClass.simpleName))
            stopCapture()
            return
        }
        recorder = rec
        output = out
        RecorderState.setStatus(CaptureStatus.Recording(startedAtMs = SystemClock.elapsedRealtime()))
        if (s.audioSource != AudioSource.NONE && rec.effectiveAudio != s.audioSource) {
            toast(getString(R.string.toast_audio_degraded))
        }
    }

    private fun captureSpec(): CaptureSpec {
        val s = settings.current
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

    private fun stopCapture() {
        if (stopping) return
        countdownJob?.cancel()
        countdownJob = null
        val rec = recorder
        if (rec == null) {
            // Nothing recorded yet (cancelled countdown, or a failed start).
            releaseProjection()
            RecorderState.setStatus(CaptureStatus.Idle)
            refreshForeground()
            return
        }
        stopping = true
        recorder = null
        val out = output
        output = null
        RecorderState.setStatus(CaptureStatus.Saving)
        scope.launch {
            // Joins the encoder threads; seconds at worst, never on main.
            val saved = withContext(Dispatchers.IO) {
                val ok = rec.stop()
                val result = when {
                    out == null -> null
                    ok -> out.publish(contentResolver)?.copy(audioKbps = rec.audioBitrate / 1000)
                    else -> { out.discard(contentResolver); null }
                }
                rec.finishSession(result != null)
                result
            }
            releaseProjection()
            stopping = false
            RecorderState.setStatus(CaptureStatus.Idle)
            if (saved != null) {
                RecorderState.setLastSaved(saved)
                postSavedNotification(saved)
                toast(getString(R.string.toast_saved, saved.relativePath + saved.displayName))
            } else {
                fail(getString(R.string.error_nothing_recorded))
            }
            refreshForeground()
        }
    }

    private fun releaseProjection() {
        projection?.let { p ->
            projection = null
            runCatching { p.unregisterCallback(projectionCallback) }
            runCatching { p.stop() }
        }
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
            button = FloatingButton(this, buttonActions).also { it.show() }
        }
        RecorderState.setButtonShown(true)
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

    private fun projectionType(): Int {
        var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (button != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        return t
    }

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
            isCapturing() -> enterForeground(projectionType())
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

    private fun postSavedNotification(saved: SavedRecording) {
        val uri = saved.uri.toUri()
        val edit = PendingIntent.getActivity(
            this, 1,
            MainActivity.editIntent(this, uri),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val send = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(saved.displayName, uri)
        val share = PendingIntent.getActivity(
            this, 2,
            Intent.createChooser(send, getString(R.string.action_share)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, RecorderApp.CHANNEL_SAVED)
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_SAVED_ID, n)
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
