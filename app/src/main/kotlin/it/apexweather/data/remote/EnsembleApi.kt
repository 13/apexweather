package it.apexweather.data.remote

import it.apexweather.domain.SouthTyrol
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.Instant

/**
 * ICON-D2's ensemble: the same model run twenty times from slightly different starting points.
 *
 * The consensus spread this app already shows measures how far several models disagree, which is a
 * proxy for uncertainty. An ensemble measures the thing itself — how much the forecast moves when
 * the atmosphere it started from is nudged within the error bars of what was observed. On a settled
 * day the members sit on top of each other; before a front they fan out.
 *
 * Two days only, which is as far as ICON-D2's ensemble runs, and one variable. That is 1.8 kB.
 */
interface EnsembleApi {
    @GET("v1/ensemble")
    suspend fun forecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("timezone") timezone: String = SouthTyrol.ZONE.id,
        @Query("forecast_days") forecastDays: Int = 2,
        @Query("models") models: String = "icon_d2",
        @Query("hourly") hourly: String = "temperature_2m",
    ): EnsembleResponse

    companion object { const val BASE_URL = "https://ensemble-api.open-meteo.com/" }
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
