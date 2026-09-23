package it.apexweather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChosenStationsTest {
    private val itirol26 = ChosenStation(
        istat = "021101", network = "wu", code = "ITIROL26", name = "Tirol",
        lat = 46.694926, lon = 11.154523, altitudeM = 659, distanceKm = 0.68,
    )

    @Test
    fun aRecordSurvivesTheRoundTrip() {
        assertEquals(listOf(itirol26), ChosenStations.decode(ChosenStations.encode(listOf(itirol26))))
    }

    /** Nothing stored is the ordinary state: 116 places and at most a handful ever overridden. */
    @Test
    fun nothingStoredDecodesToNothing() {
        assertTrue(ChosenStations.decode(null).isEmpty())
        assertTrue(ChosenStations.decode("").isEmpty())
    }

    /**
     * A preference written by another version, or corrupted, must not take the screen down with it.
     * Every other stored list in this app drops what it cannot read.
     */
    @Test
    fun rubbishDecodesToNothingRatherThanThrowing() {
        assertTrue(ChosenStations.decode("{not json").isEmpty())
    }

    @Test
    fun choosingReplacesThatPlacesRecordAndLeavesTheOthers() {
        val other = itirol26.copy(istat = "021051", code = "IBOLZANO2")
        val next = ChosenStations.with(listOf(itirol26, other), itirol26.copy(code = "ITIROL16"))
        assertEquals(2, next.size)
        assertEquals("ITIROL16", next.first { it.istat == "021101" }.code)
        assertEquals("IBOLZANO2", next.first { it.istat == "021051" }.code)
    }

    /** Null is "go back to the catalogue's own choice", which is a removal and not a record. */
    @Test
    fun clearingRemovesThatPlacesRecord() {
        assertTrue(ChosenStations.with(listOf(itirol26), null, istat = "021101").isEmpty())
    }
}
