package it.apexweather.ui

import it.apexweather.data.AppSettings
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WeatherRepository
import it.apexweather.di.ApplicationScope
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.model.ConsensusForecast
import it.apexweather.domain.model.WeatherSnapshot
import it.apexweather.ui.home.HomeStateBuilder
import it.apexweather.ui.home.HomeUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    blender: ConsensusBlender,
    private val clock: Clock,
    @ApplicationScope scope: CoroutineScope,
) {
    private val minuteTick = flow { while (true) { emit(Unit); delay(60_000) } }

    /** Re-subscribes to the repository only when the bulletin language changes, not on every settings edit. */
    private val snapshots = settingsRepository.settings
        .map { it.bulletinLanguage(Locale.getDefault().toLanguageTag()) }
        .distinctUntilChanged()
        .flatMapLatest { language -> repository.snapshot(language) }

    val weather: StateFlow<WeatherState> =
        combine(snapshots, settingsRepository.settings, minuteTick) { snapshot, settings, _ ->
            WeatherState(snapshot, settings, blender.blend(snapshot.forecasts), clock.instant())
        }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), WeatherState())

    /** The home screen's state, built once and shared with the sky behind every tab. */
    val home: StateFlow<HomeUiState> =
        weather.map { HomeStateBuilder.build(it.snapshot, it.settings, it.consensus, it.now) }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
}
