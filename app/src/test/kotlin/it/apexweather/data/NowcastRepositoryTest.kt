package it.apexweather.data

import it.apexweather.data.remote.NowcastMapper
import it.apexweather.data.remote.NowcastFeature
import it.apexweather.data.remote.NowcastGeometry
import it.apexweather.data.remote.NowcastParameter
import it.apexweather.data.remote.NowcastProperties
import it.apexweather.data.remote.NowcastResponse
import it.apexweather.data.remote.PrecipNowcast
import kotlinx.coroutines.delay
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

    private class FakeApi : NowcastSource {
        var reference: String = "2026-09-14T05:00+00:00"
        var calls = 0
        var fail = false

        /** When true, AROME answers with real data instead of the default empty response. */
        var withOutlook = false

        /** How long each of the two requests takes to answer. */
        var delayMs: Long = 0

        /** How long INCA alone takes, on top of [delayMs]. */
        var nowcastExtraDelayMs: Long = 0
        override suspend fun nowcast() = run {
            calls++
            if (delayMs + nowcastExtraDelayMs > 0) delay(delayMs + nowcastExtraDelayMs)
            if (fail) throw IOException("no network")
            NowcastMapper.map(NowcastResponse(
                referenceTime = reference,
                timestamps = listOf(reference.replace(":00+", ":15+")),
                features = listOf(NowcastFeature(NowcastGeometry(listOf(11.16, 46.69)), NowcastProperties(mapOf("rr" to NowcastParameter("kg m-2", listOf(0.1)))))),
            ))
        }
        override suspend fun outlook(end: String) = run {
            if (delayMs > 0) delay(delayMs)
            if (!withOutlook) return@run NowcastMapper.map(NowcastResponse())
            NowcastMapper.mapOutlook(NowcastResponse(
                referenceTime = "2026-09-14T00:00+00:00",
                timestamps = listOf("2026-09-14T01:00+00:00"),
                features = listOf(NowcastFeature(NowcastGeometry(listOf(11.16, 46.69)), NowcastProperties(mapOf("rain_p50" to NowcastParameter("kg m-2", listOf(1.0)))))),
            ), after = null)
        }
    }

    private val t = { hhmm: String -> Instant.parse("2026-09-14T$hhmm:00Z") }

    /** The two requests are independent; asked one after the other, AROME waited out INCA's 4,4 s. */
    @Test
    fun `the nowcast and the outlook are fetched side by side`() = runTest {
        val api = FakeApi().apply { withOutlook = true; delayMs = 1_000 }
        val repo = NowcastRepository(api, MutableClock(t("05:36")))
        val held = repo.current()
        assertEquals("one request waited for the other", 1_000L, testScheduler.currentTime)
        assertEquals(1, held.outlook.size)
    }

    /** On 2026-09-16 INCA took 17 s and AROME half a second; the map waited for both. */
    @Test
    fun `the half that lands first is handed over at once`() = runTest {
        val api = FakeApi().apply { withOutlook = true; nowcastExtraDelayMs = 17_000 }
        val partials = mutableListOf<Pair<Long, PrecipNowcast>>()
        val held = NowcastRepository(api, MutableClock(t("05:36"))).current { partials += testScheduler.currentTime to it }
        assertEquals(1, partials.size)
        assertEquals("AROME, before INCA", 0L, partials.single().first)
        assertEquals(1, partials.single().second.outlook.size)
        assertEquals(17_000L, testScheduler.currentTime)
        // Then both: INCA's step, and AROME whole for Heute.
        assertEquals(Instant.parse("2026-09-14T05:15:00Z"), held.steps.single().time)
        assertEquals(1, held.outlook.size)
    }

    /** A failed half keeps what that half held before. */
    @Test
    fun `a failed nowcast keeps the held one beside a fresh outlook`() = runTest {
        val api = FakeApi().apply { withOutlook = true }
        val clock = MutableClock(t("05:36"))
        val repo = NowcastRepository(api, clock)
        val first = repo.current()
        api.fail = true
        clock.now = t("05:51")
        val second = repo.current()
        assertEquals(first.steps.first(), second.steps.first())
    }

    /** About 730 kB a fetch: on a metered connection the map asks every half hour at most. */
    @Test
    fun `a metered connection is asked every half hour at most`() = runTest {
        val api = FakeApi()
        val clock = MutableClock(t("05:36"))
        val repo = NowcastRepository(api, clock, MeteredNetwork { true })
        repo.current()
        clock.now = t("05:51") // due by the run schedule
        repo.current()
        assertEquals(1, api.calls)
        clock.now = t("06:06")
        api.reference = "2026-09-14T05:30+00:00"
        repo.current()
        assertEquals(2, api.calls)
    }

    @Test
    fun `a held run is kept until the next one is due`() = runTest {
        val api = FakeApi()
        val clock = MutableClock(t("05:36"))
        val repo = NowcastRepository(api, clock)
        repo.current()
        assertEquals(1, api.calls)
        // 05:00 + 15 min + 35 min lag = 05:50.
        clock.now = t("05:45")
        repo.current()
        assertEquals("not due yet", 1, api.calls)
        clock.now = t("05:51")
        api.reference = "2026-09-14T05:15+00:00"
        repo.current()
        assertEquals("due", 2, api.calls)
    }

    /** A late run costs a request every three minutes, not one per resume. */
    @Test
    fun `a late run is asked for at most every three minutes`() = runTest {
        val api = FakeApi()
        val clock = MutableClock(t("05:36"))
        val repo = NowcastRepository(api, clock)
        repo.current()
        clock.now = t("05:52")
        repo.current() // due, but the service still has 05:00
        clock.now = t("05:53")
        repo.current()
        assertEquals(2, api.calls)
        clock.now = t("05:55")
        repo.current()
        assertEquals(3, api.calls)
    }

    /** Only a smaller lag is believed: a fetch can land long after a run appeared, never before. */
    @Test
    fun `the lag learns from a run seen sooner`() = runTest {
        val api = FakeApi()
        val clock = MutableClock(t("05:20")) // 05:00 run seen after 20 min
        val repo = NowcastRepository(api, clock)
        repo.current()
        clock.now = t("05:36") // 05:00 + 15 + 20 = 05:35, due
        repo.current()
        assertEquals(2, api.calls)
    }

    /** The run covers the province, so there is nothing to ask again when the reader moves. */
    @Test
    fun `one run serves every place`() = runTest {
        val api = FakeApi()
        val repo = NowcastRepository(api, MutableClock(t("05:36")))
        repo.current()
        repo.current()
        assertEquals(1, api.calls)
    }

    @Test
    fun `a failed fetch keeps what was held`() = runTest {
        val api = FakeApi()
        val clock = MutableClock(t("05:36"))
        val repo = NowcastRepository(api, clock)
        val held = repo.current()
        api.fail = true
        clock.now = t("05:51")
        assertEquals(held, repo.current())
    }

    /**
     * GeoSphere's clock and the reader's are not perfectly synced, so a run can be stamped after the
     * moment the app asks for it. Without a floor of zero that reads as a negative lag, which pulls
     * the next ask earlier than the run that has not appeared yet.
     */
    @Test
    fun `a run stamped ahead of the clock never makes the lag negative`() = runTest {
        val api = FakeApi()
        api.reference = "2026-09-14T05:15+00:00"
        val clock = MutableClock(t("05:10"))
        val repo = NowcastRepository(api, clock)
        repo.current()
        assertEquals(1, api.calls)
        // With a floor of 0 the next run is due at 05:15 + 15 + 0 = 05:30. The old code learns
        // -5 min, is due at 05:25, and asks.
        clock.now = t("05:27")
        repo.current()
        assertEquals(1, api.calls)
    }

    /** AROME succeeding on its own must never move when the next INCA run is expected. */
    @Test
    fun `a run from AROME alone does not move INCA's schedule`() = runTest {
        val api = FakeApi()
        api.withOutlook = true
        val clock = MutableClock(t("05:36"))
        val repo = NowcastRepository(api, clock)
        repo.current()
        assertEquals(1, api.calls)

        clock.now = t("05:51")
        api.fail = true
        repo.current() // INCA fails, AROME alone still answers: due.
        assertEquals(2, api.calls)

        clock.now = t("05:53")
        repo.current() // three-minute floor
        assertEquals(2, api.calls)

        clock.now = t("05:54")
        api.fail = false
        api.reference = "2026-09-14T05:15+00:00"
        repo.current() // due: the held INCA run is still 05:00
        assertEquals(3, api.calls)

        clock.now = t("06:04")
        repo.current() // 05:15 + 15 + 35 = 06:05, not due yet
        assertEquals(3, api.calls)

        clock.now = t("06:06")
        repo.current()
        assertEquals(4, api.calls)
    }

    /** Heute needs every AROME hour, including the ones INCA's finer steps already cover in Jetzt. */
    @Test
    fun `the outlook keeps every hour`() = runTest {
        val api = object : NowcastSource {
            override suspend fun nowcast() = NowcastMapper.map(NowcastResponse(
                referenceTime = "2026-09-14T05:00+00:00",
                timestamps = listOf("2026-09-14T06:00+00:00"),
                features = listOf(NowcastFeature(NowcastGeometry(listOf(11.16, 46.69)), NowcastProperties(mapOf("rr" to NowcastParameter("", listOf(0.1)))))),
            ))
            override suspend fun outlook(end: String) = NowcastMapper.mapOutlook(NowcastResponse(
                referenceTime = "2026-09-14T00:00+00:00",
                timestamps = listOf("2026-09-14T06:00+00:00", "2026-09-14T07:00+00:00"),
                features = listOf(NowcastFeature(NowcastGeometry(listOf(11.16, 46.69)), NowcastProperties(mapOf("rain_p50" to NowcastParameter("", listOf(1.0, 1.0)))))),
            ), after = null)
        }
        val result = NowcastRepository(api, MutableClock(t("05:36"))).current()
        assertEquals(2, result.outlook.size)
        assertEquals(2, result.steps.size) // INCA 06:00, then AROME 07:00 only
    }
}
