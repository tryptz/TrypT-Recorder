package tf.monochrome.recorder.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.recorder.editor.export.ExportMode
import tf.monochrome.recorder.editor.export.ExportPlanner
import tf.monochrome.recorder.editor.export.ExportResolution
import tf.monochrome.recorder.editor.export.ExportSettings
import tf.monochrome.recorder.editor.export.PlanResult
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.model.TimelineRate
import tf.monochrome.recorder.editor.ui.estimatedBytes
import tf.monochrome.recorder.editor.ui.formatBytes

/** The export sheet's "Estimated file size": the bitrates the export asks for, over its length. */
class ExportEstimateTest {
    @Test
    fun `the estimate is the planned bitrates over the length`() {
        val p = project(video("a", 30).copy(frameRate = 60f))
        val settings = ExportSettings(resolution = ExportResolution.P1080)
        val plan = (ExportPlanner.plan(p, settings) as PlanResult.Ok).plan
        val expected = (plan.videoBitrate.toLong() + plan.audioBitrate) * 30 / 8
        assertEquals(expected, estimatedBytes(p, settings))
    }

    @Test
    fun `fewer pixels, frames or no sound make a smaller file`() {
        val p = project(video("a", 30).copy(frameRate = 60f))
        val full = estimatedBytes(p, ExportSettings(resolution = ExportResolution.P1080))!!
        assertTrue(estimatedBytes(p, ExportSettings(resolution = ExportResolution.P720))!! < full)
        assertTrue(estimatedBytes(p, ExportSettings(resolution = ExportResolution.P1080, frameRate = TimelineRate.FPS_30))!! < full)
        assertTrue(estimatedBytes(p, ExportSettings(mode = ExportMode.VIDEO_ONLY, resolution = ExportResolution.P1080))!! < full)
    }

    @Test
    fun `nothing to export gives no estimate`() {
        assertNull(estimatedBytes(Project("p", "x", 0, 0), ExportSettings()))
    }

    @Test
    fun `sizes read as KB, MB or GB`() {
        assertEquals("259 MB", formatBytes(259_000_000))
        assertEquals("1.5 GB", formatBytes(1_500_000_000))
        assertEquals("12 KB", formatBytes(12_000))
        assertEquals("1 KB", formatBytes(10))
    }
}
