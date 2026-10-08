package tf.monochrome.recorder.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.recorder.editor.model.Filter
import tf.monochrome.recorder.editor.model.GrainMotion
import tf.monochrome.recorder.editor.model.GrainPreset
import tf.monochrome.recorder.editor.model.VideoLook

class GrainPresetTest {
    @Test
    fun `a preset sets the grain and nothing else`() {
        val look = VideoLook(filter = Filter.WARM, brightness = 0.2f, fadeInUs = 5)
        val out = GrainPreset.MM16.applyTo(look)
        assertEquals(look.copy(grain = out.grain, grainSize = out.grainSize, grainDetail = out.grainDetail), out)
        assertTrue(GrainPreset.MM16.matches(out))
    }

    @Test
    fun `a preset keeps the chosen motion and still matches`() {
        val look = VideoLook(grainDetail = VideoLook().grainDetail.copy(motion = GrainMotion.FILM_24))
        val out = GrainPreset.FINE.applyTo(look)
        assertEquals(GrainMotion.FILM_24, out.grainDetail.motion)
        assertTrue(GrainPreset.FINE.matches(out))
    }

    @Test
    fun `any change makes it custom`() {
        val out = GrainPreset.MM35.applyTo(VideoLook())
        assertFalse(GrainPreset.MM35.matches(out.copy(grain = out.grain + 0.01f)))
        assertFalse(GrainPreset.MM35.matches(out.copy(grainDetail = out.grainDetail.copy(blue = 1f))))
        assertTrue(GrainPreset.entries.none { it.matches(VideoLook()) })
    }

    @Test
    fun `smaller film formats are grainier`() {
        val formats = listOf(GrainPreset.FINE, GrainPreset.MM35, GrainPreset.MM16, GrainPreset.SUPER8)
        assertTrue(formats.zipWithNext().all { (a, b) -> b.amount > a.amount && b.size > a.size })
        // Colour stocks' blue layer is the grainiest; black and white has no colour grain.
        assertTrue(formats.all { it.detail.blue > it.detail.green })
        assertEquals(0f, GrainPreset.MONO.detail.colour)
    }
}
