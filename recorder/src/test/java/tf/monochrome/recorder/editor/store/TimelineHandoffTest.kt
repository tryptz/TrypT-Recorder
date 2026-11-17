package tf.monochrome.recorder.editor.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import tf.monochrome.recorder.editor.project
import tf.monochrome.recorder.editor.video

class TimelineHandoffTest {
    private fun tracks(otio: String) =
        Json.parseToJsonElement(otio).jsonObject.getValue("tracks").jsonObject.getValue("children").jsonArray

    @Test fun aProjectWithNoClipsHandsOverAnEmptyTimeline() {
        // Bundle export of such a project used to crash on an unchecked cast.
        val otio = TimelineHandoff.create(project())
        val tracks = tracks(otio)
        assertEquals(1, tracks.size)
        assertEquals("V1", tracks[0].jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(0, tracks[0].jsonObject.getValue("children").jsonArray.size)
    }

    @Test fun aClipBecomesAVideoAndAnAudioTrack() {
        val tracks = tracks(TimelineHandoff.create(project(video("a", 4))))
        assertEquals(listOf("V1", "A1"), tracks.map { it.jsonObject.getValue("name").jsonPrimitive.content })
    }
}
