package it.apexweather.domain

import it.apexweather.domain.model.Source
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

enum class Quantity { TEMPERATURE, RAIN, WIND }

/** Who a row of the table is about: a model, or one of the two things a model has to beat. */
sealed interface Contender {
    data class Model(val source: Source) : Contender
    /** The weighted median of the models at the station (weighted mean for rain). */
    data object Consensus : Contender
    /** The station's own reading 24 hours earlier. Not for rain. */
    data object SameAsYesterday : Contender
}

/**
 * One contender's record over a period.
 *
 * - Temperature, wind: [main] mean absolute error; [hitRate] share within the tolerance; [lean] mean
 *   signed error (positive = too warm / too windy).
 * - Rain: [main] critical success index, hits ÷ (hits + misses + false alarms); [hitRate] share of wet
 *   hours forecast wet; [lean] share of wet forecasts that stayed dry.
 */
data class Score(
    val hours: Int,
    val main: Double?,
    val hitRate: Double?,
    val lean: Double?,
    /** Rain: hours wet observed or forecast. */
    val wetHours: Int = 0,
)

data class RankedRow(val contender: Contender, val score: Score, val rank: Int?)

data class Ranking(
    val quantity: Quantity,
    val lead: LeadBucket,
    val ranked: List<RankedRow>,
    val references: List<RankedRow>,
    val unranked: List<RankedRow>,
    /** Hours in the period with an observation of [quantity]. */
    val observedHours: Int,
    val firstHour: Instant?,
    /**
     * Hours in the period taken to be a station fault: the reading is further from the models'
     * consensus than the fault limit (see [ForecastScores.faultHours]). Each is dropped for every
     * contender, so there is no per-contender count. Always 0 for rain, which has no fault rule.
     */
    val excludedHours: Int,
)

object ForecastScores {
    const val MIN_HOURS = 24
    const val MIN_WET_HOURS = 5
    const val WET_MM = 0.1
    const val TEMP_HIT_K = 2.0
    const val WIND_HIT_KMH = 5.0
    const val TEMP_FAULT_K = 15.0
    const val WIND_FAULT_KMH = 60.0

    private val MODELS: List<Source> = Source.entries.filter { it.checkableAtStation }

    fun rank(hours: List<VerificationHour>, quantity: Quantity, lead: LeadBucket, since: Instant): Ranking {
        val faults = faultHours(hours, quantity, lead)
        val period = hours.filter { !it.time.isBefore(since) && observed(it, quantity) != null }
        val scored = period.filter { it.time !in faults }
        val modelRows = MODELS.map { source ->
            RankedRow(Contender.Model(source), score(pairs(scored, quantity) { predicted(it, lead, source, quantity) }, quantity), rank = null)
        }.filter { it.score.hours > 0 }
        val (rankable, notYet) = modelRows.partition { rankable(it.score, quantity) }
        val ranked = rankable.sortedWith(compare(quantity)).mapIndexed { i, row -> row.copy(rank = i + 1) }
        // A reference row is held to the same minimum as a model: a Konsens over three hours sitting
        // above twelve ranked models is an accident of the sample, not something to beat.
        val references = buildList {
            score(pairs(scored, quantity) { consensus(it, lead, quantity) }, quantity).takeIf { rankable(it, quantity) }
                ?.let { add(RankedRow(Contender.Consensus, it, null)) }
            if (quantity != Quantity.RAIN) {
                val byTime = hours.associateBy { it.time }
                // A faulty reading a day earlier is no forecast either.
                score(pairs(scored, quantity) { byTime[it.time.minusSeconds(24 * 3600)]?.takeIf { y -> y.time !in faults }?.let { y -> observed(y, quantity) } }, quantity)
                    .takeIf { rankable(it, quantity) }?.let { add(RankedRow(Contender.SameAsYesterday, it, null)) }
            }
        }
        return Ranking(
            quantity = quantity, lead = lead, ranked = ranked, references = references,
            unranked = notYet.sortedBy { (it.contender as Contender.Model).source.ordinal },
            observedHours = period.size, firstHour = period.minOfOrNull { it.time },
            excludedHours = period.count { it.time in faults },
        )
    }

    /**
     * The hours whose reading is taken to be a station fault, at [lead]: the observation is further
     * than [TEMP_FAULT_K] or [WIND_FAULT_KMH] from the models' consensus (the weighted median).
     *
     * Judged once per hour against the consensus, never per model: a single model far off while the
     * station and the others agree has simply missed, and that miss counts against it. Rain has no
     * fault rule. An hour no model forecast at [lead] cannot be judged and is not a fault.
     */
    fun faultHours(hours: List<VerificationHour>, quantity: Quantity, lead: LeadBucket): Set<Instant> {
        val limit = when (quantity) {
            Quantity.TEMPERATURE -> TEMP_FAULT_K
            Quantity.WIND -> WIND_FAULT_KMH
            Quantity.RAIN -> return emptySet()
        }
        return hours.filter { hour ->
            val o = observed(hour, quantity) ?: return@filter false
            val c = consensus(hour, lead, quantity) ?: return@filter false
            abs(o - c) > limit
        }.mapTo(HashSet()) { it.time }
    }

    fun byPart(hours: List<VerificationHour>, quantity: Quantity, lead: LeadBucket, since: Instant, source: Source, zone: ZoneId): Map<DayPart, Score> {
        val faults = faultHours(hours, quantity, lead)
        val period = hours.filter { !it.time.isBefore(since) && it.time !in faults }
        return DayPart.entries.associateWith { part ->
            score(pairs(period.filter { DayPart.of(it.time, zone) == part }, quantity) { predicted(it, lead, source, quantity) }, quantity)
        }
    }

    /** Mean absolute error per local day, oldest first. Empty for rain. */
    fun dailyError(hours: List<VerificationHour>, quantity: Quantity, lead: LeadBucket, since: Instant, source: Source, zone: ZoneId): List<Pair<LocalDate, Double>> {
        if (quantity == Quantity.RAIN) return emptyList()
        val faults = faultHours(hours, quantity, lead)
        return hours.filter { !it.time.isBefore(since) && it.time !in faults }
            .groupBy { it.time.atZone(zone).toLocalDate() }
            .toSortedMap()
            .mapNotNull { (day, dayHours) ->
                score(pairs(dayHours, quantity) { predicted(it, lead, source, quantity) }, quantity).main?.let { day to it }
            }
    }

    /** Best first. Temperature and wind: smaller miss; rain: larger CSI. Then more hits, then list order. */
    fun compare(quantity: Quantity): Comparator<RankedRow> {
        val byMain = if (quantity == Quantity.RAIN) compareByDescending<RankedRow> { it.score.main ?: -1.0 }
        else compareBy<RankedRow> { it.score.main ?: Double.MAX_VALUE }
        return byMain
            .thenByDescending { it.score.hitRate ?: -1.0 }
            .thenBy { (it.contender as? Contender.Model)?.source?.ordinal ?: -1 }
    }

    private fun rankable(score: Score, quantity: Quantity): Boolean =
        score.main != null && score.hours >= MIN_HOURS && (quantity != Quantity.RAIN || score.wetHours >= MIN_WET_HOURS)

    private fun observed(hour: VerificationHour, quantity: Quantity): Double? = when (quantity) {
        Quantity.TEMPERATURE -> hour.observedC
        Quantity.RAIN -> hour.observedRainMm
        Quantity.WIND -> hour.observedWindKmh
    }

    private fun predicted(hour: VerificationHour, lead: LeadBucket, source: Source, quantity: Quantity): Double? {
        val p = hour.predicted[lead]?.get(source) ?: return null
        return when (quantity) {
            Quantity.TEMPERATURE -> p.tempC
            Quantity.RAIN -> p.rainMm
            Quantity.WIND -> p.windKmh
        }
    }

    private fun consensus(hour: VerificationHour, lead: LeadBucket, quantity: Quantity): Double? {
        val values = MODELS.mapNotNull { s -> predicted(hour, lead, s, quantity)?.let { s to it } }.toMap()
        if (values.isEmpty()) return null
        return if (quantity == Quantity.RAIN) ConsensusBlender.weightedMean(values) else ConsensusBlender.weightedMedian(values)
    }

    /** (forecast, observed) for every hour where both exist. */
    private fun pairs(hours: List<VerificationHour>, quantity: Quantity, forecast: (VerificationHour) -> Double?): List<Pair<Double, Double>> =
        hours.mapNotNull { h -> val o = observed(h, quantity) ?: return@mapNotNull null; forecast(h)?.let { it to o } }

    private fun score(pairs: List<Pair<Double, Double>>, quantity: Quantity): Score = when (quantity) {
        Quantity.TEMPERATURE -> continuous(pairs, TEMP_HIT_K)
        Quantity.WIND -> continuous(pairs, WIND_HIT_KMH)
        Quantity.RAIN -> rain(pairs)
    }

    private fun continuous(pairs: List<Pair<Double, Double>>, hit: Double): Score {
        if (pairs.isEmpty()) return Score(0, null, null, null)
        val errors = pairs.map { (p, o) -> p - o }
        return Score(
            hours = errors.size,
            main = errors.sumOf { abs(it) } / errors.size,
            hitRate = errors.count { abs(it) <= hit }.toDouble() / errors.size,
            lean = errors.sum() / errors.size,
        )
    }

    private fun rain(pairs: List<Pair<Double, Double>>): Score {
        var hits = 0
        var misses = 0
        var falseAlarms = 0
        pairs.forEach { (p, o) ->
            // A small tolerance below WET_MM: model values are floats too, and a value the station or
            // a model meant as exactly 0.1 can arrive as 0.09999999999999998 (see VerificationHistory).
            val forecastWet = p >= WET_MM - 1e-9
            val observedWet = o >= WET_MM - 1e-9
            when {
                forecastWet && observedWet -> hits++
                !forecastWet && observedWet -> misses++
                forecastWet && !observedWet -> falseAlarms++
            }
        }
        val wet = hits + misses + falseAlarms
        return Score(
            hours = pairs.size,
            main = if (wet == 0) null else hits.toDouble() / wet,
            hitRate = if (hits + misses == 0) null else hits.toDouble() / (hits + misses),
            lean = if (hits + falseAlarms == 0) null else falseAlarms.toDouble() / (hits + falseAlarms),
            wetHours = wet,
        )
    }
}
