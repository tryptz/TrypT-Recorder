package tf.monochrome.recorder.editor.ui

import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import tf.monochrome.recorder.R
import tf.monochrome.recorder.capture.formatDuration
import tf.monochrome.recorder.editor.export.ExportManager
import tf.monochrome.recorder.editor.export.ExportSettings
import tf.monochrome.recorder.editor.export.ExportState
import tf.monochrome.recorder.editor.export.PlanResult
import tf.monochrome.recorder.editor.model.AudioKind
import tf.monochrome.recorder.editor.model.CropBox
import tf.monochrome.recorder.editor.model.MediaRef
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.model.assignLanes
import tf.monochrome.recorder.editor.model.deleteAudio
import tf.monochrome.recorder.editor.model.deleteClip
import tf.monochrome.recorder.editor.model.deleteText
import tf.monochrome.recorder.editor.model.detachAudio
import tf.monochrome.recorder.editor.model.duplicateClip
import tf.monochrome.recorder.editor.model.durationUs
import tf.monochrome.recorder.editor.model.moveAudio
import tf.monochrome.recorder.editor.model.moveClip
import tf.monochrome.recorder.editor.model.restoreAudio
import tf.monochrome.recorder.editor.model.splitAt
import tf.monochrome.recorder.editor.model.splitAudioAt
import tf.monochrome.recorder.editor.model.updateText
import tf.monochrome.recorder.ui.ExportStatusDialog
import tf.monochrome.recorder.ui.MediaDetailsDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(projectId: String, onClose: () -> Unit) {
    val vm: EditorViewModel = viewModel(key = "editor-$projectId", factory = EditorViewModel.factory(projectId))
    val history by vm.history.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val playhead by vm.playheadUs.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val aspect by vm.aspect.collectAsStateWithLifecycle()
    val previewError by vm.previewError.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val exportState by ExportManager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var tool by rememberSaveable { mutableStateOf(Tool.NONE) }
    var zoom by rememberSaveable { mutableFloatStateOf(48f) }
    var exportSettings by remember { mutableStateOf(ExportSettings()) }
    var cropRatio by rememberSaveable { mutableStateOf(CropRatio.FREE) }
    var renaming by remember { mutableStateOf(false) }
    var detailsOf by remember { mutableStateOf<MediaRef?>(null) }

    val clipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { vm.addClips(uris) }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { vm.addAudio(uri) }
    }

    LaunchedEffect(selection) { if (!tool.fits(selection)) tool = Tool.NONE }
    LaunchedEffect(tool) { vm.setCropEditing(tool == Tool.CROP) }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.message.value = null
        }
    }
    // An export can take minutes; keep the screen (and so the app) alive for it.
    val view = LocalView.current
    val exporting = exportState is ExportState.Running
    DisposableEffect(exporting) {
        view.keepScreenOn = exporting
        onDispose { view.keepScreenOn = false }
    }

    BackHandler {
        if (tool != Tool.NONE) tool = Tool.NONE else onClose()
    }

    val h = history
    if (h == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val project = h.present

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
                title = {
                    Text(
                        project.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { renaming = true },
                    )
                },
                actions = {
                    IconButton(onClick = vm::undo, enabled = h.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.action_undo)) }
                    IconButton(onClick = vm::redo, enabled = h.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.action_redo)) }
                    Button(
                        onClick = { tool = Tool.EXPORT },
                        enabled = project.clips.isNotEmpty(),
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text(stringResource(R.string.action_export)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (project.clips.isEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.editor_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { clipPicker.launch(arrayOf("video/*")) }) { Text(stringResource(R.string.action_add_clip)) }
                    }
                } else {
                    Box(Modifier.aspectRatio(aspect.coerceIn(0.2f, 5f))) {
                        AndroidView(
                            factory = { ctx -> SurfaceView(ctx).also(vm::attachSurface) },
                            onRelease = vm::detachSurface,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (tool == Tool.CROP) {
                            val frame = shownFrame(project)
                            CropOverlay(
                                rect = CropBox.shown(project.canvas),
                                ratio = frame?.let { (w, h) -> cropRatio.normalized(w.toFloat() / h) },
                                onChange = { r -> vm.preview { it.copy(canvas = it.canvas.copy(crop = CropBox.insets(r, it.canvas))) } },
                                onDragEnd = vm::commit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    previewError?.let {
                        Text(
                            stringResource(R.string.editor_preview_error, it),
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .background(Color(0xAA000000))
                                .padding(8.dp),
                        )
                    }
                }
            }

            // Transport: play, time, zoom.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = vm::togglePlay, enabled = project.clips.isNotEmpty()) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(if (playing) R.string.action_pause else R.string.action_play))
                }
                Text(
                    "${formatDuration(playhead / 1000)} / ${formatDuration(project.durationUs / 1000)}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { zoom = (zoom / 1.6f).coerceAtLeast(6f) }) { Icon(Icons.Filled.ZoomOut, stringResource(R.string.action_zoom_out)) }
                IconButton(onClick = { zoom = (zoom * 1.6f).coerceAtMost(480f) }) { Icon(Icons.Filled.ZoomIn, stringResource(R.string.action_zoom_in)) }
            }

            val laneCount = remember(project.audio) { assignLanes(project.audio).size }
            val timelineHeight = 18 + 4 + 56 + (if (project.texts.isNotEmpty()) 30 else 0) + laneCount * 38 + 8
            Timeline(
                project = project,
                playheadUs = playhead,
                selection = selection,
                pixelsPerSecond = zoom.dp,
                onScrub = vm::scrubTo,
                onSelect = { vm.selection.value = it },
                onDragAudio = { id, start -> vm.preview { it.moveAudio(id, start) } },
                onDragText = { id, start ->
                    vm.preview { p -> p.updateText(id) { t -> t.copy(startUs = start, endUs = start + (t.endUs - t.startUs)) } }
                },
                onDragEnd = vm::commit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(timelineHeight.coerceAtMost(260).dp),
            )

            if (tool == Tool.NONE) {
                ActionRow(
                    actions = actionsFor(
                        project = project,
                        selection = selection,
                        playheadUs = playhead,
                        vm = vm,
                        openTool = { tool = it },
                        addClip = { clipPicker.launch(arrayOf("video/*")) },
                        addAudio = { audioPicker.launch(arrayOf("audio/*")) },
                        addTextLabel = stringResource(R.string.text_default),
                        showDetails = { detailsOf = it },
                    ),
                )
            } else {
                ToolSheet(title = stringResource(toolTitle(tool)), onDone = {
                    vm.commit()
                    tool = Tool.NONE
                }) {
                    ToolContent(
                        tool = tool,
                        vm = vm,
                        project = project,
                        selection = selection,
                        playheadUs = playhead,
                        exportSettings = exportSettings,
                        onExportSettings = { exportSettings = it },
                        cropRatio = cropRatio,
                        onCropRatio = { cropRatio = it },
                        onExport = {
                            when (vm.export(exportSettings)) {
                                null -> tool = Tool.NONE
                                PlanResult.Reason.NO_CLIPS -> Toast.makeText(context, R.string.export_no_clips, Toast.LENGTH_SHORT).show()
                                PlanResult.Reason.NO_AUDIO -> Toast.makeText(context, R.string.export_no_audio, Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
        }
    }

    ExportStatusDialog(
        state = exportState,
        onCancel = {
            ExportManager.cancel()
            vm.restorePreview()
        },
        onDismiss = {
            ExportManager.acknowledge()
            vm.restorePreview()
        },
    )

    detailsOf?.let { ref -> MediaDetailsDialog(ref.uri.toUri(), ref.displayName) { detailsOf = null } }

    if (renaming) {
        var name by remember { mutableStateOf(project.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.action_rename)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    vm.rename(name)
                    renaming = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private data class EditorAction(val icon: ImageVector, val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

@Composable
private fun actionsFor(
    project: Project,
    selection: Selection,
    playheadUs: Long,
    vm: EditorViewModel,
    openTool: (Tool) -> Unit,
    addClip: () -> Unit,
    addAudio: () -> Unit,
    addTextLabel: String,
    showDetails: (MediaRef) -> Unit,
): List<EditorAction> = when (selection) {
    Selection.None -> listOf(
        EditorAction(Icons.Filled.VideoLibrary, stringResource(R.string.action_add_clip), onClick = addClip),
        EditorAction(Icons.Filled.LibraryMusic, stringResource(R.string.action_add_audio), project.clips.isNotEmpty(), addAudio),
        EditorAction(Icons.Filled.Title, stringResource(R.string.action_add_text), project.clips.isNotEmpty()) { vm.addText(addTextLabel); openTool(Tool.TEXT) },
        EditorAction(Icons.Filled.ContentCut, stringResource(R.string.action_split), project.clips.isNotEmpty()) { vm.edit { it.splitAt(playheadUs) } },
        EditorAction(Icons.Filled.Crop, stringResource(R.string.crop), project.clips.isNotEmpty()) { openTool(Tool.CROP) },
        EditorAction(Icons.Filled.AspectRatio, stringResource(R.string.tool_canvas)) { openTool(Tool.CANVAS) },
        EditorAction(Icons.Filled.AutoFixHigh, stringResource(R.string.tool_filters)) { openTool(Tool.FILTERS) },
        EditorAction(Icons.Filled.Tune, stringResource(R.string.tool_adjust)) { openTool(Tool.ADJUST) },
        EditorAction(Icons.Filled.Grain, stringResource(R.string.tool_grain)) { openTool(Tool.GRAIN) },
        EditorAction(Icons.Filled.Gradient, stringResource(R.string.tool_fades)) { openTool(Tool.VIDEO_FADES) },
    )
    is Selection.Clip -> {
        val clip = project.clips.firstOrNull { it.id == selection.id }
        listOf(
            EditorAction(Icons.Filled.ContentCut, stringResource(R.string.action_split)) { vm.edit { it.splitAt(playheadUs) } },
            EditorAction(Icons.Filled.Edit, stringResource(R.string.tool_trim)) { openTool(Tool.TRIM) },
            EditorAction(Icons.Filled.Speed, stringResource(R.string.tool_speed)) { openTool(Tool.SPEED) },
            EditorAction(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.tool_volume), clip?.source?.hasAudio == true && clip.audioDetached.not()) { openTool(Tool.VOLUME) },
            if (clip?.audioDetached == true) {
                EditorAction(Icons.AutoMirrored.Filled.CallMerge, stringResource(R.string.action_restore_audio)) { vm.edit { it.restoreAudio(selection.id) } }
            } else {
                EditorAction(Icons.AutoMirrored.Filled.CallSplit, stringResource(R.string.action_detach_audio), clip?.source?.hasAudio == true) {
                    vm.edit { it.detachAudio(selection.id) }
                }
            },
            EditorAction(Icons.Filled.ContentCopy, stringResource(R.string.action_duplicate)) { vm.edit { it.duplicateClip(selection.id) } },
            EditorAction(Icons.Filled.Info, stringResource(R.string.action_details), clip != null) { clip?.let { showDetails(it.source) } },
            EditorAction(Icons.Filled.ChevronLeft, stringResource(R.string.action_move_earlier)) { vm.edit { it.moveClip(selection.id, -1) } },
            EditorAction(Icons.Filled.ChevronRight, stringResource(R.string.action_move_later)) { vm.edit { it.moveClip(selection.id, 1) } },
            EditorAction(Icons.Filled.Delete, stringResource(R.string.action_delete)) { vm.edit { it.deleteClip(selection.id) } },
        )
    }
    is Selection.Audio -> {
        val clip = project.audio.firstOrNull { it.id == selection.id }
        listOfNotNull(
            EditorAction(Icons.Filled.ContentCut, stringResource(R.string.action_split)) { vm.edit { it.splitAudioAt(selection.id, playheadUs) } },
            EditorAction(Icons.Filled.Edit, stringResource(R.string.tool_trim)) { openTool(Tool.TRIM) },
            EditorAction(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.tool_volume)) { openTool(Tool.VOLUME) },
            EditorAction(Icons.Filled.Gradient, stringResource(R.string.tool_fades)) { openTool(Tool.AUDIO_FADES) },
            EditorAction(Icons.Filled.SettingsEthernet, stringResource(R.string.tool_position)) { openTool(Tool.AUDIO_POSITION) },
            EditorAction(Icons.Filled.Info, stringResource(R.string.action_details), clip != null) { clip?.let { showDetails(it.source) } },
            clip?.takeIf { it.kind == AudioKind.DETACHED && it.linkedClipId != null }?.let { c ->
                EditorAction(Icons.AutoMirrored.Filled.CallMerge, stringResource(R.string.action_restore_audio)) { vm.edit { it.restoreAudio(c.linkedClipId!!) } }
            },
            EditorAction(Icons.Filled.Delete, stringResource(R.string.action_delete)) { vm.edit { it.deleteAudio(selection.id) } },
        )
    }
    is Selection.Text -> listOf(
        EditorAction(Icons.Filled.Edit, stringResource(R.string.action_edit)) { openTool(Tool.TEXT) },
        EditorAction(Icons.Filled.Add, stringResource(R.string.action_add_text)) { vm.addText(addTextLabel); openTool(Tool.TEXT) },
        EditorAction(Icons.Filled.Delete, stringResource(R.string.action_delete)) { vm.edit { it.deleteText(selection.id) } },
    )
}

@Composable
private fun ActionRow(actions: List<EditorAction>) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        actions.forEach { a ->
            Column(
                Modifier
                    .width(76.dp)
                    .clickable(enabled = a.enabled, onClick = a.onClick)
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val tint = if (a.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                Icon(a.icon, null, tint = tint, modifier = Modifier.size(24.dp))
                Text(a.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ToolSheet(title: String, onDone: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) { Text(stringResource(R.string.action_done)) }
        }
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    }
}

@Composable
private fun ToolContent(
    tool: Tool,
    vm: EditorViewModel,
    project: Project,
    selection: Selection,
    playheadUs: Long,
    exportSettings: ExportSettings,
    onExportSettings: (ExportSettings) -> Unit,
    cropRatio: CropRatio,
    onCropRatio: (CropRatio) -> Unit,
    onExport: () -> Unit,
) {
    val clip = (selection as? Selection.Clip)?.let { s -> project.clips.firstOrNull { it.id == s.id } }
    val audio = (selection as? Selection.Audio)?.let { s -> project.audio.firstOrNull { it.id == s.id } }
    val text = (selection as? Selection.Text)?.let { s -> project.texts.firstOrNull { it.id == s.id } }
    when (tool) {
        Tool.NONE -> Unit
        Tool.TRIM -> {
            clip?.let { ClipTrimPanel(vm, it, playheadUs) }
            audio?.let { AudioTrimPanel(vm, it) }
        }
        Tool.SPEED -> clip?.let { SpeedPanel(vm, it) }
        Tool.VOLUME -> {
            clip?.let { ClipVolumePanel(vm, it) }
            audio?.let { AudioVolumePanel(vm, it) }
        }
        Tool.AUDIO_FADES -> audio?.let { AudioFadesPanel(vm, it) }
        Tool.AUDIO_POSITION -> audio?.let { AudioPositionPanel(vm, it, playheadUs) }
        Tool.TEXT -> text?.let { TextPanel(vm, it, playheadUs) }
        Tool.CROP -> CropPanel(vm, project, cropRatio, onCropRatio)
        Tool.CANVAS -> CanvasPanel(vm, project)
        Tool.FILTERS -> FiltersPanel(vm, project)
        Tool.ADJUST -> AdjustPanel(vm, project)
        Tool.GRAIN -> GrainPanel(vm, project)
        Tool.VIDEO_FADES -> VideoFadesPanel(vm, project)
        Tool.EXPORT -> ExportPanel(exportSettings, onExportSettings, onExport)
    }
}

private fun toolTitle(t: Tool) = when (t) {
    Tool.NONE -> R.string.app_name
    Tool.TRIM -> R.string.tool_trim
    Tool.SPEED -> R.string.tool_speed
    Tool.VOLUME -> R.string.tool_volume
    Tool.AUDIO_FADES -> R.string.tool_fades
    Tool.AUDIO_POSITION -> R.string.tool_position
    Tool.TEXT -> R.string.tool_text
    Tool.CROP -> R.string.crop
    Tool.CANVAS -> R.string.tool_canvas
    Tool.FILTERS -> R.string.tool_filters
    Tool.ADJUST -> R.string.tool_adjust
    Tool.GRAIN -> R.string.tool_grain
    Tool.VIDEO_FADES -> R.string.tool_fades
    Tool.EXPORT -> R.string.action_export
}
