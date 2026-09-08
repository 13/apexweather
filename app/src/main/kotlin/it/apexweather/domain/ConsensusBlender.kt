package it.apexweather.domain

import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.ConsensusDay
import it.apexweather.domain.model.ConsensusForecast
import it.apexweather.domain.model.ConsensusHour
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceForecast
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

class ConsensusBlender(private val zone: ZoneId = ZoneId.of("Europe/Rome")) {

    fun blend(forecasts: Map<Source, SourceForecast>): ConsensusForecast {
        if (forecasts.isEmpty()) return ConsensusForecast.EMPTY

        // time → (source → point), times truncated to the hour
        val byTime = sortedMapOf<Instant, MutableMap<Source, HourlyPoint>>()
        forecasts.values.forEach { f ->
            f.hourly.forEach { p ->
                val t = p.time.truncatedTo(ChronoUnit.HOURS)
                byTime.getOrPut(t) { mutableMapOf() }[f.source] = p
            }
        }

        val hourly = byTime.mapNotNull { (time, bySource) ->
            val regional = bySource.filterKeys { it.regional }
            val contributing = if (regional.size >= 2) regional else bySource
            if (contributing.isEmpty()) return@mapNotNull null
            blendHour(time, contributing)
        }

        val sunTimes = forecasts.values.flatMap { it.daily }
            .filter { it.sunrise != null }
            .associate { it.date to (it.sunrise to it.sunset) }

        val daily = DailyAggregator.aggregate(
            hourly.map { h ->
                HourlyPoint(
                    time = h.time, tempC = h.tempC, precipMm = h.precipMm, precipProb = h.precipProb,
                    windKmh = h.windKmh, gustKmh = h.gustKmh, condition = h.condition,
                )
            },
            zone, sunTimes,
        ).map { d ->
            val agreements = hourly.filter { it.time.atZone(zone).toLocalDate() == d.date }.map { it.agreement }
            ConsensusDay(
                date = d.date, minC = d.minC, maxC = d.maxC, precipMm = d.precipMm,
                condition = d.condition, agreement = agreements.average().toFloat(),
                sunrise = d.sunrise, sunset = d.sunset,
            )
        }
        return ConsensusForecast(hourly, daily)
    }

    private fun blendHour(time: Instant, points: Map<Source, HourlyPoint>): ConsensusHour {
        val values = points.values
        val temps = values.map { it.tempC }
        val tMin = temps.min()
        val tMax = temps.max()
        val spread = tMax - tMin
        val agreement = if (values.size == 1) 0.5f else (1.0 - (spread / 6.0).coerceIn(0.0, 1.0)).toFloat()

        val probs = values.mapNotNull { it.precipProb }
        val precipProb = if (probs.isNotEmpty()) probs.max()
        else (100.0 * values.count { it.precipMm > 0.1 } / values.size).roundToInt()

        val feels = values.mapNotNull { it.feelsLikeC }
        val gusts = values.mapNotNull { it.gustKmh }

        return ConsensusHour(
            time = time,
            tempC = median(temps),
            tempMinC = tMin,
            tempMaxC = tMax,
            feelsLikeC = feels.takeIf { it.isNotEmpty() }?.let(::median),
            precipMm = median(values.map { it.precipMm }),
            precipProb = precipProb,
            windKmh = median(values.map { it.windKmh }),
            gustKmh = gusts.maxOrNull(),
            condition = voteCondition(values.map { it.condition }),
            agreement = agreement,
            sourceCount = values.size,
            perSource = points,
        )
    }

    companion object {
        fun median(xs: List<Double>): Double {
            val s = xs.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
        }

        /** Majority vote; ties resolved toward the more severe condition. */
        fun voteCondition(conditions: List<Condition>): Condition =
            conditions.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<Condition, Int>> { it.value }.thenByDescending { it.key })
                .first().key
    }
}
