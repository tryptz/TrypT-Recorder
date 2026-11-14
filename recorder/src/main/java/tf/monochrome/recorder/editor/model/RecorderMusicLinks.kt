package tf.monochrome.recorder.editor.model

import androidx.annotation.StringRes
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tf.monochrome.recorder.R

internal enum class RecorderLinkTarget(val key: String, @get:StringRes val label: Int) {
    BRIGHTNESS("recorder.brightness", R.string.music_links_brightness),
    CONTRAST("recorder.contrast", R.string.music_links_contrast),
    SATURATION("recorder.saturation", R.string.music_links_saturation),
    EXPOSURE("recorder.exposure", R.string.music_links_exposure),
    ;

    fun default() = VisualLink(key, LinkShape(lowest = 0f, highest = 0.35f))
}

internal object RecorderMusicLinks {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Serializable
    private data class Preset(
        val type: String = "",
        val version: Int = 0,
        val links: List<VisualLink> = emptyList(),
    )

    fun readPreset(text: String): List<VisualLink> {
        val preset = json.decodeFromString<Preset>(text)
        require(preset.type == "tryptify-music-links" && preset.version == 1)
        return preset.links.take(100).filter { it.target.isNotBlank() }.also { require(it.isNotEmpty()) }
    }

    fun remap(link: VisualLink, destination: RecorderLinkTarget): VisualLink {
        val old = link.shape
        val shape = if (old.lowest in -1f..1f && old.highest in -1f..1f) old
        else old.copy(lowest = 0f, highest = 0.35f)
        return link.copy(target = destination.key, shape = shape.clamped(-1f, 1f))
    }

    fun encode(links: List<VisualLink>): String = json.encodeToString(clean(links))
    fun decode(text: String): List<VisualLink> =
        runCatching { clean(json.decodeFromString<List<VisualLink>>(text)) }.getOrDefault(emptyList())

    fun clean(links: List<VisualLink>): List<VisualLink> = links
        .filter { link -> RecorderLinkTarget.entries.any { it.key == link.target } }
        .distinctBy { it.target }
        .map { link -> link.copy(shape = link.shape.clamped(-1f, 1f)) }
}
