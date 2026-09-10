package it.apexweather.ui.map

import it.apexweather.data.remote.RadarFrame
import it.apexweather.data.remote.RainViewerMapper
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

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
    // FLAG_NO_BULK, because nothing here ever mass-downloads. Deliberately *not*
    // FLAG_NO_PREVENTIVE, which osmdroid's own OpenStreetMap source sets to honour the OSM tile
    // policy: that flag is checked in MapTilePreCache and stops the parent tile being fetched, and
    // the parent tile is the whole mechanism by which a z7 radar image gets drawn at z8 and beyond.
    // With it set the map draws no radar at all above zoom 7 — the approximater is handed a tile it
    // has nothing to scale from. RainViewer asks for no such restriction; their limit is a rate.
    TileSourcePolicy(2, TileSourcePolicy.FLAG_NO_BULK),
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

/**
 * Fetches the radar's own zoom-7 tiles for whatever the map is showing, so there is something to
 * scale from at the zooms above them.
 *
 * osmdroid draws a tile above a source's maximum zoom by scaling up its parent, but only when that
 * parent is already in the cache. The machinery meant to put it there — the protected-tile
 * computers feeding `MapTileCache`'s pre-cache — runs inside `garbageCollection()`, which returns
 * early unless the memory cache is over capacity. A radar overlay showing a dozen tiles never comes
 * close, so the parent is never fetched and no rain is ever drawn above zoom 7. That is not a
 * setting to flip; it is why this exists.
 *
 * Asking the provider for the zoom-7 tiles directly sends them through the downloader, which writes
 * them into the same cache the approximater reads. Over this province that is one or two tiles.
 */
fun MapTileProviderBase.fetchRadarParents(map: MapView) {
    val zoom = RainViewerMapper.MAX_ZOOM
    if (map.zoomLevelDouble <= zoom) return // the tiles are fetched directly at this zoom
    val box = map.boundingBox
    val left = tileX(box.lonWest, zoom)
    val right = tileX(box.lonEast, zoom)
    val top = tileY(box.latNorth, zoom)
    val bottom = tileY(box.latSouth, zoom)
    for (x in left..right) {
        for (y in top..bottom) {
            getMapTile(MapTileIndex.getTileIndex(zoom, x, y))
        }
    }
}

private fun tileX(lon: Double, zoom: Int): Int {
    val n = 1 shl zoom
    return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
}

private fun tileY(lat: Double, zoom: Int): Int {
    val n = 1 shl zoom
    val rad = Math.toRadians(lat)
    return floor((1.0 - asinh(tan(rad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
}
