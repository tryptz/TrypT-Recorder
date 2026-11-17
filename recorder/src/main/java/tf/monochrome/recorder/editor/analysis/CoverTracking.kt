package tf.monochrome.recorder.editor.analysis

import java.nio.ByteBuffer
import tf.monochrome.recorder.editor.model.CoverSample
import tf.monochrome.recorder.editor.model.CoverTracker
import tf.monochrome.recorder.editor.model.CropRect
import tf.monochrome.recorder.editor.model.TrackedCover

/**
 * Small luma pictures of a clip at chosen times, for the cover tracker.
 * [read] takes times in increasing order and hands each one's picture to
 * `onFrame` — at most once, in the same order — as [CoverTracking.width] ×
 * [CoverTracking.height] luma bytes in a buffer that is only valid during the
 * call. A time it cannot show is skipped.
 */
interface LumaSource {
    fun read(timesUs: LongArray, onFrame: (index: Int, luma: ByteArray) -> Unit)
}

/**
 * Follows a box through a clip, from the frame it was drawn on outwards in
 * both directions, each match searched for around the one before. Pure:
 * where the pictures come from is [LumaSource]'s business, so this is held
 * by JVM tests.
 *
 * The pictures are read in time order, as a decoder gives them: the times
 * after the seed in one pass, tracked as they come; the times before it in
 * chunks of [chunk], each read forwards and then tracked backwards, so
 * only a chunk of pictures is ever held.
 */
class CoverTracking(
    val width: Int,
    val height: Int,
    private val chunk: Int = 48,
) {
    /**
     * The track of [box], drawn at [seedUs], over [startUs] until [endUs].
     * [progress] gets 0..1; [checkActive] is called between pictures and may
     * throw to stop.
     */
    fun track(
        source: LumaSource,
        startUs: Long,
        endUs: Long,
        seedUs: Long,
        box: CropRect,
        progress: (Float) -> Unit = {},
        checkActive: () -> Unit = {},
    ): TrackedCover {
        val (before, after) = times(startUs, endUs, seedUs)
        val total = (before.size + after.size + 1).toFloat()
        var done = 0
        val samples = mutableListOf(CoverSample(seedUs, box))
        var tracker: CoverTracker? = null
        var last = box
        // The seed, then onwards to the end, matched as the pictures come.
        val forward = LongArray(after.size + 1) { if (it == 0) seedUs else after[it - 1] }
        var expected = 1
        // A time the source skipped is a lost sample, where the box was last seen.
        fun skipTo(index: Int) {
            while (expected < index) samples += CoverSample(forward[expected++], last, false)
        }
        source.read(forward) { index, luma ->
            checkActive()
            if (index == 0) {
                tracker = CoverTracker(luma, width, height, box)
            } else {
                val seed = tracker ?: return@read
                skipTo(index)
                val match = seed.find(luma, last)
                if (match != null) last = match
                samples += CoverSample(forward[index], last, match != null)
                expected = index + 1
            }
            progress(++done / total)
        }
        val seed = requireNotNull(tracker) { "the frame the box was drawn on could not be read" }
        skipTo(forward.size)
        done = forward.size
        progress(done / total)
        // Back to the start, a chunk at a time: each read forwards, matched backwards.
        last = box
        val pool = Array(minOf(chunk, before.size)) { ByteArray(width * height) }
        for (part in before.chunked(chunk)) {
            val ascending = part.reversed().toLongArray()
            val held = BooleanArray(ascending.size)
            source.read(ascending) { index, luma ->
                checkActive()
                luma.copyInto(pool[index])
                held[index] = true
            }
            for (index in ascending.indices.reversed()) {
                checkActive()
                val match = if (held[index]) seed.find(pool[index], last) else null
                if (match != null) last = match
                samples += CoverSample(ascending[index], last, match != null)
                progress(++done / total)
            }
        }
        return TrackedCover(samples.sortedBy { it.sourceUs })
    }

    companion object {
        /** At most this many samples over a clip; never closer than [MIN_STEP_US]. */
        const val MAX_SAMPLES = 600
        const val MIN_STEP_US = 166_667L

        /**
         * The times a track is sampled at, besides the seed: those before it,
         * nearest first, and those after it, in order. One step apart, the
         * last ones on the clip's first and last microsecond.
         */
        fun times(startUs: Long, endUs: Long, seedUs: Long): Pair<List<Long>, List<Long>> {
            val step = maxOf(MIN_STEP_US, (endUs - startUs) / MAX_SAMPLES)
            fun walk(direction: Int): List<Long> = buildList {
                var time = seedUs
                val edge = if (direction < 0) startUs else endUs - 1
                while (time != edge) {
                    time = (time + direction * step).coerceIn(startUs, endUs - 1)
                    add(time)
                }
            }
            return walk(-1) to walk(1)
        }
    }
}

/**
 * Which decoded frame each wanted time shows. A screen recording has a
 * frame only when the screen changed, so what is on screen at a time is
 * the latest frame at or before it — not the nearest, which can be one
 * that only appears later. A frame is known to be that one once the frame
 * after it has arrived, so a decoder holds each frame until the next comes
 * ([claim]); only frames some time needs are ever converted.
 */
class FramePicker(private val timesUs: LongArray) {
    private var next = 0

    /** Every time has its frame. */
    val done: Boolean get() = next >= timesUs.size

    /** The index of the first time [claim] last gave out. */
    var first = 0
        private set

    /**
     * The held frame is shown until [nextUs], when the following frame starts
     * (Long.MAX_VALUE after the last frame). Returns how many times it is
     * the frame for, starting at [first]. Times before the very first frame
     * get that frame.
     */
    fun claim(nextUs: Long): Int {
        first = next
        while (next < timesUs.size && timesUs[next] < nextUs) next++
        return next - first
    }
}

/**
 * Shrinks one decoded luma plane to the tracker's small picture: the
 * decoder's crop, turned upright by the track's rotation (clockwise, as
 * the container says it is to be shown), four taps averaged per output
 * pixel, and limited-range video levels widened to full range, as the
 * frames MediaMetadataRetriever gave were. Built once per output format.
 */
class LumaGrid(
    private val outWidth: Int,
    private val outHeight: Int,
    cropLeft: Int,
    cropTop: Int,
    cropWidth: Int,
    cropHeight: Int,
    rowStride: Int,
    pixelStride: Int,
    rotation: Int,
    fullRange: Boolean,
) {
    private val taps = IntArray(outWidth * outHeight * 4)
    private val levels = IntArray(256) { y ->
        if (fullRange) y else ((y - 16) * 255 + 109) / 219
    }.map { it.coerceIn(0, 255) }.toIntArray()

    init {
        val turn = ((rotation % 360) + 360) % 360
        val sideways = turn == 90 || turn == 270
        val shownWidth = if (sideways) cropHeight else cropWidth
        val shownHeight = if (sideways) cropWidth else cropHeight
        var t = 0
        for (gy in 0 until outHeight) {
            for (gx in 0 until outWidth) {
                for (sub in 0 until 4) {
                    val dx = (((gx + 0.25 + 0.5 * (sub and 1)) * shownWidth) / outWidth).toInt().coerceIn(0, shownWidth - 1)
                    val dy = (((gy + 0.25 + 0.5 * (sub shr 1)) * shownHeight) / outHeight).toInt().coerceIn(0, shownHeight - 1)
                    val (cx, cy) = when (turn) {
                        90 -> dy to cropHeight - 1 - dx
                        180 -> cropWidth - 1 - dx to cropHeight - 1 - dy
                        270 -> cropWidth - 1 - dy to dx
                        else -> dx to dy
                    }
                    taps[t++] = (cropTop + cy) * rowStride + (cropLeft + cx) * pixelStride
                }
            }
        }
    }

    /** Reads [plane] (absolute positions, its position and limit untouched) into [out]. */
    fun sample(plane: ByteBuffer, out: ByteArray) {
        var t = 0
        for (i in 0 until outWidth * outHeight) {
            val sum = (plane.get(taps[t]).toInt() and 255) + (plane.get(taps[t + 1]).toInt() and 255) +
                (plane.get(taps[t + 2]).toInt() and 255) + (plane.get(taps[t + 3]).toInt() and 255)
            out[i] = levels[(sum + 2) shr 2].toByte()
            t += 4
        }
    }
}
