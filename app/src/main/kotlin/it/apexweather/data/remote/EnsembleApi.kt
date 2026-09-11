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
        @Query("hourly") hourly: String = "temperature_2m",
    ): EnsembleResponse

    companion object {
        const val BASE_URL = "https://ensemble-api.open-meteo.com/"

        /** Two days is as far as ICON-D2's ensemble runs. */
        const val ICON_D2 = "icon_d2"
        const val ICON_D2_DAYS = 2

        /** Fifteen, which is one day more than the day list shows. */
        const val ECMWF_ENS = "ecmwf_ifs025"
        const val ECMWF_ENS_DAYS = 15
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
) {
    fun halfWidthAt(t: Instant): Double? =
        halfWidthByEpochSecond[t.truncatedTo(java.time.temporal.ChronoUnit.HOURS).epochSecond]

    companion object {
        /**
         * [near] wherever it reaches, [far] beyond it.
         *
         * ICON-D2 at 2 km says more about an Alpine valley tomorrow than ECMWF at 25 km does, so it
         * wins every hour both cover; ECMWF's fifty members carry the rest of the fortnight. The
         * member count reported is the one whose numbers are used at the near end, because that is
         * the ensemble a reader is looking at when they open the app.
         */
        fun combine(near: EnsembleSpread?, far: EnsembleSpread?): EnsembleSpread? = when {
            near == null || near.halfWidthByEpochSecond.isEmpty() -> far
            far == null -> near
            else -> near.copy(
                halfWidthByEpochSecond = far.halfWidthByEpochSecond + near.halfWidthByEpochSecond,
            )
        }
    }
}

object EnsembleMapper {
    private const val MEMBER_PREFIX = "temperature_2m_member"

    fun map(resp: EnsembleResponse, fetchedAt: Instant): EnsembleSpread {
        val times = resp.hourly.strings("time").map { parseLocal(it!!, SouthTyrol.ZONE) }
        val members = resp.hourly.keys.filter { it.startsWith(MEMBER_PREFIX) }.sorted()
            .map { resp.hourly.doubles(it) }
        if (members.isEmpty()) return EnsembleSpread(fetchedAt, 0)

        val byHour = times.indices.mapNotNull { i ->
            val values = members.mapNotNull { it.getOrNull(i) }.sorted()
            // Two members is not an ensemble; an hour the run does not reach contributes nothing.
            if (values.size < 5) return@mapNotNull null
            val low = values[(values.size * 0.1).toInt()]
            val high = values[((values.size - 1) * 0.9).toInt()]
            times[i].epochSecond to (high - low) / 2.0
        }.toMap()
        return EnsembleSpread(fetchedAt, members.size, byHour)
    }
}
