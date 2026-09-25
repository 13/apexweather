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
import it.apexweather.domain.FeelsLike
import it.apexweather.domain.Horizon
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.StationDownscale
import it.apexweather.domain.StrongWind
import it.apexweather.domain.MeasuredRain
import it.apexweather.domain.RadarNow
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
    /**
     * When the sun actually clears and loses this place's own skyline, per local day, for as many
     * days as the 48-hour strip reaches.
     *
     * These are the times a reader can check by looking out of the window, and in this province they
     * are nothing like the astronomical pair above: Dorf Tirol gets the sun 70 minutes after
     * "sunrise" in September and loses it 54 minutes before "sunset", and 86 minutes before it at
     * the winter solstice. Empty where the catalogue has no skyline for the place — see [Horizon].
     */
    val visibleSun: Map<LocalDate, Pair<Instant, Instant>> = emptyMap(),
    val palette: SkyPalette = SkyPaletteSelector.select(Condition.PARTLY_CLOUDY, SunPhase.DAY, 0.0),
    val heroTempC: Double? = null,
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
     * Strong gusts within the next twelve hours, from the models' median gust — and the station's
     * measured one for the current hour. Null on an ordinary day. See [StrongWind].
     */
    val windLine: StrongWind.Line? = null,
    /**
     * How much the station's reading had to be moved to stand for the village, in degrees. Null when
     * the hero is not a station reading, or when there was nothing to correct it with — the wording
     * beside the temperature depends on it, because a moved reading has to say so.
     */
    val heroAdjustmentC: Double? = null,
    /**
     * What the hero's hour feels like, or null where saying so would tell the reader nothing.
     *
     * Already gated: see [it.apexweather.domain.FeelsLike]. The screen prints it where it is
     * present and prints nothing where it is not — the decision is not the composable's.
     *
     * It is the **hero's** temperature plus the models' paired offset, never the models' own
     * apparent median, because the hero is usually a station reading carried up the hill and those
     * two numbers come from different populations.
     */
    val heroFeelsLikeC: Double? = null,
    /**
     * Whether [observation] came from an amateur station rather than the provincial network.
     *
     * The hero already names its station and says when it was read; this is what makes it say *what
     * kind* of station. It is not a disclaimer — the station was checked against a DEM and won on
     * eight weeks of measured stability, and it is usually the better thermometer — it is the
     * reader being told which instrument they are looking at.
     */
    val observationIsPrivate: Boolean = false,
    /**
     * The models' own value for the hero's hour — a number the app is free to publish.
     *
     * Read **only when [observationIsPrivate]**, and ignored otherwise. Weather Underground's data
     * is not licensed for redistribution and `ShareCapture` writes a PNG that goes into somebody
     * else's chat, so a card built over an amateur reading quotes this instead.
     *
     * Deliberately *not* "the hero, or this when it may not be shared". A second field that has to
     * be kept in step with [heroTempC] is one that callers will copy one of and not the other —
     * which is how a test that set only the hero got a card quoting the consensus, and would
     * eventually have been a real screen disagreeing with a real card.
     */
    val publishableTempC: Double? = null,
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
     * Refreshing has been failing, on a phone that believes it has a connection.
     *
     * A fact about failure rather than about age. Data an hour old on a mountain with no signal is
     * not a fault and the offline banner already says so; what had no symptom at all was a phone
     * with a bar of signal that cannot reach an upstream — the "Aktualisiert 09:12" line stayed
     * exactly as confident for hours. Now that the app refreshes itself while it is open, an hour
     * without a new timestamp means something has gone wrong rather than that nobody asked.
     */
    val staleOnScreen: Boolean = false,
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
        val date = t.atZone(SouthTyrol.ZONE).toLocalDate()
        val day = days.firstOrNull { it.date == date }
        val visible = visibleSun[date]
        return SunPhaseCalculator.phase(
            t, day?.sunrise ?: sunrise, day?.sunset ?: sunset, SouthTyrol.ZONE,
            visible?.first, visible?.second,
        )
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

    /**
     * How old the data may be before the line that claims its age says something is wrong.
     *
     * An hour, which is two missed full refreshes: one late cycle is a slow network, two in a row
     * on a connected phone is an upstream that has stopped answering.
     */
    val STALE_ON_SCREEN: Duration = Duration.ofHours(1)

    fun build(
        place: Place?,
        snapshot: WeatherSnapshot,
        settings: AppSettings,
        consensus: ConsensusForecast,
        now: Instant,
        dismissedWarnings: Set<String> = emptySet(),
        /** The newest radar frame at the place; the widget and the worker build without one. */
        radar: RadarNow? = null,
    ): HomeUiState {
        val thisHour = now.truncatedTo(ChronoUnit.HOURS)
        val upcomingRaw = consensus.hourly.filter { !it.time.isBefore(thisHour) }.take(48)
        val rawCurrent = upcomingRaw.firstOrNull()
        val todayDate = now.atZone(SouthTyrol.ZONE).toLocalDate()
        val today = consensus.daily.firstOrNull { it.date == todayDate }
        // The sun's own times for this place, ridge included, for the days the strip actually
        // reaches — three at most. Walking a day costs 720 closed-form solar positions, which is
        // nothing, but walking all fourteen every minute for no reader would be waste.
        val skyline = place?.horizon?.takeIf(Horizon::usable)
        val visibleSun = if (skyline == null || place == null) emptyMap() else {
            (listOf(todayDate) + upcomingRaw.map { it.time.atZone(SouthTyrol.ZONE).toLocalDate() })
                .distinct()
                .mapNotNull { date ->
                    Horizon.visibleDaylight(skyline, date, SouthTyrol.ZONE, place.lat, place.lon)
                        ?.let { date to it }
                }.toMap()
        }
        val phase = SunPhaseCalculator.phase(
            now, today?.sunrise, today?.sunset, SouthTyrol.ZONE,
            visibleSun[todayDate]?.first, visibleSun[todayDate]?.second,
        )
        val obs = snapshot.observation?.takeIf { Duration.between(it.time, now) <= OBSERVATION_MAX_AGE && it.tempC != null }
        // The station stands somewhere else, and in this province mostly somewhere lower, so its
        // thermometer is only worth quoting once it has been carried up; where it cannot be, the
        // consensus is already at the right height and is the better number. The raw reading is the
        // last resort, never the first choice. How far it has to travel bounds how far it may be
        // moved, so the two altitudes go in with it.
        val heightDifferenceM = place?.station?.let { place.altitudeM - it.altitudeM }
        // The reading on screen is an amateur one when the place *has* one and the snapshot's
        // primary is not the province's own.
        //
        // The `place.pws != null` half is load-bearing and was missing once: without it, a snapshot
        // that simply has no provincial record — a hand-built state, or a place whose SIAG fetch has
        // never succeeded — made every reading look amateur, and the share card then quietly
        // replaced a perfectly publishable hero with the models' value. Absence of the provincial
        // record is not evidence about where the reading came from.
        val isPrivate = place?.pws != null &&
            snapshot.observation != null &&
            snapshot.observation != snapshot.officialObservation
        // An amateur station is quoted as read. It was chosen for standing at the village — its
        // height checked against the DEM, or picked by the reader — so there is no hill to carry it
        // up, and moving it would swap a village thermometer for the models' idea of one. It was
        // also moved by the wrong gap: `heightDifferenceM` is the *provincial* station's, 264 m for
        // Dorf Tirol, while ITIROL16 stands 40 m above the village. StationFault is what guards a
        // broken amateur reading now; the repository then falls back to the province's, which is
        // not private and is moved as before.
        val heroFromStation = when {
            isPrivate -> obs?.tempC
            heightDifferenceM == null -> null
            else -> obs?.let {
                StationDownscale.villageTemperature(it, snapshot.stationReference, snapshot.forecastsForBlend, consensus, now, heightDifferenceM)
            }
        }
        // What the screen says it moved the reading by has to be what it actually moved it by. The
        // models' own village-minus-station gap is only part of that now — a large station anomaly
        // is carried up only in part, see StationDownscale — so the difference is read back off the
        // result rather than quoted from the gap.
        // A move too small to show is not a move: "umgerechnet (0,0°)" claims a correction that did
        // not happen, and the line without it already says which station the reading came from.
        val adjustment = heroFromStation?.takeUnless { isPrivate }?.let { village -> obs?.tempC?.let { village - it } }
            ?.takeIf { kotlin.math.abs(it) >= 0.05 }
        // A saturated station is the only ground truth this app has about the sky, and it applies to
        // this hour alone — which is why the hour is re-voted here rather than in the blender, where
        // it would colour all forty-eight. It cannot invent fog: something has to have forecast it.
        val current = rawCurrent?.let { wet ->
            // The instruments outrank the models about this minute, in both directions: a gauge
            // that has not risen is no rain now, and a rising gauge or a radar echo is rain the
            // models may have missed. See MeasuredRain for the order they are trusted in.
            val measured = MeasuredRain.now(snapshot.observation, radar, now)
            val measuredDry = measured == MeasuredRain.Dry
            val h = when {
                measuredDry && wet.precipMm > 0.0 -> wet.copy(precipMm = 0.0)
                measured is MeasuredRain.Wet && wet.precipMm < measured.mmPerHour -> wet.copy(precipMm = measured.mmPerHour)
                else -> wet
            }
            val voted = if (StationFog.impliesFog(snapshot.observation, now, h)) {
                Condition.FOG
            } else {
                // Keyed by source rather than flattened to labels, so the re-vote weighs the four
                // ICON runs the same way the blend that produced this hour did. Flattening here
                // would have the hero and the strip disagree for no reason but which overload was
                // reached for.
                ConsensusBlender.voteCondition(
                    h.perSource.mapValues { it.value.condition },
                    h.precipMm,
                    stationSaturated = StationFog.saturated(snapshot.observation, now),
                    cloudPct = h.perSource.mapNotNull { (s, p) -> p.cloudPct?.let { s to it } }.toMap(),
                    measuredDry = measuredDry,
                ).let { vote ->
                    // The models' words may all be dry while it is measurably raining; the word then
                    // comes from what was measured.
                    when {
                        measured !is MeasuredRain.Wet || vote.isPrecipitation -> vote
                        measured.snow -> Condition.SNOW
                        else -> Condition.rainFor(h.precipMm)
                    }
                }
            }
            // And then held to what the sunlight actually arriving allows. This runs last because
            // it outranks the fog above it — a pyranometer reading 834 W/m² is not fog, whatever
            // the humidity says — and because it can only ever lighten the answer, so it is safe to
            // apply to whatever the lines before it settled on. See StationSun.
            val revoted = snapshot.observation?.let { obs ->
                // The *station's* skyline, not the village's: the pyranometer is where the question
                // is asked, and in this province it is usually much lower and much more hemmed in.
                place?.station?.let { st -> StationSun.corrected(voted, obs, st.lat, st.lon, now, h, st.horizon) }
            } ?: voted
            val revotedHour = if (revoted == h.condition && h === wet) wet else h.copy(condition = revoted)
            // An anemometer outranks the models about this minute the way the gauge does; see
            // StrongWind.withMeasured. The hero's wind line and the strip's first column read it.
            StrongWind.withMeasured(revotedHour, snapshot.observation, now)
        }
        // The hero and the strip's first column both answer to the word "Jetzt", so they have to be
        // the same weather. The re-vote above gave that hour its condition; this gives it its
        // temperature, and for the same reason.
        //
        // Only the condition was ever carried across, and the temperature quietly was not. Measured
        // on the phone on 2026-09-12 at 08:14 over Dorf Tirol: the hero read 11° and the column
        // directly beneath it read 13°, with nothing on screen accounting for the gap. The models
        // had that morning 2,5 K too warm — the thermometer in Meran read 10,9 where the eight of
        // them put it at 13,35 — and the hero had the thermometer's word for it while the strip had
        // only the models. Which of the two was right is not a matter of opinion either: the
        // stations standing at the village's own height read 9,1 (St. Martin, 588 m) and 9,4
        // (Naturns, 541 m) at that hour. The corrected number was the better one and it was being
        // contradicted, on the same screen, one line down.
        //
        // This hour alone. A thermometer can only speak for the hour it measured, and the forty-seven
        // hours after it are still exactly what the models say — see StationSun, which draws the same
        // line for the same reason.
        val currentShown = current?.let { h ->
            if (heroFromStation == null || heroFromStation == h.tempC) h else h.copy(
                tempC = heroFromStation,
                // The apparent temperature has to move with the real one. Only the condition was
                // ever carried across to this hour and the temperature quietly was not; this is the
                // same omission one field further along, and it shows up as a tile in the hour
                // sheet quoting the models' feels-like over the station's air.
                feelsLikeC = h.feelsOffsetC?.let { heroFromStation + it },
            )
        }
        val upcoming = if (currentShown == null || currentShown === rawCurrent) upcomingRaw else listOf(currentShown) + upcomingRaw.drop(1)
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
            visibleSun = visibleSun,
            palette = SkyPaletteSelector.select(heroCondition, phase, current?.precipMm ?: 0.0),
            heroTempC = heroFromStation ?: current?.tempC ?: obs?.tempC,
            heroAdjustmentC = adjustment?.takeIf { heroFromStation != null },
            heroFeelsLikeC = FeelsLike.shown(heroFromStation ?: current?.tempC ?: obs?.tempC, current?.feelsOffsetC),
            observationIsPrivate = isPrivate,
            // `current` is the re-voted hour *before* its temperature was swapped for the
            // station's, which is exactly the models' own number wanted here.
            publishableTempC = current?.tempC,
            heroCondition = heroCondition,
            // The ensemble's own spread where it reaches this hour, the models' disagreement otherwise.
            bandHalfWidth = current?.let { it.ensembleHalfWidthC ?: (it.tempMaxC - it.tempMinC) / 2.0 },
            currentHour = currentShown,
            observation = obs?.takeIf { heroFromStation != null },
            station = snapshot.observation,
            warnings = snapshot.warnings,
            dismissedWarnings = dismissedWarnings,
            minutelyStart = consensus.precipitationStartsAt(now),
            windLine = StrongWind.line(upcoming, now),
            upcomingHours = upcoming,
            days = consensus.daily.filter { !it.date.isBefore(now.atZone(SouthTyrol.ZONE).toLocalDate()) }.take(MAX_DAYS),
            hoursByDate = consensus.hourly.groupBy { it.time.atZone(SouthTyrol.ZONE).toLocalDate() },
            sourceDays = DailyAggregator.perSource(snapshot.forecasts, SouthTyrol.ZONE),
            bulletin = snapshot.bulletin,
            updatedAt = snapshot.lastSuccessfulRefresh,
            offline = snapshot.lastRefreshFailed,
            // Not while offline: two things saying one thing is how the hero got long enough to
            // need cutting back in the first place.
            staleOnScreen = !snapshot.lastRefreshFailed && snapshot.lastSuccessfulRefresh != null &&
                Duration.between(snapshot.lastSuccessfulRefresh, now) > STALE_ON_SCREEN,
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
