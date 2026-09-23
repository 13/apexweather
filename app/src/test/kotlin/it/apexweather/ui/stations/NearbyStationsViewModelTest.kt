package it.apexweather.ui.stations

import it.apexweather.data.ChosenStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The ViewModel's own fetch needs a Hilt graph and a network; what is worth pinning here is the
 * arithmetic it does with what comes back, which is pure.
 */
class NearbyStationsViewModelTest {
    @Test
    fun coordinatesGoOutAsTwoCommaJoinedListsInTheSameOrder() {
        val points = listOf(46.693246 to 11.155237, 46.694926 to 11.154523)
        assertEquals("46.693246,46.694926", NearbyStationsViewModel.joinLatitudes(points))
        assertEquals("11.155237,11.154523", NearbyStationsViewModel.joinLongitudes(points))
    }

    /** An answer of the wrong length cannot be matched to its stations, so none of it is used. */
    @Test
    fun anElevationAnswerOfTheWrongLengthIsDiscardedWholesale() {
        assertEquals(listOf(null, null), NearbyStationsViewModel.heightsFrom(listOf(639.0), stations = 2))
        assertEquals(listOf(null, null), NearbyStationsViewModel.heightsFrom(null, stations = 2))
    }

    @Test
    fun anElevationAnswerIsRoundedToWholeMetres() {
        assertEquals(listOf(639, 659), NearbyStationsViewModel.heightsFrom(listOf(639.4, 658.6), stations = 2))
    }

    @Test
    fun aRowWithGroundHasARecordToSend() {
        val row = StationRow(
            code = "ITIROL26", name = "Tirol", distanceKm = 0.68, lat = 46.694926, lon = 11.154523,
            claimedAltitudeM = 669, demAltitudeM = 659, reading = null, selectable = true,
        )
        val record: ChosenStation? = row.asChosen("021101")
        assertEquals("ITIROL26", record?.code)
        assertEquals(659, record?.altitudeM)
        assertEquals("wu", record?.network)
    }

    @Test
    fun aRowWithNoGroundHasNoRecordToSend() {
        val row = StationRow(
            code = "ITIROL26", name = "Tirol", distanceKm = 0.68, lat = 46.694926, lon = 11.154523,
            claimedAltitudeM = 669, demAltitudeM = null, reading = null, selectable = false,
        )
        assertNull(row.asChosen("021101"))
    }
}
