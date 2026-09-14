package it.apexweather.data

import it.apexweather.data.remote.NowcastApi
import it.apexweather.data.remote.NowcastFeature
import it.apexweather.data.remote.NowcastGeometry
import it.apexweather.data.remote.NowcastParameter
import it.apexweather.data.remote.NowcastProperties
import it.apexweather.data.remote.NowcastResponse
import it.apexweather.domain.DORF_TIROL
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * On 2026-09-14 the map held INCA's 05:00Z run while the 05:15Z run that had dropped a dead shower
 * was already on offer, because it only asked every ten minutes. It now asks when a run is due.
 */
class NowcastRepositoryTest {

    private class FakeApi : NowcastApi {
        var reference: String = "2026-09-14T05:00+00:00"
        var calls = 0
        var fail = false
        override suspend fun precipitation(bbox: String, parameters: String, outputFormat: String): NowcastResponse {
            calls++
            if (fail) throw IOException("no network")
            return NowcastResponse(
                referenceTime = reference,
                timestamps = listOf(reference.replace(":00+", ":15+")),
                features = listOf(NowcastFeature(NowcastGeometry(listOf(11.16, 46.69)), NowcastProperties(mapOf("rr" to NowcastParameter("kg m-2", listOf(0.1)))))),
            )
        }
        override suspend fun outlook(bbox: String, end: String, parameters: String, outputFormat: String) = NowcastResponse()
    }

    private val t = { hhmm: String -> Instant.parse("2026-09-14T$hhmm:00Z") }

    @Test
    fun `a held run is kept until the next one is due`() = runTest {
        val api = FakeApi()
        val clock = MutableClock(t("05:36"))
        val repo = NowcastRepository(api, clock)
        repo.forPlace(DORF_TIROL)
        assertEquals(1, api.calls)
        // 05:00 + 15 min + 35 min lag = 05:50.
        clock.now = t("05:45")
        repo.forPlace(DORF_TIROL)
        assertEquals("not due yet", 1, api.calls)
        clock.now = t("05:51")
        api.reference = "2026-09-14T05:15+00:00"
        repo.forPlace(DORF_TIROL)
        assertEquals("due", 2, api.calls)
    }

    /** A late run costs a request every three minutes, not one per resume. */
    @Test
    fun `a late run is asked for at most every three minutes`() = runTest {
        val api = FakeApi()
        val clock = MutableClock(t("05:36"))
        val repo = NowcastRepository(api, clock)
        repo.forPlace(DORF_TIROL)
        clock.now = t("05:52")
        repo.forPlace(DORF_TIROL) // due, but the service still has 05:00
        clock.now = t("05:53")
        repo.forPlace(DORF_TIROL)
        assertEquals(2, api.calls)
        clock.now = t("05:55")
        repo.forPlace(DORF_TIROL)
        assertEquals(3, api.calls)
    }

    /** Only a smaller lag is believed: a fetch can land long after a run appeared, never before. */
    @Test
    fun `the lag learns from a run seen sooner`() = runTest {
        val api = FakeApi()
        val clock = MutableClock(t("05:20")) // 05:00 run seen after 20 min
        val repo = NowcastRepository(api, clock)
        repo.forPlace(DORF_TIROL)
        clock.now = t("05:36") // 05:00 + 15 + 20 = 05:35, due
        repo.forPlace(DORF_TIROL)
        assertEquals(2, api.calls)
    }

    @Test
    fun `a new place is asked for at once`() = runTest {
        val api = FakeApi()
        val clock = MutableClock(t("05:36"))
        val repo = NowcastRepository(api, clock)
        repo.forPlace(DORF_TIROL)
        repo.forPlace(DORF_TIROL.copy(istat = "021008", lat = 46.5, lon = 11.3))
        assertEquals(2, api.calls)
    }

    @Test
    fun `a failed fetch keeps what was held`() = runTest {
        val api = FakeApi()
        val clock = MutableClock(t("05:36"))
        val repo = NowcastRepository(api, clock)
        val held = repo.forPlace(DORF_TIROL)
        api.fail = true
        clock.now = t("05:51")
        assertEquals(held, repo.forPlace(DORF_TIROL))
    }
}
