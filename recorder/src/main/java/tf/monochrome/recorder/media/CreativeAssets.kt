package tf.monochrome.recorder.media

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import androidx.annotation.StringRes
import java.io.File
import java.net.URI
import java.net.HttpURLConnection
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tf.monochrome.recorder.R
import tf.monochrome.recorder.editor.model.CubeLut

internal object CreativeAssets {
    data class Lut(val file: String, val title: String, val collection: String)
    data class Track(val title: String, val file: File, val bundled: Boolean)

    val luts = listOf(
        Lut("seagull-cove", "Seagull Cove", "Striped Purple"),
        Lut("teal-embers", "Teal Embers", "Striped Purple"),
        Lut("philly-fog", "Philly Fog", "Striped Purple"),
        Lut("cinema-1970s", "1970s Cinema", "Striped Purple"),
        Lut("acros", "Acros", "FilmSim"),
        Lut("classic-chrome", "Classic Chrome", "FilmSim"),
        Lut("provia", "Provia Standard", "FilmSim"),
        Lut("velvia", "Velvia Vivid", "FilmSim"),
    )

    suspend fun readLut(context: Context, lut: Lut): CubeLut = withContext(Dispatchers.IO) {
        context.assets.open("creative/luts/${lut.file}.cube").bufferedReader().use {
            CubeLut.parse(it.readText(), lut.title)
        }
    }

    private val bundled = listOf(
        "level1-step1" to "Step 1",
        "level1-step2" to "Step 2",
        "level1-step3" to "Step 3",
        "level1-step1-evil" to "Step 1 · Dark",
        "level1-step2-evil" to "Step 2 · Dark",
        "level1-step3-evil" to "Step 3 · Dark",
    )
    private val extensions = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "mp4", "webm")
    private fun root(context: Context) = File(context.filesDir, "recorder-music")

    suspend fun list(context: Context): List<Track> = withContext(Dispatchers.IO) {
        val folder = root(context)
        bundled.map { (id, title) -> Track(title, File(folder, "bundled/$id.m4a"), true) } +
            File(folder, "imports").listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }
                .flatMap { it.listFiles().orEmpty().asList() }.filter { it.isFile }
                .sortedByDescending { it.lastModified() }
                .map { Track(it.nameWithoutExtension, it, false) }
    }

    suspend fun fileFor(context: Context, track: Track): Uri = withContext(Dispatchers.IO) {
        if (track.bundled && !track.file.isFile) {
            track.file.parentFile?.mkdirs()
            val temp = File.createTempFile(".music-", ".part", track.file.parentFile)
            try {
                context.assets.open("creative/music/${track.file.name}").use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                check(temp.renameTo(track.file) || track.file.isFile)
            } finally {
                temp.delete()
            }
        }
        Uri.fromFile(track.file)
    }

    class ImportError(@get:StringRes val resource: Int) : Exception()

    suspend fun download(context: Context, link: String): Track = withContext(Dispatchers.IO) {
        var url = httpsUri(link.trim()) ?: throw ImportError(R.string.music_link_invalid_url)
        val imports = File(root(context), "imports").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val stage = File(imports, ".$id").apply { check(mkdir()) }
        try {
            repeat(6) { hop ->
                coroutineContext.ensureActive()
                val connection = url.toURL().openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 12_000
                    connection.readTimeout = 12_000
                    val status = connection.responseCode
                    if (status in 300..399) {
                        val location = connection.getHeaderField("Location")
                            ?: throw ImportError(R.string.music_link_download_failed)
                        url = httpsUri(runCatching { url.resolve(location).toString() }.getOrDefault(""))
                            ?: throw ImportError(R.string.music_link_invalid_url)
                        if (hop == 5) throw ImportError(R.string.music_link_download_failed)
                        return@repeat
                    }
                    if (status != 200) throw ImportError(R.string.music_link_download_failed)
                    val mime = connection.contentType.orEmpty().substringBefore(';').lowercase()
                    val fromPath = url.path.orEmpty().substringAfterLast('/').take(160)
                    val ext = fromPath.substringAfterLast('.', "").lowercase()
                    if (ext !in extensions && !mime.startsWith("audio/")) {
                        throw ImportError(R.string.music_link_not_audio)
                    }
                    val suffix = ext.takeIf { it in extensions } ?: when (mime) {
                        "audio/mpeg" -> "mp3"
                        "audio/mp4", "audio/x-m4a" -> "m4a"
                        "audio/wav", "audio/x-wav" -> "wav"
                        "audio/flac" -> "flac"
                        "audio/ogg" -> "ogg"
                        else -> "audio"
                    }
                    val name = fromPath.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} ._()-]"), "_")
                        .take(80).trim('.', ' ').ifBlank { context.getString(R.string.music_link_download_name) }
                    val file = File(stage, "$name.$suffix")
                    val storage = context.getSystemService(StorageManager::class.java)
                    val available = storage.getAllocatableBytes(storage.getUuidForPath(imports))
                    val limit = minOf(available, 250L * 1024 * 1024)
                    if (connection.contentLengthLong > limit) throw ImportError(R.string.music_link_size)
                    var written = 0L
                    connection.inputStream.use { input ->
                        file.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                written += count
                                if (written > limit) throw ImportError(R.string.music_link_size)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    val media = MediaProbe.probe(context, Uri.fromFile(file))
                    if (media?.hasAudio != true || media.hasVideo || media.durationUs <= 0) {
                        throw ImportError(R.string.music_link_not_audio)
                    }
                    coroutineContext.ensureActive()
                    val target = File(imports, id)
                    check(stage.renameTo(target))
                    return@withContext Track(name, File(target, file.name), false)
                } finally {
                    connection.disconnect()
                }
            }
            throw ImportError(R.string.music_link_download_failed)
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun httpsUri(value: String): URI? = runCatching { URI(value) }.getOrNull()
        ?.takeIf { it.scheme.equals("https", ignoreCase = true) && it.host != null && it.userInfo == null }
}
