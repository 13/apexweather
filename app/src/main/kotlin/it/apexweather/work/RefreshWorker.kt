package it.apexweather.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import it.apexweather.data.RefreshResult
import it.apexweather.data.PlaceCatalogue
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WeatherRepository
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.Place
import it.apexweather.domain.SouthTyrol
import it.apexweather.notify.NotificationDecider
import it.apexweather.notify.NotifyStore
import it.apexweather.notify.WeatherNotifier
import it.apexweather.ui.common.Formats
import it.apexweather.ui.home.HomeStateBuilder
import it.apexweather.widget.ApexWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.util.Locale

@HiltWorker
class RefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: WeatherRepository,
    private val settings: SettingsRepository,
    private val catalogue: PlaceCatalogue,
    private val blender: ConsensusBlender,
    private val notifier: WeatherNotifier,
    private val notifyStore: NotifyStore,
    private val clock: Clock,
    private val radar: it.apexweather.data.RadarNowSource,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val appSettings = settings.settings.first()
        val language = appSettings.bulletinLanguage(Locale.getDefault().toLanguageTag())
        // The background refresh is about the place the reader has chosen. The others go stale and
        // say so when they are returned to, which is what this app already does for a failed source.
        val place = (catalogue.byIstat(appSettings.placeIstat)
            ?: checkNotNull(catalogue.byIstat(SouthTyrol.DEFAULT_ISTAT)))
            .withAmateurStation(appSettings.amateurStations)
        val result = try {
            repository.refresh(place, language)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        try {
            // After the refresh, from settings read then: the reader may have changed place while
            // it ran, and a list worked out beforehand would evict the place they are now looking at.
            val current = settings.settings.first()
            refreshPins(current, language, except = place.istat)
            val keep = current.keptPlaces
            repository.evictAllBut(keep, keep.mapNotNull { catalogue.byIstat(it)?.district }.distinct())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A cache left slightly too large is not worth failing the refresh over.
        }
        try {
            ApexWidget().updateAll(applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // widget update failure must not affect the refresh outcome
        }
        try {
            notify(place, appSettings, language)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nor must a notification: the data is already stored and the widget already updated.
        }
        return outcome(result, runAttemptCount)
    }

    /**
     * Reads back what was just stored — cache, not network — and decides from that. Reading the
     * cache rather than the refresh result is what makes an offline hour behave correctly: the
     * warning that came in an hour ago is still in force, and still worth announcing once.
     */
    /**
     * The pinned places, on a connection nobody is paying for by the megabyte.
     *
     * A pin keeps a place in the cache, and the cache was the whole of what it did: nothing kept
     * the place *current*, so pinning the valley you ski in and not opening it for a week left a
     * week-old forecast to be found the one time it mattered — opening it somewhere with no signal,
     * which is the state a mountain is usually in. Keeping and keeping fresh are different promises
     * and only one of them was being met.
     *
     * **Unmetered only**, and that is the whole of the rule. A place costs about 60 kB gzipped an
     * hour — 30,7 for the village's eleven models, 1,8 for the station's, the rest ensembles and the
     * regional upstreams — so four pins is five times today's data and five times the work per
     * wake. On Wi-Fi that is nothing; on a phone roaming over a pass it is somebody's money, and the
     * place they are actually looking at is refreshed either way.
     *
     * Each pin is independent: one failing must not cost the others, and none of them may fail the
     * refresh of the place the reader is on, which has already happened by the time this runs.
     */
    private suspend fun refreshPins(current: it.apexweather.data.AppSettings, language: String, except: String) {
        val connectivity = applicationContext.getSystemService(android.net.ConnectivityManager::class.java)
        // No connectivity service to ask is not a licence to spend somebody's data.
        val metered = connectivity?.isActiveNetworkMetered ?: true
        pinsToRefresh(current, except, metered).forEach { istat ->
            val pinned = (catalogue.byIstat(istat) ?: return@forEach).withAmateurStation(current.amateurStations)
            try {
                repository.refresh(pinned, language)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A pin that could not be reached keeps whatever it had, and says how old it is
                // when it is opened. That is the same bargain every source in this app makes.
            }
        }
    }

    private suspend fun notify(place: Place, appSettings: it.apexweather.data.AppSettings, language: String) {
        if (!appSettings.anyNotification || !notifier.canPost()) return
        val snapshot = repository.snapshot(place, language).first()
        val now = clock.instant()
        // The radar at the place, so "rain starting" is not announced while it is already raining:
        // one tile, and a failure is simply no reading.
        val radarNow = it.apexweather.data.runCatchingCancellable { radar.latestAt(place.lat, place.lon) }.getOrNull()
        val home = HomeStateBuilder.build(
            place, snapshot, appSettings, blender.blend(snapshot.forecastsForBlend, snapshot.modelBias, now, snapshot.ensemble), now,
            radar = radarNow,
        )
        val memory = notifyStore.read()
        // The worker renders outside a composition, so it resolves the reader's language and clock
        // preference from its own context, exactly as the widget does.
        val locale = applicationContext.resources.configuration.locales[0]
        val formats = Formats(locale, android.text.format.DateFormat.is24HourFormat(applicationContext))
        val decided = NotificationDecider.decide(home, appSettings, memory, now, SouthTyrol.ZONE, place.name(locale))
        val posted = notifier.post(decided, formats, now)
        // Only what actually reached the reader is remembered, so a notification Android dropped is
        // tried again on the next refresh rather than silently counted as delivered.
        if (posted.isNotEmpty()) {
            notifyStore.write(NotificationDecider.remember(memory, posted, home.warnings.map { it.noticeKey }.toSet()))
        }
    }

    companion object {
        /**
         * Which pinned places to refresh in the background this wake.
         *
         * Pure, so the rule can be read and tested without a worker — the same reason [outcome] is.
         *
         * The place on screen is refreshed by the caller whatever the connection; this is only about
         * the others. A place costs about 60 kB gzipped an hour, so four pins is five times the data
         * and five times the work per wake: on Wi-Fi that is nothing, and on a phone roaming over a
         * pass it is somebody's money.
         */
        fun pinsToRefresh(settings: it.apexweather.data.AppSettings, current: String, metered: Boolean): List<String> =
            if (metered) emptyList() else settings.favouritePlaces.filterNot { it == current }

        fun outcome(result: RefreshResult?, runAttemptCount: Int): Result = when {
            result == null -> Result.retry()
            result.allFailed -> if (runAttemptCount < 2) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }
}
