package it.apexweather.domain

import it.apexweather.domain.model.StationObservation
import java.time.Duration
import java.time.Instant

/**
 * Whether the weather station's rain gauge says it is not raining right now.
 *
 * On 2026-09-16 at 18:56 the screen over Meran said "Gewitter" with rain falling across the sky,
 * while the station read 0,0 mm since midnight at 49 % humidity under a dry sky. The models had the
 * evening's front arriving and were right about the evening; they were wrong about the minute, and
 * the app had a measurement that said so and no rule that listened to it. [StationFog] and
 * [StationSun] correct the current hour's sky from the station; this corrects its rain.
 *
 * The gauge publishes a total since local midnight, not a rate, so "dry" means one of two things:
 * nothing at all has fallen today, or the total has not risen since the reading before
 * ([StationObservation.previousPrecipTodayMm], carried across refreshes by [withPrevious]).
 *
 * The current hour only, as with fog and sun: a thermometer speaks for the hour it measured.
 */
object StationDry {

    /**
     * SIAG stations publish every ten to twenty minutes, and a shower can start inside that. Half an
     * hour is as old as a reading may be and still say anything about this minute.
     */
    val FRESH_FOR: Duration = Duration.ofMinutes(30)

    /**
     * Drizzle can fall for a while under a bucket's 0,1 to 0,2 mm resolution without tipping it,
     * and it saturates the air. Above this the gauge's silence is not trusted.
     */
    const val HUMID_RH_PCT = 90

    fun isDry(observation: StationObservation?, now: Instant): Boolean {
        val total = observation?.precipTodayMm ?: return false
        if (Duration.between(observation.time, now) > FRESH_FOR) return false
        if ((observation.humidityPct ?: return false) >= HUMID_RH_PCT) return false
        if (total == 0.0) return true
        val previous = observation.previousPrecipTodayMm ?: return false
        val previousAt = observation.previousTime ?: return false
        val zone = SouthTyrol.ZONE
        if (previousAt.atZone(zone).toLocalDate() != observation.time.atZone(zone).toLocalDate()) return false
        return total <= previous
    }

    /**
     * [fresh] with the reading before it attached, so the next [isDry] can tell whether the total
     * rose. The same reading fetched again keeps the comparison it already carried.
     */
    fun withPrevious(fresh: StationObservation, cached: StationObservation?): StationObservation {
        val base = when {
            cached == null -> return fresh
            cached.time.isBefore(fresh.time) -> cached
            cached.time == fresh.time -> return fresh.copy(
                previousPrecipTodayMm = cached.previousPrecipTodayMm,
                previousTime = cached.previousTime,
            )
            else -> return fresh
        }
        return fresh.copy(previousPrecipTodayMm = base.precipTodayMm, previousTime = base.time)
    }
}
