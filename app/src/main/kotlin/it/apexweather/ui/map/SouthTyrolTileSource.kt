package it.apexweather.ui.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.util.MapTileIndex
import java.util.Locale

/**
 * The province's own map, shaded from the province's own elevation model.
 *
 * `Basemap-Meteo-Dark` is what the Autonome Provinz Bozen – Südtirol publishes for exactly this
 * job: a grey relief map with roads and bilingual labels and no colour of its own, drawn over the
 * LiDAR terrain model. Which matters twice over. The ground under a radar loop should be legible
 * without competing with it, and the previous basemap was OpenStreetMap's daylight raster style
 * held down by a colour matrix — a green-and-beige road map dimmed until it stopped shouting,
 * which is not the same thing as a map made to be looked through. And in a province that is all
 * valleys, the shape of the ground *is* the information: rain sitting in the Etschtal and rain on
 * the Schlern are different facts, and a flat map cannot tell them apart.
 *
 * It is also better behaved. The province publishes this under CC0, so the pre-emptive-fetch
 * restriction that the OSM tile policy imposes — and that osmdroid encodes as
 * `FLAG_NO_PREVENTIVE` — does not apply here, and the tiles go deeper: OSM's own raster stops
 * being useful for this at about zoom 11, while this draws streets at 17. It stops at the
 * provincial boundary, which is the other half of why the map no longer lets the reader leave.
 *
 * The service is WMTS rather than XYZ, but its EPSG_3857 matrix set is the ordinary Web Mercator
 * one — top-left corner at −20037508, 256 px tiles — so a tile's row and column are its y and x,
 * and only the zero-padded two-digit zoom has to be written out.
 */
class SouthTyrolTileSource : OnlineTileSourceBase(
    "southtyrol-meteo-dark",
    MIN_ZOOM,
    MAX_ZOOM,
    TILE_SIZE,
    ".jpeg",
    arrayOf("https://geoservices.buergernetz.bz.it/mapproxy/root/wmts/p_bz-BaseMap:Basemap-Meteo-Dark/EPSG_3857/"),
    "© Autonome Provinz Bozen – Südtirol",
    // FLAG_NO_BULK because nothing here ever mass-downloads. Not FLAG_NO_PREVENTIVE: that exists to
    // honour the OSM tile policy, and this is not OSM's tile server.
    TileSourcePolicy(4, TileSourcePolicy.FLAG_NO_BULK),
) {
    override fun getTileURLString(pMapTileIndex: Long): String = baseUrl + String.format(
        // Locale.ROOT because this is a URL. Numbers a reader sees go through ui/common/Format.kt;
        // a tile path is not one of them, and a locale with its own digits would break it.
        Locale.ROOT,
        "%02d/%d/%d.jpeg",
        MapTileIndex.getZoom(pMapTileIndex),
        MapTileIndex.getX(pMapTileIndex),
        MapTileIndex.getY(pMapTileIndex),
    )

    companion object {
        const val TILE_SIZE = 256

        /** The whole province fits on one screen at 7; below that the map is Europe. */
        const val MIN_ZOOM = 7

        /** Where the labels and streets stop being drawn. */
        const val MAX_ZOOM = 18
    }
}
