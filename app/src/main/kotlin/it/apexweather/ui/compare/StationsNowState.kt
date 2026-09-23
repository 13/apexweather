package it.apexweather.ui.compare

import it.apexweather.data.StationProbe
import it.apexweather.domain.Place
import it.apexweather.domain.model.StationObservation
import java.time.Instant

/**
 * One station's column on the comparison screen: what it is reading this minute.
 *
 * Every value is nullable and **a null is drawn as a dash, never as a zero**. ITIROL16 has no
 * pyranometer and ITIROL26 does, and that difference is what decides whether `StationSun` has
 * anything to work with; printing 0 W/m² for an instrument that does not exist would be inventing
 * a measurement.
 */
data class StationColumn(
    val code: String,
    val name: String,
    val distanceKm: Double,
    val tempC: Double?,
    val humidityPct: Int?,
    val precipTodayMm: Double?,
    val radiationWm2: Double?,
    val readAt: Instant?,
    /** The station this place actually reads. Marked, and named in a heavier weight. */
    val chosen: Boolean = false,
    /** The province's own network, which is where everything an amateur station lacks comes from. */
    val provincial: Boolean = false,
    /**
     * It answered, but had nothing from the last hour — Weather Underground's HTTP 204.
     *
     * Its column is dashes and it says so. A live station produces this: going quiet for an hour is
     * an ordinary hour and not a fault, and nobody is accused of anything.
     */
    val quiet: Boolean = false,
)

/**
 * The card under the day table: every thermometer round the place, side by side.
 *
 * [modelsTempC] is the one anchor, and it is **the models' consensus at the place for this hour,
 * never the hero**. The hero has been through StationDownscale, StationFog, StationSun, StationDry
 * and MeasuredRain; repeating it here would be a second answer about one hour derived somewhere
 * else, with no way for a reader to tell which is right when the two disagree. `ShareCardStateBuilder`
 * follows the same rule for the same reason — it computes no weather.
 */
data class StationsNowUiState(
    val columns: List<StationColumn> = emptyList(),
    val modelsTempC: Double? = null,
) {
    val hasAnything: Boolean get() = columns.isNotEmpty()
}

object StationsNowStateBuilder {
    fun build(
        place: Place,
        probes: List<StationProbe>,
        provincial: StationObservation?,
        modelsTempC: Double?,
    ): StationsNowUiState {
        val chosenCode = place.readingStation?.code
        val amateur = probes
            // A station that could not be reached at all is not a column: an empty column says
            // nothing a reader can act on, and the stations screen is where a failure is explained.
            .filter { it.error == null }
            .sortedBy { it.distanceKm }
            .map { p ->
                StationColumn(
                    code = p.code,
                    name = p.reading?.stationName ?: p.name,
                    distanceKm = p.distanceKm,
                    tempC = p.reading?.tempC,
                    humidityPct = p.reading?.humidityPct,
                    precipTodayMm = p.reading?.precipTodayMm,
                    radiationWm2 = p.reading?.radiationWm2,
                    readAt = p.reading?.time,
                    chosen = p.code == chosenCode,
                    quiet = p.reading == null,
                )
            }

        // The province's own last and under its own mark: it is not one of the neighbours, it is
        // the fallback and the source of every quantity an amateur station does not publish.
        val official = place.station?.let { s ->
            StationColumn(
                code = s.code,
                name = s.name,
                distanceKm = s.distanceKm,
                tempC = provincial?.tempC,
                humidityPct = provincial?.humidityPct,
                precipTodayMm = provincial?.precipTodayMm,
                radiationWm2 = provincial?.radiationWm2,
                readAt = provincial?.time,
                chosen = s.code == chosenCode,
                provincial = true,
                quiet = provincial == null,
            )
        }

        return StationsNowUiState(
            columns = amateur + listOfNotNull(official),
            modelsTempC = modelsTempC,
        )
    }
}
