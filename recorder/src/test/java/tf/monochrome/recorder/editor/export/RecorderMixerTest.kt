package tf.monochrome.recorder.editor.export

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.recorder.editor.model.AudioRole
import tf.monochrome.recorder.editor.model.Mixer
import tf.monochrome.recorder.editor.model.MixerFx
import tf.monochrome.recorder.editor.model.RoleMix
import tf.monochrome.recorder.editor.model.SnapinType

class RecorderMixerTest {
    private fun route(processor: RouteAudio, channels: Int, samples: ShortArray): ShortArray {
        processor.configure(AudioFormat(MIX_SAMPLE_RATE, channels, C.ENCODING_PCM_16BIT))
        processor.flush()
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        input.asShortBuffer().put(samples)
        processor.queueInput(input)
        assertFalse(input.hasRemaining())
        val out = processor.output
        return ShortArray(out.remaining() / 2).also { out.asShortBuffer().get(it) }
    }

    @Test
    fun `each role's sound goes to its own pair and the rest stay silent`() {
        val frames = 1_300 // More than one chunk.
        val stereo = ShortArray(frames * 2) { (if (it % 2 == 0) it else -it).toShort() }
        val out = route(RouteAudio(AudioRole.MUSIC), 2, stereo)
        assertEquals(frames * 8, out.size)
        val pair = AudioRole.MUSIC.ordinal * 2
        for (f in 0 until frames) {
            for (c in 0 until 8) {
                val expected = when (c) {
                    pair -> stereo[f * 2]
                    pair + 1 -> stereo[f * 2 + 1]
                    else -> 0
                }
                assertEquals("frame $f channel $c", expected, out[f * 8 + c])
            }
        }
    }

    @Test
    fun `mono is heard on both sides`() {
        val mono = shortArrayOf(1000, -2000, 32767)
        val out = route(RouteAudio(AudioRole.DIALOGUE), 1, mono)
        for (f in mono.indices) {
            assertEquals(mono[f], out[f * 8])
            assertEquals(mono[f], out[f * 8 + 1])
            for (c in 2 until 8) assertEquals(0.toShort(), out[f * 8 + c])
        }
    }

    @Test
    fun `a silent item comes out as zeros without being read`() {
        val processor = RouteAudio(AudioRole.SFX, silent = true)
        // Whatever the input holds, a gap's silence is silence.
        val out = route(processor, 2, ShortArray(2_000) { 1234 })
        assertEquals(1_000 * 8, out.size)
        assertTrue(out.all { it == 0.toShort() })
    }

    @Test
    fun `the snapshot works out gain and pan, muting and solo once`() {
        val mixer = Mixer(
            roles = mapOf(
                AudioRole.MUSIC to RoleMix(gain = 0.5f, balance = 1f),
                AudioRole.SFX to RoleMix(muted = true),
            ),
            masterGain = 3f,
            masterBalance = -0.5f,
        )
        val c = MixerSnapshot(mixer).controls
        val music = AudioRole.MUSIC.ordinal * 2
        assertEquals(0f, c[music], 0f)
        assertEquals(0.5f, c[music + 1], 0f)
        assertEquals(0f, c[AudioRole.SFX.ordinal * 2], 0f)
        // Master: held to 1.5, panned left.
        assertEquals(1.5f, c[8], 0f)
        assertEquals(0.75f, c[9], 0f)
        // Solo silences the others.
        val solo = MixerSnapshot(mixer.copy(solo = AudioRole.DIALOGUE)).controls
        assertEquals(0f, solo[music + 1], 0f)
        assertEquals(1f, solo[AudioRole.DIALOGUE.ordinal * 2], 0f)
    }

    private class Recorded : ChainControl {
        val calls = mutableListOf<String>()
        override fun setWet(bus: Int, effect: Int, wet: Float) { calls += "wet $bus $effect $wet" }
        override fun setParameter(bus: Int, effect: Int, parameter: Int, value: Float) { calls += "param $bus $effect $parameter $value" }
    }

    @Test
    fun `a value change is sent in place, a new effect needs a new chain`() {
        val type = SnapinType.entries.first()
        val fx = MixerFx(type, id = "a", values = mapOf(0 to 0f))
        val before = BusFx(listOf(fx, fx.copy(id = "off", enabled = false)).filter { it.enabled })
        val tweaked = BusFx(listOf(fx.copy(wet = 0.5f, values = mapOf(0 to before.params[0][0] + 0.25f))))
        assertTrue(before.sameChain(tweaked))
        val control = Recorded()
        before.changeTo(tweaked, 2, control)
        assertEquals(listOf("wet 2 0 0.5", "param 2 0 0 ${tweaked.params[0][0]}"), control.calls)
        // Nothing changed: nothing sent.
        val quiet = Recorded()
        tweaked.changeTo(BusFx(listOf(fx.copy(wet = 0.5f, values = mapOf(0 to before.params[0][0] + 0.25f)))), 2, quiet)
        assertTrue(quiet.calls.isEmpty())
        // Another effect, or the same one oversampled, is a different chain.
        assertFalse(before.sameChain(BusFx(listOf(fx, fx.copy(id = "b")))))
        assertFalse(before.sameChain(BusFx(listOf(fx.copy(oversampling = 2)))))
        assertFalse(before.sameChain(BusFx.NONE))
        // What the native chain builder is handed.
        val two = BusFx(listOf(fx, fx.copy(id = "b")))
        assertEquals(two.params.sumOf { it.size }, two.flatParams().size)
        assertEquals(two.params.map { it.size }, two.counts().toList())
    }

    @Test
    fun `switched-off effects are not in the snapshot's chains`() {
        val type = SnapinType.entries.first()
        val mixer = Mixer(masterEffects = listOf(MixerFx(type, id = "a"), MixerFx(type, id = "b", enabled = false)))
        assertEquals(1, MixerSnapshot(mixer).buses[4].size)
    }
}
