package it.apexweather.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogFileTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `rotates into one old file and never keeps a third`() {
        val dir = tmp.newFolder()
        val log = LogFile(dir, maxBytes = 100)
        repeat(30) { log.append("line %02d of the log".format(it)) }
        assertEquals(setOf(LogFile.NAME, LogFile.OLD_NAME), dir.list()!!.toSet())
        assertTrue(File(dir, LogFile.NAME).length() <= 100 + 30)
    }

    @Test
    fun `tail reads across the rotation, oldest first`() {
        val log = LogFile(tmp.newFolder(), maxBytes = 60)
        repeat(10) { log.append("n$it") }
        assertEquals(listOf("n7", "n8", "n9"), log.tail(3))
        assertEquals("n9", log.tail(100).last())
    }

    @Test
    fun `the WU key never reaches a line`() {
        val url = "https://api.weather.com/v2/pws/observations/current?stationId=ITIROL16&format=json&units=m&apiKey=0123456789abcdef"
        val out = redact("GET $url failed")
        assertFalse(out.contains("0123456789abcdef"))
        assertTrue(out.contains("stationId=ITIROL16"))
        assertTrue(out.contains("apiKey=…"))
    }

    @Test
    fun `redaction leaves ordinary text alone`() {
        val text = "refresh 021101: 12 ok in 3400 ms, failed GEOSPHERE_AROME"
        assertEquals(text, redact(text))
    }

    @Test
    fun `lines are written off the caller's thread and reach the file`() {
        val dir = tmp.newFolder()
        AppLog.installAt(dir)
        try {
            AppLog.w("Test", "something with key=secret in it", IllegalStateException("boom"))
            AppLog.flushBlocking()
            val text = File(dir, LogFile.NAME).readText()
            assertTrue(text.contains(" W Test: something with key=… in it"))
            assertTrue(text.contains("java.lang.IllegalStateException: boom"))
            assertFalse(text.contains("secret"))
        } finally {
            AppLog.uninstall()
        }
    }
}
