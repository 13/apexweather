package it.apexweather.data

import it.apexweather.data.remote.RainViewerApi
import it.apexweather.data.remote.RainViewerFrame
import it.apexweather.data.remote.RainViewerMaps
import it.apexweather.data.remote.RainViewerRadar
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Radar frames are two hours of imagery that are worthless by tomorrow, so they are held in memory
 * and never written to Room — Room in this app holds things worth showing while offline.
 */
class RadarRepositoryTest {

    private class MovableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneOffset = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
    }

    private class FakeApi(var fail: Boolean = false) : RainViewerApi {
        var calls = 0
        override suspend fun weatherMaps(): RainViewerMaps {
            calls++
            if (fail) throw IOException("no network")
            return RainViewerMaps(
                host = "https://tilecache.rainviewer.com",
                radar = RainViewerRadar(past = listOf(RainViewerFrame(1789036800, "/v2/radar/5fa7198de455"))),
            )
        }
    }

    private val t0: Instant = Instant.parse("2026-09-10T11:00:00Z")

    @Test
    fun `the first call fetches`() = runTest {
        val api = FakeApi()
        val frames = RadarRepository(api, MovableClock(t0)).frames()
        assertEquals(1, api.calls)
        assertEquals(1, frames.size)
    }

    /** The upstream publishes every ten minutes; asking more often than that is asking for nothing. */
    @Test
    fun `a second call inside ten minutes does not fetch again`() = runTest {
        val api = FakeApi()
        val clock = MovableClock(t0)
        val repo = RadarRepository(api, clock)
        repo.frames()
        clock.now = t0.plusSeconds(9 * 60)
        repo.frames()
        assertEquals(1, api.calls)
    }

    @Test
    fun `after ten minutes it fetches again`() = runTest {
        val api = FakeApi()
        val clock = MovableClock(t0)
        val repo = RadarRepository(api, clock)
        repo.frames()
        clock.now = t0.plusSeconds(10 * 60)
        repo.frames()
        assertEquals(2, api.calls)
    }

    /** Nothing to draw is a state the map is built for, not an exception it has to catch. */
    @Test
    fun `a failed fetch yields no frames rather than throwing`() = runTest {
        val frames = RadarRepository(FakeApi(fail = true), MovableClock(t0)).frames()
        assertTrue(frames.isEmpty())
    }

    /** Two hours of imagery beats nothing, and the map's own time label says how old it is. */
    @Test
    fun `a failed refresh keeps the frames it already had`() = runTest {
        val api = FakeApi()
        val clock = MovableClock(t0)
        val repo = RadarRepository(api, clock)
        assertEquals(1, repo.frames().size)
        api.fail = true
        clock.now = t0.plusSeconds(11 * 60)
        assertEquals(1, repo.frames().size)
    }

    /** And it tries again on the next ask rather than waiting out another ten minutes. */
    @Test
    fun `a failed refresh does not start a fresh ten minutes`() = runTest {
        val api = FakeApi()
        val clock = MovableClock(t0)
        val repo = RadarRepository(api, clock)
        repo.frames()
        api.fail = true
        clock.now = t0.plusSeconds(11 * 60)
        repo.frames()
        api.fail = false
        repo.frames()
        assertEquals(3, api.calls)
    }
}
