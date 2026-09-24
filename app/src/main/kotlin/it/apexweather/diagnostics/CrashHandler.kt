package it.apexweather.diagnostics

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId

/**
 * Writes an uncaught exception to `crash-<epochMs>.txt` and then lets the process die the way it
 * would have anyway.
 *
 * **Synchronous, on the crashing thread.** Handing the write to [AppLog]'s thread would race the
 * process's death and usually lose. The file is small and the process is ending; blocking is the
 * one correct thing to do here.
 *
 * **It chains to the handler it replaced.** That handler is the platform's, which shows the system
 * dialog and kills the process — swallowing the exception instead would leave a half-dead app on
 * screen with its main thread gone.
 *
 * Installed first in `Application.onCreate`, before anything Hilt builds, so a crash while the
 * graph is being assembled is caught too.
 */
class CrashHandler(
    private val dir: File,
    private val appInfo: () -> Map<String, String>,
    private val previous: Thread.UncaughtExceptionHandler?,
    private val clock: () -> Instant = Instant::now,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, error: Throwable) {
        runCatching {
            AppLog.e("Crash", "uncaught on ${thread.name}", error)
            AppLog.flushBlocking(500)
            val now = clock()
            dir.mkdirs()
            File(dir, "$CRASH_PREFIX${now.toEpochMilli()}.txt").writeText(
                redact(CrashReport.format(now, thread.name, error, appInfo(), AppLog.tail(RECENT_LINES))),
            )
            prune(dir, CRASH_PREFIX, KEEP_CRASHES)
        }
        previous?.uncaughtException(thread, error)
    }

    companion object {
        const val CRASH_PREFIX = "crash-"
        const val KEEP_CRASHES = 10
        private const val RECENT_LINES = 50

        fun install(dir: File, appInfo: () -> Map<String, String>) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            if (previous is CrashHandler) return
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(dir, appInfo, previous))
        }
    }
}

/** Keeps the newest [keep] files in [dir] whose names start with [prefix]; the rest are deleted. */
internal fun prune(dir: File, prefix: String, keep: Int) {
    dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) }
        ?.sortedByDescending { stampOf(it, prefix) ?: 0L }
        ?.drop(keep)
        ?.forEach { it.delete() }
}

/** The epoch milliseconds in a diagnostic file's name: `crash-1790000000000.txt`, `exit-1790000000000-anr.txt`. */
internal fun stampOf(file: File, prefix: String): Long? =
    file.name.removePrefix(prefix).takeWhile { it.isDigit() }.toLongOrNull()

/** The text of one crash file. Pure, so its shape is pinned by a JVM test. */
object CrashReport {
    fun format(
        at: Instant,
        thread: String,
        error: Throwable,
        appInfo: Map<String, String>,
        recentLog: List<String>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        appendLine("Apex Weather crash")
        appendLine("time: ${at.atZone(zone)}")
        appendLine("thread: $thread")
        appInfo.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine()
        val trace = StringWriter()
        error.printStackTrace(PrintWriter(trace))
        append(trace.toString().trimEnd())
        appendLine()
        if (recentLog.isNotEmpty()) {
            appendLine()
            appendLine("last ${recentLog.size} log lines:")
            recentLog.forEach { appendLine(it) }
        }
    }
}
