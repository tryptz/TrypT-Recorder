package tf.monochrome.recorder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tf.monochrome.recorder.R
import tf.monochrome.recorder.RecorderApp
import tf.monochrome.recorder.capture.formatDuration
import tf.monochrome.recorder.editor.EditorLauncher
import tf.monochrome.recorder.editor.export.ExportManager
import tf.monochrome.recorder.editor.export.PlanResult
import tf.monochrome.recorder.editor.store.ProjectSummary
import tf.monochrome.recorder.media.Recording
import tf.monochrome.recorder.media.RecordingsRepository
import tf.monochrome.recorder.media.Thumbnails

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpenProject: (String) -> Unit,
    onEditUris: (List<Uri>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = RecorderApp.instance.recordings
    val store = RecorderApp.instance.projects
    val recordings by remember { repo.observe() }.collectAsStateWithLifecycle(emptyList())
    val drafts by store.projects.collectAsStateWithLifecycle()
    val exportState by ExportManager.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var acting by remember { mutableStateOf<Recording?>(null) }
    var renaming by remember { mutableStateOf<Recording?>(null) }
    var deleting by remember { mutableStateOf<Recording?>(null) }
    var deletingDraft by remember { mutableStateOf<ProjectSummary?>(null) }

    LaunchedEffect(Unit) { store.refresh() }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onEditUris(uris)
    }
    // Our own recordings are always listed. These only add ones from before a
    // reinstall. On 14+ a partial grant ("select videos") counts as answered.
    val readPermissions = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    var canReadAll by remember {
        mutableStateOf(readPermissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
    }
    val readLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        canReadAll = granted.values.any { it }
    }

    fun handle(outcome: RecordingsRepository.Outcome) {
        when (outcome) {
            RecordingsRepository.Outcome.Done -> Unit
            RecordingsRepository.Outcome.Failed -> Toast.makeText(context, R.string.library_action_failed, Toast.LENGTH_SHORT).show()
            is RecordingsRepository.Outcome.NeedsConsent -> consent.launch(IntentSenderRequest.Builder(outcome.request).build())
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.library_recordings)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.library_drafts)) })
            }
            if (tab == 0) {
                if (!canReadAll) {
                    Text(
                        stringResource(R.string.library_grant_access),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { readLauncher.launch(readPermissions) }
                            .padding(16.dp),
                    )
                }
                if (recordings.isEmpty()) {
                    Empty(stringResource(R.string.library_no_recordings))
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(recordings, key = { it.uri.toString() }) { r ->
                            ListItem(
                                modifier = Modifier.clickable { acting = r },
                                leadingContent = { RecordingThumb(r.uri) },
                                headlineContent = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    Text("${formatDuration(r.durationMs)} · ${formatBytes(context, r.sizeBytes)} · ${formatDate(r.dateAddedSec * 1000)}")
                                },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                            )
                        }
                    }
                }
            } else {
                if (drafts.isEmpty()) {
                    Empty(stringResource(R.string.library_no_drafts))
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(drafts, key = { it.id }) { d ->
                            ListItem(
                                modifier = Modifier.combinedClickable(onClick = { onOpenProject(d.id) }, onLongClick = { deletingDraft = d }),
                                leadingContent = { DraftThumb(d.coverUri) },
                                headlineContent = { Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    Text(pluralStringResource(R.plurals.draft_summary, d.clipCount, d.clipCount, formatDuration(d.durationUs / 1000), formatDate(d.updatedAt)))
                                },
                                trailingContent = {
                                    Icon(
                                        Icons.Filled.Delete,
                                        stringResource(R.string.action_delete),
                                        modifier = Modifier.clickable { deletingDraft = d },
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                            )
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { picker.launch(arrayOf("video/*")) },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text(stringResource(R.string.library_new_project)) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }

    acting?.let { r ->
        ModalBottomSheet(onDismissRequest = { acting = null }) {
            Text(r.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            SheetAction(Icons.Filled.Edit, stringResource(R.string.action_edit)) { acting = null; onEditUris(listOf(r.uri)) }
            SheetAction(Icons.Filled.PlayArrow, stringResource(R.string.action_play)) { acting = null; play(context, r.uri) }
            SheetAction(Icons.Filled.Share, stringResource(R.string.action_share)) { acting = null; share(context, r.uri, "video/mp4") }
            SheetAction(Icons.Filled.AudioFile, stringResource(R.string.action_extract_audio)) {
                acting = null
                scope.launch {
                    when (EditorLauncher.extractAudio(context, r.uri)) {
                        null -> Unit
                        PlanResult.Reason.NO_AUDIO -> Toast.makeText(context, R.string.export_no_audio, Toast.LENGTH_SHORT).show()
                        PlanResult.Reason.NO_CLIPS -> Toast.makeText(context, R.string.editor_some_files_skipped, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            SheetAction(Icons.Filled.DriveFileRenameOutline, stringResource(R.string.action_rename)) { acting = null; renaming = r }
            SheetAction(Icons.Filled.Delete, stringResource(R.string.action_delete)) { acting = null; deleting = r }
            Box(Modifier.padding(bottom = 24.dp))
        }
    }

    renaming?.let { r ->
        var name by remember(r) { mutableStateOf(r.name.substringBeforeLast('.')) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.action_rename)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    renaming = null
                    scope.launch { handle(repo.rename(r.uri, name)) }
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_recording_title)) },
            text = { Text(r.name) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { handle(repo.delete(r.uri)) }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    deletingDraft?.let { d ->
        AlertDialog(
            onDismissRequest = { deletingDraft = null },
            title = { Text(stringResource(R.string.delete_draft_title)) },
            text = { Text(stringResource(R.string.delete_draft_text, d.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deletingDraft = null
                    scope.launch { store.delete(d.id) }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deletingDraft = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    ExportStatusDialog(exportState, onCancel = ExportManager::cancel, onDismiss = ExportManager::acknowledge)
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecordingThumb(uri: Uri) {
    val context = LocalContext.current
    val bmp by produceState<android.graphics.Bitmap?>(null, uri) { value = Thumbnails.library(context, uri, Size(192, 192)) }
    Thumb(bmp)
}

@Composable
private fun DraftThumb(uri: String?) {
    val context = LocalContext.current
    val bmp by produceState<android.graphics.Bitmap?>(null, uri) {
        value = uri?.let { Thumbnails.frame(context, it, 0, 128) }
    }
    Thumb(bmp)
}

@Composable
private fun Thumb(bmp: android.graphics.Bitmap?) {
    Box(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
