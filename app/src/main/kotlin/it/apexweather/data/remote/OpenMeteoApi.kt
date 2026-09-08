package it.apexweather.data.remote

import it.apexweather.domain.DailyAggregator
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.WmoCodes
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.DailyPoint
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceForecast
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.Instant
import java.time.LocalDate

interface OpenMeteoApi {
    @GET("v1/forecast")
    suspend fun forecast(
        @Query("latitude") latitude: Double = DorfTirol.LAT,
        @Query("longitude") longitude: Double = DorfTirol.LON,
        @Query("timezone") timezone: String = DorfTirol.ZONE.id,
        @Query("forecast_days") forecastDays: Int = 7,
        @Query("models") models: String = OpenMeteoMapper.MODELS.values.joinToString(","),
        @Query("hourly") hourly: String = OpenMeteoMapper.HOURLY_VARS,
        @Query("daily") daily: String = OpenMeteoMapper.DAILY_VARS,
    ): OpenMeteoResponse

    companion object { const val BASE_URL = "https://api.open-meteo.com/" }
}

@Serializable
data class OpenMeteoResponse(
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int = 0,
    val hourly: JsonObject,
    val daily: JsonObject,
)

object OpenMeteoMapper {
    val MODELS: Map<Source, String> = mapOf(
        Source.ICON_CH1 to "meteoswiss_icon_ch1",
        Source.ICON_CH2 to "meteoswiss_icon_ch2",
        Source.ICON_2I to "italia_meteo_arpae_icon_2i",
        Source.ICON_D2 to "icon_d2",
        Source.ECMWF to "ecmwf_ifs025",
    )
    const val HOURLY_VARS = "temperature_2m,apparent_temperature,precipitation,precipitation_probability," +
        "weather_code,cloud_cover,relative_humidity_2m,wind_speed_10m,wind_gusts_10m,wind_direction_10m"
    const val DAILY_VARS = "temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code,sunrise,sunset"

    fun map(resp: OpenMeteoResponse, fetchedAt: Instant): Map<Source, SourceForecast> {
        val zone = DorfTirol.ZONE
        val times = resp.hourly.strings("time").map { parseLocal(it!!, zone) }
        val dayDates = resp.daily.strings("time").map { LocalDate.parse(it!!) }

        return MODELS.mapNotNull { (source, key) ->
            val h = resp.hourly
            val temps = h.doubles("temperature_2m_$key")
            if (temps.isEmpty()) return@mapNotNull null
            val feels = h.doubles("apparent_temperature_$key")
            val precip = h.doubles("precipitation_$key")
            val prob = h.ints("precipitation_probability_$key")
            val code = h.ints("weather_code_$key")
            val cloud = h.ints("cloud_cover_$key")
            val hum = h.ints("relative_humidity_2m_$key")
            val wind = h.doubles("wind_speed_10m_$key")
            val gust = h.doubles("wind_gusts_10m_$key")
            val dir = h.ints("wind_direction_10m_$key")

            val hourly = times.indices.mapNotNull { i ->
                val t = temps.getOrNull(i) ?: return@mapNotNull null
                HourlyPoint(
                    time = times[i],
                    tempC = t,
                    feelsLikeC = feels.getOrNull(i),
                    precipMm = precip.getOrNull(i) ?: 0.0,
                    precipProb = prob.getOrNull(i),
                    windKmh = wind.getOrNull(i) ?: 0.0,
                    gustKmh = gust.getOrNull(i),
                    windDirDeg = dir.getOrNull(i),
                    cloudPct = cloud.getOrNull(i),
                    humidityPct = hum.getOrNull(i),
                    condition = WmoCodes.toCondition(code.getOrNull(i)),
                )
            }

            val d = resp.daily
            val tmax = d.doubles("temperature_2m_max_$key")
            val tmin = d.doubles("temperature_2m_min_$key")
            val psum = d.doubles("precipitation_sum_$key")
            val dcode = d.ints("weather_code_$key")
            val sunrise = d.strings("sunrise_$key")
            val sunset = d.strings("sunset_$key")
            val daily = dayDates.indices.mapNotNull { i ->
                val max = tmax.getOrNull(i) ?: return@mapNotNull null
                val min = tmin.getOrNull(i) ?: return@mapNotNull null
                DailyPoint(
                    date = dayDates[i],
                    minC = min,
                    maxC = max,
                    precipMm = psum.getOrNull(i) ?: 0.0,
                    condition = dcode.getOrNull(i)?.let(WmoCodes::toCondition) ?: run {
                        val hoursOfDay = hourly.filter { it.time.atZone(zone).toLocalDate() == dayDates[i] }
                            .map { it.time.atZone(zone).hour to it.condition }
                        if (hoursOfDay.isEmpty()) Condition.CLOUDY else DailyAggregator.worstCondition(hoursOfDay)
                    },
                    sunrise = sunrise.getOrNull(i)?.let { parseLocal(it, zone) },
                    sunset = sunset.getOrNull(i)?.let { parseLocal(it, zone) },
                )
            }
            source to SourceForecast(source, issuedAt = fetchedAt, fetchedAt = fetchedAt, hourly = hourly, daily = daily)
        }.toMap()
    }
}
