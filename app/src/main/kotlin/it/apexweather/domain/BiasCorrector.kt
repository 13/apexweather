package it.apexweather.domain

import it.apexweather.domain.model.Source
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/** One hour in which both the station and the models can be pinned to a number. */
data class StationSample(val time: Instant, val observedC: Double, val modelC: Map<Source, Double>)

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
 * Three guards, because a correction can do harm:
 *
 * - It needs [MIN_SAMPLES] hours before it says anything at all. Two hours of agreement is luck.
 * - It is clamped to [MAX_BIAS_C]. Beyond that the input is broken, not biased, and the honest
 *   response is to leave the forecast alone rather than to move it a long way on bad evidence.
 * - It fades with lead time. A model's systematic error today is decent evidence about this
 *   afternoon and almost none about Thursday, so the correction is applied in full for the first
 *   [FULL_STRENGTH_HOURS] and tapers to nothing by [NO_STRENGTH_HOURS].
 */
object BiasCorrector {

    /** Fewer hours than this and the difference is weather, not habit. */
    const val MIN_SAMPLES = 6

    /** Beyond this the input is broken rather than biased. */
    const val MAX_BIAS_C = 3.0

    const val FULL_STRENGTH_HOURS = 3L
    const val NO_STRENGTH_HOURS = 12L

    /** How far back samples are worth keeping and reading. */
    val WINDOW: Duration = Duration.ofDays(7)

    /**
     * Mean signed error per source over the window: positive where the model runs warm. Sources with
     * too few hours to judge are absent rather than assigned a zero, so a caller can tell "no bias"
     * from "no idea".
     */
    fun biases(samples: List<StationSample>, now: Instant): Map<Source, Double> {
        val recent = samples.filter { Duration.between(it.time, now) <= WINDOW && !it.time.isAfter(now) }
        if (recent.isEmpty()) return emptyMap()
        return Source.entries.mapNotNull { source ->
            val errors = recent.mapNotNull { s -> s.modelC[source]?.minus(s.observedC) }
            if (errors.size < MIN_SAMPLES) return@mapNotNull null
            val mean = errors.average()
            if (abs(mean) > MAX_BIAS_C) null else source to mean
        }.toMap()
    }

    /**
     * The correction to subtract from a forecast [leadHours] ahead. Zero where there is no bias to
     * apply, so a caller can always subtract without checking.
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
