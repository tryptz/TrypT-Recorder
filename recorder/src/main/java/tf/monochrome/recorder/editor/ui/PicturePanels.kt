package tf.monochrome.recorder.editor.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tf.monochrome.recorder.R
import tf.monochrome.recorder.editor.model.ClipGrade
import tf.monochrome.recorder.editor.model.MotionEasing
import tf.monochrome.recorder.editor.model.MotionKey
import tf.monochrome.recorder.editor.model.OverlayClip
import tf.monochrome.recorder.editor.model.OVERLAY_LAYERS
import tf.monochrome.recorder.editor.model.setOverlayLayer
import tf.monochrome.recorder.editor.model.TimelineRate
import tf.monochrome.recorder.editor.model.PictureTransform
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.model.VideoClip
import tf.monochrome.recorder.editor.model.clipStartUs
import tf.monochrome.recorder.editor.model.durationUs
import tf.monochrome.recorder.editor.model.sourceAt
import tf.monochrome.recorder.editor.model.toggleMarker
import tf.monochrome.recorder.editor.model.trimOverlay
import tf.monochrome.recorder.editor.model.updateClipPicture
import tf.monochrome.recorder.editor.model.updateOverlay
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ClipMotionPanel(vm: EditorViewModel, project: Project, clip: VideoClip, playheadUs: Long) {
    val start = project.clipStartUs(clip.id)
    if (playheadUs !in start until start + clip.durationUs) {
        TextButton(onClick = { vm.scrubTo(start) }) { Text(stringResource(R.string.editor_go_to_clip)) }
        return
    }
    val time = clip.sourceAt(playheadUs - start)
    val picture = clip.picture
    val key = picture.keys.minByOrNull { abs(it.sourceUs - time) }?.takeIf { abs(it.sourceUs - time) <= 4_000 }
    val current = key?.transform ?: picture.at(time)

    fun adjust(change: (PictureTransform) -> PictureTransform) {
        vm.preview { draft ->
            draft.updateClipPicture(clip.id) { state ->
                if (state.keys.isEmpty()) state.copy(transform = change(state.transform))
                else {
                    val target = key?.sourceUs ?: time
                    state.put(MotionKey(target, change(state.at(target)), key?.easing ?: MotionEasing.SMOOTH))
                }
            }
        }
    }

    Row(Modifier.horizontalScroll(rememberScrollState())) {
        TextButton(onClick = {
            vm.edit { draft ->
                draft.updateClipPicture(clip.id) { state ->
                    if (key == null) state.put(MotionKey(time, current))
                    else state.copy(keys = state.keys.filterNot { it.sourceUs == key.sourceUs }, transform = current)
                }
            }
        }) { Text(stringResource(if (key == null) R.string.editor_add_key else R.string.editor_remove_key)) }
        TextButton(onClick = {
            vm.edit { draft -> draft.updateClipPicture(clip.id) { it.copy(transform = PictureTransform(), keys = emptyList()) } }
        }) { Text(stringResource(R.string.editor_reset_motion)) }
    }
    if (picture.keys.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            picture.keys.filter { it.sourceUs in clip.trimStartUs until clip.trimEndUs }.forEach { point ->
                val offset = clip.timing.timelineAt(point.sourceUs)
                FilterChip(point.sourceUs == key?.sourceUs, { vm.scrubTo(start + offset) }, { Text(timeLabel(offset)) })
            }
        }
    }
    if (key != null) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MotionEasing.entries.forEach { easing ->
                val label = when (easing) {
                    MotionEasing.LINEAR -> R.string.editor_easing_linear
                    MotionEasing.SMOOTH -> R.string.editor_easing_smooth
                    MotionEasing.HOLD -> R.string.editor_easing_hold
                }
                FilterChip(
                    selected = key.easing == easing,
                    onClick = { vm.edit { draft -> draft.updateClipPicture(clip.id) { it.put(key.copy(easing = easing)) } } },
                    label = { Text(stringResource(label)) },
                )
            }
        }
    }
    PictureSlider(R.string.editor_zoom, current.zoom, 0.5f..4f, vm::commit) { adjust { p -> p.copy(zoom = it) } }
    PictureSlider(R.string.editor_horizontal, current.x, -1f..1f, vm::commit) { adjust { p -> p.copy(x = it) } }
    PictureSlider(R.string.editor_vertical, current.y, -1f..1f, vm::commit) { adjust { p -> p.copy(y = it) } }
    PictureSlider(R.string.editor_rotation, current.rotation, -180f..180f, vm::commit, degrees = true) {
        adjust { p -> p.copy(rotation = it) }
    }
}

@Composable
fun ClipGradePanel(vm: EditorViewModel, clip: VideoClip, addLut: () -> Unit) {
    val grade = clip.picture.grade
    fun adjust(change: (ClipGrade) -> ClipGrade) {
        vm.preview { p -> p.updateClipPicture(clip.id) { it.copy(grade = change(it.grade)) } }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(!grade.bypass, {
            vm.edit { p -> p.updateClipPicture(clip.id) { it.copy(grade = it.grade.copy(bypass = !it.grade.bypass)) } }
        }, { Text(stringResource(R.string.pro_grade_enabled)) })
        TextButton(onClick = addLut) { Text(stringResource(R.string.creative_lut_library)) }
    }
    grade.lut?.let { lut ->
        Text(stringResource(R.string.pro_lut_label, lut.name))
        PictureSlider(R.string.pro_lut_mix, grade.lutMix, 0f..1f, vm::commit) { adjust { g -> g.copy(lutMix = it) } }
        TextButton(onClick = { vm.edit { p -> p.updateClipPicture(clip.id) { it.copy(grade = it.grade.copy(lut = null)) } } }) {
            Text(stringResource(R.string.pro_remove_lut))
        }
    }
    Text(stringResource(R.string.pro_exposure_value, grade.exposure))
    Slider(grade.exposure, { value -> adjust { it.copy(exposure = value) } },
        valueRange = -2f..2f, onValueChangeFinished = vm::commit)
    PictureSlider(R.string.pro_tint, grade.tint, -1f..1f, vm::commit) { adjust { g -> g.copy(tint = it) } }
    PictureSlider(R.string.editor_brightness, grade.brightness, -1f..1f, vm::commit) { adjust { g -> g.copy(brightness = it) } }
    PictureSlider(R.string.editor_contrast, grade.contrast, -1f..1f, vm::commit) { adjust { g -> g.copy(contrast = it) } }
    PictureSlider(R.string.editor_saturation, grade.saturation, -1f..1f, vm::commit) { adjust { g -> g.copy(saturation = it) } }
    PictureSlider(R.string.editor_temperature, grade.temperature, -1f..1f, vm::commit) { adjust { g -> g.copy(temperature = it) } }
    TextButton(onClick = { vm.edit { p -> p.updateClipPicture(clip.id) { it.copy(grade = ClipGrade()) } } }) {
        Text(stringResource(R.string.editor_reset_grade))
    }
}

@Composable
fun OverlayPanel(vm: EditorViewModel, clip: OverlayClip) {
    fun adjust(change: (OverlayClip) -> OverlayClip) {
        vm.preview { it.updateOverlay(clip.id, change) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (layer in 0 until OVERLAY_LAYERS) {
            FilterChip(clip.layer == layer, {
                val current = vm.project
                val edited = current?.setOverlayLayer(clip.id, layer)
                vm.applyPrecision(edited?.takeIf { it != current || layer == clip.layer })
            }, { Text(stringResource(R.string.pro_overlay_layer, layer + 2)) })
        }
    }
    Text(stringResource(R.string.editor_overlay_trim, timeLabel(clip.trimStartUs), timeLabel(clip.trimEndUs)))
    RangeSlider(
        value = clip.trimStartUs.toFloat()..clip.trimEndUs.toFloat(),
        onValueChange = { range -> vm.preview { it.trimOverlay(clip.id, range.start.toLong(), range.endInclusive.toLong()) } },
        onValueChangeFinished = vm::commit,
        valueRange = 0f..clip.source.durationUs.toFloat(),
    )
    PictureSlider(R.string.editor_scale, clip.scale, 0.1f..1f, vm::commit) { adjust { c -> c.copy(scale = it) } }
    PictureSlider(R.string.editor_horizontal, clip.x, -1f..1f, vm::commit) { adjust { c -> c.copy(x = it) } }
    PictureSlider(R.string.editor_vertical, clip.y, -1f..1f, vm::commit) { adjust { c -> c.copy(y = it) } }
    PictureSlider(R.string.editor_opacity, clip.opacity, 0f..1f, vm::commit) { adjust { c -> c.copy(opacity = it) } }
}

@Composable
fun MarkerPanel(vm: EditorViewModel, project: Project, playheadUs: Long) {
    Text(stringResource(R.string.pro_timeline_fps))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TimelineRate.entries.forEach { rate ->
            FilterChip(project.frameRate == rate, { vm.edit { it.copy(frameRate = rate) } }, { Text(rate.label) })
        }
    }
    val markers = project.markers.filter { it.timeUs <= project.durationUs }
    val exists = markers.any { abs(it.timeUs - playheadUs) <= 2_000 }
    TextButton(onClick = { vm.edit { it.toggleMarker(playheadUs) } }) {
        Text(stringResource(if (exists) R.string.editor_remove_marker else R.string.editor_add_marker))
    }
    if (markers.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            markers.forEach { marker ->
                FilterChip(
                    selected = abs(marker.timeUs - playheadUs) <= 2_000,
                    onClick = { vm.scrubTo(marker.timeUs) },
                    label = { Text(timeLabel(marker.timeUs)) },
                )
            }
        }
    }
}

private fun timeLabel(timeUs: Long): String = "%.3f s".format(timeUs / 1_000_000.0)

@Composable
private fun PictureSlider(
    @StringRes label: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onDone: () -> Unit,
    degrees: Boolean = false,
    onChange: (Float) -> Unit,
) {
    val display = if (degrees) "${value.roundToInt()}°" else "${(value * 100).roundToInt()}%"
    Row(Modifier.fillMaxWidth()) {
        Text(stringResource(label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Text(display, style = MaterialTheme.typography.labelMedium)
    }
    Slider(value, onChange, valueRange = range, onValueChangeFinished = onDone)
}
