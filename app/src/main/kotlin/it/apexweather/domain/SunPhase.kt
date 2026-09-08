package it.apexweather.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

enum class SunPhase { NIGHT, DAWN, DAY, DUSK }

object SunPhaseCalculator {
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
