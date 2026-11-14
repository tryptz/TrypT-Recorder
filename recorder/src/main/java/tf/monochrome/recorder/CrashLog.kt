package tf.monochrome.recorder

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash so it can be shared from Settings. Nothing is sent
 * anywhere: the file stays in the app's private storage until the user shares
 * or clears it.
 *
 * A crash in Kotlin is written by the uncaught-exception handler as it
 * happens. A crash in native code, or the app freezing ("not responding"),
 * kills the process before any handler runs; Android records those, and on
 * the next start they are read back from its exit history (Android 11+).
 */
object CrashLog {
    private const val FILE = "last-crash.txt"
    private const val PREFS = "crash_log"
    private const val K_SEEN = "seen_exit_ms"
    private const val MAX_CHARS = 60_000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                write(app, header(app, "Crash on thread \"${thread.name}\"") + trace)
            }
            previous?.uncaughtException(thread, error)
        }
        // Reading the exit history touches disk; keep it off the main thread.
        Thread({ runCatching { readExitHistory(app) } }, "crash-log").start()
    }

    fun read(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    private fun write(context: Context, text: String) {
        File(context.filesDir, FILE).writeText(text.take(MAX_CHARS))
    }

    private fun header(context: Context, what: String, timeMs: Long = System.currentTimeMillis()): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName} (${it.longVersionCode})" }
        }.getOrDefault("?")
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(timeMs))
        return "$what\nTryptify Recorder $version\n${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n$time\n\n"
    }

    private fun readExitHistory(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong(K_SEEN, 0L)
        val exits = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 10)
        val latest = exits.filter { it.timestamp > seen }
            .firstOrNull { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || it.reason == ApplicationExitInfo.REASON_ANR }
        exits.maxOfOrNull { it.timestamp }?.let { newest -> prefs.edit { putLong(K_SEEN, maxOf(newest, seen)) } }
        latest ?: return
        // A Kotlin crash is already written with its full trace; do not overwrite a newer one.
        val existing = File(context.filesDir, FILE)
        if (existing.isFile && existing.lastModified() > latest.timestamp) return
        val what = if (latest.reason == ApplicationExitInfo.REASON_ANR) "App not responding (froze)" else "Native crash"
        val body = buildString {
            append(latest.description.orEmpty()).append('\n')
            // An ANR's trace is the text of every thread's stack: what it was stuck on.
            if (latest.reason == ApplicationExitInfo.REASON_ANR) {
                runCatching { latest.traceInputStream?.bufferedReader()?.use { append('\n').append(it.readText()) } }
            }
        }
        write(context, header(context, what, latest.timestamp) + body)
    }
}
