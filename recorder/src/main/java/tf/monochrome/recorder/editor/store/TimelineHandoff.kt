package tf.monochrome.recorder.editor.store

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tf.monochrome.recorder.editor.export.ExportPlanner
import tf.monochrome.recorder.editor.export.ExportSettings
import tf.monochrome.recorder.editor.export.PlanResult
import tf.monochrome.recorder.editor.export.PlannedMedia
import tf.monochrome.recorder.editor.export.Segment
import tf.monochrome.recorder.editor.model.MediaRef
import tf.monochrome.recorder.editor.model.Project

/** OpenTimelineIO cut and relative original-media references for a portable bundle. */
object TimelineHandoff {
    val notes = """
        Extract the entire archive before opening timeline.otio. The media folder contains original
        files, including footage covered by redaction in Recorder. Do not share the bundle as a
        redacted deliverable. Export a rendered MP4 for that purpose.

        The OTIO timeline carries media, cuts, gaps, layers, and linear speed segments. Mixer
        processing, color, framing, overlays' transforms, masks, titles, and redaction are stored in
        project.json for Recorder. A receiving editor may need manual relink and may ignore time
        warp. Captions, when present, are in captions.srt.
    """.trimIndent()

    fun create(project: Project): String {
        val plan = (ExportPlanner.plan(project, ExportSettings()) as PlanResult.Ok).plan
        val fps = project.frameRate.numerator.toDouble() / project.frameRate.denominator
        val refs = buildList {
            project.clips.forEach { clip -> add(clip.source); clip.angles.forEach { add(it.source) } }
            addAll(project.audio.map { it.source })
            addAll(project.overlays.map { it.source })
        }.associateBy { it.uri }
        val tracks = buildList {
            add(track("V1", "Video", sequence(plan.primary, refs, fps)))
            for ((layer, clips) in plan.overlays.groupBy { it.layer }.toSortedMap()) {
                val children = mutableListOf<JsonObject>()
                var cursor = 0L
                for (overlay in clips) {
                    if (overlay.startUs > cursor) children += gap(overlay.startUs - cursor, fps)
                    children += clip(refs.getValue(overlay.source.uri), overlay.trimStartUs, overlay.durationUs, 1.0, fps)
                    cursor = overlay.endUs
                }
                add(track("V${layer + 2}", "Video", children))
            }
            if (plan.primaryHasAudio) add(track("A1", "Audio", sequence(plan.primary, refs, fps, audio = true)))
            plan.lanes.forEachIndexed { index, lane ->
                add(track("A${index + 2}", "Audio", sequence(lane, refs, fps)))
            }
        }
        return buildJsonObject {
            put("OTIO_SCHEMA", "Timeline.1")
            put("name", project.name)
            put("metadata", emptyMetadata)
            put("global_start_time", time(0, fps))
            put("tracks", buildJsonObject {
                item("Stack.1", "tracks", null)
                put("children", JsonArray(tracks))
            })
        }.toString()
    }

    private fun sequence(
        segments: List<Segment>, refs: Map<String, MediaRef>, fps: Double, audio: Boolean = false,
    ): List<JsonObject> = buildList {
        for (segment in segments) {
            val media = (segment as? Segment.Media)?.media
            if (media == null || (audio && (media.removeAudio || !refs.getValue(media.uri).hasAudio))) add(gap(segment.durationUs, fps))
            else addAll(clipSegments(media, refs.getValue(media.uri), fps))
        }
    }

    private fun clipSegments(media: PlannedMedia, ref: MediaRef, fps: Double): List<JsonObject> = buildList {
        val first = media.pictureSourceStartUs
        val last = first + media.clipEndUs - media.clipStartUs
        var start = first
        while (start < last) {
            val end = media.timing.nextChangeAfter(start) ?: last
            val duration = media.timing.timelineAt(end) - media.timing.timelineAt(start)
            if (duration > 0) {
                val speed = if (media.speedKeys.isEmpty()) media.speed.toDouble() else (end - start).toDouble() / duration
                add(clip(ref, media.clipStartUs + start - first, duration, speed, fps))
            }
            start = end
        }
    }

    private fun clip(ref: MediaRef, startUs: Long, durationUs: Long, speed: Double, fps: Double): JsonObject = buildJsonObject {
        val effects = if (speed == 1.0) empty else JsonArray(listOf(buildJsonObject {
            put("OTIO_SCHEMA", "LinearTimeWarp.1")
            put("metadata", emptyMetadata)
            put("name", "")
            put("effect_name", "LinearTimeWarp")
            put("enabled", true)
            put("time_scalar", speed)
        }))
        item("Clip.2", ref.displayName, range(startUs, durationUs, fps), effects)
        put("active_media_reference_key", "DEFAULT_MEDIA")
        put("media_references", buildJsonObject {
            put("DEFAULT_MEDIA", buildJsonObject {
                put("OTIO_SCHEMA", "ExternalReference.1")
                put("metadata", emptyMetadata)
                put("name", ref.displayName)
                put("available_range", range(0, ref.durationUs, fps))
                put("available_image_bounds", JsonNull)
                put("target_url", ref.uri)
            })
        })
    }

    private fun gap(durationUs: Long, fps: Double): JsonObject =
        buildJsonObject { item("Gap.1", "", range(0, durationUs, fps)) }

    private fun track(name: String, kind: String, children: List<JsonObject>): JsonObject = buildJsonObject {
        item("Track.1", name, null)
        put("kind", kind)
        put("children", JsonArray(children))
    }

    private fun range(startUs: Long, durationUs: Long, fps: Double): JsonObject = buildJsonObject {
        put("OTIO_SCHEMA", "TimeRange.1")
        put("start_time", time(startUs, fps))
        put("duration", time(durationUs, fps))
    }

    private fun time(us: Long, fps: Double): JsonObject = buildJsonObject {
        put("OTIO_SCHEMA", "RationalTime.1")
        put("rate", fps)
        put("value", us / 1_000_000.0 * fps)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.item(
        schema: String, name: String, source: JsonObject?, effects: JsonArray = empty,
    ) {
        put("OTIO_SCHEMA", schema)
        put("metadata", emptyMetadata)
        put("name", name)
        put("source_range", source ?: JsonNull)
        put("effects", effects)
        put("markers", empty)
        put("enabled", true)
        put("color", JsonNull)
    }

    private val empty = JsonArray(emptyList())
    private val emptyMetadata = JsonObject(emptyMap())
}
