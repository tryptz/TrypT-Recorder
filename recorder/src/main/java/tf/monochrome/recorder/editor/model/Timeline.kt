package tf.monochrome.recorder.editor.model

import java.util.UUID
import kotlin.math.roundToLong

/*
 * Editing operations. Every one is a pure function from a Project to a new
 * Project — that is what makes undo a list of old values and lets the tests
 * cover the editor without a device. An operation that does not apply (an
 * unknown id, a split too close to an edge) returns the project unchanged.
 */

/** Nothing on the timeline is shorter than this, so every piece stays visible and selectable. */
const val MIN_CLIP_US = 100_000L

val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)
const val MAX_VOLUME = 2f

fun newId(): String = UUID.randomUUID().toString()

// ── Main track ─────────────────────────────────────────────────────────

val Project.durationUs: Long get() = clips.sumOf { it.durationUs }

fun Project.clipStartUs(index: Int): Long = clips.take(index).sumOf { it.durationUs }

fun Project.clipStartUs(id: String): Long {
    val i = clips.indexOfFirst { it.id == id }
    return if (i < 0) 0 else clipStartUs(i)
}

/** Index of the clip playing at [timeUs]; the last clip at or past the end; -1 if there are none. */
fun Project.clipIndexAt(timeUs: Long): Int {
    if (clips.isEmpty()) return -1
    var start = 0L
    clips.forEachIndexed { i, c ->
        if (timeUs < start + c.durationUs) return i
        start += c.durationUs
    }
    return clips.lastIndex
}

fun Project.addClips(refs: List<MediaRef>): Project = copy(
    clips = clips + refs.filter { it.hasVideo && it.durationUs >= MIN_CLIP_US }.map { ref ->
        VideoClip(id = newId(), source = ref, trimStartUs = 0, trimEndUs = ref.durationUs)
    },
)

/** Cuts the clip under the playhead in two. */
fun Project.splitAt(timeUs: Long): Project {
    val i = clipIndexAt(timeUs)
    if (i < 0) return this
    val c = clips[i]
    val offsetOnTimeline = timeUs - clipStartUs(i)
    if (offsetOnTimeline < MIN_CLIP_US || c.durationUs - offsetOnTimeline < MIN_CLIP_US) return this
    val cut = c.trimStartUs + (offsetOnTimeline * c.speed).roundToLong()
    val left = c.copy(trimEndUs = cut)
    val right = c.copy(id = newId(), trimStartUs = cut)
    return copy(clips = clips.toMutableList().apply { set(i, left); add(i + 1, right) })
}

fun Project.trimClip(id: String, startUs: Long, endUs: Long): Project = updateClip(id) { c ->
    val s = startUs.coerceIn(0, c.source.durationUs - minSourceSpan(c.speed))
    val e = endUs.coerceIn(s + minSourceSpan(c.speed), c.source.durationUs)
    c.copy(trimStartUs = s, trimEndUs = e)
}

/** Trims the clip's start so that it begins where the playhead is now. */
fun Project.trimStartToPlayhead(id: String, timeUs: Long): Project {
    val i = clips.indexOfFirst { it.id == id }
    if (i < 0) return this
    val c = clips[i]
    val offset = timeUs - clipStartUs(i)
    if (offset <= 0 || offset >= c.durationUs) return this
    return trimClip(id, c.trimStartUs + (offset * c.speed).roundToLong(), c.trimEndUs)
}

/** Trims the clip's end so that it stops where the playhead is now. */
fun Project.trimEndToPlayhead(id: String, timeUs: Long): Project {
    val i = clips.indexOfFirst { it.id == id }
    if (i < 0) return this
    val c = clips[i]
    val offset = timeUs - clipStartUs(i)
    if (offset <= 0 || offset >= c.durationUs) return this
    return trimClip(id, c.trimStartUs, c.trimStartUs + (offset * c.speed).roundToLong())
}

fun Project.setClipSpeed(id: String, speed: Float): Project = updateClip(id) { c ->
    val s = speed.coerceIn(SPEEDS.first(), SPEEDS.last())
    // A faster clip must still be at least MIN_CLIP_US long on the timeline.
    if (c.sourceSpanUs / s < MIN_CLIP_US) c else c.copy(speed = s)
}

fun Project.setClipVolume(id: String, volume: Float): Project =
    updateClip(id) { it.copy(volume = volume.coerceIn(0f, MAX_VOLUME)) }

fun Project.deleteClip(id: String): Project = copy(clips = clips.filterNot { it.id == id })

fun Project.duplicateClip(id: String): Project {
    val i = clips.indexOfFirst { it.id == id }
    if (i < 0) return this
    return copy(clips = clips.toMutableList().apply { add(i + 1, clips[i].copy(id = newId())) })
}

/** Moves a clip [by] places along the main track (negative is earlier). */
fun Project.moveClip(id: String, by: Int): Project {
    val i = clips.indexOfFirst { it.id == id }
    if (i < 0) return this
    val j = (i + by).coerceIn(0, clips.lastIndex)
    if (i == j) return this
    return copy(clips = clips.toMutableList().apply { add(j, removeAt(i)) })
}

/**
 * Separates a clip's sound from its picture: the clip goes silent and its
 * audio becomes an independent piece on an audio lane, lined up exactly
 * where it was. From there it can be moved, trimmed, faded, split, deleted
 * or exported on its own.
 */
fun Project.detachAudio(id: String): Project {
    val i = clips.indexOfFirst { it.id == id }
    if (i < 0) return this
    val c = clips[i]
    if (!c.source.hasAudio || c.audioDetached) return this
    val piece = AudioClip(
        id = newId(),
        source = c.source,
        trimStartUs = c.trimStartUs,
        trimEndUs = c.trimEndUs,
        startUs = clipStartUs(i),
        // A muted clip's sound is detached in order to work with it, so it comes back audible.
        volume = if (c.volume > 0f) c.volume else 1f,
        speed = c.speed,
        kind = AudioKind.DETACHED,
        linkedClipId = c.id,
    )
    return copy(
        clips = clips.toMutableList().apply { set(i, c.copy(audioDetached = true)) },
        audio = audio + piece,
    )
}

/** Undoes [detachAudio]: drops every piece split off this clip and gives it its sound back. */
fun Project.restoreAudio(id: String): Project {
    if (clips.none { it.id == id && it.audioDetached }) return this
    return copy(
        clips = clips.map { if (it.id == id) it.copy(audioDetached = false) else it },
        audio = audio.filterNot { it.linkedClipId == id },
    )
}

private fun minSourceSpan(speed: Float) = (MIN_CLIP_US * speed).roundToLong()

private inline fun Project.updateClip(id: String, f: (VideoClip) -> VideoClip): Project =
    copy(clips = clips.map { if (it.id == id) f(it) else it })

// ── Audio lanes ────────────────────────────────────────────────────────

fun Project.addAudio(ref: MediaRef, atUs: Long): Project {
    if (!ref.hasAudio || ref.durationUs < MIN_CLIP_US) return this
    return copy(
        audio = audio + AudioClip(
            id = newId(),
            source = ref,
            trimStartUs = 0,
            trimEndUs = ref.durationUs,
            startUs = atUs.coerceAtLeast(0),
        ),
    )
}

fun Project.moveAudio(id: String, startUs: Long): Project = updateAudio(id) { it.copy(startUs = startUs.coerceAtLeast(0)) }

fun Project.trimAudio(id: String, startUs: Long, endUs: Long): Project = updateAudio(id) { a ->
    val s = startUs.coerceIn(0, a.source.durationUs - minSourceSpan(a.speed))
    val e = endUs.coerceIn(s + minSourceSpan(a.speed), a.source.durationUs)
    // Trimming the head keeps the remaining sound where it was on the timeline.
    val shift = ((s - a.trimStartUs) / a.speed).toLong()
    a.copy(trimStartUs = s, trimEndUs = e, startUs = (a.startUs + shift).coerceAtLeast(0)).clampFades()
}

fun Project.setAudioVolume(id: String, volume: Float): Project =
    updateAudio(id) { it.copy(volume = volume.coerceIn(0f, MAX_VOLUME)) }

fun Project.setAudioFades(id: String, fadeInUs: Long, fadeOutUs: Long): Project =
    updateAudio(id) { it.copy(fadeInUs = fadeInUs.coerceAtLeast(0), fadeOutUs = fadeOutUs.coerceAtLeast(0)).clampFades() }

fun Project.splitAudioAt(id: String, timeUs: Long): Project {
    val i = audio.indexOfFirst { it.id == id }
    if (i < 0) return this
    val a = audio[i]
    val offset = timeUs - a.startUs
    if (offset < MIN_CLIP_US || a.durationUs - offset < MIN_CLIP_US) return this
    val cut = a.trimStartUs + (offset * a.speed).roundToLong()
    val left = a.copy(trimEndUs = cut, fadeOutUs = 0).clampFades()
    val right = a.copy(id = newId(), trimStartUs = cut, startUs = a.startUs + offset, fadeInUs = 0).clampFades()
    return copy(audio = audio.toMutableList().apply { set(i, left); add(i + 1, right) })
}

/**
 * Deleting the last piece split off a clip leaves that clip silent — which is
 * what "delete the sound" means. [restoreAudio] still brings it back.
 */
fun Project.deleteAudio(id: String): Project = copy(audio = audio.filterNot { it.id == id })

/** Fades never overlap: each is held to half the piece. */
private fun AudioClip.clampFades(): AudioClip {
    val half = durationUs / 2
    return copy(fadeInUs = fadeInUs.coerceIn(0, half), fadeOutUs = fadeOutUs.coerceIn(0, half))
}

private inline fun Project.updateAudio(id: String, f: (AudioClip) -> AudioClip): Project =
    copy(audio = audio.map { if (it.id == id) f(it) else it })

/**
 * Packs overlapping audio pieces into lanes: each lane is a run of pieces
 * that never overlap, which is what one Media3 sequence can play. Pieces go
 * in the first lane that is free at their start.
 */
fun assignLanes(pieces: List<AudioClip>): List<List<AudioClip>> {
    val lanes = mutableListOf<MutableList<AudioClip>>()
    val laneEnds = mutableListOf<Long>()
    for (a in pieces.sortedWith(compareBy({ it.startUs }, { it.id }))) {
        val free = laneEnds.indexOfFirst { it <= a.startUs }
        if (free >= 0) {
            lanes[free] += a
            laneEnds[free] = a.endUs
        } else {
            lanes += mutableListOf(a)
            laneEnds += a.endUs
        }
    }
    return lanes
}

// ── Text ───────────────────────────────────────────────────────────────

fun Project.addText(text: String, atUs: Long, lengthUs: Long = 3_000_000): Project {
    val end = durationUs
    val start = atUs.coerceIn(0, (end - MIN_CLIP_US).coerceAtLeast(0))
    val stop = (start + lengthUs).coerceAtMost(end.coerceAtLeast(start + MIN_CLIP_US))
    return copy(texts = texts + TextItem(id = newId(), text = text, startUs = start, endUs = stop))
}

fun Project.updateText(id: String, f: (TextItem) -> TextItem): Project = copy(
    texts = texts.map { t ->
        if (t.id != id) t else f(t).let { n ->
            val s = n.startUs.coerceAtLeast(0)
            n.copy(startUs = s, endUs = n.endUs.coerceAtLeast(s + MIN_CLIP_US))
        }
    },
)

fun Project.deleteText(id: String): Project = copy(texts = texts.filterNot { it.id == id })
