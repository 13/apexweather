package it.apexweather.domain

import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.ConsensusDay
import it.apexweather.domain.model.ConsensusForecast
import it.apexweather.domain.model.ConsensusHour
import it.apexweather.data.remote.EnsembleSpread
import it.apexweather.domain.model.ConsensusMinute
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceForecast
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

class ConsensusBlender(private val zone: ZoneId = ZoneId.of("Europe/Rome")) {

    /**
     * [bias] is how warm each model has lately run at the nearest station, from [BiasCorrector]. It
     * is subtracted from that model's temperatures before they are compared with anyone else's, and
     * fades with lead time — a model's habit today says a lot about this afternoon and little about
     * Thursday. An empty map is the normal state until enough hours have accumulated.
     */
    fun blend(
        forecasts: Map<Source, SourceForecast>,
        bias: Map<Source, Double> = emptyMap(),
        now: Instant? = null,
        ensemble: EnsembleSpread? = null,
    ): ConsensusForecast {
        if (forecasts.isEmpty()) return ConsensusForecast.EMPTY

        // time → (source → point), times truncated to the hour
        val byTime = sortedMapOf<Instant, MutableMap<Source, HourlyPoint>>()
        forecasts.values.forEach { f ->
            f.hourly.forEach { p ->
                val t = p.time.truncatedTo(ChronoUnit.HOURS)
                byTime.getOrPut(t) { mutableMapOf() }[f.source] = p
            }
        }

        val from = now ?: byTime.firstKey()
        val hourly = byTime.mapNotNull { (time, bySource) ->
            val regional = bySource.filterKeys { it.regional }
            val contributing = if (regional.size >= 2) regional else bySource
            if (contributing.isEmpty()) return@mapNotNull null
            val lead = Duration.between(from, time).toHours().coerceAtLeast(0)
            val corrected = contributing.mapValues { (source, point) ->
                val correction = BiasCorrector.correctionAt(bias[source], lead)
                if (correction == 0.0) point else point.copy(
                    tempC = point.tempC - correction,
                    feelsLikeC = point.feelsLikeC?.minus(correction),
                )
            }
            blendHour(time, corrected, ensemble?.halfWidthAt(time))
        }

        val sunTimes = forecasts.values.flatMap { it.daily }
            .filter { it.sunrise != null }
            .associate { it.date to (it.sunrise to it.sunset) }

        val daily = DailyAggregator.aggregate(
            hourly.map { h ->
                HourlyPoint(
                    time = h.time, tempC = h.tempC, precipMm = h.precipMm, precipProb = h.precipProb,
                    windKmh = h.windKmh, gustKmh = h.gustKmh, freezingLevelM = h.freezingLevelM,
                    condition = h.condition,
                )
            },
            zone, sunTimes,
        ).map { d ->
            val hoursOfDay = hourly.filter { it.time.atZone(zone).toLocalDate() == d.date }
            val agreements = hoursOfDay.map { it.agreement }
            ConsensusDay(
                date = d.date, minC = d.minC, maxC = d.maxC, precipMm = d.precipMm,
                condition = d.condition,
                // Days are built from these hours, so the list is never empty; guarded anyway because
                // an empty average is NaN and NaN would reach the screen as a blank badge.
                agreement = if (agreements.isEmpty()) 0.5f else agreements.average().toFloat(),
                sourceCount = hoursOfDay.maxOfOrNull { it.sourceCount } ?: 0,
                freezingLevelMinM = hoursOfDay.mapNotNull { it.freezingLevelM }.minOrNull(),
                sunrise = d.sunrise, sunset = d.sunset,
            )
        }
        return ConsensusForecast(hourly, daily, blendMinutely(forecasts))
    }

    /**
     * The quarter-hourly series, median across whichever models published one. Only the regional
     * models do, so this is a smaller consensus than the hourly one — and an honest one, rather than
     * a wide one padded with globals interpolating their own hourly values.
     */
    private fun blendMinutely(forecasts: Map<Source, SourceForecast>): List<ConsensusMinute> {
        val byTime = sortedMapOf<Instant, MutableList<Double>>()
        forecasts.values.forEach { f ->
            f.minutely.forEach { p -> byTime.getOrPut(p.time) { mutableListOf() }.add(p.precipMm) }
        }
        return byTime.map { (time, values) -> ConsensusMinute(time, median(values), values.size) }
    }

    private fun blendHour(time: Instant, points: Map<Source, HourlyPoint>, ensembleHalfWidth: Double?): ConsensusHour {
        val values = points.values
        val temps = values.map { it.tempC }
        val tMin = temps.min()
        val tMax = temps.max()
        // The ensemble measures uncertainty; the spread between models only stands in for it. Where
        // the ensemble reaches this hour it is the better number, and it is doubled to compare like
        // with like — one is a half-width, the other a full range.
        val spread = ensembleHalfWidth?.times(2) ?: (tMax - tMin)
        val agreement = when {
            ensembleHalfWidth == null && values.size == 1 -> 0.5f
            else -> (1.0 - (spread / 6.0).coerceIn(0.0, 1.0)).toFloat()
        }

        val probs = values.mapNotNull { it.precipProb }
        val precipProb = if (probs.isNotEmpty()) probs.max()
        else (100.0 * values.count { it.precipMm > 0.1 } / values.size).roundToInt()

        val feels = values.mapNotNull { it.feelsLikeC }
        val gusts = values.mapNotNull { it.gustKmh }
        val winds = values.mapNotNull { it.windKmh }
        val freezing = values.mapNotNull { it.freezingLevelM }

        return ConsensusHour(
            time = time,
            tempC = median(temps),
            tempMinC = tMin,
            tempMaxC = tMax,
            feelsLikeC = feels.takeIf { it.isNotEmpty() }?.let(::median),
            precipMm = median(values.map { it.precipMm }),
            precipProb = precipProb,
            windKmh = winds.takeIf { it.isNotEmpty() }?.let(::median),
            // Deliberately the maximum rather than the median every other quantity uses: a gust is a
            // peak, and one nobody was warned about is worse than one that did not arrive. Precipitation
            // probability is a maximum for the same reason. Both are decisions about what "consensus"
            // means here, not oversights; change them only on purpose.
            gustKmh = gusts.maxOrNull(),
            freezingLevelM = freezing.takeIf { it.isNotEmpty() }?.let(::median),
            ensembleHalfWidthC = ensembleHalfWidth,
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

        /**
         * How many of the models have to put water in the sky before the hour is called wet.
         *
         * A third, because a plurality vote over categories loses precipitation. Dry weather has
         * three labels to split between — clear, mostly clear, cloudy — and rain has six, so a
         * genuinely rainy hour can be outvoted by models that merely disagree about how grey it is.
         * Seen on 2026-09-10: four models said cloudy, one partly cloudy, two drizzle and one rain,
         * and the app reported "Bedeckt" while it was raining, two lines above its own "Niederschlag
         * ab 13:15".
         *
         * The threshold is a judgement, and it is deliberately asymmetric in the same direction as
         * the gust and the precipitation probability: being told it is overcast while getting wet is
         * a worse error than being told it drizzles under a grey sky.
         */
        private const val WET_SHARE_DENOMINATOR = 3

        /**
         * Majority vote; ties resolved toward the more severe condition.
         *
         * With one exception: precipitation is voted on among the models that forecast it, once
         * enough of them do. See [WET_SHARE_DENOMINATOR].
         */
        fun voteCondition(conditions: List<Condition>): Condition {
            if (conditions.isEmpty()) return Condition.CLOUDY
            val wet = conditions.filter { it.isPrecipitation }
            // The mildest wet answer the models actually gave, not the worst: a third of them
            // saying so is reason to call it drizzle, not reason to promise heavy rain.
            val pool = if (wet.isNotEmpty() && wet.size * WET_SHARE_DENOMINATOR >= conditions.size) wet else conditions
            return plurality(pool)
        }

        private fun plurality(conditions: List<Condition>): Condition =
            conditions.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<Condition, Int>> { it.value }.thenByDescending { it.key })
                .first().key
    }
}
