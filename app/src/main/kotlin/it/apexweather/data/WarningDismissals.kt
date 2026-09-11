package it.apexweather.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import it.apexweather.domain.model.Warning
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The warnings the reader has waved away.
 *
 * Its own store rather than a corner of the settings: this is not a preference but a record of what
 * has already been dealt with, and losing it costs one reappearing card.
 */
private val Context.dismissalStore: DataStore<Preferences> by preferencesDataStore(name = "warning_dismissals")

@Singleton
class WarningDismissals @Inject constructor(@ApplicationContext private val context: Context) {

    val dismissed: Flow<Set<String>> = context.dismissalStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[KEY].orEmpty() }

    suspend fun dismiss(warning: Warning) = context.dismissalStore.edit {
        it[KEY] = it[KEY].orEmpty() + key(warning)
    }

    suspend fun restore(warning: Warning) = context.dismissalStore.edit {
        it[KEY] = it[KEY].orEmpty() - key(warning)
    }

    /**
     * Forgets everything not in [active], so the set cannot grow without bound and a warning
     * re-issued later — MeteoAlarm gives it a new identifier — is shown again rather than staying
     * hidden behind a dismissal of the old one.
     */
    suspend fun prune(active: List<Warning>) {
        val keep = active.map(::key).toSet()
        val current = dismissed.first()
        if (current.all { it in keep }) return
        context.dismissalStore.edit { it[KEY] = current.intersect(keep) }
    }

    companion object {
        private val KEY = stringSetPreferencesKey("dismissed")

        /**
         * Identifier and level together — [Warning.noticeKey], which the notifications key on too.
         * The feed does not change a warning's level in place today; an upgrade arrives as its own
         * entry beside the old one. But if it ever did, keying on both means the upgrade
         * un-dismisses itself instead of inheriting the silence.
         */
        fun key(warning: Warning): String = warning.noticeKey
    }
}
