package it.apexweather.domain

import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.DailyPoint
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceForecast
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Aggregates hourly points into local calendar days. Shared by source mappers and the blender. */
object DailyAggregator {

    private const val DAY_START_HOUR = 6
    private const val DAY_END_HOUR = 22 // exclusive

    fun aggregate(
        hourly: List<HourlyPoint>,
        zone: ZoneId,
        sunTimes: Map<LocalDate, Pair<Instant?, Instant?>> = emptyMap(),
    ): List<DailyPoint> {
        if (hourly.isEmpty()) return emptyList()
        return hourly.groupBy { it.time.atZone(zone).toLocalDate() }
            .toSortedMap()
            .map { (date, points) ->
                DailyPoint(
                    date = date,
                    minC = points.minOf { it.tempC },
                    maxC = points.maxOf { it.tempC },
                    precipMm = points.sumOf { it.precipMm },
                    condition = worstCondition(points.map { it.time.atZone(zone).hour to it.condition }),
                    sunrise = sunTimes[date]?.first,
                    sunset = sunTimes[date]?.second,
                )
            }
    }

    /**
     * Each source's own view of every day, keyed by date. Sources that publish a daily block are
     * taken at their word; the rest are aggregated from their hourly points. Used wherever the UI
     * shows how far the models disagree about a whole day.
     */
    fun perSource(forecasts: Map<Source, SourceForecast>, zone: ZoneId): Map<Source, Map<LocalDate, DailyPoint>> =
        forecasts.mapValues { (_, fc) ->
            (fc.daily.takeIf { it.isNotEmpty() } ?: aggregate(fc.hourly, zone)).associateBy { it.date }
        }

    /** Worst condition between 06:00 and 22:00 local; if no hours in that window, worst of all. */
    fun worstCondition(hourAndCondition: List<Pair<Int, Condition>>): Condition {
        val daytime = hourAndCondition.filter { it.first in DAY_START_HOUR until DAY_END_HOUR }
        val pool = if (daytime.isNotEmpty()) daytime else hourAndCondition
        return pool.maxOf { it.second }
    }
}
