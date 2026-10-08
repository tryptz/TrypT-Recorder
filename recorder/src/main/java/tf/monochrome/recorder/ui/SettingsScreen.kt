package tf.monochrome.recorder.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tf.monochrome.recorder.R
import tf.monochrome.recorder.RecorderApp
import tf.monochrome.recorder.media.MediaStoreFile
import tf.monochrome.recorder.settings.AudioSource
import tf.monochrome.recorder.settings.QualityPreset
import tf.monochrome.recorder.settings.RecorderSettings
import tf.monochrome.recorder.settings.ResolutionPreset

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val store = RecorderApp.instance.settings
    val s by store.settings.collectAsStateWithLifecycle()
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SectionHeader(stringResource(R.string.settings_video))
        ChoiceRow(
            title = stringResource(R.string.settings_resolution),
            options = ResolutionPreset.entries,
            selected = s.resolution,
            label = { stringResource(resolutionLabel(it)) },
            onSelect = { r -> store.update { it.copy(resolution = r) } },
        )
        ChoiceRow(
            title = stringResource(R.string.settings_frame_rate),
            options = RecorderSettings.FRAME_RATES,
            selected = s.frameRate,
            label = { stringResource(R.string.fps, it) },
            onSelect = { f -> store.update { it.copy(frameRate = f) } },
        )
        ChoiceRow(
            title = stringResource(R.string.settings_quality),
            options = QualityPreset.entries,
            selected = s.quality,
            label = { stringResource(qualityLabel(it)) },
            onSelect = { q -> store.update { it.copy(quality = q) } },
        )
        Text(
            stringResource(R.string.settings_video_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        SectionHeader(stringResource(R.string.settings_audio))
        ChoiceRow(
            title = stringResource(R.string.settings_audio_source),
            options = AudioSource.entries,
            selected = s.audioSource,
            label = { stringResource(audioShortLabel(it)) },
            onSelect = { a -> store.update { it.copy(audioSource = a) } },
        )
        Text(
            stringResource(R.string.settings_audio_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        SectionHeader(stringResource(R.string.settings_recording))
        ChoiceRow(
            title = stringResource(R.string.settings_countdown),
            options = RecorderSettings.COUNTDOWNS,
            selected = s.countdownSeconds,
            label = { if (it == 0) stringResource(R.string.countdown_off) else pluralStringResource(R.plurals.countdown_seconds, it, it) },
            onSelect = { c -> store.update { it.copy(countdownSeconds = c) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_hide_button),
            subtitle = stringResource(R.string.settings_hide_button_hint),
            checked = s.hideButtonWhileRecording,
            onChange = { v -> store.update { it.copy(hideButtonWhileRecording = v) } },
        )

        SectionHeader(stringResource(R.string.settings_storage))
        Text(
            stringResource(
                R.string.settings_storage_text,
                MediaStoreFile.relativePath(MediaStoreFile.Kind.VIDEO),
                MediaStoreFile.relativePath(MediaStoreFile.Kind.AUDIO),
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        SectionHeader(stringResource(R.string.settings_about))
        Text(
            stringResource(R.string.settings_about_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
        )
    }
}

fun resolutionLabel(r: ResolutionPreset) = when (r) {
    ResolutionPreset.NATIVE -> R.string.resolution_native
    ResolutionPreset.P1440 -> R.string.resolution_1440
    ResolutionPreset.P1080 -> R.string.resolution_1080
    ResolutionPreset.P720 -> R.string.resolution_720
    ResolutionPreset.P480 -> R.string.resolution_480
}

fun qualityLabel(q: QualityPreset) = when (q) {
    QualityPreset.LOW -> R.string.quality_low
    QualityPreset.MEDIUM -> R.string.quality_standard
    QualityPreset.HIGH -> R.string.quality_high
}
