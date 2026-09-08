package it.apexweather.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.apexweather.data.AppSettings
import it.apexweather.data.LanguageSetting
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WindUnit
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val repo: SettingsRepository) : ViewModel() {
    val settings: StateFlow<AppSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    fun setLanguage(l: LanguageSetting) = viewModelScope.launch { repo.setLanguage(l) }
    fun setWindUnit(u: WindUnit) = viewModelScope.launch { repo.setWindUnit(u) }
    fun setAnimations(b: Boolean) = viewModelScope.launch { repo.setAnimations(b) }
}
