package tf.monochrome.recorder.editor.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

@Serializable
data class SpeedKey(val sourceUs: Long, val rate: Float)

/** Piecewise speed samples anchored to source time, independent of either trim boundary. */
class ClipTiming(
    private val startUs: Long,
    private val endUs: Long,
    private val speed: Float,
    keys: List<SpeedKey>,
) {
    private data class Sample(val sourceUs: Long, val rate: Float, val outputUs: Double)
    private val samples: List<Sample>
    private val outputDuration: Double

    init {
        val keysByTime = keys.sortedBy { it.sourceUs }.distinctBy { it.sourceUs }
        val points = buildList {
            keysByTime.forEachIndexed { index, key ->
                add(key)
                val next = keysByTime.getOrNull(index + 1) ?: return@forEachIndexed
                // A finite number of changes per pair keeps audio processing bounded.
                for (step in 1 until RAMP_STEPS) {
                    val t = key.sourceUs + (next.sourceUs - key.sourceUs) * step / RAMP_STEPS
                    if (t > last().sourceUs && t < next.sourceUs) {
                        val fraction = (t - key.sourceUs).toDouble() / (next.sourceUs - key.sourceUs)
                        add(SpeedKey(t, (key.rate + (next.rate - key.rate) * fraction).toFloat()))
                    }
                }
            }
        }
        val firstRate = points.lastOrNull { it.sourceUs <= startUs }?.rate
            ?: points.firstOrNull()?.rate ?: speed
        var elapsed = 0.0
        var current = Sample(startUs, firstRate, 0.0)
        samples = buildList {
            add(current)
            for (point in points) {
                if (point.sourceUs <= startUs || point.sourceUs >= endUs || point.rate == current.rate) continue
                elapsed += (point.sourceUs - current.sourceUs) / current.rate.toDouble()
                current = Sample(point.sourceUs, point.rate, elapsed)
                add(current)
            }
        }
        outputDuration = elapsed + (endUs - current.sourceUs) / current.rate.toDouble()
    }

    val durationUs: Long get() = outputDuration.toLong()
    val peakRate: Float get() = samples.maxOf { it.rate }
    val lowestRate: Float get() = samples.minOf { it.rate }

    private fun sampleIndex(sourceUs: Long): Int {
        val i = samples.binarySearchBy(sourceUs) { it.sourceUs }
        return if (i >= 0) i else (-i - 2).coerceAtLeast(0)
    }

    fun rateAt(sourceUs: Long): Float = samples[sampleIndex(sourceUs)].rate

    fun nextChangeAfter(sourceUs: Long): Long? = samples.getOrNull(sampleIndex(sourceUs) + 1)?.sourceUs

    fun sourceAt(outputUs: Long): Long {
        if (outputUs <= 0) return startUs
        if (outputUs >= durationUs) return endUs
        val i = samples.binarySearchBy(outputUs.toDouble()) { it.outputUs }
        val sample = samples[if (i >= 0) i else -i - 2]
        return (sample.sourceUs + ((outputUs - sample.outputUs) * sample.rate).roundToLong())
            .coerceIn(startUs, endUs)
    }

    fun timelineAt(sourceUs: Long): Long {
        val time = sourceUs.coerceIn(startUs, endUs)
        val sample = samples[sampleIndex(time)]
        return (sample.outputUs + (time - sample.sourceUs) / sample.rate.toDouble()).toLong()
    }

    private companion object {
        const val RAMP_STEPS = 16
    }
}

fun Project.setSpeedRamp(id: String, keys: List<SpeedKey>): Project = copy(
    clips = clips.map { clip ->
        if (clip.id != id) clip else {
            val sorted = keys.filter { it.sourceUs in 0..clip.source.durationUs && it.rate.isFinite() }
                .map { it.copy(rate = it.rate.coerceIn(SPEEDS.first(), SPEEDS.last())) }
                .sortedBy { it.sourceUs }.distinctBy { it.sourceUs }
            val candidate = clip.copy(speedKeys = sorted)
            if (candidate.durationUs >= MIN_CLIP_US) candidate else clip
        }
    },
)
