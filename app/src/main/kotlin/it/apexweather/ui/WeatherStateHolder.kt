package it.apexweather.ui

import it.apexweather.data.AppSettings
import it.apexweather.data.PlaceCatalogue
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WarningDismissals
import it.apexweather.data.WeatherRepository
import it.apexweather.di.ApplicationScope
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.Place
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.model.ConsensusForecast
import it.apexweather.domain.model.WeatherSnapshot
import it.apexweather.ui.home.HomeStateBuilder
import it.apexweather.ui.home.HomeUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the screens derive their state from, blended once. */
data class WeatherState(
    val place: Place? = null,
    val dismissedWarnings: Set<String> = emptySet(),
    val snapshot: WeatherSnapshot = WeatherSnapshot.EMPTY,
    val settings: AppSettings = AppSettings(),
    val consensus: ConsensusForecast = ConsensusForecast.EMPTY,
    val now: Instant = Instant.EPOCH,
)

/**
 * One subscription to the cache and one blend of the seven models for the whole app.
 *
 * Home and Sky are both created at the top of the composition, so before this existed they each
 * held their own minute tick and blended the same forecasts every 60 seconds on every screen —
 * and Sky then discarded all of it but the palette. Four view models also subscribed to the
 * repository separately, so every emission decoded all seven cached forecasts four times over.
 *
 * The flows stay hot for five seconds after the last collector, which carries them across
 * configuration changes and tab switches without a re-blend.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class WeatherStateHolder @Inject constructor(
    repository: WeatherRepository,
    settingsRepository: SettingsRepository,
    private val catalogue: PlaceCatalogue,
    private val dismissals: WarningDismissals,
    blender: ConsensusBlender,
    private val clock: Clock,
    @ApplicationScope scope: CoroutineScope,
) {
    /** Identity matters: `awaitCached` tells a real emission from this by reference. */
    private val initial = WeatherState()

    private val minuteTick = flow { while (true) { emit(Unit); delay(60_000) } }

    private val settings = settingsRepository.settings

    /**
     * The chosen place, or the default where the stored code names nothing the catalogue knows —
     * an app with no place at all has nothing to show, and a code can outlive a municipal merger.
     */
    private val place: Flow<Place> = settings
        .map { it.placeIstat }
        .distinctUntilChanged()
        .map { istat ->
            catalogue.byIstat(istat) ?: checkNotNull(catalogue.byIstat(SouthTyrol.DEFAULT_ISTAT)) {
                "the catalogue is missing its own default place"
            }
        }

    /**
     * Re-subscribes to the repository when the place or the bulletin language changes, and on
     * nothing else: flipping the animations switch must not re-blend seven models.
     */
    private val snapshots = combine(
        place,
        settings.map { it.bulletinLanguage(Locale.getDefault().toLanguageTag()) },
    ) { p, language -> p to language }
        .distinctUntilChanged()
        .flatMapLatest { (p, language) -> repository.snapshot(p, language).map { p to it } }

    /**
     * The blend depends on the forecasts and nothing else, so it sits on its own upstream. Folding it
     * into the combine below would re-blend all seven models every minute and on every settings edit —
     * flipping the animations switch would have re-run it.
     */
    private val blended = snapshots
        // forecastsForBlend, not forecasts: a model run that has gone stale stays visible per source
        // with its age beside it, but is kept out of the number the app leads with.
        .map { (place, snapshot) -> Triple(place, snapshot, blender.blend(snapshot.forecastsForBlend)) }
        .flowOn(Dispatchers.Default)

    val weather: StateFlow<WeatherState> =
        combine(blended, settings, dismissals.dismissed, minuteTick) { (place, snapshot, consensus), settings, dismissed, _ ->
            WeatherState(place, dismissed, snapshot, settings, consensus, clock.instant())
        }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)

    /**
     * The first state that came from the cache rather than the placeholder every StateFlow starts on.
     * A caller that needs to know how old the data is has to await this, not `weather.value`.
     */
    suspend fun awaitCached(): WeatherState = weather.first { it !== initial }

    /** The home screen's state, built once and shared with the sky behind every tab. */
    val home: StateFlow<HomeUiState> =
        weather.map { HomeStateBuilder.build(it.place, it.snapshot, it.settings, it.consensus, it.now, it.dismissedWarnings) }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
}
