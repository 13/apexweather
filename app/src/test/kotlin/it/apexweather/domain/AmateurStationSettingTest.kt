package it.apexweather.domain

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
}
