package tf.monochrome.recorder.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.recorder.editor.model.AudioKind
import tf.monochrome.recorder.editor.model.MIN_CLIP_US
import tf.monochrome.recorder.editor.model.addAudio
import tf.monochrome.recorder.editor.model.addText
import tf.monochrome.recorder.editor.model.assignLanes
import tf.monochrome.recorder.editor.model.clipIndexAt
import tf.monochrome.recorder.editor.model.clipStartUs
import tf.monochrome.recorder.editor.model.deleteAudio
import tf.monochrome.recorder.editor.model.deleteClip
import tf.monochrome.recorder.editor.model.detachAudio
import tf.monochrome.recorder.editor.model.duplicateClip
import tf.monochrome.recorder.editor.model.durationUs
import tf.monochrome.recorder.editor.model.moveAudio
import tf.monochrome.recorder.editor.model.moveClip
import tf.monochrome.recorder.editor.model.restoreAudio
import tf.monochrome.recorder.editor.model.setAudioFades
import tf.monochrome.recorder.editor.model.setClipSpeed
import tf.monochrome.recorder.editor.model.setClipVolume
import tf.monochrome.recorder.editor.model.splitAt
import tf.monochrome.recorder.editor.model.splitAudioAt
import tf.monochrome.recorder.editor.model.trimAudio
import tf.monochrome.recorder.editor.model.trimClip
import tf.monochrome.recorder.editor.model.trimEndToPlayhead
import tf.monochrome.recorder.editor.model.trimStartToPlayhead
import tf.monochrome.recorder.editor.model.updateText

class TimelineTest {
    @Test
    fun `clips play back to back`() {
        val p = project(video("a", 10), video("b", 5))
        assertEquals(15 * SEC, p.durationUs)
        assertEquals(10 * SEC, p.clipStartUs(1))
        assertEquals(0, p.clipIndexAt(9 * SEC))
        assertEquals(1, p.clipIndexAt(10 * SEC))
        assertEquals(1, p.clipIndexAt(99 * SEC))
    }

    @Test
    fun `split cuts the clip under the playhead and keeps the total`() {
        val p = project(video("a", 10)).splitAt(4 * SEC)
        assertEquals(2, p.clips.size)
        assertEquals(4 * SEC, p.clips[0].trimEndUs)
        assertEquals(4 * SEC, p.clips[1].trimStartUs)
        assertEquals(10 * SEC, p.durationUs)
        assertNotEquals(p.clips[0].id, p.clips[1].id)
    }

    @Test
    fun `split respects speed when mapping timeline to source`() {
        val base = project(video("a", 10))
        val fast = base.setClipSpeed(base.clips[0].id, 2f) // 5 s on the timeline
        val p = fast.splitAt(2 * SEC) // 2 s in = 4 s of source
        assertEquals(4 * SEC, p.clips[0].trimEndUs)
        assertEquals(5 * SEC, p.durationUs)
    }

    @Test
    fun `split too close to an edge does nothing`() {
        val p = project(video("a", 10))
        assertSame(p, p.splitAt(MIN_CLIP_US - 1))
        assertSame(p, p.splitAt(10 * SEC - MIN_CLIP_US + 1))
    }

    @Test
    fun `trim is clamped to the file and to a minimum length`() {
        val p = project(video("a", 10))
        val id = p.clips[0].id
        val t = p.trimClip(id, -5, 99 * SEC).clips[0]
        assertEquals(0L, t.trimStartUs)
        assertEquals(10 * SEC, t.trimEndUs)
        val tiny = p.trimClip(id, 5 * SEC, 5 * SEC).clips[0]
        assertEquals(MIN_CLIP_US, tiny.sourceSpanUs)
    }

    @Test
    fun `trim to playhead uses the clip's place on the timeline`() {
        val p = project(video("a", 10), video("b", 10))
        val b = p.clips[1].id
        assertEquals(3 * SEC, p.trimStartToPlayhead(b, 13 * SEC).clips[1].trimStartUs)
        assertEquals(4 * SEC, p.trimEndToPlayhead(b, 14 * SEC).clips[1].trimEndUs)
    }

    @Test
    fun `speed and volume are clamped`() {
        val p = project(video("a", 10))
        val id = p.clips[0].id
        assertEquals(4f, p.setClipSpeed(id, 10f).clips[0].speed)
        assertEquals(2f, p.setClipVolume(id, 5f).clips[0].volume)
        assertEquals(0f, p.setClipVolume(id, -1f).clips[0].volume)
    }

    @Test
    fun `move, duplicate and delete`() {
        val p = project(video("a", 1), video("b", 2), video("c", 3))
        val c = p.clips[2].id
        assertEquals(listOf("c", "a", "b"), p.moveClip(c, -5).clips.map { it.source.displayName })
        val d = p.duplicateClip(p.clips[0].id)
        assertEquals(listOf("a", "a", "b", "c"), d.clips.map { it.source.displayName })
        assertEquals(listOf("a", "c"), p.deleteClip(p.clips[1].id).clips.map { it.source.displayName })
    }

    @Test
    fun `detaching audio silences the clip and lines the sound up where it was`() {
        val p = project(video("a", 4), video("b", 6))
        val b = p.clips[1]
        val d = p.detachAudio(b.id)
        assertTrue(d.clips[1].audioDetached)
        assertFalse(d.clips[1].contributesAudio)
        val piece = d.audio.single()
        assertEquals(AudioKind.DETACHED, piece.kind)
        assertEquals(b.id, piece.linkedClipId)
        assertEquals(4 * SEC, piece.startUs)
        assertEquals(b.trimStartUs, piece.trimStartUs)
        assertEquals(b.trimEndUs, piece.trimEndUs)
        // Twice is once.
        assertEquals(d, d.detachAudio(b.id))
    }

    @Test
    fun `a clip without sound cannot be detached`() {
        val p = project(video("silent", 4, audio = false))
        assertSame(p, p.detachAudio(p.clips[0].id))
    }

    @Test
    fun `restoring audio removes every piece split off the clip`() {
        val p = project(video("a", 10))
        val id = p.clips[0].id
        val d = p.detachAudio(id)
        val split = d.splitAudioAt(d.audio[0].id, 5 * SEC)
        assertEquals(2, split.audio.size)
        val r = split.restoreAudio(id)
        assertTrue(r.audio.isEmpty())
        assertTrue(r.clips[0].contributesAudio)
    }

    @Test
    fun `detached sound moves, trims and fades on its own`() {
        val p = project(video("a", 10)).let { it.detachAudio(it.clips[0].id) }
        val id = p.audio[0].id
        val moved = p.moveAudio(id, 2 * SEC)
        assertEquals(2 * SEC, moved.audio[0].startUs)
        assertEquals(0L, moved.clips.sumOf { it.trimStartUs }) // picture untouched
        // Trimming the head keeps the rest of the sound where it was.
        val trimmed = moved.trimAudio(id, 3 * SEC, 8 * SEC).audio[0]
        assertEquals(5 * SEC, trimmed.startUs)
        assertEquals(5 * SEC, trimmed.durationUs)
        // Fades are held to half the piece each.
        val faded = moved.trimAudio(id, 3 * SEC, 8 * SEC).setAudioFades(id, 9 * SEC, 1 * SEC).audio[0]
        assertEquals(2_500_000L, faded.fadeInUs)
        assertEquals(1 * SEC, faded.fadeOutUs)
        assertTrue(p.deleteAudio(id).audio.isEmpty())
    }

    @Test
    fun `audio split hands out the fades to the right halves`() {
        val p = project(video("a", 10)).addAudio(music("song", 8), 1 * SEC)
        val id = p.audio[0].id
        val faded = p.setAudioFades(id, 1 * SEC, 2 * SEC)
        val s = faded.splitAudioAt(id, 5 * SEC)
        assertEquals(listOf(1 * SEC, 0L), s.audio.map { it.fadeInUs })
        assertEquals(listOf(0L, 2 * SEC), s.audio.map { it.fadeOutUs })
        assertEquals(5 * SEC, s.audio[1].startUs)
        assertEquals(4 * SEC, s.audio[1].trimStartUs)
    }

    @Test
    fun `overlapping audio is packed into separate lanes`() {
        val p = project(video("a", 30))
            .addAudio(music("m1", 10), 0)
            .addAudio(music("m2", 10), 5 * SEC) // overlaps m1
            .addAudio(music("m3", 5), 12 * SEC) // fits after m1
        val lanes = assignLanes(p.audio)
        assertEquals(2, lanes.size)
        assertEquals(listOf("m1", "m3"), lanes[0].map { it.source.displayName })
        assertEquals(listOf("m2"), lanes[1].map { it.source.displayName })
    }

    @Test
    fun `text is kept on the timeline and at least the minimum length`() {
        val p = project(video("a", 10)).addText("Hello", 9 * SEC)
        val t = p.texts.single()
        assertEquals(9 * SEC, t.startUs)
        assertEquals(10 * SEC, t.endUs)
        val u = p.updateText(t.id) { it.copy(startUs = -3, endUs = -2) }.texts.single()
        assertEquals(0L, u.startUs)
        assertEquals(MIN_CLIP_US, u.endUs)
    }
}
