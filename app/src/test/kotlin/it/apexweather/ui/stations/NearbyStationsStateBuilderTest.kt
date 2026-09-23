package it.apexweather.ui.stations

import it.apexweather.domain.DORF_TIROL
import it.apexweather.domain.DORF_TIROL_WITH_PWS
import it.apexweather.domain.hour
import it.apexweather.domain.model.StationObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class NearbyStationsStateBuilderTest {
    private val german = Locale.GERMANY

    private fun reading(temp: Double, name: String = "x") = StationObservation(
        name, hour(3), tempC = temp, humidityPct = 40, windKmh = 1.0, windDir = "N",
        gustKmh = null, precipTodayMm = 0.0, pressureHpa = 1010.0,
    )

    private fun probe(code: String, km: Double, alt: Int?, reading: StationObservation? = null, error: String? = null) =
        StationProbe(code, code, km, alt, reading, error)

    @Test
    fun `stations are ordered by distance and the provincial one is last`() {
        val s = NearbyStationsStateBuilder.build(
            DORF_TIROL_WITH_PWS,
            listOf(probe("IFAR", 1.9, 400, reading(18.0)), probe("INEAR", 0.35, 600, reading(14.0))),
            provincial = reading(19.0, "Meran"),
            locale = german,
        )
        assertEquals(listOf("INEAR", "IFAR", "23200MS"), s.rows.map { it.code })
        assertTrue(s.rows.last().provincial)
    }

    @Test
    fun `the chosen station is marked, and only it`() {
        val s = NearbyStationsStateBuilder.build(
            DORF_TIROL_WITH_PWS,
            listOf(probe("ITIROL16", 0.49, 586, reading(14.0)), probe("IOTHER", 1.0, 600, reading(15.0))),
            provincial = reading(19.0, "Meran"),
            locale = german,
        )
        assertEquals(listOf("ITIROL16"), s.rows.filter { it.chosen }.map { it.code })
    }

    /** With no amateur station in the catalogue the province's own is the one being read. */
    @Test
    fun `without a pws the provincial station is the chosen one`() {
        val s = NearbyStationsStateBuilder.build(
            DORF_TIROL,
            listOf(probe("ITIROL16", 0.49, 586, reading(14.0))),
            provincial = reading(19.0, "Meran"),
            locale = german,
        )
        assertTrue(s.rows.single { it.provincial }.chosen)
        assertFalse(s.rows.single { it.code == "ITIROL16" }.chosen)
    }

    /**
     * 204 is an ordinary hour, not a failure: a live station produces it. The row keeps its place
     * and its distance and simply has nothing to say.
     */
    @Test
    fun `a station with no recent reading is listed without one`() {
        val s = NearbyStationsStateBuilder.build(
            DORF_TIROL_WITH_PWS, listOf(probe("IQUIET", 0.5, 600, reading = null)),
            provincial = null, locale = german,
        )
        val row = s.rows.first { it.code == "IQUIET" }
        assertNull(row.reading)
        assertNull(row.error)
    }

    @Test
    fun `a station that failed carries its error`() {
        val s = NearbyStationsStateBuilder.build(
            DORF_TIROL_WITH_PWS, listOf(probe("IBROKE", 0.5, 600, error = "timeout")),
            provincial = null, locale = german,
        )
        assertEquals("timeout", s.rows.first { it.code == "IBROKE" }.error)
    }

    /**
     * The case this screen exists for: WU had ITIROL26 at 204 m because its form is in feet. A
     * station 350 m from a village at 594 m claiming 182 implies a 50° slope, which is not a siting
     * difference but a bad record.
     */
    @Test
    fun `an impossible altitude is marked unverified`() {
        val s = NearbyStationsStateBuilder.build(
            DORF_TIROL_WITH_PWS,
            listOf(probe("ITIROL25", 0.35, 182, reading(18.0)), probe("ITIROL16", 0.49, 586, reading(14.0))),
            provincial = null, locale = german,
        )
        assertTrue(s.rows.first { it.code == "ITIROL25" }.altitudeUnverified)
        // And a station a few hundred metres from the village is not flagged: that is ordinary here.
        assertFalse(s.rows.first { it.code == "ITIROL16" }.altitudeUnverified)
    }

    /** A station that publishes no altitude is not thereby suspect. */
    @Test
    fun `no claimed altitude is not unverified`() {
        val s = NearbyStationsStateBuilder.build(
            DORF_TIROL_WITH_PWS, listOf(probe("INOALT", 0.5, null, reading(14.0))),
            provincial = null, locale = german,
        )
        assertFalse(s.rows.single { it.code == "INOALT" }.altitudeUnverified)
    }

    /** The catalogue's DEM figure is shown beside the claim, for the one station it knows. */
    @Test
    fun `the chosen station carries the catalogue's verified altitude`() {
        val s = NearbyStationsStateBuilder.build(
            DORF_TIROL_WITH_PWS, listOf(probe("ITIROL16", 0.49, 586, reading(14.0))),
            provincial = null, locale = german,
        )
        val row = s.rows.single { it.code == "ITIROL16" }
        assertEquals(586, row.claimedAltitudeM)
        assertEquals(634, row.verifiedAltitudeM)
    }

    /**
     * And it refuses to guess past what it can see. ITIROL24 claims 128 m at 1,76 km — 265 m/km,
     * perfectly possible ground — and its claim is still wrong by 291 m against the DEM. Only the
     * generator, which has SRTM tiles, can know that; this screen flags the impossible, not the
     * merely untrue.
     */
    @Test
    fun `a wrong but possible altitude is not flagged`() {
        val s = NearbyStationsStateBuilder.build(
            DORF_TIROL_WITH_PWS, listOf(probe("ITIROL24", 1.76, 128, reading(20.0))),
            provincial = null, locale = german,
        )
        assertFalse(s.rows.single { it.code == "ITIROL24" }.altitudeUnverified)
    }

    /** A station on the doorstep does not get its allowance divided down to nothing. */
    @Test
    fun `a very close station still gets a floor on its allowance`() {
        val s = NearbyStationsStateBuilder.build(
            DORF_TIROL_WITH_PWS, listOf(probe("ICLOSE", 0.01, 594 - 100, reading(14.0))),
            provincial = null, locale = german,
        )
        assertFalse(s.rows.single { it.code == "ICLOSE" }.altitudeUnverified)
    }
}
