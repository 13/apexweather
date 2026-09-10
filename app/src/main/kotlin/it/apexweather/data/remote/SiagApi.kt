package it.apexweather.data.remote

import it.apexweather.domain.NearbyStation
import it.apexweather.domain.distanceKm
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.SiagCodes
import it.apexweather.domain.model.Bulletin
import it.apexweather.domain.model.BulletinCondition
import it.apexweather.domain.model.BulletinDay
import it.apexweather.domain.model.DailyPoint
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceForecast
import it.apexweather.domain.model.StationObservation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.time.Instant
import java.time.LocalDateTime
import kotlin.math.roundToInt

/** api-weather.services.siag.it — the backend of the official "Wetter Südtirol" app. */
interface SiagApi {
    @GET("api/v2/municipality/MunicipalityBulletin/{istat}")
    suspend fun municipality(@Path("istat") istat: String): KmosResponse

    @GET("api/v2/station")
    suspend fun stations(@Query("categoryId") categoryId: Int = 1, @Query("visibility") visibility: Int = 11): SiagStationsResponse

    companion object { const val BASE_URL = "https://api-weather.services.siag.it/" }
}

/** tourism.opendatahub.com — open-data mirror of the province bulletin. */
interface OdhApi {
    @GET("v1/Weather")
    suspend fun weather(@Query("language") language: String): OdhWeatherResponse

    @GET("v1/Weather/District/{id}")
    suspend fun district(@Path("id") id: Int, @Query("language") language: String): OdhDistrictResponse

    companion object { const val BASE_URL = "https://tourism.opendatahub.com/" }
}

// ---- KMOS municipality forecast DTOs ----

@Serializable data class KmosResponse(val info: KmosInfo = KmosInfo(), val municipality: KmosMunicipality)
@Serializable data class KmosInfo(val model: String? = null, val currentModelRun: String? = null, val fileCreationDate: String? = null)
@Serializable
data class KmosMunicipality(
    val code: String? = null,
    val nameDe: String? = null,
    val tempMin24: KmosSeries? = null,
    val tempMax24: KmosSeries? = null,
    val temp3: KmosSeries? = null,
    val precProb3: KmosSeries? = null,
    val precSum3: KmosSeries? = null,
    val symbols3: KmosSeries? = null,
    val symbols24: KmosSeries? = null,
    val precSum24: KmosSeries? = null,
    val precProb24: KmosSeries? = null,
)
@Serializable data class KmosSeries(val unit: String? = null, val data: List<KmosPoint> = emptyList())
@Serializable data class KmosPoint(val date: String, val value: JsonPrimitive? = null)

// ---- Open Data Hub bulletin DTOs (keys are PascalCase; lowercase duplicates are ignored) ----

@Serializable
data class OdhWeatherResponse(
    @SerialName("Date") val date: String,
    @SerialName("EvolutionTitle") val evolutionTitle: String? = null,
    @SerialName("Evolution") val evolution: String? = null,
    @SerialName("Conditions") val conditions: List<OdhCondition> = emptyList(),
)
@Serializable
data class OdhCondition(
    @SerialName("Date") val date: String,
    @SerialName("Title") val title: String? = null,
    @SerialName("WeatherDesc") val weatherDesc: String? = null,
    @SerialName("Temperatures") val temperatures: String? = null,
    @SerialName("WeatherImgUrl") val weatherImgUrl: String? = null,
)
@Serializable
data class OdhDistrictResponse(
    @SerialName("DistrictName") val districtName: String? = null,
    @SerialName("BezirksForecast") val forecast: List<OdhDistrictDay> = emptyList(),
)
@Serializable
data class OdhDistrictDay(
    @SerialName("Date") val date: String,
    @SerialName("WeatherCode") val weatherCode: String? = null,
    @SerialName("WeatherDesc") val weatherDesc: String? = null,
    @SerialName("WeatherImgUrl") val weatherImgUrl: String? = null,
    @SerialName("MaxTemp") val maxTemp: Double? = null,
    @SerialName("MinTemp") val minTemp: Double? = null,
    @SerialName("RainFrom") val rainFrom: Double? = null,
    @SerialName("RainTo") val rainTo: Double? = null,
    @SerialName("Thunderstorm") val thunderstorm: Int? = null,
)

// ---- Live station DTOs ----

@Serializable data class SiagStationsResponse(val rows: List<SiagStationRow> = emptyList())
@Serializable
data class SiagStationRow(
    val code: String? = null,
    val name: String? = null,
    val t: String? = null,
    val rh: String? = null,
    val p: String? = null,
    val ff: String? = null,
    val dd: String? = null,
    val wMax: String? = null,
    val n: String? = null,
    val lastUpdated: String? = null,
    /** Comma-decimal strings, as everything numeric from this endpoint is. */
    val latitude: String? = null,
    val longitude: String? = null,
)

object SiagMappers {

    private fun nearestTo(resp: SiagStationsResponse, station: NearbyStation): SiagStationRow? =
        resp.rows
            .mapNotNull { row ->
                val lat = row.latitude.siagDouble() ?: return@mapNotNull null
                val lon = row.longitude.siagDouble() ?: return@mapNotNull null
                row to distanceKm(station.lat, station.lon, lat, lon)
            }
            .minByOrNull { it.second }
            ?.first


    fun mapKmos(resp: KmosResponse, fetchedAt: Instant): SourceForecast {
        val m = resp.municipality
        val temps = m.temp3?.data?.takeIf { it.isNotEmpty() } ?: error("KMOS: missing temp3")
        val precByDate = m.precSum3?.data?.associate { it.date to it.value?.doubleOrNull } ?: emptyMap()
        val probByDate = m.precProb3?.data?.associate { it.date to it.value?.intOrNull } ?: emptyMap()
        val symByDate = m.symbols3?.data?.associate { it.date to it.value?.contentOrNull } ?: emptyMap()

        val hourly = temps.mapNotNull { pt ->
            val t = pt.value?.doubleOrNull ?: return@mapNotNull null
            HourlyPoint(
                time = parseOffset(pt.date),
                tempC = t,
                precipMm = (precByDate[pt.date] ?: 0.0) / 3.0, // 3-hour sum spread as an hourly rate
                precipProb = probByDate[pt.date],
                // KMOS publishes no wind parameter; absence travels as null rather than a calm-looking 0.0.
                condition = SiagCodes.toCondition(symByDate[pt.date]),
            )
        }

        val minByDate = m.tempMin24?.data?.associate { it.date to it.value?.doubleOrNull } ?: emptyMap()
        val precDayByDate = m.precSum24?.data?.associate { it.date to it.value?.doubleOrNull } ?: emptyMap()
        val symDayByDate = m.symbols24?.data?.associate { it.date to it.value?.contentOrNull } ?: emptyMap()
        val daily = m.tempMax24?.data.orEmpty().mapNotNull { pt ->
            val max = pt.value?.doubleOrNull ?: return@mapNotNull null
            val min = minByDate[pt.date] ?: return@mapNotNull null
            DailyPoint(
                date = parseOffset(pt.date).atZone(SouthTyrol.ZONE).toLocalDate(),
                minC = min,
                maxC = max,
                precipMm = precDayByDate[pt.date] ?: 0.0,
                condition = SiagCodes.toCondition(symDayByDate[pt.date]),
            )
        }

        return SourceForecast(
            source = Source.SIAG_KMOS,
            issuedAt = resp.info.currentModelRun?.let { runCatching { parseOffset(it) }.getOrNull() } ?: fetchedAt,
            fetchedAt = fetchedAt,
            hourly = hourly,
            daily = daily,
        )
    }

    fun mapBulletin(weather: OdhWeatherResponse, district: OdhDistrictResponse, language: String): Bulletin {
        val zone = SouthTyrol.ZONE
        return Bulletin(
            language = language,
            issuedAt = LocalDateTime.parse(weather.date).atZone(zone).toInstant(),
            title = weather.evolutionTitle.orEmpty(),
            evolution = weather.evolution.orEmpty().replace("\r\n", "\n"),
            conditions = weather.conditions.map { c ->
                BulletinCondition(
                    date = LocalDateTime.parse(c.date).toLocalDate(),
                    title = c.title.orEmpty(),
                    description = c.weatherDesc.orEmpty(),
                    temperatures = c.temperatures,
                    mapImageUrl = c.weatherImgUrl,
                )
            },
            days = district.forecast.map { d ->
                BulletinDay(
                    date = LocalDateTime.parse(d.date).toLocalDate(),
                    code = d.weatherCode.orEmpty(),
                    description = d.weatherDesc.orEmpty(),
                    iconUrl = d.weatherImgUrl ?: d.weatherCode?.let(SiagCodes::iconUrl),
                    minC = d.minTemp,
                    maxC = d.maxTemp,
                    rainFromMm = d.rainFrom,
                    rainToMm = d.rainTo,
                    thunderstormLevel = d.thunderstorm,
                )
            },
        )
    }

    /**
     * The reading from the station a place was matched to.
     *
     * With a fallback to the nearest station that is still reporting: the catalogue precomputes
     * which station speaks for a place, and a station decommissioned since then should cost a
     * slightly more distant reading rather than the whole observation.
     */
    fun mapObservation(resp: SiagStationsResponse, station: NearbyStation): StationObservation? {
        val row = resp.rows.firstOrNull { it.code == station.code } ?: nearestTo(resp, station) ?: return null
        val updated = row.lastUpdated ?: return null
        fun msToKmh(v: Double?) = v?.let { (it * 3.6 * 10).roundToInt() / 10.0 }
        return StationObservation(
            stationName = row.name ?: station.name,
            time = LocalDateTime.parse(updated).atZone(SouthTyrol.ZONE).toInstant(),
            tempC = row.t.siagDouble(),
            humidityPct = row.rh.siagDouble()?.roundToInt(),
            windKmh = msToKmh(row.ff.siagDouble()),
            windDir = row.dd?.takeIf { it != "--" },
            gustKmh = msToKmh(row.wMax.siagDouble()),
            precipMm = row.n.siagDouble(),
            pressureHpa = row.p.siagDouble(),
        )
    }
}
