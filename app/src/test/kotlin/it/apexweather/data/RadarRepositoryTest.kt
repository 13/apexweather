package it.apexweather.data

import it.apexweather.RadarFixtures
import it.apexweather.data.remote.RainViewerApi
import it.apexweather.data.remote.RainViewerFrame
import it.apexweather.data.remote.RainViewerMaps
import it.apexweather.data.remote.RainViewerRadar
import it.apexweather.domain.RadarReading
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
}
