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
    /** Hours left out as a station fault. */
    val excluded: Int = 0,
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
        val period = hours.filter { !it.time.isBefore(since) && observed(it, quantity) != null }
        val modelRows = MODELS.map { source ->
            RankedRow(Contender.Model(source), score(pairs(period, quantity) { predicted(it, lead, source, quantity) }, quantity), rank = null)
        }.filter { it.score.hours > 0 || it.score.excluded > 0 }
        val (rankable, notYet) = modelRows.partition { rankable(it.score, quantity) }
        val ranked = rankable.sortedWith(compare(quantity)).mapIndexed { i, row -> row.copy(rank = i + 1) }
        val references = buildList {
            score(pairs(period, quantity) { consensus(it, lead, quantity) }, quantity).takeIf { it.hours > 0 }
                ?.let { add(RankedRow(Contender.Consensus, it, null)) }
            if (quantity != Quantity.RAIN) {
                val byTime = hours.associateBy { it.time }
                score(pairs(period, quantity) { byTime[it.time.minusSeconds(24 * 3600)]?.let { y -> observed(y, quantity) } }, quantity)
                    .takeIf { it.hours > 0 }?.let { add(RankedRow(Contender.SameAsYesterday, it, null)) }
            }
        }
        return Ranking(
            quantity = quantity, lead = lead, ranked = ranked, references = references,
            unranked = notYet.sortedBy { (it.contender as Contender.Model).source.ordinal },
            observedHours = period.size, firstHour = period.minOfOrNull { it.time },
            excludedHours = modelRows.sumOf { it.score.excluded },
        )
    }

    fun byPart(hours: List<VerificationHour>, quantity: Quantity, lead: LeadBucket, since: Instant, source: Source, zone: ZoneId): Map<DayPart, Score> {
        val period = hours.filter { !it.time.isBefore(since) }
        return DayPart.entries.associateWith { part ->
            score(pairs(period.filter { DayPart.of(it.time, zone) == part }, quantity) { predicted(it, lead, source, quantity) }, quantity)
        }
    }

    /** Mean absolute error per local day, oldest first. Empty for rain. */
    fun dailyError(hours: List<VerificationHour>, quantity: Quantity, lead: LeadBucket, since: Instant, source: Source, zone: ZoneId): List<Pair<LocalDate, Double>> {
        if (quantity == Quantity.RAIN) return emptyList()
        return hours.filter { !it.time.isBefore(since) }
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
        Quantity.TEMPERATURE -> continuous(pairs, TEMP_HIT_K, TEMP_FAULT_K)
        Quantity.WIND -> continuous(pairs, WIND_HIT_KMH, WIND_FAULT_KMH)
        Quantity.RAIN -> rain(pairs)
    }

    private fun continuous(pairs: List<Pair<Double, Double>>, hit: Double, fault: Double): Score {
        val errors = pairs.map { (p, o) -> p - o }
        val kept = errors.filter { abs(it) <= fault }
        val excluded = errors.size - kept.size
        if (kept.isEmpty()) return Score(0, null, null, null, excluded = excluded)
        return Score(
            hours = kept.size,
            main = kept.sumOf { abs(it) } / kept.size,
            hitRate = kept.count { abs(it) <= hit }.toDouble() / kept.size,
            lean = kept.sum() / kept.size,
            excluded = excluded,
        )
    }

    private fun rain(pairs: List<Pair<Double, Double>>): Score {
        var hits = 0
        var misses = 0
        var falseAlarms = 0
        pairs.forEach { (p, o) ->
            val forecastWet = p >= WET_MM
            val observedWet = o >= WET_MM
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
