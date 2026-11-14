package tf.monochrome.recorder.capture

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tf.monochrome.recorder.editor.model.AudioRole
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

@Serializable
data class SessionStem(val role: AudioRole, val startUs: Long, val durationUs: Long)

@Serializable
data class SessionManifest(
    val stems: List<SessionStem>,
    val markerTimesUs: List<Long>,
    val isolatedAudioComplete: Boolean,
)

class EditReadySession private constructor(private val dir: File) {
    private val writers = mutableMapOf<AudioRole, WavStem>()
    private val completed = mutableListOf<SessionStem>()
    private val markers = mutableListOf<Long>()
    private var expected = emptySet<AudioRole>()

    @Synchronized
    fun expect(device: Boolean, mic: Boolean) {
        expected = buildSet {
            if (device) add(AudioRole.DEVICE)
            if (mic) add(AudioRole.DIALOGUE)
        }
    }

    /** Called by the audio pump on its common, pause-adjusted sample clock. */
    fun append(role: AudioRole, timeUs: Long, pcm: ShortArray, frames: Int) {
        if (role !in expected) return
        try {
            val writer = writers.getOrPut(role) { WavStem(file(role), timeUs) }
            writer.append(timeUs, pcm, frames)
        } catch (_: Exception) {
            writers.remove(role)?.discard()
            file(role).delete()
            expected = expected - role
            incomplete = true
        }
    }

    @Volatile private var incomplete = false

    @Synchronized
    fun mark(timeUs: Long) {
        markers += timeUs
    }

    /** Invoked once from the audio pump's finally block, before the MP4 is published. */
    fun finishAudio() {
        for ((role, writer) in writers) {
            val stem = runCatching { writer.finish(role) }.getOrNull()
            if (stem != null) completed += stem else {
                incomplete = true
                writer.discard()
            }
        }
        writers.clear()
    }

    @Synchronized
    fun publish(originUs: Long, audioFinished: Boolean) {
        val validStems = if (audioFinished) completed.map { it.copy(startUs = it.startUs - originUs) } else emptyList()
        val manifest = SessionManifest(
            validStems,
            markers.map { (it - originUs).coerceAtLeast(0) }.distinct().sorted(),
            audioFinished && !incomplete && validStems.map { it.role }.toSet() == expected,
        )
        val atomic = AtomicFile(File(dir, "session.json"))
        val output = atomic.startWrite()
        try {
            output.write(Json.encodeToString(SessionManifest.serializer(), manifest).toByteArray())
            atomic.finishWrite(output)
        } catch (e: Exception) {
            atomic.failWrite(output)
            throw e
        }
    }

    fun discard() {
        dir.deleteRecursively()
    }

    private fun file(role: AudioRole) = File(dir, "${role.name.lowercase()}.wav")

    companion object {
        fun open(context: Context, uri: String): EditReadySession {
            val dir = directory(context, uri)
            check(dir.mkdirs() || dir.isDirectory)
            return EditReadySession(dir)
        }

        fun load(context: Context, uri: String): SessionManifest? = runCatching {
            val bytes = AtomicFile(File(directory(context, uri), "session.json")).readFully()
            Json.decodeFromString(SessionManifest.serializer(), bytes.toString(Charsets.UTF_8))
        }.getOrNull()

        fun stemFile(context: Context, uri: String, role: AudioRole): File =
            EditReadySession(directory(context, uri)).file(role)

        private fun directory(context: Context, uri: String): File {
            val id = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray())
                .joinToString("") { "%02x".format(it) }
            return File(context.filesDir, "capture-sessions/$id")
        }
    }
}

/** Stereo PCM16 WAV with an explicit sample cursor. Missing clock intervals are zero-filled. */
internal class WavStem(private val file: File, private val startUs: Long) {
    private val out = file.outputStream().buffered(64 * 1024)
    private val bytes = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
    private val zeros = ByteArray(4096)
    private var writtenFrames = 0L

    init { out.write(ByteArray(44)) }

    fun append(timeUs: Long, samples: ShortArray, count: Int) {
        val frame = ((timeUs - startUs) * RATE + 500_000) / 1_000_000
        val offset = (writtenFrames - frame).coerceIn(0, count.toLong()).toInt()
        var missing = (frame - writtenFrames).coerceAtLeast(0)
        require((writtenFrames + missing + count - offset) * 4 <= 0xffff_ffffL - 36)
        while (missing > 0) {
            val n = minOf(missing, zeros.size.toLong() / 4).toInt()
            out.write(zeros, 0, n * 4)
            writtenFrames += n
            missing -= n
        }
        bytes.clear()
        val frames = count - offset
        bytes.asShortBuffer().put(samples, offset * 2, frames * 2)
        out.write(bytes.array(), 0, frames * 4)
        writtenFrames += frames
    }

    fun finish(role: AudioRole): SessionStem {
        out.close()
        require(writtenFrames > 0)
        val count = (writtenFrames * 4).toInt()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(count + 36).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(2).putInt(RATE).putInt(RATE * 4)
            .putShort(4).putShort(16).put("data".toByteArray()).putInt(count)
        RandomAccessFile(file, "rw").use { it.write(header.array()) }
        return SessionStem(role, startUs, writtenFrames * 1_000_000L / RATE)
    }

    fun discard() {
        runCatching { out.close() }
        file.delete()
    }

    private companion object { const val RATE = ScreenRecorder.SAMPLE_RATE }
}
