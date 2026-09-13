package it.apexweather.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
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
    fun elevationDegrees(at: Instant, lat: Double, lon: Double): Double =
        position(at, lat, lon).elevationDeg

    /**
     * Where on the compass the sun stands, in degrees clockwise from true north.
     *
     * The other half of a solar position, and the half nothing needed until the app learnt that its
     * horizon is made of mountains: an elevation says how high the sun is, and only an azimuth says
     * which piece of skyline it has to clear. See [Horizon].
     */
    fun azimuthDegrees(at: Instant, lat: Double, lon: Double): Double =
        position(at, lat, lon).azimuthDeg

    /** Elevation above the horizon and bearing from true north, both in degrees. */
    private data class SolarPosition(val elevationDeg: Double, val azimuthDeg: Double)

    /**
     * NOAA's own approximation, good to a fraction of a degree and needing no ephemeris.
     *
     * Both angles come out of one calculation because they share every input: splitting them into
     * two functions would have the declination and the hour angle computed twice for the same
     * instant, and — worse — let one of them be edited without the other.
     */
    private fun position(at: Instant, lat: Double, lon: Double): SolarPosition {
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
        val elevation = asin(sinElevation.coerceIn(-1.0, 1.0))

        // NOAA's azimuth, measured from the zenith angle. At the poles of the expression — the sun
        // exactly overhead, or a latitude of exactly ninety degrees — the denominator vanishes and
        // there is no bearing to give; due south is the answer that is wrong by the least, and this
        // province is nowhere near either case.
        val zenith = PI / 2.0 - elevation
        val denominator = cos(latitude) * sin(zenith)
        val azimuth = if (abs(denominator) < 1e-9) 180.0 else {
            val cosAzimuth = (sin(latitude) * cos(zenith) - sin(declination)) / denominator
            val fromNorth = Math.toDegrees(acos(cosAzimuth.coerceIn(-1.0, 1.0)))
            // acos cannot tell morning from afternoon; the hour angle can.
            if (hourAngle > 0) (fromNorth + 180.0) % 360.0 else (540.0 - fromNorth) % 360.0
        }
        return SolarPosition(Math.toDegrees(elevation), azimuth)
    }

    private val twilight: Duration = Duration.ofMinutes(45)

    /**
     * The phase of the day, from the astronomical sun times alone — a flat horizon, which is the
     * horizon of somewhere with no mountains in it.
     *
     * Kept for the two callers that have nothing better: a catalogue written before the skylines
     * existed, and any place whose profile did not survive [Horizon.usable].
     */
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

    /**
     * The same, once it is known what time the sun actually clears and loses the ridge.
     *
     * The two kinds of sun time answer two different questions and this is the one place both are
     * needed at once, so neither may simply replace the other:
     *
     * - **The terrain times bound the day.** It is not day here while the village is in the shadow
     *   of the Texelgruppe, whatever the ephemeris says — and in Dorf Tirol that is 70 minutes each
     *   morning and 54 each evening in September, 86 in the evening at the solstice. Measured, not
     *   estimated: see `tools/horizons.py`.
     * - **The astronomical times bound the night.** The sky overhead is not dark the moment the sun
     *   goes behind a mountain; it is lit for the better part of an hour more, and this app draws a
     *   *sky*. Ending the day at the terrain sunset and then treating the next forty-five minutes as
     *   the end of everything would paint the screen night while it is plainly blue outside — the
     *   same error as the current one, in the other direction and slightly larger.
     *
     * So day is when the sun is actually on the place *and* the ephemeris agrees it is well up,
     * night is exactly what it always was, and the twilight either side stretches to cover the
     * difference. Where a place has no ridge worth the name the two pairs coincide and every instant
     * falls in the phase [phase] would have given it — which is pinned below, because a change that
     * quietly moved the colours of an open plain would be a bug rather than this feature.
     */
    fun phase(
        now: Instant,
        sunrise: Instant?,
        sunset: Instant?,
        zone: ZoneId,
        visibleSunrise: Instant?,
        visibleSunset: Instant?,
    ): SunPhase {
        if (sunrise == null || sunset == null) return phase(now, sunrise, sunset, zone)
        if (visibleSunrise == null || visibleSunset == null) return phase(now, sunrise, sunset, zone)
        // A ridge can only ever delay the morning and hurry the evening, so each boundary is the
        // later or earlier of the two candidates. That is also what makes this degrade *exactly*:
        // with no ridge the terrain times are the astronomical ones, both maxOf/minOf pick the
        // twilight-shifted value, and every instant lands in the phase [phase] would have given it.
        val dayFrom = maxOf(visibleSunrise, sunrise.plus(twilight))
        // ...and never before the day began, for the valley that sees the sun for an hour in
        // December: DAY is then empty rather than inverted, and the day runs dawn straight to dusk.
        val dayTo = maxOf(dayFrom, minOf(visibleSunset, sunset.minus(twilight)))
        return when {
            now.isBefore(sunrise.minus(twilight)) -> SunPhase.NIGHT
            now.isBefore(dayFrom) -> SunPhase.DAWN
            now.isBefore(dayTo) -> SunPhase.DAY
            now.isBefore(sunset.plus(twilight)) -> SunPhase.DUSK
            else -> SunPhase.NIGHT
        }
    }
}
