package tf.monochrome.recorder.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where a recording is in its life. Times are SystemClock.elapsedRealtime() millis. */
sealed interface CaptureStatus {
    data object Idle : CaptureStatus
    data class Countdown(val secondsLeft: Int) : CaptureStatus
    data class Recording(
        val startedAtMs: Long,
        /** Paused time already over and done with. */
        val pausedTotalMs: Long = 0,
        /** When the current pause began, or null while running. */
        val pausedAtMs: Long? = null,
    ) : CaptureStatus {
        val isPaused: Boolean get() = pausedAtMs != null

        /** Recorded time — wall time since start minus every pause. */
        fun elapsedMs(nowMs: Long): Long {
            val end = pausedAtMs ?: nowMs
            return (end - startedAtMs - pausedTotalMs).coerceAtLeast(0)
        }

        fun paused(nowMs: Long) = if (isPaused) this else copy(pausedAtMs = nowMs)

        fun resumed(nowMs: Long) = pausedAtMs?.let { copy(pausedTotalMs = pausedTotalMs + (nowMs - it), pausedAtMs = null) } ?: this
    }
    data object Saving : CaptureStatus

    val isActive: Boolean get() = this !is Idle
}

/** A finished recording, as handed to the UI and the "saved" notification. */
data class SavedRecording(
    val uri: String,
    val displayName: String,
    val relativePath: String,
    /** AAC bitrate the recording's sound was encoded at, kbps; 0 for none (or not a recording). */
    val audioKbps: Int = 0,
)

/**
 * Process-wide recording state. The service is the only writer; the home
 * screen, the floating button and the Quick Settings tile observe it.
 */
object RecorderState {
    private val _status = MutableStateFlow<CaptureStatus>(CaptureStatus.Idle)
    val status: StateFlow<CaptureStatus> = _status.asStateFlow()

    private val _buttonShown = MutableStateFlow(false)
    /** Whether the floating button is on screen right now. */
    val buttonShown: StateFlow<Boolean> = _buttonShown.asStateFlow()

    private val _lastSaved = MutableStateFlow<SavedRecording?>(null)
    val lastSaved: StateFlow<SavedRecording?> = _lastSaved.asStateFlow()

    /** Last failure, for the home screen to show once. */
    val lastError = MutableStateFlow<String?>(null)

    internal fun setStatus(s: CaptureStatus) { _status.value = s }
    internal fun setButtonShown(shown: Boolean) { _buttonShown.value = shown }
    internal fun setLastSaved(saved: SavedRecording) { _lastSaved.value = saved }
}

/** Whether the floating button is taken off screen. Pure, so the rule is JVM-tested. */
object ButtonVisibility {
    /**
     * [startingCapture] covers the moment between the countdown and the first
     * frame, when the status may still be Idle (no countdown) or Countdown.
     * Any other state shows the button again — including a start that failed
     * or was cancelled in that moment.
     */
    fun hidden(hideWhileRecording: Boolean, startingCapture: Boolean, status: CaptureStatus): Boolean =
        hideWhileRecording && (startingCapture || status is CaptureStatus.Recording || status is CaptureStatus.Saving)
}

/** Free space below which a recording is not started, and one in progress is stopped and saved. */
object StorageGuard {
    const val MARGIN_BYTES = 200L * 1024 * 1024

    /** How often a recording checks, in milliseconds. */
    const val CHECK_MS = 4_000L

    fun isLow(allocatableBytes: Long?): Boolean = allocatableBytes != null && allocatableBytes < MARGIN_BYTES
}

/** m:ss, or h:mm:ss past the hour. */
fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
