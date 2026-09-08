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
    ECMWF("ECMWF IFS", false);

    /** Hours after the model run at which a cached forecast counts as stale. KMOS runs only twice a day
     * (02:00 and 14:00 local), so its threshold is the 12 h cadence plus margin for the publication delay. */
    val staleAfterHours: Int get() = when (this) {
        SIAG_KMOS -> 16
        ECMWF -> 12
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
    val windKmh: Double = 0.0,
    val gustKmh: Double? = null,
    val windDirDeg: Int? = null,
    val cloudPct: Int? = null,
    val humidityPct: Int? = null,
    val condition: Condition,
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

@Serializable
data class StationObservation(
    val stationName: String,
    @Serializable(with = InstantSerializer::class) val time: Instant,
    val tempC: Double?,
    val humidityPct: Int?,
    val windKmh: Double?,
    val windDir: String?,
    val gustKmh: Double?,
    val precipMm: Double?,
    val pressureHpa: Double?,
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
    val status: Map<Source, SourceStatus>,
    val bulletinStatus: SourceStatus?,
    val observationStatus: SourceStatus?,
    val lastSuccessfulRefresh: Instant?,
    val lastRefreshFailed: Boolean,
) {
    val isEmpty: Boolean get() = forecasts.isEmpty() && bulletin == null && observation == null
    companion object {
        val EMPTY = WeatherSnapshot(emptyMap(), null, null, emptyMap(), null, null, null, false)
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
    val windKmh: Double,
    val gustKmh: Double?,
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
    val sunrise: Instant?,
    val sunset: Instant?,
)

data class ConsensusForecast(val hourly: List<ConsensusHour>, val daily: List<ConsensusDay>) {
    companion object { val EMPTY = ConsensusForecast(emptyList(), emptyList()) }
}
