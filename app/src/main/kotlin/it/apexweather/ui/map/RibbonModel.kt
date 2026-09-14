package it.apexweather.ui.map

import it.apexweather.data.remote.NowcastKind
import it.apexweather.domain.SouthTyrol
import it.apexweather.ui.home.PrecipScale
import java.time.Instant

enum class BarKind { OBSERVED, NOWCAST, OUTLOOK }

/** One step of the ribbon, about the reader's place alone. */
data class RibbonBar(
    val time: Instant,
    val kind: BarKind,
    val mmPerHour: Double,
    val upperMmPerHour: Double?,
    val unconfirmed: Boolean,
)

enum class RainWord { DRY, POSSIBLE, LIGHT, MODERATE, HEAVY }

/**
 * What the ribbon draws, worked out without a screen.
 *
 * A bar is the place's own value at that step: what the radar saw there, or the forecast grid cell
 * nearest to it. The radar's word is only rain from 15 dBZ; the forecast's cell is only "here"
 * within three quarters of its own grid spacing, so rain one cell over is not rain here.
 */
object RibbonModel {

    private const val NOWCAST_REACH_KM = 0.75
    private const val OUTLOOK_REACH_KM = 1.9

    fun bars(state: MapUiState): List<RibbonBar> {
        val lat = state.place?.lat ?: state.check?.lat
        val lon = state.place?.lon ?: state.check?.lon
        return state.visible.map { frame ->
            when (frame) {
                is MapFrame.Observed -> RibbonBar(
                    frame.time, BarKind.OBSERVED,
                    state.check?.readings?.get(frame.time)?.mmPerHour ?: 0.0, null, false,
                )
                is MapFrame.Forecast -> {
                    val outlook = frame.step.kind == NowcastKind.OUTLOOK
                    val reach = if (outlook) OUTLOOK_REACH_KM else NOWCAST_REACH_KM
                    val cell = if (lat == null || lon == null) null else frame.step.cells
                        .map { it to distanceKm(it.lat, it.lon, lat, lon) }
                        .filter { it.second <= reach }
                        .minByOrNull { it.second }?.first
                    RibbonBar(
                        frame.time, if (outlook) BarKind.OUTLOOK else BarKind.NOWCAST,
                        cell?.mmPerHour ?: 0.0, cell?.upperMmPerHour, cell?.unconfirmed ?: false,
                    )
                }
            }
        }
    }

    fun word(bar: RibbonBar): RainWord = when {
        bar.unconfirmed && bar.mmPerHour >= PrecipColors.DRAWN_FROM_MM -> RainWord.POSSIBLE
        bar.mmPerHour >= PrecipColors.HEAVY_FROM_MM -> RainWord.HEAVY
        bar.mmPerHour >= PrecipColors.MODERATE_FROM_MM -> RainWord.MODERATE
        PrecipColors.isRain(bar.mmPerHour) -> RainWord.LIGHT
        bar.mmPerHour >= PrecipColors.DRAWN_FROM_MM -> RainWord.POSSIBLE
        (bar.upperMmPerHour ?: 0.0) >= PrecipColors.RAIN_FROM_MM -> RainWord.POSSIBLE
        else -> RainWord.DRY
    }

    /** The same square-root scale as the hour strip, so a height means the same rain on both screens. */
    fun fraction(mm: Double): Float = PrecipScale.fillFraction(mm)

    /** Bars that carry an hour label: whole local hours, every hour in Jetzt and 00/06/12/18 in Heute. */
    fun labelIndices(bars: List<RibbonBar>, zoom: MapZoom): List<Int> {
        val every = if (zoom == MapZoom.NOW) 1 else 6
        return bars.indices.filter { i ->
            val local = bars[i].time.atZone(SouthTyrol.ZONE)
            local.minute == 0 && local.second == 0 && local.hour % every == 0
        }
    }
}
