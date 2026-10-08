package tf.monochrome.recorder.editor

import org.junit.Assert.assertEquals
import org.junit.Test
import tf.monochrome.recorder.editor.model.Filter
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.model.TextPosition
import tf.monochrome.recorder.editor.model.VideoLook
import tf.monochrome.recorder.editor.model.addAudio
import tf.monochrome.recorder.editor.model.addText
import tf.monochrome.recorder.editor.model.detachAudio
import tf.monochrome.recorder.editor.store.ProjectStore

class ProjectJsonTest {
    @Test
    fun `a full project survives a round trip`() {
        val base = project(video("a", 10), video("b", 3))
        val p = base.detachAudio(base.clips[0].id)
            .addAudio(music("song", 30), 2 * SEC)
            .addText("Title", 0)
            .let { it.copy(look = it.look.copy(filter = Filter.SEPIA, brightness = 0.2f, grain = 0.4f, grainSize = 0.7f), texts = it.texts.map { t -> t.copy(position = TextPosition.TOP) }) }
        val json = ProjectStore.JSON.encodeToString(Project.serializer(), p)
        assertEquals(p, ProjectStore.JSON.decodeFromString(Project.serializer(), json))
    }

    @Test
    fun `drafts from before grain load without it`() {
        val json = """{"id":"x","name":"n","createdAt":1,"updatedAt":2,"look":{"brightness":0.1}}"""
        val look = ProjectStore.JSON.decodeFromString(Project.serializer(), json).look
        assertEquals(0f, look.grain)
        assertEquals(VideoLook.DEFAULT_GRAIN_SIZE, look.grainSize)
        assertEquals(0.1f, look.brightness)
    }

    @Test
    fun `drafts from a newer version with unknown fields still load`() {
        val json = """{"id":"x","name":"n","createdAt":1,"updatedAt":2,"futureField":true}"""
        assertEquals("x", ProjectStore.JSON.decodeFromString(Project.serializer(), json).id)
    }
}
