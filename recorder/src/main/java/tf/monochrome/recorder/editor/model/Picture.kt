package tf.monochrome.recorder.editor.model

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class PictureTransform(
    val zoom: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f,
    val rotation: Float = 0f,
)

@Serializable
enum class MotionEasing { LINEAR, SMOOTH, HOLD }

@Serializable
data class MotionKey(
    val sourceUs: Long,
    val transform: PictureTransform,
    val easing: MotionEasing = MotionEasing.SMOOTH,
)

@Serializable
data class ClipGrade(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val exposure: Float = 0f,
    val tint: Float = 0f,
    val lut: CubeLut? = null,
    val lutMix: Float = 1f,
    val bypass: Boolean = false,
)

@Serializable
data class ClipPicture(
    val transform: PictureTransform = PictureTransform(),
    val keys: List<MotionKey> = emptyList(),
    val grade: ClipGrade = ClipGrade(),
    val cover: CropRect? = null,
    val trackedCover: TrackedCover? = null,
) {
    fun at(sourceUs: Long): PictureTransform {
        if (keys.isEmpty()) return transform
        val index = keys.binarySearchBy(sourceUs) { it.sourceUs }
        if (index >= 0) return keys[index].transform
        val next = -index - 1
        if (next == 0) return keys.first().transform
        if (next == keys.size) return keys.last().transform
        val a = keys[next - 1]
        val b = keys[next]
        val t = (sourceUs - a.sourceUs).toFloat() / (b.sourceUs - a.sourceUs)
        val eased = when (a.easing) {
            MotionEasing.LINEAR -> t
            MotionEasing.SMOOTH -> t * t * (3 - 2 * t)
            MotionEasing.HOLD -> 0f
        }
        fun mix(from: Float, to: Float) = from + (to - from) * eased
        return PictureTransform(
            mix(a.transform.zoom, b.transform.zoom),
            mix(a.transform.x, b.transform.x),
            mix(a.transform.y, b.transform.y),
            mix(a.transform.rotation, b.transform.rotation),
        )
    }

    fun put(key: MotionKey): ClipPicture =
        copy(keys = (keys.filterNot { it.sourceUs == key.sourceUs } + key).sortedBy { it.sourceUs })
}

@Serializable
data class OverlayClip(
    val id: String,
    val source: MediaRef,
    val startUs: Long,
    val trimStartUs: Long,
    val trimEndUs: Long,
    val scale: Float = 0.33f,
    val x: Float = 0.62f,
    val y: Float = 0.62f,
    val opacity: Float = 1f,
    val layer: Int = 0,
) {
    val durationUs: Long get() = trimEndUs - trimStartUs
    val endUs: Long get() = startUs + durationUs
}

@Serializable
data class EditMarker(val id: String, val timeUs: Long)

fun VideoClip.sourceAt(offsetUs: Long): Long = timing.sourceAt(offsetUs)

fun Project.updateClipPicture(id: String, edit: (ClipPicture) -> ClipPicture): Project =
    copy(clips = clips.map { if (it.id == id) it.copy(picture = edit(it.picture)) else it })

const val OVERLAY_LAYERS = 3

fun Project.addOverlay(ref: MediaRef, playheadUs: Long, layer: Int = 0): Project {
    if (layer !in 0 until OVERLAY_LAYERS || !ref.hasVideo || ref.durationUs < MIN_CLIP_US || durationUs < MIN_CLIP_US) return this
    val start = playheadUs.coerceIn(0, durationUs - MIN_CLIP_US)
    val lane = overlays.filter { it.layer == layer }
    if (lane.any { start in it.startUs until it.endUs }) return this
    val end = lane.firstOrNull { it.startUs > start }?.startUs ?: durationUs
    val length = minOf(ref.durationUs, end - start, durationUs - start)
    if (length < MIN_CLIP_US) return this
    return copy(overlays = (overlays + OverlayClip(newId(), ref, start, 0, length, layer = layer)).sortedBy { it.startUs })
}

fun Project.updateOverlay(id: String, edit: (OverlayClip) -> OverlayClip): Project =
    copy(overlays = overlays.map { if (it.id == id) edit(it) else it })

fun Project.setOverlayLayer(id: String, layer: Int): Project {
    val clip = overlays.firstOrNull { it.id == id } ?: return this
    if (layer !in 0 until OVERLAY_LAYERS || overlays.any {
            it.id != id && it.layer == layer && it.startUs < clip.endUs && clip.startUs < it.endUs
        }
    ) return this
    return updateOverlay(id) { it.copy(layer = layer) }
}

fun Project.moveOverlay(id: String, requestedUs: Long): Project {
    val clip = overlays.firstOrNull { it.id == id } ?: return this
    val lane = overlays.filter { it.layer == clip.layer }
    val index = lane.indexOfFirst { it.id == id }
    val first = lane.getOrNull(index - 1)?.endUs ?: 0L
    val last = minOf(lane.getOrNull(index + 1)?.startUs ?: durationUs, durationUs) - clip.durationUs
    if (last < first) return this
    return updateOverlay(id) { it.copy(startUs = requestedUs.coerceIn(first, last)) }
        .let { it.copy(overlays = it.overlays.sortedBy(OverlayClip::startUs)) }
}

fun Project.trimOverlay(id: String, startUs: Long, endUs: Long): Project {
    val clip = overlays.firstOrNull { it.id == id } ?: return this
    val next = overlays.firstOrNull { it.layer == clip.layer && it.startUs > clip.startUs }?.startUs ?: durationUs
    val maxLength = minOf(next, durationUs) - clip.startUs
    if (maxLength < MIN_CLIP_US || clip.source.durationUs < MIN_CLIP_US) return this
    val start = startUs.coerceIn(0, clip.source.durationUs - MIN_CLIP_US)
    val end = endUs.coerceIn(start + MIN_CLIP_US, minOf(clip.source.durationUs, start + maxLength))
    return updateOverlay(id) { it.copy(trimStartUs = start, trimEndUs = end) }
}

fun Project.splitOverlay(id: String, playheadUs: Long): Project {
    val clip = overlays.firstOrNull { it.id == id } ?: return this
    val offset = playheadUs - clip.startUs
    if (offset < MIN_CLIP_US || clip.durationUs - offset < MIN_CLIP_US) return this
    val cut = clip.trimStartUs + offset
    return copy(overlays = overlays.flatMap {
        if (it.id != id) listOf(it)
        else listOf(it.copy(trimEndUs = cut), it.copy(id = newId(), startUs = playheadUs, trimStartUs = cut))
    }.sortedBy { it.startUs })
}

fun Project.toggleMarker(timeUs: Long): Project {
    val time = timeUs.coerceIn(0, durationUs)
    val current = markers.firstOrNull { abs(it.timeUs - time) <= 2_000 }
    val next = if (current == null) (markers + EditMarker(newId(), time)).sortedBy { it.timeUs }
    else markers.filterNot { it.id == current.id }
    return copy(markers = next)
}
