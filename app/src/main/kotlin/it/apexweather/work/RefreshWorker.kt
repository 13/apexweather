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
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val appSettings = settings.settings.first()
        val language = appSettings.bulletinLanguage(Locale.getDefault().toLanguageTag())
        // The background refresh is about the place the reader has chosen. The others go stale and
        // say so when they are returned to, which is what this app already does for a failed source.
        val place = catalogue.byIstat(appSettings.placeIstat)
            ?: checkNotNull(catalogue.byIstat(SouthTyrol.DEFAULT_ISTAT))
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
            val keep = (listOf(current.placeIstat) + current.recentPlaces).distinct()
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
    private suspend fun notify(place: Place, appSettings: it.apexweather.data.AppSettings, language: String) {
        if (!appSettings.anyNotification || !notifier.canPost()) return
        val snapshot = repository.snapshot(place, language).first()
        val now = clock.instant()
        val home = HomeStateBuilder.build(place, snapshot, appSettings, blender.blend(snapshot.forecastsForBlend, snapshot.modelBias, now, snapshot.ensemble), now)
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
        fun outcome(result: RefreshResult?, runAttemptCount: Int): Result = when {
            result == null -> Result.retry()
            result.allFailed -> if (runAttemptCount < 2) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }
}
