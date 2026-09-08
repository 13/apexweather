package it.apexweather.ui.sky

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WeatherRepository
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.SkyPalette
import it.apexweather.ui.home.HomeStateBuilder
import it.apexweather.ui.home.HomeUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.util.Locale
import javax.inject.Inject

data class SkyUiState(val palette: SkyPalette = HomeUiState().palette, val animations: Boolean = true)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SkyViewModel @Inject constructor(
    repository: WeatherRepository,
    settingsRepository: SettingsRepository,
    blender: ConsensusBlender,
    clock: Clock,
) : ViewModel() {
    private val tick = flow { while (true) { emit(Unit); delay(60_000) } }
    val state: StateFlow<SkyUiState> = combine(
        settingsRepository.settings.flatMapLatest { s ->
            repository.snapshot(s.bulletinLanguage(Locale.getDefault().toLanguageTag())).map { s to it }
        },
        tick,
    ) { (settings, snapshot), _ ->
        val home = HomeStateBuilder.build(snapshot, settings, blender.blend(snapshot.forecasts), clock.instant())
        SkyUiState(home.palette, settings.animations)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SkyUiState())
}
