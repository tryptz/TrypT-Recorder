package tf.monochrome.recorder.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun theSizeLimitFinishesAShorterValidStemInsteadOfFailing() {
        val file = folder.newFile("full.wav")
        // Room for three frames.
        val wav = WavStem(file, 0, maxDataBytes = 12)
        wav.append(0, shortArrayOf(1, -1, 2, -2), 2)
        wav.append(42, shortArrayOf(3, -3, 4, -4), 2)
        wav.append(84, shortArrayOf(5, -5, 6, -6), 2)
        val stem = wav.finish(AudioRole.DEVICE)
        assertTrue(stem.truncated)
        assertEquals(3L * 1_000_000 / 48_000, stem.durationUs)
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(12 + 36, buffer.getInt(4))
        assertEquals(12, buffer.getInt(40))
        assertEquals(44 + 12, file.length().toInt())
        buffer.position(44)
        val samples = ShortArray(6)
        buffer.asShortBuffer().get(samples)
        assertArrayEquals(shortArrayOf(1, -1, 2, -2, 3, -3), samples)
    }

    @Test fun aGapPastTheLimitIsCutAtTheLimit() {
        val file = folder.newFile("gap.wav")
        val wav = WavStem(file, 0, maxDataBytes = 16)
        wav.append(0, shortArrayOf(1, -1), 1)
        wav.append(1_000_000, shortArrayOf(9, -9), 1)
        val stem = wav.finish(AudioRole.DIALOGUE)
        assertTrue(stem.truncated)
        assertEquals(44 + 16, file.length().toInt())
    }

    @Test fun theLimitKeepsBothHeaderSizesWithin32Bits() {
        assertEquals(0L, WavStem.MAX_DATA_BYTES % 4)
        assertTrue(WavStem.MAX_DATA_BYTES + 36 <= 0xffff_ffffL)
        assertFalse(WavStem.MAX_DATA_BYTES + 4 + 36 <= 0xffff_ffffL)
    }
}
