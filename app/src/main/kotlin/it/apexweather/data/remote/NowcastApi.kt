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
     * The rest of the day, from AROME's **ensemble** on the same grid service.
     *
     * INCA stops two and a half hours out, and "will it rain this evening" is a map question too.
     * This is the 2,5 km run rather than the 1 km one: over a box around the place the kilometre
     * grid is 488 kB for a day and this is 117 kB, for a resolution still finer than the radar's own
     * at the zooms anyone looks at. The near hours keep INCA's kilometre and its quarter hours,
     * which is where resolution actually buys something.
     *
     * The **median of the ensemble** rather than the single deterministic run it replaced, at
     * exactly the same payload — 117 kB either way, which is the only reason this was a free choice.
     * Measured against that run over a box of the eastern Dolomites on 2026-09-11: the median calls
     * *more* cell-hours wet than the single run does (1137 against 730), so it is not the drier
     * answer one might fear from a median; where the two disagree, the ensemble's ninetieth
     * percentile sides with the single run nine times out of ten, which is what one realisation of a
     * model is — a member, and not usually the middle one.
     *
     * **`rain_*` is the precipitation here, not `rr_*`.** They both claim `kg m-2` and `rr_p50`
     * tops out at 0,008 over a box a day long, against 7,9 for `rain_p50`; a map drawn from `rr`
     * shows no rain ever, which is how this was nearly shipped. Unlike the deterministic run's
     * `rr_acc` these are per-step and want no differencing.
     */
    @GET("v1/grid/forecast/ensemble-v1-1h-2500m")
    suspend fun outlook(
        @Query("bbox") bbox: String,
        @Query("end") end: String,
        @Query("parameters") parameters: String =
            "${NowcastMapper.OUTLOOK_PARAMETER},${NowcastMapper.OUTLOOK_UPPER_PARAMETER}",
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

/**
 * One grid point's forecast rate, in millimetres per hour.
 *
 * [upperMmPerHour] is the ensemble's ninetieth percentile where there is one — what the wetter end
 * of the members expects, which is the whole reason for running fifty of them. The nowcast has no
 * members and leaves it null.
 */
data class NowcastCell(
    val lat: Double,
    val lon: Double,
    val mmPerHour: Double,
    val upperMmPerHour: Double? = null,
)

/**
 * Which run a step came from, because the two do not answer at the same sharpness and the map
 * should not pretend otherwise.
 */
enum class NowcastKind {
    /** INCA: a kilometre, a quarter of an hour, and radar folded into it. */
    NOWCAST,

    /** AROME's ensemble median: 2,5 km and a whole hour, but it reaches the rest of the day. */
    OUTLOOK,
}

/** Every cell at one step, and how finely it was drawn. */
data class NowcastStep(
    val time: Instant,
    val cells: List<NowcastCell>,
    val kind: NowcastKind = NowcastKind.NOWCAST,
)

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

    /** INCA's parameter, a sum over its quarter-hour step. */
    private const val NOWCAST_PARAMETER = "rr"

    /** The ensemble's, a sum over its hour. Its `rr_*` is a different and far smaller quantity. */
    const val OUTLOOK_PARAMETER = "rain_p50"

    /**
     * And the wetter end of the same ensemble.
     *
     * Fetched beside the median because a forecast drawn as one field looks like a fact, and an
     * ensemble's answer is not one: over a box of the eastern Dolomites on 2026-09-11 the median
     * called 730 cell-hours dry that the ninetieth percentile called wet. It costs 71 kB on top of
     * the median's 117, measured over a place box for a day.
     */
    const val OUTLOOK_UPPER_PARAMETER = "rain_p90"

    private const val QUARTER_HOURS_PER_HOUR = 4

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
     * The ensemble's hourly median, in the same per-step rates INCA gives.
     *
     * [after] is the last hour the finer forecast already covers; steps at or before it are dropped
     * rather than drawn twice.
     */
    fun mapOutlook(resp: NowcastResponse, after: Instant?): PrecipNowcast =
        map(resp, parameter = OUTLOOK_PARAMETER, after = after, kind = NowcastKind.OUTLOOK)

    fun map(resp: NowcastResponse): PrecipNowcast =
        map(resp, parameter = NOWCAST_PARAMETER, after = null, kind = NowcastKind.NOWCAST)

    private fun map(
        resp: NowcastResponse,
        parameter: String,
        after: Instant?,
        kind: NowcastKind,
    ): PrecipNowcast {
        val issuedAt = parse(resp.referenceTime) ?: return PrecipNowcast.EMPTY
        // INCA sums over a quarter hour; the hourly runs sum over an hour. Both are turned into the
        // rate a colour scale and a reader can use.
        val perHour = if (parameter == NOWCAST_PARAMETER) QUARTER_HOURS_PER_HOUR else 1
        val times = resp.timestamps.map(::parse)
        val steps = times.mapIndexedNotNull { i, time ->
            if (time == null) return@mapIndexedNotNull null
            if (after != null && !time.isAfter(after)) return@mapIndexedNotNull null
            val cells = resp.features.mapNotNull { feature ->
                val lon = feature.geometry.coordinates.getOrNull(0) ?: return@mapNotNull null
                val lat = feature.geometry.coordinates.getOrNull(1) ?: return@mapNotNull null
                val mm = feature.properties.parameters[parameter]?.data?.getOrNull(i) ?: return@mapNotNull null
                val rate = mm * perHour
                val upper = feature.properties.parameters[OUTLOOK_UPPER_PARAMETER]
                    ?.data?.getOrNull(i)?.times(perHour)
                // A cell is kept when either the middle of the ensemble or its wetter end has
                // something to say. Dropping the rest matters: over a box this size most cells are
                // dry most days, and they would be drawn as nothing anyway.
                val worthDrawing = rate >= MIN_MM_PER_HOUR || (upper ?: 0.0) >= MIN_MM_PER_HOUR
                if (!worthDrawing) null else NowcastCell(lat, lon, rate, upper)
            }
            NowcastStep(time, cells, kind)
        }
        return PrecipNowcast(issuedAt, steps)
    }
}
