package it.apexweather.ui.home

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WeatherRepository
import it.apexweather.domain.ConsensusBlender
import it.apexweather.widget.ApexWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.util.Locale
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: WeatherRepository,
    private val settingsRepository: SettingsRepository,
    private val blender: ConsensusBlender,
    private val clock: Clock,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val minuteTick = flow { while (true) { emit(Unit); delay(60_000) } }

    /** Re-subscribes to the repository only when the bulletin language changes, not on every settings edit. */
    private val snapshotWithSettings = settingsRepository.settings
        .map { it to it.bulletinLanguage(Locale.getDefault().toLanguageTag()) }
        .distinctUntilChangedBy { it.second }
        .flatMapLatest { (settings, language) -> repository.snapshot(language).map { settings to it } }

    val state: StateFlow<HomeUiState> =
        combine(snapshotWithSettings, settingsRepository.settings, refreshing, minuteTick) { (_, snapshot), settings, isRefreshing, _ ->
            HomeStateBuilder.build(snapshot, settings, blender.blend(snapshot.forecasts), clock.instant()).copy(refreshing = isRefreshing)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch {
            val (settings, snapshot) = snapshotWithSettings.first()
            val age = snapshot.lastSuccessfulRefresh?.let { Duration.between(it, clock.instant()) }
            if (age == null || age > Duration.ofMinutes(30)) doRefresh(settings.bulletinLanguage(Locale.getDefault().toLanguageTag()))
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            doRefresh(settings.bulletinLanguage(Locale.getDefault().toLanguageTag()))
        }
    }

    private suspend fun doRefresh(language: String) {
        if (refreshing.value) return
        refreshing.value = true
        try {
            repository.refresh(language)
            runCatching { ApexWidget().updateAll(context) }
        } finally {
            refreshing.value = false
        }
    }
}
