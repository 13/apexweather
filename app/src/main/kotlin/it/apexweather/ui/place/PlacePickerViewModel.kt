package it.apexweather.ui.place

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.apexweather.data.PlaceCatalogue
import it.apexweather.data.SettingsRepository
import it.apexweather.domain.Place
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class PlacePickerUiState(
    val query: String = "",
    val places: List<Place> = emptyList(),
    /** The ISTAT code currently in force, so the list can mark it. */
    val selected: String = "",
)

@HiltViewModel
class PlacePickerViewModel @Inject constructor(
    private val catalogue: PlaceCatalogue,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")

    val state: StateFlow<PlacePickerUiState> =
        combine(query, settings.settings.map { it.placeIstat }) { q, selected -> q to selected }
            .map { (q, selected) -> PlacePickerUiState(q, catalogue.search(q, Locale.getDefault()), selected) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlacePickerUiState())

    fun onQuery(q: String) {
        query.value = q
    }

    /**
     * Writes the choice and lets the caller leave. Everything else — the cache the repository reads,
     * the models it fetches, the bulletin's district — follows from the settings change.
     */
    fun pick(istat: String) = viewModelScope.launch { settings.setPlace(istat) }
}
