package it.apexweather.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class HorizonTest {

    private val zone: ZoneId = SouthTyrol.ZONE

    /** Dorf Tirol, and the skyline `tools/horizons.py` measured for it on 2026-09-13. */
    private val lat = 46.688958
    private val lon = 11.156624

    /** A profile with no mountains in it: the horizon the app assumed everywhere before this. */
    private val flat = List(Horizon.BEARINGS) { 0 }

    /** Twenty degrees of ridge the whole way round — a village at the bottom of a very deep hole. */
    private val walled = List(Horizon.BEARINGS) { 200 }

    @Test
    fun `a profile is only usable at the length the generator writes`() {
        assertTrue(Horizon.usable(flat))
        assertFalse("a catalogue without skylines falls back to the flat horizon", Horizon.usable(null))
        assertFalse("a generator that changed stride must not be read at the old one", Horizon.usable(List(36) { 0 }))
    }

    @Test
    fun `the skyline is interpolated between the two sampled bearings`() {
        val profile = MutableList(Horizon.BEARINGS) { 0 }
        profile[0] = 0
        profile[1] = 100 // 10,0° at a bearing of 5°
        assertEquals(0.0, Horizon.elevationAt(profile, 0.0), 1e-9)
        assertEquals(10.0, Horizon.elevationAt(profile, 5.0), 1e-9)
        // Halfway between the two samples, which is the whole reason this is not a lookup: the sun
        // crosses five degrees of azimuth in twenty minutes, and rounding would quantise every
        // sunset to that.
        assertEquals(5.0, Horizon.elevationAt(profile, 2.5), 1e-9)
    }

    @Test
    fun `the profile wraps rather than running off its end`() {
        val profile = MutableList(Horizon.BEARINGS) { 0 }
        profile[Horizon.BEARINGS - 1] = 100
        // Between the last sample (355°) and the first (0°/360°).
        assertEquals(5.0, Horizon.elevationAt(profile, 357.5), 1e-9)
        assertEquals(0.0, Horizon.elevationAt(profile, 360.0), 1e-9)
        assertEquals(Horizon.elevationAt(profile, 10.0), Horizon.elevationAt(profile, 370.0), 1e-9)
    }

    /**
     * The property that makes the whole thing worth doing: a ridge can only ever shorten the day.
     *
     * A flat profile has to reproduce the astronomical answer exactly, or every place without a
     * skyline in the catalogue would silently start disagreeing with the ephemeris.
     */
    @Test
    fun `a ridge shortens the day and a flat horizon does not`() {
        val date = LocalDate.of(2026, 9, 13)
        val open = Horizon.visibleDaylight(flat, date, zone, lat, lon)
        val hemmed = Horizon.visibleDaylight(walled, date, zone, lat, lon)
        assertNotNull(open)
        assertNotNull(hemmed)
        assertTrue("a wall delays the morning", hemmed!!.first.isAfter(open!!.first))
        assertTrue("and hurries the evening", hemmed.second.isBefore(open.second))
    }

    /**
     * In mid-December the sun never reaches twenty degrees at this latitude, so a village walled in
     * to twenty degrees never sees it. Null is the honest answer and a real one in this province,
     * not a failure to compute.
     */
    @Test
    fun `a day the sun never clears the ridge has no visible daylight at all`() {
        val december = LocalDate.of(2026, 12, 21)
        assertNull(Horizon.visibleDaylight(walled, december, zone, lat, lon))
        assertNotNull("and the same day is an ordinary one on the flat", Horizon.visibleDaylight(flat, december, zone, lat, lon))
    }

    /** The sun is up between the two times it returns, and down outside them. */
    @Test
    fun `visibleDaylight brackets the hours the sun is actually on the place`() {
        val date = LocalDate.of(2026, 9, 13)
        val (up, down) = Horizon.visibleDaylight(walled, date, zone, lat, lon)!!
        assertTrue(Horizon.sunIsUp(walled, up, lat, lon))
        assertTrue(Horizon.sunIsUp(walled, down, lat, lon))
        assertFalse("an hour before it clears the ridge it is behind it", Horizon.sunIsUp(walled, up.minus(1, ChronoUnit.HOURS), lat, lon))
        assertFalse("and an hour after it has gone", Horizon.sunIsUp(walled, down.plus(1, ChronoUnit.HOURS), lat, lon))
    }

    /** Never below the horizon at night, whatever the profile says. */
    @Test
    fun `the sun is never up at midnight`() {
        val midnight = LocalDate.of(2026, 6, 21).atStartOfDay(zone).toInstant()
        assertFalse(Horizon.sunIsUp(flat, midnight, lat, lon))
    }
}
