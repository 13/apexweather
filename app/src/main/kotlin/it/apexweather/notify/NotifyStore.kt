package it.apexweather.notify

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What has already been announced. Its own store rather than a corner of the settings: this is
 * bookkeeping the reader never sees or edits, and clearing it would only cost one repeated
 * notification, so it is deliberately not backed up with the preferences either.
 */
private val Context.notifyStore: DataStore<Preferences> by preferencesDataStore(name = "notify_state")

@Singleton
class NotifyStore @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val lastSummaryDate = stringPreferencesKey("last_summary_date")
        val lastRainOnsetMs = longPreferencesKey("last_rain_onset_ms")
        val notifiedWarnings = stringSetPreferencesKey("notified_warnings")
    }

    suspend fun read(): NotifyMemory = context.notifyStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            NotifyMemory(
                // A preference written by an older build, or corrupted, means "nothing announced yet";
                // the cost of getting that wrong is one duplicate notification, never a crash.
                lastSummaryDate = p[Keys.lastSummaryDate]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                lastRainOnset = p[Keys.lastRainOnsetMs]?.let(Instant::ofEpochMilli),
                notifiedWarningKeys = p[Keys.notifiedWarnings].orEmpty(),
            )
        }
        .first()

    suspend fun write(memory: NotifyMemory) {
        context.notifyStore.edit { p ->
            memory.lastSummaryDate?.let { p[Keys.lastSummaryDate] = it.toString() } ?: p.remove(Keys.lastSummaryDate)
            memory.lastRainOnset?.let { p[Keys.lastRainOnsetMs] = it.toEpochMilli() } ?: p.remove(Keys.lastRainOnsetMs)
            p[Keys.notifiedWarnings] = memory.notifiedWarningKeys
        }
    }
}
