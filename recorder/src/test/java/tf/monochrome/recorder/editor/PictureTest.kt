package tf.monochrome.recorder.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.recorder.editor.export.ExportMode
import tf.monochrome.recorder.editor.export.ExportPlanner
import tf.monochrome.recorder.editor.export.ExportSettings
import tf.monochrome.recorder.editor.export.PlanResult
import tf.monochrome.recorder.editor.model.ClipPicture
import tf.monochrome.recorder.editor.model.MotionEasing
import tf.monochrome.recorder.editor.model.MotionKey
import tf.monochrome.recorder.editor.model.PictureTransform
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.model.addOverlay
import tf.monochrome.recorder.editor.model.moveOverlay
import tf.monochrome.recorder.editor.model.sourceAt
import tf.monochrome.recorder.editor.model.splitAt
import tf.monochrome.recorder.editor.model.splitOverlay
import tf.monochrome.recorder.editor.model.trimClip
import tf.monochrome.recorder.editor.model.updateClipPicture
import tf.monochrome.recorder.editor.store.ProjectStore

class PictureTest {
    @Test fun motionKeysKeepTheirSourceTimingAfterSplit() {
        val p = project(video("main", 5))
        val a = MotionKey(0, PictureTransform(), MotionEasing.LINEAR)
        val b = MotionKey(4 * SEC, PictureTransform(zoom = 2f))
        val picture = ClipPicture().put(b).put(a)
        val right = p.updateClipPicture(p.clips.single().id) { picture }.splitAt(2 * SEC).clips[1]
        assertEquals(3 * SEC, right.sourceAt(SEC))
        assertEquals(1.75f, right.picture.at(right.sourceAt(SEC)).zoom, 0f)
        assertEquals(1f, picture.put(a.copy(easing = MotionEasing.HOLD)).at(2 * SEC).zoom, 0f)
    }

    @Test fun overlayLanePreventsOverlapAndSplitsAtThePlayhead() {
        val p = project(video("main", 10)).addOverlay(video("cam", 4), 3 * SEC)
        val clip = p.overlays.single()
        assertEquals(p, p.addOverlay(video("overlap", 2), 4 * SEC))
        val second = p.addOverlay(video("earlier", 4), 0)
        assertEquals(3 * SEC, second.overlays.first().durationUs)
        assertEquals(second, second.moveOverlay(clip.id, SEC))
        val split = second.splitOverlay(clip.id, 5 * SEC)
        assertEquals(listOf(0L, 3 * SEC, 5 * SEC), split.overlays.map { it.startUs })
        assertEquals(2 * SEC, split.overlays.last().trimStartUs)
    }

    @Test fun overlaysPersistAndFollowThePictureDurationWithoutAddingAudio() {
        val base = project(video("main", 10, audio = false)).addOverlay(video("cam", 5), 6 * SEC)
        val p = base.trimClip(base.clips.single().id, 0, 8 * SEC)
        val plan = (ExportPlanner.plan(p, ExportSettings()) as PlanResult.Ok).plan
        assertEquals(2 * SEC, plan.overlays.single().durationUs)
        assertTrue(!plan.hasAudio)
        assertTrue(ExportPlanner.plan(p, ExportSettings(mode = ExportMode.AUDIO_ONLY)) is PlanResult.Nothing)
        val json = ProjectStore.JSON.encodeToString(Project.serializer(), p)
        assertEquals(p, ProjectStore.JSON.decodeFromString(Project.serializer(), json))
    }
}
