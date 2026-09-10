package it.apexweather.ui.map

import it.apexweather.data.remote.RadarFrame
import it.apexweather.domain.Place

/**
 * What the map tab draws. Pure, so the screen can be driven from a hand-built state in a test
 * without a network, a tile server or a real MapView.
 */
data class MapUiState(
    val frames: List<RadarFrame> = emptyList(),
    /** Index into [frames]. Out-of-range values resolve to no frame rather than throwing. */
    val selected: Int = 0,
    val playing: Boolean = false,
    val place: Place? = null,
    val loading: Boolean = true,
) {
    val frame: RadarFrame? get() = frames.getOrNull(selected)

    /** The radar could not be reached, and the map is basemap and marker only. */
    val radarUnavailable: Boolean get() = !loading && frames.isEmpty()
}
