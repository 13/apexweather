package it.apexweather.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * What Android saw happen to the app's previous processes.
 *
 * The only way to learn about the failures an exception handler never sees: an **ANR** — the
 * system kills a frozen app and hands over the main thread's stack, which this writes down — a
 * native crash, and being killed for memory. minSdk is 31, so the API is always there.
 *
 * Read once per process start, off the main thread. Records newer than the last one seen are
 * written as `exit-<epochMs>-<reason>.txt` where they are worth a file, and every one gets a log line.
 */
object ExitReasons {

    const val EXIT_PREFIX = "exit-"
    const val KEEP_EXITS = 20
    private const val PREFS = "diagnostics"
    private const val LAST_SEEN = "last_exit_seen_ms"
    private const val MAX_TRACE_BYTES = 64 * 1024

    fun collect(context: Context) {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(LAST_SEEN, 0L)
        val infos = runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, 0) }
            .getOrElse { AppLog.w(TAG, "cannot read exit reasons", it); return }
            .filter { it.timestamp > lastSeen }
            .sortedBy { it.timestamp }
        if (infos.isEmpty()) return
        val dir = AppLog.directory(context).apply { mkdirs() }
        infos.forEach { info ->
            val record = ExitRecord(
                timestampMs = info.timestamp,
                reason = info.reason,
                description = info.description,
                importance = info.importance,
                pssKb = info.pss,
                rssKb = info.rss,
                trace = if (info.reason == ApplicationExitInfo.REASON_ANR) readTrace(info) else null,
            )
            AppLog.i(TAG, "previous process: ${record.summary()}")
            if (record.worthAFile) {
                runCatching { File(dir, "$EXIT_PREFIX${info.timestamp}-${ExitRecord.reasonName(info.reason)}.txt").writeText(redact(record.format())) }
            }
        }
        prune(dir, EXIT_PREFIX, KEEP_EXITS)
        prefs.edit { putLong(LAST_SEEN, infos.last().timestamp) }
    }

    private fun readTrace(info: ApplicationExitInfo): String? = runCatching {
        info.traceInputStream?.use { stream ->
            // Not readNBytes, which is API 33; minSdk is 31.
            val buffer = ByteArray(MAX_TRACE_BYTES)
            var read = 0
            while (read < buffer.size) {
                val n = stream.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            String(buffer, 0, read, Charsets.UTF_8)
        }
    }.getOrNull()

    private const val TAG = "Exit"
}

/**
 * One previous process's end, in plain fields so its text can be tested without a device.
 *
 * [reason] is an `ApplicationExitInfo.REASON_*` value. The constants are repeated as literals in
 * [reasonName] rather than read from the class, because a JVM test cannot load `android.app`.
 */
data class ExitRecord(
    val timestampMs: Long,
    val reason: Int,
    val description: String?,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
    val trace: String?,
) {
    /** Anything but the ordinary ways an app ends: the reader swiping it away, or Android stopping it. */
    val worthAFile: Boolean get() = reason in FAILURES

    fun summary(): String = "${reasonName(reason)}${description?.let { " ($it)" } ?: ""}, " +
        "importance $importance, pss ${pssKb / 1024} MB"

    fun format(zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        appendLine("Apex Weather: previous process ended")
        appendLine("time: ${Instant.ofEpochMilli(timestampMs).atZone(zone)}")
        appendLine("reason: ${reasonName(reason)}")
        description?.let { appendLine("description: $it") }
        appendLine("importance: $importance")
        appendLine("pss: $pssKb kB, rss: $rssKb kB")
        trace?.let {
            appendLine()
            appendLine("trace:")
            append(it)
        }
    }

    companion object {
        private val FAILURES = setOf(2, 3, 4, 5, 6, 7, 9) // signaled … initialization failure, excessive resource

        fun reasonName(reason: Int): String = when (reason) {
            0 -> "unknown"
            1 -> "exit_self"
            2 -> "signaled"
            3 -> "low_memory"
            4 -> "crash"
            5 -> "crash_native"
            6 -> "anr"
            7 -> "initialization_failure"
            8 -> "permission_change"
            9 -> "excessive_resource_usage"
            10 -> "user_requested"
            11 -> "user_stopped"
            12 -> "dependency_died"
            13 -> "other"
            14 -> "freezer"
            15 -> "package_state_change"
            16 -> "package_updated"
            else -> "reason $reason"
        }
    }
}
