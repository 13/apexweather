package it.apexweather.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.apexweather.data.AppSettings
import it.apexweather.data.LanguageSetting
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WindUnit
import it.apexweather.data.WuKeyChecker
import it.apexweather.ui.WeatherStateHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val keyChecker: WuKeyChecker,
    private val holder: WeatherStateHolder,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    /** Persists the language and only then runs [then]; callers refresh from there, because the
     * bulletin cache is keyed by language and a refresh started before the write would use the old one. */
    fun setLanguage(l: LanguageSetting, then: () -> Unit = {}) = viewModelScope.launch {
        repo.setLanguage(l)
        then()
    }
    fun setWindUnit(u: WindUnit) = viewModelScope.launch { repo.setWindUnit(u) }
    fun setAnimations(b: Boolean) = viewModelScope.launch { repo.setAnimations(b) }
    fun setAmateurStations(b: Boolean) = viewModelScope.launch { repo.setAmateurStations(b) }
    /**
     * The key is written first and checked after, because the checker reads it back through
     * [it.apexweather.data.WuKeySource] — checking before the write would test the old one.
     *
     * Debounced by [KEY_CHECK_DELAY_MS], because this is called on every keystroke: a typed
     * 32-character key would otherwise spend 32 of the day's 1500 requests proving the first
     * thirty-one prefixes wrong.
     */
    fun setWuApiKey(v: String) {
        keyCheck?.cancel()
        keyCheck = viewModelScope.launch {
            repo.setWuApiKey(v)
            delay(KEY_CHECK_DELAY_MS)
            val place = holder.weather.value.place ?: return@launch
            keyChecker.check(place.lat, place.lon)
        }
    }

    private var keyCheck: Job? = null
    fun setNotifySummary(b: Boolean) = viewModelScope.launch { repo.setNotifySummary(b) }
    fun setNotifySummaryHour(h: Int) = viewModelScope.launch { repo.setNotifySummaryHour(h) }
    fun setNotifyRain(b: Boolean) = viewModelScope.launch { repo.setNotifyRain(b) }
    fun setNotifyWarnings(b: Boolean) = viewModelScope.launch { repo.setNotifyWarnings(b) }

    companion object {
        /** Long enough that a key being typed is checked once, at the end. */
        const val KEY_CHECK_DELAY_MS = 1_200L
    }
}
