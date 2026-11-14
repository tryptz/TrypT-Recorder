package tf.monochrome.recorder.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.recorder.editor.export.ExportMode
import tf.monochrome.recorder.editor.export.ExportPlan
import tf.monochrome.recorder.editor.export.ExportPlanner
import tf.monochrome.recorder.editor.export.ExportQuality
import tf.monochrome.recorder.editor.export.ExportResolution
import tf.monochrome.recorder.editor.export.ExportSettings
import tf.monochrome.recorder.editor.export.GifFrameRate
import tf.monochrome.recorder.editor.export.GifSize
import tf.monochrome.recorder.editor.export.PlanResult
import tf.monochrome.recorder.editor.export.Segment
import tf.monochrome.recorder.editor.model.AspectPreset
import tf.monochrome.recorder.editor.model.CanvasSettings
import tf.monochrome.recorder.editor.model.CropInsets
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.model.VideoLook
import tf.monochrome.recorder.editor.model.addAudio
import tf.monochrome.recorder.editor.model.addText
import tf.monochrome.recorder.editor.model.detachAudio
import tf.monochrome.recorder.editor.model.moveAudio
import tf.monochrome.recorder.editor.model.setClipSpeed
import tf.monochrome.recorder.editor.model.setClipVolume

class ExportPlannerTest {
    private fun plan(p: Project, mode: ExportMode = ExportMode.VIDEO, res: ExportResolution = ExportResolution.ORIGINAL): ExportPlan =
        (ExportPlanner.plan(p, ExportSettings(mode = mode, resolution = res)) as PlanResult.Ok).plan

    private val ExportPlan.media get() = primary.filterIsInstance<Segment.Media>().map { it.media }

    @Test
    fun `nothing to export without clips`() {
        val r = ExportPlanner.plan(Project("p", "x", 0, 0), ExportSettings())
        assertEquals(PlanResult.Nothing(PlanResult.Reason.NO_CLIPS), r)
    }

    @Test
    fun `plain export keeps picture and sound`() {
        val pl = plan(project(video("a", 10), video("b", 5)))
        assertTrue(pl.primaryHasVideo)
        assertTrue(pl.primaryHasAudio)
        assertEquals(15 * SEC, pl.durationUs)
        assertEquals(listOf(0L, 10 * SEC), pl.media.map { it.timelineStartUs })
        assertTrue(pl.media.none { it.removeAudio || it.removeVideo })
        assertTrue(pl.lanes.isEmpty())
    }

    @Test
    fun `detached sound leaves the picture silent and plays from a lane at the same time`() {
        val base = project(video("a", 4), video("b", 6))
        val p = base.detachAudio(base.clips[1].id)
        val pl = plan(p)
        assertEquals(listOf(false, true), pl.media.map { it.removeAudio })
        val lane = pl.lanes.single()
        assertEquals(Segment.Gap(4 * SEC), lane[0])
        val piece = (lane[1] as Segment.Media).media
        assertTrue("the lane takes only the sound", piece.removeVideo)
        assertFalse(piece.removeAudio)
        assertEquals(4 * SEC, piece.timelineStartUs)
    }

    @Test
    fun `never strips an item of both tracks`() {
        val base = project(video("a", 4), video("b", 6, audio = false))
        val p = base.detachAudio(base.clips[0].id).setClipVolume(base.clips[1].id, 0f)
        for (mode in ExportMode.entries) {
            val r = ExportPlanner.plan(p, ExportSettings(mode = mode))
            if (r !is PlanResult.Ok) continue
            val all = r.plan.primary + r.plan.lanes.flatten()
            all.filterIsInstance<Segment.Media>().forEach { assertFalse("$mode", it.media.removeAudio && it.media.removeVideo) }
        }
    }

    @Test
    fun `video only drops every sound`() {
        val p = project(video("a", 10)).addAudio(music("song", 5), 0)
        val pl = plan(p, ExportMode.VIDEO_ONLY)
        assertFalse(pl.hasAudio)
        assertTrue(pl.media.all { it.removeAudio })
        assertTrue(pl.lanes.isEmpty())
    }

    @Test
    fun `audio only keeps timing with gaps for silent clips`() {
        val base = project(video("a", 4, audio = false), video("b", 6))
        val pl = plan(base, ExportMode.AUDIO_ONLY)
        assertNull(pl.visuals)
        assertFalse(pl.primaryHasVideo)
        assertEquals(Segment.Gap(4 * SEC), pl.primary[0])
        val m = (pl.primary[1] as Segment.Media).media
        assertTrue(m.removeVideo)
        assertFalse(m.removeAudio)
    }

    @Test
    fun `audio only needs some audio`() {
        val r = ExportPlanner.plan(project(video("a", 4, audio = false)), ExportSettings(mode = ExportMode.AUDIO_ONLY))
        assertEquals(PlanResult.Nothing(PlanResult.Reason.NO_AUDIO), r)
    }

    @Test
    fun `audio only with only lanes skips the empty main track`() {
        val base = project(video("a", 10))
        val p = base.detachAudio(base.clips[0].id)
        val pl = plan(p, ExportMode.AUDIO_ONLY)
        assertTrue(pl.primary.isEmpty())
        assertEquals(1, pl.lanes.size)
    }

    @Test
    fun `music past the end of the video is cut off there`() {
        val p = project(video("a", 10)).addAudio(music("song", 60), 0).let { it.moveAudio(it.audio[0].id, 4 * SEC) }
        val piece = (plan(p).lanes.single()[1] as Segment.Media).media
        assertEquals(6 * SEC, piece.clipEndUs - piece.clipStartUs)
        assertEquals(6 * SEC, piece.durationUs)
    }

    @Test
    fun `music starting after the end is dropped`() {
        val p = project(video("a", 10)).addAudio(music("song", 5), 0).let { it.moveAudio(it.audio[0].id, 11 * SEC) }
        assertTrue(plan(p).lanes.isEmpty())
    }

    @Test
    fun `speed shortens the timeline but not the source range`() {
        val base = project(video("a", 10))
        val p = base.setClipSpeed(base.clips[0].id, 2f)
        val m = plan(p).media.single()
        assertEquals(2f, m.speed)
        assertEquals(10 * SEC, m.clipEndUs)
        assertEquals(5 * SEC, m.durationUs)
    }

    @Test
    fun `canvas follows crop, rotation, aspect and resolution`() {
        val portrait = CanvasSettings()
        assertEquals(1080 to 2400, ExportPlanner.canvasSize(1080, 2400, portrait, ExportResolution.ORIGINAL))
        assertEquals(720 to 1600, ExportPlanner.canvasSize(1080, 2400, portrait, ExportResolution.P720))
        assertEquals(2400 to 1080, ExportPlanner.canvasSize(1080, 2400, portrait.copy(rotation = 90), ExportResolution.ORIGINAL))
        assertEquals(1080 to 1080, ExportPlanner.canvasSize(1080, 2400, portrait.copy(aspect = AspectPreset.SQUARE), ExportResolution.ORIGINAL))
        assertEquals(1920 to 1080, ExportPlanner.canvasSize(1080, 2400, portrait.copy(aspect = AspectPreset.WIDE), ExportResolution.P1080))
        val cropped = portrait.copy(crop = CropInsets(top = 0.25f, bottom = 0.25f))
        assertEquals(1080 to 1200, ExportPlanner.canvasSize(1080, 2400, cropped, ExportResolution.ORIGINAL))
        // Over-large sources are held to what encoders accept, and stay even.
        val (w, h) = ExportPlanner.canvasSize(7681, 4321, portrait, ExportResolution.ORIGINAL)
        assertEquals(3840, w)
        assertEquals(0, h % 2)
    }

    @Test
    fun `titles are clamped to the timeline and scaled to the canvas`() {
        val p = project(video("a", 10)).addText("Hi", 8 * SEC, 5 * SEC).addText("  ", 0)
        val t = plan(p, res = ExportResolution.P720).visuals!!.texts.single()
        assertEquals(10 * SEC, t.endUs)
        assertEquals(0.65f * 720 / 1080f, t.scale, 1e-4f)
    }

    @Test
    fun `grain reaches the picture, held to its range`() {
        assertNull(plan(project(video("a", 10))).visuals!!.grain)
        val p = project(video("a", 10)).copy(look = VideoLook(grain = 1.5f, grainSize = -0.2f))
        val g = plan(p).visuals!!.grain!!
        assertEquals(1f, g.amount)
        assertEquals(0f, g.size)
        assertFalse("never exported as grain-only", g.solo)
        // Silent video and GIF carry it too.
        assertEquals(1f, plan(p, ExportMode.GIF).visuals!!.grain!!.amount)
    }

    @Test
    fun `whole-video fades are held to half the length`() {
        val p = project(video("a", 10)).copy(look = VideoLook(fadeInUs = 9 * SEC, fadeOutUs = 1 * SEC))
        val v = plan(p).visuals!!
        assertEquals(5 * SEC, v.fadeInUs)
        assertEquals(1 * SEC, v.fadeOutUs)
    }

    @Test
    fun `fade gain ramps in and out`() {
        assertEquals(0f, ExportPlanner.fadeGain(0, 10 * SEC, 2 * SEC, 2 * SEC), 1e-6f)
        assertEquals(0.5f, ExportPlanner.fadeGain(1 * SEC, 10 * SEC, 2 * SEC, 2 * SEC), 1e-6f)
        assertEquals(1f, ExportPlanner.fadeGain(5 * SEC, 10 * SEC, 2 * SEC, 2 * SEC), 1e-6f)
        assertEquals(0.25f, ExportPlanner.fadeGain(9_500_000, 10 * SEC, 2 * SEC, 2 * SEC), 1e-6f)
        assertEquals(1f, ExportPlanner.fadeGain(0, 10 * SEC, 0, 0), 1e-6f)
    }

    @Test
    fun `export bitrate is sized for the frame rate actually exported`() {
        val hfr = project(video("a", 10).copy(frameRate = 165f))
        assertEquals(165f, ExportPlanner.outputFrameRate(hfr), 0.01f)
        // Twice as fast is twice the frames per second, held to 240.
        assertEquals(240f, ExportPlanner.outputFrameRate(hfr.setClipSpeed(hfr.clips[0].id, 2f)), 0.01f)
        // Unknown rate (older drafts): assume 60.
        assertEquals(60f, ExportPlanner.outputFrameRate(project(video("b", 10))), 0.01f)
        val at165 = plan(hfr, res = ExportResolution.P720).videoBitrate
        val at60 = plan(project(video("b", 10)), res = ExportResolution.P720).videoBitrate
        assertEquals(165.0 / 60.0, at165.toDouble() / at60, 0.01)
    }

    @Test
    fun `exports keep the recorder's 320 kbps audio`() {
        assertEquals(320_000, plan(project(video("a", 10))).audioBitrate)
        assertEquals(320_000, plan(project(video("a", 10)), ExportMode.AUDIO_ONLY).audioBitrate)
    }

    private fun gif(p: Project, size: GifSize = GifSize.MEDIUM, fps: GifFrameRate = GifFrameRate.FPS_15, quality: ExportQuality = ExportQuality.STANDARD): ExportPlan =
        (ExportPlanner.plan(p, ExportSettings(mode = ExportMode.GIF, quality = quality, gifSize = size, gifFrameRate = fps)) as PlanResult.Ok).plan

    @Test
    fun `a gif is a silent picture at the gif's size and frame rate`() {
        val pl = gif(project(video("a", 10)).addAudio(music("song", 5), 0), fps = GifFrameRate.FPS_20)
        assertTrue(pl.primaryHasVideo)
        assertFalse(pl.hasAudio)
        assertTrue(pl.media.all { it.removeAudio })
        assertTrue(pl.lanes.isEmpty())
        assertEquals(480 to 1066, pl.visuals!!.width to pl.visuals!!.height)
        assertEquals(20, pl.gifFrameRate)
        assertEquals(10 * SEC, pl.durationUs)
    }

    @Test
    fun `a gif keeps the canvas edits`() {
        val p = project(video("a", 10)).copy(canvas = CanvasSettings(aspect = AspectPreset.SQUARE))
        val v = gif(p, GifSize.SMALL).visuals!!
        assertEquals(320 to 320, v.width to v.height)
    }

    @Test
    fun `a gif is never bigger than its source`() {
        val small = project(video("a", 10, w = 360, h = 640))
        val v = gif(small, GifSize.LARGE).visuals!!
        assertEquals(360 to 640, v.width to v.height)
    }

    @Test
    fun `a gif's source video is rendered at high quality whatever the setting`() {
        val p = project(video("a", 10).copy(frameRate = 60f))
        val pl = gif(p, quality = ExportQuality.LOW)
        val v = pl.visuals!!
        assertEquals(ExportPlanner.bitrate(v.width, v.height, ExportQuality.HIGH, 60f), pl.videoBitrate)
    }

    @Test
    fun `only gif exports carry a gif frame rate`() {
        val p = project(video("a", 10))
        for (mode in ExportMode.entries.filter { it != ExportMode.GIF }) {
            assertNull("$mode", (ExportPlanner.plan(p, ExportSettings(mode = mode)) as PlanResult.Ok).plan.gifFrameRate)
        }
    }

    @Test
    fun `boosting saturates instead of wrapping`() {
        assertEquals(Short.MAX_VALUE, ExportPlanner.scaleSample(30_000, 2f))
        assertEquals(Short.MIN_VALUE, ExportPlanner.scaleSample(-30_000, 2f))
        assertEquals(500.toShort(), ExportPlanner.scaleSample(1_000, 0.5f))
    }
}
