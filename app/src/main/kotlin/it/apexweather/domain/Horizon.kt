package it.apexweather.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The skyline around a point, and what the sun does behind it.
 *
 * Everywhere in this province the astronomical sunrise and sunset are the wrong times, and not by a
 * little. Dorf Tirol has the Texelgruppe standing 19° above its northern horizon and the far side of
 * the Vinschgau 24° above its western one; the sun is off the village well before it is off the
 * ephemeris. A flat horizon is the horizon of a place with no mountains in it, which is not a
 * description of anywhere the 116 municipalities in this catalogue actually are.
 *
 * The profile is measured once, offline, from a 30 m digital elevation model — see
 * `tools/horizons.py`, which also explains why it is five degrees of bearing and what that costs.
 * It is stored as tenths of a degree so that a whole province's skylines add about 90 kB to an asset
 * that is read once at startup.
 *
 * Three things read it and each gets something different:
 *
 * - the sky's own colours, which used to turn to dusk while the village had been in shadow an hour;
 * - [StationSun], which had to forbid itself from ever concluding cloud from a dark pyranometer,
 *   because with a flat horizon a station in shade and a station under cloud are the same reading;
 * - the sun times the reader is shown, which are the ones they can check by looking out of a window.
 *
 * It is a *terrain* horizon and nothing more. The DEM is bare earth, so the tree on the ridge and
 * the house across the road are not in it, and a village that sits in the lee of its own church
 * tower will still be told the sun is up. Those are minutes; the ridge is an hour.
 */
object Horizon {

    /** Degrees of bearing between samples, and the reason a profile is 72 long. */
    const val BEARING_STEP_DEG = 5

    /** How many samples a whole turn of the compass takes. */
    const val BEARINGS = 360 / BEARING_STEP_DEG

    /**
     * A profile is only usable if it is the length the generator writes. A catalogue from an older
     * build has no `horizon` at all, and one with the wrong number of bearings is a generator that
     * changed underneath the app — in both cases the honest answer is the flat horizon the app
     * always used, not a profile read at the wrong stride.
     */
    fun usable(profile: List<Int>?): Boolean = profile != null && profile.size == BEARINGS

    /**
     * How high the skyline stands at [azimuthDeg], in degrees, interpolated between the two sampled
     * bearings either side.
     *
     * Interpolated rather than rounded to the nearer sample because the sun crosses five degrees of
     * azimuth in twenty minutes, and rounding would quantise every sunset to that. A ridge is not
     * linear between two bearings, but it is much closer to linear than to a step.
     */
    fun elevationAt(profile: List<Int>, azimuthDeg: Double): Double {
        val turns = ((azimuthDeg % 360.0) + 360.0) % 360.0 / BEARING_STEP_DEG
        val low = turns.toInt() % BEARINGS
        val high = (low + 1) % BEARINGS
        val fraction = turns - turns.toInt()
        return (profile[low] + (profile[high] - profile[low]) * fraction) / 10.0
    }

    /** Whether the sun is above the skyline at [at], as seen from this point. */
    fun sunIsUp(profile: List<Int>, at: Instant, lat: Double, lon: Double): Boolean {
        val elevation = SunPhaseCalculator.elevationDegrees(at, lat, lon)
        // Below the flat horizon there is nothing to test against a ridge, and the azimuth of a sun
        // well below the horizon is not worth computing.
        if (elevation <= 0.0) return false
        return elevation > elevationAt(profile, SunPhaseCalculator.azimuthDegrees(at, lat, lon))
    }

    /** How finely the day is walked when looking for the moment the sun clears or loses the ridge. */
    private val STEP: Duration = Duration.ofMinutes(2)

    /**
     * When the sun first clears the skyline on [date], and when it last stands above it — or null
     * for a day it never does at all, which in this province is a real answer in a deep valley in
     * December and not an error.
     *
     * The day is walked in two-minute steps rather than solved, because the thing being solved for
     * is a piecewise-linear ridge rather than a smooth function: a valley with a notch in its wall
     * can have the sun set, rise and set again, and a root-finder would confidently report one of
     * those. Two minutes over a day is 720 evaluations of a closed-form solar position, which is
     * nothing, and it is done for one or two days rather than fourteen.
     *
     * The pair is first-up and last-up, so a notch is spanned rather than reported. That is the
     * honest simplification: "the sun is about between these two times" is true, where "the sun sets
     * at 12:40 and rises again at 13:10" is true and useless.
     */
    fun visibleDaylight(
        profile: List<Int>,
        date: LocalDate,
        zone: ZoneId,
        lat: Double,
        lon: Double,
    ): Pair<Instant, Instant>? {
        var first: Instant? = null
        var last: Instant? = null
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        var t = start
        while (t.isBefore(end)) {
            if (sunIsUp(profile, t, lat, lon)) {
                if (first == null) first = t
                last = t
            }
            t = t.plus(STEP)
        }
        val from = first ?: return null
        return from to (last ?: from)
    }
}
