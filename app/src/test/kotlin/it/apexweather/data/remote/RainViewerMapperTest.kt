package it.apexweather.data.remote

import it.apexweather.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * RainViewer's public Weather Maps API, against a response recorded on 2026-09-10.
 *
 * The zoom ceiling asserted here was found by fetching tiles rather than by reading the docs: z7
 * returns real radar and z8 returns a 1370-byte PNG with "Zoom Level Not Supported" written on it.
 * The map clamps itself to that, so if the ceiling ever moves, this is where it is written down.
 */
class RainViewerMapperTest {

    private fun maps(name: String = "rainviewer.json"): RainViewerMaps =
        Fixtures.json.decodeFromString(RainViewerMaps.serializer(), Fixtures.read(name))

    @Test
    fun `every past frame becomes a radar frame`() {
        val frames = RainViewerMapper.map(maps())
        assertEquals(3, frames.size)
        assertEquals(Instant.parse("2026-09-10T10:40:00Z"), frames.first().time)
        assertEquals(Instant.parse("2026-09-10T11:00:00Z"), frames.last().time)
    }

    @Test
    fun `the frames run oldest to newest, so the animation plays forwards`() {
        val frames = RainViewerMapper.map(maps())
        assertEquals(frames.map { it.time }.sorted(), frames.map { it.time })
    }

    /** The tile URL is assembled here rather than in the map view, so it can be asserted. */
    @Test
    fun `a tile url carries the host, the frame, the size, the scheme and the options`() {
        val frame = RainViewerMapper.map(maps()).first()
        assertEquals(
            "https://tilecache.rainviewer.com/v2/radar/5fa7198de455/256/7/67/45/4/1_1.png",
            frame.tileUrl(z = 7, x = 67, y = 45),
        )
    }

    @Test
    fun `the radar stops at zoom seven`() {
        assertEquals(7, RainViewerMapper.MAX_ZOOM)
    }

    /** A frame with no path cannot be fetched, and a frame with no time cannot be placed. */
    @Test
    fun `frames that could not be used are dropped rather than drawn`() {
        val broken = RainViewerMaps(
            host = "https://tilecache.rainviewer.com",
            radar = RainViewerRadar(
                past = listOf(
                    RainViewerFrame(time = 1789036800, path = ""),
                    RainViewerFrame(time = 0, path = "/v2/radar/abc"),
                    RainViewerFrame(time = 1789037400, path = "/v2/radar/def"),
                ),
            ),
        )
        assertEquals(
            listOf("/v2/radar/def"),
            RainViewerMapper.map(broken).map { it.base.removePrefix("https://tilecache.rainviewer.com") },
        )
    }

    /** A response with no host is a response nothing can be fetched from. */
    @Test
    fun `no host means no frames`() {
        val hostless = RainViewerMaps(host = "", radar = RainViewerRadar(past = listOf(RainViewerFrame(1789036800, "/v2/radar/abc"))))
        assertTrue(RainViewerMapper.map(hostless).isEmpty())
    }
}
