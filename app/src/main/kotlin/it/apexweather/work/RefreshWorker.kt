package it.apexweather.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WeatherRepository
import it.apexweather.widget.ApexWidget
import kotlinx.coroutines.flow.first
import java.util.Locale

@HiltWorker
class RefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: WeatherRepository,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val language = settings.settings.first().bulletinLanguage(Locale.getDefault().toLanguageTag())
        val result = runCatching { repository.refresh(language) }.getOrNull()
        runCatching { ApexWidget().updateAll(applicationContext) }
        return when {
            result == null -> Result.retry()
            result.allFailed -> if (runAttemptCount < 3) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }
}
