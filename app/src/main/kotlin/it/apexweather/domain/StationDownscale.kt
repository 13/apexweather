package it.apexweather.domain

import it.apexweather.data.remote.StationReference
import it.apexweather.domain.model.ConsensusForecast
import it.apexweather.domain.model.StationObservation
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Carries the weather station's live reading up the hill to the village.
 *
 * The nearest station is in Meran at 330 m; the village sits at about 600 m, 1.4 km away. Quoting
 * the station's thermometer as the village's temperature is therefore wrong by however much those
 * 270 m are worth at that moment — a median 1.9 K across a two-day model run, up to 2.7 K on a
 * clear afternoon, and as little as 0.3 K when the valley is mixed.
 *
 * The correction is the models' own difference between the two points at that hour, not a textbook
 * lapse rate. That matters because the difference is not constant: it is largest in the afternoon
 * and smallest overnight, and on an inversion night the valley floor is the colder of the two, when
 * a fixed lapse rate would push the error the wrong way and double it.
 *
 * The result is still an observation — a real thermometer reading, moved — and is preferred over
 * the pure forecast for exactly that reason.
 */
object StationDownscale {

    /**
     * Beyond this the difference is not a height correction any more but a broken input — a stale
     * reference, a model that has gone strange — and the observation is better left alone.
     */
    private const val MAX_ADJUSTMENT_C = 6.0

    /**
     * How far the station may depart from what the models say about it before that departure stops
     * being treated as this valley's weather and starts being treated as this valley floor's.
     *
     * Moving the reading up the hill carries the station's whole anomaly with it: the village is
     * quoted as the models' village plus however much the thermometer differs from the models'
     * station. That is right when the models have simply got the day a degree wrong — the whole
     * column is a degree off, the village with it. It is wrong when the anomaly is the valley floor
     * doing something of its own, which on a clear night is exactly what it does: cold air pools at
     * the bottom, and the slope 270 m up does not join in.
     *
     * On 2026-09-11 at 04:00 the station read 12,7 °C at 100 % humidity while the eight models put
     * it at 15,05 and the village at 13,35. Carrying the whole -2,35 K anomaly up gave 11,0 °C. A
     * thermometer in the village read 12. The anomaly was the valley's, not the village's.
     *
     * The numbers below are a judgement calibrated on that night and the lapse rates in the class
     * doc, not a measured constant, and one night is one night. `station_history` is accumulating
     * what each model says against what the station reads; when BiasCorrector has six hours of it,
     * the systematic half of this belongs there instead.
     */
    private const val FULLY_TRUSTED_ANOMALY_C = 1.5
    private const val UNTRUSTED_ANOMALY_C = 4.5

    /** How stale the reference series may be before it stops describing today's air. */
    private val REFERENCE_MAX_AGE_HOURS = 12L

    /**
     * The village temperature implied by [observation], or null when there is nothing to base a
     * correction on — in which case the caller should fall back to the forecast rather than quoting
     * a station 270 m below the village.
     */
    fun villageTemperature(
        observation: StationObservation,
        reference: StationReference?,
        consensus: ConsensusForecast,
        now: Instant,
    ): Double? {
        val observed = observation.tempC ?: return null
        val offset = offsetAt(observation.time, reference, consensus, now) ?: return null
        val anomaly = stationAnomaly(observation.time, observed, reference, now) ?: return observed + offset
        // The village the models draw, plus as much of the thermometer's disagreement with them as
        // is likely to be shared 270 m up the hill. At full trust this is exactly observed + offset,
        // which is what it always was.
        return (observed + offset) - anomaly * (1 - transferred(anomaly))
    }

    /**
     * How much of a station anomaly of [anomaly] K belongs to the village: all of a small one, none
     * of a large one, and a straight line between.
     */
    private fun transferred(anomaly: Double): Double {
        val size = abs(anomaly)
        return when {
            size <= FULLY_TRUSTED_ANOMALY_C -> 1.0
            size >= UNTRUSTED_ANOMALY_C -> 0.0
            else -> (UNTRUSTED_ANOMALY_C - size) / (UNTRUSTED_ANOMALY_C - FULLY_TRUSTED_ANOMALY_C)
        }
    }

    /** What the thermometer reads minus what the models say it should, at that hour. */
    fun stationAnomaly(time: Instant, observed: Double, reference: StationReference?, now: Instant): Double? {
        if (reference == null) return null
        if (ChronoUnit.HOURS.between(reference.fetchedAt, now) > REFERENCE_MAX_AGE_HOURS) return null
        val atStation = reference.tempAt(time.truncatedTo(ChronoUnit.HOURS)) ?: return null
        return observed - atStation
    }

    /**
     * Village minus station, as the models see it at [time]. Null when either side is missing or the
     * two disagree by more than a height difference could explain.
     */
    fun offsetAt(
        time: Instant,
        reference: StationReference?,
        consensus: ConsensusForecast,
        now: Instant,
    ): Double? {
        if (reference == null) return null
        if (ChronoUnit.HOURS.between(reference.fetchedAt, now) > REFERENCE_MAX_AGE_HOURS) return null
        val hour = time.truncatedTo(ChronoUnit.HOURS)
        val atStation = reference.tempAt(hour) ?: return null
        val atVillage = consensus.hourly.firstOrNull { it.time == hour }?.tempC ?: return null
        val offset = atVillage - atStation
        return if (abs(offset) > MAX_ADJUSTMENT_C) null else offset
    }
}
