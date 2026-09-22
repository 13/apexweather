package it.apexweather.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
        val keep = settings.keptPlaces
        repository.evictAllBut(keep, keep.mapNotNull { catalogue.byIstat(it)?.district }.distinct())
    }

    // ---- the loop that runs while the app is in front of somebody ----

    /**
     * The timing lives in [ForegroundRefreshLoop] and the wiring lives here.
     *
     * The loop takes functions rather than these objects so that a test of a `delay` does not have
     * to stand up a DataStore, a Room database, an asset read and three `WhileSubscribed` flows to
     * observe one.
     */
    private val loop = ForegroundRefreshLoop(
        clock = clock,
        scope = scope,
        seed = {
            val cached = holder.awaitCached().snapshot
            ForegroundRefreshLoop.Seed(cached.lastSuccessfulRefresh, cached.lastObservationFetch)
        },
        place = { holder.weather.value.place },
        fullRefresh = {
            val before = holder.weather.value.snapshot.lastSuccessfulRefresh
            run()
            val after = holder.weather.value.snapshot.lastSuccessfulRefresh
            after != null && after != before
        },
        stationRefresh = { repository.refreshObservation(it) },
        metered = { isMetered() },
        online = { isOnline() },
    )

    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Starts refreshing while the app is open.
     *
     * Called by [it.apexweather.ApexApplication] from its count of started activities rather than
     * by a screen: being open is not a property of any one screen, which is the same argument that
     * put [refreshIfStale] above the tabs.
     */
    @Synchronized
    fun start() {
        registerNetworkCallback()
        loop.start()
    }

    @Synchronized
    fun stop() {
        loop.stop()
        unregisterNetworkCallback()
    }

    private fun connectivity(): ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)

    /** Unknown counts as metered, as `RefreshWorker` already has it: the cautious direction. */
    private fun isMetered(): Boolean = connectivity()?.isActiveNetworkMetered ?: true

    /**
     * Whether there is a network to try at all.
     *
     * Deliberately **not** `NET_CAPABILITY_VALIDATED`. Validation is the platform's opinion about
     * whether a network reaches the internet, it is reported differently across OEMs, and a captive
     * portal that answers wrongly is a fetch that fails — which the backoff already handles, and
     * handles more honestly than a prediction would. What this rules out is the case worth ruling
     * out: no network at all, where trying every ten minutes on a mountain is the battery bug this
     * feature would be accused of.
     */
    private fun isOnline(): Boolean {
        val cm = connectivity() ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Wakes the loop the moment signal returns.
     *
     * This is the mountain case and the only reason the registration earns its keep: without it a
     * reader walking back into coverage waits out whatever backoff the dead half hour built up, and
     * the app they have just pulled out of a pocket shows them the weather from before the tunnel.
     */
    private fun registerNetworkCallback() {
        if (callback != null) return
        val cm = connectivity() ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = loop.wake()
        }
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                cb,
            )
            callback = cb
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = callback ?: return
        callback = null
        runCatching { connectivity()?.unregisterNetworkCallback(cb) }
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
