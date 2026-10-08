package tf.monochrome.recorder.ui

import androidx.core.net.toUri
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import tf.monochrome.recorder.R
import tf.monochrome.recorder.RecorderApp
import tf.monochrome.recorder.capture.CaptureConsentActivity
import tf.monochrome.recorder.capture.CaptureStatus
import tf.monochrome.recorder.capture.RecorderService
import tf.monochrome.recorder.capture.RecorderState
import tf.monochrome.recorder.capture.formatDuration
import tf.monochrome.recorder.settings.AudioSource

@Composable
fun RecordScreen(onEdit: (Uri) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = RecorderApp.instance.settings
    val settings by store.settings.collectAsStateWithLifecycle()
    val status by RecorderState.status.collectAsStateWithLifecycle()
    val lastSaved by RecorderState.lastSaved.collectAsStateWithLifecycle()
    val error by RecorderState.lastError.collectAsStateWithLifecycle()

    // The foreground-service notification is the stop button of last resort;
    // ask once for the right to show it.
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedNotifications &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotifications = true
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(context)) {
            store.update { it.copy(floatingButton = true) }
            RecorderService.showButton(context)
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        BigRecordButton(
            status = status,
            onStart = { context.startActivity(CaptureConsentActivity.intent(context)) },
            onStop = { RecorderService.stop(context) },
        )
        Spacer(Modifier.height(16.dp))
        StatusLine(status)
        val st = status
        if (st is CaptureStatus.Recording) {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { if (st.isPaused) RecorderService.resume(context) else RecorderService.pause(context) }) {
                    Icon(if (st.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null)
                    Text(stringResource(if (st.isPaused) R.string.action_resume else R.string.action_pause), Modifier.padding(start = 6.dp))
                }
                FilledTonalButton(onClick = { RecorderService.stop(context) }) {
                    Icon(Icons.Filled.Stop, null)
                    Text(stringResource(R.string.action_stop), Modifier.padding(start = 6.dp))
                }
            }
        }

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .clickable { RecorderState.lastError.value = null },
            )
        }

        Spacer(Modifier.height(28.dp))
        Card {
            Text(stringResource(R.string.settings_audio_source), style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                AudioSource.entries.forEachIndexed { i, a ->
                    SegmentedButton(
                        selected = settings.audioSource == a,
                        onClick = { store.update { it.copy(audioSource = a) } },
                        shape = SegmentedButtonDefaults.itemShape(i, AudioSource.entries.size),
                        enabled = !status.isActive,
                    ) { Text(stringResource(audioShortLabel(a)), maxLines = 1) }
                }
            }
            Text(
                stringResource(audioHint(settings.audioSource)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                stringResource(
                    R.string.record_video_summary,
                    stringResource(resolutionLabel(settings.resolution)),
                    settings.frameRate,
                    stringResource(qualityLabel(settings.quality)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        Card(padding = 0.dp) {
            SwitchRow(
                title = stringResource(R.string.floating_button),
                subtitle = stringResource(R.string.floating_button_hint),
                checked = settings.floatingButton,
                onChange = { on ->
                    if (!on) {
                        store.update { it.copy(floatingButton = false) }
                        RecorderService.hideButton(context)
                    } else if (Settings.canDrawOverlays(context)) {
                        store.update { it.copy(floatingButton = true) }
                        RecorderService.showButton(context)
                    } else {
                        overlayLauncher.launch(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()),
                        )
                    }
                },
            )
        }

        lastSaved?.let { saved ->
            Spacer(Modifier.height(12.dp))
            Card {
                Text(stringResource(R.string.last_recording), style = MaterialTheme.typography.titleSmall)
                Text(saved.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MediaSummaryLine(saved.uri.toUri(), Modifier.padding(top = 2.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val uri = saved.uri.toUri()
                    OutlinedButton(onClick = { onEdit(uri) }) {
                        Icon(Icons.Filled.Edit, null)
                        Text(stringResource(R.string.action_edit), Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(onClick = { play(context, uri) }) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Text(stringResource(R.string.action_play), Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(onClick = { share(context, uri, "video/mp4") }) {
                        Icon(Icons.Filled.Share, null)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Card(padding: androidx.compose.ui.unit.Dp = 16.dp, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(padding),
    ) { content() }
}

@Composable
private fun BigRecordButton(status: CaptureStatus, onStart: () -> Unit, onStop: () -> Unit) {
    val active = status.isActive
    Box(
        Modifier
            .size(168.dp)
            .clip(CircleShape)
            .border(4.dp, if (active) RecordRed else Color.White, CircleShape)
            .clickable(
                onClickLabel = stringResource(if (active) R.string.action_stop else R.string.action_record),
                onClick = { if (active) onStop() else onStart() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            CaptureStatus.Idle -> Box(Modifier.size(96.dp).clip(CircleShape).background(RecordRed))
            is CaptureStatus.Countdown -> Text(status.secondsLeft.toString(), fontSize = 64.sp, fontWeight = FontWeight.Bold)
            is CaptureStatus.Recording -> Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(RecordRed))
            CaptureStatus.Saving -> CircularProgressIndicator(Modifier.size(56.dp), color = Color.White)
        }
    }
}

@Composable
private fun StatusLine(status: CaptureStatus) {
    when (status) {
        CaptureStatus.Idle -> Text(stringResource(R.string.record_tap_to_start), color = MaterialTheme.colorScheme.onSurfaceVariant)
        is CaptureStatus.Countdown -> Text(stringResource(R.string.notif_countdown, status.secondsLeft))
        is CaptureStatus.Recording -> {
            var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
            LaunchedEffect(status) {
                while (true) {
                    now = SystemClock.elapsedRealtime()
                    delay(250)
                }
            }
            Text(
                (if (status.isPaused) stringResource(R.string.notif_paused) + " · " else "") + formatDuration(status.elapsedMs(now)),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        CaptureStatus.Saving -> Text(stringResource(R.string.notif_saving))
    }
}

fun audioShortLabel(a: AudioSource) = when (a) {
    AudioSource.NONE -> R.string.audio_none
    AudioSource.DEVICE -> R.string.audio_device
    AudioSource.MICROPHONE -> R.string.audio_mic
    AudioSource.DEVICE_AND_MIC -> R.string.audio_both
}

private fun audioHint(a: AudioSource) = when (a) {
    AudioSource.NONE -> R.string.audio_none_hint
    AudioSource.DEVICE -> R.string.audio_device_hint
    AudioSource.MICROPHONE -> R.string.audio_mic_hint
    AudioSource.DEVICE_AND_MIC -> R.string.audio_both_hint
}
