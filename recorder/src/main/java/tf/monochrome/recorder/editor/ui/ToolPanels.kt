package tf.monochrome.recorder.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tf.monochrome.recorder.R
import tf.monochrome.recorder.capture.formatDuration
import tf.monochrome.recorder.editor.export.ExportMode
import tf.monochrome.recorder.editor.export.ExportQuality
import tf.monochrome.recorder.editor.export.ExportResolution
import tf.monochrome.recorder.editor.export.ExportSettings
import tf.monochrome.recorder.editor.model.AspectPreset
import tf.monochrome.recorder.editor.model.AudioClip
import tf.monochrome.recorder.editor.model.CropInsets
import tf.monochrome.recorder.editor.model.Filter
import tf.monochrome.recorder.editor.model.MAX_VOLUME
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.model.SPEEDS
import tf.monochrome.recorder.editor.model.TextItem
import tf.monochrome.recorder.editor.model.TextPosition
import tf.monochrome.recorder.editor.model.TextSize
import tf.monochrome.recorder.editor.model.VideoClip
import tf.monochrome.recorder.editor.model.durationUs
import tf.monochrome.recorder.editor.model.moveAudio
import tf.monochrome.recorder.editor.model.setAudioFades
import tf.monochrome.recorder.editor.model.setAudioVolume
import tf.monochrome.recorder.editor.model.setClipSpeed
import tf.monochrome.recorder.editor.model.setClipVolume
import tf.monochrome.recorder.editor.model.trimAudio
import tf.monochrome.recorder.editor.model.trimClip
import tf.monochrome.recorder.editor.model.trimEndToPlayhead
import tf.monochrome.recorder.editor.model.trimStartToPlayhead
import tf.monochrome.recorder.editor.model.updateText

/** The panel a tool opens below the timeline. */
enum class Tool {
    NONE, TRIM, SPEED, VOLUME, AUDIO_FADES, AUDIO_POSITION, TEXT, CANVAS, FILTERS, ADJUST, VIDEO_FADES, EXPORT;

    /** Whether this tool still makes sense for [selection]. */
    fun fits(selection: Selection): Boolean = when (this) {
        TRIM, VOLUME -> selection is Selection.Clip || selection is Selection.Audio
        SPEED -> selection is Selection.Clip
        AUDIO_FADES, AUDIO_POSITION -> selection is Selection.Audio
        TEXT -> selection is Selection.Text
        else -> true
    }
}

private fun secs(us: Long) = "%.1f s".format(us / 1_000_000f)

@Composable
private fun Labeled(label: String, value: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

// ── Clip and audio ─────────────────────────────────────────────────────

@Composable
fun ClipTrimPanel(vm: EditorViewModel, clip: VideoClip, playheadUs: Long) {
    Labeled(stringResource(R.string.tool_trim), "${formatDuration(clip.trimStartUs / 1000)} – ${formatDuration(clip.trimEndUs / 1000)}") {
        RangeSlider(
            value = clip.trimStartUs.toFloat()..clip.trimEndUs.toFloat(),
            onValueChange = { r -> vm.preview { it.trimClip(clip.id, r.start.toLong(), r.endInclusive.toLong()) } },
            onValueChangeFinished = vm::commit,
            valueRange = 0f..clip.source.durationUs.toFloat(),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.edit { it.trimStartToPlayhead(clip.id, playheadUs) } }) { Text(stringResource(R.string.trim_start_here)) }
        OutlinedButton(onClick = { vm.edit { it.trimEndToPlayhead(clip.id, playheadUs) } }) { Text(stringResource(R.string.trim_end_here)) }
    }
}

@Composable
fun AudioTrimPanel(vm: EditorViewModel, clip: AudioClip) {
    Labeled(stringResource(R.string.tool_trim), "${formatDuration(clip.trimStartUs / 1000)} – ${formatDuration(clip.trimEndUs / 1000)}") {
        RangeSlider(
            value = clip.trimStartUs.toFloat()..clip.trimEndUs.toFloat(),
            onValueChange = { r -> vm.preview { it.trimAudio(clip.id, r.start.toLong(), r.endInclusive.toLong()) } },
            onValueChangeFinished = vm::commit,
            valueRange = 0f..clip.source.durationUs.toFloat(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeedPanel(vm: EditorViewModel, clip: VideoClip) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SPEEDS.forEach { s ->
            FilterChip(
                selected = clip.speed == s,
                onClick = { vm.edit { it.setClipSpeed(clip.id, s) } },
                label = { Text("${s.toString().removeSuffix(".0")}×") },
            )
        }
    }
    Text(
        stringResource(R.string.speed_hint, formatDuration(clip.durationUs / 1000)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun VolumePanel(volume: Float, onChange: (Float) -> Unit, onDone: () -> Unit, onToggleMute: () -> Unit) {
    Labeled(stringResource(R.string.tool_volume), "${(volume * 100).toInt()}%") {
        Slider(value = volume, onValueChange = onChange, onValueChangeFinished = onDone, valueRange = 0f..MAX_VOLUME)
    }
    OutlinedButton(onClick = onToggleMute) {
        Text(stringResource(if (volume == 0f) R.string.action_unmute else R.string.action_mute))
    }
}

@Composable
fun ClipVolumePanel(vm: EditorViewModel, clip: VideoClip) = VolumePanel(
    volume = clip.volume,
    onChange = { v -> vm.preview { it.setClipVolume(clip.id, v) } },
    onDone = vm::commit,
    onToggleMute = { vm.edit { it.setClipVolume(clip.id, if (clip.volume == 0f) 1f else 0f) } },
)

@Composable
fun AudioVolumePanel(vm: EditorViewModel, clip: AudioClip) = VolumePanel(
    volume = clip.volume,
    onChange = { v -> vm.preview { it.setAudioVolume(clip.id, v) } },
    onDone = vm::commit,
    onToggleMute = { vm.edit { it.setAudioVolume(clip.id, if (clip.volume == 0f) 1f else 0f) } },
)

@Composable
fun AudioFadesPanel(vm: EditorViewModel, clip: AudioClip) {
    val max = minOf(5_000_000L, clip.durationUs / 2).toFloat().coerceAtLeast(1f)
    Labeled(stringResource(R.string.fade_in), secs(clip.fadeInUs)) {
        Slider(
            value = clip.fadeInUs.toFloat().coerceAtMost(max),
            onValueChange = { v -> vm.preview { it.setAudioFades(clip.id, v.toLong(), clip.fadeOutUs) } },
            onValueChangeFinished = vm::commit,
            valueRange = 0f..max,
        )
    }
    Labeled(stringResource(R.string.fade_out), secs(clip.fadeOutUs)) {
        Slider(
            value = clip.fadeOutUs.toFloat().coerceAtMost(max),
            onValueChange = { v -> vm.preview { it.setAudioFades(clip.id, clip.fadeInUs, v.toLong()) } },
            onValueChangeFinished = vm::commit,
            valueRange = 0f..max,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioPositionPanel(vm: EditorViewModel, clip: AudioClip, playheadUs: Long) {
    Text(stringResource(R.string.audio_starts_at, "%.2f s".format(clip.startUs / 1_000_000f)))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(-1_000_000L, -100_000L, 100_000L, 1_000_000L).forEach { d ->
            OutlinedButton(onClick = { vm.edit { it.moveAudio(clip.id, clip.startUs + d) } }) {
                Text((if (d > 0) "+" else "−") + "%.1f s".format(kotlin.math.abs(d) / 1_000_000f))
            }
        }
        Button(onClick = { vm.edit { it.moveAudio(clip.id, playheadUs) } }) { Text(stringResource(R.string.move_to_playhead)) }
    }
    Text(stringResource(R.string.audio_drag_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ── Text ───────────────────────────────────────────────────────────────

private val TextColors = listOf(0xFFFFFFFF, 0xFF000000, 0xFFFFD60A, 0xFFE5383B, 0xFF4CC9F0, 0xFF80ED99)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextPanel(vm: EditorViewModel, item: TextItem, playheadUs: Long) {
    OutlinedTextField(
        value = item.text,
        onValueChange = { v -> vm.preview { it.updateText(item.id) { t -> t.copy(text = v) } } },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.text_label)) },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextPosition.entries.forEach { pos ->
            FilterChip(
                selected = item.position == pos,
                onClick = { vm.edit { it.updateText(item.id) { t -> t.copy(position = pos) } } },
                label = { Text(stringResource(textPositionLabel(pos))) },
            )
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextSize.entries.forEach { size ->
            FilterChip(
                selected = item.size == size,
                onClick = { vm.edit { it.updateText(item.id) { t -> t.copy(size = size) } } },
                label = { Text(stringResource(textSizeLabel(size))) },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        TextColors.forEach { c ->
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(if (item.color == c) 3.dp else 1.dp, if (item.color == c) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                    .clickable { vm.edit { it.updateText(item.id) { t -> t.copy(color = c) } } },
            )
        }
        Text(stringResource(R.string.text_background), Modifier.padding(start = 8.dp))
        Switch(checked = item.background, onCheckedChange = { b -> vm.edit { it.updateText(item.id) { t -> t.copy(background = b) } } })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { vm.edit { it.updateText(item.id) { t -> t.copy(startUs = playheadUs, endUs = maxOf(t.endUs, playheadUs + 500_000)) } } }) {
            Text(stringResource(R.string.text_start_here))
        }
        OutlinedButton(onClick = { vm.edit { it.updateText(item.id) { t -> t.copy(endUs = playheadUs) } } }) {
            Text(stringResource(R.string.text_end_here))
        }
        Text("${formatDuration(item.startUs / 1000)} – ${formatDuration(item.endUs / 1000)}", style = MaterialTheme.typography.bodySmall)
    }
}

private fun textPositionLabel(p: TextPosition) = when (p) {
    TextPosition.TOP -> R.string.pos_top
    TextPosition.CENTER -> R.string.pos_center
    TextPosition.BOTTOM -> R.string.pos_bottom
}

private fun textSizeLabel(s: TextSize) = when (s) {
    TextSize.SMALL -> R.string.size_small
    TextSize.MEDIUM -> R.string.size_medium
    TextSize.LARGE -> R.string.size_large
    TextSize.HUGE -> R.string.size_huge
}

// ── Whole video ────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CanvasPanel(vm: EditorViewModel, project: Project) {
    val c = project.canvas
    Text(stringResource(R.string.canvas_shape), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AspectPreset.entries.forEach { a ->
            FilterChip(
                selected = c.aspect == a,
                onClick = { vm.edit { it.copy(canvas = it.canvas.copy(aspect = a)) } },
                label = { Text(aspectLabel(a)) },
            )
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(selected = !c.fill, onClick = { vm.edit { it.copy(canvas = it.canvas.copy(fill = false)) } }, label = { Text(stringResource(R.string.canvas_fit)) })
        FilterChip(selected = c.fill, onClick = { vm.edit { it.copy(canvas = it.canvas.copy(fill = true)) } }, label = { Text(stringResource(R.string.canvas_fill)) })
        OutlinedButton(onClick = { vm.edit { it.copy(canvas = it.canvas.copy(rotation = (it.canvas.rotation + 270) % 360)) } }) { Text(stringResource(R.string.rotate_left)) }
        OutlinedButton(onClick = { vm.edit { it.copy(canvas = it.canvas.copy(rotation = (it.canvas.rotation + 90) % 360)) } }) { Text(stringResource(R.string.rotate_right)) }
        FilterChip(selected = c.flipHorizontal, onClick = { vm.edit { it.copy(canvas = it.canvas.copy(flipHorizontal = !c.flipHorizontal)) } }, label = { Text(stringResource(R.string.flip_horizontal)) })
        FilterChip(selected = c.flipVertical, onClick = { vm.edit { it.copy(canvas = it.canvas.copy(flipVertical = !c.flipVertical)) } }, label = { Text(stringResource(R.string.flip_vertical)) })
    }
    Text(stringResource(R.string.crop), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
    CropSlider(stringResource(R.string.crop_left), c.crop.left, vm::commit) { v -> vm.preview { it.copy(canvas = it.canvas.copy(crop = it.canvas.crop.copy(left = v))) } }
    CropSlider(stringResource(R.string.crop_right), c.crop.right, vm::commit) { v -> vm.preview { it.copy(canvas = it.canvas.copy(crop = it.canvas.crop.copy(right = v))) } }
    CropSlider(stringResource(R.string.crop_top), c.crop.top, vm::commit) { v -> vm.preview { it.copy(canvas = it.canvas.copy(crop = it.canvas.crop.copy(top = v))) } }
    CropSlider(stringResource(R.string.crop_bottom), c.crop.bottom, vm::commit) { v -> vm.preview { it.copy(canvas = it.canvas.copy(crop = it.canvas.crop.copy(bottom = v))) } }
    if (!c.crop.isNone) {
        TextButton(onClick = { vm.edit { it.copy(canvas = it.canvas.copy(crop = CropInsets())) } }) { Text(stringResource(R.string.reset_crop)) }
    }
}

@Composable
private fun CropSlider(label: String, value: Float, onDone: () -> Unit, onChange: (Float) -> Unit) {
    Labeled(label, "${(value * 100).toInt()}%") {
        Slider(value = value, onValueChange = onChange, onValueChangeFinished = onDone, valueRange = 0f..CropInsets.MAX)
    }
}

@Composable
private fun aspectLabel(a: AspectPreset) = when (a) {
    AspectPreset.ORIGINAL -> stringResource(R.string.aspect_original)
    AspectPreset.WIDE -> "16:9"
    AspectPreset.TALL -> "9:16"
    AspectPreset.SQUARE -> "1:1"
    AspectPreset.PORTRAIT -> "4:5"
    AspectPreset.CLASSIC -> "4:3"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FiltersPanel(vm: EditorViewModel, project: Project) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Filter.entries.forEach { f ->
            FilterChip(
                selected = project.look.filter == f,
                onClick = { vm.edit { it.copy(look = it.look.copy(filter = f)) } },
                label = { Text(stringResource(filterLabel(f))) },
            )
        }
    }
}

private fun filterLabel(f: Filter) = when (f) {
    Filter.NONE -> R.string.filter_none
    Filter.MONO -> R.string.filter_mono
    Filter.SEPIA -> R.string.filter_sepia
    Filter.VIVID -> R.string.filter_vivid
    Filter.COOL -> R.string.filter_cool
    Filter.WARM -> R.string.filter_warm
    Filter.INVERT -> R.string.filter_invert
}

@Composable
fun AdjustPanel(vm: EditorViewModel, project: Project) {
    val l = project.look
    AdjustSlider(stringResource(R.string.adjust_brightness), l.brightness, vm::commit) { v -> vm.preview { it.copy(look = it.look.copy(brightness = v)) } }
    AdjustSlider(stringResource(R.string.adjust_contrast), l.contrast, vm::commit) { v -> vm.preview { it.copy(look = it.look.copy(contrast = v)) } }
    AdjustSlider(stringResource(R.string.adjust_saturation), l.saturation, vm::commit) { v -> vm.preview { it.copy(look = it.look.copy(saturation = v)) } }
    TextButton(onClick = { vm.edit { it.copy(look = it.look.copy(brightness = 0f, contrast = 0f, saturation = 0f)) } }) {
        Text(stringResource(R.string.reset))
    }
}

@Composable
private fun AdjustSlider(label: String, value: Float, onDone: () -> Unit, onChange: (Float) -> Unit) {
    Labeled(label, "%+d".format((value * 100).toInt())) {
        Slider(value = value, onValueChange = onChange, onValueChangeFinished = onDone, valueRange = -1f..1f)
    }
}

@Composable
fun VideoFadesPanel(vm: EditorViewModel, project: Project) {
    val max = minOf(3_000_000L, project.durationUs / 2).toFloat().coerceAtLeast(1f)
    Labeled(stringResource(R.string.fade_in_black), secs(project.look.fadeInUs)) {
        Slider(
            value = project.look.fadeInUs.toFloat().coerceAtMost(max),
            onValueChange = { v -> vm.preview { it.copy(look = it.look.copy(fadeInUs = v.toLong())) } },
            onValueChangeFinished = vm::commit,
            valueRange = 0f..max,
        )
    }
    Labeled(stringResource(R.string.fade_out_black), secs(project.look.fadeOutUs)) {
        Slider(
            value = project.look.fadeOutUs.toFloat().coerceAtMost(max),
            onValueChange = { v -> vm.preview { it.copy(look = it.look.copy(fadeOutUs = v.toLong())) } },
            onValueChangeFinished = vm::commit,
            valueRange = 0f..max,
        )
    }
}

// ── Export ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ExportPanel(settings: ExportSettings, onChange: (ExportSettings) -> Unit, onExport: () -> Unit) {
    Text(stringResource(R.string.export_what), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExportMode.entries.forEach { m ->
            FilterChip(selected = settings.mode == m, onClick = { onChange(settings.copy(mode = m)) }, label = { Text(stringResource(exportModeLabel(m))) })
        }
    }
    Text(stringResource(exportModeHint(settings.mode)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (settings.mode != ExportMode.AUDIO_ONLY) {
        Text(stringResource(R.string.export_resolution), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExportResolution.entries.forEach { r ->
                FilterChip(
                    selected = settings.resolution == r,
                    onClick = { onChange(settings.copy(resolution = r)) },
                    label = { Text(r.shortSide?.let { "${it}p" } ?: stringResource(R.string.aspect_original)) },
                )
            }
        }
        Text(stringResource(R.string.export_quality), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExportQuality.entries.forEach { q ->
                FilterChip(selected = settings.quality == q, onClick = { onChange(settings.copy(quality = q)) }, label = { Text(stringResource(exportQualityLabel(q))) })
            }
        }
    }
    Button(onClick = onExport, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text(stringResource(R.string.action_export)) }
}

private fun exportModeLabel(m: ExportMode) = when (m) {
    ExportMode.VIDEO -> R.string.export_mode_video
    ExportMode.VIDEO_ONLY -> R.string.export_mode_video_only
    ExportMode.AUDIO_ONLY -> R.string.export_mode_audio_only
}

private fun exportModeHint(m: ExportMode) = when (m) {
    ExportMode.VIDEO -> R.string.export_mode_video_hint
    ExportMode.VIDEO_ONLY -> R.string.export_mode_video_only_hint
    ExportMode.AUDIO_ONLY -> R.string.export_mode_audio_only_hint
}

private fun exportQualityLabel(q: ExportQuality) = when (q) {
    ExportQuality.LOW -> R.string.quality_low
    ExportQuality.STANDARD -> R.string.quality_standard
    ExportQuality.HIGH -> R.string.quality_high
}
