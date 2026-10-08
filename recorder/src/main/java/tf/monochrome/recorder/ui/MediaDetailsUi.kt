package tf.monochrome.recorder.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tf.monochrome.recorder.R
import tf.monochrome.recorder.capture.formatDuration
import tf.monochrome.recorder.media.MediaDetails
import tf.monochrome.recorder.media.MediaInspector
import tf.monochrome.recorder.media.Rates

/** Reading a file's details: null while it runs. */
private sealed interface Reading {
    data object Busy : Reading
    data class Done(val details: MediaDetails?) : Reading
}

@Composable
private fun rememberDetails(uri: Uri): Reading {
    val context = LocalContext.current
    val state by produceState<Reading>(Reading.Busy, uri) { value = Reading.Done(MediaInspector.inspect(context, uri)) }
    return state
}

/**
 * The file's technical summary in one line, read from the file itself —
 * `1080×2400 · 165 fps · H.264 High · 31.2 Mbps · AAC-LC 320 kbps stereo`.
 */
@Composable
fun MediaSummaryLine(uri: Uri, modifier: Modifier = Modifier) {
    val text = when (val r = rememberDetails(uri)) {
        Reading.Busy -> stringResource(R.string.details_reading)
        is Reading.Done -> r.details?.summary() ?: return
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** Everything about a file worth knowing: container, codecs, frame rate, bitrates. */
@Composable
fun MediaDetailsDialog(uri: Uri, title: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val reading = rememberDetails(uri)
    val sections = (reading as? Reading.Done)?.details?.let { detailRows(it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2) },
        text = {
            when {
                reading is Reading.Busy -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                }
                sections == null -> Text(stringResource(R.string.details_unreadable))
                else -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    sections.forEach { (header, rows) ->
                        Text(
                            header,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        rows.forEach { (label, value) ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
                                Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.58f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        dismissButton = {
            if (sections != null) {
                TextButton(onClick = {
                    val text = buildString {
                        appendLine(title)
                        sections.forEach { (header, rows) ->
                            appendLine()
                            appendLine(header)
                            rows.forEach { (l, v) -> appendLine("$l: $v") }
                        }
                    }
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText(title, text.trimEnd()))
                    Toast.makeText(context, R.string.details_copied, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.details_copy)) }
            }
        },
    )
}

@Composable
private fun detailRows(d: MediaDetails): List<Pair<String, List<Pair<String, String>>>> {
    val context = LocalContext.current
    val approx: @Composable (Boolean, String) -> String = { a, v -> if (a) stringResource(R.string.details_approx, v) else v }
    val out = mutableListOf<Pair<String, List<Pair<String, String>>>>()
    out += stringResource(R.string.details_file) to listOfNotNull(
        stringResource(R.string.details_container) to d.container,
        stringResource(R.string.details_duration) to formatDuration(d.durationUs / 1000),
        (stringResource(R.string.details_size) to formatBytes(context, d.sizeBytes)).takeIf { d.sizeBytes > 0 },
        (stringResource(R.string.details_overall_bitrate) to Rates.formatBitrate(d.overallBitrate)).takeIf { d.overallBitrate > 0 },
    )
    d.video?.let { v ->
        val codec = listOfNotNull(
            v.codec,
            v.profile?.let { stringResource(R.string.details_profile, it) },
            v.level?.let { stringResource(R.string.details_level, it) },
        ).joinToString(" · ")
        val fps = when {
            Rates.isVariable(v.peakFps, v.averageFps) ->
                stringResource(R.string.details_fps_variable, Rates.formatFps(v.peakFps), Rates.formatFps(v.averageFps))
            v.averageFps > 0f -> stringResource(R.string.details_fps, Rates.formatFps(v.averageFps))
            v.peakFps > 0f -> stringResource(R.string.details_fps, Rates.formatFps(v.peakFps))
            else -> "?"
        }
        out += stringResource(R.string.details_video) to listOfNotNull(
            stringResource(R.string.details_codec) to codec,
            stringResource(R.string.details_resolution) to "${v.width} × ${v.height}",
            stringResource(R.string.details_frame_rate) to fps,
            (stringResource(R.string.details_bitrate) to approx(v.approximate, Rates.formatBitrate(v.bitrate))).takeIf { v.bitrate > 0 },
            (stringResource(R.string.details_frames) to "%,d".format(v.frames)).takeIf { v.frames > 0 },
        )
    }
    d.audio?.let { a ->
        out += stringResource(R.string.details_audio) to listOfNotNull(
            stringResource(R.string.details_codec) to a.codec,
            (stringResource(R.string.details_sample_rate) to Rates.formatSampleRate(a.sampleRate)).takeIf { a.sampleRate > 0 },
            (stringResource(R.string.details_channels) to "${a.channels} (${Rates.channels(a.channels)})").takeIf { a.channels > 0 },
            (stringResource(R.string.details_bitrate) to approx(a.approximate, Rates.formatBitrate(a.bitrate))).takeIf { a.bitrate > 0 },
        )
    }
    return out
}
