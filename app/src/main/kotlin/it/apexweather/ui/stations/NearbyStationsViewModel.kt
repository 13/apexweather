package it.apexweather.ui.stations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.apexweather.data.NearbyStationsRepository
import it.apexweather.data.SettingsRepository
import it.apexweather.data.runCatchingCancellable
import it.apexweather.ui.WeatherStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

/**
 * Every weather station around the chosen place, and which one this place reads.
 *
 * **The fetching lives in [NearbyStationsRepository] and not here**, because the comparison
 * screen's card wants the same neighbourhood and each fetching for itself would cost two lots of
 * eleven Weather Underground requests against a cap of 1500 a day. The repository holds one place's
 * answer for ten minutes, so opening this screen after the card costs nothing.
 */
@HiltViewModel
class NearbyStationsViewModel @Inject constructor(
    private val holder: WeatherStateHolder,
    private val stations: NearbyStationsRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NearbyStationsUiState())
    val state: StateFlow<NearbyStationsUiState> = _state

    init {
        viewModelScope.launch { load() }
    }

    /**
     * Picks this station for this place, and reloads so the screen shows the new state.
     *
     * A row whose ground is unknown has no record to send — see [StationRow.asChosen] — and its card
     * offers no action, so this is a no-op rather than a guard that can be got round.
     */
    fun choose(row: StationRow) {
        val place = holder.weather.value.place ?: return
        val record = row.asChosen(place.istat) ?: return
        viewModelScope.launch {
            settings.setChosenStation(place.istat, record)
            // The write goes to DataStore and comes back through the settings flow, the place flow
            // and WeatherStateHolder before `place.readingStation` is the new one — and `load()`
            // reads that to decide which card is marked.
            holder.weather.first { it.place?.readingStation?.code == record.code }
            load()
        }
    }

    private suspend fun load() = withContext(Dispatchers.Default) {
        // One read of the shared state: the place the reader is on and the province's own reading of
        // it, which is already in memory and costs nothing to take.
        val weather = holder.weather.first { it.place != null }
        val place = checkNotNull(weather.place)
        val name = place.name(Locale.getDefault())
        val found = runCatchingCancellable { stations.neighbourhood(place) }.getOrElse {
            _state.value = NearbyStationsUiState(
                placeName = name, loading = false, failed = it.message ?: it.javaClass.simpleName,
            )
            return@withContext
        }
        if (found == null) {
            // The entry point should not have been reachable, but a key can be cleared while the
            // screen is open. Saying so beats an empty list that looks like "no stations here".
            _state.value = NearbyStationsUiState(placeName = name, loading = false, failed = NO_KEY)
            return@withContext
        }
        _state.value = NearbyStationsStateBuilder.build(
            place = place,
            probes = found.stations,
            provincial = weather.snapshot.officialObservation,
            locale = Locale.getDefault(),
        )
    }

    companion object {
        const val NO_KEY = "no-key"
    }
}
