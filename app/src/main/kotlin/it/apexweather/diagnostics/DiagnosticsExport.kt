package it.apexweather.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import it.apexweather.BuildConfig
import it.apexweather.ui.share.ShareCapture
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * How many crashes the diagnostics directory holds, and since when — the line under the export row.
 *
 * A Java crash leaves two files, the handler's own and Android's exit record of the same death, so
 * exit records whose reason is `crash` are not counted a second time. Only the exit records that
 * are the app failing count at all: a `signaled` or `low_memory` end is Android reclaiming the
 * process, which is worth a file for the export and is not a crash to be reported on screen. Seen
 * on the phone on 2026-09-24, where the history held one `signaled` and no crash at all.
 */
data class DiagnosticsSummary(val failures: Int, val since: Instant?) {
    companion object {
        fun of(dir: File): DiagnosticsSummary {
            val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
            val crashes = files.filter { it.name.startsWith(CrashHandler.CRASH_PREFIX) }
            val exits = files.filter { f ->
                f.name.startsWith(ExitReasons.EXIT_PREFIX) && COUNTED.any { f.name.endsWith("-$it.txt") }
            }
            val stamps = crashes.mapNotNull { stampOf(it, CrashHandler.CRASH_PREFIX) } +
                exits.mapNotNull { stampOf(it, ExitReasons.EXIT_PREFIX) }
            return DiagnosticsSummary(crashes.size + exits.size, stamps.minOrNull()?.let(Instant::ofEpochMilli))
        }

        /** Exit reasons that are the app failing rather than being stopped. `crash` is the handler's file. */
        private val COUNTED = listOf("crash_native", "anr", "initialization_failure")
    }
}

/**
 * Everything in the diagnostics directory as one zip the reader chooses to send.
 *
 * Nothing leaves the phone otherwise: the app has no server, no account and no crash service, and
 * a log names the reader's place and stations. The zip is written into the share provider's one
 * cache directory — the same one [ShareCapture] uses, pruned the same way — so no new path is
 * exposed to other apps.
 */
object DiagnosticsExport {

    const val HEADER_NAME = "README.txt"

    /** The device and build, the lines every crash file and the export header start with. */
    fun appInfo(): Map<String, String> = linkedMapOf(
        "version" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})",
        "commit" to "${BuildConfig.GIT_HASH} ${BuildConfig.GIT_DATE}",
        "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "locale" to Locale.getDefault().toLanguageTag(),
    )

    /** The zip's first entry. [settings] must already have the key taken out; [redact] runs over it anyway. */
    fun header(appInfo: Map<String, String>, settings: String, at: Instant): String = redact(
        buildString {
            appendLine("Apex Weather diagnostics")
            appendLine("exported: $at")
            appInfo.forEach { (k, v) -> appendLine("$k: $v") }
            appendLine()
            appendLine("settings: $settings")
        },
    )

    /** Writes [header] and every file in [files] into [out]; a file that vanished meanwhile is skipped. */
    fun zip(files: List<File>, header: String, out: File) {
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(HEADER_NAME))
            zip.write(header.toByteArray())
            zip.closeEntry()
            files.filter { it.isFile }.sortedBy { it.name }.forEach { file ->
                runCatching { file.readBytes() }.onSuccess { bytes ->
                    zip.putNextEntry(ZipEntry(file.name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
    }

    /** Builds the zip for sharing and returns its `content://` Uri. Blocking; call off the main thread. */
    fun write(context: Context, settings: String, now: Instant): Uri {
        AppLog.flushBlocking()
        val dir = File(context.cacheDir, ShareCapture.DIRECTORY).apply { mkdirs() }
        ShareCapture.prune(dir, now)
        val out = File(dir, "apexweather-diagnostics-${now.toEpochMilli()}.zip")
        val files = AppLog.directory(context).listFiles()?.toList().orEmpty()
        zip(files, header(appInfo(), settings, now), out)
        return FileProvider.getUriForFile(context, context.packageName + ShareCapture.AUTHORITY_SUFFIX, out)
    }

    fun chooser(uri: Uri, subject: String, title: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}
