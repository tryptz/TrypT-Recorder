@file:OptIn(UnstableApi::class)

package tf.monochrome.recorder.editor.export

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SingleColorLut
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import tf.monochrome.recorder.editor.model.Filter
import tf.monochrome.recorder.editor.model.OverlayClip
import tf.monochrome.recorder.editor.model.PictureTransform
import tf.monochrome.recorder.editor.model.RecorderLinkTarget

/**
 * Maps an [ExportPlan] onto a Media3 [Composition]. The same Composition is
 * played by the editor's CompositionPlayer and rendered by Transformer, so
 * the preview is the export.
 *
 * Every per-item effect list is built fresh: overlays hold GL textures that
 * are released with the item's shader program, and must not be shared.
 */
object CompositionFactory {
    internal fun build(
        context: Context,
        plan: ExportPlan,
        preview: Boolean = false,
        music: MusicLinkTimeline? = null,
    ): Composition {
        val sequences = mutableListOf<EditedMediaItemSequence>()
        val visuals = plan.visuals
        val hasOverlay = visuals != null && plan.overlays.isNotEmpty()
        val overlayLanes = plan.overlays.groupBy { it.layer }.toSortedMap(reverseOrder()).values.toList()
        // CompositionPlayer generates a silent audio input even for video-only
        // sequences. Normalize those to the same role format as decoded audio.
        val routePreviewSilence = preview && plan.mixer != null
        for (lane in overlayLanes) sequences += overlaySequence(context, lane, plan.durationUs, routePreviewSilence)
        if (plan.primary.isNotEmpty()) {
            val types = buildSet {
                if (plan.primaryHasVideo) add(C.TRACK_TYPE_VIDEO)
                if (plan.primaryHasAudio) add(C.TRACK_TYPE_AUDIO)
            }
            sequences += sequence(context, types, plan.primary, plan.primaryHasAudio || routePreviewSilence) { media ->
                videoEffects(visuals, media, finish = !hasOverlay, music = music)
            }
        }
        for (lane in plan.lanes) {
            sequences += sequence(context, setOf(C.TRACK_TYPE_AUDIO), lane, routesAudio = true) { emptyList() }
        }
        return Composition.Builder(sequences).apply {
            val hasLut = plan.primary.any {
                val grade = (it as? Segment.Media)?.media?.picture?.grade
                grade != null && !grade.bypass && grade.lut != null && grade.lutMix > 0f
            }
            if (hasOverlay || hasLut) setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            if (hasOverlay) {
                setVideoCompositorSettings(PictureInPictureCompositor(visuals, overlayLanes))
            }
            if (plan.mixer != null || hasOverlay) {
                setEffects(Effects(
                    if (plan.mixer != null) listOf(UnpackMaster()) else emptyList(),
                    if (hasOverlay) finishingEffects(visuals, music) else emptyList(),
                ))
            }
        }.build()
    }

    // The first video input supplies the compositor's timestamps and is drawn on top.
    private fun overlaySequence(
        context: Context,
        clips: List<OverlayClip>,
        durationUs: Long,
        routesAudio: Boolean,
    ): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
        var cursor = 0L
        for (clip in clips) {
            if (clip.startUs > cursor) addGap(builder, context, clip.startUs - cursor, routesAudio)
            val media = PlannedMedia(
                uri = clip.source.uri,
                sourceDurationUs = clip.source.durationUs,
                clipStartUs = clip.trimStartUs,
                clipEndUs = clip.trimEndUs,
                speed = 1f,
                removeAudio = true,
                removeVideo = false,
                gain = 1f,
                fadeInUs = 0,
                fadeOutUs = 0,
                timelineStartUs = clip.startUs,
                durationUs = clip.durationUs,
            )
            builder.addItem(item(media, emptyList(), routesAudio))
            cursor = clip.endUs
        }
        if (cursor < durationUs) addGap(builder, context, durationUs - cursor, routesAudio)
        return builder.build()
    }

    private class PictureInPictureCompositor(
        private val canvas: PlannedVisuals,
        lanes: List<List<OverlayClip>>,
    ) : VideoCompositorSettings {
        private val normal = StaticOverlaySettings.Builder().build()
        private val hidden = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
        private val positions = lanes.map { lane -> lane.map { clip ->
            val fit = minOf(
                canvas.width.toFloat() / clip.source.width.coerceAtLeast(1),
                canvas.height.toFloat() / clip.source.height.coerceAtLeast(1),
            ) * clip.scale
            clip to StaticOverlaySettings.Builder()
                .setScale(fit, fit)
                .setBackgroundFrameAnchor(clip.x, clip.y)
                .setAlphaScale(clip.opacity)
                .build()
        } }

        override fun getOutputSize(inputSizes: List<Size>): Size = Size(canvas.width, canvas.height)

        override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
            val lane = positions.getOrNull(inputId) ?: return normal
            return lane.firstOrNull { (clip, _) ->
                presentationTimeUs in clip.startUs until clip.endUs
            }?.second ?: hidden
        }
    }

    private fun sequence(
        context: Context,
        types: Set<Int>,
        segments: List<Segment>,
        routesAudio: Boolean,
        effects: (PlannedMedia) -> List<Effect>,
    ): EditedMediaItemSequence {
        val b = EditedMediaItemSequence.Builder(types)
        for (s in segments) {
            when (s) {
                is Segment.Gap -> addGap(b, context, s.durationUs, routesAudio)
                is Segment.Media -> b.addItem(item(
                    s.media,
                    if (s.media.removeVideo) emptyList() else effects(s.media),
                    routesAudio,
                ))
            }
        }
        return b.build()
    }

    private fun addGap(
        builder: EditedMediaItemSequence.Builder,
        context: Context,
        durationUs: Long,
        routesAudio: Boolean,
    ) {
        if (!routesAudio) {
            builder.addGap(durationUs)
            return
        }
        var remaining = durationUs
        while (remaining > 0) {
            val part = minOf(remaining, MixerSilence.MAX_DURATION_US)
            builder.addItem(silentAudio(context, part))
            remaining -= part
        }
    }

    private fun silentAudio(context: Context, durationUs: Long): EditedMediaItem {
        val file = MixerSilence.asset(context, durationUs)
        val assetDuration = (file.length() - 44) * 1_000_000 / (MIX_SAMPLE_RATE * 2)
        return item(
            PlannedMedia(
                uri = file.toURI().toString(),
                sourceDurationUs = assetDuration,
                clipStartUs = 0,
                clipEndUs = durationUs,
                speed = 1f,
                removeAudio = false,
                removeVideo = false,
                gain = 1f,
                fadeInUs = 0,
                fadeOutUs = 0,
                timelineStartUs = 0,
                durationUs = durationUs,
            ),
            emptyList(),
            routesAudio = true,
        )
    }

    private fun item(m: PlannedMedia, videoEffects: List<Effect>, routesAudio: Boolean = false): EditedMediaItem {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionUs(m.clipStartUs)
            // Up to the end of the file: let the file say where that is, rather
            // than trusting a millisecond-rounded duration from its metadata.
            .setEndPositionUs(if (m.clipEndUs >= m.sourceDurationUs) C.TIME_END_OF_SOURCE else m.clipEndUs)
            .build()
        val mediaItem = MediaItem.Builder().setUri(m.uri).setClippingConfiguration(clipping).build()
        val audio = buildList<AudioProcessor> {
            if (routesAudio) {
                if (!m.removeAudio && (m.gain != 1f || m.fadeInUs > 0 || m.fadeOutUs > 0)) {
                    add(VolumeEnvelope(m.gain, m.fadeInUs, m.fadeOutUs, m.durationUs))
                }
                add(SonicAudioProcessor().apply { setOutputSampleRateHz(MIX_SAMPLE_RATE) })
                add(RouteAudio(m.role))
            }
        }
        return EditedMediaItem.Builder(mediaItem)
            // CompositionPlayer needs every item's length up front.
            .setDurationUs(m.sourceDurationUs)
            .setRemoveAudio(m.removeAudio)
            .setRemoveVideo(m.removeVideo)
            .setEffects(Effects(audio, videoEffects))
            .apply {
                if (m.speedKeys.isNotEmpty()) setSpeed(RampSpeed(m.timing, m.pictureSourceStartUs))
                else if (m.speed != 1f) setSpeed(ConstantSpeed(m.speed))
            }
            .build()
    }

    /**
     * Geometry first (crop, rotate/flip, fit to the canvas), then colour at
     * output size where it is cheapest, then grain, then fades and titles on
     * top — so a fade ends on clean black and titles stay crisp.
     */
    private fun videoEffects(v: PlannedVisuals?, media: PlannedMedia, finish: Boolean, music: MusicLinkTimeline?): List<Effect> {
        if (v == null) return emptyList()
        val out = mutableListOf<Effect>()
        media.picture.trackedCover?.let { track ->
            out += OverlayEffect(listOf(SolidCover { presentationTimeUs ->
                track.at(media.timing.sourceAt(presentationTimeUs - media.timelineStartUs))
            }))
        }
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
        val picture = media.picture
        if (picture.transform != PictureTransform() || picture.keys.isNotEmpty()) out += ClipMotion(media)
        val grade = picture.grade
        if (!grade.bypass) {
            if (grade.temperature != 0f || grade.exposure != 0f || grade.tint != 0f) {
                out += ColorMatrix.whiteBalance(grade.temperature, grade.exposure, grade.tint)
            }
            out += adjustments(grade.brightness, grade.contrast, grade.saturation)
            if (grade.lutMix > 0f) grade.lut?.let { out += SingleColorLut.createFromCube(it.toRgbCube(grade.lutMix)) }
        }
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
        out += adjustments(
            if (music?.contains(RecorderLinkTarget.BRIGHTNESS) == true) 0f else v.brightness,
            if (music?.contains(RecorderLinkTarget.CONTRAST) == true) 0f else v.contrast,
            if (music?.contains(RecorderLinkTarget.SATURATION) == true) 0f else v.saturation,
        )
        picture.cover?.let { out += OverlayEffect(listOf(SolidCover { _ -> it })) }
        if (finish) out += finishingEffects(v, music)
        return out
    }

    private fun adjustments(brightness: Float, contrast: Float, saturation: Float): List<Effect> = buildList {
        if (brightness != 0f) add(Brightness((brightness * 0.5f).coerceIn(-1f, 1f)))
        if (contrast != 0f) add(Contrast((contrast * 0.6f).coerceIn(-1f, 1f)))
        if (saturation != 0f) add(HslAdjustment.Builder().adjustSaturation((saturation * 100f).coerceIn(-100f, 100f)).build())
    }

    private fun finishingEffects(v: PlannedVisuals, music: MusicLinkTimeline?): List<Effect> = buildList {
        music?.let { add(MusicGrade(it)) }
        v.grain?.let { add(FilmGrain(it)) }
        if (v.fadeInUs > 0 || v.fadeOutUs > 0) add(FadeToBlack(v.fadeInUs, v.fadeOutUs, v.totalUs))
        if (v.texts.isNotEmpty()) add(OverlayEffect(v.texts.map { TimedText(it) }))
    }
}
