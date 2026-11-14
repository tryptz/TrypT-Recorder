@file:OptIn(UnstableApi::class)

package tf.monochrome.recorder.editor.export

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioMixer
import androidx.media3.transformer.DefaultAudioMixer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import tf.monochrome.recorder.editor.model.AudioRole
import tf.monochrome.recorder.editor.model.Mixer
import tf.monochrome.recorder.editor.model.MixerFx
import tf.monochrome.recorder.editor.model.ParamDef
import tf.monochrome.recorder.editor.model.getParamDefs
import kotlin.math.roundToInt

internal const val MIX_SAMPLE_RATE = 48_000
private val routes = AudioRole.entries
private val master = routes.size
private val packedChannels = routes.size * 2

/** PCM16 stereo pairs reserved for the four role buses inside Media3's graph. */
internal class RouteAudio(private val role: AudioRole) : BaseAudioProcessor() {
    private lateinit var matrix: ChannelMixingMatrix
    private var samples = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        check(inputAudioFormat.encoding == C.ENCODING_PCM_16BIT)
        matrix = if (inputAudioFormat.channelCount <= 2) {
            ChannelMixingMatrix.createForConstantGain(inputAudioFormat.channelCount, 2)
        } else {
            ChannelMixingMatrix.createForConstantPower(inputAudioFormat.channelCount, 2)
        }
        samples = FloatArray(inputAudioFormat.channelCount)
        return AudioFormat(inputAudioFormat.sampleRate, packedChannels, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frameCount = inputBuffer.remaining() / inputAudioFormat.bytesPerFrame
        val out = replaceOutputBuffer(frameCount * packedChannels * Short.SIZE_BYTES)
        repeat(frameCount) {
            for (channel in samples.indices) samples[channel] = inputBuffer.short.toFloat()
            for (channel in 0 until packedChannels) {
                val sample = if (channel / 2 == role.ordinal) {
                    var value = 0f
                    for (i in samples.indices) value += samples[i] * matrix.getMixingCoefficient(i, channel % 2)
                    value.roundToInt().coerceIn(-32768, 32767).toShort()
                } else {
                    0.toShort()
                }
                out.putShort(sample)
            }
        }
        out.flip()
    }
}

/** Exposes stereo to the preview audio sink and AAC encoder. */
internal class UnpackMaster : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        check(inputAudioFormat.channelCount == packedChannels)
        return AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / inputAudioFormat.bytesPerFrame
        val out = replaceOutputBuffer(frames * 2 * Short.SIZE_BYTES)
        repeat(frames) {
            val start = inputBuffer.position()
            out.putShort(inputBuffer.short)
            out.putShort(inputBuffer.short)
            inputBuffer.position(start + inputAudioFormat.bytesPerFrame)
        }
        out.flip()
    }
}

internal object NativeRecorderMixer {
    init {
        System.loadLibrary("recorder_dsp")
    }

    external fun create(sampleRate: Int): Long
    external fun destroy(handle: Long)
    external fun clear(handle: Long, bus: Int)
    external fun append(handle: Long, bus: Int, type: Int, params: FloatArray, wet: Float, oversampling: Int)
    external fun setParameter(handle: Long, bus: Int, effect: Int, parameter: Int, value: Float)
    external fun setWet(handle: Long, bus: Int, effect: Int, wet: Float)
    external fun render(
        handle: Long,
        input: ByteBuffer,
        inputOffset: Int,
        output: ByteBuffer,
        frames: Int,
        controls: FloatArray,
        fxEnabled: Boolean,
        peaks: FloatArray,
    )
}

/**
 * Uses Media3 for alignment, gaps and float accumulation. The native mixer
 * receives the role sums, applies the insert chains and produces the master.
 */
internal class RecorderAudioMixerFactory(
    initialMixer: Mixer = Mixer(),
    private val publishPeaks: ((FloatArray) -> Unit)? = null,
    private val audioTap: ((ByteBuffer, Int, Int) -> Unit)? = null,
) : AudioMixer.Factory {
    @Volatile var settings: Mixer = initialMixer

    override fun create(): AudioMixer =
        RecorderAudioMixer(DefaultAudioMixer.Factory(false, false).create())

    private inner class RecorderAudioMixer(
        private val mixer: AudioMixer,
    ) : AudioMixer by mixer {
        private var handle = 0L
        private var previous: Mixer? = null
        private var output = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
        private val controls = FloatArray((master + 1) * 2)
        private val peaks = FloatArray(controls.size)
        private val peakHold = FloatArray(controls.size)
        private var lastPublishNs = 0L

        override fun configure(outputAudioFormat: AudioFormat, bufferSizeMs: Int, startTimeUs: Long) {
            if (outputAudioFormat.channelCount != packedChannels || outputAudioFormat.sampleRate != MIX_SAMPLE_RATE) {
                // Not the routed format: pass straight through, never through a native mixer
                // left over from an earlier configuration.
                if (handle != 0L) NativeRecorderMixer.destroy(handle)
                handle = 0L
                previous = null
                mixer.configure(outputAudioFormat, bufferSizeMs, startTimeUs)
                return
            }
            mixer.configure(
                AudioFormat(outputAudioFormat.sampleRate, packedChannels, C.ENCODING_PCM_FLOAT),
                bufferSizeMs,
                startTimeUs,
            )
            // Configured again without a reset: start from a fresh native mixer,
            // and forget what the old one had, or settings would be applied as
            // changes to effects the new one never got.
            if (handle != 0L) NativeRecorderMixer.destroy(handle)
            previous = null
            handle = NativeRecorderMixer.create(outputAudioFormat.sampleRate)
        }

        override fun getOutput(): ByteBuffer {
            val mixed = mixer.output
            if (handle == 0L || !mixed.hasRemaining()) return mixed
            val frames = mixed.remaining() / (packedChannels * Float.SIZE_BYTES)
            val size = frames * packedChannels * Short.SIZE_BYTES
            if (output.capacity() < size) {
                output = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            }
            val snapshot = settings
            if (snapshot !== previous) applySettings(snapshot)
            NativeRecorderMixer.render(handle, mixed, mixed.position(), output, frames, controls, snapshot.fxEnabled, peaks)
            publishLevels()
            output.position(0)
            output.limit(size)
            audioTap?.invoke(output, frames, packedChannels * Short.SIZE_BYTES)
            return output
        }

        private fun applySettings(snapshot: Mixer) {
            for (index in 0..master) {
                val role = routes.getOrNull(index)
                val channel = snapshot.channel(role)
                val old = previous?.channel(role)
                if (old?.effects != channel.effects) {
                    syncEffects(index, old?.effects.orEmpty().filter { it.enabled }, channel.effects.filter { it.enabled })
                }
                val muted = if (role == null) channel.muted else !snapshot.isRouted(role)
                val gain = if (muted) 0f else channel.gain.coerceIn(0f, 1.5f)
                val pan = channel.balance.coerceIn(-1f, 1f)
                controls[index * 2] = gain * (1f - pan.coerceAtLeast(0f))
                controls[index * 2 + 1] = gain * (1f + pan.coerceAtMost(0f))
            }
            previous = snapshot
        }

        private fun syncEffects(bus: Int, before: List<MixerFx>, after: List<MixerFx>) {
            val sameOrder = before.size == after.size && after.indices.all { i ->
                before[i].id == after[i].id && before[i].type == after[i].type &&
                    before[i].oversampling == after[i].oversampling
            }
            if (!sameOrder) {
                NativeRecorderMixer.clear(handle, bus)
                for (fx in after) {
                    val defs = getParamDefs(fx.type)
                    val params = FloatArray(defs.size) { i -> value(fx, i, defs[i]) }
                    NativeRecorderMixer.append(handle, bus, fx.type.ordinal, params, fx.wet.coerceIn(0f, 1f), fx.oversampling)
                }
                return
            }
            for (i in after.indices) {
                val old = before[i]
                val fx = after[i]
                if (old.wet != fx.wet) {
                    NativeRecorderMixer.setWet(handle, bus, i, fx.wet.coerceIn(0f, 1f))
                }
                if (old.values != fx.values) {
                    getParamDefs(fx.type).forEachIndexed { parameter, def ->
                        val next = value(fx, parameter, def)
                        if (next != value(old, parameter, def)) {
                            NativeRecorderMixer.setParameter(handle, bus, i, parameter, next)
                        }
                    }
                }
            }
        }

        private fun value(fx: MixerFx, index: Int, def: ParamDef): Float =
            fx.values[index]?.takeIf(Float::isFinite)?.coerceIn(def.min, def.max) ?: def.default

        private fun publishLevels() {
            val publish = publishPeaks ?: return
            for (i in peaks.indices) peakHold[i] = maxOf(peakHold[i], peaks[i])
            val now = System.nanoTime()
            if (now - lastPublishNs >= 33_000_000) {
                publish(peakHold.copyOf())
                peakHold.fill(0f)
                lastPublishNs = now
            }
        }

        override fun reset() {
            mixer.reset()
            if (handle != 0L) NativeRecorderMixer.destroy(handle)
            handle = 0L
            previous = null
            peakHold.fill(0f)
            lastPublishNs = 0L
        }
    }
}
