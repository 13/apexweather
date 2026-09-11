package it.apexweather.data.remote

import it.apexweather.domain.Place
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

/**
 * GeoSphere's INCA nowcast: where the rain will be, rather than where it has been.
 *
 * The radar loop answers half of what a reader wants from a map. The other half — is that shower
 * going to reach me — it cannot answer at all: RainViewer's own `nowcast` array has been empty every
 * time it was looked at. This is the forecast that fills it, and it is the right one for this
 * province: 1 km, quarter-hourly, two and a half hours ahead, built by blending radar and station
 * observations into the ALARO model rather than by extrapolating the last two radar frames.
 *
 * **It is asked for a box around the reader's place, not for the province**, and that is the whole
 * reason it is affordable. A South Tyrol bounding box is 4.7 MB of uncompressed GeoJSON — the
 * service does not gzip, and its 46 kB NetCDF alternative is HDF5, which nothing on Android reads
 * without a library larger than this app. A box [Place.nowcastBox] wide is about 300 kB, fetched at
 * most once every ten minutes, which is the same order as the fourteen-day forecast the app already
 * pulls on every refresh.
 *
 * The grid is a projected 1 km one, so no two points share a latitude and the lat/lon lattice is
 * not rectangular. Cells are therefore drawn one at a time rather than as rows.
 */
interface NowcastApi {
    @GET("v1/grid/forecast/nowcast-v1-15min-1km")
    suspend fun precipitation(
        @Query("bbox") bbox: String,
        @Query("parameters") parameters: String = "rr",
        @Query("output_format") outputFormat: String = "geojson",
    ): NowcastResponse

    /**
     * The rest of the day, from AROME on the same grid service.
     *
     * INCA stops two and a half hours out, and "will it rain this evening" is a map question too.
     * This is the 2,5 km run rather than the 1 km one on purpose: over a box around the place the
     * kilometre grid is 488 kB for a day and this is 117 kB, for a resolution still finer than the
     * radar's own at the zooms anyone looks at. The near hours keep INCA's kilometre and its
     * quarter hours, which is where resolution actually buys something.
     *
     * `rr_acc` is accumulated from the run's start, so consecutive steps are differenced — the same
     * shape `GeoSphereMapper` already handles for the village.
     */
    @GET("v1/grid/forecast/nwp-v1-1h-2500m")
    suspend fun outlook(
        @Query("bbox") bbox: String,
        @Query("end") end: String,
        @Query("parameters") parameters: String = "rr_acc",
        @Query("output_format") outputFormat: String = "geojson",
    ): NowcastResponse

    companion object {
        const val BASE_URL = "https://dataset.api.hub.geosphere.at/"

        /** How far the map's forecast reaches. */
        const val OUTLOOK_HOURS = 24L

        /** The `end` this service wants: local-ish ISO minutes, no offset and no seconds. */
        fun endOf(now: Instant): String = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
            .withZone(java.time.ZoneOffset.UTC)
            .format(now.plusSeconds(OUTLOOK_HOURS * 3600))

        /** `bbox` wants south,west,north,east. */
        fun boxAround(place: Place): String {
            val dLat = Place.NOWCAST_BOX_DEG_LAT
            val dLon = Place.NOWCAST_BOX_DEG_LON
            return "%.4f,%.4f,%.4f,%.4f".format(
                java.util.Locale.ROOT,
                place.lat - dLat, place.lon - dLon, place.lat + dLat, place.lon + dLon,
            )
        }
    }
}

@Serializable
data class NowcastResponse(
    @SerialName("reference_time") val referenceTime: String = "",
    val timestamps: List<String> = emptyList(),
    val features: List<NowcastFeature> = emptyList(),
)

@Serializable
data class NowcastFeature(
    val geometry: NowcastGeometry = NowcastGeometry(),
    val properties: NowcastProperties = NowcastProperties(),
)

/** GeoJSON order: longitude first. */
@Serializable
data class NowcastGeometry(val coordinates: List<Double> = emptyList())

@Serializable
data class NowcastProperties(val parameters: Map<String, NowcastParameter> = emptyMap())

@Serializable
data class NowcastParameter(val unit: String = "", val data: List<Double?> = emptyList())

/** One grid point's forecast rate, in millimetres per hour. */
data class NowcastCell(val lat: Double, val lon: Double, val mmPerHour: Double)

/** Every cell at one quarter-hour. */
data class NowcastStep(val time: Instant, val cells: List<NowcastCell>)

/**
 * A nowcast for one place: a handful of quarter-hourly frames, each a grid of rates.
 *
 * [issuedAt] is the model's own reference time, not the fetch, because a nowcast an hour old is
 * describing an hour that has happened.
 */
data class PrecipNowcast(val issuedAt: Instant, val steps: List<NowcastStep>) {
    companion object { val EMPTY = PrecipNowcast(Instant.EPOCH, emptyList()) }
}

object NowcastMapper {

    /** Below this a cell is drawn as nothing rather than as the faintest possible blue. */
    const val MIN_MM_PER_HOUR = 0.1

    /** `rr` is a sum over the step, and the steps are quarter hours. */
    private const val STEPS_PER_HOUR = 4

    /**
     * GeoSphere stamps times as `2026-09-11T08:00+00:00` — an offset with no seconds, which
     * `ISO_OFFSET_DATE_TIME` will not parse.
     */
    private val TIME: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd'T'HH:mm")
        .optionalStart().appendPattern(":ss").optionalEnd()
        .appendOffset("+HH:MM", "Z")
        .toFormatter()

    private fun parse(text: String): Instant? =
        runCatching { OffsetDateTime.parse(text, TIME).toInstant() }.getOrNull()

    /**
     * AROME's accumulated series, turned into the same per-step rates INCA gives.
     *
     * [after] is the last hour the finer forecast already covers; steps at or before it are dropped
     * rather than drawn twice. The first surviving step is differenced against the one before it in
     * the response, so the hour's own rain is what is shown and not the run's total to date.
     */
    fun mapOutlook(resp: NowcastResponse, after: Instant?): PrecipNowcast {
        val issuedAt = parse(resp.referenceTime) ?: return PrecipNowcast.EMPTY
        val times = resp.timestamps.map(::parse)
        val steps = times.mapIndexedNotNull { i, time ->
            if (time == null) return@mapIndexedNotNull null
            if (i == 0) return@mapIndexedNotNull null // nothing to difference against
            if (after != null && !time.isAfter(after)) return@mapIndexedNotNull null
            val cells = resp.features.mapNotNull { feature ->
                val lon = feature.geometry.coordinates.getOrNull(0) ?: return@mapNotNull null
                val lat = feature.geometry.coordinates.getOrNull(1) ?: return@mapNotNull null
                val series = feature.properties.parameters["rr_acc"]?.data ?: return@mapNotNull null
                val now = series.getOrNull(i) ?: return@mapNotNull null
                val before = series.getOrNull(i - 1) ?: return@mapNotNull null
                // An accumulation that goes backwards is a new run spliced into the series, not
                // rain that un-fell.
                val rate = (now - before).coerceAtLeast(0.0)
                if (rate < MIN_MM_PER_HOUR) null else NowcastCell(lat, lon, rate)
            }
            NowcastStep(time, cells)
        }
        return PrecipNowcast(issuedAt, steps)
    }

    fun map(resp: NowcastResponse): PrecipNowcast {
        val issuedAt = parse(resp.referenceTime) ?: return PrecipNowcast.EMPTY
        val times = resp.timestamps.map(::parse)
        val steps = times.mapIndexedNotNull { i, time ->
            if (time == null) return@mapIndexedNotNull null
            val cells = resp.features.mapNotNull { feature ->
                val lon = feature.geometry.coordinates.getOrNull(0) ?: return@mapNotNull null
                val lat = feature.geometry.coordinates.getOrNull(1) ?: return@mapNotNull null
                val mm = feature.properties.parameters["rr"]?.data?.getOrNull(i) ?: return@mapNotNull null
                val rate = mm * STEPS_PER_HOUR
                // A dry cell is left out rather than carried as a zero: over a box this size that is
                // most of them on most days, and they would be drawn as nothing anyway.
                if (rate < MIN_MM_PER_HOUR) null else NowcastCell(lat, lon, rate)
            }
            NowcastStep(time, cells)
        }
        return PrecipNowcast(issuedAt, steps)
    }
}
