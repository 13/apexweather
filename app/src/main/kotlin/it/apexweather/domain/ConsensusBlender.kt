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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class ConsensusBlender(private val zone: ZoneId = ZoneId.of("Europe/Rome")) {

    /**
     * [bias] is how warm each model has lately run at the nearest station, from [BiasCorrector]. It
     * is subtracted from that model's temperatures before they are compared with anyone else's, and
     * fades with lead time — a model's habit today says a lot about this afternoon and little about
     * Thursday. It is read at the part of the day of the hour being blended, not of now: a model
     * that runs warm at four in the afternoon should be corrected for tomorrow afternoon, whatever
     * time the app happens to be looking. [ModelBias.NONE] is the normal state until enough hours
     * have accumulated.
     */
    fun blend(
        forecasts: Map<Source, SourceForecast>,
        bias: ModelBias = ModelBias.NONE,
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
                val correction = BiasCorrector.correctionAt(bias.at(source, time, zone, lead), lead)
                if (correction == 0.0) point else point.copy(
                    tempC = point.tempC - correction,
                    feelsLikeC = point.feelsLikeC?.minus(correction),
                )
            }
            blendHour(time, corrected, ensemble?.halfWidthAt(time), lead)
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
                // Where an ensemble reaches the day at all, its own spread is what the day's
                // agreement was built from, and the badge needs to know that before it calls a day
                // uncomparable for having a single model in it.
                ensembleHalfWidthC = hoursOfDay.mapNotNull { it.ensembleHalfWidthC }
                    .takeIf { it.isNotEmpty() }?.average(),
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

    /**
     * The spread at which the forecast is said to be telling you nothing, in degrees.
     *
     * It was a flat 6 K, which was right while everything the badge measured was inside two days.
     * It is wrong once the badge is fed a fortnight: ECMWF's ensemble opens from under 2 K on the
     * first days to 8 or 10 K by day fourteen, so against a constant 6 K every day past the ninth
     * pins at 0 % — a red dot that says the same thing about a settled week as about an unsettled
     * one.
     *
     * Spread that grows with lead time is not the forecast failing, it is what a forecast a
     * fortnight out *is*. So the scale opens with it and the badge goes on meaning "unusually
     * uncertain for this far ahead" rather than "far ahead". The near end is untouched: at lead
     * zero this is still exactly 6 K.
     *
     * The growth was measured, not guessed: the full ensemble spread across eight places spanning
     * the province — Bozen at 262 m to Corvara at 1568 m, one run on 2026-09-11 — fits
     * 0,84 K + 0,62 K per lead day, with per-place slopes from 0,54 to 0,78. [
     * FULL_DISAGREEMENT_GROWTH_C_PER_DAY] is 0,6. That is eight places in one weather regime, not
     * eight regimes, so it is the shape that is measured rather than the exact number.
     */
    private fun fullDisagreementAt(leadHours: Long): Double =
        FULL_DISAGREEMENT_C + FULL_DISAGREEMENT_GROWTH_C_PER_DAY * (leadHours / 24.0)

    private fun blendHour(
        time: Instant,
        points: Map<Source, HourlyPoint>,
        ensembleHalfWidth: Double?,
        leadHours: Long,
    ): ConsensusHour {
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
            else -> (1.0 - (spread / fullDisagreementAt(leadHours)).coerceIn(0.0, 1.0)).toFloat()
        }

        // The **mean**, where every other quantity here takes the median, and the one place that
        // difference is not an oversight. Precipitation is zero-inflated: the moment half the models
        // say dry the median is 0.0 and every wet model is discarded, however much rain they
        // forecast. That is what put a rain cloud over a blank amount at 19:00 on 2026-09-10 —
        // three of six models wet, at 0,1, 0,2 and 0,5 mm, and a median of 0,05. The mean of those
        // six is 0,13 mm, which is what the hour actually amounts to.
        val precip = values.map { it.precipMm }.average()

        // The **mean**, for the same reason the amount above is: a probability is the other
        // zero-inflated quantity here, and averaging is what the number actually means. Asked
        // "will it rain at four", each model answers with its own chance, and the chance across a
        // set of equally good models is the average of theirs — three models at 60 % and five at
        // 0 % is a 23 % hour.
        //
        // It used to be the maximum, which let the single most alarmist of ten models set the
        // figure on the screen on its own: the same eight-model hour read 60 %. The median is no
        // better here than it is for the amount, and for the same reason — five dry models put it
        // at zero and the three that see the shower are discarded.
        //
        // The fallback, for an hour no model publishes a probability for, is already this same
        // average, of ones and zeroes.
        val probs = values.mapNotNull { it.precipProb }
        val precipProb = if (probs.isNotEmpty()) probs.average().roundToInt()
        else (100.0 * values.count { it.precipMm > 0.1 } / values.size).roundToInt()

        val feels = values.mapNotNull { it.feelsLikeC }
        val gusts = values.mapNotNull { it.gustKmh }
        val winds = values.mapNotNull { it.windKmh }
        val freezing = values.mapNotNull { it.freezingLevelM }
        val humidity = values.mapNotNull { it.humidityPct }

        return ConsensusHour(
            time = time,
            tempC = median(temps),
            tempMinC = tMin,
            tempMaxC = tMax,
            feelsLikeC = feels.takeIf { it.isNotEmpty() }?.let(::median),
            precipMm = precip,
            precipProb = precipProb,
            windKmh = winds.takeIf { it.isNotEmpty() }?.let(::median),
            // Deliberately the maximum rather than the median every other quantity uses: a gust is a
            // peak, and one nobody was warned about is worse than one that did not arrive. This is
            // now the only maximum left here — the precipitation probability was one too, and is
            // the mean for the reason given above it. A decision about what "consensus" means,
            // not an oversight; change it only on purpose.
            gustKmh = gusts.maxOrNull(),
            windDirDeg = meanDirectionDeg(values.mapNotNull { it.windDirDeg }),
            humidityPct = humidity.takeIf { it.isNotEmpty() }?.let { median(it.map(Int::toDouble)).roundToInt() },
            freezingLevelM = freezing.takeIf { it.isNotEmpty() }?.let(::median),
            ensembleHalfWidthC = ensembleHalfWidth,
            condition = voteCondition(values.map { it.condition }, precip, cloudPct = values.mapNotNull { it.cloudPct }),
            agreement = agreement,
            sourceCount = values.size,
            perSource = points,
        )
    }

    companion object {
        /** The spread at which a forecast for the current hour is telling you nothing. */
        const val FULL_DISAGREEMENT_C = 6.0

        /** How much of that a day of lead time is worth; see `fullDisagreementAt`. */
        const val FULL_DISAGREEMENT_GROWTH_C_PER_DAY = 0.6

        /**
         * How much the models have to agree on a direction before one is worth printing.
         *
         * The resultant length of the unit vectors: 1 where every model points the same way, 0
         * where they cancel exactly. A half is roughly a spread of a right angle either side of the
         * mean — enough to say "from the north-west", not enough to be the mean of models pointing
         * up and down the valley at once. Unlike every other quantity here, the mean of two opposite
         * answers is not somewhere between them; it is nowhere, and the honest output is nothing.
         */
        private const val MIN_DIRECTION_AGREEMENT = 0.5

        /**
         * The direction the models agree the wind comes from, or null where they do not.
         *
         * An angle has no median and cannot be averaged arithmetically — 350° and 10° average to
         * 180°, the exact opposite of the answer. Each model's bearing becomes a unit vector, the
         * vectors are summed, and the direction of the sum is the answer; the *length* of the sum
         * is how much they agreed, which is what [MIN_DIRECTION_AGREEMENT] is read against.
         */
        fun meanDirectionDeg(degrees: List<Int>): Int? {
            if (degrees.isEmpty()) return null
            val radians = degrees.map { Math.toRadians(it.toDouble()) }
            val x = radians.sumOf(::cos) / radians.size
            val y = radians.sumOf(::sin) / radians.size
            if (sqrt(x * x + y * y) < MIN_DIRECTION_AGREEMENT) return null
            return (((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).roundToInt()) % 360
        }

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
         * Below this an hour has no precipitation to speak of. The same tenth of a millimetre the
         * upstream mappers use to decide whether anything is falling at all, and the same one the
         * strip uses to decide whether to print an amount.
         */
        private const val WET_MIN_MM = 0.1

        /**
         * Majority vote; ties resolved toward the more severe condition.
         *
         * Two exceptions. Precipitation is voted on among the models that forecast it, once enough
         * of them do — see [WET_SHARE_DENOMINATOR]. And an hour that amounts to less than
         * [WET_MIN_MM] is not called wet whatever the labels say, because the icon would then be
         * promising rain over a blank amount: on 2026-09-10 the strip drew a rain cloud above an
         * empty bar and no millimetres at all, and the hour sheet read "19:00 · Regen · 0,0 mm".
         * The reader is told the chance separately, and that is where an unlikely shower belongs.
         */
        /**
         * Fog is not precipitation, so the wet-pool rule below discarded it outright: once a third
         * of the models forecast drizzle, a model saying Talnebel had no way to be heard. In a
         * valley where fog and drizzle are the same grey afternoon that is a real loss, and it is
         * why the app never once showed fog.
         *
         * So fog is decided first, and it wins on the same third the wet pool uses — but only while
         * the hour is light. Being told it is foggy while a downpour is arriving would be the same
         * mistake in the other direction, so above [FOG_LOSES_ABOVE_MM] the rain leads.
         */
        private const val FOG_LOSES_ABOVE_MM = 0.5

        /**
         * How cloudy the sky is, from the number the models publish rather than the word.
         *
         * The boundaries are the ones the WMO codes themselves imply — a model calling an hour
         * "mainly clear" is saying something under half covered — so the app's four labels keep
         * meaning what the models mean by them.
         */
        fun fromCloudCover(pct: Double): Condition = when {
            pct < MOSTLY_CLEAR_ABOVE_PCT -> Condition.CLEAR
            pct < PARTLY_ABOVE_PCT -> Condition.MOSTLY_CLEAR
            pct < OVERCAST_ABOVE_PCT -> Condition.PARTLY_CLOUDY
            else -> Condition.CLOUDY
        }

        fun voteCondition(
            conditions: List<Condition>,
            precipMm: Double,
            stationSaturated: Boolean = false,
            cloudPct: List<Int> = emptyList(),
        ): Condition {
            if (conditions.isEmpty()) return Condition.CLOUDY
            val wet = conditions.filter { it.isPrecipitation }
            val dry = conditions.filterNot { it.isPrecipitation }
            val fog = conditions.count { it == Condition.FOG }
            // A saturated station is ground truth against a forecast, so it lowers the bar to a
            // single source having seen the fog. It never invents fog on its own: something has to
            // have forecast it first. See StationFog.
            val fogWins = fog > 0 && precipMm <= FOG_LOSES_ABOVE_MM &&
                (stationSaturated || fog * WET_SHARE_DENOMINATOR >= conditions.size)
            if (fogWins) return Condition.FOG
            if (precipMm < WET_MIN_MM && dry.isNotEmpty()) {
                // The **median of the cloud the models publish**, not a plurality over the words
                // they put on it. Four ordered labels voted on as if they were four unrelated
                // categories throws away how cloudy each model actually said, and the tie-break
                // then hands the hour to the cloudier one — which is the same failure the comment
                // on WET_SHARE_DENOMINATOR describes between wet and dry, one level down.
                //
                // Measured against all 49 pyranometers in the province on the afternoon of
                // 2026-09-11: the label plurality ran +1,12 steps too cloudy, the median of the
                // cloud +0,59, and of the 37 places the instruments called clear or nearly so the
                // plurality called twenty of them overcast where the median called six. One
                // afternoon and one weather regime, but the mechanism does not depend on either.
                if (cloudPct.size >= MIN_CLOUD_SOURCES) return fromCloudCover(median(cloudPct.map { it.toDouble() }))
                return plurality(dry)
            }
            // The mildest wet answer the models actually gave, not the worst: a third of them
            // saying so is reason to call it drizzle, not reason to promise heavy rain.
            val pool = if (wet.isNotEmpty() && wet.size * WET_SHARE_DENOMINATOR >= conditions.size) wet else conditions
            return plurality(pool)
        }

        /**
         * Fewer than this and the median is one model's opinion wearing a statistic's clothes, so
         * the labels have their vote back. Nine of the ten sources publish cloud cover; SIAG KMOS
         * is the one that does not.
         */
        const val MIN_CLOUD_SOURCES = 3

        /** The WMO codes' own boundaries: clear, mainly clear, partly cloudy, overcast. */
        const val MOSTLY_CLEAR_ABOVE_PCT = 12.5
        const val PARTLY_ABOVE_PCT = 50.0
        const val OVERCAST_ABOVE_PCT = 87.5

        private fun plurality(conditions: List<Condition>): Condition =
            conditions.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<Condition, Int>> { it.value }.thenByDescending { it.key })
                .first().key
    }
}
