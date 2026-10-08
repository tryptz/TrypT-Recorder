package tf.monochrome.recorder.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDetailsTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `H264 profile and level from an Annex-B SPS, as MediaExtractor gives it`() {
        // 00 00 00 01 | 67 (SPS) | 64 = High | 00 | 34 = level 5.2 | …
        val sps = bytes(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x34, 0xAC, 0xD9)
        assertEquals(Triple(100, 0, 52), Codecs.avcProfileLevel(sps))
        assertEquals("High", Codecs.avcProfile(100, 0))
        assertEquals("5.2", Codecs.avcLevel(52))
        // A 3-byte start code works too.
        assertEquals(Triple(77, 0, 40), Codecs.avcProfileLevel(bytes(0, 0, 1, 0x67, 77, 0, 40)))
    }

    @Test
    fun `H264 profile and level from an avcC record`() {
        assertEquals(Triple(66, 0xC0, 31), Codecs.avcProfileLevel(bytes(1, 66, 0xC0, 31, 0xFF)))
        assertEquals("Constrained Baseline", Codecs.avcProfile(66, 0xC0))
        assertEquals("Baseline", Codecs.avcProfile(66, 0))
        assertEquals("3.1", Codecs.avcLevel(31))
        assertEquals("4", Codecs.avcLevel(40))
        assertEquals("1b", Codecs.avcLevel(9))
    }

    @Test
    fun `no SPS, no profile`() {
        assertNull(Codecs.avcProfileLevel(bytes(0, 0, 0, 1, 0x68, 1, 2, 3))) // a PPS
        assertNull(Codecs.avcProfileLevel(ByteArray(0)))
    }

    @Test
    fun `AAC object type from the AudioSpecificConfig`() {
        // 0x11 0x90: object type 2 (LC), 48 kHz, stereo — what the recorder writes.
        assertEquals(2, Codecs.aacObjectType(bytes(0x11, 0x90)))
        assertEquals("AAC-LC", Codecs.audio("audio/mp4a-latm", bytes(0x11, 0x90)))
        assertEquals("HE-AAC", Codecs.aacName(5))
        // Escape value 31: type = 32 + next six bits. 0xF8 0x20 → 32 + 1 = 33.
        assertEquals(33, Codecs.aacObjectType(bytes(0xF8, 0x20)))
        assertEquals("AAC", Codecs.audio("audio/mp4a-latm", null))
    }

    @Test
    fun `codec and container names`() {
        assertEquals("H.264", Codecs.video("video/avc"))
        assertEquals("H.265", Codecs.video("video/hevc"))
        assertEquals("AV1", Codecs.video("video/av01"))
        assertEquals("Opus", Codecs.audio("audio/opus", null))
        assertEquals("MP4", Codecs.container("video/mp4"))
        assertEquals("video/x-unknown", Codecs.video("video/x-unknown"))
    }

    @Test
    fun `bitrates and rates`() {
        // 40 kB/s for 10 s = 320 kbps.
        assertEquals(320_000L, Rates.bitrate(400_000, 10_000_000))
        assertEquals("320 kbps", Rates.formatBitrate(319_800))
        assertEquals("31.2 Mbps", Rates.formatBitrate(31_234_567))
        assertEquals(0L, Rates.bitrate(1_000, 0))
        assertEquals(165f, Rates.averageFps(1_650, 10_000_000), 0.001f)
        assertEquals("165", Rates.formatFps(165.02f))
        assertEquals("59.94", Rates.formatFps(59.94f))
        assertEquals("48 kHz", Rates.formatSampleRate(48_000))
        assertEquals("44.1 kHz", Rates.formatSampleRate(44_100))
        assertEquals("stereo", Rates.channels(2))
        assertTrue(Rates.isVariable(165f, 98f))
        assertFalse(Rates.isVariable(60f, 59.9f))
    }

    @Test
    fun `summary line reads like a media-info app`() {
        val d = MediaDetails(
            container = "MP4",
            durationUs = 10_000_000,
            sizeBytes = 40_000_000,
            video = VideoDetails("H.264", "High", "5.2", 1080, 2400, peakFps = 165f, averageFps = 98.4f, frames = 984, bitrate = 31_200_000, approximate = false),
            audio = AudioDetails("AAC-LC", 48_000, 2, 320_000, approximate = false),
        )
        assertEquals("1080×2400 · 165 fps · H.264 High · 31.2 Mbps · AAC-LC 320 kbps stereo", d.summary())
        assertEquals(32_000_000L, d.overallBitrate)
        val silent = d.copy(audio = null, video = d.video!!.copy(peakFps = 0f, averageFps = 60f, profile = null))
        assertEquals("1080×2400 · 60 fps · H.264 · 31.2 Mbps", silent.summary())
    }

    @Test
    fun `peak rate from a primitive array matches the list version`() {
        val times = LongArray(1000) { it * 1_000_000L / 165 }
        assertEquals(FrameTiming.peakFrameRate(times.toList()), FrameTiming.peakFrameRate(times, times.size), 0f)
        // Only the first count entries count.
        assertEquals(0f, FrameTiming.peakFrameRate(times, 2), 0f)
    }
}
