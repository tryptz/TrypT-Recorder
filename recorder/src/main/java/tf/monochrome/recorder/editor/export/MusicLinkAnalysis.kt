@file:OptIn(UnstableApi::class, ExperimentalApi::class)

package tf.monochrome.recorder.editor.export

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExperimentalAnalyzerModeFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import tf.monochrome.recorder.editor.model.LinkShape
import tf.monochrome.recorder.editor.model.Mixer
import tf.monochrome.recorder.editor.model.MixerFx
import tf.monochrome.recorder.editor.model.MusicLinkFollower
import tf.monochrome.recorder.editor.model.Project
import tf.monochrome.recorder.editor.model.RecorderLinkTarget
import tf.monochrome.recorder.editor.model.RecorderMusicLinks
import tf.monochrome.recorder.editor.model.VisualLink

private const val stepUs = 20_000L
private const val framesPerStep = MIX_SAMPLE_RATE / 50

/** Immutable, timeline-addressed control values shared by preview and rendering. */
internal class MusicLinkTimeline(private val values: Map<String, FloatArray>) {
    operator fun contains(target: RecorderLinkTarget): Boolean = target.key in values

    fun value(target: RecorderLinkTarget, timeUs: Long): Float {
        val envelope = values[target.key] ?: return 0f
        val position = timeUs.coerceAtLeast(0L).toDouble() / stepUs
        val index = position.toInt().coerceAtMost(envelope.lastIndex)
        val next = (index + 1).coerceAtMost(envelope.lastIndex)
        return envelope[index] + (envelope[next] - envelope[index]) * (position - index).toFloat()
    }

    /**
     * What a linked grading control is at [timeUs]: the slider's own [base]
     * with the link's movement added, held to the control's -1..1 range, so
     * the slider sets where the link moves around instead of being ignored.
     * Zero for a control no link follows: the static effect draws that one.
     * Exposure has no slider in the look, so its base is 0.
     */
    fun linked(target: RecorderLinkTarget, base: Float, timeUs: Long): Float =
        if (target in this) (base + value(target, timeUs)).coerceIn(-1f, 1f) else 0f
}

/**
 * Measures the soundtrack for the music links. One analysis per [Key]: the
 * preview and an export asking for the same one share it while it runs
 * ([analyse]), finished ones are kept for a while, and one that failed is
 * remembered so the preview does not start it again on every rebuild.
 */
internal object MusicLinkAnalysis {
    /** What an analysis depends on: the sound, and the links that listen to it. */
    internal data class Key(val plan: ExportPlan, val links: List<VisualLink>)

    private class Running(val work: Deferred<MusicLinkTimeline>) {
        var waiters = 0
    }

    private val lock = Any()
    private val cache = LinkedHashMap<Key, MusicLinkTimeline>(4, 0.75f, true)
    private val running = HashMap<Key, Running>()
    private val failures = LinkedHashSet<Key>()

    // Transformer is driven from the main thread.
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    /**
     * How long an analysis nobody waits for any more keeps running. Starting
     * an export stops the preview's wait just before the export asks for the
     * same analysis; without this it would be thrown away and begun again.
     */
    private const val ORPHAN_GRACE_MS = 3_000L
    private const val KEEP = 4

    /** Null when [project] has nothing to analyse: no active links, or no sound. */
    fun key(project: Project): Key? {
        val links = RecorderMusicLinks.clean(project.musicLinks).filterNot { it.paused }
        if (links.isEmpty()) return null
        val plan = (ExportPlanner.plan(project, ExportSettings(mode = ExportMode.AUDIO_ONLY)) as? PlanResult.Ok)?.plan
            ?: return null
        val mixer = plan.mixer ?: return null
        // Picture/grade edits are irrelevant to the audio analysis and should
        // never invalidate it: audio-only planning strips those fields. The
        // mixer stays in the key: the links follow the processed Master signal,
        // which every fader, pan, mute, solo and running effect changes. Only
        // what cannot change that signal is left out (see signalOf).
        return Key(plan.copy(mixer = signalOf(mixer)), links)
    }

    /**
     * The part of [mixer] that shapes the sound: effects that are switched
     * off (one by one, or all with the FX switch) are dropped, and the ids
     * the editor tells effects apart by are blanked, so neither restarts an
     * analysis that would come out the same.
     */
    internal fun signalOf(mixer: Mixer): Mixer {
        fun clean(effects: List<MixerFx>) =
            if (!mixer.fxEnabled) emptyList() else effects.filter { it.enabled }.map { it.copy(id = "") }
        return mixer.copy(
            roles = mixer.roles.mapValues { it.value.copy(effects = clean(it.value.effects)) },
            masterEffects = clean(mixer.masterEffects),
        )
    }

    /** The finished analysis for [key], if there is one; never waits. */
    fun cached(key: Key): MusicLinkTimeline? = synchronized(lock) { cache[key] }

    /** True when the analysis for [key] has failed; the preview then plays without it rather than retrying. */
    fun failed(key: Key): Boolean = synchronized(lock) { key in failures }

    /**
     * Renders the whole soundtrack to measure it, which takes about as long as
     * an audio export: an export waits for it, the preview must not (see
     * EditorViewModel.rebuildPreview).
     */
    suspend fun prepare(context: Context, project: Project): MusicLinkTimeline? {
        val key = key(project) ?: return null
        return analyse(context, key)
    }

    /**
     * The analysis for [key]: from the cache, by joining one already running,
     * or by starting it. It runs on its own, not in the caller's coroutine,
     * so one caller giving up does not cancel it for another; once nobody
     * has waited for it for [ORPHAN_GRACE_MS] it is stopped.
     */
    suspend fun analyse(context: Context, key: Key): MusicLinkTimeline {
        val app = context.applicationContext
        val run = synchronized(lock) {
            cache[key]?.let { return it }
            running.getOrPut(key) {
                Running(scope.async(start = CoroutineStart.LAZY) { render(app, key) })
            }.also { it.waiters++ }
        }
        try {
            run.work.start()
            return run.work.await()
        } finally {
            val orphaned = synchronized(lock) { --run.waiters == 0 && !run.work.isCompleted }
            if (orphaned) {
                scope.launch {
                    delay(ORPHAN_GRACE_MS)
                    val stop = synchronized(lock) {
                        (run.waiters == 0 && running[key] === run).also { if (it) running.remove(key) }
                    }
                    if (stop) run.work.cancel()
                }
            }
        }
    }

    private suspend fun render(context: Context, key: Key): MusicLinkTimeline {
        try {
            val timeline = measure(context, key)
            synchronized(lock) {
                cache[key] = timeline
                if (cache.size > KEEP) cache.remove(cache.keys.first())
            }
            return timeline
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(lock) {
                failures += key
                if (failures.size > KEEP * 2) failures.remove(failures.first())
            }
            throw e
        } finally {
            val self = currentCoroutineContext()[Job]
            synchronized(lock) { if (running[key]?.work === self) running.remove(key) }
        }
    }

    /**
     * Media3's analyzer mode runs the whole audio pipeline, the mixer and its
     * tap included, but its encoder drops what it is given and its muxer writes
     * nothing: no AAC is encoded and no file is written only to be deleted.
     */
    private suspend fun measure(context: Context, key: Key): MusicLinkTimeline {
        val plan = key.plan
        val mixer = requireNotNull(plan.mixer)
        val collector = Collector(key.links, plan.durationUs)
        // Building the composition prepares the silence files gaps play: disk work, kept off the main thread.
        val composition = withContext(Dispatchers.IO) { CompositionFactory.build(context, plan) }
        // Never written by the analyzer's muxer; Transformer only asks for a path.
        val unused = File(context.cacheDir, "music-links-analysis.m4a").absolutePath
        suspendCancellableCoroutine { continuation ->
            val transformer = ExperimentalAnalyzerModeFactory.buildAnalyzer(
                context,
                Transformer.Builder(context)
                    .setAudioMixerFactory(RecorderAudioMixerFactory(mixer, audioTap = collector::accept))
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            if (continuation.isActive) continuation.resumeWithException(exportException)
                        }
                    })
                    .build(),
            )
            continuation.invokeOnCancellation {
                Handler(Looper.getMainLooper()).post { transformer.cancel() }
            }
            try {
                transformer.start(composition, unused)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
        return collector.finish()
    }

    private class Collector(links: List<VisualLink>, durationUs: Long) {
        private val bands = links.map { Band(it.shape) }
        private val keys = links.map { it.target }
        private val output = Array(links.size) { FloatArray((durationUs / stepUs).toInt() + 2) }
        private var tick = 0
        private var frame = 0

        @Synchronized
        fun accept(data: ByteBuffer, frames: Int, stride: Int) {
            if (tick >= output[0].size) return
            var offset = 0
            repeat(frames) {
                val left = data.getShort(offset) / 32768f
                val right = data.getShort(offset + 2) / 32768f
                for (band in bands) band.feed(left, right)
                offset += stride
                frame++
                if (frame == framesPerStep) {
                    step()
                    if (tick >= output[0].size) return
                }
            }
        }

        private fun step() {
            for (i in bands.indices) output[i][tick] = bands[i].step(frame)
            tick++
            frame = 0
        }

        @Synchronized
        fun finish(): MusicLinkTimeline {
            if (frame > 0 && tick < output[0].size) step()
            while (tick < output[0].size) {
                frame = framesPerStep
                step()
            }
            return MusicLinkTimeline(keys.indices.associate { keys[it] to output[it] })
        }
    }

    private class Band(shape: LinkShape) {
        private val follower = MusicLinkFollower(shape)
        private val leftHigh = Biquad(shape.effectiveLoHz, true)
        private val leftLow = Biquad(shape.effectiveHiHz, false)
        private val rightHigh = Biquad(shape.effectiveLoHz, true)
        private val rightLow = Biquad(shape.effectiveHiHz, false)
        private var energy = 0f

        fun feed(left: Float, right: Float) {
            val l = leftLow.filter(leftHigh.filter(left))
            val r = rightLow.filter(rightHigh.filter(right))
            energy += (l * l + r * r) * 0.5f
        }

        fun step(frames: Int): Float {
            val db = 10f * log10((energy / frames).coerceAtLeast(1e-12f))
            energy = 0f
            // Silent long enough, the link goes to rest (LinkShape.pauseWhenSilent).
            return follower.follow(db, frames.toFloat() / MIX_SAMPLE_RATE)
        }
    }

    private class Biquad(hz: Float, highPass: Boolean) {
        private val w = (2 * PI * hz / MIX_SAMPLE_RATE).toFloat()
        private val c = cos(w)
        private val alpha = sin(w) / 1.41421356f
        private val a0 = 1f + alpha
        private val a1 = -2f * c / a0
        private val a2 = (1f - alpha) / a0
        private val b0 = (1f + if (highPass) c else -c) / (2f * a0)
        private val b1 = (if (highPass) -2f else 2f) * b0
        private var z1 = 0f
        private var z2 = 0f

        fun filter(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b0 * x - a2 * y
            return y
        }
    }
}

/**
 * One time-varying RGB matrix for the linked project grading controls. The
 * look's own brightness, contrast and saturation are the base each link moves
 * around ([MusicLinkTimeline.linked]); CompositionFactory leaves a linked
 * control out of the static adjustments, so it is applied here, once.
 */
internal class MusicGrade(
    private val timeline: MusicLinkTimeline,
    private val baseBrightness: Float = 0f,
    private val baseContrast: Float = 0f,
    private val baseSaturation: Float = 0f,
) : androidx.media3.effect.RgbMatrix {
    private val matrix = FloatArray(16)
    private val luma = floatArrayOf(0.2126f, 0.7152f, 0.0722f)

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val brightness = timeline.linked(RecorderLinkTarget.BRIGHTNESS, baseBrightness, presentationTimeUs) * 0.5f
        val contrast = timeline.linked(RecorderLinkTarget.CONTRAST, baseContrast, presentationTimeUs) * 0.6f
        val saturation = 1f + timeline.linked(RecorderLinkTarget.SATURATION, baseSaturation, presentationTimeUs)
        val exposure = 2f.pow(timeline.linked(RecorderLinkTarget.EXPOSURE, 0f, presentationTimeUs))
        val slope = (1f + contrast) / (1f - contrast)
        val bias = (0.5f * (1f - slope) + brightness) * exposure
        matrix.fill(0f)
        for (input in 0..2) {
            for (output in 0..2) {
                matrix[input * 4 + output] =
                    (luma[input] * (1f - saturation) + if (input == output) saturation else 0f) * slope * exposure
            }
            matrix[12 + input] = bias
        }
        matrix[15] = 1f
        return matrix
    }
}
