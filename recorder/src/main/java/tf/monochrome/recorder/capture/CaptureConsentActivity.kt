package tf.monochrome.recorder.capture

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import tf.monochrome.recorder.R
import tf.monochrome.recorder.RecorderApp

/**
 * Invisible stop between "record" and the service: collects RECORD_AUDIO when
 * the chosen audio source needs it, then the system's screen-capture consent,
 * and hands the result to [RecorderService]. Every entry point — home screen,
 * floating button, tile, notification — comes through here, because consent
 * can only be asked for from an Activity.
 */
class CaptureConsentActivity : ComponentActivity() {
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            RecorderService.start(this, result.resultCode, data)
        }
        finish()
    }

    private val audioLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Declined: record anyway, without sound — better than nothing, and
        // the toast says why it is silent.
        if (!granted) Toast.makeText(this, R.string.toast_no_audio_permission, Toast.LENGTH_LONG).show()
        askForCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated mid-flow (rotation): the launchers above deliver the result.
        if (savedInstanceState != null) return
        if (RecorderState.status.value.isActive) {
            finish()
            return
        }
        val audio = RecorderApp.instance.settings.current.audioSource
        if (audio.needsRecordPermission &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            askForCapture()
        }
    }

    private fun askForCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    companion object {
        fun intent(context: Context) = Intent(context, CaptureConsentActivity::class.java)
    }
}
