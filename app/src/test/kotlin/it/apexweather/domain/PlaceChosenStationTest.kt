package it.apexweather.domain

import it.apexweather.data.AppSettings
import it.apexweather.data.ChosenStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceChosenStationTest {
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
    private val settings = AppSettings(amateurStations = true, wuApiKey = "k")
    private val chosen = ChosenStation(
        istat = "021101", network = "wu", code = "ITIROL26", name = "Tirol",
        lat = 46.694926, lon = 11.154523, altitudeM = 659, distanceKm = 0.68,
    )

    @Test
    fun withNothingStoredTheCatalogueStationIsRead() {
        assertEquals("ITIROL16", place.forSettings(settings).readingStation?.code)
    }

    @Test
    fun aChosenAmateurStationIsReadInsteadOfTheCatalogueOne() {
        val resolved = place.forSettings(settings.copy(chosenStations = listOf(chosen)))
        assertEquals("ITIROL26", resolved.readingStation?.code)
        assertEquals(659, resolved.readingStation?.altitudeM)
        assertEquals(46.694926, resolved.readingStation?.lat ?: 0.0, 1e-9)
    }

    /** Choosing the province's own for one place, without switching amateur stations off for all 116. */
    @Test
    fun choosingTheProvincialStationDropsTheAmateurOne() {
        val provincial = chosen.copy(network = "siag", code = "23200MS", name = "Meran", altitudeM = 330)
        val resolved = place.forSettings(settings.copy(chosenStations = listOf(provincial)))
        assertEquals("23200MS", resolved.readingStation?.code)
        assertNull(resolved.pws)
    }

    /** The switch is a statement about every amateur instrument and outranks one place's choice. */
    @Test
    fun theGlobalSwitchOffOverridesAChoice() {
        val resolved = place.forSettings(settings.copy(amateurStations = false, chosenStations = listOf(chosen)))
        assertEquals("23200MS", resolved.readingStation?.code)
    }

    /**
     * Without a key there is nothing to fetch the station with, and a place keeping a `pws` it
     * cannot read would have the models asked about a point whose thermometer is never read.
     */
    @Test
    fun noKeyOverridesAChoice() {
        val resolved = place.forSettings(settings.copy(wuApiKey = null, chosenStations = listOf(chosen)))
        assertEquals("23200MS", resolved.readingStation?.code)
    }

    @Test
    fun aRecordForAnotherPlaceIsIgnored() {
        val elsewhere = chosen.copy(istat = "021051")
        assertEquals("ITIROL16", place.forSettings(settings.copy(chosenStations = listOf(elsewhere))).readingStation?.code)
    }

    /** A place with no provincial station and a chosen amateur one still reads the amateur one. */
    @Test
    fun aPlaceWithNoProvincialStationStillReadsItsChoice() {
        val orphan = place.copy(station = null)
        assertEquals("ITIROL26", orphan.forSettings(settings.copy(chosenStations = listOf(chosen))).readingStation?.code)
    }
}
