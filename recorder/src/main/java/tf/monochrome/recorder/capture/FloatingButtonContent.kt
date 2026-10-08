package tf.monochrome.recorder.capture

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import tf.monochrome.recorder.R

private val RecordRed = Color(0xFFE5383B)
private val PauseAmber = Color(0xFFFFB703)
private val Glass = Color(0xE6101010)

/**
 * What the floating button shows. Idle: a record dot — tap records, long
 * press offers "open app" and "hide". Countdown: the seconds left. Recording:
 * the elapsed time — tap opens pause/resume and stop beside it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FloatingButtonContent(side: FloatingButton.Side, actions: FloatingButton.Actions) {
    val status by RecorderState.status.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    // Collapse whenever the phase changes, so a menu opened while idle does
    // not linger into the recording.
    LaunchedEffect(status::class) { expanded = false }

    val controls: @Composable () -> Unit = {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Glass)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when (val st = status) {
                    is CaptureStatus.Recording -> {
                        if (st.isPaused) {
                            SmallAction(Icons.Filled.PlayArrow, stringResource(R.string.action_resume)) { actions.resume() }
                        } else {
                            SmallAction(Icons.Filled.Pause, stringResource(R.string.action_pause)) { actions.pause() }
                        }
                        SmallAction(Icons.Filled.Stop, stringResource(R.string.action_stop), tint = RecordRed) {
                            expanded = false
                            actions.stop()
                        }
                    }
                    else -> {
                        SmallAction(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.action_open_app)) {
                            expanded = false
                            actions.openApp()
                        }
                        SmallAction(Icons.Filled.Close, stringResource(R.string.action_hide_button)) { actions.hide() }
                    }
                }
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (side == FloatingButton.Side.RIGHT) controls()
        Bubble(
            status = status,
            onTap = {
                when (status) {
                    CaptureStatus.Idle -> if (expanded) expanded = false else actions.record()
                    is CaptureStatus.Countdown -> actions.stop() // cancels
                    is CaptureStatus.Recording -> expanded = !expanded
                    CaptureStatus.Saving -> Unit
                }
            },
            onLongPress = { if (status == CaptureStatus.Idle) expanded = !expanded },
        )
        if (side == FloatingButton.Side.LEFT) controls()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(status: CaptureStatus, onTap: () -> Unit, onLongPress: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ringAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "ring",
    )
    val ringColor = when (status) {
        is CaptureStatus.Recording -> if (status.isPaused) PauseAmber else RecordRed
        else -> Color.White.copy(alpha = 0.6f)
    }
    val description = stringResource(
        when (status) {
            CaptureStatus.Idle -> R.string.action_record
            is CaptureStatus.Countdown -> R.string.action_cancel
            is CaptureStatus.Recording -> R.string.cd_recording_controls
            CaptureStatus.Saving -> R.string.notif_saving
        },
    )
    Box(
        modifier = Modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(Glass)
            .border(
                width = 2.dp,
                color = ringColor.copy(alpha = if (status is CaptureStatus.Recording && !status.isPaused) ringAlpha else 1f),
                shape = CircleShape,
            )
            .combinedClickable(onClickLabel = description, onClick = onTap, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            CaptureStatus.Idle -> Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(RecordRed),
            )
            is CaptureStatus.Countdown -> Text(
                text = status.secondsLeft.toString(),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            is CaptureStatus.Recording -> {
                var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
                LaunchedEffect(status) {
                    while (true) {
                        now = SystemClock.elapsedRealtime()
                        delay(250)
                    }
                }
                Text(
                    text = formatDuration(status.elapsedMs(now)),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.alpha(if (status.isPaused) ringAlpha else 1f),
                )
            }
            CaptureStatus.Saving -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .combinedClickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint)
    }
}
