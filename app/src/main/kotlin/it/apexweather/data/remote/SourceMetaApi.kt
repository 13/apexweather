package it.apexweather.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import retrofit2.http.GET
import retrofit2.http.Url
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder

/**
 * When a model's current run started, as its provider publishes it — for the source sheet only.
 *
 * Asked for by absolute URL because the answers live on two hosts. Nothing here feeds the forecast,
 * the consensus or staleness: it is fetched when the reader opens a sheet, and a failure costs one
 * line of that sheet.
 */
interface SourceMetaApi {
    @GET
    suspend fun metadata(@Url url: String): JsonObject

    companion object {
        /** Retrofit wants a base; every call passes an absolute URL. */
        const val BASE_URL = "https://api.open-meteo.com/"

        fun openMeteoUrl(dataset: String): String = "https://api.open-meteo.com/data/$dataset/static/meta.json"

        /** The dataset the AROME forecast call itself uses (`GeoSphereApi`), not the grid one. */
        const val GEOSPHERE_AROME_URL = "https://dataset.api.hub.geosphere.at/v1/timeseries/forecast/nwp-v1-1h-2500m/metadata"
    }
}

/** A model's latest run. Every field may be missing; each is a line of its own on the sheet. */
data class SourceMeta(
    val runStartedAt: Instant?,
    val publishedAt: Instant?,
    val updateEvery: Duration?,
)

object SourceMetaMapper {

    /** Open-Meteo's `meta.json`: Unix seconds. `data_end_time` is deliberately not read — see SourceInfo. */
    fun openMeteo(json: JsonObject): SourceMeta = SourceMeta(
        runStartedAt = json.long("last_run_initialisation_time")?.let(Instant::ofEpochSecond),
        publishedAt = json.long("last_run_availability_time")?.let(Instant::ofEpochSecond),
        updateEvery = json.long("update_interval_seconds")?.takeIf { it > 0 }?.let(Duration::ofSeconds),
    )

    /**
     * GeoSphere's dataset metadata. It publishes reference times but no publication time, and its
     * cycle is the gap between the two newest reference times.
     */
    fun geoSphere(json: JsonObject): SourceMeta {
        val times = (json["available_forecast_reftimes"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(::parse) }
            .sortedDescending()
        val latest = (json["last_forecast_reftime"] as? JsonPrimitive)?.contentOrNull?.let(::parse) ?: times.firstOrNull()
        val every = if (times.size >= 2) Duration.between(times[1], times[0]).takeIf { !it.isNegative && !it.isZero } else null
        return SourceMeta(runStartedAt = latest, publishedAt = null, updateEvery = every)
    }

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    /** GeoSphere writes `2026-09-14T21:00+00:00`: an offset and no seconds. */
    private val TIME: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd'T'HH:mm")
        .optionalStart().appendPattern(":ss").optionalEnd()
        .appendOffset("+HH:MM", "Z")
        .toFormatter()

    private fun parse(text: String): Instant? = runCatching { OffsetDateTime.parse(text, TIME).toInstant() }.getOrNull()
}
