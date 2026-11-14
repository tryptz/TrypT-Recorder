package tf.monochrome.recorder.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tf.monochrome.recorder.editor.model.AudioRole
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavStemTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun clockGapIsPaddedAndOverlapIsDroppedInStereoFrames() {
        val file = folder.newFile("stem.wav")
        val wav = WavStem(file, 1_000)
        wav.append(1_000, shortArrayOf(100, -100, 200, -200), 2)
        wav.append(1_083, shortArrayOf(300, -300, 400, -400), 2)
        wav.append(1_104, shortArrayOf(500, -500, 600, -600), 2)
        val stem = wav.finish(AudioRole.DIALOGUE)
        assertEquals(1_000L, stem.startUs)
        assertEquals(145L, stem.durationUs)
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(28, buffer.getInt(40))
        buffer.position(44)
        val samples = ShortArray(14)
        buffer.asShortBuffer().get(samples)
        assertArrayEquals(shortArrayOf(100, -100, 200, -200, 0, 0, 0, 0, 300, -300, 400, -400, 600, -600), samples)
    }
}
