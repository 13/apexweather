package it.apexweather.ui.home

import it.apexweather.data.AppSettings
import it.apexweather.data.WarningDismissals
import it.apexweather.domain.Place
import it.apexweather.domain.SkyPalette
import it.apexweather.domain.SkyPaletteSelector
import it.apexweather.domain.SunPhase
import it.apexweather.domain.SunPhaseCalculator
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.DailyAggregator
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.StationDownscale
import it.apexweather.domain.StationFog
import it.apexweather.domain.StationSun
import it.apexweather.domain.model.Bulletin
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.ConsensusDay
import it.apexweather.domain.model.ConsensusForecast
import it.apexweather.domain.model.ConsensusHour
import it.apexweather.domain.model.DailyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceStatus
import it.apexweather.domain.model.StationObservation
import it.apexweather.domain.model.Warning
import it.apexweather.domain.model.WeatherSnapshot
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class HomeUiState(
    /** Which municipality all of this is about. Null only before the catalogue has been read. */
    val place: Place? = null,
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
    /** The station reading, but only while it is fresh enough to stand in for the hero temperature. */
    val observation: StationObservation? = null,
    /**
     * The same reading with no age gate, for the card that shows what the station actually measured.
     * The card carries the timestamp, so an older reading is honest rather than hidden.
     */
    val station: StationObservation? = null,
    /** Civil-protection warnings in force for the province, worst first — dismissed ones included. */
    val warnings: List<Warning> = emptyList(),
    /** Keys of the warnings the reader has waved away; see [WarningDismissals.key]. */
    val dismissedWarnings: Set<String> = emptySet(),
    /** When precipitation next begins, to the quarter-hour, or null if it is already falling. */
    val minutelyStart: Instant? = null,
    /**
     * How much the station's reading had to be moved to stand for the village, in degrees. Null when
     * the hero is not a station reading, or when there was nothing to correct it with — the wording
     * beside the temperature depends on it, because a moved reading has to say so.
     */
    val heroAdjustmentC: Double? = null,
    val upcomingHours: List<ConsensusHour> = emptyList(),
    val days: List<ConsensusDay> = emptyList(),
    /** Every consensus hour of the week, grouped by local day, so a day sheet can show its hours. */
    val hoursByDate: Map<LocalDate, List<ConsensusHour>> = emptyMap(),
    /** What each model on its own says about each day. */
    val sourceDays: Map<Source, Map<LocalDate, DailyPoint>> = emptyMap(),
    val bulletin: Bulletin? = null,
    val updatedAt: Instant? = null,
    val offline: Boolean = false,
    /**
     * Sources that have never managed to deliver anything, while the app as a whole is working.
     *
     * GeoSphere AROME answered HTTP 400 to every request for months and the only place that said so
     * was four scrolls down the comparison screen. The consensus quietly went from ten models to
     * nine and the agreement badge looked exactly as confident as before — which is the one thing
     * this app is not supposed to do.
     */
    val silentSources: List<Source> = emptyList(),
    val settings: AppSettings = AppSettings(),
) {
    /**
     * Sun phase at an arbitrary instant, using the sunrise/sunset of the local day [t] falls on,
     * so hours beyond today still get a day/night icon of their own. Falls back to today's
     * sunrise/sunset and finally to the calculator's 07–19 local rule.
     */
    fun phaseAt(t: Instant): SunPhase {
        val day = days.firstOrNull { it.date == t.atZone(SouthTyrol.ZONE).toLocalDate() }
        return SunPhaseCalculator.phase(t, day?.sunrise ?: sunrise, day?.sunset ?: sunset, SouthTyrol.ZONE)
    }

    /**
     * What each model says about [date]. Models whose forecast does not reach that far are absent
     * rather than filled in, so a day late in the week honestly shows fewer of them.
     */
    /**
     * What the card shows. The sheet still lists everything, greyed, because dismissing a warning
     * should stop it shouting, not put it beyond reach.
     */
    val visibleWarnings: List<Warning>
        get() = warnings.filterNot { WarningDismissals.key(it) in dismissedWarnings }

    fun isDismissed(warning: Warning): Boolean = WarningDismissals.key(warning) in dismissedWarnings

    fun sourcesForDay(date: LocalDate): Map<Source, DailyPoint> =
        sourceDays.mapNotNull { (source, byDate) -> byDate[date]?.let { source to it } }
            .sortedBy { it.first.ordinal }
            .toMap()
}

object HomeStateBuilder {
    private val OBSERVATION_MAX_AGE: Duration = Duration.ofMinutes(90)

    /**
     * Two weeks, matching what Open-Meteo is asked for. Only the two ECMWF runs reach beyond about
     * day five, so the later days are a consensus of two from one institution; their agreement comes
     * from ECMWF's own ensemble instead, and a day down to a single model says so rather than
     * implying a consensus.
     */
    const val MAX_DAYS = 14

    fun build(
        place: Place?,
        snapshot: WeatherSnapshot,
        settings: AppSettings,
        consensus: ConsensusForecast,
        now: Instant,
        dismissedWarnings: Set<String> = emptySet(),
    ): HomeUiState {
        val thisHour = now.truncatedTo(ChronoUnit.HOURS)
        val upcomingRaw = consensus.hourly.filter { !it.time.isBefore(thisHour) }.take(48)
        val rawCurrent = upcomingRaw.firstOrNull()
        val today = consensus.daily.firstOrNull { it.date == now.atZone(SouthTyrol.ZONE).toLocalDate() }
        val phase = SunPhaseCalculator.phase(now, today?.sunrise, today?.sunset, SouthTyrol.ZONE)
        val obs = snapshot.observation?.takeIf { Duration.between(it.time, now) <= OBSERVATION_MAX_AGE && it.tempC != null }
        // The station stands somewhere else, and in this province mostly somewhere lower, so its
        // thermometer is only worth quoting once it has been carried up; where it cannot be, the
        // consensus is already at the right height and is the better number. The raw reading is the
        // last resort, never the first choice. How far it has to travel bounds how far it may be
        // moved, so the two altitudes go in with it.
        val heightDifferenceM = place?.station?.let { place.altitudeM - it.altitudeM }
        val heroFromStation = if (heightDifferenceM == null) null else obs?.let {
            StationDownscale.villageTemperature(it, snapshot.stationReference, consensus, now, heightDifferenceM)
        }
        // What the screen says it moved the reading by has to be what it actually moved it by. The
        // models' own village-minus-station gap is only part of that now — a large station anomaly
        // is carried up only in part, see StationDownscale — so the difference is read back off the
        // result rather than quoted from the gap.
        // A move too small to show is not a move: "umgerechnet (0,0°)" claims a correction that did
        // not happen, and the line without it already says which station the reading came from.
        val adjustment = heroFromStation?.let { village -> obs?.tempC?.let { village - it } }
            ?.takeIf { kotlin.math.abs(it) >= 0.05 }
        // A saturated station is the only ground truth this app has about the sky, and it applies to
        // this hour alone — which is why the hour is re-voted here rather than in the blender, where
        // it would colour all forty-eight. It cannot invent fog: something has to have forecast it.
        val current = rawCurrent?.let { h ->
            val voted = if (StationFog.impliesFog(snapshot.observation, now, h)) {
                Condition.FOG
            } else {
                ConsensusBlender.voteCondition(
                    h.perSource.values.map { it.condition },
                    h.precipMm,
                    stationSaturated = StationFog.saturated(snapshot.observation, now),
                    cloudPct = h.perSource.values.mapNotNull { it.cloudPct },
                )
            }
            // And then held to what the sunlight actually arriving allows. This runs last because
            // it outranks the fog above it — a pyranometer reading 834 W/m² is not fog, whatever
            // the humidity says — and because it can only ever lighten the answer, so it is safe to
            // apply to whatever the lines before it settled on. See StationSun.
            val revoted = snapshot.observation?.let { obs ->
                place?.station?.let { st -> StationSun.corrected(voted, obs, st.lat, st.lon, now, h) }
            } ?: voted
            if (revoted == h.condition) h else h.copy(condition = revoted)
        }
        // The strip's first column is this same hour, so it carries the re-vote too; the hero
        // disagreeing with the column directly beneath it is the failure mode this avoids.
        val upcoming = if (current == null || current === rawCurrent) upcomingRaw else listOf(current) + upcomingRaw.drop(1)
        val heroCondition = current?.condition ?: Condition.PARTLY_CLOUDY
        val isEmpty = current == null && obs == null && snapshot.bulletin == null
        return HomeUiState(
            place = place,
            loading = false,
            isEmpty = isEmpty,
            now = now,
            phase = phase,
            sunrise = today?.sunrise,
            sunset = today?.sunset,
            palette = SkyPaletteSelector.select(heroCondition, phase, current?.precipMm ?: 0.0),
            heroTempC = heroFromStation ?: current?.tempC ?: obs?.tempC,
            heroAdjustmentC = adjustment?.takeIf { heroFromStation != null },
            heroFeelsLikeC = current?.feelsLikeC,
            heroCondition = heroCondition,
            // The ensemble's own spread where it reaches this hour, the models' disagreement otherwise.
            bandHalfWidth = current?.let { it.ensembleHalfWidthC ?: (it.tempMaxC - it.tempMinC) / 2.0 },
            currentHour = current,
            observation = obs?.takeIf { heroFromStation != null },
            station = snapshot.observation,
            warnings = snapshot.warnings,
            dismissedWarnings = dismissedWarnings,
            minutelyStart = consensus.precipitationStartsAt(now),
            upcomingHours = upcoming,
            days = consensus.daily.filter { !it.date.isBefore(now.atZone(SouthTyrol.ZONE).toLocalDate()) }.take(MAX_DAYS),
            hoursByDate = consensus.hourly.groupBy { it.time.atZone(SouthTyrol.ZONE).toLocalDate() },
            sourceDays = DailyAggregator.perSource(snapshot.forecasts, SouthTyrol.ZONE),
            bulletin = snapshot.bulletin,
            updatedAt = snapshot.lastSuccessfulRefresh,
            offline = snapshot.lastRefreshFailed,
            // Only once something has worked at least once: during the very first fetch every
            // source has yet to deliver, and that is not the same as being broken.
            silentSources = if (snapshot.lastSuccessfulRefresh == null) emptyList() else {
                snapshot.status.filterValues { it is SourceStatus.Failed && it.lastIssuedAt == null }
                    .keys.sortedBy { it.ordinal }
            },
            settings = settings,
        )
    }
}
