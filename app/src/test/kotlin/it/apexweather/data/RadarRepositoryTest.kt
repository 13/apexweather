package it.apexweather.data

import it.apexweather.RadarFixtures
import it.apexweather.data.remote.RainViewerApi
import it.apexweather.data.remote.RainViewerFrame
import it.apexweather.data.remote.RainViewerMaps
import it.apexweather.data.remote.RainViewerRadar
import it.apexweather.domain.RadarReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
@OptIn(ExperimentalCoroutinesApi::class)
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
        override suspend fun tile(url: String): ResponseBody = throw IOException("no tiles in this fake")
    }

    private val decoder = TileDecoder { bytes -> RadarFixtures.decode(bytes).let { (w, px) -> DecodedTile(w, px) } }

    private val t0: Instant = Instant.parse("2026-09-10T11:00:00Z")

    @Test
    fun `the first call fetches`() = runTest {
        val api = FakeApi()
        val frames = RadarRepository(api, decoder, MovableClock(t0)).frames()
        assertEquals(1, api.calls)
        assertEquals(1, frames.size)
    }

    /** The upstream publishes every ten minutes; asking more often than that is asking for nothing. */
    @Test
    fun `a second call inside ten minutes does not fetch again`() = runTest {
        val api = FakeApi()
        val clock = MovableClock(t0)
        val repo = RadarRepository(api, decoder, clock)
        repo.frames()
        clock.now = t0.plusSeconds(9 * 60)
        repo.frames()
        assertEquals(1, api.calls)
    }

    @Test
    fun `after ten minutes it fetches again`() = runTest {
        val api = FakeApi()
        val clock = MovableClock(t0)
        val repo = RadarRepository(api, decoder, clock)
        repo.frames()
        clock.now = t0.plusSeconds(10 * 60)
        repo.frames()
        assertEquals(2, api.calls)
    }

    /** Nothing to draw is a state the map is built for, not an exception it has to catch. */
    @Test
    fun `a failed fetch yields no frames rather than throwing`() = runTest {
        val frames = RadarRepository(FakeApi(fail = true), decoder, MovableClock(t0)).frames()
        assertTrue(frames.isEmpty())
    }

    /** Two hours of imagery beats nothing, and the map's own time label says how old it is. */
    @Test
    fun `a failed refresh keeps the frames it already had`() = runTest {
        val api = FakeApi()
        val clock = MovableClock(t0)
        val repo = RadarRepository(api, decoder, clock)
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
        val repo = RadarRepository(api, decoder, clock)
        repo.frames()
        api.fail = true
        clock.now = t0.plusSeconds(11 * 60)
        repo.frames()
        api.fail = false
        repo.frames()
        assertEquals(3, api.calls)
    }

    /** This morning, 04:30Z to 05:40Z, as RainViewer listed it and as its tiles read at Dorf Tirol. */
    private class MorningApi : RainViewerApi {
        val times = listOf("0430", "0440", "0450", "0500", "0510", "0520", "0530", "0540")
        var tileCalls = 0
        var failing: String? = null
        override suspend fun weatherMaps() = RainViewerMaps(
            host = "https://tilecache.rainviewer.com",
            radar = RainViewerRadar(past = times.map {
                RainViewerFrame(Instant.parse("2026-09-14T${it.take(2)}:${it.drop(2)}:00Z").epochSecond, "/v2/radar/$it")
            }),
        )
        override suspend fun tile(url: String): ResponseBody {
            tileCalls++
            val time = url.substringAfter("/v2/radar/").take(4)
            check("/7/67/45/" in url) { "asked for the wrong tile: $url" }
            if (time == failing) throw IOException("dropped")
            return RadarFixtures.bytes("radar-z7-67-45-${time}Z.png").toResponseBody("image/png".toMediaType())
        }
    }

    private val lat = 46.688958
    private val lon = 11.156624

    @Test
    fun `readings at the place come from that place's own tile`() = runTest {
        val api = MorningApi()
        val readings = RadarRepository(api, decoder, MovableClock(Instant.parse("2026-09-14T05:45:00Z"))).readingsAt(lat, lon)
        assertEquals(8, readings[Instant.parse("2026-09-14T04:30:00Z")]?.dbz)
        assertEquals(RadarReading.NO_ECHO, readings[Instant.parse("2026-09-14T05:40:00Z")])
        assertEquals(8, readings.size)
    }

    @Test
    fun `a tile is fetched once per frame, not once per ask`() = runTest {
        val api = MorningApi()
        val repo = RadarRepository(api, decoder, MovableClock(Instant.parse("2026-09-14T05:45:00Z")))
        repo.readingsAt(lat, lon)
        repo.readingsAt(lat, lon)
        assertEquals(8, api.tileCalls)
    }

    /** A dropped tile is an unknown, not a dry frame: a missing reading must never overrule a forecast. */
    @Test
    fun `a failed tile leaves its frame out`() = runTest {
        val api = MorningApi().apply { failing = "0540" }
        val readings = RadarRepository(api, decoder, MovableClock(Instant.parse("2026-09-14T05:45:00Z"))).readingsAt(lat, lon)
        assertFalse(readings.containsKey(Instant.parse("2026-09-14T05:40:00Z")))
        assertEquals(7, readings.size)
    }

    /** The first two calls return at once; the third and every one after it hang until cancelled. */
    private class SlowApi : RainViewerApi {
        val times = listOf("0430", "0440", "0450", "0500", "0510", "0520", "0530", "0540")
        var tileCalls = 0
        override suspend fun weatherMaps() = RainViewerMaps(
            host = "https://tilecache.rainviewer.com",
            radar = RainViewerRadar(past = times.map {
                RainViewerFrame(Instant.parse("2026-09-14T${it.take(2)}:${it.drop(2)}:00Z").epochSecond, "/v2/radar/$it")
            }),
        )
        override suspend fun tile(url: String): ResponseBody {
            tileCalls++
            if (tileCalls > 2) delay(60_000)
            val time = url.substringAfter("/v2/radar/").take(4)
            return RadarFixtures.bytes("radar-z7-67-45-${time}Z.png").toResponseBody("image/png".toMediaType())
        }
    }

    /**
     * A cancelled fetch must stop the loop, not be swallowed and retried on every frame left.
     *
     * The count is [RadarRepository.MAX_PARALLEL_TILES] plus the two that returned at once, not the
     * old sequential three: four tiles start together, the first two of those (calls 1 and 2) return
     * immediately and free their permits, which lets two more (calls 5 and 6) start and then hang —
     * so by the time `cancel` runs, six calls have been made and four fetches are stuck mid-flight.
     * Cancelling must stop it there rather than let the two still-queued frames (7 and 8) ever call
     * the API at all, which is what this pins.
     */
    @Test
    fun `cancelling readingsAt stops fetching further tiles`() = runTest {
        val api = SlowApi()
        val repo = RadarRepository(api, decoder, MovableClock(Instant.parse("2026-09-14T05:45:00Z")))
        val job = launch { repo.readingsAt(lat, lon) }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertEquals(RadarRepository.MAX_PARALLEL_TILES + 2, api.tileCalls)
        assertTrue("cancellation must stop it short of every frame", api.tileCalls < api.times.size)
    }

    /** The third dimension the old, strictly sequential fetch could not have: real concurrency. */
    private class ConcurrencyApi : RainViewerApi {
        val times = listOf("0430", "0440", "0450", "0500", "0510", "0520", "0530", "0540")
        var tileCalls = 0
        var inFlight = 0
        var peakInFlight = 0
        override suspend fun weatherMaps() = RainViewerMaps(
            host = "https://tilecache.rainviewer.com",
            radar = RainViewerRadar(past = times.map {
                RainViewerFrame(Instant.parse("2026-09-14T${it.take(2)}:${it.drop(2)}:00Z").epochSecond, "/v2/radar/$it")
            }),
        )
        override suspend fun tile(url: String): ResponseBody {
            tileCalls++
            inFlight++
            peakInFlight = maxOf(peakInFlight, inFlight)
            // Long enough that every tile still running overlaps every other one still running,
            // rather than racing to finish before the next batch even starts.
            delay(100)
            inFlight--
            val time = url.substringAfter("/v2/radar/").take(4)
            return RadarFixtures.bytes("radar-z7-67-45-${time}Z.png").toResponseBody("image/png".toMediaType())
        }
    }

    @Test
    fun `tiles are fetched concurrently, at most four at a time`() = runTest {
        val api = ConcurrencyApi()
        val repo = RadarRepository(api, decoder, MovableClock(Instant.parse("2026-09-14T05:45:00Z")))
        val readings = repo.readingsAt(lat, lon)
        assertEquals(RadarRepository.MAX_PARALLEL_TILES, api.peakInFlight)
        assertEquals(8, api.tileCalls)
        assertEquals(8, readings.size)
    }
}
