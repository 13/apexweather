package it.apexweather.ui

import android.content.Context
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import it.apexweather.data.PlaceCatalogue
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WarningDismissals
import it.apexweather.data.WeatherRepository
import it.apexweather.di.ApplicationScope
import it.apexweather.widget.ApexWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that decides whether opening the app should hit the network, and the one that runs
 * the refresh when it should.
 *
 * It is app-scoped rather than owned by a screen because coming back to the app is not a property
 * of any one screen. The check used to live in `HomeViewModel.init`, which runs once per place and
 * therefore once per ViewModel: a reader who left the app open and came back hours later — process
 * still alive, ViewModel still alive — was shown the morning's cache, and the only thing that would
 * have moved it was the hourly worker, which is allowed to be late. Clearing the app's data
 * "fixed" it by forcing a cold start, which is how the bug was reported.
 *
 * Putting it on the screen would have fixed the Home tab alone. The reader who leaves the app on
 * the comparison or the radar and comes back to it still has to get today's weather.
 */
@Singleton
class StaleRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val holder: WeatherStateHolder,
    private val repository: WeatherRepository,
    private val settingsRepository: SettingsRepository,
    private val catalogue: PlaceCatalogue,
    private val dismissals: WarningDismissals,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _refreshing = MutableStateFlow(false)

    /** True while a refresh is in flight, for whichever screen wants to say so. */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Refetch if the cache has gone stale. Cheap and silent when it has not. */
    fun refreshIfStale() {
        scope.launch {
            val cached = holder.awaitCached()
            if (!shouldRefresh(cached.snapshot.lastSuccessfulRefresh, clock.instant())) return@launch
            run()
        }
    }

    /** Refetch whatever the cache's age, for the reader who asked for it by hand. */
    fun refreshNow() {
        scope.launch { run() }
    }

    private suspend fun run() {
        // One refresh at a time, claimed atomically.
        //
        // The app scope outlives every screen and runs on `Dispatchers.Default`, so reading this
        // flag and then setting it — two operations — let two callers on two threads both see
        // `false` and both proceed. That race is real but it is *not* what made a first launch
        // fetch everything twice; `WeatherRepository.refresh` explains that one, and coalescing
        // there is what fixed it. This is the smaller guard, kept because it is still wrong the
        // other way.
        if (!_refreshing.compareAndSet(expect = false, update = true)) return
        try {
            val cached = holder.awaitCached()
            val place = cached.place ?: return
            val language = settingsRepository.settings.first().bulletinLanguage(Locale.getDefault().toLanguageTag())
            repository.refresh(place, language)
            // Read after the refresh, not before: the reader may have changed place while it ran,
            // and a list worked out beforehand would evict the place they are now looking at.
            evictStalePlaces()
            // A warning that has expired takes its dismissal with it, so the set cannot grow and a
            // warning re-issued later is shown again rather than inheriting the old silence.
            runCatching { dismissals.prune(holder.weather.value.snapshot.warnings) }
            runCatching { ApexWidget().updateAll(context) }
        } finally {
            _refreshing.value = false
        }
    }

    private suspend fun evictStalePlaces() {
        val settings = settingsRepository.settings.first()
        val keep = (listOf(settings.placeIstat) + settings.recentPlaces).distinct()
        repository.evictAllBut(keep, keep.mapNotNull { catalogue.byIstat(it)?.district }.distinct())
    }

    companion object {
        /**
         * How old the cache may be when the app comes forward. Short enough that the number on the
         * screen is this hour's, long enough that walking between two tabs costs nothing.
         */
        val STALE_ON_OPEN: Duration = Duration.ofMinutes(30)

        /**
         * Whether opening the app should hit the network. Null means the cache has never been
         * filled — which is also what the holder's placeholder state reports, so the caller must
         * await a real emission before asking, or this answers "yes" every single time.
         */
        fun shouldRefresh(lastSuccessfulRefresh: Instant?, now: Instant): Boolean =
            lastSuccessfulRefresh == null || Duration.between(lastSuccessfulRefresh, now) > STALE_ON_OPEN
    }
}
