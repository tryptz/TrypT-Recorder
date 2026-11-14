package tf.monochrome.recorder.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.recorder.editor.export.MusicLinkTimeline
import tf.monochrome.recorder.editor.model.LinkBand
import tf.monochrome.recorder.editor.model.LinkShape
import tf.monochrome.recorder.editor.model.MusicLinkFollower
import tf.monochrome.recorder.editor.model.RecorderLinkTarget
import tf.monochrome.recorder.editor.model.RecorderMusicLinks

class MusicLinksTest {
    @Test fun importsAndRemapsATryptifyPreset() {
        val input = """
            {
                "type":"tryptify-music-links","version":1,"name":"Pulse",
                "links":[{"target":"lyrics.godRayExposure","shape":{
                    "band":"KICK","lowest":12,"highest":30,"feel":0.25,"way":"DOWN"
                }}]
            }
        """.trimIndent()
        val link = RecorderMusicLinks.readPreset(input).single()
        val mapped = RecorderMusicLinks.remap(link, RecorderLinkTarget.EXPOSURE)
        assertEquals("recorder.exposure", mapped.target)
        assertEquals(LinkBand.KICK, mapped.shape.band)
        assertEquals(0.25f, mapped.shape.feel, 0f)
        assertEquals(0.35f, mapped.shape.highest, 0f)
        assertEquals(listOf(mapped), RecorderMusicLinks.decode(RecorderMusicLinks.encode(listOf(mapped, link))))
    }

    @Test fun soundtrackControlsInterpolateByPresentationTime() {
        val timeline = MusicLinkTimeline(mapOf(RecorderLinkTarget.CONTRAST.key to floatArrayOf(0f, 0.4f, 1f)))
        assertEquals(0.2f, timeline.value(RecorderLinkTarget.CONTRAST, 10_000), 0.0001f)
        assertEquals(0.7f, timeline.value(RecorderLinkTarget.CONTRAST, 30_000), 0.0001f)
        assertEquals(1f, timeline.value(RecorderLinkTarget.CONTRAST, 900_000), 0f)
        assertEquals(0f, timeline.value(RecorderLinkTarget.EXPOSURE, 30_000), 0f)
    }

    @Test fun aMusicBandHitMovesAControlAndSilenceLetsItRest() {
        val follower = MusicLinkFollower(LinkShape(lowest = 0f, highest = 0.6f))
        repeat(20) { follower.step(-120f, 0.02f) }
        val hit = follower.step(-12f, 0.02f)
        assertTrue(hit > 0.1f)
        repeat(150) { follower.step(-120f, 0.02f) }
        assertTrue(follower.asleep)
        assertTrue(follower.strength < 0.001f)
    }
}
