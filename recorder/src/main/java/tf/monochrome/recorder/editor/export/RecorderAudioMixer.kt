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
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray
import tf.monochrome.recorder.editor.model.AudioRole
import tf.monochrome.recorder.editor.model.Mixer
import tf.monochrome.recorder.editor.model.MixerFx
import tf.monochrome.recorder.editor.model.getParamDefs
import kotlin.math.roundToInt

internal const val MIX_SAMPLE_RATE = 48_000
private val routes = AudioRole.entries
private val master = routes.size
private val packedChannels = routes.size * 2

/**
 * PCM16 stereo pairs reserved for the four role buses inside Media3's graph.
 * The mixing coefficients are read once per format, samples move in bulk,
 * and the other roles' channels are zeros written once; a [silent] item (a
 * gap's silence file) is all zeros and is not read at all.
 */
internal class RouteAudio(private val role: AudioRole, private val silent: Boolean = false) : BaseAudioProcessor() {
    private var toLeft = FloatArray(0)
    private var toRight = FloatArray(0)
    private var input = ShortArray(0)

    /** [CHUNK] packed frames; only this role's two slots are ever written, the rest stay zero. */
    private val packed = ShortArray(CHUNK * packedChannels)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        check(inputAudioFormat.encoding == C.ENCODING_PCM_16BIT)
        val channels = inputAudioFormat.channelCount
        val matrix = if (channels <= 2) {
            ChannelMixingMatrix.createForConstantGain(channels, 2)
        } else {
            ChannelMixingMatrix.createForConstantPower(channels, 2)
        }
        toLeft = FloatArray(channels) { matrix.getMixingCoefficient(it, 0) }
        toRight = FloatArray(channels) { matrix.getMixingCoefficient(it, 1) }
        input = ShortArray(CHUNK * channels)
        packed.fill(0)
        return AudioFormat(inputAudioFormat.sampleRate, packedChannels, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val frameCount = inputBuffer.remaining() / inputAudioFormat.bytesPerFrame
        val outBytes = frameCount * packedChannels * Short.SIZE_BYTES
        val out = replaceOutputBuffer(outBytes)
        val dst = out.asShortBuffer()
        if (silent) {
            // A gap: zeros in, zeros out, for every channel.
            var left = frameCount * packedChannels
            while (left > 0) {
                val n = minOf(left, zeros.size)
                dst.put(zeros, 0, n)
                left -= n
            }
        } else {
            val src = inputBuffer.asShortBuffer()
            val base = role.ordinal * 2
            var done = 0
            while (done < frameCount) {
                val n = minOf(CHUNK, frameCount - done)
                src.get(input, 0, n * channels)
                var at = 0
                for (frame in 0 until n) {
                    var l = 0f
                    var r = 0f
                    for (c in 0 until channels) {
                        val sample = input[at++].toFloat()
                        l += sample * toLeft[c]
                        r += sample * toRight[c]
                    }
                    val slot = frame * packedChannels + base
                    packed[slot] = l.roundToInt().coerceIn(-32768, 32767).toShort()
                    packed[slot + 1] = r.roundToInt().coerceIn(-32768, 32767).toShort()
                }
                dst.put(packed, 0, n * packedChannels)
                done += n
            }
        }
        inputBuffer.position(inputBuffer.position() + frameCount * inputAudioFormat.bytesPerFrame)
        out.position(outBytes)
        out.flip()
    }

    private companion object {
        const val CHUNK = 512

        /** Shared and never written: zeros for silent items. */
        val zeros = ShortArray(CHUNK * packedChannels)
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

    /** A whole effect chain, built away from the audio thread; [destroyChain] frees it. */
    external fun prepareChain(
        sampleRate: Int,
        types: IntArray,
        params: FloatArray,
        counts: IntArray,
        wets: FloatArray,
        oversampling: IntArray,
    ): Long

    /** Swaps [chain]'s effects with [bus]'s: a pointer swap, no allocation. [chain] then holds the old ones. */
    external fun installChain(handle: Long, bus: Int, chain: Long)
    external fun destroyChain(chain: Long)
}

/** Where a bus's effect changes are sent: the native chain, or a test. */
internal interface ChainControl {
    fun setWet(bus: Int, effect: Int, wet: Float)
    fun setParameter(bus: Int, effect: Int, parameter: Int, value: Float)
}

/**
 * One bus's running effects as the native chain wants them, worked out once
 * per settings change (on the thread that changes them) so the audio thread
 * only compares and copies numbers.
 */
internal class BusFx(effects: List<MixerFx>) {
    private val ids = Array(effects.size) { effects[it].id }
    val types = IntArray(effects.size) { effects[it].type.ordinal }
    val oversampling = IntArray(effects.size) { effects[it].oversampling }
    val wets = FloatArray(effects.size) { effects[it].wet.coerceIn(0f, 1f) }
    val params: Array<FloatArray> = Array(effects.size) { i ->
        val fx = effects[i]
        val defs = getParamDefs(fx.type)
        FloatArray(defs.size) { p ->
            fx.values[p]?.takeIf(Float::isFinite)?.coerceIn(defs[p].min, defs[p].max) ?: defs[p].default
        }
    }
    val size: Int get() = types.size

    /** The same effects in the same order: only values can differ, and those change in place. */
    fun sameChain(other: BusFx): Boolean {
        if (other === this) return true
        if (other.size != size) return false
        for (i in 0 until size) {
            if (ids[i] != other.ids[i] || types[i] != other.types[i] || oversampling[i] != other.oversampling[i]) return false
        }
        return true
    }

    /** Sends what differs between this and [next] (the same chain) to [control]. Allocates nothing. */
    fun changeTo(next: BusFx, bus: Int, control: ChainControl) {
        for (i in 0 until size) {
            if (wets[i] != next.wets[i]) control.setWet(bus, i, next.wets[i])
            val before = params[i]
            val after = next.params[i]
            for (p in after.indices) {
                if (p >= before.size || before[p] != after[p]) control.setParameter(bus, i, p, after[p])
            }
        }
    }

    /** The flat arrays [NativeRecorderMixer.prepareChain] takes. */
    fun flatParams(): FloatArray = FloatArray(params.sumOf { it.size }).also { out ->
        var at = 0
        for (p in params) { p.copyInto(out, at); at += p.size }
    }

    fun counts(): IntArray = IntArray(size) { params[it].size }

    companion object {
        val NONE = BusFx(emptyList())
    }
}

/** Everything the audio thread needs from one [Mixer]: per-bus effects and the gain/pan pairs. */
internal class MixerSnapshot(val mixer: Mixer) {
    val fxEnabled: Boolean = mixer.fxEnabled
    val buses: Array<BusFx> = Array(master + 1) { index ->
        BusFx(mixer.channel(routes.getOrNull(index)).effects.filter { it.enabled })
    }

    /** Left and right gain per bus, master last: what the native render takes. */
    val controls = FloatArray((master + 1) * 2).also { controls ->
        for (index in 0..master) {
            val role = routes.getOrNull(index)
            val channel = mixer.channel(role)
            val muted = if (role == null) channel.muted else !mixer.isRouted(role)
            val gain = if (muted) 0f else channel.gain.coerceIn(0f, 1.5f)
            val pan = channel.balance.coerceIn(-1f, 1f)
            controls[index * 2] = gain * (1f - pan.coerceAtLeast(0f))
            controls[index * 2 + 1] = gain * (1f + pan.coerceAtMost(0f))
        }
    }
}

/**
 * Uses Media3 for alignment, gaps and float accumulation. The native mixer
 * receives the role sums, applies the insert chains and produces the master.
 *
 * Changing an effect chain's make-up (adding, removing, reordering an effect,
 * or its oversampling) means building new effects, which allocate. That is
 * done here on a worker thread as soon as the settings change, and the audio
 * thread only swaps the finished chain in — a pointer swap — and hands the old
 * one back to the worker to free. Values change in place. The audio thread
 * does no Kotlin allocation either: the settings are worked out once
 * ([MixerSnapshot]) by whoever sets them.
 */
internal class RecorderAudioMixerFactory(
    initialMixer: Mixer = Mixer(),
    private val publishPeaks: ((FloatArray) -> Unit)? = null,
    private val audioTap: ((ByteBuffer, Int, Int) -> Unit)? = null,
) : AudioMixer.Factory {
    @Volatile private var snapshot = MixerSnapshot(initialMixer)

    /** The mixer's settings. Setting them prepares any changed effect chains straight away, off the audio thread. */
    var settings: Mixer
        get() = snapshot.mixer
        set(value) {
            val before = snapshot
            if (value == before.mixer) return
            val next = MixerSnapshot(value)
            snapshot = next
            val rebuilt = (0..master).filter { !before.buses[it].sameChain(next.buses[it]) }
            if (rebuilt.isEmpty() || live.isEmpty()) return
            chains.execute { for (mixer in live) mixer.prepare(next, rebuilt) }
        }

    /** The mixers currently configured for the routed format: those that want chains prepared. */
    private val live = CopyOnWriteArraySet<RecorderAudioMixer>()

    override fun create(): AudioMixer =
        RecorderAudioMixer(DefaultAudioMixer.Factory(false, false).create())

    private class Prepared(val bus: BusFx, val chain: Long)

    private inner class RecorderAudioMixer(
        private val mixer: AudioMixer,
    ) : AudioMixer by mixer, ChainControl {
        private var handle = 0L
        private var output = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
        private val peaks = FloatArray((master + 1) * 2)
        private val peakHold = FloatArray(peaks.size)
        private var lastPublishNs = 0L

        /** The snapshot fully in the native graph, and what each bus's chain is now. */
        private var applied: MixerSnapshot? = null
        private val current = Array(master + 1) { BusFx.NONE }
        private val waited = IntArray(master + 1)

        /** Chains the worker has built, waiting for the audio thread. */
        private val pending = AtomicReferenceArray<Prepared?>(master + 1)

        /** Swapped-out chains for the worker to free. */
        private val retired = AtomicLongArray(RETIRED_SLOTS)
        private val lock = Any()
        private var registered = false

        override fun configure(outputAudioFormat: AudioFormat, bufferSizeMs: Int, startTimeUs: Long) {
            unregister()
            if (handle != 0L) NativeRecorderMixer.destroy(handle)
            handle = 0L
            applied = null
            current.fill(BusFx.NONE)
            if (outputAudioFormat.channelCount != packedChannels || outputAudioFormat.sampleRate != MIX_SAMPLE_RATE) {
                // Not the routed format: pass straight through, never through a native mixer
                // left over from an earlier configuration.
                mixer.configure(outputAudioFormat, bufferSizeMs, startTimeUs)
                return
            }
            mixer.configure(
                AudioFormat(outputAudioFormat.sampleRate, packedChannels, C.ENCODING_PCM_FLOAT),
                bufferSizeMs,
                startTimeUs,
            )
            // Configured again without a reset: start from a fresh native mixer.
            handle = NativeRecorderMixer.create(outputAudioFormat.sampleRate)
            // Registered before the settings are read, so a change from here on is prepared for it too.
            synchronized(lock) { registered = true }
            live += this
            // Setting up, not rendering: the chains it starts with are built here.
            val snap = snapshot
            for (bus in 0..master) build(bus, snap.buses[bus])
            applied = snap
        }

        override fun getOutput(): ByteBuffer {
            val mixed = mixer.output
            if (handle == 0L || !mixed.hasRemaining()) return mixed
            val frames = mixed.remaining() / (packedChannels * Float.SIZE_BYTES)
            val size = frames * packedChannels * Short.SIZE_BYTES
            if (output.capacity() < size) {
                output = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            }
            val snap = snapshot
            if (snap !== applied) apply(snap)
            NativeRecorderMixer.render(handle, mixed, mixed.position(), output, frames, snap.controls, snap.fxEnabled, peaks)
            publishLevels()
            output.position(0)
            output.limit(size)
            audioTap?.invoke(output, frames, packedChannels * Short.SIZE_BYTES)
            return output
        }

        /** Brings each bus to [snap] as far as it can without building anything on this thread. */
        private fun apply(snap: MixerSnapshot) {
            var complete = true
            for (bus in 0..master) {
                val want = snap.buses[bus]
                val have = current[bus]
                if (have === want) continue
                if (have.sameChain(want)) {
                    have.changeTo(want, bus, this)
                    current[bus] = want
                    continue
                }
                val ready = pending.get(bus)
                if (ready != null && ready.bus.sameChain(want) && pending.compareAndSet(bus, ready, null)) {
                    NativeRecorderMixer.installChain(handle, bus, ready.chain)
                    retire(ready.chain)
                    ready.bus.changeTo(want, bus, this)
                    current[bus] = want
                    waited[bus] = 0
                } else if (++waited[bus] > MAX_WAIT) {
                    // The worker never delivered (it should): build here, as this used to always do.
                    build(bus, want)
                    waited[bus] = 0
                } else {
                    complete = false
                }
            }
            if (complete) applied = snap
        }

        /** Builds [bus]'s chain in place. For configuration, and the rare fallback in [apply]. */
        private fun build(bus: Int, fx: BusFx) {
            NativeRecorderMixer.clear(handle, bus)
            for (i in 0 until fx.size) {
                NativeRecorderMixer.append(handle, bus, fx.types[i], fx.params[i], fx.wets[i], fx.oversampling[i])
            }
            current[bus] = fx
        }

        private fun retire(chain: Long) {
            for (i in 0 until RETIRED_SLOTS) {
                if (retired.compareAndSet(i, 0L, chain)) return
            }
            // Every slot full (the worker is far behind): free it here rather than leak it.
            NativeRecorderMixer.destroyChain(chain)
        }

        /** On the worker: builds [snap]'s chains for [buses], and frees what the audio thread swapped out. */
        fun prepare(snap: MixerSnapshot, buses: List<Int>) {
            freeRetired()
            for (bus in buses) {
                val fx = snap.buses[bus]
                val chain = NativeRecorderMixer.prepareChain(
                    MIX_SAMPLE_RATE, fx.types, fx.flatParams(), fx.counts(), fx.wets, fx.oversampling,
                )
                if (chain == 0L) continue
                val stale = synchronized(lock) {
                    if (registered) pending.getAndSet(bus, Prepared(fx, chain))?.chain else chain
                }
                if (stale != null) NativeRecorderMixer.destroyChain(stale)
            }
        }

        private fun freeRetired() {
            for (i in 0 until RETIRED_SLOTS) {
                val chain = retired.getAndSet(i, 0L)
                if (chain != 0L) NativeRecorderMixer.destroyChain(chain)
            }
        }

        /** No more chains for this mixer until it is configured again; frees what is waiting. */
        private fun unregister() {
            live -= this
            synchronized(lock) {
                registered = false
                for (bus in 0..master) pending.getAndSet(bus, null)?.let { NativeRecorderMixer.destroyChain(it.chain) }
            }
            freeRetired()
            waited.fill(0)
        }

        override fun setWet(bus: Int, effect: Int, wet: Float) =
            NativeRecorderMixer.setWet(handle, bus, effect, wet)

        override fun setParameter(bus: Int, effect: Int, parameter: Int, value: Float) =
            NativeRecorderMixer.setParameter(handle, bus, effect, parameter, value)

        private fun publishLevels() {
            val publish = publishPeaks ?: return
            for (i in peaks.indices) peakHold[i] = maxOf(peakHold[i], peaks[i])
            val now = System.nanoTime()
            if (now - lastPublishNs >= 33_000_000) {
                // A new array is what makes the meters' StateFlow see a change: 30 small ones a second.
                publish(peakHold.copyOf())
                peakHold.fill(0f)
                lastPublishNs = now
            }
        }

        override fun reset() {
            mixer.reset()
            unregister()
            if (handle != 0L) NativeRecorderMixer.destroy(handle)
            handle = 0L
            applied = null
            current.fill(BusFx.NONE)
            peakHold.fill(0f)
            lastPublishNs = 0L
        }
    }

    private companion object {
        /** Swapped-out chains that may wait for the worker at once. */
        const val RETIRED_SLOTS = 16

        /** Audio callbacks a changed chain may wait for the worker before it is built in place. */
        const val MAX_WAIT = 200

        /** Builds and frees effect chains, one at a time, away from the audio thread. */
        val chains: ExecutorService by lazy {
            Executors.newSingleThreadExecutor { task -> Thread(task, "mixer-chains").apply { isDaemon = true } }
        }
    }
}
