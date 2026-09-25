package it.apexweather.domain

import it.apexweather.domain.model.ConsensusHour
import it.apexweather.domain.model.StationObservation
import java.time.Duration
import java.time.Instant

/**
 * When the wind is worth mentioning on the home screen, and how strongly.
 *
 * **Gusts, not the mean wind**: a gust is what takes a parasol or a branch, and it is what the
 * Föhn brings down these valleys. The thresholds are Beaufort's, as the German weather service
 * uses them for its gust warnings: 50 km/h is force 7 ("starke Böen"), 75 km/h force 9
 * ("Sturmböen").
 *
 * **The median gust, never the maximum.** [ConsensusHour.gustKmh] is the highest gust any model
 * publishes, on purpose, for the hour sheet's "up to". Triggering on it would let the single most
 * alarmist of thirteen models put a wind line on the home screen by itself — the mistake the
 * chance of rain made while it was a maximum. [ConsensusHour.gustMedianKmh] is the weighted median,
 * and exists only where at least [MIN_SOURCES] models publish a gust: a median of two is one
 * model's opinion.
 */
object StrongWind {
    const val STRONG_KMH = 50.0
    const val STORM_KMH = 75.0

    /** Fewer models than this publishing a gust, and the hour has no median to trigger on. */
    const val MIN_SOURCES = 3

    /** How far ahead the hero looks. Past half a day it is the day list's business. */
    const val LOOKAHEAD_HOURS = 12L

    enum class Level { STRONG, STORM }

    /**
     * The hero's wind line: the first windy hour within [LOOKAHEAD_HOURS] and the peak gust over
     * the run of windy hours it starts. [from] is null when that run has already begun.
     */
    data class Line(val from: Instant?, val peakKmh: Double, val level: Level)

    fun levelOf(gustKmh: Double?): Level? = when {
        gustKmh == null -> null
        gustKmh >= STORM_KMH -> Level.STORM
        gustKmh >= STRONG_KMH -> Level.STRONG
        else -> null
    }

    fun levelOf(hour: ConsensusHour): Level? = levelOf(hour.gustMedianKmh)

    fun line(hours: List<ConsensusHour>, now: Instant): Line? {
        val until = now.plus(Duration.ofHours(LOOKAHEAD_HOURS))
        val ahead = hours.filter { it.time.plusSeconds(3600) > now && it.time <= until }
        val first = ahead.indexOfFirst { levelOf(it) != null }
        if (first < 0) return null
        val run = ahead.drop(first).takeWhile { levelOf(it) != null }
        val peak = run.maxOf { it.gustMedianKmh!! }
        val start = ahead[first].time
        return Line(from = start.takeIf { it > now }, peakKmh = peak, level = levelOf(peak)!!)
    }

    /**
     * The current hour with the station's measured gust in place of the models', in both
     * directions — a thermometer outranks the models about this minute, and so does an anemometer.
     * The current hour only, as with rain, fog and sun; a reading older than [FRESH_FOR] says
     * nothing about now.
     */
    fun withMeasured(hour: ConsensusHour, observation: StationObservation?, now: Instant): ConsensusHour {
        val gust = observation?.gustKmh ?: return hour
        if (Duration.between(observation.time, now) > FRESH_FOR) return hour
        return if (hour.gustMedianKmh == gust) hour else hour.copy(gustMedianKmh = gust)
    }

    val FRESH_FOR: Duration = Duration.ofMinutes(30)
}
