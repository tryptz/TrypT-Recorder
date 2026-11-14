package tf.monochrome.recorder.editor.model

import kotlinx.serialization.Serializable

@Serializable
enum class TimelineRate(val numerator: Int, val denominator: Int, val label: String) {
    FPS_24(24, 1, "24"),
    FPS_25(25, 1, "25"),
    FPS_2997(30_000, 1_001, "29.97"),
    FPS_30(30, 1, "30"),
    FPS_60(60, 1, "60"),
    FPS_120(120, 1, "120"),
    FPS_165(165, 1, "165");

    private val periodNumerator: Long get() = 1_000_000L * denominator

    fun step(timeUs: Long, forward: Boolean): Long {
        val time = timeUs.coerceAtLeast(0)
        val containing = ((time + 1) * numerator - 1) / periodNumerator
        val frame = when {
            forward -> containing + 1
            time == containing * periodNumerator / numerator -> (containing - 1).coerceAtLeast(0)
            else -> containing
        }
        return frame * periodNumerator / numerator
    }
}
