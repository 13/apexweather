package it.apexweather.domain

import it.apexweather.domain.model.Source
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * How far ahead a model's word was given.
 *
 * The app checks its models at three distances rather than one. Before this it checked at one — the
 * value the run just fetched put on the hour that has just happened, which is an analysis and very
 * nearly a measurement — and then faded that number across twelve hours of lead time as though it
 * had been a forecast error. A model's error at lead zero and its error half a day out are
 * different quantities, and the second is the one the app actually applies.
 *
 * Three buckets, because each has to fill with six hours of evidence before it says anything, and a
 * finer grid would take proportionally longer to fill for no visible gain.
 */
enum class LeadBucket(val hours: Long) {
    NOW(0), SIX(6), TWELVE(12);

    companion object {
        /**
         * The bucket whose record judges a forecast [leadHours] ahead. Beyond the furthest the app
         * verifies, the furthest bucket is still the closest thing to evidence there is — and
         * [BiasCorrector.correctionAt] fades it out rather than trusting it whole.
         */
        fun judging(leadHours: Long): LeadBucket = when {
            leadHours <= 3 -> NOW
            leadHours <= 8 -> SIX
            else -> TWELVE
        }
    }
}

/**
 * One hour in which both the station and the models can be pinned to a number.
 *
 * [predictedC] holds what each model said this hour would be, keyed by how far ahead it said it.
 * A row is built up over half a day: the twelve-hour-ahead value is written when the app refreshes
 * twelve hours before the hour, the six-hour one six hours before, and the observation and the
 * lead-zero value when the hour arrives.
 */
data class StationSample(
    val time: Instant,
    val observedC: Double,
    val predictedC: Map<LeadBucket, Map<Source, Double>>,
)

/**
 * Which sixth-of-a-day an hour belongs to, in the place's own time zone.
 *
 * A model's error in a mountain valley is not one number: the afternoon mixes the column and the
 * night pools cold air on the floor, and a model can be systematically warm in one and cold in the
 * other. Averaged together those cancel and the model looks unbiased, which is the one answer that
 * is certainly wrong. Six-hour blocks are coarse enough that each fills within a couple of days.
 */
enum class DayPart {
    NIGHT, MORNING, AFTERNOON, EVENING;

    companion object {
        fun of(time: Instant, zone: ZoneId): DayPart = when (time.atZone(zone).hour) {
            in 0..5 -> NIGHT
            in 6..11 -> MORNING
            in 12..17 -> AFTERNOON
            else -> EVENING
        }
    }
}

/**
 * How warm each model has lately run at the station, by part of the day and by how far ahead it was
 * asked.
 *
 * A source absent from a cell is one there is not enough evidence about yet, which is a different
 * thing from one measured as unbiased — [at] returns null for the first and 0.0 for the second, and
 * a caller can tell them apart.
 */
data class ModelBias(
    val byPart: Map<Source, Map<DayPart, Map<LeadBucket, Double>>> = emptyMap(),
) {

    /**
     * How warm [source] runs at the time of day [time] falls in, when asked [leadHours] ahead — or
     * null where that has not been measured enough times to say.
     */
    fun at(source: Source, time: Instant, zone: ZoneId, leadHours: Long): Double? =
        byPart[source]?.get(DayPart.of(time, zone))?.get(LeadBucket.judging(leadHours))

    companion object {
        val NONE = ModelBias()
    }
}

/**
 * How wrong each model has lately been, at the one place where it can be checked.
 *
 * A weather station is the only ground truth this app has. Every hour it records what the
 * thermometer read and what each model said the temperature would be *at that thermometer*, and
 * over a few days the difference between the two stops being noise and starts being that model's
 * habit here — a valley a model runs slightly too warm in, a slope it cools too fast overnight.
 * Subtracting that habit is the oldest trick in statistical post-processing and the only one
 * available without a training archive.
 *
 * It is measured along two axes, because a single number over the week is the average of things
 * that cancel:
 *
 * - Per [DayPart], because the habit is not the same at four in the morning as at four in the
 *   afternoon, and in this valley the average of the two is close to zero however wrong the model
 *   is at either end of the day.
 * - Per [LeadBucket], because the app applies the correction to a forecast and must therefore
 *   measure a forecast. What it used to measure was the run's value for the hour just gone, which
 *   is an analysis: nearly a measurement, and a far easier thing to get right than tonight.
 *
 * Three guards, because a correction can do harm:
 *
 * - It needs [MIN_SAMPLES] hours *in that cell* before it says anything at all. Two hours of
 *   agreement is luck. The price of splitting is that a cell takes about a day to fill rather than
 *   six hours; the alternative is a correction that is confidently the wrong sign twice a day.
 * - It is clamped to [MAX_BIAS_C], and refused outright beyond [IMPLAUSIBLE_BIAS_C]. Those are two
 *   different statements. Three degrees is the most the app is willing to move a forecast on this
 *   evidence, so a model measured four degrees warm is moved three — it used to be moved *none*,
 *   which handed the worst model on the list the gentlest treatment and put a cliff in the middle
 *   of the range: 2,9 K corrected in full, 3,1 K not at all. Past six degrees the reading is no
 *   longer a habit but a broken input — a mismatched station, a unit, a model returning nonsense —
 *   and there the honest response really is to leave the forecast alone.
 * - It still fades, but only past where the record reaches: full strength to
 *   [FULL_STRENGTH_HOURS] and nothing by [NO_STRENGTH_HOURS].
 */
object BiasCorrector {

    /**
     * Fewer hours than this, within one part of the day at one lead time, and the difference is
     * weather, not habit.
     */
    const val MIN_SAMPLES = 6

    /** The most the app will move a forecast on this evidence. A larger habit is clamped to it. */
    const val MAX_BIAS_C = 3.0

    /**
     * And the size past which it is no longer a habit at all.
     *
     * [ConsensusBlender.FULL_DISAGREEMENT_C]: the span at which the app already says the models are
     * telling the reader nothing about the current hour. A *mean* error that big, sustained over
     * six hours of one part of the day, is not a model running warm in this valley; it is a
     * mismatched station, a unit, or a model returning nonsense, and nothing useful is subtracted
     * from a forecast on the strength of it.
     */
    const val IMPLAUSIBLE_BIAS_C = 6.0

    /**
     * As far ahead as the app has actually checked its models, and the point past which the
     * correction starts fading.
     *
     * It used to be three hours, because the record was an analysis and stood in for a forecast
     * error; now the twelve-hour bucket holds what the models really said twelve hours out, and
     * applying that at twelve hours needs no apology. [LeadBucket.judging] hands that bucket to
     * anything from nine hours on, so the last hour it honestly covers is fifteen.
     */
    const val FULL_STRENGTH_HOURS = 15L

    /**
     * And where it reaches zero. Beyond the furthest the app verifies, the furthest bucket is the
     * only evidence there is; it is worth something for a while and nothing by the next day.
     */
    const val NO_STRENGTH_HOURS = 24L

    /** How far back samples are worth keeping and reading. */
    val WINDOW: Duration = Duration.ofDays(7)

    /**
     * Mean signed error per source, part of the day and lead bucket over the window: positive where
     * the model runs warm. A cell with too few hours to judge is absent rather than assigned a
     * zero, so a caller can tell "no bias" from "no idea".
     */
    fun biases(samples: List<StationSample>, now: Instant, zone: ZoneId): ModelBias {
        val recent = samples.filter { Duration.between(it.time, now) <= WINDOW && !it.time.isAfter(now) }
        if (recent.isEmpty()) return ModelBias.NONE
        val byPart = recent.groupBy { DayPart.of(it.time, zone) }
        return ModelBias(
            Source.entries.mapNotNull { source ->
                val parts = byPart.mapNotNull { (part, inPart) ->
                    val leads = LeadBucket.entries.mapNotNull { lead ->
                        val errors = inPart.mapNotNull { s -> s.predictedC[lead]?.get(source)?.minus(s.observedC) }
                        if (errors.size < MIN_SAMPLES) return@mapNotNull null
                        val mean = errors.average()
                        // Refused where it is not a habit; clamped where it is a large one.
                        if (abs(mean) > IMPLAUSIBLE_BIAS_C) null
                        else lead to mean.coerceIn(-MAX_BIAS_C, MAX_BIAS_C)
                    }.toMap()
                    if (leads.isEmpty()) null else part to leads
                }.toMap()
                if (parts.isEmpty()) null else source to parts
            }.toMap(),
        )
    }

    /**
     * The correction to subtract from a forecast [leadHours] ahead. Zero where there is no bias to
     * apply, so a caller can always subtract without checking.
     *
     * Full strength out to where the record reaches, then a straight line to nothing.
     */
    fun correctionAt(bias: Double?, leadHours: Long): Double {
        if (bias == null) return 0.0
        val strength = when {
            leadHours <= FULL_STRENGTH_HOURS -> 1.0
            leadHours >= NO_STRENGTH_HOURS -> 0.0
            else -> (NO_STRENGTH_HOURS - leadHours).toDouble() / (NO_STRENGTH_HOURS - FULL_STRENGTH_HOURS)
        }
        return bias * strength
    }
}
