package tf.monochrome.recorder.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class AacPickerTest {
    private fun enc(name: String, max: Int, formatOk: Boolean = true, cbr: Boolean = true) =
        AacCandidate(name = name, formatOk = formatOk, minBitrate = 8_000, maxBitrate = max, cbr = cbr)

    @Test
    fun `a first-listed encoder that cannot reach 320 is passed over`() {
        val phone = listOf(enc("c2.vendor.aac.hw.encoder", max = 256_000), enc("c2.android.aac.encoder", max = 960_000))
        assertEquals(AacChoice("c2.android.aac.encoder", 320_000, cbr = true), AacPicker.pick(phone, 320_000))
    }

    @Test
    fun `android's own encoder is preferred when several can do it`() {
        val phone = listOf(enc("c2.vendor.aac.encoder", max = 512_000), enc("c2.android.aac.encoder", max = 960_000))
        assertEquals("c2.android.aac.encoder", AacPicker.pick(phone, 320_000).name)
    }

    @Test
    fun `otherwise the phone's order decides`() {
        val phone = listOf(enc("c2.a.aac.encoder", max = 512_000), enc("c2.b.aac.encoder", max = 512_000))
        assertEquals("c2.a.aac.encoder", AacPicker.pick(phone, 320_000).name)
    }

    @Test
    fun `an encoder that cannot take 48 kHz stereo is never chosen`() {
        val phone = listOf(enc("c2.android.aac.encoder", max = 960_000, formatOk = false), enc("c2.vendor.aac.encoder", max = 320_000))
        assertEquals("c2.vendor.aac.encoder", AacPicker.pick(phone, 320_000).name)
    }

    @Test
    fun `when nothing reaches 320 the closest is used and says so`() {
        val phone = listOf(enc("c2.x.aac.encoder", max = 192_000), enc("c2.y.aac.encoder", max = 288_000, cbr = false))
        assertEquals(AacChoice("c2.y.aac.encoder", 288_000, cbr = false), AacPicker.pick(phone, 320_000))
    }

    @Test
    fun `no usable encoder falls back to the platform default at the bitrate asked`() {
        assertEquals(AacChoice(null, 320_000, cbr = false), AacPicker.pick(emptyList(), 320_000))
        assertEquals(AacChoice(null, 320_000, cbr = false), AacPicker.pick(listOf(enc("c2.z.aac.encoder", 960_000, formatOk = false)), 320_000))
    }
}
