@file:OptIn(UnstableApi::class)

package tf.monochrome.recorder.editor.export

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import tf.monochrome.recorder.editor.model.Filter

/**
 * Maps an [ExportPlan] onto a Media3 [Composition]. The same Composition is
 * played by the editor's CompositionPlayer and rendered by Transformer, so
 * the preview is the export.
 *
 * Every per-item effect list is built fresh: overlays hold GL textures that
 * are released with the item's shader program, and must not be shared.
 */
object CompositionFactory {
    fun build(plan: ExportPlan): Composition {
        val sequences = mutableListOf<EditedMediaItemSequence>()
        if (plan.primary.isNotEmpty()) {
            val types = buildSet {
                if (plan.primaryHasVideo) add(C.TRACK_TYPE_VIDEO)
                if (plan.primaryHasAudio) add(C.TRACK_TYPE_AUDIO)
            }
            sequences += sequence(types, plan.primary) { videoEffects(plan.visuals) }
        }
        for (lane in plan.lanes) {
            sequences += sequence(setOf(C.TRACK_TYPE_AUDIO), lane) { emptyList() }
        }
        return Composition.Builder(sequences).build()
    }

    private fun sequence(
        types: Set<Int>,
        segments: List<Segment>,
        effects: (PlannedMedia) -> List<Effect>,
    ): EditedMediaItemSequence {
        val b = EditedMediaItemSequence.Builder(types)
        for (s in segments) {
            when (s) {
                is Segment.Gap -> b.addGap(s.durationUs)
                is Segment.Media -> b.addItem(item(s.media, if (s.media.removeVideo) emptyList() else effects(s.media)))
            }
        }
        return b.build()
    }

    private fun item(m: PlannedMedia, videoEffects: List<Effect>): EditedMediaItem {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionUs(m.clipStartUs)
            // Up to the end of the file: let the file say where that is, rather
            // than trusting a millisecond-rounded duration from its metadata.
            .setEndPositionUs(if (m.clipEndUs >= m.sourceDurationUs) C.TIME_END_OF_SOURCE else m.clipEndUs)
            .build()
        val mediaItem = MediaItem.Builder().setUri(m.uri).setClippingConfiguration(clipping).build()
        val audio = if (!m.removeAudio && (m.gain != 1f || m.fadeInUs > 0 || m.fadeOutUs > 0)) {
            listOf(VolumeEnvelope(m.gain, m.fadeInUs, m.fadeOutUs, m.durationUs))
        } else {
            emptyList()
        }
        return EditedMediaItem.Builder(mediaItem)
            // CompositionPlayer needs every item's length up front.
            .setDurationUs(m.sourceDurationUs)
            .setRemoveAudio(m.removeAudio)
            .setRemoveVideo(m.removeVideo)
            .setEffects(Effects(audio, videoEffects))
            .apply { if (m.speed != 1f) setSpeed(ConstantSpeed(m.speed)) }
            .build()
    }

    /**
     * Geometry first (crop, rotate/flip, fit to the canvas), then colour at
     * output size where it is cheapest, then fades and titles on top.
     */
    fun videoEffects(v: PlannedVisuals?): List<Effect> {
        if (v == null) return emptyList()
        val out = mutableListOf<Effect>()
        val crop = v.canvas.crop
        if (!crop.isNone) {
            // Normalised device coordinates: -1..1 on each axis, y up.
            out += Crop(
                -1f + 2f * crop.left,
                1f - 2f * crop.right,
                -1f + 2f * crop.bottom,
                1f - 2f * crop.top,
            )
        }
        val rotation = ((v.canvas.rotation % 360) + 360) % 360
        if (rotation != 0 || v.canvas.flipHorizontal || v.canvas.flipVertical) {
            out += ScaleAndRotateTransformation.Builder()
                .setScale(if (v.canvas.flipHorizontal) -1f else 1f, if (v.canvas.flipVertical) -1f else 1f)
                // Media3 rotates counter-clockwise; the editor's rotation is clockwise.
                .setRotationDegrees(((360 - rotation) % 360).toFloat())
                .build()
        }
        out += Presentation.createForWidthAndHeight(
            v.width,
            v.height,
            if (v.fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT,
        )
        when (v.filter) {
            Filter.NONE -> Unit
            Filter.MONO -> out += RgbFilter.createGrayscaleFilter()
            Filter.INVERT -> out += RgbFilter.createInvertedFilter()
            Filter.SEPIA -> out += ColorMatrix.SEPIA
            Filter.WARM -> out += ColorMatrix.WARM
            Filter.COOL -> out += ColorMatrix.COOL
            Filter.VIVID -> {
                out += HslAdjustment.Builder().adjustSaturation(35f).build()
                out += Contrast(0.12f)
            }
        }
        // Slider ranges are -1..1; these factors keep the extremes usable.
        if (v.brightness != 0f) out += Brightness((v.brightness * 0.5f).coerceIn(-1f, 1f))
        if (v.contrast != 0f) out += Contrast((v.contrast * 0.6f).coerceIn(-1f, 1f))
        if (v.saturation != 0f) out += HslAdjustment.Builder().adjustSaturation((v.saturation * 100f).coerceIn(-100f, 100f)).build()
        if (v.fadeInUs > 0 || v.fadeOutUs > 0) out += FadeToBlack(v.fadeInUs, v.fadeOutUs, v.totalUs)
        if (v.texts.isNotEmpty()) out += OverlayEffect(v.texts.map { TimedText(it) })
        return out
    }
}
