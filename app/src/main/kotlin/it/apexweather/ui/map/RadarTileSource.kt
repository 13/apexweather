package it.apexweather.ui.map

import it.apexweather.data.remote.RadarFrame
import it.apexweather.data.remote.RainViewerMapper
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.util.MapTileIndex

/**
 * One radar frame as an osmdroid tile source.
 *
 * `maximumZoomLevel` is [RainViewerMapper.MAX_ZOOM] and that is not a guess: zoom 8 returns a PNG
 * reading "Zoom Level Not Supported" rather than a 404, so a source that asked for it would draw
 * that label over the province. Declaring the ceiling makes osmdroid upscale the z7 tiles instead,
 * which is what the reader wants when they pinch in on a shower.
 *
 * The name carries the frame's own timestamp, which is what keeps osmdroid's cache from serving one
 * frame's tiles for another.
 */
class RadarTileSource(private val frame: RadarFrame) : OnlineTileSourceBase(
    "rainviewer-${frame.time.epochSecond}",
    RADAR_MIN_ZOOM,
    RainViewerMapper.MAX_ZOOM,
    RainViewerMapper.TILE_SIZE,
    ".png",
    arrayOf(frame.base),
    "Weather data by RainViewer",
    TileSourcePolicy(
        2,
        TileSourcePolicy.FLAG_NO_BULK or TileSourcePolicy.FLAG_NO_PREVENTIVE,
    ),
) {
    override fun getTileURLString(pMapTileIndex: Long): String = frame.tileUrl(
        z = MapTileIndex.getZoom(pMapTileIndex),
        x = MapTileIndex.getX(pMapTileIndex),
        y = MapTileIndex.getY(pMapTileIndex),
    )

    companion object {
        /** Below this the province is a smudge and the whole Alps fit on one tile. */
        const val RADAR_MIN_ZOOM = 4
    }
}
