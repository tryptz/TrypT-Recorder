package tf.monochrome.recorder.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.recorder.editor.export.ExportJob
import tf.monochrome.recorder.editor.export.ExportMode
import tf.monochrome.recorder.editor.export.ExportPlanner
import tf.monochrome.recorder.editor.export.ExportSettings
import tf.monochrome.recorder.editor.export.PlanResult
import tf.monochrome.recorder.editor.export.Segment
import tf.monochrome.recorder.editor.model.AspectPreset
import tf.monochrome.recorder.editor.model.AudioRole
import tf.monochrome.recorder.editor.model.CameraAngle
import tf.monochrome.recorder.editor.model.Captions
import tf.monochrome.recorder.editor.model.CoverSample
import tf.monochrome.recorder.editor.model.CoverTracker
import tf.monochrome.recorder.editor.model.CropRect
import tf.monochrome.recorder.editor.model.CubeLut
import tf.monochrome.recorder.editor.model.Mixer
import tf.monochrome.recorder.editor.model.MixerFx
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.model.SnapinType
import tf.monochrome.recorder.editor.model.SpeedKey
import tf.monochrome.recorder.editor.model.TimelineRate
import tf.monochrome.recorder.editor.model.TrackedCover
import tf.monochrome.recorder.editor.model.addAudio
import tf.monochrome.recorder.editor.model.addOverlay
import tf.monochrome.recorder.editor.model.addText
import tf.monochrome.recorder.editor.model.cutToAngle
import tf.monochrome.recorder.editor.model.detachAudio
import tf.monochrome.recorder.editor.model.rippleDelete
import tf.monochrome.recorder.editor.model.setOverlayLayer
import tf.monochrome.recorder.editor.model.setSpeedRamp
import tf.monochrome.recorder.editor.model.sourceAt
import tf.monochrome.recorder.editor.model.splitAt
import tf.monochrome.recorder.editor.model.toggleMarker
import tf.monochrome.recorder.editor.model.trimClip
import tf.monochrome.recorder.editor.model.updateAngles
import tf.monochrome.recorder.editor.store.ProjectStore
import kotlin.math.abs

class ProEditorTest {
    @Test fun rampSplitRetainsSourceRateAndDetachedAudioMapping() {
        val p = project(video("video", 8))
        val id = p.clips.single().id
        val ramped = p.setSpeedRamp(id, listOf(SpeedKey(0, 0.5f), SpeedKey(8 * SEC, 2f)))
        val clip = ramped.clips.single()
        assertEquals(0.5f, clip.timing.rateAt(0), 0f)
        assertEquals(2f, clip.timing.peakRate, 0.1f)
        val cutAt = 4 * SEC
        val sourceAtCut = clip.sourceAt(cutAt)
        val split = ramped.splitAt(cutAt)
        assertEquals(sourceAtCut, split.clips[1].trimStartUs)
        assertEquals(clip.timing.rateAt(sourceAtCut), split.clips[1].timing.rateAt(sourceAtCut), 0f)
        assertTrue(abs(clip.durationUs - split.clips.sumOf { it.durationUs }) <= 1)
        val detached = ramped.detachAudio(id)
        assertEquals(clip.durationUs, detached.audio.single().durationUs)
        val plan = (ExportPlanner.plan(detached, ExportSettings()) as PlanResult.Ok).plan
        assertEquals(clip.speedKeys, (plan.lanes.single().single() as Segment.Media).media.speedKeys)
    }

    @Test fun alternatePictureKeepsOriginalAudioAndUsesSynchronizedSourceTime() {
        val p = project(video("base", 8))
        val id = p.clips.single().id
        val angle = CameraAngle("b", video("other", 10), SEC)
        val draft = p.updateAngles(id) { it.copy(angles = listOf(angle)) }
        val cut = requireNotNull(draft.cutToAngle(id, angle.id, 3 * SEC))
        val plan = (ExportPlanner.plan(cut, ExportSettings()) as PlanResult.Ok).plan
        val switched = (plan.primary[1] as Segment.Media).media
        val sound = (plan.lanes.single().last() as Segment.Media).media
        assertEquals(angle.source.uri, switched.uri)
        assertEquals(4 * SEC, switched.clipStartUs)
        assertEquals(3 * SEC, switched.pictureSourceStartUs)
        assertTrue(switched.removeAudio)
        assertEquals(p.clips.single().source.uri, sound.uri)
        assertEquals(3 * SEC, sound.timelineStartUs)
        assertEquals(3 * SEC, sound.clipStartUs)
        val audioOnly = (ExportPlanner.plan(cut, ExportSettings(mode = ExportMode.AUDIO_ONLY)) as PlanResult.Ok).plan
        assertEquals(p.clips.single().source.uri, (audioOnly.primary[1] as Segment.Media).media.uri)
        assertTrue(audioOnly.lanes.isEmpty())
    }

    @Test fun rippleClosesTheSameIntervalOnAllTracks() {
        val p = project(video("a", 2), video("b", 2), video("c", 2))
            .addAudio(music("music", 6), 0)
            .addOverlay(video("overlay", 4), SEC)
            .addText("caption", 3 * SEC, 2 * SEC, caption = true)
            .toggleMarker(3 * SEC)
            .toggleMarker(5 * SEC)
        val cut = p.rippleDelete(p.clips[1].id)
        assertEquals(listOf(0L, 2 * SEC), cut.audio.map { it.startUs })
        assertEquals(listOf(0L, 4 * SEC), cut.audio.map { it.trimStartUs })
        assertEquals(listOf(SEC, 2 * SEC), cut.overlays.map { it.startUs })
        assertEquals(2 * SEC, cut.texts.single().startUs)
        assertEquals(3 * SEC, cut.markers.single().timeUs)
    }

    @Test fun overlaysCanOverlapOnlyAcrossLayersAndSoloSilencesOtherRoles() {
        val p = project(video("video", 8)).addOverlay(video("bottom", 5), 0)
            .addOverlay(video("top", 4), SEC, layer = 1)
            .addAudio(music("music", 8), 0)
        assertEquals(2, p.overlays.size)
        assertEquals(p, p.setOverlayLayer(p.overlays.last().id, 0))
        val solo = p.copy(mixer = p.mixer.copy(solo = AudioRole.MUSIC))
        val plan = (ExportPlanner.plan(solo, ExportSettings()) as PlanResult.Ok).plan
        assertFalse(plan.primaryHasAudio)
        assertTrue(plan.hasAudio)
        assertEquals(listOf(0, 1), plan.overlays.map { it.layer })
    }

    @Test fun mixerChainsRoundTripAndPreviewKeepsMutedSourcesRouted() {
        val legacy = ProjectStore.JSON.decodeFromString<Mixer>("""{"masterGain":0.5}""")
        assertEquals(0.5f, legacy.masterGain, 0f)
        assertTrue(legacy.fxEnabled)
        assertTrue(legacy.masterEffects.isEmpty())
        val reverb = MixerFx(SnapinType.REVERB, wet = 0.4f, values = mapOf(1 to 2.8f))
        val limiter = MixerFx(SnapinType.LIMITER)
        val mixer = legacy.editChannel(AudioRole.DEVICE) { it.copy(effects = listOf(reverb)) }
            .editChannel(null) { it.copy(effects = listOf(limiter), muted = true) }
            .copy(solo = AudioRole.MUSIC)
        val project = project(video("device", 8)).addAudio(music("music", 5), SEC).copy(mixer = mixer)
        val restored = ProjectStore.JSON.decodeFromString<Project>(
            ProjectStore.JSON.encodeToString(Project.serializer(), project),
        )
        assertEquals(mixer, restored.mixer)

        val export = (ExportPlanner.plan(restored, ExportSettings()) as PlanResult.Ok).plan
        val preview = (ExportPlanner.plan(restored, ExportSettings(), retainMutedInputs = true) as PlanResult.Ok).plan
        assertFalse(export.primaryHasAudio)
        assertTrue(preview.primaryHasAudio)
        assertEquals(AudioRole.DEVICE, (preview.primary.single() as Segment.Media).media.role)
        assertEquals(AudioRole.MUSIC, (preview.lanes.single().last() as Segment.Media).media.role)
        assertEquals(mixer, export.mixer)
        assertEquals(mixer, preview.mixer)
    }

    @Test fun srtRoundTripsAndCanBeOmittedFromThePictureOnly() {
        val captions = Captions.parse("\uFEFF1\r\n00:00:01,250 --> 00:00:02,010\r\nHello\r\nworld\r\n")
        val p = project(video("a", 4)).copy(texts = captions).addText("Title", 0)
        val result = (ExportPlanner.plan(p, ExportSettings(burnCaptions = false)) as PlanResult.Ok).plan
        assertEquals(listOf("Title"), result.visuals?.texts?.map { it.text })
        assertEquals(captions.single().copy(id = ""), Captions.parse(Captions.write(p.texts, 4 * SEC)).single().copy(id = ""))
        assertEquals(33_366, TimelineRate.FPS_2997.step(0, true))
        assertEquals(66_733, TimelineRate.FPS_2997.step(33_366, true))
        assertEquals(33_366, TimelineRate.FPS_2997.step(66_733, false))
    }

    @Test fun trackFailsClosedAcrossAnUncertainSample() {
        val box = CropRect(.2f, .2f, .4f, .4f)
        val later = CropRect(.4f, .2f, .6f, .4f)
        val track = TrackedCover(listOf(CoverSample(0, box), CoverSample(SEC, box, false), CoverSample(2 * SEC, later)))
        assertEquals(CropRect.FULL, track.at(SEC / 2))
        assertEquals(CropRect.FULL, track.at(3 * SEC))
        assertEquals(.3f, track.correct(SEC, later).at(SEC / 2).left, .0001f)

        val pixels = IntArray(40 * 40) { 0xff777777.toInt() }
        assertNull(CoverTracker(pixels, 40, 40, box).find(pixels, box))
        assertNotNull(track.at(0))
    }

    @Test fun lutChannelOrderAndQueueSnapshotRoundTrip() {
        val raw = buildString {
            appendLine("LUT_3D_SIZE 2")
            for (b in 0..1) for (g in 0..1) for (r in 0..1) appendLine("$b $g $r")
        }
        val lut = CubeLut.parse(raw, "swap")
        assertEquals(0xff0000ff.toInt(), lut.toRgbCube(1f)[1][0][0])
        assertEquals(0xffff0000.toInt(), lut.toRgbCube(0f)[1][0][0])
        val p = project(video("a", 5))
        val trimmed = p.trimClip(p.clips.first().id, SEC, 4 * SEC)
        val job = ExportJob("job", trimmed, ExportSettings(aspectOverride = AspectPreset.TALL), "vertical", "content://result")
        val json = ProjectStore.JSON.encodeToString(ExportJob.serializer(), job)
        assertEquals(job, ProjectStore.JSON.decodeFromString(ExportJob.serializer(), json))
    }
}
