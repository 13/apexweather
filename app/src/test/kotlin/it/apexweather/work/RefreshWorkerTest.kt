package it.apexweather.work

import androidx.work.ListenableWorker.Result
import it.apexweather.data.AppSettings
import it.apexweather.data.RefreshResult
import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshWorkerTest {

    @Test
    fun `null result retries`() {
        assertEquals(Result.retry(), RefreshWorker.outcome(null, 0))
    }

    @Test
    fun `all failed retries on first attempt`() {
        val result = RefreshResult(emptyList(), mapOf("X" to "boom"))
        assertEquals(Result.retry(), RefreshWorker.outcome(result, 0))
    }

    @Test
    fun `all failed retries on second attempt`() {
        val result = RefreshResult(emptyList(), mapOf("X" to "boom"))
        assertEquals(Result.retry(), RefreshWorker.outcome(result, 1))
    }

    @Test
    fun `all failed fails on third attempt`() {
        val result = RefreshResult(emptyList(), mapOf("X" to "boom"))
        assertEquals(Result.failure(), RefreshWorker.outcome(result, 2))
    }

    @Test
    fun `any success succeeds`() {
        val result = RefreshResult(listOf("OPEN_METEO"), emptyMap())
        assertEquals(Result.success(), RefreshWorker.outcome(result, 0))
    }

    /**
     * A pin kept a place in the cache and nothing kept it current, so pinning the valley you ski in
     * and not opening it for a week left a week-old forecast to be found the one time it mattered:
     * opening it with no signal, which is the state a mountain is usually in. Keeping and keeping
     * fresh are different promises and only one of them was being met.
     */
    @Test
    fun `pins are refreshed on an unmetered connection`() {
        val settings = AppSettings(placeIstat = "021101", favouritePlaces = listOf("021008", "021051"))
        assertEquals(
            listOf("021008", "021051"),
            RefreshWorker.pinsToRefresh(settings, current = "021101", metered = false),
        )
    }

    /** And never on one somebody is paying for by the megabyte. */
    @Test
    fun `pins are left alone on a metered connection`() {
        val settings = AppSettings(placeIstat = "021101", favouritePlaces = listOf("021008", "021051"))
        assertEquals(emptyList<String>(), RefreshWorker.pinsToRefresh(settings, current = "021101", metered = true))
    }

    /** The place on screen has already been refreshed by the time this runs; it is not fetched twice. */
    @Test
    fun `the current place is not refreshed a second time as a pin`() {
        val settings = AppSettings(placeIstat = "021008", favouritePlaces = listOf("021008", "021051"))
        assertEquals(listOf("021051"), RefreshWorker.pinsToRefresh(settings, current = "021008", metered = false))
    }

    @Test
    fun `no pins is no work`() {
        assertEquals(emptyList<String>(), RefreshWorker.pinsToRefresh(AppSettings(), current = "021101", metered = false))
    }
}
