package tf.monochrome.recorder.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.recorder.editor.export.MusicLinkAnalysis
import tf.monochrome.recorder.editor.export.MusicLinkTimeline
import tf.monochrome.recorder.editor.model.AudioRole
import tf.monochrome.recorder.editor.model.LinkBand
import tf.monochrome.recorder.editor.model.LinkShape
import tf.monochrome.recorder.editor.model.LinkSmoothing
import tf.monochrome.recorder.editor.model.LinkWay
import tf.monochrome.recorder.editor.model.Mixer
import tf.monochrome.recorder.editor.model.MixerFx
import tf.monochrome.recorder.editor.model.RoleMix
import tf.monochrome.recorder.editor.model.SnapinType
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

    @Test fun aLinkedControlMovesAroundItsSliderAndStaysInRange() {
        val timeline = MusicLinkTimeline(mapOf(RecorderLinkTarget.BRIGHTNESS.key to floatArrayOf(0.5f, 0.5f)))
        // The slider is the base, the link adds to it.
        assertEquals(0.8f, timeline.linked(RecorderLinkTarget.BRIGHTNESS, 0.3f, 0), 0.0001f)
        assertEquals(0.5f, timeline.linked(RecorderLinkTarget.BRIGHTNESS, 0f, 0), 0.0001f)
        // Held to the control's range.
        assertEquals(1f, timeline.linked(RecorderLinkTarget.BRIGHTNESS, 0.8f, 0), 0f)
        assertEquals(-0.5f, timeline.linked(RecorderLinkTarget.BRIGHTNESS, -1f, 0), 0.0001f)
        // An unlinked control is left to the static effect: nothing here.
        assertEquals(0f, timeline.linked(RecorderLinkTarget.CONTRAST, 0.4f, 0), 0f)
    }

    @Test fun pauseWhenSilentReturnsTheLinkToRestAndTheMusicWakesIt() {
        for (way in LinkWay.entries) {
            val shape = LinkShape(lowest = 0.1f, highest = 0.6f, way = way, smoothing = LinkSmoothing.OFF, feel = 1f)
            val follower = MusicLinkFollower(shape)
            val rest = follower.restValue()
            assertEquals(
                when (way) { LinkWay.UP -> 0.1f; LinkWay.DOWN -> 0.6f; LinkWay.BOTH -> 0.35f },
                rest, 0.0001f,
            )
            // Music, then just under the silent threshold: the smooth feel leaves a tail.
            repeat(100) { i -> follower.follow(if (i % 10 == 0) -6f else -30f, 0.02f) }
            val steps = (LinkShape.SILENT_PAUSE_SEC / 0.02f).toInt()
            repeat(steps - 2) { follower.follow(-90f, 0.02f) }
            assertTrue(!follower.asleep)
            repeat(4) { follower.follow(-90f, 0.02f) }
            assertTrue(follower.asleep)
            assertEquals(rest, follower.follow(-90f, 0.02f), 0f)
            // The first sound wakes it.
            follower.follow(-6f, 0.02f)
            assertTrue(!follower.asleep)
        }
    }

    @Test fun withPauseWhenSilentOffTheLinkKeepsFollowing() {
        val follower = MusicLinkFollower(LinkShape(lowest = 0f, highest = 1f, pauseWhenSilent = false))
        repeat(500) { follower.follow(-90f, 0.02f) }
        assertTrue(!follower.asleep)
    }

    @Test fun theAnalysisKeyIgnoresMixerChangesThatCannotChangeTheSound() {
        val reverb = MixerFx(SnapinType.entries.first(), id = "a", values = mapOf(0 to 0.3f))
        val off = MixerFx(SnapinType.entries.first(), id = "b", enabled = false)
        val mixer = Mixer(roles = mapOf(AudioRole.MUSIC to RoleMix(effects = listOf(reverb, off))), masterEffects = listOf(reverb))
        val signal = MusicLinkAnalysis.signalOf(mixer)
        // Switched-off effects and the editor's ids do not shape the sound.
        assertEquals(signal, MusicLinkAnalysis.signalOf(mixer.edit(AudioRole.MUSIC) { it.copy(effects = listOf(reverb.copy(id = "z"))) }))
        // Everything that does still counts.
        assertNotEquals(signal, MusicLinkAnalysis.signalOf(mixer.edit(AudioRole.MUSIC) { it.copy(gain = 0.5f) }))
        assertNotEquals(signal, MusicLinkAnalysis.signalOf(mixer.copy(masterEffects = listOf(reverb.copy(values = mapOf(0 to 0.9f))))))
        // With the FX switch off, no effect matters.
        val bypassed = mixer.copy(fxEnabled = false)
        assertEquals(MusicLinkAnalysis.signalOf(bypassed), MusicLinkAnalysis.signalOf(bypassed.copy(masterEffects = emptyList())))
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
