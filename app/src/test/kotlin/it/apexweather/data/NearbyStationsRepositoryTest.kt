package it.apexweather.data

import it.apexweather.domain.NearbyStation
import it.apexweather.domain.Place
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NearbyStationsRepositoryTest {
    private val place = Place(
        istat = "021101", nameDe = "Dorf Tirol", nameIt = "Tirolo", nameEn = "Tirol",
        lat = 46.688958, lon = 11.156624, altitudeM = 594, district = 2,
        station = NearbyStation(
            code = "23200MS", name = "Meran", lat = 46.688, lon = 11.1366,
            altitudeM = 330, distanceKm = 1.53, network = "siag",
        ),
        pws = NearbyStation(
            code = "ITIROL16", name = "Tirolo - Tirol", lat = 46.693246, lon = 11.155237,
            altitudeM = 634, distanceKm = 0.49, network = "wu",
        ),
    )
    private val elsewhere = place.copy(istat = "021051", nameDe = "Bozen", pws = null)

    private val wu = FakeWeatherUnderground(tempC = 11.0, time = java.time.Instant.parse("2026-09-23T11:00:00Z"))
    private val openMeteo = FakeOpenMeteo()
    private val clock = MutableClock(java.time.Instant.parse("2026-09-23T11:00:00Z"))

    private fun repo(key: String? = "k") = NearbyStationsRepository(wu, WuKeySource { key }, openMeteo, clock)

    @Test
    fun theNeighbourhoodIsFetchedOnce() = runTest {
        val r = repo()
        r.neighbourhood(place)
        assertEquals(1, wu.askedNear.size)
    }

    /**
     * Two callers want this — the stations screen and the comparison card — and each fetching for
     * itself is two lots of about eleven requests against a cap of 1500 a day.
     */
    @Test
    fun asecondReadInsideTheWindowSpendsNothing() = runTest {
        val r = repo()
        r.neighbourhood(place)
        clock.now = clock.now.plusSeconds(9 * 60)
        r.neighbourhood(place)
        assertEquals(1, wu.askedNear.size)
    }

    @Test
    fun pastTheWindowItIsFetchedAgain() = runTest {
        val r = repo()
        r.neighbourhood(place)
        clock.now = clock.now.plusSeconds(11 * 60)
        r.neighbourhood(place)
        assertEquals(2, wu.askedNear.size)
    }

    /** A different place is a different neighbourhood, whatever the cache holds. */
    @Test
    fun anotherPlaceIsNotServedFromTheCache() = runTest {
        val r = repo()
        r.neighbourhood(place)
        r.neighbourhood(elsewhere)
        assertEquals(2, wu.askedNear.size)
    }

    /**
     * A failed fetch must not be remembered as an answer: one dropped connection would otherwise
     * cost the card its whole ten-minute window.
     */
    @Test
    fun aFailureIsNotCached() = runTest {
        val r = repo()
        wu.fail = true
        val threw = runCatching { r.neighbourhood(place) }.exceptionOrNull()
        assertTrue("the failure was swallowed: $threw", threw is IOException)
        wu.fail = false
        val found = r.neighbourhood(place)
        assertTrue("the retry returned nothing", found != null)
    }

    /** No key, no neighbourhood — and no request may go out for one. */
    @Test
    fun withoutAKeyNothingIsAskedAndNothingIsReturned() = runTest {
        assertNull(repo(key = null).neighbourhood(place))
        assertTrue(wu.askedNear.isEmpty())
    }

    @Test
    fun theCataloguesOwnStationIsAskedAboutEvenWhenNearOmitsIt() = runTest {
        wu.nearby = listOf("IMERAN3" to 1.42)
        val found = repo().neighbourhood(place)
        assertTrue("the catalogue's station was left out", found!!.stations.any { it.code == "ITIROL16" })
    }

    @Test
    fun stationsComeBackNearestFirst() = runTest {
        wu.nearby = listOf("FAR" to 2.0, "NEAR" to 0.3)
        val found = repo().neighbourhood(place)
        assertEquals(listOf(0.3, 0.49, 2.0), found!!.stations.map { it.distanceKm }.sorted())
        assertEquals(found.stations.map { it.distanceKm }.sorted(), found.stations.map { it.distanceKm })
    }

    /** An answer of the wrong length cannot be matched to its stations, so none of it is used. */
    @Test
    fun anElevationAnswerOfTheWrongLengthIsDiscardedWholesale() {
        assertEquals(listOf(null, null), NearbyStationsRepository.heightsFrom(listOf(639.0), stations = 2))
        assertEquals(listOf(null, null), NearbyStationsRepository.heightsFrom(null, stations = 2))
    }

    @Test
    fun anElevationAnswerIsRoundedToWholeMetres() {
        assertEquals(listOf(639, 659), NearbyStationsRepository.heightsFrom(listOf(639.4, 658.6), stations = 2))
    }
}
