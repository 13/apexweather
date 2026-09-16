package it.apexweather.data.remote

import it.apexweather.domain.SouthTyrol
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.Instant

/**
 * An ensemble: the same model run many times from slightly different starting points.
 *
 * The consensus spread this app already shows measures how far several models disagree, which is a
 * proxy for uncertainty. An ensemble measures the thing itself — how much the forecast moves when
 * the atmosphere it started from is nudged within the error bars of what was observed. On a settled
 * day the members sit on top of each other; before a front they fan out.
 *
 * Two are asked for, because they cover different halves of the list:
 *
 *  - **ICON-D2**, twenty members at 2 km, two days. The better ensemble for this terrain, and the
 *    one that wins wherever it reaches. One variable, two days: 1.8 kB.
 *  - **ECMWF IFS ENS**, fifty members at 25 km, fifteen days. It is here for the far end of the day
 *    list, which until now had no measure of uncertainty at all: only ECMWF's deterministic run
 *    reaches past about day five, so those days carried one model, no spread, and a grey badge
 *    reading "1 model" because there was genuinely nothing to compare. Fifty members of that same
 *    model are something to compare — they are what the forecast's own confidence looks like — and
 *    at 23 kB gzipped over fifteen days it is the cheapest honest number available for a Thursday
 *    next week.
 */
interface EnsembleApi {
    @GET("v1/ensemble")
    suspend fun forecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("models") models: String = ICON_D2,
        @Query("forecast_days") forecastDays: Int = ICON_D2_DAYS,
        @Query("timezone") timezone: String = SouthTyrol.ZONE.id,
        @Query("hourly") hourly: String = HOURLY_VARS,
    ): EnsembleResponse

    companion object {
        const val BASE_URL = "https://ensemble-api.open-meteo.com/"

        /** Two days is as far as ICON-D2's ensemble runs. */
        const val ICON_D2 = "icon_d2"
        const val ICON_D2_DAYS = 2

        /** Fifteen, which is one day more than the day list shows. */
        const val ECMWF_ENS = "ecmwf_ifs025"
        const val ECMWF_ENS_DAYS = 15

        /**
         * Temperature for the spread, precipitation for the chance of rain.
         *
         * The second is what an ensemble is actually *for*. A probability of precipitation built
         * from deterministic models is an average of eleven opinions about a chance; built from an
         * ensemble it is a counted frequency — how many of fifty equally likely atmospheres got
         * wet. Measured on the live API: precipitation costs 225 B gzipped on ICON-D2's two days
         * and 3 238 B on ECMWF's fifteen, against 1 834 B and 23 939 B for temperature alone.
         */
        const val HOURLY_VARS = "temperature_2m,precipitation"
    }
}

@Serializable
data class EnsembleResponse(
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int = 0,
    val hourly: JsonObject,
)

/**
 * How far the ensemble members spread at each hour, as a half-width in degrees.
 *
 * The tenth to ninetieth percentile rather than the full range: a single member wandering off is a
 * member wandering off, not the forecast being uncertain, and the badge should not widen because of
 * one.
 */
@Serializable
data class EnsembleSpread(
    @Serializable(with = it.apexweather.domain.model.InstantSerializer::class) val fetchedAt: Instant,
    val memberCount: Int,
    /** Epoch second → half the tenth-to-ninetieth percentile range. */
    val halfWidthByEpochSecond: Map<Long, Double> = emptyMap(),
    /**
     * Epoch second → the share of members, 0..1, that got wet in that hour.
     *
     * This is the thing an ensemble is for. Every other probability in this app is an average of
     * what several deterministic models each *say* the chance is; this is a count of how many of
     * fifty equally plausible atmospheres actually rained. Defaulted empty so a row cached by a
     * build that did not fetch precipitation still decodes.
     */
    val wetShareByEpochSecond: Map<Long, Double> = emptyMap(),
) {
    fun halfWidthAt(t: Instant): Double? =
        halfWidthByEpochSecond[t.truncatedTo(java.time.temporal.ChronoUnit.HOURS).epochSecond]

    /** The measured chance of precipitation at [t], 0..1, where an ensemble reaches it. */
    fun wetShareAt(t: Instant): Double? =
        wetShareByEpochSecond[t.truncatedTo(java.time.temporal.ChronoUnit.HOURS).epochSecond]

    companion object {
        /**
         * [near] wherever it reaches, [far] beyond it — for the spread. The chance of rain pools.
         *
         * ICON-D2 at 2 km says more about an Alpine valley tomorrow than ECMWF at 25 km does, so its
         * spread wins every hour both cover; ECMWF's fifty members carry the rest of the fortnight.
         * The member count reported is the one whose numbers are used at the near end, because that
         * is the ensemble a reader is looking at when they open the app.
         *
         * **The wet share is the mean of the two where both reach, one vote per ensemble.** Twenty
         * members of one model measure how that model's rain moves when its start is nudged, not
         * whether the model has the timing of a front wrong — and when it has, all twenty have it
         * wrong together. On 2026-09-16 over Dorf Tirol ICON-D2 had the evening's rain late: 15 %
         * of its members wet at 19:00 against 100 % of ECMWF's, ten of eleven deterministic models
         * raining and the province's own KMOS at 65 %, and the strip printed 16 % under 2,3 mm.
         * Per ensemble rather than per member, or ECMWF's fifty would outvote the finer run.
         */
        fun combine(near: EnsembleSpread?, far: EnsembleSpread?): EnsembleSpread? = when {
            near == null || near.halfWidthByEpochSecond.isEmpty() -> far
            far == null -> near
            else -> near.copy(
                halfWidthByEpochSecond = far.halfWidthByEpochSecond + near.halfWidthByEpochSecond,
                wetShareByEpochSecond = far.wetShareByEpochSecond +
                    near.wetShareByEpochSecond.mapValues { (t, share) ->
                        far.wetShareByEpochSecond[t]?.let { (share + it) / 2.0 } ?: share
                    },
            )
        }
    }
}

object EnsembleMapper {
    private const val TEMP_PREFIX = "temperature_2m_member"
    private const val PRECIP_PREFIX = "precipitation_member"

    /** The millimetres in an hour at which a member counts as wet; the app's own threshold. */
    private const val MEMBER_WET_MM = 0.1

    /** Fewer members than this reaching an hour and it is not an ensemble, it is a couple of runs. */
    private const val MIN_MEMBERS = 5

    fun map(resp: EnsembleResponse, fetchedAt: Instant): EnsembleSpread {
        val times = resp.hourly.strings("time").map { parseLocal(it!!, SouthTyrol.ZONE) }
        fun members(prefix: String) =
            resp.hourly.keys.filter { it.startsWith(prefix) }.sorted().map { resp.hourly.doubles(it) }
        val temps = members(TEMP_PREFIX)
        if (temps.isEmpty()) return EnsembleSpread(fetchedAt, 0)
        val precip = members(PRECIP_PREFIX)

        val byHour = times.indices.mapNotNull { i ->
            val values = temps.mapNotNull { it.getOrNull(i) }.sorted()
            // Two members is not an ensemble; an hour the run does not reach contributes nothing.
            if (values.size < MIN_MEMBERS) return@mapNotNull null
            val low = values[(values.size * 0.1).toInt()]
            val high = values[((values.size - 1) * 0.9).toInt()]
            times[i].epochSecond to (high - low) / 2.0
        }.toMap()

        // The share of members that got wet, which is a counted frequency rather than an average of
        // opinions. An hour too few members reach is absent rather than reported as dry.
        val wet = times.indices.mapNotNull { i ->
            val values = precip.mapNotNull { it.getOrNull(i) }
            if (values.size < MIN_MEMBERS) return@mapNotNull null
            times[i].epochSecond to values.count { it >= MEMBER_WET_MM }.toDouble() / values.size
        }.toMap()

        return EnsembleSpread(fetchedAt, temps.size, byHour, wet)
    }
}
