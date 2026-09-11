package it.apexweather.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

enum class SunPhase { NIGHT, DAWN, DAY, DUSK }

object SunPhaseCalculator {

    /**
     * How high the sun stands, in degrees above the horizon.
     *
     * NOAA's own approximation, which is good to a fraction of a degree and needs no ephemeris.
     * The app wants it for one thing: to know how much sunlight *should* be arriving, so that the
     * station's measured radiation can be read as a fraction of it. See [StationSun].
     *
     * Negative below the horizon, which is the honest answer at night and the one that stops
     * anything being concluded from a dark pyranometer.
     */
    fun elevationDegrees(at: Instant, lat: Double, lon: Double): Double {
        val utc = at.atZone(ZoneOffset.UTC)
        val dayOfYear = utc.dayOfYear
        val hours = utc.hour + utc.minute / 60.0
        val fractionalYear = 2.0 * PI / 365.0 * (dayOfYear - 1 + (hours - 12.0) / 24.0)
        val equationOfTime = 229.18 * (
            0.000075 +
                0.001868 * cos(fractionalYear) - 0.032077 * sin(fractionalYear) -
                0.014615 * cos(2 * fractionalYear) - 0.040849 * sin(2 * fractionalYear)
            )
        val declination = 0.006918 -
            0.399912 * cos(fractionalYear) + 0.070257 * sin(fractionalYear) -
            0.006758 * cos(2 * fractionalYear) + 0.000907 * sin(2 * fractionalYear) -
            0.002697 * cos(3 * fractionalYear) + 0.00148 * sin(3 * fractionalYear)
        val trueSolarMinutes = hours * 60.0 + equationOfTime + 4.0 * lon
        val hourAngle = Math.toRadians(trueSolarMinutes / 4.0 - 180.0)
        val latitude = Math.toRadians(lat)
        val sinElevation = sin(latitude) * sin(declination) +
            cos(latitude) * cos(declination) * cos(hourAngle)
        return Math.toDegrees(asin(sinElevation.coerceIn(-1.0, 1.0)))
    }

    private val twilight: Duration = Duration.ofMinutes(45)

    fun phase(now: Instant, sunrise: Instant?, sunset: Instant?, zone: ZoneId): SunPhase {
        if (sunrise == null || sunset == null) {
            val h = now.atZone(zone).hour
            return if (h in 7 until 19) SunPhase.DAY else SunPhase.NIGHT
        }
        return when {
            now.isBefore(sunrise.minus(twilight)) -> SunPhase.NIGHT
            now.isBefore(sunrise.plus(twilight)) -> SunPhase.DAWN
            now.isBefore(sunset.minus(twilight)) -> SunPhase.DAY
            now.isBefore(sunset.plus(twilight)) -> SunPhase.DUSK
            else -> SunPhase.NIGHT
        }
    }
}
