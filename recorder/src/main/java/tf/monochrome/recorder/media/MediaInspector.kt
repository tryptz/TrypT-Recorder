package tf.monochrome.recorder.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a file's real technical details back out of it: codec, profile and
 * level, frame rate, and bitrates measured from the samples themselves — so
 * what the app shows is what was written, not what the settings asked for.
 *
 * Sample metadata only; nothing is decoded. A very long track is measured for
 * a few seconds and the rest extrapolated (flagged as approximate).
 */
object MediaInspector {
    private const val TAG = "MediaInspector"
    private const val SCAN_BUDGET_MS = 4_000L
    /** Frame timestamps kept for the peak-rate estimate: about 40 min at 165 fps. */
    private const val MAX_TIMES = 400_000

    suspend fun inspect(context: Context, uri: Uri): MediaDetails? = withContext(Dispatchers.IO) {
        runCatching { inspectNow(context, uri) }
            .onFailure { Log.w(TAG, "cannot inspect $uri", it) }
            .getOrNull()
    }

    private class Scan(val bytes: Long, val samples: Long, val firstUs: Long, val lastUs: Long, val complete: Boolean, val times: LongArray, val timeCount: Int)

    /**
     * One MediaExtractor reads the formats and then scans each track in turn
     * (it used to be opened three times, parsing the whole file each time).
     * MediaMetadataRetriever, a parse of its own, is only asked for the frame
     * count when a scan ran out of time before the end of a long video track.
     */
    private fun inspectNow(context: Context, uri: Uri): MediaDetails? {
        val size = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull() ?: 0L

        var video: VideoDetails? = null
        var audio: AudioDetails? = null
        val containerMime: String?
        var durationUs = 0L
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            containerMime = runCatching { ex.metrics.getString(MediaExtractor.MetricsConstants.MIME_TYPE) }.getOrNull()
                ?: context.contentResolver.getType(uri)
            val formats = (0 until ex.trackCount).map { ex.getTrackFormat(it) }
            durationUs = formats.maxOfOrNull { if (it.containsKey(MediaFormat.KEY_DURATION)) it.getLong(MediaFormat.KEY_DURATION) else 0L } ?: 0L
            for ((i, f) in formats.withIndex()) {
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                val trackUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else durationUs
                val csd0 = csd0(f)
                if (video == null && mime.startsWith("video/")) {
                    val scan = scan(ex, i, keepTimes = true)
                    val avc = if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) csd0?.let(Codecs::avcProfileLevel) else null
                    var w = f.getIntOrZero(MediaFormat.KEY_WIDTH)
                    var h = f.getIntOrZero(MediaFormat.KEY_HEIGHT)
                    if (f.getIntOrZero(MediaFormat.KEY_ROTATION) % 180 != 0) w = h.also { h = w }
                    val frames = if (scan.complete) scan.samples else frameCount(context, uri) ?: scan.samples
                    video = VideoDetails(
                        codec = Codecs.video(mime),
                        profile = avc?.let { Codecs.avcProfile(it.first, it.second) },
                        level = avc?.let { Codecs.avcLevel(it.third) },
                        width = w,
                        height = h,
                        peakFps = FrameTiming.peakFrameRate(scan.times, scan.timeCount),
                        averageFps = Rates.averageFps(frames, trackUs),
                        frames = frames,
                        bitrate = bitrate(scan, trackUs),
                        approximate = !scan.complete,
                    )
                } else if (audio == null && mime.startsWith("audio/")) {
                    val scan = scan(ex, i, keepTimes = false)
                    audio = AudioDetails(
                        codec = Codecs.audio(mime, csd0),
                        sampleRate = f.getIntOrZero(MediaFormat.KEY_SAMPLE_RATE),
                        channels = f.getIntOrZero(MediaFormat.KEY_CHANNEL_COUNT),
                        bitrate = bitrate(scan, trackUs),
                        approximate = !scan.complete,
                    )
                }
            }
        } finally {
            runCatching { ex.release() }
        }
        if (video == null && audio == null) return null
        return MediaDetails(Codecs.container(containerMime), durationUs, size, video, audio)
    }

    /** The container's own frame count (exact, from its index); null if it does not say. */
    private fun frameCount(context: Context, uri: Uri): Long? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()?.takeIf { it > 0 }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** A whole track's bytes over its duration; a partial scan's over the time it covered. */
    private fun bitrate(scan: Scan, trackUs: Long): Long =
        if (scan.complete) Rates.bitrate(scan.bytes, trackUs)
        else Rates.bitrate(scan.bytes, scan.lastUs - scan.firstUs)

    /** Walks one track of the shared extractor from its start, then leaves it unselected for the next. */
    private fun scan(ex: MediaExtractor, track: Int, keepTimes: Boolean): Scan {
        ex.selectTrack(track)
        try {
            ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var times = LongArray(if (keepTimes) 4096 else 0)
            var timeCount = 0
            var bytes = 0L
            var samples = 0L
            var first = -1L
            var last = 0L
            val deadline = SystemClock.elapsedRealtime() + SCAN_BUDGET_MS
            var complete = true
            while (true) {
                val t = ex.sampleTime
                if (t < 0) break
                if (first < 0) first = t
                last = t
                bytes += ex.sampleSize.coerceAtLeast(0)
                samples++
                if (keepTimes && timeCount < MAX_TIMES) {
                    if (timeCount == times.size) times = times.copyOf(minOf(times.size * 2, MAX_TIMES))
                    times[timeCount++] = t
                }
                if ((samples and 0xFF) == 0L && SystemClock.elapsedRealtime() > deadline) {
                    complete = false
                    break
                }
                if (!ex.advance()) break
            }
            return Scan(bytes, samples, first.coerceAtLeast(0), last, complete, times, timeCount)
        } finally {
            ex.unselectTrack(track)
        }
    }

    private fun csd0(f: MediaFormat): ByteArray? = runCatching {
        f.getByteBuffer("csd-0")?.let { b ->
            val d = b.duplicate()
            d.rewind()
            ByteArray(d.remaining()).also { d.get(it) }
        }
    }.getOrNull()

    private fun MediaFormat.getIntOrZero(key: String) = if (containsKey(key)) getInteger(key) else 0
}
