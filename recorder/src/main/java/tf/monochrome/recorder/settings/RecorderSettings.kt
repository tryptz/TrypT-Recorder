package tf.monochrome.recorder.settings

/** What ends up on the recording's sound track. */
enum class AudioSource(val needsRecordPermission: Boolean, val usesDevice: Boolean, val usesMic: Boolean) {
    NONE(false, false, false),
    /** Other apps' media/game audio, captured digitally (AudioPlaybackCapture) — no room noise. */
    DEVICE(true, true, false),
    MICROPHONE(true, false, true),
    /** Both, mixed into one stereo track: a voice-over on top of the device sound. */
    DEVICE_AND_MIC(true, true, true),
}

/** Cap on the capture's short side; null records at the panel's own resolution. */
enum class ResolutionPreset(val shortSide: Int?) {
    NATIVE(null),
    P1440(1440),
    P1080(1080),
    P720(720),
    P480(480),
}

/** Bits per pixel per frame. MEDIUM is the debug recorder's original 0.08. */
enum class QualityPreset(val bitsPerPixel: Double) {
    LOW(0.04),
    MEDIUM(0.08),
    HIGH(0.14),
}

data class RecorderSettings(
    val audioSource: AudioSource = AudioSource.DEVICE,
    val resolution: ResolutionPreset = ResolutionPreset.P1080,
    /**
     * Capped by the panel's fastest refresh, so 165 means "as fast as this
     * screen goes": 165 on a 165 Hz phone, 120 on a 120 Hz one, 60 on 60.
     */
    val frameRate: Int = 165,
    val quality: QualityPreset = QualityPreset.MEDIUM,
    val countdownSeconds: Int = 3,
    /** Whether the floating record button should be on screen. */
    val floatingButton: Boolean = false,
    /** Take the floating button off screen while recording so it is not in the video. */
    val hideButtonWhileRecording: Boolean = false,
    val editReady: Boolean = false,
) {
    companion object {
        val FRAME_RATES = listOf(24, 30, 60, 90, 120, 144, 165)
        val COUNTDOWNS = listOf(0, 3, 5, 10)
    }
}
