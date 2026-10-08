package tf.monochrome.recorder.editor.model

import kotlinx.serialization.Serializable

/*
 * The editor's document. Plain immutable data with no android.* in it, so the
 * timeline operations, the export planner and undo are all JVM-testable, and
 * the whole thing round-trips through JSON for drafts.
 *
 * Times are microseconds throughout, matching Media3. A clip's *source* span
 * is the part of its file it plays (trimStart..trimEnd); its *duration* on the
 * timeline is that span divided by its speed.
 */

/** A media file as the editor knows it. Width and height are as displayed (rotation applied). */
@Serializable
data class MediaRef(
    val uri: String,
    val displayName: String,
    val durationUs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val hasVideo: Boolean = true,
    val hasAudio: Boolean = true,
    /** Frames per second while the picture moves; 0 when unknown (or from a draft made before this was read). */
    val frameRate: Float = 0f,
)

/** A piece of video on the main track. Clips play back to back, in order. */
@Serializable
data class VideoClip(
    val id: String,
    val source: MediaRef,
    val trimStartUs: Long,
    val trimEndUs: Long,
    val speed: Float = 1f,
    /** 0 = muted, 1 = as recorded, up to 2 = +6 dB. */
    val volume: Float = 1f,
    /** The sound has been split off onto an audio lane; this clip plays silent. */
    val audioDetached: Boolean = false,
) {
    val sourceSpanUs: Long get() = trimEndUs - trimStartUs
    val durationUs: Long get() = (sourceSpanUs / speed).toLong()

    /** Whether this clip puts its own sound on the main track. */
    val contributesAudio: Boolean get() = source.hasAudio && !audioDetached && volume > 0f
}

enum class AudioKind {
    /** Imported music or any audio file. */
    MUSIC,
    /** Sound separated from a video clip. */
    DETACHED,
}

/** A piece of audio placed freely on the timeline. Overlapping pieces mix. */
@Serializable
data class AudioClip(
    val id: String,
    val source: MediaRef,
    val trimStartUs: Long,
    val trimEndUs: Long,
    /** Where it starts on the timeline. */
    val startUs: Long,
    val volume: Float = 1f,
    val speed: Float = 1f,
    val fadeInUs: Long = 0,
    val fadeOutUs: Long = 0,
    val kind: AudioKind = AudioKind.MUSIC,
    /** For [AudioKind.DETACHED]: the clip it came from, so it can be put back. */
    val linkedClipId: String? = null,
) {
    val sourceSpanUs: Long get() = trimEndUs - trimStartUs
    val durationUs: Long get() = (sourceSpanUs / speed).toLong()
    val endUs: Long get() = startUs + durationUs
}

enum class TextPosition { TOP, CENTER, BOTTOM }

/** Text size relative to a 1080-pixel short side; scaled with the export. */
enum class TextSize(val scale: Float) { SMALL(0.45f), MEDIUM(0.65f), LARGE(0.9f), HUGE(1.25f) }

/** A title or caption shown over the video between two times. */
@Serializable
data class TextItem(
    val id: String,
    val text: String,
    val startUs: Long,
    val endUs: Long,
    val position: TextPosition = TextPosition.BOTTOM,
    val size: TextSize = TextSize.MEDIUM,
    /** ARGB. */
    val color: Long = 0xFFFFFFFF,
    /** A dark box behind the text, for legibility over busy footage. */
    val background: Boolean = true,
)

enum class Filter { NONE, MONO, SEPIA, VIVID, COOL, WARM, INVERT }

/** Colour and fades, applied to the whole video. */
@Serializable
data class VideoLook(
    val filter: Filter = Filter.NONE,
    /** -1..1, 0 = unchanged. */
    val brightness: Float = 0f,
    /** -1..1, 0 = unchanged. */
    val contrast: Float = 0f,
    /** -1..1, 0 = unchanged; -1 is greyscale. */
    val saturation: Float = 0f,
    /** Fade in from black at the start. */
    val fadeInUs: Long = 0,
    /** Fade out to black at the end. */
    val fadeOutUs: Long = 0,
    /** Film grain, 0 = none .. 1 = heavy. */
    val grain: Float = 0f,
    /** 0 = finest .. 1 = coarsest grain. */
    val grainSize: Float = DEFAULT_GRAIN_SIZE,
) {
    companion object {
        /** "Fine": grains of about 1.5 px on a 1080-pixel short side. */
        const val DEFAULT_GRAIN_SIZE = 0.15f
    }
}

enum class AspectPreset(val ratio: Float?) {
    ORIGINAL(null),
    WIDE(16f / 9f),
    TALL(9f / 16f),
    SQUARE(1f),
    PORTRAIT(4f / 5f),
    CLASSIC(4f / 3f),
}

/** Fractions of the source cut off each edge, before rotation. */
@Serializable
data class CropInsets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    val isNone: Boolean get() = left == 0f && top == 0f && right == 0f && bottom == 0f

    companion object {
        /** At least this much of either side survives a crop; any size and shape above it. */
        const val MIN_KEEP = 0.05f
    }
}

/** Frame geometry: shape of the output, crop, rotation and mirroring. */
@Serializable
data class CanvasSettings(
    val aspect: AspectPreset = AspectPreset.ORIGINAL,
    /** Fill the frame (cropping overflow) instead of fitting inside it (letterboxing). */
    val fill: Boolean = false,
    /** Clockwise: 0, 90, 180 or 270. */
    val rotation: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val crop: CropInsets = CropInsets(),
)

@Serializable
data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val clips: List<VideoClip> = emptyList(),
    val audio: List<AudioClip> = emptyList(),
    val texts: List<TextItem> = emptyList(),
    val look: VideoLook = VideoLook(),
    val canvas: CanvasSettings = CanvasSettings(),
)
