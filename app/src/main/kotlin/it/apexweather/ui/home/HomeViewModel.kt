package it.apexweather.ui.home

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import it.apexweather.data.PlaceCatalogue
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WarningDismissals
import it.apexweather.data.WeatherRepository
import it.apexweather.domain.Place
import it.apexweather.domain.model.Warning
import it.apexweather.ui.WeatherStateHolder
import it.apexweather.widget.ApexWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val holder: WeatherStateHolder,
    private val repository: WeatherRepository,
    private val settingsRepository: SettingsRepository,
    private val catalogue: PlaceCatalogue,
    private val dismissals: WarningDismissals,
    private val clock: Clock,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)

    // The shared state is already built; this only stamps on whether a refresh is in flight.
    val state: StateFlow<HomeUiState> = combine(holder.home, refreshing) { home, isRefreshing ->
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
                .collect { weather ->
                    val place = weather.place ?: return@collect
                    if (shouldRefreshOnOpen(weather.snapshot.lastSuccessfulRefresh, clock.instant())) {
                        doRefresh(place, weather.settings.bulletinLanguage(systemTag()))
                    }
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val place = holder.awaitCached().place ?: return@launch
            doRefresh(place, settingsRepository.settings.first().bulletinLanguage(systemTag()))
        }
    }

    private fun systemTag() = Locale.getDefault().toLanguageTag()

    companion object {
        private val STALE_ON_OPEN: Duration = Duration.ofMinutes(30)

        /**
         * Whether opening the app should hit the network. Null means the cache has never been
         * filled — which is also what the holder's placeholder state reports, so the caller must
         * await a real emission before asking, or this answers "yes" every single time.
         */
        fun shouldRefreshOnOpen(lastSuccessfulRefresh: Instant?, now: Instant): Boolean =
            lastSuccessfulRefresh == null || Duration.between(lastSuccessfulRefresh, now) > STALE_ON_OPEN
    }

    private suspend fun doRefresh(place: Place, language: String) {
        if (refreshing.value) return
        refreshing.value = true
        try {
            repository.refresh(place, language)
            // Read after the refresh, not before: the reader may have changed place while it ran,
            // and a list worked out beforehand would evict the place they are now looking at.
            evictStalePlaces()
            // A warning that has expired takes its dismissal with it, so the set cannot grow and a
            // warning re-issued later is shown again rather than inheriting the old silence.
            runCatching { dismissals.prune(holder.weather.value.snapshot.warnings) }
            runCatching { ApexWidget().updateAll(context) }
        } finally {
            refreshing.value = false
        }
    }

    fun dismissWarning(warning: Warning) = viewModelScope.launch { dismissals.dismiss(warning) }

    fun restoreWarning(warning: Warning) = viewModelScope.launch { dismissals.restore(warning) }

    private suspend fun evictStalePlaces() {
        val settings = settingsRepository.settings.first()
        val keep = (listOf(settings.placeIstat) + settings.recentPlaces).distinct()
        repository.evictAllBut(keep, keep.mapNotNull { catalogue.byIstat(it)?.district }.distinct())
    }
}
