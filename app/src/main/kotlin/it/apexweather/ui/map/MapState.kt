package it.apexweather.ui.map

import it.apexweather.data.remote.NowcastStep
import it.apexweather.data.remote.RadarFrame
import it.apexweather.domain.Place
import java.time.Instant

/**
 * One position on the map's timeline: either something a radar saw, or something a model expects.
 *
 * They share a scrubber because a reader tracking a shower does not want to change screens halfway
 * through its journey — but they are never drawn alike, and the sheet says which one it is looking
 * at. Confusing "this happened" with "this is expected" is the one mistake a radar map can make
 * that matters.
 */
sealed interface MapFrame {
    val time: Instant

    /** A radar image: where the rain was. */
    data class Observed(val radar: RadarFrame) : MapFrame {
        override val time: Instant get() = radar.time
    }

    /** A quarter-hour of GeoSphere's nowcast: where the rain is expected. */
    data class Forecast(val step: NowcastStep) : MapFrame {
        override val time: Instant get() = step.time
    }
}

/**
 * What the map tab draws. Pure, so the screen can be driven from a hand-built state in a test
 * without a network, a tile server or a real MapView.
 */
data class MapUiState(
    val frames: List<MapFrame> = emptyList(),
    /** Index into [frames]. Out-of-range values resolve to no frame rather than throwing. */
    val selected: Int = 0,
    val playing: Boolean = false,
    val place: Place? = null,
    val loading: Boolean = true,
) {
    val frame: MapFrame? get() = frames.getOrNull(selected)

    /** The radar could not be reached, and the map is basemap and marker only. */
    val radarUnavailable: Boolean get() = !loading && frames.none { it is MapFrame.Observed }

    /** Whether the timeline reaches into the future at all; it does not when the nowcast fails. */
    val hasForecast: Boolean get() = frames.any { it is MapFrame.Forecast }

    /** Where the past ends: the newest frame a radar actually saw, or -1 when there is none. */
    val nowIndex: Int get() = frames.indexOfLast { it is MapFrame.Observed }

    /** True when the frame on screen is a forecast rather than an observation. */
    val showingForecast: Boolean get() = frame is MapFrame.Forecast

    companion object {
        /**
         * Merges the radar's past with the nowcast's future into one timeline.
         *
         * Forecast steps at or before the newest radar image are dropped rather than shown: INCA is
         * issued on the quarter hour and reaches back to cover the gap to its own reference time, so
         * its first step or two often describe minutes a radar has already watched. Where both
         * exist the radar is the better witness, and a timeline that ran backwards through them
         * would be nonsense.
         */
        fun timeline(radar: List<RadarFrame>, forecast: List<NowcastStep>): List<MapFrame> {
            val observed = radar.sortedBy { it.time }.map(MapFrame::Observed)
            val lastSeen = observed.lastOrNull()?.time
            val ahead = forecast
                .filter { step -> lastSeen == null || step.time.isAfter(lastSeen) }
                .sortedBy { it.time }
                .map(MapFrame::Forecast)
            return observed + ahead
        }
    }
}
