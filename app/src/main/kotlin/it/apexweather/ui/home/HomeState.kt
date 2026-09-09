package it.apexweather.ui.home

import it.apexweather.data.AppSettings
import it.apexweather.domain.SkyPalette
import it.apexweather.domain.SkyPaletteSelector
import it.apexweather.domain.SunPhase
import it.apexweather.domain.SunPhaseCalculator
import it.apexweather.domain.DailyAggregator
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.model.Bulletin
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.ConsensusDay
import it.apexweather.domain.model.ConsensusForecast
import it.apexweather.domain.model.ConsensusHour
import it.apexweather.domain.model.DailyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.StationObservation
import it.apexweather.domain.model.WeatherSnapshot
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class HomeUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val isEmpty: Boolean = true,
    val now: Instant = Instant.EPOCH,
    val phase: SunPhase = SunPhase.DAY,
    val sunrise: Instant? = null,
    val sunset: Instant? = null,
    val palette: SkyPalette = SkyPaletteSelector.select(Condition.PARTLY_CLOUDY, SunPhase.DAY, 0.0),
    val heroTempC: Double? = null,
    val heroFeelsLikeC: Double? = null,
    val heroCondition: Condition = Condition.PARTLY_CLOUDY,
    val bandHalfWidth: Double? = null,
    val currentHour: ConsensusHour? = null,
    val observation: StationObservation? = null,
    val upcomingHours: List<ConsensusHour> = emptyList(),
    val days: List<ConsensusDay> = emptyList(),
    /** Every consensus hour of the week, grouped by local day, so a day sheet can show its hours. */
    val hoursByDate: Map<LocalDate, List<ConsensusHour>> = emptyMap(),
    /** What each model on its own says about each day. */
    val sourceDays: Map<Source, Map<LocalDate, DailyPoint>> = emptyMap(),
    val bulletin: Bulletin? = null,
    val updatedAt: Instant? = null,
    val offline: Boolean = false,
    val settings: AppSettings = AppSettings(),
) {
    /**
     * Sun phase at an arbitrary instant, using the sunrise/sunset of the local day [t] falls on,
     * so hours beyond today still get a day/night icon of their own. Falls back to today's
     * sunrise/sunset and finally to the calculator's 07–19 local rule.
     */
    fun phaseAt(t: Instant): SunPhase {
        val day = days.firstOrNull { it.date == t.atZone(DorfTirol.ZONE).toLocalDate() }
        return SunPhaseCalculator.phase(t, day?.sunrise ?: sunrise, day?.sunset ?: sunset, DorfTirol.ZONE)
    }

    /**
     * What each model says about [date]. Models whose forecast does not reach that far are absent
     * rather than filled in, so a day late in the week honestly shows fewer of them.
     */
    fun sourcesForDay(date: LocalDate): Map<Source, DailyPoint> =
        sourceDays.mapNotNull { (source, byDate) -> byDate[date]?.let { source to it } }
            .sortedBy { it.first.ordinal }
            .toMap()
}

object HomeStateBuilder {
    private val OBSERVATION_MAX_AGE: Duration = Duration.ofMinutes(90)

    fun build(snapshot: WeatherSnapshot, settings: AppSettings, consensus: ConsensusForecast, now: Instant): HomeUiState {
        val thisHour = now.truncatedTo(ChronoUnit.HOURS)
        val upcoming = consensus.hourly.filter { !it.time.isBefore(thisHour) }.take(48)
        val current = upcoming.firstOrNull()
        val today = consensus.daily.firstOrNull { it.date == now.atZone(DorfTirol.ZONE).toLocalDate() }
        val phase = SunPhaseCalculator.phase(now, today?.sunrise, today?.sunset, DorfTirol.ZONE)
        val obs = snapshot.observation?.takeIf { Duration.between(it.time, now) <= OBSERVATION_MAX_AGE && it.tempC != null }
        val heroCondition = current?.condition ?: Condition.PARTLY_CLOUDY
        val isEmpty = current == null && obs == null && snapshot.bulletin == null
        return HomeUiState(
            loading = false,
            isEmpty = isEmpty,
            now = now,
            phase = phase,
            sunrise = today?.sunrise,
            sunset = today?.sunset,
            palette = SkyPaletteSelector.select(heroCondition, phase, current?.precipMm ?: 0.0),
            heroTempC = obs?.tempC ?: current?.tempC,
            heroFeelsLikeC = current?.feelsLikeC,
            heroCondition = heroCondition,
            bandHalfWidth = current?.let { (it.tempMaxC - it.tempMinC) / 2.0 },
            currentHour = current,
            observation = obs,
            upcomingHours = upcoming,
            days = consensus.daily.filter { !it.date.isBefore(now.atZone(DorfTirol.ZONE).toLocalDate()) }.take(7),
            hoursByDate = consensus.hourly.groupBy { it.time.atZone(DorfTirol.ZONE).toLocalDate() },
            sourceDays = DailyAggregator.perSource(snapshot.forecasts, DorfTirol.ZONE),
            bulletin = snapshot.bulletin,
            updatedAt = snapshot.lastSuccessfulRefresh,
            offline = snapshot.lastRefreshFailed,
            settings = settings,
        )
    }
}
