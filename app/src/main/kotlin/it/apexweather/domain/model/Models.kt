package it.apexweather.domain.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate

/** Forecast sources. [regional] sources always take part in the consensus; ECMWF only fills gaps. */
enum class Source(val displayName: String, val regional: Boolean) {
    SIAG_KMOS("Südtirol KMOS", true),
    GEOSPHERE_AROME("GeoSphere AROME", true),
    ICON_CH1("MeteoSwiss ICON-CH1", true),
    ICON_CH2("MeteoSwiss ICON-CH2", true),
    ICON_2I("ARPAE ICON-2I", true),
    ICON_D2("DWD ICON-D2", true),
    // Two independent HARMONIE-AROME runs at about 2 km. They matter because without them the
    // regional half of the consensus is four flavours of ICON, and four models that share a core
    // agreeing with each other is not the same thing as four models being right.
    KNMI_HARMONIE("KNMI HARMONIE", true),
    DMI_HARMONIE("DMI HARMONIE", true),
    ECMWF("ECMWF IFS", false),
    /** ECMWF's machine-learned model: the same institution, an entirely different way of forecasting. */
    ECMWF_AIFS("ECMWF AIFS", false);

    /** True when [SourceStatus.Ok.issuedAt] really is the model run time. Open-Meteo does not report a
     * run time, so for those sources the timestamp is only when we fetched the data. */
    val hasRunTime: Boolean get() = this == SIAG_KMOS || this == GEOSPHERE_AROME

    /** Hours after the model run at which a cached forecast counts as stale. KMOS runs only twice a day
     * (02:00 and 14:00 local), so its threshold is the 12 h cadence plus margin for the publication delay. */
    val staleAfterHours: Int get() = when (this) {
        SIAG_KMOS -> 16
        ECMWF, ECMWF_AIFS -> 12
        else -> 6
    }
}

/** Declaration order = severity order (used for tie-breaks and "worst of day"). */
enum class Condition {
    CLEAR, MOSTLY_CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, HEAVY_RAIN,
    SLEET, SNOW, HEAVY_SNOW, THUNDERSTORM;

    val isPrecipitation: Boolean get() = ordinal >= DRIZZLE.ordinal
}

@Serializable
data class HourlyPoint(
    @Serializable(with = InstantSerializer::class) val time: Instant,
    val tempC: Double,
    val feelsLikeC: Double? = null,
    val precipMm: Double = 0.0,
    val precipProb: Int? = null,
    /** Null where the model publishes no wind at all, as SIAG KMOS does. */
    val windKmh: Double? = null,
    val gustKmh: Double? = null,
    val windDirDeg: Int? = null,
    val cloudPct: Int? = null,
    val humidityPct: Int? = null,
    /** Height of the 0 °C isotherm in metres above sea level. Null where the model does not publish it;
     * ECMWF IFS via Open-Meteo is one such model. */
    val freezingLevelM: Double? = null,
    val condition: Condition,
)

/**
 * A quarter of an hour of precipitation.
 *
 * Only the regional models publish these natively. Asking a 25 km global model for a quarter-hourly
 * series returns one, but it is interpolation wearing the clothes of resolution, so those are left
 * out rather than allowed to vote on when the rain starts.
 */
@Serializable
data class MinutePoint(
    @Serializable(with = InstantSerializer::class) val time: Instant,
    val precipMm: Double,
)

@Serializable
data class DailyPoint(
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val minC: Double,
    val maxC: Double,
    val precipMm: Double,
    val condition: Condition,
    @Serializable(with = InstantSerializer::class) val sunrise: Instant? = null,
    @Serializable(with = InstantSerializer::class) val sunset: Instant? = null,
)

@Serializable
data class SourceForecast(
    val source: Source,
    @Serializable(with = InstantSerializer::class) val issuedAt: Instant,
    @Serializable(with = InstantSerializer::class) val fetchedAt: Instant,
    val hourly: List<HourlyPoint>,
    val daily: List<DailyPoint>,
    /** Quarter-hourly precipitation for the next twelve hours; empty for a model that has none. */
    val minutely: List<MinutePoint> = emptyList(),
)

@Serializable
data class BulletinCondition(
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val title: String,
    val description: String,
    val temperatures: String?,
    val mapImageUrl: String?,
)

@Serializable
data class BulletinDay(
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val code: String,
    val description: String,
    val iconUrl: String?,
    val minC: Double?,
    val maxC: Double?,
    val rainFromMm: Double?,
    val rainToMm: Double?,
    val thunderstormLevel: Int?,
)

@Serializable
data class Bulletin(
    val language: String,
    @Serializable(with = InstantSerializer::class) val issuedAt: Instant,
    val title: String,
    val evolution: String,
    val conditions: List<BulletinCondition>,
    val days: List<BulletinDay>,
)

/** Awareness level of a warning, in the order MeteoAlarm paints them. */
enum class WarningLevel { YELLOW, ORANGE, RED }

/** What the warning is about. [OTHER] keeps a warning whose wording we do not recognise visible. */
enum class WarningType { WIND, RAIN, THUNDERSTORM, SNOW_ICE, FOG, HIGH_TEMPERATURE, LOW_TEMPERATURE, COASTAL_EVENT, FOREST_FIRE, AVALANCHE, RAIN_FLOOD, FLOOD, OTHER }

/**
 * One civil-protection warning for the province, as published through MeteoAlarm.
 *
 * The feed is regional: the smallest area Italy publishes is "Trentino Alto Adige", so a warning
 * here covers far more ground than Dorf Tirol. It is shown as what it is — a provincial warning —
 * and never presented as a forecast for the village.
 */
@Serializable
data class Warning(
    val identifier: String,
    val type: WarningType,
    val level: WarningLevel,
    val areaDesc: String,
    @Serializable(with = InstantSerializer::class) val onset: Instant,
    @Serializable(with = InstantSerializer::class) val expires: Instant,
    /** The feed's own English wording, kept for the detail sheet; the card uses our own translations. */
    val headline: String,
) {
    fun isActiveAt(now: Instant): Boolean = !now.isAfter(expires)
    fun hasStartedAt(now: Instant): Boolean = !now.isBefore(onset)
}

@Serializable
data class StationObservation(
    val stationName: String,
    @Serializable(with = InstantSerializer::class) val time: Instant,
    val tempC: Double?,
    val humidityPct: Int?,
    val windKmh: Double?,
    val windDir: String?,
    val gustKmh: Double?,
    /**
     * Precipitation since midnight, not a rate and not "raining now".
     *
     * Checked against the live network on 2026-09-10: all 57 stations reported a non-zero figure
     * between 8 and 27.6 mm at the same timestamp, which is a daily accumulation and not an hourly
     * one. The name says so because reading it as "it is raining" is the obvious mistake.
     */
    val precipTodayMm: Double?,
    val pressureHpa: Double?,
    /**
     * Global solar radiation in watts per square metre — how much sun is actually reaching the
     * ground, measured.
     *
     * SIAG publishes it as `gs` on the same row as everything else here, and 49 of the 57 stations
     * report it. It is the only direct evidence the app has about the *sky* rather than the air:
     * see [it.apexweather.domain.StationSun], which uses it to refuse a forecast of overcast while
     * the sun is plainly out.
     */
    val radiationWm2: Double? = null,
)

sealed interface SourceStatus {
    data class Ok(val issuedAt: Instant) : SourceStatus
    data class Stale(val issuedAt: Instant) : SourceStatus
    data class Failed(val reason: String, val lastIssuedAt: Instant?) : SourceStatus
}

data class WeatherSnapshot(
    val forecasts: Map<Source, SourceForecast>,
    val bulletin: Bulletin?,
    val observation: StationObservation?,
    val warnings: List<Warning>,
    /** The models' temperature at the weather station, for carrying its reading up to the village. */
    val stationReference: it.apexweather.data.remote.StationReference?,
    /** How wrong each model has lately been at the station, by part of the day; empty until enough
     * hours have accumulated. */
    val modelBias: it.apexweather.domain.ModelBias,
    /** How far ICON-D2's ensemble members spread, which is uncertainty rather than disagreement. */
    val ensemble: it.apexweather.data.remote.EnsembleSpread?,
    val status: Map<Source, SourceStatus>,
    val bulletinStatus: SourceStatus?,
    val observationStatus: SourceStatus?,
    val warningStatus: SourceStatus?,
    val lastSuccessfulRefresh: Instant?,
    val lastRefreshFailed: Boolean,
) {
    val isEmpty: Boolean get() = forecasts.isEmpty() && bulletin == null && observation == null

    /**
     * The forecasts worth blending: the ones whose run is current.
     *
     * A model run that has gone stale is still cached, because showing something old beats showing
     * nothing — but mixing a twenty-hour-old ICON-D2 into the median with five current runs drags
     * the consensus toward yesterday's weather while looking exactly as confident as before. So a
     * stale run is kept for the per-source lists, where its age is visible beside it, and left out
     * of the number the app leads with.
     *
     * When every run is stale there is nothing to prefer, so all of them are used and the screen's
     * own staleness banner is what tells the reader.
     */
    val forecastsForBlend: Map<Source, SourceForecast>
        get() = forecasts.filterKeys { status[it] is SourceStatus.Ok }.takeIf { it.isNotEmpty() } ?: forecasts
    companion object {
        val EMPTY = WeatherSnapshot(
            forecasts = emptyMap(), bulletin = null, observation = null, warnings = emptyList(),
            stationReference = null, modelBias = it.apexweather.domain.ModelBias.NONE, ensemble = null, status = emptyMap(),
            bulletinStatus = null, observationStatus = null, warningStatus = null,
            lastSuccessfulRefresh = null, lastRefreshFailed = false,
        )
    }
}

data class ConsensusHour(
    val time: Instant,
    val tempC: Double,
    val tempMinC: Double,
    val tempMaxC: Double,
    val feelsLikeC: Double?,
    val precipMm: Double,
    val precipProb: Int,
    /** Null when not one contributing model publishes wind. */
    val windKmh: Double?,
    /** Highest gust any contributing model publishes, not the median; see ConsensusBlender. */
    val gustKmh: Double?,
    /** Median 0 °C isotherm across the models that publish one, in metres. */
    val freezingLevelM: Double?,
    /**
     * Half the ensemble's tenth-to-ninetieth percentile range, where one reaches this hour. This is
     * uncertainty measured rather than inferred, and the badge prefers it to the model spread.
     */
    val ensembleHalfWidthC: Double? = null,
    val condition: Condition,
    val agreement: Float,
    val sourceCount: Int,
    val perSource: Map<Source, HourlyPoint>,
)

data class ConsensusDay(
    val date: LocalDate,
    val minC: Double,
    val maxC: Double,
    val precipMm: Double,
    val condition: Condition,
    val agreement: Float,
    /**
     * Most models that agreed on any hour of this day. One means no two models were compared — but
     * see [ensembleHalfWidthC], which is the other thing there is to compare.
     */
    val sourceCount: Int,
    /**
     * The day's mean ensemble half-width in degrees, where an ensemble reaches it at all.
     *
     * This is what makes the far end of the list worth a number. From about day six only ECMWF's
     * deterministic run reaches, so [sourceCount] is one and there is no model spread — but its own
     * fifty ensemble members still say how confident that forecast is, and [agreement] is built
     * from them.
     */
    val ensembleHalfWidthC: Double? = null,
    /** Lowest 0 °C isotherm of the day in metres — the snow line at its lowest. Null where no model says. */
    val freezingLevelMinM: Double?,
    val sunrise: Instant?,
    val sunset: Instant?,
)

/** Quarter-hourly precipitation, blended the same way as everything else: the median of the models. */
data class ConsensusMinute(val time: Instant, val precipMm: Double, val sourceCount: Int)

data class ConsensusForecast(
    val hourly: List<ConsensusHour>,
    val daily: List<ConsensusDay>,
    val minutely: List<ConsensusMinute> = emptyList(),
) {
    /**
     * When precipitation next begins, to the quarter-hour, or null if it is already falling or does
     * not start inside the sub-hourly window. The threshold is the same one the hourly series uses
     * for "this counts as rain".
     */
    fun precipitationStartsAt(now: Instant): Instant? {
        val ahead = minutely.filter { it.time.isAfter(now) }
        if (ahead.isEmpty()) return null
        // Already raining: the reader can see that out of the window, and a start time would be a lie.
        if (minutely.lastOrNull { !it.time.isAfter(now) }?.let { it.precipMm >= WET_MM } == true) return null
        return ahead.firstOrNull { it.precipMm >= WET_MM }?.time
    }

    companion object {
        /** Millimetres in a quarter of an hour that count as precipitation rather than damp air. */
        const val WET_MM = 0.05

        val EMPTY = ConsensusForecast(emptyList(), emptyList())
    }
}
