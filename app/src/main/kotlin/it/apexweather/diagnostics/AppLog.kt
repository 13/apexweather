package it.apexweather.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The app's own log, kept on the phone so a failure can be explained after the fact.
 *
 * logcat is gone within minutes on a phone, the release build refuses `run-as`, and a sideloaded app
 * has no crash service — so until this existed, every failure worth knowing about was found by
 * reading the screen. Lines go to `filesDir/diagnostics/` (not the cache, which Android clears
 * under storage pressure, exactly when the log matters) and to logcat as well.
 *
 * **Nothing touches the disk on the caller's thread**: lines are handed to one background thread.
 * Only [flushBlocking], which the crash handler calls, waits for them. Before [install] runs, a
 * line reaches logcat alone — the JVM tests of classes that log never install it.
 *
 * Events only, never per frame or per tile: 256 kB is a few thousand lines, and a log that rolls
 * over in ten minutes of radar playback explains nothing about yesterday.
 */
object AppLog {

    @Volatile private var file: LogFile? = null
    private val lock = Any()
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppLog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

    /** The directory every diagnostic file lives in. */
    fun directory(context: Context): File = File(context.filesDir, DIRECTORY)

    fun install(context: Context) = installAt(directory(context))

    /** For tests, which have a temporary directory and no Context. */
    internal fun installAt(dir: File) {
        file = LogFile(dir)
    }

    /** For tests, so one test's directory does not keep receiving the next test's lines. */
    internal fun uninstall() {
        flushBlocking()
        file = null
    }

    fun i(tag: String, message: String) = write('I', tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = write('W', tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = write('E', tag, message, error)

    /** The last [count] lines written, for the crash report. Blocks; call off the main thread. */
    fun tail(count: Int): List<String> = synchronized(lock) { file?.tail(count).orEmpty() }

    /** Waits for every line already handed over, up to [timeoutMs]. For the crash handler. */
    fun flushBlocking(timeoutMs: Long = 1_000) {
        if (file == null) return
        runCatching { writer.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    /** Both log files, oldest first. */
    fun files(): List<File> = synchronized(lock) { file?.files().orEmpty() }

    private fun write(level: Char, tag: String, message: String, error: Throwable?) {
        val text = redact(message)
        when (level) {
            'E' -> Log.e(tag, text, error)
            'W' -> Log.w(tag, text, error)
            else -> Log.i(tag, text)
        }
        val target = file ?: return
        val line = buildString {
            append(ZonedDateTime.now().format(stamp)).append(' ').append(level).append(' ')
            append(tag).append(": ").append(text)
            if (error != null) append('\n').append(redact(shortTrace(error)))
        }
        runCatching { writer.execute { synchronized(lock) { runCatching { target.append(line) } } } }
    }

    /** A cause chain without the framework's forty frames of looper under every one of them. */
    internal fun shortTrace(error: Throwable, framesPerCause: Int = 8): String = buildString {
        var t: Throwable? = error
        var depth = 0
        while (t != null && depth < 5) {
            if (depth > 0) append("\nCaused by: ")
            append(t.javaClass.name).append(": ").append(t.message)
            t.stackTrace.take(framesPerCause).forEach { append("\n    at ").append(it) }
            if (t.stackTrace.size > framesPerCause) append("\n    … ").append(t.stackTrace.size - framesPerCause).append(" more")
            t = t.cause.takeIf { it !== t }
            depth++
        }
    }

    const val DIRECTORY = "diagnostics"
}
