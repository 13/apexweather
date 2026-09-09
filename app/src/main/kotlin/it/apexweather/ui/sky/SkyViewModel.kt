package it.apexweather.ui.sky

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.apexweather.domain.SkyPalette
import it.apexweather.ui.WeatherStateHolder
import it.apexweather.ui.home.HomeUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SkyUiState(val palette: SkyPalette = HomeUiState().palette, val animations: Boolean = true)

/** The sky reads the shared state rather than blending its own copy just to pick a palette. */
@HiltViewModel
class SkyViewModel @Inject constructor(holder: WeatherStateHolder) : ViewModel() {
    val state: StateFlow<SkyUiState> = holder.home
        .map { SkyUiState(it.palette, it.settings.animations) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SkyUiState())
}
