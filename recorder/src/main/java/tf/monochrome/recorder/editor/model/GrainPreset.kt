package tf.monochrome.recorder.editor.model

/**
 * Starting points for film grain, after the film formats they resemble:
 * the smaller the frame of film, the larger and more visible its grain, and
 * colour stocks' blue layer is the grainiest. Any change afterwards makes the
 * grain custom.
 */
enum class GrainPreset(val amount: Float, val size: Float, val detail: GrainDetail) {
    FINE(0.25f, 0.10f, GrainDetail(sharpness = 0.2f, texture = 0.4f, colour = 0.1f, shadows = 0.3f, highlights = 0.25f, blue = 1.1f)),
    MM35(0.40f, 0.25f, GrainDetail(sharpness = 0.3f, texture = 0.5f, colour = 0.2f, shadows = 0.35f, highlights = 0.3f, red = 0.9f, blue = 1.25f)),
    MM16(0.60f, 0.50f, GrainDetail(sharpness = 0.4f, texture = 0.6f, colour = 0.3f, shadows = 0.45f, highlights = 0.35f, red = 0.9f, blue = 1.3f)),
    SUPER8(0.85f, 0.80f, GrainDetail(sharpness = 0.5f, texture = 0.7f, colour = 0.45f, shadows = 0.55f, highlights = 0.4f, red = 0.9f, blue = 1.35f)),
    /** Black-and-white stock: crisp, gritty, no colour. */
    MONO(0.55f, 0.40f, GrainDetail(sharpness = 0.6f, texture = 0.7f, shadows = 0.5f, highlights = 0.3f)),
    ;

    /** [look] with this grain; the rest of the look is untouched. Motion is kept: it is a separate choice. */
    fun applyTo(look: VideoLook): VideoLook =
        look.copy(grain = amount, grainSize = size, grainDetail = detail.copy(motion = look.grainDetail.motion))

    /** Whether [look]'s grain is exactly this preset. */
    fun matches(look: VideoLook): Boolean =
        look.grain == amount && look.grainSize == size && look.grainDetail.copy(motion = GrainMotion.EVERY_FRAME) == detail
}
