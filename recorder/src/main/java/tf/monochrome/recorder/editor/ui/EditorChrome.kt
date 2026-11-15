package tf.monochrome.recorder.editor.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.recorder.R
import tf.monochrome.recorder.editor.export.ExportMode
import tf.monochrome.recorder.editor.export.ExportPlanner
import tf.monochrome.recorder.editor.export.ExportQuality
import tf.monochrome.recorder.editor.export.ExportResolution
import tf.monochrome.recorder.editor.export.ExportSettings
import tf.monochrome.recorder.editor.export.GifFrameRate
import tf.monochrome.recorder.editor.export.GifSize
import tf.monochrome.recorder.editor.export.PlanResult
import tf.monochrome.recorder.editor.model.AspectPreset
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.model.TimelineRate
import tf.monochrome.recorder.capture.formatDuration
import tf.monochrome.recorder.ui.glass.GlassButton
import tf.monochrome.recorder.ui.glass.GlassPanel
import tf.monochrome.recorder.ui.glass.GlassPill
import kotlin.math.roundToInt

/*
 * The editor's chrome: the bar across the top, the export sheet, the
 * transport, the tool row and the dock. Laid out after the reference the
 * owner gave (a phone gallery's video editor) in Material You colours, with
 * every floating surface the glass from Tryptify (ui/glass).
 */

// ── Top bar ────────────────────────────────────────────────────────────

/**
 * Cancel on the left, the export format in the middle (it opens the export
 * sheet), Save on the right (it exports with what the sheet shows).
 */
@Composable
internal fun EditorTopBar(
    format: String,
    sheetOpen: Boolean,
    canSave: Boolean,
    onCancel: () -> Unit,
    onFormat: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassButton(onClick = onCancel, modifier = Modifier.height(40.dp)) {
            Text(
                stringResource(R.string.action_cancel),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 20.dp),
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onFormat)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics { stateDescription = format },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val color = if (sheetOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Text(format, style = MaterialTheme.typography.titleMedium, color = color)
                Icon(if (sheetOpen) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown, stringResource(R.string.editor_export_settings), tint = color)
            }
        }
        Button(
            onClick = onSave,
            enabled = canSave,
            shape = RoundedCornerShape(50),
            contentPadding = ButtonDefaults.ContentPadding,
            modifier = Modifier.height(40.dp),
        ) { Text(stringResource(R.string.action_save), style = MaterialTheme.typography.titleMedium) }
    }
}

/** What the middle of the top bar shows: the format Save will write. */
@Composable
internal fun exportFormatLabel(settings: ExportSettings): String = when (settings.mode) {
    ExportMode.AUDIO_ONLY -> stringResource(R.string.export_format_audio)
    ExportMode.GIF -> stringResource(R.string.export_format_gif, settings.gifSize.shortSide)
    ExportMode.VIDEO, ExportMode.VIDEO_ONLY -> buildString {
        append(resolutionShort(settings.resolution))
        settings.frameRate?.let { append(" · ").append(it.label) }
    }
}

@Composable
private fun resolutionShort(r: ExportResolution) =
    r.shortSide?.let { "${it}p" } ?: stringResource(R.string.aspect_original)

// ── Export sheet ───────────────────────────────────────────────────────

/**
 * The export settings as a floating pane: what to export, then resolution
 * and frame rate as stepped sliders with what each step means, and the
 * estimated size of the file at the bottom. The rarer things — the queue,
 * the project bundle, delivery aspect, captions — fold away under More.
 */
@Composable
internal fun ExportSheet(
    project: Project,
    settings: ExportSettings,
    onChange: (ExportSettings) -> Unit,
    pendingExports: Int,
    bundleBusy: Boolean,
    saveBundle: () -> Unit,
    resumeExport: () -> Unit,
    discardExport: () -> Unit,
    queueBothAspects: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPanel(modifier = modifier, blockTouchesBelow = true) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .animateContentSize(),
        ) {
            Segmented(
                options = ExportMode.entries.map { stringResource(exportModeShort(it)) },
                selected = settings.mode.ordinal,
                onSelect = { onChange(settings.copy(mode = ExportMode.entries[it])) },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 20.dp, start = 16.dp, end = 16.dp),
            )
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (settings.mode) {
                    ExportMode.GIF -> GifSettings(settings, onChange)
                    ExportMode.AUDIO_ONLY -> Text(
                        stringResource(R.string.export_mode_audio_only_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    ExportMode.VIDEO, ExportMode.VIDEO_ONLY -> VideoSettings(settings, onChange)
                }
                MoreSection(
                    settings = settings,
                    onChange = onChange,
                    pendingExports = pendingExports,
                    bundleBusy = bundleBusy,
                    saveBundle = saveBundle,
                    resumeExport = resumeExport,
                    discardExport = discardExport,
                    queueBothAspects = queueBothAspects,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            Text(
                estimateLabel(project, settings),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp),
            )
        }
    }
}

@Composable
private fun VideoSettings(settings: ExportSettings, onChange: (ExportSettings) -> Unit) {
    // Smallest first, as a slider reads: more to the right is more.
    val resolutions = listOf(ExportResolution.P480, ExportResolution.P720, ExportResolution.P1080, ExportResolution.ORIGINAL)
    SettingHeader(stringResource(R.string.export_resolution), stringResource(resolutionHint(settings.resolution)))
    StepSlider(
        labels = resolutions.map { resolutionShort(it) },
        selected = resolutions.indexOf(settings.resolution),
        onSelect = { onChange(settings.copy(resolution = resolutions[it])) },
        description = stringResource(R.string.export_resolution),
    )
    Spacer(Modifier.height(12.dp))
    // Null is "Source": every recorded frame at its own time.
    val rates: List<TimelineRate?> = listOf(null) + TimelineRate.entries
    SettingHeader(stringResource(R.string.export_frame_rate), stringResource(frameRateHint(settings.frameRate)))
    StepSlider(
        labels = rates.map { it?.label ?: stringResource(R.string.export_frame_rate_source) },
        selected = rates.indexOf(settings.frameRate),
        onSelect = { onChange(settings.copy(frameRate = rates[it])) },
        description = stringResource(R.string.export_frame_rate),
    )
    if (settings.frameRate != null) {
        SwitchRow(
            title = stringResource(R.string.export_optical_flow),
            hint = stringResource(if (settings.opticalFlow) R.string.export_optical_flow_hint else R.string.export_frame_rate_repeat_hint),
            checked = settings.opticalFlow,
            onChange = { onChange(settings.copy(opticalFlow = it)) },
        )
    }
    Spacer(Modifier.height(12.dp))
    val qualities = ExportQuality.entries
    SettingHeader(stringResource(R.string.export_quality), stringResource(qualityHint(settings.quality)))
    StepSlider(
        labels = qualities.map { stringResource(exportQualityName(it)) },
        selected = qualities.indexOf(settings.quality),
        onSelect = { onChange(settings.copy(quality = qualities[it])) },
        description = stringResource(R.string.export_quality),
    )
}

@Composable
private fun GifSettings(settings: ExportSettings, onChange: (ExportSettings) -> Unit) {
    val sizes = GifSize.entries
    SettingHeader(stringResource(R.string.export_gif_size), "")
    StepSlider(
        labels = sizes.map { "${it.shortSide}p" },
        selected = sizes.indexOf(settings.gifSize),
        onSelect = { onChange(settings.copy(gifSize = sizes[it])) },
        description = stringResource(R.string.export_gif_size),
    )
    Spacer(Modifier.height(12.dp))
    val rates = GifFrameRate.entries
    SettingHeader(stringResource(R.string.export_gif_frame_rate), "")
    StepSlider(
        labels = rates.map { stringResource(R.string.fps, it.fps) },
        selected = rates.indexOf(settings.gifFrameRate),
        onSelect = { onChange(settings.copy(gifFrameRate = rates[it])) },
        description = stringResource(R.string.export_gif_frame_rate),
    )
    Text(
        stringResource(R.string.export_mode_gif_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun MoreSection(
    settings: ExportSettings,
    onChange: (ExportSettings) -> Unit,
    pendingExports: Int,
    bundleBusy: Boolean,
    saveBundle: () -> Unit,
    resumeExport: () -> Unit,
    discardExport: () -> Unit,
    queueBothAspects: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(pendingExports > 0) }
    TextButton(onClick = { open = !open }, modifier = Modifier.padding(top = 4.dp)) {
        Text(stringResource(R.string.editor_export_more))
        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
    }
    if (!open) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        if (pendingExports > 0) {
            Text(stringResource(R.string.pro_export_queue_count, pendingExports), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = resumeExport) { Text(stringResource(R.string.pro_export_resume)) }
                TextButton(onClick = discardExport) { Text(stringResource(R.string.pro_export_discard)) }
            }
        }
        if (settings.mode == ExportMode.VIDEO || settings.mode == ExportMode.VIDEO_ONLY) {
            Text(stringResource(R.string.pro_delivery_aspect), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(settings.aspectOverride == null, { onChange(settings.copy(aspectOverride = null)) },
                    { Text(stringResource(R.string.pro_project_aspect)) })
                listOf(AspectPreset.WIDE to "16:9", AspectPreset.TALL to "9:16", AspectPreset.SQUARE to "1:1").forEach { (aspect, label) ->
                    FilterChip(settings.aspectOverride == aspect, { onChange(settings.copy(aspectOverride = aspect)) }, { Text(label) })
                }
            }
            SwitchRow(
                title = stringResource(R.string.pro_burn_captions),
                hint = null,
                checked = settings.burnCaptions,
                onChange = { onChange(settings.copy(burnCaptions = it)) },
            )
            OutlinedButton(onClick = queueBothAspects, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pro_export_both))
            }
        }
        OutlinedButton(onClick = saveBundle, enabled = !bundleBusy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (bundleBusy) R.string.pro_bundle_saving else R.string.pro_bundle_save))
        }
        Text(
            stringResource(R.string.pro_bundle_originals),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Estimated file size: 259 MB", from the bitrates the export will ask for. */
@Composable
private fun estimateLabel(project: Project, settings: ExportSettings): String {
    val bytes = remember(project, settings) { estimatedBytes(project, settings) }
    return when {
        settings.mode == ExportMode.GIF -> stringResource(R.string.export_estimate_gif)
        bytes == null -> stringResource(R.string.export_no_clips)
        else -> stringResource(R.string.export_estimate, formatBytes(bytes))
    }
}

/**
 * The size an export comes out at, from the bitrates it asks the encoders
 * for over its length. An estimate: a still screen encodes far smaller than
 * its bitrate, and a GIF depends entirely on its pictures, so none is given.
 */
internal fun estimatedBytes(project: Project, settings: ExportSettings): Long? {
    val plan = (ExportPlanner.plan(project, settings) as? PlanResult.Ok)?.plan ?: return null
    val bitrate = plan.videoBitrate.toLong() + if (plan.hasAudio) plan.audioBitrate else 0
    return bitrate * plan.durationUs / 8_000_000L
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000L -> "${(bytes / 1e6).roundToInt()} MB"
    else -> "${(bytes / 1e3).roundToInt().coerceAtLeast(1)} KB"
}

@Composable
private fun SettingHeader(title: String, hint: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 12.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SwitchRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            hint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ── Stepped slider ─────────────────────────────────────────────────────

/**
 * A pill track with one dot per step, filled up to the chosen one, and the
 * steps' names under it: tap a step or drag along the track. Only steps can
 * be chosen, which is what these settings are.
 */
@Composable
internal fun StepSlider(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val count = labels.size.coerceAtLeast(1)
    val current by rememberUpdatedState(selected.coerceIn(0, count - 1))
    val select by rememberUpdatedState(onSelect)
    val fill = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val dotOn = MaterialTheme.colorScheme.onPrimary
    val dotOff = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .semantics {
                    contentDescription = description
                    stateDescription = labels.getOrElse(current) { "" }
                }
                .pointerInput(count) {
                    fun stepAt(x: Float): Int {
                        val r = size.height / 2f
                        val span = (size.width - 2 * r).coerceAtLeast(1f)
                        return (((x - r) / span) * (count - 1)).roundToInt().coerceIn(0, count - 1)
                    }
                    detectTapGestures { select(stepAt(it.x)) }
                }
                .pointerInput(count) {
                    fun stepAt(x: Float): Int {
                        val r = size.height / 2f
                        val span = (size.width - 2 * r).coerceAtLeast(1f)
                        return (((x - r) / span) * (count - 1)).roundToInt().coerceIn(0, count - 1)
                    }
                    detectHorizontalDragGestures { change, _ ->
                        val step = stepAt(change.position.x)
                        if (step != current) select(step)
                    }
                },
        ) {
            val r = size.height / 2f
            val span = size.width - 2 * r
            fun xOf(i: Int) = r + if (count > 1) span * i / (count - 1) else span / 2f
            drawRoundRect(track, cornerRadius = CornerRadius(r, r))
            drawRoundRect(fill, size = Size(xOf(current) + r, size.height), cornerRadius = CornerRadius(r, r))
            for (i in 0 until count) {
                if (i == current) continue
                drawCircle(if (i < current) dotOn else dotOff, radius = r * 0.22f, center = Offset(xOf(i), r))
            }
            drawCircle(Color.White, radius = r * 0.72f, center = Offset(xOf(current), r))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            labels.forEachIndexed { i, label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (i == current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (i == current) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    textAlign = when (i) {
                        0 -> TextAlign.Start
                        labels.lastIndex -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ── Segmented control ──────────────────────────────────────────────────

/** A glass pill holding a row of choices, the chosen one lit in the accent. */
@Composable
internal fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    GlassPill(modifier = modifier.height(44.dp)) {
        Row(Modifier.fillMaxSize().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent)
                        .clickable(role = Role.Tab) { onSelect(i) }
                        .semantics { this.selected = on }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ── Transport ──────────────────────────────────────────────────────────

/** Time on the left, play in the middle (with a frame step each side), undo and redo on the right. */
@Composable
internal fun Transport(
    playheadUs: Long,
    durationUs: Long,
    playing: Boolean,
    canPlay: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onPlay: () -> Unit,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            buildTime(playheadUs, durationUs),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp),
        )
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onStepBack, enabled = canPlay && playheadUs > 0) {
                Icon(Icons.Filled.SkipPrevious, stringResource(R.string.editor_step_back))
            }
            IconButton(onClick = onPlay, enabled = canPlay, modifier = Modifier.size(56.dp)) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    stringResource(if (playing) R.string.action_pause else R.string.action_play),
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(onClick = onStepForward, enabled = canPlay && playheadUs < durationUs) {
                Icon(Icons.Filled.SkipNext, stringResource(R.string.editor_step_forward))
            }
        }
        Row(Modifier.align(Alignment.CenterEnd)) {
            IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.action_undo)) }
            IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.action_redo)) }
        }
    }
}

private fun buildTime(playheadUs: Long, durationUs: Long) =
    "${formatDuration(playheadUs / 1000)} / ${formatDuration(durationUs / 1000)}"

// ── Tool row ───────────────────────────────────────────────────────────

/** One action in the row above the timeline. */
internal data class EditorAction(val icon: ImageVector, val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/** Icons with their names under them, scrolling sideways when they do not fit. */
@Composable
internal fun ToolRow(actions: List<EditorAction>, modifier: Modifier = Modifier) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(actions.size) { i ->
            val a = actions[i]
            val tint = if (a.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            Column(
                Modifier
                    .width(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = a.enabled, onClick = a.onClick)
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(a.icon, null, tint = tint, modifier = Modifier.size(26.dp))
                Text(
                    a.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = tint,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

// ── Dock ───────────────────────────────────────────────────────────────

/** The dock's tabs: each one picks which tools the row above the timeline shows. */
internal enum class DockTab(val icon: ImageVector, val label: Int) {
    EDIT(Icons.Filled.ContentCut, R.string.dock_edit),
    AUDIO(Icons.Filled.MusicNote, R.string.dock_audio),
    TEXT(Icons.Filled.TextFields, R.string.dock_text),
    ADJUST(Icons.Filled.Tune, R.string.dock_adjust),
    FILTER(Icons.Filled.AutoFixHigh, R.string.dock_filter),
    FRAME(Icons.Filled.AspectRatio, R.string.dock_frame),
}

/** The floating glass dock along the bottom, the chosen tab in the accent. */
@Composable
internal fun EditorDock(selected: DockTab?, onSelect: (DockTab) -> Unit, modifier: Modifier = Modifier) {
    GlassPill(modifier = modifier.height(76.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            DockTab.entries.forEach { tab ->
                val on = tab == selected
                val tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(role = Role.Tab) { onSelect(tab) }
                        .semantics { this.selected = on }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(tab.icon, null, tint = tint, modifier = Modifier.size(26.dp))
                    Text(stringResource(tab.label), style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
                }
            }
        }
    }
}

// ── Labels ─────────────────────────────────────────────────────────────

private fun exportModeShort(m: ExportMode) = when (m) {
    ExportMode.VIDEO -> R.string.export_mode_short_video
    ExportMode.VIDEO_ONLY -> R.string.export_mode_short_silent
    ExportMode.AUDIO_ONLY -> R.string.export_mode_short_audio
    ExportMode.GIF -> R.string.export_mode_gif
}

private fun resolutionHint(r: ExportResolution) = when (r) {
    ExportResolution.P480 -> R.string.export_resolution_hint_480
    ExportResolution.P720 -> R.string.export_resolution_hint_720
    ExportResolution.P1080 -> R.string.export_resolution_hint_1080
    ExportResolution.ORIGINAL -> R.string.export_resolution_hint_original
}

private fun frameRateHint(rate: TimelineRate?) = when {
    rate == null -> R.string.export_frame_rate_hint_source
    rate.numerator <= 30 * rate.denominator -> R.string.export_frame_rate_hint_film
    rate.numerator <= 60 * rate.denominator -> R.string.export_frame_rate_hint_smooth
    else -> R.string.export_frame_rate_hint_high
}

private fun qualityHint(q: ExportQuality) = when (q) {
    ExportQuality.LOW -> R.string.export_quality_hint_low
    ExportQuality.STANDARD -> R.string.export_quality_hint_standard
    ExportQuality.HIGH -> R.string.export_quality_hint_high
}

private fun exportQualityName(q: ExportQuality) = when (q) {
    ExportQuality.LOW -> R.string.quality_low
    ExportQuality.STANDARD -> R.string.quality_standard
    ExportQuality.HIGH -> R.string.quality_high
}
