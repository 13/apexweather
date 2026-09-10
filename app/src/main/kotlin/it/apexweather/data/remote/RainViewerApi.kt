package it.apexweather.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import java.time.Instant

/**
 * RainViewer's public Weather Maps API: a tile pyramid over the radars of 150-odd countries, free,
 * with no key and no account.
 *
 * One JSON call returns the frames that currently exist — thirteen of them, the last two hours at
 * ten-minute steps — each naming a path under a tile host. There is a `nowcast` array beside them
 * that was empty every time this was looked at, so the app animates the past and promises no
 * future; see [RainViewerMapper].
 *
 * Their free terms require the credit "Weather data by RainViewer" to be visible wherever this is
 * drawn, and say plainly that a radar's owner can have their data withdrawn at any time. The map
 * treats an empty frame list as a normal state for that reason.
 */
interface RainViewerApi {
    @GET("public/weather-maps.json")
    suspend fun weatherMaps(): RainViewerMaps

    companion object { const val BASE_URL = "https://api.rainviewer.com/" }
}

@Serializable
data class RainViewerMaps(
    val host: String = "",
    val radar: RainViewerRadar = RainViewerRadar(),
)

@Serializable
data class RainViewerRadar(
    val past: List<RainViewerFrame> = emptyList(),
    val nowcast: List<RainViewerFrame> = emptyList(),
)

@Serializable
data class RainViewerFrame(
    /** Seconds since the epoch, UTC. */
    val time: Long = 0,
    /** The frame's own path under the tile host, e.g. `/v2/radar/5fa7198de455`. */
    val path: String = "",
)

/** One radar image, and everything needed to fetch its tiles. */
data class RadarFrame(val time: Instant, val base: String) {
    fun tileUrl(z: Int, x: Int, y: Int): String =
        "$base/${RainViewerMapper.TILE_SIZE}/$z/$x/$y/${RainViewerMapper.COLOR_SCHEME}/${RainViewerMapper.OPTIONS}.png"
}

object RainViewerMapper {

    /**
     * Radar tiles exist only this far in. Zoom 8 does not 404 — it returns a 1370-byte PNG with
     * "Zoom Level Not Supported" written across it, which would appear on the map as a grey label
     * where the rain should be. Verified against the live service at z5, z6, z7 and z8 on
     * 2026-09-10. The map's own zoom goes further and lets the tiles upscale.
     */
    const val MAX_ZOOM = 7

    const val TILE_SIZE = 256

    /** RainViewer's colour scheme 4: blue for light rain through yellow and red for heavy. */
    const val COLOR_SCHEME = 4

    /** `{smooth}_{snow}`: blurred rather than blocky, and snow coloured apart from rain. */
    const val OPTIONS = "1_1"

    /**
     * The past frames, oldest first, as fetchable images.
     *
     * `nowcast` is deliberately ignored. It has been empty every time this was checked, and a
     * timeline that sometimes reaches into the future and sometimes does not is worse than one that
     * never claims to.
     */
    fun map(maps: RainViewerMaps): List<RadarFrame> {
        if (maps.host.isBlank()) return emptyList()
        return maps.radar.past
            .filter { it.time > 0 && it.path.isNotBlank() }
            .map { RadarFrame(Instant.ofEpochSecond(it.time), maps.host + it.path) }
            .sortedBy { it.time }
    }
}
