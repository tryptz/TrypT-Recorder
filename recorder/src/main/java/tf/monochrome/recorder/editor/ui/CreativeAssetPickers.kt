package tf.monochrome.recorder.editor.ui

import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tf.monochrome.recorder.R
import tf.monochrome.recorder.media.CreativeAssets

@Composable
internal fun LutLibrary(close: () -> Unit, browseFiles: () -> Unit, use: (CreativeAssets.Lut) -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.creative_lut_library)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(CreativeAssets.luts, key = { it.file }) { lut ->
                    AssetRow(lut.title, lut.collection) { use(lut) }
                }
            }
        },
        confirmButton = { TextButton(onClick = browseFiles) { Text(stringResource(R.string.creative_browse_lut)) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun MusicLibrary(close: () -> Unit, browseFiles: () -> Unit, use: (CreativeAssets.Track) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val tracks by produceState(emptyList(), context, refresh) { value = CreativeAssets.list(context) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.creative_music_library)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(tracks, key = { it.file.path }) { track ->
                    val source = if (track.bundled) R.string.creative_music_cc0 else R.string.creative_music_imported
                    // Downloaded and imported music can be removed again; bundled loops cannot.
                    val delete: (() -> Unit)? = if (track.bundled) null else {
                        { scope.launch { if (CreativeAssets.deleteImport(context, track)) refresh++ } }
                    }
                    AssetRow(track.title, stringResource(source), delete) { use(track) }
                }
            }
        },
        confirmButton = { TextButton(onClick = browseFiles) { Text(stringResource(R.string.creative_browse_music)) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun AssetRow(title: String, subtitle: String, delete: (() -> Unit)? = null, use: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(onClick = use).padding(vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (delete != null) {
            IconButton(onClick = delete) { Icon(Icons.Filled.Delete, stringResource(R.string.action_delete)) }
        }
    }
}
