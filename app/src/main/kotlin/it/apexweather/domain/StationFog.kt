package it.apexweather.domain

import it.apexweather.domain.model.ConsensusHour
import it.apexweather.domain.model.StationObservation
import java.time.Duration
import java.time.Instant

/**
 * Whether the weather station is standing in saturated air right now.
 *
 * This exists because of an evening in Dorf Tirol on 2026-09-10 when it was foggy outside and not
 * one of the app's ten sources said so — eight Open-Meteo models returned overcast or drizzle, the
 * three that publish visibility said 12 to 29 km, and SIAG KMOS, which has codes for both Hochnebel
 * and Talnebel, returned "Bedeckt, mäßiger Regen". The one number that disagreed with all of them
 * was the station's: 100 % relative humidity.
 *
 * **What this is not.** It is not a fog detector. The station sits 270 m below the village and
 * publishes no visibility — SIAG's station rows carry temperature, humidity, wind, gust, pressure
 * and an accumulated precipitation total, and nothing else — and valley fog often lies *below* a
 * village on the shoulder rather than over it. Worse, rain saturates the air just as fog does, so
 * a saturated reading on its own means very little.
 *
 * It is used two ways, both narrow:
 *
 * - as a **tiebreaker**, lowering the bar for fog a source has already forecast from a third of them
 *   down to one — see `ConsensusBlender.voteCondition`;
 * - and, in [impliesFog], as evidence in its own right, but only under the same conditions the AROME
 *   mapper uses to call fog from a *modelled* humidity: a covered sky and nothing much falling. It
 *   is the same rule with a measured number instead of a forecast one, which is exactly what
 *   [StationDownscale] already does for temperature.
 */
object StationFog {

    /** Not 100: a capacitive hygrometer rarely reads it, and 97 % is already cloud on the ground. */
    const val SATURATED_RH_PCT = 97

    /**
     * The same ninety minutes the hero temperature uses. An observation older than that is still
     * worth showing on the station card with its timestamp beside it, but it is no longer evidence
     * about what the sky is doing this minute.
     */
    val FRESH_FOR: Duration = Duration.ofMinutes(90)

    fun saturated(observation: StationObservation?, now: Instant): Boolean {
        val rh = observation?.humidityPct ?: return false
        if (Duration.between(observation.time, now) > FRESH_FOR) return false
        return rh >= SATURATED_RH_PCT
    }

    /**
     * The measured air is saturated, the sky above is covered, and nothing to speak of is falling:
     * cloud at ground level.
     *
     * Each clause carries its weight. Without the covered sky this fires on a clear humid dawn;
     * without the dry clause it fires on every shower, because rain saturates the air just as fog
     * does, and being told "Nebel" in a downpour is the same error facing the other way.
     *
     * It can be wrong. The station is 270 m below the village and valley fog often lies below a
     * shoulder rather than over it, so this says the air the app can *measure* is saturated under
     * cloud, and infers the village is in it. On 2026-09-10 that inference was right and all ten
     * forecasts were wrong.
     */
    fun impliesFog(observation: StationObservation?, now: Instant, hour: ConsensusHour?): Boolean {
        if (hour == null || !saturated(observation, now)) return false
        if (hour.precipMm > FOG_LOSES_ABOVE_MM) return false
        val clouds = hour.perSource.values.mapNotNull { it.cloudPct }
        if (clouds.isEmpty() || clouds.average() < COVERED_PCT) return false
        val humidity = hour.perSource.mapNotNull { (s, p) -> p.humidityPct?.let { s to it.toDouble() } }.toMap()
        return humidity.isNotEmpty() && ConsensusBlender.weightedMedian(humidity) >= VILLAGE_HUMID_PCT
    }

    /** Fog is cloud on the ground, so the sky has to be covered. Matches the AROME mapper's 0.9. */
    private const val COVERED_PCT = 90.0

    /**
     * The station's saturation has to be at least plausible up at the village, by the models' own
     * account of the village's air.
     *
     * The station is on the valley floor and the village is not, and a saturated thermometer 264 m
     * down says nothing on its own about the shoulder above it. On 2026-09-14 at 00:40 Meran read
     * 99 % under an overcast sky, the screen said "Nebel" over Dorf Tirol, and there was no fog:
     * twelve models put the village at 54 to 79 %, median 65, with 12 to 42 km of visibility. On the
     * foggy evening this object was written for, the same median ran 82 to 94 % from 15:00 to
     * 23:00. Eighty sits under the whole of the real case and well over the false one — two
     * evenings, so a judgement rather than a calibration. Not higher: the models did not see that
     * fog either, and a bar they would have to reach on their own would make the station pointless.
     */
    private const val VILLAGE_HUMID_PCT = 80.0

    /** Above this the hour is a rain hour, and rain is the more useful thing to be told. */
    private const val FOG_LOSES_ABOVE_MM = 0.5
}
