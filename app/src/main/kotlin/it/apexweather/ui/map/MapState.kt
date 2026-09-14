package it.apexweather.ui.map

import it.apexweather.data.remote.NowcastStep
import it.apexweather.data.remote.RadarFrame
import it.apexweather.domain.Place
import it.apexweather.domain.RadarReading
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

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

/** What the radar saw at the place, frame by frame. A frame whose tile failed is absent. */
data class PlaceCheck(val lat: Double, val lon: Double, val readings: Map<Instant, RadarReading>)

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
    /** Null until the place and its readings are known; nothing is marked without it. */
    val check: PlaceCheck? = null,
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

    /** The frame on screen is a forecast whose rain near the place the radar does not see. */
    val unconfirmedHere: Boolean
        get() = (frame as? MapFrame.Forecast)?.step?.cells?.any { it.unconfirmed } == true

    /** The oldest frame of the unbroken run of rainless readings up to the newest one. */
    val radarDrySince: Instant?
        get() {
            val c = check ?: return null
            return frames.filterIsInstance<MapFrame.Observed>()
                .takeLastWhile { c.readings[it.time]?.isRain == false }
                .firstOrNull()?.time
        }

    /**
     * Where the reader should be standing once [next] replaces the timeline.
     *
     * The list is rebuilt every ten minutes and on every return to the tab, and an index into the
     * old one means nothing in the new — a frame can appear at the front or fall off the back, and
     * the same number is then a different minute. So the position is carried across by **time**:
     * whatever the reader was looking at, they end up on the frame nearest to it.
     *
     * Only a first load starts somewhere of its own: the newest frame a radar actually saw — the
     * present, with the past behind it and the forecast ahead. Opening on the last forecast step
     * would lead with the least certain thing on the timeline.
     */
    fun selectionAfter(next: List<MapFrame>): Int {
        if (next.isEmpty()) return 0
        val was = frame?.time ?: return next.indexOfLast { it is MapFrame.Observed }
            .takeIf { it >= 0 } ?: next.lastIndex
        return next.indices.minBy { i -> abs(next[i].time.epochSecond - was.epochSecond) }
    }

    companion object {
        /** How long after the newest radar frame its word about the place still counts. */
        val CHECK_WINDOW: Duration = Duration.ofMinutes(60)

        /** How far around the place the radar's word reaches: where the pixels were read. */
        const val CHECK_RADIUS_KM = 5.0

        /**
         * Merges the radar's past with the nowcast's future into one timeline.
         *
         * Forecast steps at or before the newest radar image are dropped rather than shown: INCA is
         * issued on the quarter hour and reaches back to cover the gap to its own reference time, so
         * its first step or two often describe minutes a radar has already watched. Where both
         * exist the radar is the better witness, and a timeline that ran backwards through them
         * would be nonsense.
         *
         * **And the radar may overrule the forecast's first hour near the place.** On 2026-09-14 the
         * newest frame was dry over Dorf Tirol while INCA's run, built while an echo still counted,
         * put 0,96 mm/h there fifteen minutes later — and no gauge in the valley caught a drop. Where
         * [check] has a rainless reading for the newest frame, forecast cells within
         * [CHECK_RADIUS_KM] and [CHECK_WINDOW] are marked `unconfirmed`. Past the window INCA may be
         * right about rain arriving from outside the patch, so nothing is marked there; and with no
         * reading for the newest frame nothing is marked at all.
         */
        fun timeline(radar: List<RadarFrame>, forecast: List<NowcastStep>, check: PlaceCheck? = null): List<MapFrame> {
            val observed = radar.sortedBy { it.time }.map(MapFrame::Observed)
            val lastSeen = observed.lastOrNull()?.time
            val dryAtPlace = lastSeen != null && check?.readings?.get(lastSeen)?.isRain == false
            val ahead = forecast
                .filter { step -> lastSeen == null || step.time.isAfter(lastSeen) }
                .sortedBy { it.time }
                .map { step ->
                    if (!dryAtPlace || Duration.between(lastSeen, step.time) > CHECK_WINDOW) step
                    else step.copy(cells = step.cells.map { cell ->
                        if (distanceKm(cell.lat, cell.lon, check!!.lat, check.lon) <= CHECK_RADIUS_KM) cell.copy(unconfirmed = true) else cell
                    })
                }
                .map(MapFrame::Forecast)
            return observed + ahead
        }

        private fun distanceKm(lat: Double, lon: Double, lat0: Double, lon0: Double): Double {
            val dy = (lat - lat0) * KM_PER_DEGREE
            val dx = (lon - lon0) * KM_PER_DEGREE * cos(Math.toRadians(lat0))
            return sqrt(dx * dx + dy * dy)
        }

        private const val KM_PER_DEGREE = 111.2
    }
}
