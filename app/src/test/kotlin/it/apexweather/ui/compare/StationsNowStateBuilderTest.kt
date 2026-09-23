package it.apexweather.ui.compare

import it.apexweather.data.StationProbe
import it.apexweather.domain.NearbyStation
import it.apexweather.domain.Place
import it.apexweather.domain.model.StationObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class StationsNowStateBuilderTest {
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
    private val now: Instant = Instant.parse("2026-09-23T11:00:00Z")

    private fun reading(temp: Double?, radiation: Double? = null) = StationObservation(
        stationName = "Tirolo - Tirol", time = now, tempC = temp, humidityPct = 58,
        windKmh = null, windDir = null, gustKmh = null, precipTodayMm = 0.0,
        pressureHpa = null, radiationWm2 = radiation,
    )

    private fun probe(code: String, km: Double, reading: StationObservation?, error: String? = null) =
        StationProbe(
            code = code, name = code, distanceKm = km, lat = 46.69, lon = 11.15,
            claimedAltitudeM = 634, demAltitudeM = 639, reading = reading, error = error,
        )

    private fun build(probes: List<StationProbe>, provincial: StationObservation? = reading(12.0)) =
        StationsNowStateBuilder.build(place, probes, provincial, modelsTempC = 12.4)

    @Test
    fun columnsRunNearestFirstWithTheProvincialOneLast() {
        val state = build(listOf(probe("FAR", 2.0, reading(11.0)), probe("NEAR", 0.3, reading(13.0))))
        assertEquals(listOf("NEAR", "FAR", "23200MS"), state.columns.map { it.code })
        assertTrue(state.columns.last().provincial)
    }

    @Test
    fun theStationThisPlaceReadsIsMarked() {
        val state = build(listOf(probe("ITIROL16", 0.49, reading(11.0))))
        assertTrue(state.columns.first { it.code == "ITIROL16" }.chosen)
        assertFalse(state.columns.first { it.code == "23200MS" }.chosen)
    }

    /**
     * A station with no pyranometer must publish no radiation, not a zero: a zero would tell
     * StationSun the sun is not shining, which is a measurement nobody took.
     */
    @Test
    fun aQuantityAStationDoesNotPublishStaysAbsent() {
        val column = build(listOf(probe("ITIROL16", 0.49, reading(11.0, radiation = null))))
            .columns.first { it.code == "ITIROL16" }
        assertNull(column.radiationWm2)
        assertEquals(11.0, column.tempC ?: 0.0, 1e-9)
    }

    /** HTTP 204: it answered and had nothing from the last hour. An ordinary hour, not a fault. */
    @Test
    fun aStationWithNothingRecentIsQuietRatherThanMissing() {
        val column = build(listOf(probe("ITIROL16", 0.49, reading = null)))
            .columns.first { it.code == "ITIROL16" }
        assertTrue(column.quiet)
        assertNull(column.tempC)
    }

    /** A station that could not be reached is not a column: an empty one says nothing to act on. */
    @Test
    fun aStationThatFailedIsLeftOut() {
        val state = build(listOf(probe("BROKEN", 0.4, reading = null, error = "timeout")))
        assertEquals(listOf("23200MS"), state.columns.map { it.code })
    }

    /** The anchor is the models' own number, and the card carries it whatever the stations say. */
    @Test
    fun theAnchorIsTheModelsConsensus() {
        assertEquals(12.4, build(emptyList()).modelsTempC ?: 0.0, 1e-9)
    }

    /** A place with no provincial station and no neighbours has no card to draw. */
    @Test
    fun withNothingAtAllThereIsNothingToShow() {
        val orphan = place.copy(station = null, pws = null)
        val state = StationsNowStateBuilder.build(orphan, emptyList(), null, modelsTempC = null)
        assertFalse(state.hasAnything)
    }
}
