package it.apexweather.data.remote

import it.apexweather.domain.model.WarningLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The same parse as `MeteoAlarmMapperTest`, on a device.
 *
 * It is here because the JVM test cannot catch what actually went wrong: Android's
 * DocumentBuilderFactory does not implement the two hardening features the JVM's does and throws the
 * feature's own name back, so the feed parsed perfectly in the unit test and failed on a phone with
 * "http://apache.org/xml/features/disallow-doctype-decl" recorded as the fetch error. Anything that
 * touches `javax.xml` needs a device to be believed.
 */
class MeteoAlarmMapperDeviceTest {

    private fun feed(): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/meteoalarm_italy.xml")) { "missing fixture" }
            .bufferedReader().readText()

    @Test
    fun theFeedParsesOnAndroid() {
        val warnings = MeteoAlarmMapper.map(feed(), Instant.parse("2026-09-09T12:00:00Z"))
        assertTrue("nothing parsed on device", warnings.isNotEmpty())
        assertTrue(warnings.all { it.areaDesc == "Trentino Alto Adige" })
        assertTrue(warnings.any { it.level == WarningLevel.ORANGE })
    }

    /** A feed carrying a doctype must parse without reaching for whatever it names. */
    @Test
    fun aDoctypeIsNotFollowed() {
        val hostile = """<?xml version="1.0"?>
            <!DOCTYPE feed SYSTEM "http://127.0.0.1:1/nope.dtd">
            <feed xmlns="http://www.w3.org/2005/Atom"></feed>
        """.trimIndent()
        assertEquals(emptyList<Any>(), MeteoAlarmMapper.map(hostile, Instant.parse("2026-09-09T12:00:00Z")))
    }
}
