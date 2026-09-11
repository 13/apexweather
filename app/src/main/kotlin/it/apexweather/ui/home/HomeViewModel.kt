package it.apexweather.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.apexweather.data.WarningDismissals
import it.apexweather.domain.model.Warning
import it.apexweather.ui.StaleRefresher
import it.apexweather.ui.WeatherStateHolder
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val holder: WeatherStateHolder,
    private val refresher: StaleRefresher,
    private val dismissals: WarningDismissals,
) : ViewModel() {

    // The shared state is already built; this only stamps on whether a refresh is in flight.
    val state: StateFlow<HomeUiState> = combine(holder.home, refresher.refreshing) { home, isRefreshing ->
        home.copy(refreshing = isRefreshing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch {
            // Once per place, not once per launch. A place the reader has just chosen has no cache
            // and nothing else would ever fill it, so this has to fire on the switch as well as on
            // the cold start — and the staleness rule below is the same one for both, so returning
            // to a place refreshed minutes ago still costs nothing.
            //
            // The placeholder every StateFlow starts on has no place and no refresh time; taking it
            // for a real emission is what used to refetch all seven models on every cold start.
            holder.weather
                .filter { it.place != null }
                .distinctUntilChangedBy { it.place!!.istat }
                .collect { refresher.refreshIfStale() }
        }
    }

    /** The reader asked by hand — pull to refresh, or the button in the settings sheet. */
    fun refresh() = refresher.refreshNow()

    /** The app came to the foreground, on whichever tab; [StaleRefresher] decides whether to fetch. */
    fun onResumed() = refresher.refreshIfStale()

    fun dismissWarning(warning: Warning) = viewModelScope.launch { dismissals.dismiss(warning) }

    fun restoreWarning(warning: Warning) = viewModelScope.launch { dismissals.restore(warning) }


}
