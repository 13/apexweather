package it.apexweather.domain

import it.apexweather.domain.model.StationObservation
import java.time.Duration
import java.time.Instant

/** The newest radar frame's reading at a place, and when the radar took it. */
data class RadarNow(val time: Instant, val reading: RadarReading)

/**
 * What the instruments say about rain at this minute, where they say anything: the other half of
 * [StationDry].
 *
 * The models decide the current hour's rain, and they can be wrong in both directions. [StationDry]
 * covers "they say rain and the gauge has not moved"; this adds "they say dry and it is raining",
 * from two measurements of different kinds, trusted in this order:
 *
 * 1. **A rising gauge** — the station's total went up within the last hour. The bucket measured it.
 * 2. **A gauge that has not moved** ([StationDry]) — dry, whatever the radar shows. Radar sees
 *    precipitation aloft, and over these mountains some of it never reaches the valley; a false
 *    "rain" on a dry afternoon is the more visible mistake.
 * 3. **A fresh radar echo at rain strength** — where the station has nothing to say (too humid to
 *    trust its silence, no previous reading, or no station at all).
 *
 * The current hour only, as with fog and sun.
 */
object MeasuredRain {

    sealed interface Measured

    /** Measured rain at [mmPerHour]; [snow] where the radar read it as snow. */
    data class Wet(val mmPerHour: Double, val snow: Boolean = false) : Measured

    data object Dry : Measured

    /** RainViewer publishes every ten minutes and a frame is some minutes old when it appears. */
    val RADAR_FRESH_FOR: Duration = Duration.ofMinutes(20)

    /** A rise over a longer interval says it rained at some point, not that it is raining. */
    val GAUGE_WINDOW: Duration = Duration.ofMinutes(60)

    fun now(observation: StationObservation?, radar: RadarNow?, now: Instant): Measured? {
        gaugeRate(observation, now)?.let { return Wet(it) }
        if (StationDry.isDry(observation, now)) return Dry
        val echo = radar?.takeIf { Duration.between(it.time, now) <= RADAR_FRESH_FOR }?.reading
        if (echo != null && echo.isRain) return Wet(echo.mmPerHour, echo.snow)
        return null
    }

    private fun gaugeRate(observation: StationObservation?, now: Instant): Double? {
        val total = observation?.precipTodayMm ?: return null
        if (Duration.between(observation.time, now) > StationDry.FRESH_FOR) return null
        val previous = observation.previousPrecipTodayMm ?: return null
        val previousAt = observation.previousTime ?: return null
        val interval = Duration.between(previousAt, observation.time)
        if (interval.isNegative || interval.isZero || interval > GAUGE_WINDOW) return null
        val zone = SouthTyrol.ZONE
        if (previousAt.atZone(zone).toLocalDate() != observation.time.atZone(zone).toLocalDate()) return null
        val rise = total - previous
        if (rise < MIN_RISE_MM) return null
        return rise / (interval.seconds / 3600.0)
    }

    /** One tip of the bucket, near enough: SIAG publishes to a tenth of a millimetre. */
    private const val MIN_RISE_MM = 0.1 - 1e-9
}
