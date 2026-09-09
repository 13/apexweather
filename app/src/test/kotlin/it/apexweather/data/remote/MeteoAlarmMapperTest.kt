package it.apexweather.data.remote

import it.apexweather.Fixtures
import it.apexweather.domain.model.WarningLevel
import it.apexweather.domain.model.WarningType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Against `meteoalarm_italy.xml`, recorded from the live feed on 2026-09-09, when Trentino Alto
 * Adige was under orange rain and thunderstorm warnings and eighteen other regions had their own.
 */
class MeteoAlarmMapperTest {

    private val feed = Fixtures.read("meteoalarm_italy.xml")
    private val duringTheWarnings: Instant = Instant.parse("2026-09-09T12:00:00Z")

    @Test
    fun `keeps only the region Dorf Tirol is in`() {
        val warnings = MeteoAlarmMapper.map(feed, duringTheWarnings)
        assertTrue(warnings.isNotEmpty())
        assertTrue(warnings.all { it.areaDesc == "Trentino Alto Adige" })
    }

    /** Sardinia's wind warning is in the same document and must never reach a reader in South Tyrol. */
    @Test
    fun `another region's warning is not ours`() {
        val ours = MeteoAlarmMapper.map(feed, duringTheWarnings)
        val sardinia = MeteoAlarmMapper.map(feed, duringTheWarnings, region = "IT019")
        assertTrue(sardinia.isNotEmpty())
        assertTrue(sardinia.all { it.areaDesc == "Sardegna" })
        assertTrue(ours.none { it.identifier in sardinia.map { s -> s.identifier } })
    }

    @Test
    fun `reads the colour and the phenomenon out of the event wording`() {
        val warnings = MeteoAlarmMapper.map(feed, duringTheWarnings)
        assertTrue(warnings.any { it.type == WarningType.RAIN && it.level == WarningLevel.ORANGE })
        assertTrue(warnings.any { it.type == WarningType.THUNDERSTORM })
        assertTrue(warnings.all { it.headline.contains("Warning") })
    }

    /** Worst first: the card shows one warning and it has to be the one that matters most. */
    @Test
    fun `orange sorts above yellow`() {
        val levels = MeteoAlarmMapper.map(feed, duringTheWarnings).map { it.level }
        assertEquals(levels.sortedDescending(), levels)
    }

    @Test
    fun `a warning that has expired is dropped`() {
        val afterEverything = Instant.parse("2026-10-01T00:00:00Z")
        assertTrue(MeteoAlarmMapper.map(feed, afterEverything).isEmpty())
    }

    @Test
    fun `onset and expiry survive the parse`() {
        val w = MeteoAlarmMapper.map(feed, duringTheWarnings).first()
        assertTrue(w.expires.isAfter(duringTheWarnings))
        assertTrue(w.onset.isBefore(w.expires))
        assertTrue(w.identifier.isNotEmpty())
    }

    /** A feed that is not a feed is a source problem, not a crash. */
    @Test(expected = Exception::class)
    fun `malformed xml fails loudly rather than returning half a list`() {
        MeteoAlarmMapper.map("<feed><entry>", duringTheWarnings)
    }

    @Test
    fun `white means no awareness required, not a warning`() {
        assertEquals(null, MeteoAlarmMapper.level("White Rain Warning", "Minor"))
        assertEquals(WarningLevel.RED, MeteoAlarmMapper.level("Red Wind Warning", null))
        // No colour in the wording: CAP's own severity decides.
        assertEquals(WarningLevel.ORANGE, MeteoAlarmMapper.level("Stormwarning", "Severe"))
        assertEquals(null, MeteoAlarmMapper.level("Stormwarning", null))
    }

    @Test
    fun `an unknown phenomenon stays visible rather than being discarded`() {
        assertEquals(WarningType.OTHER, MeteoAlarmMapper.type("Yellow Something Warning"))
        assertEquals(WarningType.SNOW_ICE, MeteoAlarmMapper.type("Orange snow-ice Warning"))
        // "Rain-Flood" contains "rain": the more specific wording has to win.
        assertEquals(WarningType.RAIN_FLOOD, MeteoAlarmMapper.type("Yellow Rain-Flood Warning"))
        assertFalse(MeteoAlarmMapper.type("Yellow Thunderstorm Warning") == WarningType.OTHER)
    }
}
