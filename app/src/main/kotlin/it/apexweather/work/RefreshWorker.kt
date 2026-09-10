package it.apexweather.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import it.apexweather.data.RefreshResult
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WeatherRepository
import it.apexweather.domain.ConsensusBlender
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
    private val blender: ConsensusBlender,
    private val notifier: WeatherNotifier,
    private val notifyStore: NotifyStore,
    private val clock: Clock,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val appSettings = settings.settings.first()
        val language = appSettings.bulletinLanguage(Locale.getDefault().toLanguageTag())
        val result = try {
            repository.refresh(language)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        try {
            ApexWidget().updateAll(applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // widget update failure must not affect the refresh outcome
        }
        try {
            notify(appSettings, language)
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
    private suspend fun notify(appSettings: it.apexweather.data.AppSettings, language: String) {
        if (!appSettings.anyNotification || !notifier.canPost()) return
        val snapshot = repository.snapshot(language).first()
        val now = clock.instant()
        val home = HomeStateBuilder.build(snapshot, appSettings, blender.blend(snapshot.forecastsForBlend), now)
        val memory = notifyStore.read()
        val decided = NotificationDecider.decide(home, appSettings, memory, now, SouthTyrol.ZONE)
        val formats = Formats(
            applicationContext.resources.configuration.locales[0],
            android.text.format.DateFormat.is24HourFormat(applicationContext),
        )
        val posted = notifier.post(decided, formats, now)
        // Only what actually reached the reader is remembered, so a notification Android dropped is
        // tried again on the next refresh rather than silently counted as delivered.
        if (posted.isNotEmpty()) {
            notifyStore.write(NotificationDecider.remember(memory, posted, home.warnings.map { it.identifier }.toSet()))
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
