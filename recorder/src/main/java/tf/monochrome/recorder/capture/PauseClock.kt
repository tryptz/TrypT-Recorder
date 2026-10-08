package tf.monochrome.recorder.capture

/**
 * Removes paused stretches from the capture clock.
 *
 * Both tracks are stamped on the monotonic clock: surface frames carry
 * System.nanoTime-based timestamps, and audio is anchored to nanoTime when it
 * starts (and again on every resume). Pausing therefore leaves a hole in that
 * clock which has to be cut out of every sample written after it, or the file
 * would freeze for as long as the pause lasted.
 *
 * Video frames are never dropped — a P-frame needs the frame before it — so a
 * frame that lands inside a pause (one already in flight when the surface was
 * detached) is clamped to the pause's start instead.
 *
 * Thread-safe: the pump, both drain threads and the service all call in.
 */
class PauseClock {
    private val starts = ArrayList<Long>()
    private val ends = ArrayList<Long>()
    private var openStartUs = NOT_PAUSED

    @get:Synchronized
    val isPaused: Boolean get() = openStartUs != NOT_PAUSED

    @Synchronized
    fun pause(nowUs: Long) {
        if (openStartUs == NOT_PAUSED) openStartUs = nowUs
    }

    @Synchronized
    fun resume(nowUs: Long) {
        if (openStartUs == NOT_PAUSED) return
        starts += openStartUs
        ends += maxOf(nowUs, openStartUs)
        openStartUs = NOT_PAUSED
    }

    /** Total paused time up to [nowUs], including a pause still in progress. */
    @Synchronized
    fun pausedTotalUs(nowUs: Long): Long {
        var total = 0L
        for (i in starts.indices) total += ends[i] - starts[i]
        if (openStartUs != NOT_PAUSED) total += (nowUs - openStartUs).coerceAtLeast(0)
        return total
    }

    /** When the most recent pause ended, or [Long.MIN_VALUE] if none has. */
    @Synchronized
    fun lastResumeUs(): Long = ends.lastOrNull() ?: Long.MIN_VALUE

    /** Maps a capture-clock timestamp to the output timeline. */
    @Synchronized
    fun adjust(ptsUs: Long): Long {
        var offset = 0L
        for (i in starts.indices) {
            val s = starts[i]
            val e = ends[i]
            when {
                ptsUs >= e -> offset += e - s
                ptsUs > s -> return s - offset
                else -> return ptsUs - offset
            }
        }
        if (openStartUs != NOT_PAUSED && ptsUs > openStartUs) return openStartUs - offset
        return ptsUs - offset
    }

    private companion object {
        const val NOT_PAUSED = Long.MIN_VALUE
    }
}
