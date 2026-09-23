package it.apexweather.domain

import it.apexweather.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Switching amateur stations off has to remove the station from the *place*, because that is the
 * state everything downstream already handles: a hundred-odd of the 116 have no `pws` at all, so
 * there is no second code path to keep correct.
 */
class AmateurStationSettingTest {
    @Test
    fun `switched off, the place reads its provincial station`() {
        val off = DORF_TIROL_WITH_PWS.withAmateurStation(allowed = false)
        assertNull(off.pws)
        assertEquals("23200MS", off.readingStation?.code)
        // And nothing else about the place moves.
        assertEquals(DORF_TIROL_WITH_PWS.istat, off.istat)
        assertEquals(DORF_TIROL_WITH_PWS.station, off.station)
    }

    @Test
    fun `switched on, the place is untouched`() {
        assertSame(DORF_TIROL_WITH_PWS, DORF_TIROL_WITH_PWS.withAmateurStation(allowed = true))
        assertEquals("ITIROL16", DORF_TIROL_WITH_PWS.withAmateurStation(allowed = true).readingStation?.code)
    }

    /** A place that never had one is returned as-is either way, not copied for nothing. */
    @Test
    fun `a place with no amateur station is unaffected`() {
        assertSame(DORF_TIROL, DORF_TIROL.withAmateurStation(allowed = false))
        assertSame(DORF_TIROL, DORF_TIROL.withAmateurStation(allowed = true))
    }

    /** A place with neither station still has nothing to read, and that is not a failure. */
    @Test
    fun `a place with no station at all stays that way`() {
        assertNull(STERZING.withAmateurStation(allowed = false).readingStation)
    }

    /**
     * A station the app has no key to fetch is not a station, and keeping it would be worse than
     * dropping it: [Place.readingStation] is `pws ?: station`, and the Open-Meteo *station
     * reference* is fetched at readingStation's coordinates. A place that kept its pws with no key
     * would have the models asked about a point whose thermometer is never read, while the hero
     * fell back to the provincial reading — and StationDownscale would then subtract two different
     * places from each other, which is the mistake its own documentation is about.
     */
    @Test
    fun `no key drops the amateur station even with the switch on`() {
        val settings = AppSettings(amateurStations = true, wuApiKey = null)
        assertNull(DORF_TIROL_WITH_PWS.forSettings(settings).pws)
        assertEquals("23200MS", DORF_TIROL_WITH_PWS.forSettings(settings).readingStation?.code)
    }

    @Test
    fun `a blank key counts as no key`() {
        val settings = AppSettings(amateurStations = true, wuApiKey = "   ")
        assertNull(DORF_TIROL_WITH_PWS.forSettings(settings).pws)
    }

    @Test
    fun `a key and the switch on keeps it`() {
        val settings = AppSettings(amateurStations = true, wuApiKey = "abc123")
        assertEquals("ITIROL16", DORF_TIROL_WITH_PWS.forSettings(settings).readingStation?.code)
    }

    @Test
    fun `the switch off drops it however good the key`() {
        val settings = AppSettings(amateurStations = false, wuApiKey = "abc123")
        assertNull(DORF_TIROL_WITH_PWS.forSettings(settings).pws)
    }

    @Test
    fun `a place with no amateur station is returned unchanged`() {
        val settings = AppSettings(amateurStations = true, wuApiKey = "abc123")
        assertSame(DORF_TIROL, DORF_TIROL.forSettings(settings))
    }
}
