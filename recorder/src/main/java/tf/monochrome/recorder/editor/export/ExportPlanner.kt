package tf.monochrome.recorder.editor.export

import tf.monochrome.recorder.editor.model.AudioClip
import tf.monochrome.recorder.editor.model.CanvasSettings
import tf.monochrome.recorder.editor.model.Filter
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.model.TextPosition
import tf.monochrome.recorder.editor.model.assignLanes
import tf.monochrome.recorder.editor.model.durationUs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** What comes out of an export. The last two are the "separate video and sound" exports. */
enum class ExportMode {
    /** Picture and every sound, mixed. */
    VIDEO,
    /** Picture only — a silent MP4. */
    VIDEO_ONLY,
    /** Sound only — the mixed soundtrack as an M4A. */
    AUDIO_ONLY,
}

enum class ExportResolution(val shortSide: Int?) { ORIGINAL(null), P1080(1080), P720(720), P480(480) }

enum class ExportQuality(val bitsPerPixel: Double) { LOW(0.04), STANDARD(0.07), HIGH(0.12) }

data class ExportSettings(
    val mode: ExportMode = ExportMode.VIDEO,
    val resolution: ExportResolution = ExportResolution.ORIGINAL,
    val quality: ExportQuality = ExportQuality.STANDARD,
)

/** One piece of media in a sequence, already in Media3's terms. */
data class PlannedMedia(
    val uri: String,
    val sourceDurationUs: Long,
    val clipStartUs: Long,
    val clipEndUs: Long,
    val speed: Float,
    val removeAudio: Boolean,
    val removeVideo: Boolean,
    /** Linear gain, 0..2. */
    val gain: Float,
    /** Item-local, after speed. */
    val fadeInUs: Long,
    val fadeOutUs: Long,
    /** Where this item lands on the output timeline, and for how long. */
    val timelineStartUs: Long,
    val durationUs: Long,
)

sealed interface Segment {
    val durationUs: Long

    data class Media(val media: PlannedMedia) : Segment {
        override val durationUs: Long get() = media.durationUs
    }

    data class Gap(override val durationUs: Long) : Segment
}

data class PlannedText(
    val text: String,
    val startUs: Long,
    val endUs: Long,
    val position: TextPosition,
    /** Overlay scale relative to Media3's 100 px text. */
    val scale: Float,
    val color: Int,
    val background: Boolean,
)

/** Everything about the picture that is the same for every clip. */
data class PlannedVisuals(
    val width: Int,
    val height: Int,
    val fill: Boolean,
    val canvas: CanvasSettings,
    val filter: Filter,
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val fadeInUs: Long,
    val fadeOutUs: Long,
    val totalUs: Long,
    val texts: List<PlannedText>,
)

data class ExportPlan(
    val mode: ExportMode,
    /** The main track: the clips in order (gaps only in an audio-only export). */
    val primary: List<Segment>,
    val primaryHasVideo: Boolean,
    val primaryHasAudio: Boolean,
    /** Free-placed audio, packed into non-overlapping lanes, each led by a gap. */
    val lanes: List<List<Segment>>,
    /** Null for an audio-only export. */
    val visuals: PlannedVisuals?,
    val durationUs: Long,
    val videoBitrate: Int,
    val audioBitrate: Int,
) {
    val hasAudio: Boolean get() = primaryHasAudio || lanes.isNotEmpty()
}

sealed interface PlanResult {
    data class Ok(val plan: ExportPlan) : PlanResult
    data class Nothing(val reason: Reason) : PlanResult

    enum class Reason { NO_CLIPS, NO_AUDIO }
}

/**
 * Turns a [Project] into an [ExportPlan]. Pure, so every rule below is
 * covered by JVM tests; [CompositionFactory] maps the plan onto Media3.
 *
 * The rules that matter:
 *  - A clip whose sound is detached, muted, or excluded by the mode has its
 *    audio *removed*; Media3 fills the main track with silence for it, so
 *    later clips' sound stays in sync. A clip is never stripped of both.
 *  - Free audio is packed into lanes ([assignLanes]); gaps put each piece at
 *    its time. Anything past the end of the video is cut off there.
 *  - In an audio-only export the main track keeps its timing too: a clip with
 *    no sound to give becomes a gap of the same length.
 */
object ExportPlanner {
    /** Gaps shorter than this are rounding noise and are dropped. */
    private const val MIN_GAP_US = 1_000L
    private const val MIN_PIECE_US = 10_000L
    /** Common hardware encoders top out here. */
    private const val MAX_LONG_SIDE = 3840
    /** Frame rate assumed for a clip whose rate could not be read. */
    private const val NOMINAL_FPS = 60f
    private const val MAX_FPS = 240f

    /** AAC for every export, matching the recorder's 320 kbps. Media3's own default is 128. */
    const val AUDIO_BITRATE = 320_000

    fun plan(project: Project, settings: ExportSettings): PlanResult {
        if (project.clips.isEmpty()) return PlanResult.Nothing(PlanResult.Reason.NO_CLIPS)
        val total = project.durationUs
        val mode = settings.mode
        val keepClipAudio = mode != ExportMode.VIDEO_ONLY

        var cursor = 0L
        val primary = project.clips.map { c ->
            val start = cursor
            cursor += c.durationUs
            val withSound = keepClipAudio && c.contributesAudio
            if (mode == ExportMode.AUDIO_ONLY && !withSound) {
                Segment.Gap(c.durationUs)
            } else {
                Segment.Media(
                    PlannedMedia(
                        uri = c.source.uri,
                        sourceDurationUs = c.source.durationUs,
                        clipStartUs = c.trimStartUs,
                        clipEndUs = c.trimEndUs,
                        speed = c.speed,
                        removeAudio = c.source.hasAudio && !withSound,
                        removeVideo = mode == ExportMode.AUDIO_ONLY,
                        gain = if (withSound) c.volume else 1f,
                        fadeInUs = 0,
                        fadeOutUs = 0,
                        timelineStartUs = start,
                        durationUs = c.durationUs,
                    ),
                )
            }
        }
        val primaryHasAudio = keepClipAudio && project.clips.any { it.contributesAudio }
        val lanes = if (keepClipAudio) lanes(project.audio, total) else emptyList()

        if (mode == ExportMode.AUDIO_ONLY) {
            if (!primaryHasAudio && lanes.isEmpty()) return PlanResult.Nothing(PlanResult.Reason.NO_AUDIO)
            return PlanResult.Ok(
                ExportPlan(
                    mode = mode,
                    // A main track of nothing but gaps adds nothing; the lanes carry their own timing.
                    primary = if (primaryHasAudio) primary else emptyList(),
                    primaryHasVideo = false,
                    primaryHasAudio = primaryHasAudio,
                    lanes = lanes,
                    visuals = null,
                    durationUs = total,
                    videoBitrate = 0,
                    audioBitrate = AUDIO_BITRATE,
                ),
            )
        }

        val first = project.clips.first().source
        val (w, h) = canvasSize(first.width, first.height, project.canvas, settings.resolution)
        val visuals = PlannedVisuals(
            width = w,
            height = h,
            fill = project.canvas.fill,
            canvas = project.canvas,
            filter = project.look.filter,
            brightness = project.look.brightness,
            contrast = project.look.contrast,
            saturation = project.look.saturation,
            fadeInUs = project.look.fadeInUs.coerceIn(0, total / 2),
            fadeOutUs = project.look.fadeOutUs.coerceIn(0, total / 2),
            totalUs = total,
            texts = project.texts.mapNotNull { t ->
                val s = t.startUs.coerceIn(0, total)
                val e = t.endUs.coerceIn(0, total)
                if (t.text.isBlank() || e - s <= 0) null
                else PlannedText(
                    text = t.text,
                    startUs = s,
                    endUs = e,
                    position = t.position,
                    scale = t.size.scale * min(w, h) / 1080f,
                    color = t.color.toInt(),
                    background = t.background,
                )
            },
        )
        return PlanResult.Ok(
            ExportPlan(
                mode = mode,
                primary = primary,
                primaryHasVideo = true,
                primaryHasAudio = primaryHasAudio,
                lanes = lanes,
                visuals = visuals,
                durationUs = total,
                videoBitrate = bitrate(w, h, settings.quality, outputFrameRate(project)),
                audioBitrate = AUDIO_BITRATE,
            ),
        )
    }

    /** Free audio, cut off at [limitUs] and packed into gap-led lanes. */
    fun lanes(pieces: List<AudioClip>, limitUs: Long): List<List<Segment>> {
        val usable = pieces.mapNotNull { clampTo(it, limitUs) }.filter { it.volume > 0f }
        return assignLanes(usable).map { lane ->
            val out = mutableListOf<Segment>()
            var cursor = 0L
            for (a in lane) {
                val gap = a.startUs - cursor
                if (gap >= MIN_GAP_US) out += Segment.Gap(gap)
                out += Segment.Media(
                    PlannedMedia(
                        uri = a.source.uri,
                        sourceDurationUs = a.source.durationUs,
                        clipStartUs = a.trimStartUs,
                        clipEndUs = a.trimEndUs,
                        speed = a.speed,
                        removeAudio = false,
                        removeVideo = a.source.hasVideo,
                        gain = a.volume,
                        fadeInUs = a.fadeInUs,
                        fadeOutUs = a.fadeOutUs,
                        timelineStartUs = a.startUs,
                        durationUs = a.durationUs,
                    ),
                )
                cursor = a.startUs + a.durationUs
            }
            out
        }
    }

    private fun clampTo(a: AudioClip, limitUs: Long): AudioClip? {
        if (a.startUs >= limitUs) return null
        if (a.endUs <= limitUs) return a
        val keepUs = limitUs - a.startUs
        if (keepUs < MIN_PIECE_US) return null
        val trimmed = a.copy(trimEndUs = a.trimStartUs + (keepUs * a.speed).roundToLong())
        val half = trimmed.durationUs / 2
        return trimmed.copy(fadeInUs = a.fadeInUs.coerceAtMost(half), fadeOutUs = a.fadeOutUs.coerceAtMost(half))
    }

    /**
     * Output frame size. Starts from the first clip as it will look after
     * crop and rotation, takes the chosen aspect (or keeps that one), and
     * sets the short side from the chosen resolution (or keeps the source's).
     */
    fun canvasSize(sourceW: Int, sourceH: Int, canvas: CanvasSettings, resolution: ExportResolution): Pair<Int, Int> {
        val w0 = if (sourceW > 0) sourceW else 1920
        val h0 = if (sourceH > 0) sourceH else 1080
        val crop = canvas.crop
        var bw = w0 * (1f - crop.left - crop.right)
        var bh = h0 * (1f - crop.top - crop.bottom)
        if (canvas.rotation % 180 != 0) bw = bh.also { bh = bw }

        val aspect = canvas.aspect.ratio ?: (bw / bh)
        val short = resolution.shortSide?.toFloat() ?: min(bw, bh)
        var w = if (aspect >= 1f) short * aspect else short
        var h = if (aspect >= 1f) short else short / aspect
        val long = max(w, h)
        if (long > MAX_LONG_SIDE) {
            val k = MAX_LONG_SIDE / long
            w *= k
            h *= k
        }
        return even(w) to even(h)
    }

    private fun even(v: Float): Int = max(2, (v.roundToInt() / 2) * 2)

    /**
     * Frames per second the export will carry: Transformer keeps every source
     * frame, so it is the fastest clip's rate times its speed (a 165 fps clip
     * at 2× arrives at 330, held here to [MAX_FPS]).
     */
    fun outputFrameRate(project: Project): Float =
        project.clips.maxOfOrNull { c -> (if (c.source.frameRate > 0f) c.source.frameRate else NOMINAL_FPS) * c.speed }
            ?.coerceIn(24f, MAX_FPS) ?: NOMINAL_FPS

    /** Sized for the frame rate actually exported, so a 165 fps recording keeps the bits it needs. */
    fun bitrate(w: Int, h: Int, quality: ExportQuality, fps: Float): Int =
        (w.toLong() * h * fps * quality.bitsPerPixel).toLong().coerceIn(1_000_000L, 50_000_000L).toInt()

    /** One 16-bit sample times [gain], saturating rather than wrapping. */
    fun scaleSample(sample: Short, gain: Float): Short =
        (sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    /** Linear fade gain at [posUs] of something [durUs] long. Shared by the video fades and the audio envelopes. */
    fun fadeGain(posUs: Long, durUs: Long, fadeInUs: Long, fadeOutUs: Long): Float {
        var g = 1f
        if (fadeInUs > 0 && posUs < fadeInUs) g = min(g, posUs.coerceAtLeast(0).toFloat() / fadeInUs)
        val fromEnd = durUs - posUs
        if (fadeOutUs > 0 && fromEnd < fadeOutUs) g = min(g, fromEnd.coerceAtLeast(0).toFloat() / fadeOutUs)
        return g.coerceIn(0f, 1f)
    }
}
