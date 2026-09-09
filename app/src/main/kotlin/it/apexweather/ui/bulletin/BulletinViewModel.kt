package it.apexweather.ui.bulletin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.apexweather.domain.model.Bulletin
import it.apexweather.domain.model.SourceStatus
import it.apexweather.ui.WeatherStateHolder
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class BulletinUiState(val loading: Boolean = true, val bulletin: Bulletin? = null, val status: SourceStatus? = null)

@HiltViewModel
class BulletinViewModel @Inject constructor(holder: WeatherStateHolder) : ViewModel() {
    val state: StateFlow<BulletinUiState> = holder.weather
        .map { BulletinUiState(false, it.snapshot.bulletin, it.snapshot.bulletinStatus) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BulletinUiState())
}
