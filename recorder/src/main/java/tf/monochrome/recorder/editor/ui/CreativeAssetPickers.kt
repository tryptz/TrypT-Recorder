package tf.monochrome.recorder.editor.ui

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
    val tracks by produceState(emptyList(), context) { value = CreativeAssets.list(context) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.creative_music_library)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(tracks, key = { it.file.path }) { track ->
                    val source = if (track.bundled) R.string.creative_music_cc0 else R.string.creative_music_imported
                    AssetRow(track.title, stringResource(source)) { use(track) }
                }
            }
        },
        confirmButton = { TextButton(onClick = browseFiles) { Text(stringResource(R.string.creative_browse_music)) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun AssetRow(title: String, subtitle: String, use: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = use).padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
