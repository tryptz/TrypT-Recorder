package tf.monochrome.recorder.capture

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tf.monochrome.recorder.R

/** Quick Settings tile: record from the shade, tap again to stop. */
class RecordTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        watch?.cancel()
        watch = scope.launch { RecorderState.status.collect { render(it) } }
    }

    override fun onStopListening() {
        watch?.cancel()
        watch = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (RecorderState.status.value.isActive) {
            RecorderService.stop(this)
            return
        }
        val intent = CaptureConsentActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            launchBelow14(intent)
        }
    }

    /**
     * The Intent overload throws on Android 14+ — which is why it is only
     * reached below 14, where the PendingIntent overload does not exist yet.
     * Lint's check does not see the version guard at the call site.
     */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun launchBelow14(intent: Intent) = startActivityAndCollapse(intent)

    private fun render(status: CaptureStatus) {
        val tile = qsTile ?: return
        val active = status.isActive
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(if (active) R.string.tile_stop else R.string.tile_label)
        tile.subtitle = when (status) {
            is CaptureStatus.Countdown -> status.secondsLeft.toString()
            is CaptureStatus.Recording -> getString(if (status.isPaused) R.string.notif_paused else R.string.notif_recording)
            CaptureStatus.Saving -> getString(R.string.notif_saving)
            CaptureStatus.Idle -> null
        }
        tile.updateTile()
    }
}
