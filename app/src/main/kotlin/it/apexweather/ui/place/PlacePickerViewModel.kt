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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
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
    /**
     * The pinned places, in the reader's own order, as the head of the list.
     *
     * Pinned places are held at the top rather than sorted into the alphabet because the point of a
     * pin is to be reachable without reading: home and the valley you are walking in on Saturday,
     * two taps from anywhere. They are shown while the search box is empty only — once the reader
     * is typing, the answer to what they typed is the whole list and a pinned Bozen floating above
     * a search for "Brixen" is noise.
     */
    val favourites: List<Place> = emptyList(),
    /** Whether another pin would be accepted, so the star can be shown as spent rather than broken. */
    val canPinMore: Boolean = true,
)

@HiltViewModel
class PlacePickerViewModel @Inject constructor(
    private val catalogue: PlaceCatalogue,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")

    val state: StateFlow<PlacePickerUiState> =
        combine(query, settings.settings) { q, s -> q to s }
            .map { (q, s) ->
                val locale = Locale.getDefault()
                // Both of these are off the main thread: search folds and collates 116 names, and
                // this runs on every keystroke. See PlaceCatalogue.
                val all = catalogue.search(q, locale)
                PlacePickerUiState(
                    query = q,
                    places = all,
                    selected = s.placeIstat,
                    // A pinned code the catalogue no longer knows is dropped rather than shown as a
                    // blank row: a regenerated catalogue may lose a municipality, and the reader's
                    // stale pin is not a reason to draw nothing.
                    favourites = if (q.isBlank()) s.favouritePlaces.mapNotNull { catalogue.byIstat(it) } else emptyList(),
                    canPinMore = s.favouritePlaces.size < SettingsRepository.MAX_FAVOURITE_PLACES,
                )
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlacePickerUiState())

    fun onQuery(q: String) {
        query.value = q
    }

    /**
     * Writes the choice and lets the caller leave. Everything else — the cache the repository reads,
     * the models it fetches, the bulletin's district — follows from the settings change.
     */
    fun pick(istat: String) = viewModelScope.launch { settings.setPlace(istat) }

    /**
     * Pins or unpins a place. A pinned place is kept in the cache — see [AppSettings.keptPlaces] —
     * so it opens with no signal, which is what a pin is actually worth on a mountain.
     */
    fun setFavourite(istat: String, favourite: Boolean) =
        viewModelScope.launch { settings.setFavourite(istat, favourite) }
}
