package tf.monochrome.recorder.editor.export

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Media3's built-in gaps reject audio effects. Use seekable PCM silence in audio sequences. */
internal object MixerSilence {
    private const val headerSize = 44
    private const val bytesPerSecond = MIX_SAMPLE_RATE * Short.SIZE_BYTES
    private const val maxSeconds = (Int.MAX_VALUE - headerSize) / bytesPerSecond

    const val MAX_DURATION_US = maxSeconds * 1_000_000L

    @Synchronized
    fun asset(context: Context, durationUs: Long): File {
        val seconds = ((durationUs + 999_999) / 1_000_000).coerceIn(1, maxSeconds.toLong()).toInt()
        val capacity = (Integer.highestOneBit(seconds - 1) * 2).coerceIn(1, maxSeconds)
        val size = capacity * bytesPerSecond
        val target = File(context.cacheDir, "mixer-silence-v1-$capacity.wav")
        if (target.length() != (headerSize + size).toLong()) {
            val temporary = File.createTempFile("mixer-silence-", ".tmp", context.cacheDir)
            try {
                val header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
                    .put("RIFF".toByteArray()).putInt(size + 36)
                    .put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                    .putInt(MIX_SAMPLE_RATE).putInt(bytesPerSecond).putShort(2).putShort(16)
                    .put("data".toByteArray()).putInt(size)
                RandomAccessFile(temporary, "rw").use {
                    it.write(header.array())
                    // Extending the file gives a zero-filled, sparse PCM payload.
                    it.setLength((headerSize + size).toLong())
                }
                check(temporary.renameTo(target))
            } finally {
                temporary.delete()
            }
        }
        return target
    }
}
