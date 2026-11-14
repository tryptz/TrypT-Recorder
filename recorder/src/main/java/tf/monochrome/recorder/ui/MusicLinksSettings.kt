package tf.monochrome.recorder.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.recorder.R
import tf.monochrome.recorder.RecorderApp
import tf.monochrome.recorder.editor.model.LinkBand
import tf.monochrome.recorder.editor.model.LinkCurve
import tf.monochrome.recorder.editor.model.LinkFormula
import tf.monochrome.recorder.editor.model.LinkShape
import tf.monochrome.recorder.editor.model.LinkSmoothing
import tf.monochrome.recorder.editor.model.LinkWay
import tf.monochrome.recorder.editor.model.RecorderLinkTarget
import tf.monochrome.recorder.editor.model.RecorderMusicLinks
import tf.monochrome.recorder.editor.model.VisualLink
import tf.monochrome.recorder.media.CreativeAssets

@Composable
internal fun MusicLinksSettings() {
    val context = LocalContext.current
    val store = RecorderApp.instance.settings
    val scope = rememberCoroutineScope()
    var links by remember { mutableStateOf(store.musicLinks) }
    var target by remember { mutableStateOf<RecorderLinkTarget?>(null) }
    var importedDraft by remember { mutableStateOf<VisualLink?>(null) }
    var imported by remember { mutableStateOf<List<VisualLink>>(emptyList()) }
    var error by remember { mutableStateOf(false) }

    fun save(next: List<VisualLink>) {
        links = RecorderMusicLinks.clean(next)
        store.saveMusicLinks(links)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            error = false
            try {
                imported = withContext(Dispatchers.IO) {
                    val text = checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        val out = ByteArrayOutputStream()
                        val block = ByteArray(8192)
                        while (true) {
                            val n = input.read(block)
                            if (n < 0) break
                            require(out.size() + n <= 512 * 1024)
                            out.write(block, 0, n)
                        }
                        out.toString(Charsets.UTF_8.name())
                    }
                    RecorderMusicLinks.readPreset(text)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = true
            }
        }
    }

    SectionHeader(stringResource(R.string.music_links_title))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.music_links_description), style = MaterialTheme.typography.bodyMedium)
        for (item in RecorderLinkTarget.entries) {
            val existing = links.firstOrNull { it.target == item.key }
            val status = when {
                existing == null -> stringResource(R.string.music_links_add)
                existing.paused -> stringResource(R.string.music_links_paused)
                else -> bandLabel(existing.shape.band)
            }
            Row(
                Modifier.fillMaxWidth().clickable { target = item }.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(item.label))
                Text(status, color = MaterialTheme.colorScheme.primary)
            }
        }
        TextButton(onClick = { picker.launch(arrayOf("*/*")) }) {
            Text(stringResource(R.string.music_links_import_preset))
        }
        if (error) Text(stringResource(R.string.music_links_bad_preset), color = MaterialTheme.colorScheme.error)
    }

    target?.let { item ->
        val existing = links.firstOrNull { it.target == item.key }
        LinkEditor(
            target = item,
            initial = importedDraft ?: existing ?: item.default(),
            remove = existing?.let { { save(links.filterNot { it.target == item.key }); target = null } },
            close = { importedDraft = null; target = null },
            apply = { link ->
                save(links.filterNot { it.target == item.key } + link)
                importedDraft = null
                target = null
            },
        )
    }
    if (imported.isNotEmpty()) {
        var source by remember(imported) { mutableStateOf(imported.first()) }
        var destination by remember(imported) { mutableStateOf(RecorderLinkTarget.entries.first()) }
        AlertDialog(
            onDismissRequest = { imported = emptyList() },
            title = { Text(stringResource(R.string.music_links_map_preset)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.music_links_source))
                    for (link in imported) {
                        FilterChip(link == source, { source = link }, label = { Text(link.target) })
                    }
                    Text(stringResource(R.string.music_links_destination))
                    for (item in RecorderLinkTarget.entries) {
                        FilterChip(item == destination, { destination = item }, label = { Text(stringResource(item.label)) })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    importedDraft = RecorderMusicLinks.remap(source, destination)
                    target = destination
                    imported = emptyList()
                }) { Text(stringResource(R.string.fx_set)) }
            },
            dismissButton = { TextButton(onClick = { imported = emptyList() }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkEditor(
    target: RecorderLinkTarget,
    initial: VisualLink,
    remove: (() -> Unit)?,
    close: () -> Unit,
    apply: (VisualLink) -> Unit,
) {
    var shape by remember(initial) { mutableStateOf(initial.shape.clamped(-1f, 1f)) }
    var paused by remember(initial) { mutableStateOf(initial.paused) }
    val low = minOf(shape.lowest, shape.highest)
    val high = maxOf(shape.lowest, shape.highest)
    val formulaValid = shape.formula.isBlank() || LinkFormula.compile(shape.formula).program != null
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(target.label)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.music_links_band))
                Chips(LinkBand.entries, shape.band, { shape = shape.copy(band = it) }) { bandLabel(it) }
                if (shape.band == LinkBand.CUSTOM) {
                    Text(stringResource(R.string.music_links_band_range, shape.loHz.toInt(), shape.hiHz.toInt()))
                    RangeSlider(
                        value = shape.loHz..shape.hiHz,
                        onValueChange = { shape = shape.copy(loHz = it.start, hiHz = it.endInclusive).clamped(-1f, 1f) },
                        valueRange = 20f..20_000f,
                    )
                }
                Text(stringResource(R.string.music_links_range, (low * 100).toInt(), (high * 100).toInt()))
                RangeSlider(low..high, { shape = shape.copy(lowest = it.start, highest = it.endInclusive) }, valueRange = -1f..1f)
                Text(stringResource(R.string.music_links_direction))
                Chips(LinkWay.entries, shape.way, { shape = shape.copy(way = it) }) { enumLabel(it) }
                Text(stringResource(R.string.music_links_curve))
                Chips(LinkCurve.entries, shape.curve, { shape = shape.copy(curve = it) }) { enumLabel(it) }
                Text(stringResource(R.string.music_links_feel, (shape.feel * 100).toInt()))
                Slider(shape.feel, { shape = shape.copy(feel = it) })
                Text(stringResource(R.string.music_links_smoothing))
                Chips(LinkSmoothing.entries, shape.smoothing, { shape = shape.copy(smoothing = it) }) { enumLabel(it) }
                Text(stringResource(R.string.music_links_input_trim, shape.boostDb.toInt()))
                Slider(shape.boostDb, { shape = shape.copy(boostDb = it) }, valueRange = -24f..24f)
                Text(stringResource(R.string.music_links_quiet, shape.quietDb.toInt()))
                Slider(shape.quietDb, { shape = shape.copy(quietDb = it) }, valueRange = -96f..0f)
                OutlinedTextField(
                    shape.formula, { shape = shape.copy(formula = it.take(LinkFormula.MAX_LENGTH)) },
                    label = { Text(stringResource(R.string.music_links_formula)) },
                    isError = !formulaValid,
                    singleLine = true,
                )
                FilterChip(paused, { paused = !paused }, label = { Text(stringResource(R.string.music_links_paused)) })
                remove?.let { TextButton(onClick = it) { Text(stringResource(R.string.music_links_remove)) } }
            }
        },
        confirmButton = {
            TextButton(enabled = formulaValid, onClick = { apply(initial.copy(shape = shape, paused = paused)) }) {
                Text(stringResource(R.string.fx_set))
            }
        },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun <T> Chips(items: List<T>, current: T, pick: (T) -> Unit, label: @Composable (T) -> String) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (item in items) FilterChip(item == current, { pick(item) }, label = { Text(label(item)) })
    }
}

@Composable
private fun bandLabel(band: LinkBand): String = stringResource(when (band) {
    LinkBand.KICK -> R.string.music_links_kick
    LinkBand.VOCALS -> R.string.music_links_vocals
    LinkBand.SNARE -> R.string.music_links_snare
    LinkBand.HATS -> R.string.music_links_hats
    LinkBand.CUSTOM -> R.string.music_links_custom
})

private fun enumLabel(value: Enum<*>): String = value.name.lowercase().replaceFirstChar { it.uppercase() }

@Composable
internal fun CreativeMediaSettings() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var link by rememberSaveable { mutableStateOf("") }
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    SectionHeader(stringResource(R.string.creative_music_library))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = link, onValueChange = { link = it }, enabled = !busy,
            label = { Text(stringResource(R.string.music_link_import)) },
            supportingText = { Text(stringResource(R.string.music_link_url_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(enabled = !busy && link.isNotBlank(), onClick = {
            busy = true
            result = null
            scope.launch {
                try {
                    val item = CreativeAssets.download(context, link)
                    result = context.getString(R.string.music_link_download_done, item.title)
                    link = ""
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CreativeAssets.ImportError) {
                    result = context.getString(e.resource)
                } catch (_: Exception) {
                    result = context.getString(R.string.music_link_download_failed)
                } finally {
                    busy = false
                }
            }
        }) { Text(stringResource(R.string.music_link_import_button)) }
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { uriHandler.openUri("https://opengameart.org/content/music-loop-variations") }) {
            Text(stringResource(R.string.creative_music_source))
        }
        TextButton(onClick = { uriHandler.openUri("https://github.com/stripedpurple/color-grading-luts") }) {
            Text(stringResource(R.string.creative_striped_source))
        }
        TextButton(onClick = { uriHandler.openUri("https://github.com/sguyader/FilmSim") }) {
            Text(stringResource(R.string.creative_filmsim_source))
        }
    }
}
