package it.apexweather.ui.home

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WeatherRepository
import it.apexweather.ui.WeatherStateHolder
import it.apexweather.widget.ApexWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val holder: WeatherStateHolder,
    private val repository: WeatherRepository,
    private val settingsRepository: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)

    // The shared state is already built; this only stamps on whether a refresh is in flight.
    val state: StateFlow<HomeUiState> = combine(holder.home, refreshing) { home, isRefreshing ->
        home.copy(refreshing = isRefreshing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch {
            val weather = holder.weather.first()
            val age = weather.snapshot.lastSuccessfulRefresh?.let { Duration.between(it, clock.instant()) }
            if (age == null || age > Duration.ofMinutes(30)) doRefresh(weather.settings.bulletinLanguage(systemTag()))
        }
    }

    fun refresh() {
        viewModelScope.launch {
            doRefresh(settingsRepository.settings.first().bulletinLanguage(systemTag()))
        }
    }

    private fun systemTag() = Locale.getDefault().toLanguageTag()

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
