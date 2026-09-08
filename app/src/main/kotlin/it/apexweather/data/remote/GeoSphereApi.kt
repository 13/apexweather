package it.apexweather.data.remote

import it.apexweather.domain.DailyAggregator
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceForecast
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

interface GeoSphereApi {
    @GET("v1/timeseries/forecast/nwp-v1-1h-2500m")
    suspend fun forecast(
        @Query("lat_lon") latLon: String = "${DorfTirol.LAT},${DorfTirol.LON}",
        @Query("parameters") parameters: String = GeoSphereMapper.PARAMS,
    ): GeoSphereResponse

    companion object { const val BASE_URL = "https://dataset.api.hub.geosphere.at/" }
}

@Serializable
data class GeoSphereResponse(
    @SerialName("reference_time") val referenceTime: String,
    val timestamps: List<String>,
    val features: List<GeoSphereFeature>,
)

@Serializable data class GeoSphereFeature(val properties: GeoSphereProperties)
@Serializable data class GeoSphereProperties(val parameters: Map<String, GeoSphereParam>)
@Serializable data class GeoSphereParam(val unit: String? = null, val data: List<Double?>)

object GeoSphereMapper {
    const val PARAMS = "t2m,rr_acc,snow_acc,rh2m,u10m,v10m,ugust,vgust,tcc,sp,cape"

    fun map(resp: GeoSphereResponse, fetchedAt: Instant): SourceForecast {
        val zone = DorfTirol.ZONE
        val p = resp.features.firstOrNull()?.properties?.parameters ?: error("GeoSphere: no features")
        fun series(name: String): List<Double?> = p[name]?.data ?: emptyList()
        val t2m = series("t2m")
        if (t2m.none { it != null }) error("GeoSphere: missing t2m")
        val rrHourly = hourlyFromAccumulated(series("rr_acc"))
        val snowHourly = hourlyFromAccumulated(series("snow_acc"))
        val rh = series("rh2m")
        val u = series("u10m"); val v = series("v10m")
        val ug = series("ugust"); val vg = series("vgust")
        val tcc = series("tcc")
        val cape = series("cape")
        val times = resp.timestamps.map(::parseOffset)

        val hourly = times.indices.mapNotNull { i ->
            val temp = t2m.getOrNull(i) ?: return@mapNotNull null
            val precip = rrHourly.getOrNull(i) ?: 0.0
            val snow = snowHourly.getOrNull(i) ?: 0.0
            val (wind, dir) = if (u.getOrNull(i) != null && v.getOrNull(i) != null) windFromUV(u[i]!!, v[i]!!) else 0.0 to null
            val gust = if (ug.getOrNull(i) != null && vg.getOrNull(i) != null) windFromUV(ug[i]!!, vg[i]!!).first else null
            // tcc missing for an hour: assume half-covered sky rather than biasing clear or overcast.
            val cloud = tcc.getOrNull(i) ?: 0.5
            HourlyPoint(
                time = times[i],
                tempC = temp,
                precipMm = precip,
                windKmh = wind,
                gustKmh = gust,
                windDirDeg = dir,
                cloudPct = (cloud * 100).roundToInt().coerceIn(0, 100),
                humidityPct = rh.getOrNull(i)?.roundToInt()?.coerceIn(0, 100),
                condition = condition(precip, snow, cloud, temp, cape.getOrNull(i) ?: 0.0),
            )
        }
        return SourceForecast(
            source = Source.GEOSPHERE_AROME,
            issuedAt = parseOffset(resp.referenceTime),
            fetchedAt = fetchedAt,
            hourly = hourly,
            daily = DailyAggregator.aggregate(hourly, zone),
        )
    }

    /**
     * Derives per-hour values from a monotonically accumulated series in one pass, tracking the
     * last known accumulated value as a baseline. A null entry contributes 0.0 for that hour and
     * leaves the baseline unchanged, so a later value diffs against the last real reading instead
     * of treating the gap as zero accumulation.
     */
    private fun hourlyFromAccumulated(acc: List<Double?>): List<Double> {
        var lastKnown = 0.0
        return acc.map { cur ->
            if (cur == null) {
                0.0
            } else {
                val hourly = (cur - lastKnown).coerceAtLeast(0.0)
                lastKnown = cur
                hourly
            }
        }
    }

    /** u = eastward, v = northward component in m/s. Returns speed in km/h and meteorological "from" direction. */
    fun windFromUV(u: Double, v: Double): Pair<Double, Int> {
        val speedKmh = sqrt(u * u + v * v) * 3.6
        val dirFrom = (Math.toDegrees(atan2(-u, -v)) + 360.0) % 360.0
        return speedKmh to dirFrom.roundToInt() % 360
    }

    fun condition(precipMm: Double, snowMm: Double, tcc: Double, tempC: Double, cape: Double): Condition {
        if (precipMm >= 0.1) {
            val snowShare = if (precipMm > 0) snowMm / precipMm else 0.0
            val frozen = snowShare > 0.5 || tempC < 0.5
            return when {
                frozen -> if (precipMm >= 2.5) Condition.HEAVY_SNOW else Condition.SNOW
                snowShare > 0.2 -> Condition.SLEET
                cape >= 500.0 && precipMm >= 0.5 -> Condition.THUNDERSTORM
                precipMm < 0.5 -> Condition.DRIZZLE
                precipMm < 4.0 -> Condition.RAIN
                else -> Condition.HEAVY_RAIN
            }
        }
        return when {
            tcc < 0.15 -> Condition.CLEAR
            tcc < 0.40 -> Condition.MOSTLY_CLEAR
            tcc < 0.70 -> Condition.PARTLY_CLOUDY
            else -> Condition.CLOUDY
        }
    }
}
