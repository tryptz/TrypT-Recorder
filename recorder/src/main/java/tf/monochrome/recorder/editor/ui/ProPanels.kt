package tf.monochrome.recorder.editor.ui

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import tf.monochrome.recorder.R
import tf.monochrome.recorder.editor.model.AudioRole
import tf.monochrome.recorder.editor.model.CropRect
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.model.SPEEDS
import tf.monochrome.recorder.editor.model.SpeedKey
import tf.monochrome.recorder.editor.model.VideoClip
import tf.monochrome.recorder.editor.model.clipStartUs
import tf.monochrome.recorder.editor.model.rippleDelete
import tf.monochrome.recorder.editor.model.rollCut
import tf.monochrome.recorder.editor.model.setSpeedRamp
import tf.monochrome.recorder.editor.model.slipClip
import tf.monochrome.recorder.editor.model.sourceAt
import tf.monochrome.recorder.editor.model.updateAngles
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun RampPanel(vm: EditorViewModel, project: Project, clip: VideoClip, playheadUs: Long) {
    val start = project.clipStartUs(clip.id)
    val inClip = playheadUs in start until start + clip.durationUs
    val sourceUs = clip.sourceAt(playheadUs - start)
    val nearest = clip.speedKeys.minByOrNull { abs(it.sourceUs - sourceUs) }
        ?.takeIf { abs(it.sourceUs - sourceUs) <= 5_000 }
    Text(stringResource(R.string.pro_ramp_help))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(R.string.pro_ramp_up to (0.5f to 2f), R.string.pro_ramp_down to (2f to 0.5f)).forEach { (label, rates) ->
            OutlinedButton(onClick = {
                vm.edit { it.setSpeedRamp(clip.id, listOf(
                    SpeedKey(clip.trimStartUs, rates.first), SpeedKey(clip.trimEndUs, rates.second),
                )) }
            }) { Text(stringResource(label)) }
        }
        if (clip.speedKeys.isNotEmpty()) {
            TextButton(onClick = { vm.edit { it.setSpeedRamp(clip.id, emptyList()) } }) {
                Text(stringResource(R.string.pro_ramp_clear))
            }
        }
    }
    if (!inClip) {
        TextButton(onClick = { vm.scrubTo(start) }) { Text(stringResource(R.string.editor_go_to_clip)) }
        return
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SPEEDS.forEach { rate ->
            FilterChip(
                selected = nearest?.rate == rate,
                onClick = {
                    val time = nearest?.sourceUs ?: sourceUs
                    val retained = clip.speedKeys.filterNot { it.sourceUs == time }.ifEmpty {
                        if (time > clip.trimStartUs) listOf(SpeedKey(clip.trimStartUs, clip.speed)) else emptyList()
                    }
                    vm.edit { it.setSpeedRamp(clip.id, retained + SpeedKey(time, rate)) }
                    vm.scrubTo(start + (vm.project?.clips?.firstOrNull { it.id == clip.id }?.timing?.timelineAt(time) ?: 0))
                },
                label = { Text("${rate.toString().removeSuffix(".0")}×") },
            )
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        clip.speedKeys.filter { it.sourceUs in clip.trimStartUs..clip.trimEndUs }.forEach { key ->
            FilterChip(
                selected = key == nearest,
                onClick = { vm.scrubTo((start + clip.timing.timelineAt(key.sourceUs)).coerceAtMost(start + clip.durationUs - 1)) },
                label = { Text(stringResource(R.string.pro_ramp_key, key.sourceUs / 1_000_000.0, key.rate)) },
            )
        }
    }
    if (nearest != null) {
        TextButton(onClick = { vm.edit { it.setSpeedRamp(clip.id, clip.speedKeys - nearest) } }) {
            Text(stringResource(R.string.editor_remove_key))
        }
    }
}

@Composable
fun PrecisionPanel(vm: EditorViewModel, project: Project, clip: VideoClip) {
    Text(stringResource(R.string.pro_precision_help))
    EditNudgeRow(R.string.pro_slip) { delta ->
        vm.applyPrecision(project.slipClip(clip.id, delta))
    }
    EditNudgeRow(R.string.pro_roll) { delta ->
        vm.applyPrecision(project.rollCut(clip.id, delta))
    }
    TextButton(onClick = { vm.edit { it.rippleDelete(clip.id) } }) {
        Text(stringResource(R.string.pro_ripple_delete))
    }
}

@Composable
private fun EditNudgeRow(@StringRes label: Int, edit: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label), Modifier.weight(1f))
        OutlinedButton(onClick = { edit(-100_000) }) { Text(stringResource(R.string.pro_minus_nudge)) }
        OutlinedButton(onClick = { edit(100_000) }) { Text(stringResource(R.string.pro_plus_nudge)) }
    }
}

@Composable
fun AnglesPanel(vm: EditorViewModel, clip: VideoClip, addAngle: () -> Unit) {
    Text(stringResource(R.string.pro_angle_help))
    OutlinedButton(onClick = addAngle) { Text(stringResource(R.string.pro_import_angle)) }
    TextButton(onClick = { vm.switchAngle(clip.id, null) }) { Text(stringResource(R.string.pro_cut_original)) }
    clip.angles.forEach { angle ->
        var offset by remember(angle.id) { mutableStateOf((angle.offsetUs / 1_000).toString()) }
        Text(angle.source.displayName, style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = offset,
            onValueChange = { offset = it },
            label = { Text(stringResource(R.string.pro_angle_offset)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val parsed = offset.trim().toLongOrNull()?.takeIf { it in -86_400_000L..86_400_000L }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                enabled = parsed != null,
                onClick = {
                    val aligned = angle.copy(offsetUs = requireNotNull(parsed) * 1000)
                    vm.edit { project ->
                        project.updateAngles(clip.id) { current ->
                            if (angle.id == current.angleId && !aligned.covers(current.trimStartUs, current.trimEndUs)) current
                            else current.copy(angles = current.angles.map { if (it.id == angle.id) aligned else it })
                        }
                    }
                },
            ) { Text(stringResource(R.string.pro_apply_offset)) }
            OutlinedButton(onClick = { vm.switchAngle(clip.id, angle.id) }) {
                Text(stringResource(R.string.pro_cut_angle))
            }
        }
    }
}

@StringRes
internal fun roleLabel(role: AudioRole): Int = when (role) {
    AudioRole.DIALOGUE -> R.string.pro_dialogue
    AudioRole.DEVICE -> R.string.pro_device
    AudioRole.MUSIC -> R.string.pro_music
    AudioRole.SFX -> R.string.pro_sfx
}

@Composable
fun RoleSelector(role: AudioRole, setRole: (AudioRole) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AudioRole.entries.forEach { item ->
            FilterChip(role == item, { setRole(item) }, { Text(stringResource(roleLabel(item))) })
        }
    }
}

@Composable
fun TrackingDialog(vm: EditorViewModel, clip: VideoClip, sourceUs: Long, close: () -> Unit) {
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var box by remember { mutableStateOf(clip.picture.trackedCover?.at(sourceUs)
        ?.takeUnless { it == CropRect.FULL } ?: CropRect(0.25f, 0.25f, 0.75f, 0.55f)) }
    LaunchedEffect(clip.id, sourceUs) {
        try {
            frame = vm.trackingFrame(clip, sourceUs)
            failed = frame == null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.pro_track_region)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.pro_track_help))
                val bitmap = frame
                if (bitmap != null) {
                    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val ratio = bitmap.width.toFloat() / bitmap.height
                        val width = minOf(maxWidth, 330.dp * ratio)
                        Box(Modifier.width(width).aspectRatio(ratio)) {
                            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize())
                            CropOverlay(box, null, { box = it }, {}, Modifier.fillMaxSize())
                        }
                    }
                } else if (failed) {
                    Text(stringResource(R.string.pro_track_failed))
                } else {
                    CircularProgressIndicator()
                }
            }
        },
        confirmButton = {
            TextButton(enabled = frame != null, onClick = {
                vm.trackCover(clip, sourceUs, box)
                close()
            }) { Text(stringResource(R.string.pro_run_tracking)) }
        },
        dismissButton = {
            if (clip.picture.trackedCover != null) {
                TextButton(enabled = frame != null, onClick = {
                    vm.correctCover(clip.id, sourceUs, box)
                    close()
                }) { Text(stringResource(R.string.pro_track_correct)) }
            } else {
                TextButton(onClick = close) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
