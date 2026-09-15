package it.apexweather.domain

import it.apexweather.domain.model.Source
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** What one model said about one hour at the station, at one lead. A quantity it did not give is null. */
data class Predicted(val tempC: Double?, val rainMm: Double?, val windKmh: Double?)

/** One `station_history` row, decoded: names turned into enums, nothing derived yet. */
data class StationHistoryRow(
    val time: Instant,
    val observedC: Double?,
    val observedWindKmh: Double?,
    /** Rain since local midnight at the reading, as the station reports it. */
    val precipTodayMm: Double?,
    val temps: Map<LeadBucket, Map<Source, Double>>,
    val rain: Map<LeadBucket, Map<Source, Double>>,
    val wind: Map<LeadBucket, Map<Source, Double>>,
)

/** One hour ready to be scored: what the station measured and what each model said, per lead. */
data class VerificationHour(
    val time: Instant,
    val observedC: Double?,
    val observedWindKmh: Double?,
    /** Rain in this hour, derived from two daily totals; null where that cannot be done honestly. */
    val observedRainMm: Double?,
    val predicted: Map<LeadBucket, Map<Source, Predicted>>,
)

object VerificationHistory {
    /**
     * How long `station_history` is kept for the statistics screen. [BiasCorrector.WINDOW] stays seven
     * days; it filters its own window and is not affected by keeping more.
     */
    val KEEP: Duration = Duration.ofDays(90)

    fun hours(rows: List<StationHistoryRow>, zone: ZoneId): List<VerificationHour> {
        val sorted = rows.sortedBy { it.time }
        val byTime = sorted.associateBy { it.time }
        return sorted.map { row ->
            VerificationHour(
                time = row.time,
                observedC = row.observedC,
                observedWindKmh = row.observedWindKmh,
                observedRainMm = hourlyRain(row, byTime[row.time.minusSeconds(3600)], zone),
                predicted = merge(row),
            )
        }
    }

    /**
     * The station publishes rain since local midnight, so one hour's rain is this reading's total
     * minus the previous hour's. Nothing assumes *when* the station resets:
     *
     * - A total at or above the previous one is a difference, across a local midnight too — a
     *   00:xx reading that is still climbing has simply not reset yet.
     * - A total below the previous one is a reset. In the first hour of a local day that hour's rain
     *   is its own total; on any other hour the reset is unexpected and the hour is not scored.
     * - Without the previous hour nothing is scored, the first hour of a day included: a reset
     *   cannot be proven there.
     */
    fun hourlyRain(row: StationHistoryRow, previous: StationHistoryRow?, zone: ZoneId): Double? {
        val total = row.precipTodayMm ?: return null
        val before = previous?.precipTodayMm ?: return null
        // Rounded to the station's own precision: an unrounded 0.3 - 0.2 is 0.09999999999999998, which
        // reads as dry against ForecastScores.WET_MM even though the station meant exactly 0.1 mm.
        val diff = hundredths(total - before)
        if (diff >= 0.0) return diff
        val firstHourOfDay = row.time.minusSeconds(3600).atZone(zone).toLocalDate() != row.time.atZone(zone).toLocalDate()
        return if (firstHourOfDay) hundredths(total) else null
    }

    private fun hundredths(v: Double): Double = Math.round(v * 100.0) / 100.0

    private fun merge(row: StationHistoryRow): Map<LeadBucket, Map<Source, Predicted>> =
        (row.temps.keys + row.rain.keys + row.wind.keys).associateWith { lead ->
            val t = row.temps[lead].orEmpty()
            val r = row.rain[lead].orEmpty()
            val w = row.wind[lead].orEmpty()
            (t.keys + r.keys + w.keys).associateWith { source -> Predicted(t[source], r[source], w[source]) }
        }
}
