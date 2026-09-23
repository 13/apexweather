package it.apexweather.ui.stations

import it.apexweather.data.ChosenStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a row turns into when it is picked. The fetching itself belongs to
 * `NearbyStationsRepository` and is tested there.
 */
class NearbyStationsViewModelTest {
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
