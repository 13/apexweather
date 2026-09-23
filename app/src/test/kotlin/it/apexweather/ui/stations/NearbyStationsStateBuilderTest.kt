package it.apexweather.ui.stations

import it.apexweather.domain.NearbyStation
import it.apexweather.domain.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class NearbyStationsStateBuilderTest {
    private val official = NearbyStation(
        code = "23200MS", name = "Meran", lat = 46.688, lon = 11.1366,
        altitudeM = 330, distanceKm = 1.53, network = "siag",
    )
    private val catalogue = NearbyStation(
        code = "ITIROL16", name = "Tirolo - Tirol", lat = 46.693246, lon = 11.155237,
        altitudeM = 634, distanceKm = 0.49, network = "wu",
    )
    private val place = Place(
        istat = "021101", nameDe = "Dorf Tirol", nameIt = "Tirolo", nameEn = "Tirol",
        lat = 46.688958, lon = 11.156624, altitudeM = 594, district = 2,
        station = official, pws = catalogue,
    )

    private fun probe(code: String, claimed: Int?, dem: Int?, km: Double) = StationProbe(
        code = code, name = code, distanceKm = km, lat = 46.69, lon = 11.15,
        claimedAltitudeM = claimed, demAltitudeM = dem, reading = null,
    )

    private fun build(probes: List<StationProbe>) = NearbyStationsStateBuilder.build(
        place = place, probes = probes, provincial = null, locale = Locale.GERMAN,
    )

    @Test
    fun stationsAreOrderedByDistanceWithTheProvincialOneLast() {
        val state = build(listOf(probe("FAR", 600, 610, 2.0), probe("NEAR", 600, 610, 0.3)))
        assertEquals(listOf("NEAR", "FAR", "23200MS"), state.rows.map { it.code })
    }

    @Test
    fun theStationThisPlaceReadsIsMarkedChosen() {
        val state = build(listOf(probe("ITIROL16", 634, 639, 0.49)))
        assertTrue(state.rows.first { it.code == "ITIROL16" }.chosen)
        assertFalse(state.rows.first { it.code == "23200MS" }.chosen)
    }

    /** The ground is what gets stored; the claim is shown beside it so the difference is visible. */
    @Test
    fun aDisputedHeightIsMarkedAndTheGroundIsWhatWouldBeStored() {
        val row = build(listOf(probe("ITIROL25", 182, 631, 0.35))).rows.first { it.code == "ITIROL25" }
        assertTrue(row.heightDisputed)
        assertEquals(631, row.demAltitudeM)
        assertEquals(631, row.asChosen("021101")?.altitudeM)
    }

    @Test
    fun aClaimWithinTheGateIsNotMarked() {
        assertFalse(build(listOf(probe("ITIROL26", 669, 659, 0.68))).rows.first { it.code == "ITIROL26" }.heightDisputed)
    }

    /**
     * Without the ground there is no height to store, so nothing may be chosen — the reading is
     * still worth showing, because looking at what the neighbours say is half of what this screen
     * is for.
     */
    @Test
    fun withoutTheGroundNoAmateurStationIsSelectable() {
        val state = build(listOf(probe("ITIROL26", 669, null, 0.68)))
        assertTrue(state.heightsUnknown)
        assertTrue(state.rows.filterNot { it.provincial }.none { it.selectable })
        assertNull(state.rows.first { it.code == "ITIROL26" }.asChosen("021101"))
    }

    /** The province's own station has a surveyed height and needs no elevation request. */
    @Test
    fun theProvincialStationIsSelectableEvenWithoutTheGround() {
        val state = build(listOf(probe("ITIROL26", 669, null, 0.68)))
        assertTrue(state.rows.first { it.code == "23200MS" }.selectable)
        assertEquals("siag", state.rows.first { it.code == "23200MS" }.asChosen("021101")?.network)
        assertEquals(330, state.rows.first { it.code == "23200MS" }.asChosen("021101")?.altitudeM)
    }

    /** The one already being read is not offered as something to pick. */
    @Test
    fun theChosenStationIsNotSelectable() {
        assertFalse(build(listOf(probe("ITIROL16", 634, 639, 0.49))).rows.first { it.code == "ITIROL16" }.selectable)
    }
}
