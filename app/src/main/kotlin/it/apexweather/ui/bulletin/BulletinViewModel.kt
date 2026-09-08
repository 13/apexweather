package it.apexweather.ui.bulletin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WeatherRepository
import it.apexweather.domain.model.Bulletin
import it.apexweather.domain.model.SourceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import javax.inject.Inject

data class BulletinUiState(val loading: Boolean = true, val bulletin: Bulletin? = null, val status: SourceStatus? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BulletinViewModel @Inject constructor(repository: WeatherRepository, settingsRepository: SettingsRepository) : ViewModel() {
    val state: StateFlow<BulletinUiState> = settingsRepository.settings.flatMapLatest { s ->
        repository.snapshot(s.bulletinLanguage(Locale.getDefault().toLanguageTag())).map { BulletinUiState(false, it.bulletin, it.bulletinStatus) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BulletinUiState())
}
