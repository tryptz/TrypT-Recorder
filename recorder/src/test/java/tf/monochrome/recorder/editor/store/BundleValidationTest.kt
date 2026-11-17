package tf.monochrome.recorder.editor.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.recorder.editor.model.CubeLut
import tf.monochrome.recorder.editor.model.LutColors
import tf.monochrome.recorder.editor.model.SpeedKey
import tf.monochrome.recorder.editor.music
import tf.monochrome.recorder.editor.project
import tf.monochrome.recorder.editor.video
import tf.monochrome.recorder.editor.model.AudioClip
import tf.monochrome.recorder.editor.model.Project

class BundleValidationTest {
    private val base = project(video("a", 10))

    private fun withClip(edit: (tf.monochrome.recorder.editor.model.VideoClip) -> tf.monochrome.recorder.editor.model.VideoClip): Project =
        base.copy(clips = base.clips.map(edit))

    @Test fun anOrdinaryProjectPasses() {
        assertTrue(BundleValidation.problems(base).isEmpty())
    }

    @Test fun zeroNegativeAndNonFiniteSpeedsAreRejected() {
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals("speed $bad", 1, BundleValidation.problems(withClip { it.copy(speed = bad) }).size)
        }
    }

    @Test fun badSpeedKeyRatesAreRejected() {
        for (bad in listOf(0f, -0.5f, Float.NaN)) {
            val p = withClip { it.copy(speedKeys = listOf(SpeedKey(0, 1f), SpeedKey(1_000_000, bad))) }
            assertEquals("rate $bad", 1, BundleValidation.problems(p).size)
        }
        val audio = AudioClip("x", music("m", 5), 0, 5_000_000, 0, speed = 0f)
        assertEquals(1, BundleValidation.problems(base.copy(audio = listOf(audio))).size)
    }

    @Test fun aLutMustHoldSizeCubedColours() {
        val good = CubeLut("ok", 2, LutColors(IntArray(8)))
        val short = CubeLut("short", 3, LutColors(IntArray(26)))
        val huge = CubeLut("huge", 0, LutColors(IntArray(0)))
        fun graded(lut: CubeLut) = withClip { it.copy(picture = it.picture.copy(grade = it.picture.grade.copy(lut = lut))) }
        assertTrue(BundleValidation.problems(graded(good)).isEmpty())
        assertEquals(1, BundleValidation.problems(graded(short)).size)
        assertEquals(1, BundleValidation.problems(graded(huge)).size)
    }

    @Test fun mediaNamesStayStrict() {
        assertTrue(BundleValidation.mediaPath.matches("media/000001.mp4"))
        assertFalse(BundleValidation.mediaPath.matches("media/"))
        assertFalse(BundleValidation.mediaPath.matches("media/../x.mp4"))
        assertFalse(BundleValidation.mediaPath.matches("media/1.mp4"))
    }
}
