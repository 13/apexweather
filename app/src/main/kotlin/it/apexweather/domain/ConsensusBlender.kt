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

/**
 * The values a model actually published, keyed by the model — dropping the ones that published
 * nothing rather than standing a null in for them.
 *
 * Every weighted statistic in here needs the *sources* alongside the numbers, because the weight
 * comes from which other models are in the same company; collapsing to a bare list of values
 * throws that away. This keeps the two together through the filter.
 */
private inline fun <V> Map<Source, it.apexweather.domain.model.HourlyPoint>.mapNotNullValues(
    select: (it.apexweather.domain.model.HourlyPoint) -> V?,
): Map<Source, V> = mapNotNull { (source, point) -> select(point)?.let { source to it } }.toMap()

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
            blendHour(time, corrected, ensemble?.halfWidthAt(time), ensemble?.wetShareAt(time), lead)
        }

        val sunTimes = forecasts.values.flatMap { it.daily }
            .filter { it.sunrise != null }
            .associate { it.date to (it.sunrise to it.sunset) }

        val daily = DailyAggregator.aggregate(
            hourly.map { h ->
                HourlyPoint(
                    time = h.time, tempC = h.tempC, precipMm = h.precipMm, precipProb = h.precipProb,
                    windKmh = h.windKmh, gustKmh = h.gustKmh, freezingLevelM = h.freezingLevelM,
                    snowCm = h.snowCm, condition = h.condition,
                )
            },
            zone, sunTimes,
        ).map { d ->
            val hoursOfDay = hourly.filter { it.time.atZone(zone).toLocalDate() == d.date }
            val agreements = hoursOfDay.map { it.agreement }
            ConsensusDay(
                date = d.date, minC = d.minC, maxC = d.maxC, precipMm = d.precipMm,
                snowCm = d.snowCm,
                // The likeliest daylight hour, not the average of them — see ConsensusDay.precipProb
                // for why the mean and the independent-hours product are both worse answers to
                // "will it rain on Saturday".
                precipProb = DailyAggregator.daylight(hoursOfDay, zone) { it.time }
                    .maxOfOrNull { it.precipProb } ?: 0,
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
     * The quarter-hourly series, the weighted mean across whichever models published one. Only the
     * regional models do, so this is a smaller consensus than the hourly one — and an honest one,
     * rather than a wide one padded with globals interpolating their own hourly values.
     *
     * The mean, like the hourly amount, and for the same reason: precipitation is zero-inflated, so
     * a median stays at zero until half the models are wet. It was the median, and on 2026-09-16 in
     * Meran that put "Niederschlag ab 19:45" over a strip showing millimetres from 16:00 — one or
     * two of six models had showers from 15:15 and the median threw them away.
     */
    private fun blendMinutely(forecasts: Map<Source, SourceForecast>): List<ConsensusMinute> {
        val byTime = sortedMapOf<Instant, MutableMap<Source, Double>>()
        forecasts.forEach { (source, f) ->
            f.minutely.forEach { p -> byTime.getOrPut(p.time) { mutableMapOf() }[source] = p.precipMm }
        }
        return byTime.map { (time, values) -> ConsensusMinute(time, weightedMean(values), values.size) }
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
        ensembleWetShare: Double?,
        leadHours: Long,
    ): ConsensusHour {
        val values = points.values
        // What each model's word is worth here, given how many of the others share its core. The
        // company is recomputed per quantity below, because "the models that publish wind" is a
        // different set from "the models that publish anything", and a family of four that only two
        // of whom publish humidity is a family of two for the purpose of humidity.
        val temps = points.mapValues { it.value.tempC }
        val tMin = temps.values.min()
        val tMax = temps.values.max()
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
        val precip = weightedMean(points.mapValues { it.value.precipMm })

        // **The ensemble's own wet share where one reaches this hour**, and the models' opinions
        // only where it does not.
        //
        // The two are not the same kind of number and the ensemble's is the better one. A model's
        // `precipitation_probability` is that model's own claim about a chance, and averaging
        // eleven of them averages eleven claims; the wet share is a count of how many of fifty
        // equally plausible atmospheres actually rained. That is what a probability means, and it
        // is the one question an ensemble is built to answer — the app was already paying for the
        // members and reading only their temperatures.
        //
        // The ladder below it is unchanged and still needed: ICON-D2's ensemble runs two days and
        // ECMWF's fifteen, so an hour past the fortnight, or one where the ensembles failed, falls
        // back to the weighted mean of the models' own figures and then to counting the wet ones.
        // That mean is itself a decision worth keeping written down: it was once the *maximum*,
        // which let the single most alarmist of ten models set the figure on its own (an
        // eight-model hour read 60 % where the average of them is 23 %), and the median is no
        // better than it is for the amount, because five dry models put it at zero and the three
        // that see the shower are discarded.
        val probs = points.mapNotNullValues { it.precipProb?.toDouble() }
        val precipProb = when {
            ensembleWetShare != null -> (100.0 * ensembleWetShare).roundToInt()
            probs.isNotEmpty() -> weightedMean(probs).roundToInt()
            else -> (100.0 * weightedShare(points.keys) { points.getValue(it).precipMm > 0.1 }).roundToInt()
        }

        // Snow is the third zero-inflated quantity on this list and takes the mean for the reason
        // the two above it do: the moment half the models say the hour is dry, a median throws away
        // every model that saw the centimetres. It is null rather than zero where nobody publishes
        // it — see HourlyPoint.snowCm — so an hour no model has an answer for prints nothing
        // instead of promising a bare hillside.
        val snow = points.mapNotNullValues { it.snowCm }

        val feels = points.mapNotNullValues { it.feelsLikeC }
        // Paired with each model's own temperature before the median is taken; see
        // ConsensusHour.feelsOffsetC for why this is not `feels` minus `temps`.
        val feelsOffsets = points.mapNotNullValues { p -> p.feelsLikeC?.let { it - p.tempC } }
        val gusts = values.mapNotNull { it.gustKmh }
        val winds = points.mapNotNullValues { it.windKmh }
        val freezing = points.mapNotNullValues { it.freezingLevelM }
        val humidity = points.mapNotNullValues { it.humidityPct?.toDouble() }

        return ConsensusHour(
            time = time,
            tempC = weightedMedian(temps),
            tempMinC = tMin,
            tempMaxC = tMax,
            feelsLikeC = feels.takeIf { it.isNotEmpty() }?.let(::weightedMedian),
            feelsOffsetC = feelsOffsets.takeIf { it.isNotEmpty() }?.let(::weightedMedian),
            precipMm = precip,
            snowCm = snow.takeIf { it.isNotEmpty() }?.let(::weightedMean),
            precipProb = precipProb,
            windKmh = winds.takeIf { it.isNotEmpty() }?.let(::weightedMedian),
            // Deliberately the maximum rather than the median every other quantity uses: a gust is a
            // peak, and one nobody was warned about is worse than one that did not arrive. This is
            // now the only maximum left here — the precipitation probability was one too, and is
            // the mean for the reason given above it. A decision about what "consensus" means,
            // not an oversight; change it only on purpose.
            gustKmh = gusts.maxOrNull(),
            windDirDeg = meanDirectionDeg(values.mapNotNull { it.windDirDeg }),
            humidityPct = humidity.takeIf { it.isNotEmpty() }?.let { weightedMedian(it).roundToInt() },
            freezingLevelM = freezing.takeIf { it.isNotEmpty() }?.let(::weightedMedian),
            ensembleHalfWidthC = ensembleHalfWidth,
            condition = voteCondition(
                points.mapValues { it.value.condition },
                precip,
                cloudPct = points.mapNotNullValues { it.cloudPct },
            ),
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
         * How much one model's word is worth, given the company it keeps: one over the square root
         * of how many of the models being blended share its [ModelFamily].
         *
         * The consensus is a median, and a median counts votes. Four of the regional sources are
         * ICON — CH1, CH2, 2I and D2 — so on any hour where all eight regional models report, ICON
         * casts four of the eight votes and decides every close call on its own. That is not four
         * models agreeing; it is one dynamical core being asked four times. The same objection is
         * what bought KNMI and DMI their place on the list, and until now it had been acted on in
         * what the app fetches and not in what it does with it.
         *
         * **1/sqrt(n), not 1/n, and the difference is the whole judgement here.** Full
         * de-duplication would say the four ICONs are one model, which is false in a way that
         * matters: they run at different resolutions over different domains with different
         * assimilation at four different centres, and ICON-CH1 at 1 km is the best-resolved thing
         * this app has for a valley 2 km wide. Counting them as one would throw that away to fix a
         * double-count. 1/sqrt(n) is the standard treatment of partially correlated members and
         * lands between the two: on the full regional eight, ICON's share goes from 50 % to 37 %,
         * HARMONIE's from 25 % to 26 %, and AROME and KMOS gain. Neither endpoint is defensible and
         * this is deliberately in the middle of them.
         *
         * Grouping is by **core, not institution**, which is why the two ECMWF runs are not one
         * family: IFS solves equations, AIFS is machine-learned, and the entire reason AIFS is on
         * the list is that it fails differently. Sharing a letterhead is not sharing a core.
         *
         * [among] is whoever publishes the quantity being blended, not whoever is present at all: a
         * family of four of whom only two publish humidity is a family of two for humidity.
         */
        fun weightOf(source: Source, among: Set<Source>): Double {
            val kin = among.count { it.family == source.family }.coerceAtLeast(1)
            return 1.0 / sqrt(kin.toDouble())
        }

        fun weights(among: Set<Source>): Map<Source, Double> = among.associateWith { weightOf(it, among) }

        /**
         * The median with [weightOf]'s weights: the value at which half the weight lies below and
         * half above.
         *
         * With equal weights it is exactly [median], including the average of the two middle values
         * for an even count — that equivalence is what [ConsensusBlenderTest] pins, because it is
         * the only way to know the weighting changed nothing except what it was meant to change.
         */
        fun weightedMedian(values: Map<Source, Double>): Double {
            require(values.isNotEmpty()) { "no values to take a median of" }
            val w = weights(values.keys)
            val sorted = values.entries.sortedBy { it.value }
            val half = w.values.sum() / 2.0
            var cumulative = 0.0
            sorted.forEachIndexed { i, entry ->
                cumulative += w.getValue(entry.key)
                // Exactly half the weight below this value means the answer sits between it and the
                // next one, which is what makes four equally weighted models average their middle
                // pair rather than pick the lower of them.
                if (cumulative == half && i + 1 < sorted.size) return (entry.value + sorted[i + 1].value) / 2.0
                if (cumulative >= half) return entry.value
            }
            return sorted.last().value
        }

        /** The mean with the same weights. Used for the zero-inflated quantities; see [blendHour]. */
        fun weightedMean(values: Map<Source, Double>): Double {
            require(values.isNotEmpty()) { "no values to take a mean of" }
            val w = weights(values.keys)
            return values.entries.sumOf { it.value * w.getValue(it.key) } / w.values.sum()
        }

        /** The share of the weight, 0..1, whose model satisfies [predicate]. */
        fun weightedShare(among: Set<Source>, predicate: (Source) -> Boolean): Double {
            if (among.isEmpty()) return 0.0
            val w = weights(among)
            return among.filter(predicate).sumOf { w.getValue(it) } / w.values.sum()
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

        /**
         * The vote with every model counting for one — which is what a caller holding bare labels
         * and no idea who said them can honestly do.
         *
         * The blender itself never takes this path: it knows which model said what and uses the
         * overload below, where a family of four does not out-vote a family of one four times over.
         * This one stays because equal weights are still a case worth having and worth testing, and
         * because a caller that has lost the sources has lost the only thing weights can be built
         * from.
         */
        fun voteCondition(
            conditions: List<Condition>,
            precipMm: Double,
            stationSaturated: Boolean = false,
            cloudPct: List<Int> = emptyList(),
        ): Condition = voteCondition(
            conditions = conditions.mapIndexed { i, c -> i to c }.toMap(),
            weightOf = { 1.0 },
            precipMm = precipMm,
            stationSaturated = stationSaturated,
            cloudPct = cloudPct.mapIndexed { i, c -> i to c }.toMap(),
            measuredDry = false,
        )

        /**
         * The same vote, with each model's word worth [weightOf] — see that function for why four
         * ICON runs are not four independent opinions about whether it is cloudy.
         *
         * Every threshold below is a *share* rather than a count, so none of them had to move: a
         * third of the models is a third of the weight, and with equal weights the two are the same
         * number.
         */
        fun voteCondition(
            conditions: Map<Source, Condition>,
            precipMm: Double,
            stationSaturated: Boolean = false,
            cloudPct: Map<Source, Int> = emptyMap(),
            measuredDry: Boolean = false,
        ): Condition = voteCondition(
            conditions = conditions,
            weightOf = weights(conditions.keys)::getValue,
            precipMm = precipMm,
            stationSaturated = stationSaturated,
            cloudPct = cloudPct,
            measuredDry = measuredDry,
        )

        private fun <K> voteCondition(
            conditions: Map<K, Condition>,
            weightOf: (K) -> Double,
            precipMm: Double,
            stationSaturated: Boolean,
            cloudPct: Map<K, Int>,
            measuredDry: Boolean,
        ): Condition {
            if (conditions.isEmpty()) return Condition.CLOUDY
            fun weight(of: Map<K, Condition>) = of.keys.sumOf(weightOf)
            val total = weight(conditions)
            val wet = conditions.filterValues { it.isPrecipitation }
            val dry = conditions.filterValues { !it.isPrecipitation }
            val fog = weight(conditions.filterValues { it == Condition.FOG })
            // A saturated station is ground truth against a forecast, so it lowers the bar to a
            // single source having seen the fog. It never invents fog on its own: something has to
            // have forecast it first. See StationFog.
            val fogWins = fog > 0 && precipMm <= FOG_LOSES_ABOVE_MM &&
                (stationSaturated || fog * WET_SHARE_DENOMINATOR >= total)
            if (fogWins) return Condition.FOG
            // A rain gauge that has not moved is a dry hour even when every label is wet — see
            // StationDry — so the sky is then read off the cloud, or failing that called overcast:
            // a sky every model filled with rain is not a clear one.
            if (precipMm < WET_MIN_MM && (dry.isNotEmpty() || measuredDry)) {
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
                if (cloudPct.size >= MIN_CLOUD_SOURCES) {
                    return fromCloudCover(weightedMedianBy(cloudPct.mapValues { it.value.toDouble() }, weightOf))
                }
                return if (dry.isEmpty()) Condition.CLOUDY else plurality(dry, weightOf)
            }
            // The mildest wet answer the models actually gave, not the worst: a third of them
            // saying so is reason to call it drizzle, not reason to promise heavy rain.
            val pool = if (wet.isNotEmpty() && weight(wet) * WET_SHARE_DENOMINATOR >= total) wet else conditions
            val voted = plurality(pool, weightOf)
            // Which of drizzle, rain and heavy rain is a question about how much, and the amount is
            // decided above as the mean: the plurality of the labels put "Leichter Regen" over
            // 3,3 mm beside "Regen" over 1,8 on 2026-09-16. Below WET_MIN_MM the labels stand.
            return if (voted.isLiquidRain && precipMm >= WET_MIN_MM) Condition.rainFor(precipMm) else voted
        }

        /** [weightedMedian] over anything that can be weighed, not only over sources. */
        private fun <K> weightedMedianBy(values: Map<K, Double>, weightOf: (K) -> Double): Double {
            val sorted = values.entries.sortedBy { it.value }
            val half = values.keys.sumOf(weightOf) / 2.0
            var cumulative = 0.0
            sorted.forEachIndexed { i, entry ->
                cumulative += weightOf(entry.key)
                if (cumulative == half && i + 1 < sorted.size) return (entry.value + sorted[i + 1].value) / 2.0
                if (cumulative >= half) return entry.value
            }
            return sorted.last().value
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

        /** The heaviest answer by weight; ties resolved toward the more severe condition. */
        private fun <K> plurality(conditions: Map<K, Condition>, weightOf: (K) -> Double): Condition =
            conditions.entries.groupBy({ it.value }, { weightOf(it.key) })
                .mapValues { (_, ws) -> ws.sum() }.entries
                .sortedWith(compareByDescending<Map.Entry<Condition, Double>> { it.value }.thenByDescending { it.key })
                .first().key
    }
}
