package tf.monochrome.recorder.capture

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tf.monochrome.recorder.editor.model.AudioRole
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

@Serializable
data class SessionStem(
    val role: AudioRole,
    val startUs: Long,
    val durationUs: Long,
    /** Stopped at the WAV size limit (about 6 h 13 min), before the recording ended. */
    val truncated: Boolean = false,
)

@Serializable
data class SessionManifest(
    val stems: List<SessionStem>,
    val markerTimesUs: List<Long>,
    val isolatedAudioComplete: Boolean,
)

/**
 * The extras an edit-ready recording keeps beside its MP4: each audio source
 * as its own WAV stem, and markers. Lives in `filesDir/capture-sessions/`,
 * in a folder named by [SessionKeys].
 *
 * Stems are written on a thread of their own. The audio pump only copies a
 * block into a bounded queue, so a slow disk can never hold up the pump — and
 * through it the AAC encoder and the recording's sound. If the queue
 * overflows, that source's stem is stopped and dropped (the recording keeps
 * its mixed track); the pump is never blocked and the main audio never loses
 * a sample on its account.
 */
class EditReadySession private constructor(private val dir: File) {
    private class Block(val role: AudioRole, val timeUs: Long, val pcm: ShortArray, val frames: Int)

    private val queue = ArrayBlockingQueue<Block>(QUEUE_BLOCKS)
    private val pool = ArrayBlockingQueue<ShortArray>(QUEUE_BLOCKS)

    // Everything below is guarded by `lock`.
    private val lock = Object()
    private var expected = emptySet<AudioRole>()
    private val stopped = mutableSetOf<AudioRole>()
    private val completed = mutableListOf<SessionStem>()
    private val markers = mutableListOf<Long>()
    private var incomplete = false
    private var writerStarted = false
    private var writerDone = true
    private var inputClosed = false
    private var discardAll = false
    private var discardStems = false

    /** Which stems to expect; starts the writer. Called once, before the audio pump starts. */
    fun expect(device: Boolean, mic: Boolean) = synchronized(lock) {
        expected = buildSet {
            if (device) add(AudioRole.DEVICE)
            if (mic) add(AudioRole.DIALOGUE)
        }
        if (expected.isNotEmpty() && !writerStarted) {
            writerStarted = true
            writerDone = false
            Thread(::writeLoop, "rec-stems").start()
        }
    }

    /**
     * Called by the audio pump on its common, pause-adjusted sample clock.
     * Never blocks: the block is copied and queued for the writer.
     */
    fun append(role: AudioRole, timeUs: Long, pcm: ShortArray, frames: Int) {
        synchronized(lock) {
            if (inputClosed || discardAll || discardStems || role !in expected || role in stopped) return
        }
        val samples = frames * 2
        val copy = pool.poll()?.takeIf { it.size >= samples } ?: ShortArray(samples)
        System.arraycopy(pcm, 0, copy, 0, samples)
        if (!queue.offer(Block(role, timeUs, copy, frames))) {
            Log.w(TAG, "stem writer behind; dropping the ${role.name.lowercase()} stem")
            stop(role)
        }
    }

    fun mark(timeUs: Long) = synchronized(lock) {
        markers += timeUs
    }

    /** Called once from the audio pump's finally block: no more audio is coming. */
    fun finishAudio() = synchronized(lock) {
        inputClosed = true
    }

    /** Waits for the stems to be finished on disk. Returns whether they were in time. */
    fun awaitWriter(timeoutMs: Long): Boolean = synchronized(lock) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!writerDone) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) return@synchronized false
            lock.wait(left)
        }
        true
    }

    /**
     * Writes the manifest. [audioFinished] false (the pump or the writer did
     * not finish in time) keeps the markers but drops the stems, which are
     * deleted as soon as the writer lets go of them.
     */
    fun publish(originUs: Long, audioFinished: Boolean) = synchronized(lock) {
        val usable = audioFinished && writerDone
        val validStems = if (usable) completed.map { it.copy(startUs = it.startUs - originUs) } else emptyList()
        val manifest = SessionManifest(
            validStems,
            markers.map { (it - originUs).coerceAtLeast(0) }.distinct().sorted(),
            usable && !incomplete && validStems.none { it.truncated } && validStems.map { it.role }.toSet() == expected,
        )
        if (!usable) {
            discardStems = true
            if (writerDone) deleteStems()
        }
        val atomic = AtomicFile(File(dir, MANIFEST))
        val output = atomic.startWrite()
        try {
            output.write(Json.encodeToString(SessionManifest.serializer(), manifest).toByteArray())
            atomic.finishWrite(output)
        } catch (e: Exception) {
            atomic.failWrite(output)
            throw e
        }
    }

    /** Deletes the whole session — now, or when the writer lets go of it. */
    fun discard() = synchronized(lock) {
        discardAll = true
        if (writerDone) dir.deleteRecursively()
    }

    private fun stop(role: AudioRole) = synchronized(lock) {
        stopped += role
        incomplete = true
    }

    private fun writeLoop() {
        val writers = mutableMapOf<AudioRole, WavStem>()
        try {
            while (true) {
                val block = queue.poll(100, TimeUnit.MILLISECONDS)
                val (skip, quit, closed) = synchronized(lock) {
                    Triple(block != null && block.role in stopped, discardAll || discardStems, inputClosed)
                }
                if (quit) break
                if (block == null) {
                    if (closed) break else continue
                }
                if (skip) {
                    writers.remove(block.role)?.discard()
                } else {
                    try {
                        writers.getOrPut(block.role) { WavStem(file(block.role), block.timeUs) }
                            .append(block.timeUs, block.pcm, block.frames)
                    } catch (e: Exception) {
                        Log.w(TAG, "${block.role.name.lowercase()} stem failed", e)
                        writers.remove(block.role)?.discard()
                        stop(block.role)
                    }
                }
                pool.offer(block.pcm)
            }
            for ((role, writer) in writers) {
                val keep = synchronized(lock) { role !in stopped && !discardAll && !discardStems }
                val stem = if (keep) runCatching { writer.finish(role) }.getOrNull() else null
                if (stem == null) writer.discard()
                synchronized(lock) { if (stem != null) completed += stem else incomplete = true }
            }
        } catch (e: Exception) {
            Log.w(TAG, "stem writer failed", e)
            writers.values.forEach { it.discard() }
            synchronized(lock) { incomplete = true; completed.clear() }
        } finally {
            queue.clear()
            synchronized(lock) {
                writerDone = true
                when {
                    discardAll -> dir.deleteRecursively()
                    discardStems -> deleteStems()
                }
                lock.notifyAll()
            }
        }
    }

    private fun deleteStems() {
        AudioRole.entries.forEach { file(it).delete() }
    }

    private fun file(role: AudioRole) = File(dir, "${role.name.lowercase()}.wav")

    companion object {
        private const val TAG = "EditReadySession"
        private const val MANIFEST = "session.json"
        /** About 2.7 s of both sources at 1024 frames a block; ~2 MB at most. */
        private const val QUEUE_BLOCKS = 256

        fun root(context: Context) = File(context.filesDir, "capture-sessions")

        /** A fresh session for the recording at [uri], replacing anything left in its folder. */
        fun open(context: Context, uri: String): EditReadySession {
            val dir = File(root(context), SessionKeys.primary(uri))
            if (dir.exists()) dir.deleteRecursively()
            check(dir.mkdirs() || dir.isDirectory)
            return EditReadySession(dir)
        }

        fun load(context: Context, uri: String): SessionManifest? = runCatching {
            val dir = existing(context, uri) ?: return null
            val bytes = AtomicFile(File(dir, MANIFEST)).readFully()
            Json.decodeFromString(SessionManifest.serializer(), bytes.toString(Charsets.UTF_8))
        }.getOrNull()

        fun stemFile(context: Context, uri: String, role: AudioRole): File =
            EditReadySession(existing(context, uri) ?: File(root(context), SessionKeys.primary(uri))).file(role)

        /** Deletes whatever is kept for the recording at [uri] (its row-id folder and any older one). */
        fun delete(context: Context, uri: String) {
            SessionKeys.candidates(uri).forEach { File(root(context), it).deleteRecursively() }
        }

        /** The folder holding [uri]'s manifest: the row-id folder, or one written before those. */
        private fun existing(context: Context, uri: String): File? =
            SessionKeys.candidates(uri).map { File(root(context), it) }.firstOrNull { File(it, MANIFEST).isFile }
    }
}

/**
 * Stereo PCM16 WAV with an explicit sample cursor. Missing clock intervals are
 * zero-filled. A WAV cannot hold more than 4 GiB of samples; at that size the
 * stem stops taking audio and finishes as a valid, shorter file
 * ([SessionStem.truncated]).
 */
internal class WavStem(
    private val file: File,
    private val startUs: Long,
    maxDataBytes: Long = MAX_DATA_BYTES,
) {
    private val out = file.outputStream().buffered(64 * 1024)
    private var bytes = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
    private val zeros = ByteArray(4096)
    private val capacityFrames = maxDataBytes / 4
    private var writtenFrames = 0L

    var truncated = false
        private set

    init { out.write(ByteArray(44)) }

    fun append(timeUs: Long, samples: ShortArray, count: Int) {
        if (truncated) return
        val frame = ((timeUs - startUs) * RATE + 500_000) / 1_000_000
        val offset = (writtenFrames - frame).coerceIn(0, count.toLong()).toInt()
        val wanted = (frame - writtenFrames).coerceAtLeast(0)
        var missing = minOf(wanted, capacityFrames - writtenFrames)
        if (wanted + count - offset > capacityFrames - writtenFrames) truncated = true
        while (missing > 0) {
            val n = minOf(missing, zeros.size.toLong() / 4).toInt()
            out.write(zeros, 0, n * 4)
            writtenFrames += n
            missing -= n
        }
        val frames = minOf((count - offset).toLong(), capacityFrames - writtenFrames).toInt()
        if (frames <= 0) return
        if (bytes.capacity() < frames * 4) bytes = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.clear()
        bytes.asShortBuffer().put(samples, offset * 2, frames * 2)
        out.write(bytes.array(), 0, frames * 4)
        writtenFrames += frames
    }

    fun finish(role: AudioRole): SessionStem {
        out.close()
        require(writtenFrames > 0)
        // Both sizes are unsigned 32-bit in the header; MAX_DATA_BYTES keeps them in range.
        val count = writtenFrames * 4
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt((count + 36).toInt()).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(2).putInt(RATE).putInt(RATE * 4)
            .putShort(4).putShort(16).put("data".toByteArray()).putInt(count.toInt())
        RandomAccessFile(file, "rw").use { it.write(header.array()) }
        return SessionStem(role, startUs, writtenFrames * 1_000_000L / RATE, truncated)
    }

    fun discard() {
        runCatching { out.close() }
        file.delete()
    }

    companion object {
        private const val RATE = ScreenRecorder.SAMPLE_RATE
        /** The largest data chunk whose RIFF size (data + 36) still fits in 32 bits, in whole frames. */
        const val MAX_DATA_BYTES = (0xffff_ffffL - 36) / 4 * 4
    }
}
