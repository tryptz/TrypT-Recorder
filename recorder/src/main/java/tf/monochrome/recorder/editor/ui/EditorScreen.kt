package tf.monochrome.recorder.editor.ui

import androidx.compose.material3.LinearProgressIndicator
import tf.monochrome.recorder.RecorderApp
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoCameraFront
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import tf.monochrome.recorder.R
import tf.monochrome.recorder.editor.export.ExportManager
import tf.monochrome.recorder.editor.export.ExportSettings
import tf.monochrome.recorder.editor.export.ExportState
import tf.monochrome.recorder.editor.export.PlanResult
import tf.monochrome.recorder.editor.model.AudioKind
import tf.monochrome.recorder.editor.model.CropBox
import tf.monochrome.recorder.editor.model.CropRect
import tf.monochrome.recorder.editor.model.MediaRef
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.model.VideoClip
import tf.monochrome.recorder.editor.model.assignLanes
import tf.monochrome.recorder.editor.model.clipStartUs
import tf.monochrome.recorder.editor.model.deleteAudio
import tf.monochrome.recorder.editor.model.deleteClip
import tf.monochrome.recorder.editor.model.deleteText
import tf.monochrome.recorder.editor.model.detachAudio
import tf.monochrome.recorder.editor.model.duplicateClip
import tf.monochrome.recorder.editor.model.durationUs
import tf.monochrome.recorder.editor.model.moveAudio
import tf.monochrome.recorder.editor.model.moveClip
import tf.monochrome.recorder.editor.model.moveOverlay
import tf.monochrome.recorder.editor.model.restoreAudio
import tf.monochrome.recorder.editor.model.snapToMarker
import tf.monochrome.recorder.editor.model.sourceAt
import tf.monochrome.recorder.editor.model.splitAt
import tf.monochrome.recorder.editor.model.splitAudioAt
import tf.monochrome.recorder.editor.model.splitOverlay
import tf.monochrome.recorder.editor.model.updateClipPicture
import tf.monochrome.recorder.editor.model.updateText
import tf.monochrome.recorder.ui.ExportStatusDialog
import tf.monochrome.recorder.ui.MediaDetailsDialog
import tf.monochrome.recorder.ui.glass.LocalGlassBackdrop
import tf.monochrome.recorder.ui.glass.glassBackdrop
import tf.monochrome.recorder.ui.glass.rememberGlassBackdrop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(projectId: String, onClose: () -> Unit) {
    val vm: EditorViewModel = viewModel(key = "editor-$projectId", factory = EditorViewModel.factory(projectId))
    val history by vm.history.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    // The playhead moves 30 times a second while playing. Reading it here would
    // recompose the whole editor each time, so it is handed down as a function
    // and read only by what shows it (the time, the timeline, a few panels);
    // buttons that act at the playhead read vm.playheadUs.value when tapped.
    val playheadState = vm.playheadUs.collectAsStateWithLifecycle()
    val playhead: () -> Long = remember(playheadState) { { playheadState.value } }
    val playing by vm.playing.collectAsStateWithLifecycle()
    val aspect by vm.aspect.collectAsStateWithLifecycle()
    val previewError by vm.previewError.collectAsStateWithLifecycle()
    val trackProgress by vm.trackProgress.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val exportState by ExportManager.state.collectAsStateWithLifecycle()
    val bundleBusy by vm.bundleBusy.collectAsStateWithLifecycle()
    val pendingExports by ExportManager.pendingCount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var tool by rememberSaveable { mutableStateOf(Tool.NONE) }
    var zoom by rememberSaveable { mutableFloatStateOf(48f) }
    var exportSettings by remember { mutableStateOf(ExportSettings()) }
    var exportSheet by rememberSaveable { mutableStateOf(false) }
    var dockTab by rememberSaveable { mutableStateOf(DockTab.EDIT) }
    var cropRatio by rememberSaveable { mutableStateOf(CropRatio.FREE) }
    var renaming by remember { mutableStateOf(false) }
    var detailsOf by remember { mutableStateOf<MediaRef?>(null) }
    var showMusicLibrary by rememberSaveable { mutableStateOf(false) }
    var showLutLibrary by rememberSaveable { mutableStateOf<String?>(null) }
    var lutTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var angleTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var tracking by remember { mutableStateOf<Pair<VideoClip, Long>?>(null) }

    val bundleWriter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        // The app's scope, not this screen's: leaving the editor mid-save used
        // to cancel it and leave a partial zip where the user chose to save.
        if (uri != null) RecorderApp.instance.appScope.launch { vm.saveBundle(uri) }
    }
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = lutTarget
        if (uri != null && id != null) scope.launch { vm.importLut(id, uri) }
    }
    val anglePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = angleTarget
        if (uri != null && id != null) scope.launch { vm.addAngle(id, uri) }
    }
    val captionPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { vm.importCaptions(uri) }
    }
    val captionWriter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) scope.launch { vm.exportCaptions(uri) }
    }

    val clipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { vm.addClips(uris) }
    }
    val overlayPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { vm.addPictureInPicture(uri) }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { vm.addAudio(uri) }
    }

    LaunchedEffect(Unit) { vm.refreshExportQueue() }
    LaunchedEffect(selection) { if (!tool.fits(selection)) tool = Tool.NONE }
    LaunchedEffect(tool) {
        vm.setCropEditing(tool == Tool.CROP)
        if (tool != Tool.GRAIN) vm.setGrainSolo(false)
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.message.value = null
        }
    }
    // An export can take minutes; keep the screen (and so the app) alive for it.
    val view = LocalView.current
    val exporting = exportState is ExportState.Running
    // Leaving the app (Home, lock) pauses the preview.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.onBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(exporting) {
        view.keepScreenOn = exporting
        onDispose { view.keepScreenOn = false }
    }

    BackHandler {
        when {
            exportSheet -> exportSheet = false
            tool != Tool.NONE -> tool = Tool.NONE
            else -> onClose()
        }
    }

    val h = history
    if (h == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val project = h.present
    tracking?.let { (clip, sourceUs) -> TrackingDialog(vm, clip, sourceUs) { tracking = null } }
    if (tool == Tool.MIXER) {
        MixerPanel(vm, project, playing, playhead, close = {
            vm.commit()
            tool = Tool.NONE
        })
    }
    val selectedClip = (selection as? Selection.Clip)?.let { target -> project.clips.firstOrNull { it.id == target.id } }

    if (showMusicLibrary) {
        MusicLibrary(
            close = { showMusicLibrary = false },
            browseFiles = { showMusicLibrary = false; audioPicker.launch(arrayOf("audio/*")) },
            use = { track -> showMusicLibrary = false; scope.launch { vm.addLibraryMusic(track) } },
        )
    }
    showLutLibrary?.let { clipId ->
        LutLibrary(
            close = { showLutLibrary = null },
            browseFiles = {
                showLutLibrary = null
                lutTarget = clipId
                lutPicker.launch(arrayOf("*/*"))
            },
            use = { lut -> showLutLibrary = null; scope.launch { vm.applyLibraryLut(clipId, lut) } },
        )
    }
    val backdrop = rememberGlassBackdrop()
    val saveExport: (Boolean) -> Unit = { bothAspects ->
        when (vm.export(exportSettings, bothAspects)) {
            null -> exportSheet = false
            PlanResult.Reason.NO_CLIPS -> Toast.makeText(context, R.string.export_no_clips, Toast.LENGTH_SHORT).show()
            PlanResult.Reason.NO_AUDIO -> Toast.makeText(context, R.string.export_no_audio, Toast.LENGTH_SHORT).show()
        }
    }
    // The tab the dock lights: the one the selection belongs to, else the one last picked.
    val activeTab = when (selection) {
        is Selection.Clip, is Selection.Overlay -> DockTab.EDIT
        is Selection.Audio -> DockTab.AUDIO
        is Selection.Text -> DockTab.TEXT
        Selection.None -> dockTab
    }
    // The page's own text and icon colour: the Scaffold this replaced set it, and
    // without it everything not on a Surface defaults to black — on black.
    CompositionLocalProvider(
        LocalGlassBackdrop provides backdrop,
        LocalContentColor provides MaterialTheme.colorScheme.onBackground,
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Everything but the floating glass: the glass frosts and bends this,
        // so the panes are drawn beside it, never inside it.
        Column(
            Modifier
                .fillMaxSize()
                .glassBackdrop(backdrop)
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding(),
        ) {
            Spacer(Modifier.height(TOP_BAR_HEIGHT))
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
                            factory = { ctx -> TextureView(ctx).also(vm::attachSurface) },
                            onRelease = vm::detachSurface,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (tool == Tool.COVER && selectedClip != null) {
                            val start = project.clipStartUs(selectedClip.id)
                            val end = start + selectedClip.durationUs
                            val cover = selectedClip.picture.cover
                            // Recomposes when the playhead enters or leaves the clip, not as it moves.
                            val inClip by remember(start, end) { derivedStateOf { playhead() in start until end } }
                            if (cover != null && inClip) {
                                CropOverlay(
                                    rect = cover,
                                    ratio = null,
                                    onChange = { rect ->
                                        vm.preview { p -> p.updateClipPicture(selectedClip.id) { it.copy(cover = rect) } }
                                    },
                                    onDragEnd = vm::commit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
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
                    trackProgress?.let { progress ->
                        // Cover tracking runs over the whole clip; show how far it is.
                        Column(
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .background(Color(0xAA000000))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                stringResource(R.string.pro_tracking_progress, (progress * 100).toInt()),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
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

            Transport(
                playheadUs = playhead,
                durationUs = project.durationUs,
                playing = playing,
                canPlay = project.clips.isNotEmpty(),
                canUndo = h.canUndo,
                canRedo = h.canRedo,
                onPlay = vm::togglePlay,
                onStepBack = { vm.scrubTo(project.frameRate.step(vm.playheadUs.value, forward = false)) },
                onStepForward = { vm.scrubTo(project.frameRate.step(vm.playheadUs.value, forward = true)) },
                onUndo = vm::undo,
                onRedo = vm::redo,
            )

            if (tool == Tool.NONE || tool == Tool.MIXER) {
                ToolRow(
                    actions = actionsFor(
                        project = project,
                        selection = selection,
                        tab = activeTab,
                        vm = vm,
                        openTool = { tool = it },
                        addClip = { clipPicker.launch(arrayOf("video/*")) },
                        addOverlay = { overlayPicker.launch(arrayOf("video/*")) },
                        addAudio = { showMusicLibrary = true },
                        addTextLabel = stringResource(R.string.text_default),
                        showDetails = { detailsOf = it },
                        rename = { renaming = true },
                    ),
                )
            }

            val laneCount = remember(project.audio) { assignLanes(project.audio).size }
            val overlayCount = remember(project.overlays) { project.overlays.map { it.layer }.distinct().size }
            val timelineHeight = 18 + 4 + 56 + (if (project.texts.isNotEmpty()) 30 else 0) + (laneCount + overlayCount) * 42 + 8
            val snapToleranceUs = (8_000_000 / zoom).toLong().coerceAtMost(250_000)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(timelineHeight.coerceAtMost(260).dp),
            ) {
                Timeline(
                    project = project,
                    playheadUs = playhead,
                    selection = selection,
                    pixelsPerSecond = zoom.dp,
                    onScrub = vm::scrubTo,
                    onSelect = { vm.selection.value = it },
                    onDragOverlay = { id, start -> vm.preview { it.moveOverlay(id, it.snapToMarker(start, snapToleranceUs)) } },
                    onDragAudio = { id, start -> vm.preview { it.moveAudio(id, it.snapToMarker(start, snapToleranceUs)) } },
                    onDragText = { id, start ->
                        vm.preview { p -> val snapped = p.snapToMarker(start, snapToleranceUs)
                            p.updateText(id) { t -> t.copy(startUs = snapped, endUs = snapped + (t.endUs - t.startUs)) } }
                    },
                    onDragEnd = vm::commit,
                    modifier = Modifier.fillMaxSize(),
                )
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)),
                ) {
                    IconButton(onClick = { zoom = (zoom / 1.6f).coerceAtLeast(6f) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.ZoomOut, stringResource(R.string.action_zoom_out), modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { zoom = (zoom * 1.6f).coerceAtMost(480f) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.ZoomIn, stringResource(R.string.action_zoom_in), modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (tool != Tool.NONE && tool != Tool.MIXER) {
                ToolSheet(title = stringResource(toolTitle(tool)), onDone = {
                    vm.commit()
                    tool = Tool.NONE
                }) {
                    ToolContent(
                        tool = tool,
                        openTool = { tool = it },
                        vm = vm,
                        project = project,
                        selection = selection,
                        playheadUs = playhead,
                        exportSettings = exportSettings,
                        onExportSettings = { exportSettings = it },
                        cropRatio = cropRatio,
                        onCropRatio = { cropRatio = it },
                        addLut = { showLutLibrary = it },
                        addAngle = { id ->
                            angleTarget = id
                            anglePicker.launch(arrayOf("video/*"))
                        },
                        importCaptions = { captionPicker.launch(arrayOf("*/*")) },
                        exportCaptions = { captionWriter.launch(project.name.replace('/', '_') + ".srt") },
                        trackCover = { clip, sourceUs -> tracking = clip to sourceUs },
                        pendingExports = pendingExports,
                        bundleBusy = bundleBusy,
                        saveBundle = { bundleWriter.launch(project.name.replace('/', '_') + "-project.zip") },
                        resumeExport = { vm.resumeExport(); tool = Tool.NONE },
                        discardExport = vm::discardQueuedExport,
                        onExport = saveExport,
                    )
                }
            }
            // Room for the dock, which floats over the bottom of this.
            if (tool == Tool.NONE || tool == Tool.MIXER) Spacer(Modifier.navigationBarsPadding().height(DOCK_ROOM))
            else Spacer(Modifier.navigationBarsPadding())
        }

        EditorTopBar(
            format = exportFormatLabel(exportSettings),
            sheetOpen = exportSheet,
            canSave = project.clips.isNotEmpty() && exportState !is ExportState.Running,
            onCancel = onClose,
            onFormat = { exportSheet = !exportSheet },
            onSave = { saveExport(false) },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .height(TOP_BAR_HEIGHT),
        )
        if (exportSheet) {
            ExportSheet(
                project = project,
                settings = exportSettings,
                onChange = { exportSettings = it },
                pendingExports = pendingExports,
                bundleBusy = bundleBusy,
                saveBundle = { bundleWriter.launch(project.name.replace('/', '_') + "-project.zip") },
                resumeExport = { exportSheet = false; vm.resumeExport() },
                discardExport = vm::discardQueuedExport,
                queueBothAspects = { saveExport(true) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = TOP_BAR_HEIGHT + 8.dp, start = 16.dp, end = 16.dp),
            )
        }
        if (tool == Tool.NONE || tool == Tool.MIXER) {
            EditorDock(
                selected = activeTab,
                onSelect = { tab ->
                    dockTab = tab
                    vm.selection.value = Selection.None
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .fillMaxWidth(),
            )
        }
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = DOCK_ROOM),
        )
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

@Composable
private fun actionsFor(
    project: Project,
    selection: Selection,
    tab: DockTab,
    vm: EditorViewModel,
    openTool: (Tool) -> Unit,
    addClip: () -> Unit,
    addOverlay: () -> Unit,
    addAudio: () -> Unit,
    addTextLabel: String,
    showDetails: (MediaRef) -> Unit,
    rename: () -> Unit,
): List<EditorAction> = when (selection) {
    // Nothing selected: the tools of the dock's tab, for the whole project.
    Selection.None -> when (tab) {
        DockTab.EDIT -> listOf(
            EditorAction(Icons.Filled.VideoLibrary, stringResource(R.string.action_add_clip), onClick = addClip),
            EditorAction(Icons.Filled.ContentCut, stringResource(R.string.action_split), project.clips.isNotEmpty()) { vm.edit { it.splitAt(vm.playheadUs.value) } },
            EditorAction(Icons.Filled.PictureInPictureAlt, stringResource(R.string.editor_add_overlay), project.clips.isNotEmpty(), addOverlay),
            EditorAction(Icons.Filled.Bookmark, stringResource(R.string.editor_markers), project.clips.isNotEmpty()) { openTool(Tool.MARKERS) },
            EditorAction(Icons.Filled.DriveFileRenameOutline, stringResource(R.string.action_rename), onClick = rename),
        )
        DockTab.AUDIO -> listOf(
            EditorAction(Icons.Filled.LibraryMusic, stringResource(R.string.action_add_audio), project.clips.isNotEmpty(), addAudio),
            EditorAction(Icons.Filled.Tune, stringResource(R.string.pro_mixer), project.clips.isNotEmpty()) { openTool(Tool.MIXER) },
        )
        DockTab.TEXT -> listOf(
            EditorAction(Icons.Filled.Title, stringResource(R.string.action_add_text), project.clips.isNotEmpty()) { vm.addText(addTextLabel); openTool(Tool.TEXT) },
            EditorAction(Icons.Filled.Subtitles, stringResource(R.string.pro_captions), project.clips.isNotEmpty()) { openTool(Tool.CAPTIONS) },
        )
        DockTab.ADJUST -> listOf(
            EditorAction(Icons.Filled.Tune, stringResource(R.string.tool_adjust)) { openTool(Tool.ADJUST) },
            EditorAction(Icons.Filled.Grain, stringResource(R.string.tool_grain)) { openTool(Tool.GRAIN) },
            EditorAction(Icons.Filled.Gradient, stringResource(R.string.tool_fades)) { openTool(Tool.VIDEO_FADES) },
        )
        DockTab.FILTER -> listOf(
            EditorAction(Icons.Filled.AutoFixHigh, stringResource(R.string.tool_filters)) { openTool(Tool.FILTERS) },
        )
        DockTab.FRAME -> listOf(
            EditorAction(Icons.Filled.Crop, stringResource(R.string.crop), project.clips.isNotEmpty()) { openTool(Tool.CROP) },
            EditorAction(Icons.Filled.AspectRatio, stringResource(R.string.tool_canvas)) { openTool(Tool.CANVAS) },
        )
    }
    is Selection.Clip -> {
        val clip = project.clips.firstOrNull { it.id == selection.id }
        listOf(
            EditorAction(Icons.Filled.ContentCut, stringResource(R.string.action_split)) { vm.edit { it.splitAt(vm.playheadUs.value) } },
            EditorAction(Icons.Filled.Edit, stringResource(R.string.tool_trim)) { openTool(Tool.TRIM) },
            EditorAction(Icons.Filled.Speed, stringResource(R.string.tool_speed)) { openTool(Tool.SPEED) },
            EditorAction(Icons.Filled.SettingsEthernet, stringResource(R.string.pro_precision)) { openTool(Tool.PRECISION) },
            EditorAction(Icons.Filled.VideoCameraFront, stringResource(R.string.pro_angles)) { openTool(Tool.ANGLES) },
            EditorAction(Icons.Filled.Animation, stringResource(R.string.editor_motion)) { openTool(Tool.MOTION) },
            EditorAction(Icons.Filled.Tune, stringResource(R.string.editor_clip_grade)) { openTool(Tool.CLIP_GRADE) },
            EditorAction(Icons.Filled.VisibilityOff, stringResource(R.string.editor_cover)) {
                val start = project.clipStartUs(selection.id)
                if (clip != null && vm.playheadUs.value !in start until start + clip.durationUs) vm.scrubTo(start)
                openTool(Tool.COVER)
            },
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
    is Selection.Overlay -> listOf(
        EditorAction(Icons.Filled.PictureInPictureAlt, stringResource(R.string.editor_overlay_settings)) { openTool(Tool.OVERLAY) },
        EditorAction(Icons.Filled.ContentCut, stringResource(R.string.action_split)) { vm.edit { it.splitOverlay(selection.id, vm.playheadUs.value) } },
        EditorAction(Icons.Filled.Delete, stringResource(R.string.action_delete)) {
            vm.edit { p -> p.copy(overlays = p.overlays.filterNot { it.id == selection.id }) }
        },
    )
    is Selection.Audio -> {
        val clip = project.audio.firstOrNull { it.id == selection.id }
        listOfNotNull(
            EditorAction(Icons.Filled.ContentCut, stringResource(R.string.action_split)) { vm.edit { it.splitAudioAt(selection.id, vm.playheadUs.value) } },
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
private fun ToolSheet(title: String, onDone: () -> Unit, content: @Composable () -> Unit) {
    // In the page, not floating, so it is a Material surface rather than glass:
    // glass frosts what is behind it, and this sits inside what the glass frosts.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .heightIn(max = 300.dp)
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 20.dp, bottomEnd = 20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
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
    openTool: (Tool) -> Unit,
    vm: EditorViewModel,
    project: Project,
    selection: Selection,
    playheadUs: () -> Long,
    exportSettings: ExportSettings,
    onExportSettings: (ExportSettings) -> Unit,
    cropRatio: CropRatio,
    onCropRatio: (CropRatio) -> Unit,
    addAngle: (String) -> Unit,
    addLut: (String) -> Unit,
    importCaptions: () -> Unit,
    exportCaptions: () -> Unit,
    trackCover: (VideoClip, Long) -> Unit,
    pendingExports: Int,
    bundleBusy: Boolean,
    saveBundle: () -> Unit,
    resumeExport: () -> Unit,
    discardExport: () -> Unit,
    onExport: (Boolean) -> Unit,
) {
    val clip = (selection as? Selection.Clip)?.let { s -> project.clips.firstOrNull { it.id == s.id } }
    val audio = (selection as? Selection.Audio)?.let { s -> project.audio.firstOrNull { it.id == s.id } }
    val text = (selection as? Selection.Text)?.let { s -> project.texts.firstOrNull { it.id == s.id } }
    when (tool) {
        Tool.NONE -> Unit
        Tool.TRIM -> {
            clip?.let { c -> WithPlayhead(playheadUs) { ClipTrimPanel(vm, c, it) } }
            audio?.let { AudioTrimPanel(vm, it) }
        }
        Tool.SPEED -> clip?.let {
            SpeedPanel(vm, it)
            WithPlayhead(playheadUs) { p -> RampPanel(vm, project, it, p) }
        }
        Tool.PRECISION -> clip?.let { PrecisionPanel(vm, project, it) }
        Tool.ANGLES -> clip?.let { AnglesPanel(vm, it) { addAngle(it.id) } }
        Tool.MIXER -> Unit // Full-screen panel over the attached editor preview.
        Tool.CAPTIONS -> {
            TextButton(onClick = importCaptions) { Text(stringResource(R.string.pro_import_srt)) }
            TextButton(
                enabled = project.texts.any { it.caption && it.startUs < project.durationUs },
                onClick = exportCaptions,
            ) { Text(stringResource(R.string.pro_export_srt)) }
            val label = stringResource(R.string.pro_caption_default)
            TextButton(onClick = { vm.addText(label, caption = true); openTool(Tool.TEXT) }) {
                Text(stringResource(R.string.pro_add_caption))
            }
        }
        Tool.MOTION -> clip?.let { c -> WithPlayhead(playheadUs) { ClipMotionPanel(vm, project, c, it) } }
        Tool.CLIP_GRADE -> clip?.let { ClipGradePanel(vm, it) { addLut(it.id) } }
        Tool.COVER -> clip?.let { selected ->
            val hasCover = selected.picture.cover != null
            Text(stringResource(R.string.editor_cover_help))
            TextButton(onClick = {
                vm.edit { p ->
                    p.updateClipPicture(selected.id) {
                        it.copy(cover = if (hasCover) null else CropRect(0.2f, 0.2f, 0.8f, 0.4f))
                    }
                }
            }) { Text(stringResource(if (hasCover) R.string.editor_remove_cover else R.string.editor_add_cover)) }
            val start = project.clipStartUs(selected.id)
            TextButton(enabled = selected.angleId == null, onClick = {
                trackCover(selected, selected.sourceAt(playheadUs() - start).coerceAtMost(selected.trimEndUs - 1))
            }) { Text(stringResource(R.string.pro_track_region)) }
            if (selected.picture.trackedCover != null) {
                TextButton(onClick = { vm.edit { p -> p.updateClipPicture(selected.id) { it.copy(trackedCover = null) } } }) {
                    Text(stringResource(R.string.pro_track_remove))
                }
            }
        }
        Tool.OVERLAY -> (selection as? Selection.Overlay)?.let { target ->
            project.overlays.firstOrNull { it.id == target.id }?.let { OverlayPanel(vm, it) }
        }
        Tool.MARKERS -> WithPlayhead(playheadUs) { MarkerPanel(vm, project, it) }
        Tool.VOLUME -> {
            clip?.let { c ->
                ClipVolumePanel(vm, c)
                RoleSelector(c.role) { role ->
                    vm.edit { p -> p.copy(clips = p.clips.map { if (it.id == c.id) it.copy(role = role) else it }) }
                }
            }
            audio?.let { a ->
                AudioVolumePanel(vm, a)
                RoleSelector(a.audioRole) { role ->
                    vm.edit { p -> p.copy(audio = p.audio.map { if (it.id == a.id) it.copy(role = role) else it }) }
                }
            }
        }
        Tool.AUDIO_FADES -> audio?.let { AudioFadesPanel(vm, it) }
        Tool.AUDIO_POSITION -> audio?.let { a -> WithPlayhead(playheadUs) { AudioPositionPanel(vm, a, it) } }
        Tool.TEXT -> text?.let { t -> WithPlayhead(playheadUs) { TextPanel(vm, t, it) } }
        Tool.CROP -> CropPanel(vm, project, cropRatio, onCropRatio)
        Tool.CANVAS -> CanvasPanel(vm, project)
        Tool.FILTERS -> FiltersPanel(vm, project)
        Tool.ADJUST -> AdjustPanel(vm, project)
        Tool.GRAIN -> GrainPanel(vm, project)
        Tool.VIDEO_FADES -> VideoFadesPanel(vm, project)
        Tool.EXPORT -> ExportPanel(
            exportSettings, onExportSettings, pendingExports, bundleBusy, saveBundle, resumeExport, discardExport, onExport,
        )
    }
}

/**
 * Reads the playhead in a scope of its own, so that as it moves only [content]
 * recomposes, not the tool sheet or the editor around it.
 */
@Composable
private fun WithPlayhead(playheadUs: () -> Long, content: @Composable (Long) -> Unit) {
    content(playheadUs())
}

private fun toolTitle(t: Tool) = when (t) {
    Tool.NONE -> R.string.app_name
    Tool.TRIM -> R.string.tool_trim
    Tool.PRECISION -> R.string.pro_precision
    Tool.ANGLES -> R.string.pro_angles
    Tool.MIXER -> R.string.pro_mixer
    Tool.CAPTIONS -> R.string.pro_captions
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
    Tool.MOTION -> R.string.editor_motion
    Tool.CLIP_GRADE -> R.string.editor_clip_grade
    Tool.COVER -> R.string.editor_cover
    Tool.OVERLAY -> R.string.editor_overlay_settings
    Tool.MARKERS -> R.string.editor_markers
    Tool.EXPORT -> R.string.action_export
}

/** Height of the floating top bar, which the page keeps clear. */
private val TOP_BAR_HEIGHT = 56.dp

/** What the page keeps clear for the floating dock: its height and its margins. */
private val DOCK_ROOM = 100.dp
