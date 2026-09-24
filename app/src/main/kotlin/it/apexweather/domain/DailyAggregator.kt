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

    /**
     * The hours of a day the reader is actually out in, which is the window [worstCondition] has
     * always voted the day's icon in.
     *
     * It is shared rather than repeated because the day's icon and the day's chance of rain have to
     * be describing the same day: a row whose cloud says one thing about the afternoon and whose
     * percentage says another about four in the morning is two answers to one question. Where a day
     * has no hours inside the window at all — the far end of the list, or a day the models only
     * reach the tail of — every hour it does have stands in, on the same terms as below.
     */
    fun <T> daylight(points: List<T>, zone: ZoneId, time: (T) -> Instant): List<T> {
        val inWindow = points.filter { time(it).atZone(zone).hour in DAY_START_HOUR until DAY_END_HOUR }
        return inWindow.ifEmpty { points }
    }

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
                    // Null rather than zero where no hour of the day carried a figure: a day nobody
                    // published snowfall for has not been forecast to be bare, it has not been
                    // asked. See HourlyPoint.snowCm.
                    snowCm = points.mapNotNull { it.snowCm }.takeIf { it.isNotEmpty() }?.sum(),
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

    /**
     * The day's condition from the hours between 06:00 and 22:00 local; if no hours fall in that
     * window, from all of them.
     *
     * Fog or anything falling is decisive: the worst such hour is the day, because a shower at three
     * is the fact a reader opens the row for. A dry day is its **median** sky, not its cloudiest
     * hour. It used to be the maximum throughout, and on 2026-09-24 that put "Bedeckt" on Saturday
     * the 26th in Dorf Tirol — sun from 06 to 18 in every model, cloud from 19 to 21, after a 19:05
     * sunset. Three evening hours of sixteen outvoted the other thirteen.
     */
    fun worstCondition(hourAndCondition: List<Pair<Int, Condition>>): Condition {
        val daytime = hourAndCondition.filter { it.first in DAY_START_HOUR until DAY_END_HOUR }
        val pool = (if (daytime.isNotEmpty()) daytime else hourAndCondition).map { it.second }
        val worst = pool.max()
        if (worst > Condition.CLOUDY) return worst
        return pool.sorted()[pool.size / 2]
    }
}
