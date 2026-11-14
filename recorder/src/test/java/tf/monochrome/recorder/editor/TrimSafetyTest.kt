package tf.monochrome.recorder.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.recorder.editor.export.ExportPlanner
import tf.monochrome.recorder.editor.export.ExportResolution
import tf.monochrome.recorder.editor.export.ExportSettings
import tf.monochrome.recorder.editor.export.PlanResult
import tf.monochrome.recorder.editor.model.MIN_CLIP_US
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.model.SPEEDS
import tf.monochrome.recorder.editor.model.SpeedKey
import tf.monochrome.recorder.editor.model.addAudio
import tf.monochrome.recorder.editor.model.addClips
import tf.monochrome.recorder.editor.model.durationUs
import tf.monochrome.recorder.editor.model.setClipSpeed
import tf.monochrome.recorder.editor.model.setSpeedRamp
import tf.monochrome.recorder.editor.model.splitAt
import tf.monochrome.recorder.editor.model.trimAudio
import tf.monochrome.recorder.editor.model.trimClip
import tf.monochrome.recorder.editor.model.trimEndToPlayhead
import tf.monochrome.recorder.editor.model.trimStartToPlayhead
import kotlin.random.Random

/**
 * Trimming runs on the main thread for every step of a slider drag, so it must
 * never throw, whatever the slider sends, and the preview plan built from the
 * result must always be valid.
 */
class TrimSafetyTest {
    @Test
    fun `any sequence of trims leaves valid clips and a valid preview plan`() {
        val rnd = Random(7)
        repeat(3000) {
            val seconds = listOf(1L, 2, 5, 60, 3600)[rnd.nextInt(5)]
            var p = Project("p", "x", 0, 0).addClips(listOf(video("a", seconds), video("b", 3)))
            p = p.addAudio(music("m", 20), rnd.nextLong(0, p.durationUs))
            repeat(rnd.nextInt(1, 8)) {
                val c = p.clips[rnd.nextInt(p.clips.size)]
                val d = c.source.durationUs
                p = when (rnd.nextInt(7)) {
                    0, 1, 2 -> p.trimClip(c.id, rnd.nextLong(-d, 2 * d), rnd.nextLong(-d, 2 * d))
                    3 -> p.trimStartToPlayhead(c.id, rnd.nextLong(0, p.durationUs + 1))
                    4 -> p.trimEndToPlayhead(c.id, rnd.nextLong(0, p.durationUs + 1))
                    5 -> p.setClipSpeed(c.id, SPEEDS[rnd.nextInt(SPEEDS.size)])
                    else -> p.setSpeedRamp(c.id, listOf(SpeedKey(c.trimStartUs, 0.5f), SpeedKey(c.trimEndUs, 2f)))
                }
                p.audio.firstOrNull()?.let { a ->
                    p = p.trimAudio(a.id, rnd.nextLong(-SEC, 30 * SEC), rnd.nextLong(-SEC, 30 * SEC))
                }
                if (rnd.nextInt(4) == 0) p = p.splitAt(rnd.nextLong(0, p.durationUs + 1))
                for (clip in p.clips) {
                    assertTrue(clip.trimStartUs in 0..clip.trimEndUs)
                    assertTrue(clip.trimEndUs <= clip.source.durationUs)
                    assertTrue("${clip.durationUs}", clip.durationUs >= MIN_CLIP_US)
                }
                val plan = ExportPlanner.plan(p, ExportSettings(resolution = ExportResolution.P720), retainMutedInputs = true)
                assertTrue(plan is PlanResult.Ok)
            }
        }
    }

    @Test
    fun `a source shorter than the minimum is left alone, not a crash`() {
        val p = project(video("a", 1))
        val c = p.clips.single()
        // A clip whose file turned out shorter than the editor's minimum (e.g. read back
        // with a different duration) at 2x needs 200 ms of source it does not have.
        val short = c.copy(source = c.source.copy(durationUs = 150_000), trimStartUs = 0, trimEndUs = 150_000, speed = 2f)
        val q = p.copy(clips = listOf(short))
        assertEquals(q, q.trimClip(short.id, 10_000, 120_000))
    }
}
