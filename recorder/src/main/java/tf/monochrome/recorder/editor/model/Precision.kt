package tf.monochrome.recorder.editor.model

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class CameraAngle(
    val id: String,
    val source: MediaRef,
    /** Alternate source timestamp minus the original source timestamp. */
    val offsetUs: Long = 0,
) {
    fun covers(startUs: Long, endUs: Long): Boolean =
        startUs + offsetUs >= 0 && endUs + offsetUs <= source.durationUs
}

val VideoClip.activeAngle: CameraAngle?
    get() = angles.firstOrNull { it.id == angleId && it.covers(trimStartUs, trimEndUs) }

fun Project.updateAngles(id: String, change: (VideoClip) -> VideoClip): Project =
    copy(clips = clips.map { if (it.id == id) change(it) else it })

/** Replaces picture from the playhead onward. The original source still supplies the sound. */
fun Project.cutToAngle(clipId: String, angleId: String?, playheadUs: Long): Project? {
    val i = clips.indexOfFirst { it.id == clipId }
    val clip = clips.getOrNull(i) ?: return null
    val start = clipStartUs(i)
    val offset = playheadUs - start
    if (offset >= clip.durationUs) return null
    if (angleId == clip.angleId) return this
    val cut = if (offset <= 0) this else splitAt(playheadUs).takeIf { it !== this } ?: return null
    val target = if (offset <= 0) clip else cut.clips[i + 1]
    if (angleId != null && target.angles.none { it.id == angleId && it.covers(target.trimStartUs, target.trimEndUs) }) {
        return null
    }
    if (target.picture.trackedCover != null) return null
    return cut.updateAngles(target.id) { it.copy(angleId = angleId) }
}

fun Project.slipClip(id: String, offsetUs: Long): Project? {
    val clip = clips.firstOrNull { it.id == id } ?: return null
    if (clip.speedKeys.isNotEmpty() || audio.any { it.linkedClipId == id }) return null
    val angle = clip.activeAngle
    val first = maxOf(0, -(angle?.offsetUs ?: 0))
    val last = minOf(clip.source.durationUs, angle?.let { it.source.durationUs - it.offsetUs } ?: Long.MAX_VALUE)
    val start = (clip.trimStartUs + offsetUs).coerceIn(first, last - clip.sourceSpanUs)
    return updateAngles(id) { it.copy(trimStartUs = start, trimEndUs = start + it.sourceSpanUs) }
}

/** Rolls the outgoing cut, keeping the combined duration and every other track in place. */
fun Project.rollCut(id: String, deltaUs: Long): Project? {
    val i = clips.indexOfFirst { it.id == id }
    val left = clips.getOrNull(i) ?: return null
    val right = clips.getOrNull(i + 1) ?: return null
    if (left.speedKeys.isNotEmpty() || right.speedKeys.isNotEmpty() ||
        audio.any { it.linkedClipId == left.id || it.linkedClipId == right.id }
    ) return null
    val leftEnd = left.activeAngle?.let { minOf(left.source.durationUs, it.source.durationUs - it.offsetUs) }
        ?: left.source.durationUs
    val rightStart = maxOf(0, -(right.activeAngle?.offsetUs ?: 0))
    val newEnd = left.trimEndUs + (deltaUs * left.speed.toDouble()).toLong()
    val newStart = right.trimStartUs + (deltaUs * right.speed.toDouble()).toLong()
    if (newEnd !in left.trimStartUs..leftEnd || newStart !in rightStart..right.trimEndUs) return null
    val newLeft = left.copy(trimEndUs = newEnd)
    val newRight = right.copy(trimStartUs = newStart)
    if (newLeft.durationUs < MIN_CLIP_US || newRight.durationUs < MIN_CLIP_US ||
        newLeft.durationUs + newRight.durationUs != left.durationUs + right.durationUs
    ) return null
    return copy(clips = clips.toMutableList().apply {
        set(i, newLeft)
        set(i + 1, newRight)
    })
}

/** Removes a primary clip and the matching time range from all timeline-placed tracks. */
fun Project.rippleDelete(id: String): Project {
    val index = clips.indexOfFirst { it.id == id }
    val clip = clips.getOrNull(index) ?: return this
    val start = clipStartUs(index)
    val end = start + clip.durationUs
    fun shifted(time: Long) = when {
        time < start -> time
        time < end -> start
        else -> time - clip.durationUs
    }
    fun survivors(a: Long, b: Long) = listOf(
        a to minOf(b, start),
        maxOf(a, end) to b,
    ).filter { (s, e) -> e > s }
    return copy(
        clips = clips.filterNot { it.id == id },
        audio = audio.flatMap { a ->
            survivors(a.startUs, a.endUs).mapIndexed { piece, (s, e) ->
                val first = a.timing.sourceAt(s - a.startUs)
                val last = a.timing.sourceAt(e - a.startUs)
                val half = ClipTiming(first, last, a.speed, a.speedKeys).durationUs / 2
                a.copy(
                    id = if (piece == 0) a.id else newId(),
                    trimStartUs = first, trimEndUs = last, startUs = shifted(s),
                    fadeInUs = if (s == a.startUs) minOf(a.fadeInUs, half) else 0,
                    fadeOutUs = if (e == a.endUs) minOf(a.fadeOutUs, half) else 0,
                    linkedClipId = a.linkedClipId?.takeUnless { it == id },
                )
            }.filter { it.durationUs > 0 }
        },
        overlays = overlays.flatMap { o ->
            survivors(o.startUs, o.endUs).mapIndexed { piece, (s, e) ->
                o.copy(
                    id = if (piece == 0) o.id else newId(),
                    startUs = shifted(s),
                    trimStartUs = o.trimStartUs + s - o.startUs,
                    trimEndUs = o.trimStartUs + e - o.startUs,
                )
            }
        }.sortedBy { it.startUs },
        texts = texts.mapNotNull { t ->
            val s = shifted(t.startUs)
            val e = shifted(t.endUs)
            t.copy(startUs = s, endUs = e).takeIf { e > s }
        },
        markers = markers.mapNotNull { m ->
            m.copy(timeUs = shifted(m.timeUs)).takeUnless { m.timeUs in start until end }
        },
    )
}

fun Project.snapToMarker(timeUs: Long, toleranceUs: Long): Long =
    markers.asSequence().map { it.timeUs }.filter { it in 0..durationUs }
        .minByOrNull { abs(it - timeUs) }?.takeIf { abs(it - timeUs) <= toleranceUs } ?: timeUs
