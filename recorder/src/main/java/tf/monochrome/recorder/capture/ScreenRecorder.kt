package tf.monochrome.recorder.capture

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import tf.monochrome.recorder.settings.AudioSource
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Everything a capture is configured with, decided before it starts. */
data class CaptureSpec(
    val video: VideoPlan,
    val densityDpi: Int,
    val audio: AudioSource,
)

/**
 * Screen + audio capture into an MP4, grown out of Tryptify's
 * DebugScreenRecorder.
 *
 * Video: a VirtualDisplay mirrors the default display into an H.264 surface
 * encoder. Audio: 48 kHz stereo PCM16 → 320 kbps AAC, from the device's playback
 * (AudioPlaybackCapture: media, game and unknown usages — no microphone, no
 * room, no AGC), the microphone, or both mixed. Both tracks are stamped on the
 * monotonic clock, as in the original, so MediaMuxer keeps them in sync.
 *
 * What changed from the debug tool, and why:
 *  - Pause/resume: the display's surface is detached while paused and the gap
 *    is cut out of every timestamp by [PauseClock].
 *  - The muxer gate no longer blocks a drain thread waiting for the other
 *    track's format. Samples that arrive before the muxer can start are
 *    queued, and if audio never produces a format (a capture that failed to
 *    start) the file is written without it instead of not at all.
 *  - Size, frame rate and bitrate come from settings ([CapturePlanner] and
 *    [EncoderFit]) rather than always being the panel's native mode.
 *
 * Caveat inherited from the original: apps can opt out of playback capture,
 * and audio that bypasses AudioFlinger cannot be captured at all.
 */
class ScreenRecorder(
    private val projection: MediaProjection,
    private val spec: CaptureSpec,
) {
    /** Called at most once, from a worker thread, if the capture fails while running. */
    var onFatalError: ((Throwable) -> Unit)? = null

    private val running = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val clock = PauseClock()

    private var muxer: MediaMuxer? = null
    private var gate: MuxerGate? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var deviceRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null

    private var videoThread: Thread? = null
    private var audioPumpThread: Thread? = null
    private var audioDrainThread: Thread? = null

    /** The audio actually being recorded — less than asked for if a source failed to open. */
    var effectiveAudio: AudioSource = AudioSource.NONE
        private set

    /** AAC bitrate the encoder was configured with, in bits/s; 0 when recording without sound. */
    var audioBitrate: Int = 0
        private set

    val isPaused: Boolean get() = clock.isPaused

    /**
     * Starts capturing into [fd]. [audioPermitted] is whether RECORD_AUDIO is
     * granted; without it every audio source degrades to none.
     */
    fun start(fd: FileDescriptor, audioPermitted: Boolean) {
        check(running.compareAndSet(false, true)) { "already recording" }
        val v = spec.video
        Log.i(TAG, "capture ${v.width}x${v.height}@${v.fps} ${v.bitrate / 1000} kbps audio=${spec.audio}")

        openAudio(if (audioPermitted) spec.audio else AudioSource.NONE)

        val mux = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer = mux
        val g = MuxerGate(mux, expectAudio = effectiveAudio != AudioSource.NONE, clock)
        gate = g

        // ── Video ───────────────────────────────────────────────────────
        val vCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            vCodec.configure(EncoderFit.videoFormat(v, realtime = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some encoders refuse the real-time/operating-rate hints at high
            // frame rates while accepting the format itself.
            Log.w(TAG, "encoder refused real-time hints; configuring without them", e)
            vCodec.reset()
            vCodec.configure(EncoderFit.videoFormat(v, realtime = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val surface = vCodec.createInputSurface()
        vCodec.start()
        videoCodec = vCodec
        inputSurface = surface

        virtualDisplay = projection.createVirtualDisplay(
            "tryptify-recorder", v.width, v.height, spec.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null,
        )
        videoThread = Thread({ drainLoop(vCodec, isVideo = true, g) }, "rec-video").also { it.start() }

        // ── Audio ───────────────────────────────────────────────────────
        if (effectiveAudio != AudioSource.NONE) {
            val aac = EncoderFit.aacEncoder(AUDIO_BITRATE, SAMPLE_RATE, 2)
            val aCodec = aac.name?.let { MediaCodec.createByCodecName(it) }
                ?: MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val inputSize = BLOCK_FRAMES * 4 * 2
            try {
                aCodec.configure(
                    EncoderFit.aacFormat(aac, SAMPLE_RATE, 2).apply { setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, inputSize) },
                    null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
            } catch (e: Exception) {
                // An encoder that lists CBR but refuses it here: same bitrate, its own mode.
                Log.w(TAG, "AAC encoder refused CBR; configuring without it", e)
                aCodec.reset()
                aCodec.configure(
                    EncoderFit.aacFormat(aac, SAMPLE_RATE, 2, cbr = false).apply { setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, inputSize) },
                    null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
            }
            audioBitrate = aac.bitrate
            aCodec.start()
            audioCodec = aCodec
            deviceRecord?.startRecording()
            micRecord?.startRecording()
            audioPumpThread = Thread({ audioPumpLoop(aCodec) }, "rec-apump").also { it.start() }
            audioDrainThread = Thread({ drainLoop(aCodec, isVideo = false, g) }, "rec-adrain").also { it.start() }
        }
    }

    fun pause() {
        if (!running.get() || clock.isPaused) return
        // Detach first, then stamp: every frame rendered before this point has
        // an earlier timestamp than the pause.
        virtualDisplay?.surface = null
        clock.pause(nowUs())
    }

    fun resume() {
        if (!running.get() || !clock.isPaused) return
        clock.resume(nowUs())
        virtualDisplay?.surface = inputSurface
        // A key frame at the resume point keeps the cut clean for the editor.
        runCatching {
            videoCodec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    /**
     * Stops and finalises the file. Returns whether it is worth keeping: it
     * holds video and the muxer closed cleanly — which a capture that failed
     * part-way (storage filled up, say) can still manage.
     */
    fun stop(): Boolean {
        if (!running.compareAndSet(true, false)) return false
        // Video EOS via the surface; audio EOS via the pump thread.
        runCatching { virtualDisplay?.release() }
        runCatching { videoCodec?.signalEndOfInputStream() }
        runCatching { deviceRecord?.stop() }
        runCatching { micRecord?.stop() }
        videoThread?.join(3000)
        audioPumpThread?.join(3000)
        audioDrainThread?.join(3000)

        val wroteVideo = gate?.finish() ?: false
        runCatching { muxer?.release() }
        muxer = null
        runCatching { videoCodec?.stop() }; runCatching { videoCodec?.release() }
        runCatching { audioCodec?.stop() }; runCatching { audioCodec?.release() }
        runCatching { inputSurface?.release() }
        runCatching { deviceRecord?.release() }
        runCatching { micRecord?.release() }
        Log.i(TAG, "capture stopped, video=$wroteVideo")
        return wroteVideo
    }

    // ── Audio sources ──────────────────────────────────────────────────

    @SuppressLint("MissingPermission") // start() is only given audio when RECORD_AUDIO is granted
    private fun openAudio(requested: AudioSource) {
        val device = if (requested.usesDevice) openDeviceCapture() else null
        val mic = if (requested.usesMic) openMic() else null
        deviceRecord = device
        micRecord = mic
        effectiveAudio = when {
            device != null && mic != null -> AudioSource.DEVICE_AND_MIC
            device != null -> AudioSource.DEVICE
            mic != null -> AudioSource.MICROPHONE
            else -> AudioSource.NONE
        }
        if (effectiveAudio != requested) Log.w(TAG, "audio $requested degraded to $effectiveAudio")
    }

    @SuppressLint("MissingPermission")
    private fun openDeviceCapture(): AudioRecord? = runCatching {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(pcmFormat(AudioFormat.CHANNEL_IN_STEREO))
            .setBufferSizeInBytes(minBuffer(AudioFormat.CHANNEL_IN_STEREO) * 4)
            .build()
            .initializedOrNull()
    }.onFailure { Log.w(TAG, "device audio unavailable", it) }.getOrNull()

    /**
     * Stereo first: most phones with two or more microphones record a real
     * stereo image. Where the hardware cannot, it is opened mono and the one
     * channel goes to both sides.
     */
    private fun openMic(): AudioRecord? =
        openMic(AudioFormat.CHANNEL_IN_STEREO) ?: openMic(AudioFormat.CHANNEL_IN_MONO)

    @SuppressLint("MissingPermission")
    private fun openMic(mask: Int): AudioRecord? = runCatching {
        AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(pcmFormat(mask))
            .setBufferSizeInBytes(minBuffer(mask) * 4)
            .build()
            .initializedOrNull()
    }.onFailure { Log.w(TAG, "microphone unavailable with channel mask $mask", it) }.getOrNull()

    private fun AudioRecord.initializedOrNull(): AudioRecord? =
        if (state == AudioRecord.STATE_INITIALIZED) this else { release(); null }

    private fun pcmFormat(mask: Int) = AudioFormat.Builder()
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(mask)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .build()

    private fun minBuffer(mask: Int) =
        AudioRecord.getMinBufferSize(SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(BLOCK_FRAMES * 4)

    /**
     * Reads one block of interleaved stereo into [out]; returns frames read,
     * 0 for "nothing this time" and a negative AudioRecord error when the
     * source is gone.
     */
    private fun interface BlockReader {
        fun read(out: ShortArray): Int
    }

    private fun blockReader(): BlockReader {
        val device = deviceRecord
        val mic = micRecord
        val mono = ShortArray(BLOCK_FRAMES)
        // One block of microphone audio as interleaved stereo, whichever way it was opened.
        val readMic: (AudioRecord, ShortArray) -> Int = if (mic?.channelCount == 2) {
            { m, out ->
                val n = m.read(out, 0, BLOCK_FRAMES * 2, AudioRecord.READ_BLOCKING)
                if (n <= 0) n else n / 2
            }
        } else {
            { m, out ->
                val n = m.read(mono, 0, BLOCK_FRAMES, AudioRecord.READ_BLOCKING)
                if (n > 0) Pcm.monoToStereo(mono, n, out)
                n
            }
        }
        return when {
            device != null && mic != null -> {
                // The microphone is the master clock; device audio is drained
                // without waiting into a FIFO and taken back out in step with it.
                val fifo = ShortFifo(SAMPLE_RATE) // half a second of stereo
                val scratch = ShortArray(BLOCK_FRAMES * 2)
                val deviceBlock = ShortArray(BLOCK_FRAMES * 2)
                var deviceAlive = true
                BlockReader { out ->
                    val n = readMic(mic, out)
                    if (n <= 0) return@BlockReader n
                    while (deviceAlive) {
                        val d = device.read(scratch, 0, scratch.size, AudioRecord.READ_NON_BLOCKING)
                        if (d > 0) fifo.write(scratch, d) else {
                            if (d < 0) deviceAlive = false
                            break
                        }
                    }
                    fifo.readPadded(deviceBlock, n * 2)
                    Pcm.mixInto(out, deviceBlock, n * 2)
                    n
                }
            }
            device != null -> BlockReader { out ->
                val n = device.read(out, 0, BLOCK_FRAMES * 2, AudioRecord.READ_BLOCKING)
                if (n <= 0) n else n / 2
            }
            mic != null -> BlockReader { out -> readMic(mic, out) }
            else -> BlockReader { 0 }
        }
    }

    /** PCM blocks into the AAC encoder, stamped on the monotonic clock. */
    private fun audioPumpLoop(codec: MediaCodec) {
        val reader = blockReader()
        val block = ShortArray(BLOCK_FRAMES * 2)
        var anchorUs = -1L
        var framesSinceAnchor = 0L
        try {
            while (running.get()) {
                val frames = reader.read(block)
                if (frames < 0) {
                    Log.w(TAG, "audio source ended ($frames)")
                    break
                }
                if (frames == 0) continue
                if (clock.isPaused) {
                    // Keep draining the capture so it does not overflow, but
                    // nothing recorded while paused reaches the file. The next
                    // block re-anchors.
                    anchorUs = -1L
                    continue
                }
                if (anchorUs < 0) {
                    val blockUs = frames * 1_000_000L / SAMPLE_RATE
                    // The block just read began about blockUs ago — but never
                    // before the resume that ended the last pause.
                    anchorUs = maxOf(nowUs() - blockUs, clock.lastResumeUs())
                    framesSinceAnchor = 0
                }
                var offset = 0
                val samples = frames * 2
                while (offset < samples && running.get()) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx < 0) continue
                    val buf = codec.getInputBuffer(idx) ?: continue
                    buf.clear()
                    buf.order(ByteOrder.nativeOrder())
                    // Whole stereo frames only.
                    val fit = minOf(samples - offset, buf.remaining() / 2) and 1.inv()
                    val pts = anchorUs + framesSinceAnchor * 1_000_000L / SAMPLE_RATE
                    if (fit <= 0) {
                        // An input buffer smaller than one frame; hand it back
                        // rather than spin on it.
                        codec.queueInputBuffer(idx, 0, 0, pts, 0)
                        break
                    }
                    buf.asShortBuffer().put(block, offset, fit)
                    codec.queueInputBuffer(idx, 0, fit * 2, pts, 0)
                    framesSinceAnchor += fit / 2
                    offset += fit
                }
            }
        } catch (e: IllegalStateException) {
            // Codec torn down underneath us during stop.
            return
        }
        // End of stream.
        runCatching {
            while (true) {
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
            }
        }
    }

    /** Shared encoder→muxer drain; exits on EOS or codec teardown. */
    private fun drainLoop(codec: MediaCodec, isVideo: Boolean, gate: MuxerGate) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (e: IllegalStateException) {
                return
            }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> gate.addTrack(isVideo, codec.outputFormat)
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (buf != null && info.size > 0 &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        try {
                            gate.write(isVideo, buf, info)
                        } catch (e: Exception) {
                            fail(e)
                        }
                    }
                    runCatching { codec.releaseOutputBuffer(idx, false) }
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    private fun fail(e: Throwable) {
        if (failed.compareAndSet(false, true)) {
            Log.e(TAG, "capture failed", e)
            onFatalError?.invoke(e)
        }
    }

    /**
     * Starts the muxer once every expected track has a format, holding
     * samples that arrive before that, and writes pause-adjusted timestamps.
     */
    private class MuxerGate(
        private val muxer: MediaMuxer,
        private var expectAudio: Boolean,
        private val clock: PauseClock,
    ) {
        private class Pending(val isVideo: Boolean, val data: ByteBuffer, val ptsUs: Long, val flags: Int)

        private val lock = Any()
        private var started = false
        private var videoTrack = -1
        private var audioTrack = -1
        private var videoFormatAtMs = 0L
        private val pending = ArrayDeque<Pending>()
        private var pendingBytes = 0
        private val lastPts = longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE)
        private val info = MediaCodec.BufferInfo()
        private var videoSamples = 0

        fun addTrack(isVideo: Boolean, format: MediaFormat) = synchronized(lock) {
            // A track whose format turns up after the muxer started is left out.
            if (started) return@synchronized
            if (isVideo) {
                videoTrack = muxer.addTrack(format)
                videoFormatAtMs = SystemClock.elapsedRealtime()
            } else {
                audioTrack = muxer.addTrack(format)
            }
            maybeStart()
        }

        fun write(isVideo: Boolean, buf: ByteBuffer, src: MediaCodec.BufferInfo) = synchronized(lock) {
            if (!started && expectAudio && audioTrack < 0 && videoTrack >= 0 &&
                SystemClock.elapsedRealtime() - videoFormatAtMs > AUDIO_FORMAT_WAIT_MS
            ) {
                Log.w(TAG, "no audio format after ${AUDIO_FORMAT_WAIT_MS} ms; writing video only")
                expectAudio = false
                maybeStart()
            }
            val pts = clock.adjust(src.presentationTimeUs)
            if (started) {
                writeNow(isVideo, buf, src.offset, src.size, pts, src.flags)
            } else if (pendingBytes + src.size <= MAX_PENDING_BYTES) {
                val copy = ByteBuffer.allocateDirect(src.size)
                buf.position(src.offset)
                buf.limit(src.offset + src.size)
                copy.put(buf)
                copy.flip()
                pending.addLast(Pending(isVideo, copy, pts, src.flags))
                pendingBytes += src.size
            }
        }

        /** Flushes and stops the muxer. Returns whether a playable file with video came out. */
        fun finish(): Boolean = synchronized(lock) {
            if (!started && videoTrack >= 0) {
                expectAudio = false
                maybeStart()
            }
            if (!started) return@synchronized false
            val stopped = runCatching { muxer.stop() }.onFailure { Log.w(TAG, "muxer stop", it) }.isSuccess
            stopped && videoSamples > 0
        }

        private fun maybeStart() {
            if (started || videoTrack < 0 || (expectAudio && audioTrack < 0)) return
            muxer.start()
            started = true
            while (pending.isNotEmpty()) {
                val p = pending.removeFirst()
                writeNow(p.isVideo, p.data, 0, p.data.remaining(), p.ptsUs, p.flags)
            }
            pendingBytes = 0
        }

        private fun writeNow(isVideo: Boolean, buf: ByteBuffer, offset: Int, size: Int, ptsUs: Long, flags: Int) {
            val track = if (isVideo) videoTrack else audioTrack
            if (track < 0) return
            val slot = if (isVideo) 0 else 1
            // The muxer rejects time going backwards; a frame clamped into a
            // pause can tie with the one before it.
            val pts = if (ptsUs <= lastPts[slot]) lastPts[slot] + 1 else ptsUs
            lastPts[slot] = pts
            info.set(offset, size, pts, flags)
            muxer.writeSampleData(track, buf, info)
            if (isVideo) videoSamples++
        }
    }

    companion object {
        private const val TAG = "ScreenRecorder"
        const val SAMPLE_RATE = 48_000
        /** AAC-LC, 48 kHz stereo. The debug recorder used 256 kbps. */
        const val AUDIO_BITRATE = 320_000
        /** ~21 ms at 48 kHz — the AAC frame size, and small enough to keep the mix tight. */
        private const val BLOCK_FRAMES = 1024
        private const val AUDIO_FORMAT_WAIT_MS = 3_000L
        private const val MAX_PENDING_BYTES = 32 * 1024 * 1024

        private fun nowUs() = System.nanoTime() / 1000
    }
}
