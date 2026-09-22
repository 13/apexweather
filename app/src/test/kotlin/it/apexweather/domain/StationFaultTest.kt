package it.apexweather.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationFaultTest {
    @Test
    fun `a reading near the models is usable`() {
        assertTrue(StationFault.usable(14.0, 12.0))
    }

    /**
     * Three or four degrees off the models is what a thermometer standing in a village is *for*.
     * Measured at Dorf Tirol on 2026-09-22: ITIROL16 ran +4,1 K above the valley floor at 05:00 and
     * -6,6 K below it at 08:00, and both were correct.
     */
    @Test
    fun `a large but plausible disagreement is usable`() {
        assertTrue(StationFault.usable(4.0, 12.0))
        assertTrue(StationFault.usable(20.0, 12.0))
    }

    /** The boundary is the one the statistics screen already counts a fault at. */
    @Test
    fun `exactly the fault limit is still usable`() {
        assertTrue(StationFault.usable(12.0 + ForecastScores.TEMP_FAULT_K, 12.0))
        assertTrue(StationFault.usable(12.0 - ForecastScores.TEMP_FAULT_K, 12.0))
    }

    @Test
    fun `past the fault limit it is not usable`() {
        assertFalse(StationFault.usable(30.0, 12.0))
        assertFalse(StationFault.usable(-5.0, 12.0))
    }

    /** Nothing to check against is not the same as suspect. */
    @Test
    fun `no model to compare against leaves the reading usable`() {
        assertTrue(StationFault.usable(14.0, null))
    }

    @Test
    fun `no reading is not usable`() {
        assertFalse(StationFault.usable(null, 12.0))
        assertFalse(StationFault.usable(null, null))
    }
}
