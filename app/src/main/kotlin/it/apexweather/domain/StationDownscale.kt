package it.apexweather.domain

import it.apexweather.data.remote.StationReference
import it.apexweather.domain.model.ConsensusForecast
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceForecast
import it.apexweather.domain.model.StationObservation
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Carries the weather station's live reading up the hill to the village.
 *
 * Every place in the catalogue is quoted a thermometer that stands somewhere else, and in this
 * province "somewhere else" is mostly a height. Dorf Tirol is the worked example: its station is in
 * Meran at 330 m, the village sits at about 600 m, 1.4 km away, and quoting the thermometer as the
 * village's temperature is wrong by however much those 270 m are worth at that moment — a median
 * 1.9 K across a two-day model run, up to 2.7 K on a clear afternoon, and as little as 0.3 K when
 * the valley is mixed. The catalogue keeps that difference under 400 m for every place; this is
 * what closes the rest of it.
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
     * The steepest a well-mixed column gets, in kelvin per metre. A dry adiabat is the ceiling on
     * what a height difference can honestly be worth.
     */
    private const val DRY_ADIABAT_K_PER_M = 0.0098

    /**
     * What a place whose station stands at its own altitude is still allowed, and what is added to
     * the adiabat everywhere else: the difference between two points is never only their heights —
     * a valley floor and a sunny shoulder differ by more than that, and so do two sides of a ridge.
     */
    private const val SLACK_C = 2.0

    /**
     * Beyond this the difference is not a height correction any more but a broken input — a stale
     * reference, a model that has gone strange — and the observation is better left alone.
     *
     * It has to scale with the height it is correcting for. A flat six degrees was both too tight
     * and too loose: too tight for the places whose nearest station used to stand the better part
     * of a kilometre below them, where every correct afternoon offset was thrown away and the hero
     * silently dropped back to the consensus; and too loose for the many places whose station is at
     * their own altitude, where six degrees of "height correction" is nothing of the kind.
     *
     * `tools/generate-places.py` now keeps the station within 400 m of the place, so in practice
     * this runs from 2 K to about 5,9 K.
     */
    private fun maxAdjustment(heightDifferenceM: Int): Double =
        SLACK_C + DRY_ADIABAT_K_PER_M * abs(heightDifferenceM)

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
     * a station some hundreds of metres below the village.
     *
     * [heightDifferenceM] is the place's altitude minus the station's, and is what bounds the
     * correction: see [maxAdjustment].
     */
    fun villageTemperature(
        observation: StationObservation,
        reference: StationReference?,
        village: Map<Source, SourceForecast>,
        consensus: ConsensusForecast,
        now: Instant,
        heightDifferenceM: Int,
    ): Double? {
        val observed = observation.tempC ?: return null
        val offset = offsetAt(observation.time, reference, village, now, heightDifferenceM) ?: return null
        val anomaly = stationAnomaly(observation.time, observed, reference, now)
        // The village the models draw, plus as much of the thermometer's disagreement with them as
        // is likely to be shared 270 m up the hill. At full trust this is exactly observed + offset,
        // which is what it always was.
        val moved = (observed + offset) - (anomaly ?: 0.0) * (1 - transferred(anomaly ?: 0.0))
        val forecast = consensus.hourly.firstOrNull { it.time == observation.time.truncatedTo(ChronoUnit.HOURS) }?.tempC
            ?: return moved
        // Never outside what its own two sources say. The app has exactly two views of the village:
        // the thermometer 270 m below it, and the models' own value for it. A result colder than
        // both, or warmer than both, is an assertion neither of them supports.
        //
        // That is what happened on 2026-09-11 at 05:00: the station read 12,9 and the models put the
        // village at 12,95, while their gap between the two points said -1,5 K, so the reading was
        // carried down to 11,4 and the screen led with a number nothing had measured or forecast.
        // The village's own thermometer read 12 to 13. The models do not resolve what this valley
        // does at night — they apply something like a lapse rate to a village that is warmer than
        // the valley floor under an inversion — and this is the guard against believing them.
        //
        // It costs the honest case nothing: an afternoon where the station really is the warmer of
        // the two leaves the moved reading inside the bracket, untouched.
        return moved.coerceIn(minOf(observed, forecast), maxOf(observed, forecast))
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
     * Village minus station, as the models see it at [time] — **each model against itself**, then the
     * median of those differences. Null when there is no model with a value at both points, or when
     * the answer is bigger than a height difference could explain.
     *
     * Asking each model about the hill and taking the median of the answers is not the same
     * arithmetic as taking the median at each point and subtracting, and the difference was a real
     * error on the app's most-read number. Subtracting two medians compared two quantities that
     * were never the same kind of thing:
     *
     * - **Different models.** The village side came from [ConsensusForecast], whose hourly values
     *   are a median over the *regional* sources only once two of them are present — SIAG KMOS and
     *   GeoSphere AROME in, both ECMWF runs out. The station side is [StationReference], which is
     *   the Open-Meteo call and therefore holds the eight Open-Meteo models, globals included and
     *   the two regionals absent. Whatever those two populations disagree about was being reported
     *   as the height of the hill.
     * - **Corrected against uncorrected.** The consensus has [BiasCorrector]'s per-model habit
     *   subtracted; the station reference has nothing subtracted, because nobody measures a habit at
     *   a point the app has no thermometer for. So the correction — up to three degrees of it —
     *   landed in the offset and was quoted to the reader as a lapse rate.
     *
     * Pairing each model with itself makes both go away: the models are the same on both sides by
     * construction, and a habit a model has here it has at both points, so it cancels in the
     * subtraction rather than being carried into it.
     *
     * [village] is the same set of runs the blend used — a run that has gone stale is kept out of
     * this exactly as it is kept out of the consensus.
     */
    fun offsetAt(
        time: Instant,
        reference: StationReference?,
        village: Map<Source, SourceForecast>,
        now: Instant,
        heightDifferenceM: Int,
    ): Double? {
        if (reference == null) return null
        if (ChronoUnit.HOURS.between(reference.fetchedAt, now) > REFERENCE_MAX_AGE_HOURS) return null
        val hour = time.truncatedTo(ChronoUnit.HOURS)
        // One model's own view of the hill, for every model that has both ends of it.
        val perModel = reference.at(hour).mapNotNull { (source, stationC) ->
            village[source]?.hourly
                ?.firstOrNull { it.time.truncatedTo(ChronoUnit.HOURS) == hour }
                ?.tempC?.minus(stationC)
        }
        if (perModel.isEmpty()) return null
        val offset = ConsensusBlender.median(perModel)
        return if (abs(offset) > maxAdjustment(heightDifferenceM)) null else offset
    }
}
