@file:OptIn(UnstableApi::class)

package tf.monochrome.recorder.editor.export

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import tf.monochrome.recorder.editor.model.LinkShape
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
}

internal object MusicLinkAnalysis {
    private data class Key(val plan: ExportPlan, val links: List<VisualLink>)
    private val cache = LinkedHashMap<Key, MusicLinkTimeline>(4, 0.75f, true)

    suspend fun prepare(context: Context, project: Project): MusicLinkTimeline? {
        val links = RecorderMusicLinks.clean(project.musicLinks).filterNot { it.paused }
        if (links.isEmpty()) return null
        val plan = (ExportPlanner.plan(project, ExportSettings(mode = ExportMode.AUDIO_ONLY)) as? PlanResult.Ok)?.plan
            ?: return null
        val mixer = plan.mixer ?: return null
        // Picture/grade edits are irrelevant to the audio analysis and should
        // never invalidate it. Audio-only planning strips those fields.
        val key = Key(plan, links)
        synchronized(cache) { cache[key] }?.let { return it }
        val collector = Collector(links, plan.durationUs)
        val tmp = withContext(Dispatchers.IO) {
            File.createTempFile("music-links-", ".m4a", context.cacheDir).apply { delete() }
        }
        try {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation ->
                    val transformer = Transformer.Builder(context)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
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
                        .build()
                    continuation.invokeOnCancellation {
                        Handler(Looper.getMainLooper()).post { transformer.cancel(); tmp.delete() }
                    }
                    try {
                        transformer.start(CompositionFactory.build(context, plan), tmp.absolutePath)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
            val timeline = collector.finish()
            synchronized(cache) {
                cache[key] = timeline
                if (cache.size > 4) cache.remove(cache.keys.first())
            }
            return timeline
        } finally {
            tmp.delete()
        }
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
            return follower.step(db, frames.toFloat() / MIX_SAMPLE_RATE)
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

/** One time-varying RGB matrix for the linked project grading controls. */
internal class MusicGrade(private val timeline: MusicLinkTimeline) : androidx.media3.effect.RgbMatrix {
    private val matrix = FloatArray(16)
    private val luma = floatArrayOf(0.2126f, 0.7152f, 0.0722f)

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val brightness = timeline.value(RecorderLinkTarget.BRIGHTNESS, presentationTimeUs) * 0.5f
        val contrast = timeline.value(RecorderLinkTarget.CONTRAST, presentationTimeUs) * 0.6f
        val saturation = 1f + timeline.value(RecorderLinkTarget.SATURATION, presentationTimeUs)
        val exposure = 2f.pow(timeline.value(RecorderLinkTarget.EXPOSURE, presentationTimeUs))
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
