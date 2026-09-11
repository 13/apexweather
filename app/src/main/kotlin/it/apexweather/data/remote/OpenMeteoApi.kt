package it.apexweather.data.remote

import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.DailyAggregator
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.WmoCodes
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.DailyPoint
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.MinutePoint
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
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("timezone") timezone: String = SouthTyrol.ZONE.id,
        @Query("forecast_days") forecastDays: Int = OpenMeteoMapper.FORECAST_DAYS,
        @Query("models") models: String = OpenMeteoMapper.MODELS.values.joinToString(","),
        @Query("hourly") hourly: String = OpenMeteoMapper.HOURLY_VARS,
        @Query("daily") daily: String = OpenMeteoMapper.DAILY_VARS,
        @Query("minutely_15") minutely: String = "precipitation",
        @Query("forecast_minutely_15") minutelySteps: Int = OpenMeteoMapper.MINUTELY_STEPS,
    ): OpenMeteoResponse

    /**
     * The same models, at the weather station instead of the village.
     *
     * Only the temperature, and only a day either side of now: this exists to measure how much
     * colder the village is than the station at a given hour, so that the station's live reading can
     * be carried up the hill instead of being quoted 300 m too low. Yesterday is included because an
     * observation may be up to ninety minutes old and can therefore belong to the previous day.
     */
    @GET("v1/forecast")
    suspend fun stationForecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("elevation") elevation: Int,
        @Query("timezone") timezone: String = SouthTyrol.ZONE.id,
        @Query("past_days") pastDays: Int = 1,
        // Two days, not one. The series has to reach twelve hours past the current hour whatever
        // time it is, or `station_history` could only ever record a twelve-hour-ahead forecast in
        // the morning — a gap in the record that lines up exactly with the part of the day
        // BiasCorrector splits on. It costs about 1,8 kB.
        @Query("forecast_days") forecastDays: Int = 2,
        @Query("models") models: String = OpenMeteoMapper.MODELS.values.joinToString(","),
        @Query("hourly") hourly: String = "temperature_2m",
    ): OpenMeteoStationResponse

    companion object { const val BASE_URL = "https://api.open-meteo.com/" }
}

/** The station call asks for no daily block, so its response has none. */
@Serializable
data class OpenMeteoStationResponse(
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int = 0,
    val elevation: Double = 0.0,
    val hourly: JsonObject,
)

@Serializable
data class OpenMeteoResponse(
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int = 0,
    val hourly: JsonObject,
    val daily: JsonObject,
    @SerialName("minutely_15") val minutely: JsonObject = JsonObject(emptyMap()),
)

object OpenMeteoMapper {
    val MODELS: Map<Source, String> = mapOf(
        Source.ICON_CH1 to "meteoswiss_icon_ch1",
        Source.ICON_CH2 to "meteoswiss_icon_ch2",
        Source.ICON_2I to "italia_meteo_arpae_icon_2i",
        Source.ICON_D2 to "icon_d2",
        Source.KNMI_HARMONIE to "knmi_harmonie_arome_europe",
        Source.DMI_HARMONIE to "dmi_harmonie_arome_europe",
        Source.ECMWF to "ecmwf_ifs025",
        Source.ECMWF_AIFS to "ecmwf_aifs025_single",
    )
    /**
     * Two weeks, although only ECMWF reaches past day five. Every other model returns nulls for the
     * hours it does not cover and the mapper drops those, so the extra days cost nothing but a longer
     * array of nulls — 2.7 kB more over the wire once gzipped, measured against the seven-day call.
     */
    const val FORECAST_DAYS = 14
    const val HOURLY_VARS = "temperature_2m,apparent_temperature,precipitation,precipitation_probability," +
        "weather_code,cloud_cover,relative_humidity_2m,wind_speed_10m,wind_gusts_10m,wind_direction_10m," +
        "freezing_level_height"
    const val DAILY_VARS = "temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code,sunrise,sunset"

    /** Twelve hours of quarter-hours. Beyond that the resolution is a claim nobody can support. */
    const val MINUTELY_STEPS = 48

    fun map(resp: OpenMeteoResponse, fetchedAt: Instant): Map<Source, SourceForecast> {
        val zone = SouthTyrol.ZONE
        val times = resp.hourly.strings("time").map { parseLocal(it!!, zone) }
        val dayDates = resp.daily.strings("time").map { LocalDate.parse(it!!) }
        val minutelyTimes = resp.minutely.strings("time").map { parseLocal(it!!, zone) }

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
            // ECMWF IFS publishes no freezing level through Open-Meteo; the column comes back all null.
            val freezing = h.doubles("freezing_level_height_$key")
            // Only the regional models: a 25 km global returns a quarter-hourly series when asked,
            // but it is interpolated from its own hourly one and would only add false precision to
            // the question this series exists to answer — when exactly the rain starts.
            val minutely = if (!source.regional) emptyList() else {
                val values = resp.minutely.doubles("precipitation_$key")
                minutelyTimes.indices.mapNotNull { i ->
                    values.getOrNull(i)?.let { MinutePoint(minutelyTimes[i], it) }
                }
            }

            val hourly = times.indices.mapNotNull { i ->
                val t = temps.getOrNull(i) ?: return@mapNotNull null
                HourlyPoint(
                    time = times[i],
                    tempC = t,
                    feelsLikeC = feels.getOrNull(i),
                    precipMm = precip.getOrNull(i) ?: 0.0,
                    precipProb = prob.getOrNull(i),
                    windKmh = wind.getOrNull(i),
                    gustKmh = gust.getOrNull(i),
                    windDirDeg = dir.getOrNull(i),
                    cloudPct = cloud.getOrNull(i),
                    humidityPct = hum.getOrNull(i),
                    freezingLevelM = freezing.getOrNull(i),
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
            source to SourceForecast(source, issuedAt = fetchedAt, fetchedAt = fetchedAt, hourly = hourly, daily = daily, minutely = minutely)
        }.toMap()
    }
}

/**
 * The models' temperature at the weather station, hour by hour, kept per model.
 *
 * Per model rather than collapsed to a median, because this series has two jobs. Carrying the
 * station's reading up to the village needs only the median — that is a difference between two
 * points. Measuring how wrong each model has lately been at a place it can actually be checked
 * against needs each model on its own, and that is what pays for the extra rows.
 */
@kotlinx.serialization.Serializable
data class StationReference(
    @kotlinx.serialization.Serializable(with = it.apexweather.domain.model.InstantSerializer::class)
    val fetchedAt: Instant,
    val elevationM: Double,
    /** Source name → epoch second → temperature. Names, because a JSON map key is a string anyway. */
    val bySource: Map<String, Map<Long, Double>> = emptyMap(),
) {
    /** Every model's value for that hour, keyed by source. */
    fun at(t: Instant): Map<Source, Double> {
        val second = t.truncatedTo(java.time.temporal.ChronoUnit.HOURS).epochSecond
        return bySource.mapNotNull { (name, series) ->
            val source = runCatching { Source.valueOf(name) }.getOrNull() ?: return@mapNotNull null
            series[second]?.let { source to it }
        }.toMap()
    }

    /** The median across the models, which is the statistic the village consensus uses too. */
    fun tempAt(t: Instant): Double? = at(t).values.takeIf { it.isNotEmpty() }?.let { ConsensusBlender.median(it.toList()) }
}

object OpenMeteoStationMapper {
    fun map(resp: OpenMeteoStationResponse, fetchedAt: Instant): StationReference {
        val times = resp.hourly.strings("time").map { parseLocal(it!!, SouthTyrol.ZONE) }
        val bySource = OpenMeteoMapper.MODELS.mapNotNull { (source, key) ->
            val values = resp.hourly.doubles("temperature_2m_$key")
            // A model that does not reach these hours contributes nothing rather than a zero.
            val series = times.indices.mapNotNull { i -> values.getOrNull(i)?.let { times[i].epochSecond to it } }.toMap()
            if (series.isEmpty()) null else source.name to series
        }.toMap()
        return StationReference(fetchedAt = fetchedAt, elevationM = resp.elevation, bySource = bySource)
    }
}
