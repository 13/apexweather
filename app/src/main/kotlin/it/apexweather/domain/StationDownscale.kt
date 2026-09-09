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
        return observed + offset
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
