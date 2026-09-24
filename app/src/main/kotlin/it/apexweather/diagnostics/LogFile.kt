package it.apexweather.diagnostics

import java.io.File

/**
 * A text log that cannot grow without bound: [NAME] until it reaches [maxBytes], then it becomes
 * [OLD_NAME] and a fresh [NAME] starts. Two files, so the most that is ever kept is twice the limit
 * and the least, straight after a rotation, is one full file of history.
 *
 * Plain Java IO with no Android in it, so the rotation is tested on the JVM. Not thread-safe on its
 * own; [AppLog] calls it from a single thread, and the crash path takes the same lock.
 */
class LogFile(private val dir: File, private val maxBytes: Long = MAX_BYTES) {

    private val current get() = File(dir, NAME)
    private val old get() = File(dir, OLD_NAME)

    fun append(line: String) {
        dir.mkdirs()
        val file = current
        if (file.length() >= maxBytes) {
            old.delete()
            file.renameTo(old)
        }
        File(dir, NAME).appendText(line + "\n")
    }

    /** The last [count] lines across both files, oldest first. */
    fun tail(count: Int): List<String> {
        val lines = listOf(old, current).filter { it.exists() }.flatMap { it.readLines() }
        return lines.takeLast(count)
    }

    /** Both files, oldest first, for the export. */
    fun files(): List<File> = listOf(old, current).filter { it.exists() }

    companion object {
        const val NAME = "log.txt"
        const val OLD_NAME = "log.1.txt"
        const val MAX_BYTES = 256L * 1024
    }
}

/**
 * Removes what must never be written down: the reader's Weather Underground key, which travels as
 * `apiKey=` in the query string, and anything else shaped like a credential in a URL.
 *
 * Applied to every line [AppLog] writes and to the export's header, not left to the call sites —
 * one forgotten `redact` at a call site would be the whole of the leak.
 */
fun redact(text: String): String = CREDENTIAL.replace(text) { "${it.groupValues[1]}=…" }

private val CREDENTIAL = Regex("""(?i)\b(api_?key|key|token|access_token|password)=[^&\s"']+""")
