package it.apexweather.ui.map

import it.apexweather.data.remote.NowcastStep
import it.apexweather.data.remote.RadarFrame
import it.apexweather.domain.Place
import it.apexweather.domain.RadarReading
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

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

/** How much of the day the ribbon spans. */
enum class MapZoom {
    /** Two hours of radar and three of forecast, a quarter hour a step. */
    NOW,

    /** The next day, an hour a step, from AROME's ensemble alone. */
    TODAY,
}

/**
 * What the map tab draws. Pure, so the screen can be driven from a hand-built state in a test
 * without a network, a tile server or a real MapView.
 */
data class MapUiState(
    val frames: List<MapFrame> = emptyList(),
    /** The Heute zoom's frames: AROME hours from the present to a day ahead. */
    val outlook: List<MapFrame> = emptyList(),
    val zoom: MapZoom = MapZoom.NOW,
    /** Index into [visible]. Out-of-range values resolve to no frame rather than throwing. */
    val selected: Int = 0,
    val playing: Boolean = false,
    val place: Place? = null,
    val loading: Boolean = true,
    /** Null until the place and its readings are known; nothing is marked without it. */
    val check: PlaceCheck? = null,
    /** Whether the reader has switched the sky's motion off; [FrameLayers] reads this too. */
    val animations: Boolean = true,
    /** The ribbon's bars for Jetzt, worked out off the main thread (see [withBars]). */
    val nowBars: List<RibbonBar> = emptyList(),
    /** And for Heute; both are held so a zoom switch needs no recomputation. */
    val todayBars: List<RibbonBar> = emptyList(),
) {
    /**
     * The ribbon for the zoom on screen. The screen only reads these: [RibbonModel.bars] measures
     * the distance from the place to every cell of every step, and inside composition that ran on
     * the main thread on every change to the frames or the check.
     */
    val bars: List<RibbonBar> get() = if (zoom == MapZoom.NOW) nowBars else todayBars

    /** This state with both zooms' bars computed from its frames, outlook, check and place. */
    fun withBars(): MapUiState = copy(
        nowBars = RibbonModel.bars(copy(zoom = MapZoom.NOW)),
        todayBars = RibbonModel.bars(copy(zoom = MapZoom.TODAY)),
    )

    /** The frames the current zoom scrubs and plays through. */
    val visible: List<MapFrame> get() = if (zoom == MapZoom.NOW) frames else outlook

    val frame: MapFrame? get() = visible.getOrNull(selected)

    /** The radar could not be reached, and the map is basemap and marker only. */
    val radarUnavailable: Boolean get() = !loading && frames.none { it is MapFrame.Observed }

    /** Whether the timeline reaches into the future at all; it does not when the nowcast fails. */
    val hasForecast: Boolean get() = frames.any { it is MapFrame.Forecast }

    /** Where the past ends in the visible frames; in Heute the present is its first hour. */
    val nowIndex: Int get() = if (zoom == MapZoom.NOW) frames.indexOfLast { it is MapFrame.Observed } else 0

    /** The newest instant a radar saw, which is what every "in 35 min" is measured from. */
    val presentTime: Instant? get() = frames.lastOrNull { it is MapFrame.Observed }?.time ?: visible.firstOrNull()?.time

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
     * Only a first load starts somewhere of its own: the present of the zoom the reader is in. In
     * Jetzt that is the newest frame a radar actually saw, with the past behind it and the forecast
     * ahead — opening on the last forecast step would lead with the least certain thing on the
     * timeline. In Heute it is the first hour: the list has no radar frame in it, and falling back
     * to its end opened the map a day ahead whenever Heute was chosen before the outlook arrived.
     */
    fun selectionAfter(next: List<MapFrame>): Int {
        if (next.isEmpty()) return 0
        val was = frame?.time ?: return if (zoom == MapZoom.TODAY) 0
            else next.indexOfLast { it is MapFrame.Observed }.takeIf { it >= 0 } ?: next.lastIndex
        return next.indices.minBy { i -> abs(next[i].time.epochSecond - was.epochSecond) }
    }

    /** The same state in [zoom], on the same instant if that zoom has it and on the present otherwise. */
    fun withZoom(zoom: MapZoom): MapUiState {
        if (zoom == this.zoom) return this
        val at = frame?.time
        val next = copy(zoom = zoom, playing = false)
        val list = next.visible
        if (list.isEmpty()) return next.copy(selected = 0)
        val inside = at != null && !at.isBefore(list.first().time) && !at.isAfter(list.last().time)
        val index = if (inside) list.indices.minBy { abs(list[it].time.epochSecond - at!!.epochSecond) }
            else next.nowIndex.coerceIn(0, list.lastIndex)
        return next.copy(selected = index)
    }

    companion object {
        /**
         * The radar's word over the forecast near the place: where [check] has a rainless reading
         * for [lastSeen], cells of [steps] within [CHECK_RADIUS_KM] and no later than [CHECK_WINDOW]
         * after it are marked `unconfirmed`. Sixty minutes exactly is still inside.
         *
         * Both zooms go through this. Heute's outlook used to skip it, so Jetzt said "unsicher" for
         * a quarter hour while Heute's bar for the same hour at the same place read as rain.
         */
        fun markUnconfirmed(steps: List<NowcastStep>, lastSeen: Instant?, check: PlaceCheck?): List<NowcastStep> {
            if (lastSeen == null || check == null || check.readings[lastSeen]?.isRain != false) return steps
            return steps.map { step ->
                if (Duration.between(lastSeen, step.time) > CHECK_WINDOW) step
                else step.copy(cells = step.cells.map { cell ->
                    if (distanceKm(cell.lat, cell.lon, check.lat, check.lon) <= CHECK_RADIUS_KM) cell.copy(unconfirmed = true) else cell
                })
            }
        }

        /** How long after the newest radar frame its word about the place still counts. */
        val CHECK_WINDOW: Duration = Duration.ofMinutes(60)

        /** How far around the place the radar's word reaches: where the pixels were read. */
        const val CHECK_RADIUS_KM = 5.0

        /** Jetzt: how far past the newest radar frame the forecast half of the timeline reaches. */
        val NOW_AHEAD: Duration = Duration.ofHours(3)

        /** Heute: how far past the present the outlook reaches. */
        val TODAY_AHEAD: Duration = Duration.ofHours(24)

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
            val ahead = forecast
                .filter { step -> lastSeen == null || step.time.isAfter(lastSeen) }
                .sortedBy { it.time }
                .filter { step ->
                    // Comparable, not Duration.isPositive: that is Java 18 and minSdk is 31.
                    val from = lastSeen ?: forecast.minOf { it.time }
                    Duration.between(from, step.time) <= NOW_AHEAD
                }
            return observed + markUnconfirmed(ahead, lastSeen, check).map(MapFrame::Forecast)
        }
    }
}
