package it.apexweather.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipFile

class CrashHandlerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `writes the crash, then hands it to the handler it replaced`() {
        val dir = tmp.newFolder()
        var passedOn: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { _, e -> passedOn = e }
        val handler = CrashHandler(dir, { mapOf("version" to "0.34.0") }, previous, clock = { Instant.ofEpochMilli(1_790_000_000_000) })
        val error = RuntimeException("outer", IllegalArgumentException("inner"))

        handler.uncaughtException(Thread.currentThread(), error)

        assertSame(error, passedOn)
        val file = File(dir, "crash-1790000000000.txt")
        val text = file.readText()
        assertTrue(text.contains("version: 0.34.0"))
        assertTrue(text.contains("java.lang.RuntimeException: outer"))
        assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException: inner"))
    }

    @Test
    fun `keeps only the newest ten crashes`() {
        val dir = tmp.newFolder()
        (1..13).forEach { File(dir, "crash-${1_000L + it}.txt").writeText("x") }
        File(dir, "log.txt").writeText("not a crash")
        prune(dir, CrashHandler.CRASH_PREFIX, CrashHandler.KEEP_CRASHES)
        val left = dir.list()!!.filter { it.startsWith("crash-") }.sorted()
        assertEquals(10, left.size)
        assertEquals("crash-1004.txt", left.first())
        assertTrue(File(dir, "log.txt").exists())
    }

    @Test
    fun `the report carries the recent log`() {
        val text = CrashReport.format(
            Instant.parse("2026-09-24T08:00:00Z"), "main", IllegalStateException("x"),
            mapOf("device" to "test"), listOf("a line", "another"), ZoneOffset.UTC,
        )
        assertTrue(text.startsWith("Apex Weather crash\ntime: 2026-09-24T08:00Z\nthread: main\ndevice: test\n"))
        assertTrue(text.contains("last 2 log lines:\na line\nanother"))
    }

    @Test
    fun `an ANR is worth a file and a swipe away is not`() {
        val anr = ExitRecord(1L, 6, "Input dispatching timed out", 100, 204_800, 300_000, "\"main\" prio=5")
        val swiped = anr.copy(reason = 10, trace = null)
        assertTrue(anr.worthAFile)
        assertEquals(false, swiped.worthAFile)
        assertTrue(anr.format(ZoneOffset.UTC).contains("reason: anr\ndescription: Input dispatching timed out"))
        assertTrue(anr.format(ZoneOffset.UTC).endsWith("trace:\n\"main\" prio=5"))
        assertEquals("anr (Input dispatching timed out), importance 100, pss 200 MB", anr.summary())
    }

    @Test
    fun `a Java crash counts once though Android records it too, and a kill is not a crash`() {
        val dir = tmp.newFolder()
        File(dir, "crash-2000.txt").writeText("x")
        File(dir, "exit-2001-crash.txt").writeText("x")
        File(dir, "exit-3000-anr.txt").writeText("x")
        File(dir, "exit-1000-signaled.txt").writeText("x")
        File(dir, "exit-1500-low_memory.txt").writeText("x")
        File(dir, "log.txt").writeText("x")
        // Android reclaiming the process is kept for the export and is not called a crash.
        assertEquals(DiagnosticsSummary(2, Instant.ofEpochMilli(2000)), DiagnosticsSummary.of(dir))
        assertEquals(DiagnosticsSummary(0, null), DiagnosticsSummary.of(tmp.newFolder()))
    }

    @Test
    fun `the export holds every file and never the key`() {
        val dir = tmp.newFolder()
        File(dir, "log.txt").writeText("hello")
        File(dir, "crash-1.txt").writeText("trace")
        val out = File(tmp.root, "d.zip")
        val header = DiagnosticsExport.header(
            mapOf("version" to "0.34.0"),
            "AppSettings(wuApiKey=(set), placeIstat=021101) apiKey=abc123",
            Instant.EPOCH,
        )
        DiagnosticsExport.zip(dir.listFiles()!!.toList(), header, out)
        ZipFile(out).use { zip ->
            assertEquals(listOf("README.txt", "crash-1.txt", "log.txt"), zip.entries().toList().map { it.name })
            val readme = zip.getInputStream(zip.getEntry("README.txt")).readBytes().decodeToString()
            assertTrue(readme.contains("version: 0.34.0"))
            assertTrue(readme.contains("wuApiKey=(set)"))
            assertTrue(!readme.contains("abc123"))
        }
    }
}
