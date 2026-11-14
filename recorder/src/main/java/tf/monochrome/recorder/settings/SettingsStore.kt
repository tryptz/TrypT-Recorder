package tf.monochrome.recorder.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import tf.monochrome.recorder.editor.model.RecorderMusicLinks
import tf.monochrome.recorder.editor.model.VisualLink

/**
 * SharedPreferences-backed settings, exposed as a StateFlow. The service, the
 * tile and the UI all read the same instance (see RecorderApp), so a change in
 * Settings is seen by the next recording without any plumbing.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("recorder_settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    val settings: StateFlow<RecorderSettings> = state.asStateFlow()
    val current: RecorderSettings get() = state.value

    internal val musicLinks: List<VisualLink>
        get() = RecorderMusicLinks.decode(prefs.getString(K_MUSIC_LINKS, "").orEmpty())

    internal fun saveMusicLinks(links: List<VisualLink>) {
        prefs.edit { putString(K_MUSIC_LINKS, RecorderMusicLinks.encode(links)) }
    }

    fun update(transform: (RecorderSettings) -> RecorderSettings) {
        val next = transform(state.value)
        state.value = next
        prefs.edit {
            putString(K_AUDIO, next.audioSource.name)
            putString(K_RES, next.resolution.name)
            putInt(K_FPS, next.frameRate)
            putString(K_QUALITY, next.quality.name)
            putInt(K_COUNTDOWN, next.countdownSeconds)
            putBoolean(K_BUTTON, next.floatingButton)
            putBoolean(K_HIDE, next.hideButtonWhileRecording)
            putBoolean(K_EDIT_READY, next.editReady)
        }
    }

    private fun read(): RecorderSettings {
        val d = RecorderSettings()
        return RecorderSettings(
            audioSource = enumOr(prefs.getString(K_AUDIO, null), d.audioSource),
            resolution = enumOr(prefs.getString(K_RES, null), d.resolution),
            frameRate = prefs.getInt(K_FPS, d.frameRate),
            quality = enumOr(prefs.getString(K_QUALITY, null), d.quality),
            countdownSeconds = prefs.getInt(K_COUNTDOWN, d.countdownSeconds),
            floatingButton = prefs.getBoolean(K_BUTTON, d.floatingButton),
            hideButtonWhileRecording = prefs.getBoolean(K_HIDE, d.hideButtonWhileRecording),
            editReady = prefs.getBoolean(K_EDIT_READY, d.editReady),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback

    private companion object {
        const val K_MUSIC_LINKS = "music_links"
        const val K_AUDIO = "audio_source"
        const val K_RES = "resolution"
        const val K_FPS = "frame_rate"
        const val K_QUALITY = "quality"
        const val K_COUNTDOWN = "countdown"
        const val K_BUTTON = "floating_button"
        const val K_EDIT_READY = "edit_ready"
        const val K_HIDE = "hide_button_while_recording"
    }
}
