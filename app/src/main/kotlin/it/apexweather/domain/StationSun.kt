package it.apexweather.domain

import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.ConsensusHour
import it.apexweather.domain.model.StationObservation
import java.time.Duration
import java.time.Instant
import kotlin.math.sin

/**
 * Whether the sun is plainly out, measured, against a forecast that says otherwise.
 *
 * This is [StationFog]'s mistake facing the other way. On 2026-09-11 at 13:00 the app led with
 * "Bedeckt" over Dorf Tirol while the sun shone out of a nearly clear sky, and it was not wrong
 * about its sources: of the six regional models, ICON-CH1, ICON-D2 and DMI HARMONIE all called it
 * overcast — two of them at 100 % cloud — ICON-CH2 and ICON-2I said partly cloudy, and only KNMI
 * saw the actual sky at 14 %. Three votes to two to one, and the consensus reported them faithfully.
 *
 * The station a kilometre and a half away was measuring 834 W/m² at the time, against about 750 for
 * a cloudless sky at that sun height, and had recorded five and a half hours of sunshine that day.
 * SIAG publishes that as `gs` on the same row the app already reads temperature and humidity from,
 * and 49 of the 57 stations report it. It was simply being thrown away.
 *
 * **Four rules, and each one is load-bearing.**
 *
 * *It may only make the sky less cloudy, never more.* In a province of deep valleys a station loses
 * the sun behind a ridge long before the sky clouds over, so a low reading means shade at least as
 * often as cloud — across the network on that same clear afternoon the index ran from 0,09 at
 * Salurn to 1,19 at Ulten Weißbrunn. Read downwards it is a measurement; read upwards it would be
 * a guess about terrain.
 *
 * *The current hour only*, exactly as [StationFog.impliesFog]. What the sky is doing now is no
 * evidence about five o'clock, and the forty-eight-hour strip must not be coloured by it.
 *
 * *Dry hours only.* Sun and rain coexist — a bright shower is an ordinary thing here — and the
 * amount of rain is a separate number that this must not touch.
 *
 * *It beats fog.* 834 W/m² is not fog, whatever the humidity says.
 *
 * It can still be wrong, in one specific way: the station is 270 m below the village and a cloud
 * bank can sit on the slope while the valley floor is in sun. That is the same bet [StationFog]
 * makes in the other direction, and the same one [StationDownscale] makes about temperature.
 */
object StationSun {

    /** The same ninety minutes the hero temperature and [StationFog] use. */
    val FRESH_FOR: Duration = Duration.ofMinutes(90)

    /**
     * Below this the denominator is small enough that ordinary haze, a ridge, or a minute's error
     * in the sun's position swamp the answer. Nothing is concluded near sunrise, sunset or at night.
     */
    const val MIN_ELEVATION_DEG = 10.0

    /**
     * A plain clear-sky model: the solar constant knocked down by what a clean atmosphere lets
     * through, times the sine of the sun's height.
     *
     * It is deliberately crude, because the thresholds below are calibrated against it rather than
     * against theory. Measured across the whole station network on a clear afternoon it puts sunny
     * places at 1,06 to 1,19 rather than at 1,00 — so the bar for "clear" sits at
     * [CLEAR_INDEX], not at unity, and moving this constant means re-measuring those.
     */
    private const val SOLAR_CONSTANT_WM2 = 1361.0
    private const val ATMOSPHERIC_TRANSMITTANCE = 0.75

    /** At or above this the sky is clear or nearly so. */
    const val CLEAR_INDEX = 0.80

    /** And above this there is real sun getting through, whatever the models say. */
    const val PARTLY_INDEX = 0.50

    /** Rain at or above this is left alone: a bright shower is still a shower. */
    const val WET_ABOVE_MM = 0.2

    /**
     * How much of the expected cloudless sunlight is actually arriving, or null where the question
     * cannot be asked — no reading, a stale one, or the sun too low to divide by.
     */
    fun clearSkyIndex(
        observation: StationObservation?,
        lat: Double,
        lon: Double,
        now: Instant,
    ): Double? {
        val measured = observation?.radiationWm2 ?: return null
        if (Duration.between(observation.time, now) > FRESH_FOR) return null
        val elevation = SunPhaseCalculator.elevationDegrees(observation.time, lat, lon)
        if (elevation < MIN_ELEVATION_DEG) return null
        val clearSky = SOLAR_CONSTANT_WM2 * ATMOSPHERIC_TRANSMITTANCE * sin(Math.toRadians(elevation))
        if (clearSky <= 0.0) return null
        return measured / clearSky
    }

    /**
     * The cloudiest the sky can honestly be called right now, or null where the measurement has
     * nothing to say — which is most of the time, and always at night.
     *
     * Returns a *ceiling*, not an answer: the caller keeps whatever the models said unless they said
     * something cloudier than this.
     */
    fun cloudCeiling(
        observation: StationObservation?,
        lat: Double,
        lon: Double,
        now: Instant,
        hour: ConsensusHour?,
    ): Condition? {
        if (hour == null || hour.precipMm >= WET_ABOVE_MM) return null
        val index = clearSkyIndex(observation, lat, lon, now) ?: return null
        return when {
            index >= CLEAR_INDEX -> Condition.MOSTLY_CLEAR
            index >= PARTLY_INDEX -> Condition.PARTLY_CLOUDY
            else -> null
        }
    }

    /**
     * [voted] held to what the sunlight allows.
     *
     * Only ever lightens: a condition already clearer than the ceiling is left exactly as it is, and
     * so is anything wet. Because [Condition]'s declaration order runs from clear to severe, "no
     * cloudier than" is a comparison rather than a table.
     */
    fun corrected(
        voted: Condition,
        observation: StationObservation?,
        lat: Double,
        lon: Double,
        now: Instant,
        hour: ConsensusHour?,
    ): Condition {
        // Precipitation is none of this rule's business, and neither is anything else past it in
        // the severity order — thunder included.
        if (voted.isPrecipitation) return voted
        val ceiling = cloudCeiling(observation, lat, lon, now, hour) ?: return voted
        return if (voted.ordinal > ceiling.ordinal) ceiling else voted
    }
}
