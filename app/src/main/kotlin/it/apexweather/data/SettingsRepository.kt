package it.apexweather.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.emptyPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.model.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class LanguageSetting(val tag: String?) { SYSTEM(null), DE("de"), IT("it"), EN("en") }
enum class WindUnit {
    KMH, MS;

    /** Converts a km/h value into this unit. */
    fun fromKmh(kmh: Double): Double = when (this) {
        KMH -> kmh
        MS -> kmh / 3.6
    }
}
enum class CompareVariable { TEMPERATURE, PRECIPITATION, WIND }

data class AppSettings(
    val language: LanguageSetting = LanguageSetting.SYSTEM,
    val windUnit: WindUnit = WindUnit.KMH,
    val animations: Boolean = true,
    val compareSources: Set<Source> = Source.entries.toSet(),
    val compareVariable: CompareVariable = CompareVariable.TEMPERATURE,
    /**
     * Notifications, every one of them off until asked for. Android's own permission is a second
     * gate on top of these: a switch on here with the permission refused posts nothing.
     */
    val notifySummary: Boolean = false,
    /** Local hour the morning summary is posted at, on the first refresh at or after it. */
    val notifySummaryHour: Int = 7,
    val notifyRain: Boolean = false,
    val notifyWarnings: Boolean = false,
    /** The chosen municipality's ISTAT code. */
    val placeIstat: String = SouthTyrol.DEFAULT_ISTAT,
    /**
     * Places by last use, most recent first. This is what cache eviction reads: anything not in
     * here is deleted after the next refresh, so its length is the number of places the app keeps.
     */
    val recentPlaces: List<String> = emptyList(),
) {
    val anyNotification: Boolean get() = notifySummary || notifyRain || notifyWarnings

    /** Language used for the SIAG bulletin: explicit setting, else system language, else German. */
    fun bulletinLanguage(systemTag: String): String {
        language.tag?.let { return it }
        val lang = systemTag.substringBefore('-').lowercase()
        return if (lang in setOf("de", "it", "en")) lang else "de"
    }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val language = stringPreferencesKey("language")
        val windUnit = stringPreferencesKey("wind_unit")
        val animations = booleanPreferencesKey("animations")
        val compareSources = stringSetPreferencesKey("compare_sources")
        val compareVariable = stringPreferencesKey("compare_variable")
        val notifySummary = booleanPreferencesKey("notify_summary")
        val notifySummaryHour = intPreferencesKey("notify_summary_hour")
        val notifyRain = booleanPreferencesKey("notify_rain")
        val notifyWarnings = booleanPreferencesKey("notify_warnings")
        val placeIstat = stringPreferencesKey("place_istat")
        // A comma-joined list rather than a string set: the order is the whole point of it, and
        // DataStore's set preference does not keep one.
        val recentPlaces = stringPreferencesKey("recent_places")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }.map { p ->
        AppSettings(
            language = p[Keys.language]?.let { runCatching { LanguageSetting.valueOf(it) }.getOrNull() } ?: LanguageSetting.SYSTEM,
            windUnit = p[Keys.windUnit]?.let { runCatching { WindUnit.valueOf(it) }.getOrNull() } ?: WindUnit.KMH,
            animations = p[Keys.animations] ?: true,
            compareSources = p[Keys.compareSources]?.mapNotNull { runCatching { Source.valueOf(it) }.getOrNull() }?.toSet()
                ?: Source.entries.toSet(),
            compareVariable = p[Keys.compareVariable]?.let { runCatching { CompareVariable.valueOf(it) }.getOrNull() }
                ?: CompareVariable.TEMPERATURE,
            notifySummary = p[Keys.notifySummary] ?: false,
            // Anything outside a day is a corrupt preference, not a choice; fall back rather than
            // schedule a summary for hour 47.
            notifySummaryHour = p[Keys.notifySummaryHour]?.takeIf { it in 0..23 } ?: 7,
            notifyRain = p[Keys.notifyRain] ?: false,
            notifyWarnings = p[Keys.notifyWarnings] ?: false,
            placeIstat = p[Keys.placeIstat]?.takeIf { it.isNotBlank() } ?: SouthTyrol.DEFAULT_ISTAT,
            recentPlaces = p[Keys.recentPlaces].orEmpty().split(',').filter { it.isNotBlank() },
        )
    }

    suspend fun setLanguage(v: LanguageSetting) = context.settingsStore.edit { it[Keys.language] = v.name }
    suspend fun setWindUnit(v: WindUnit) = context.settingsStore.edit { it[Keys.windUnit] = v.name }
    suspend fun setAnimations(v: Boolean) = context.settingsStore.edit { it[Keys.animations] = v }
    suspend fun setCompareSources(v: Set<Source>) = context.settingsStore.edit { it[Keys.compareSources] = v.map { s -> s.name }.toSet() }
    suspend fun setCompareVariable(v: CompareVariable) = context.settingsStore.edit { it[Keys.compareVariable] = v.name }
    suspend fun setNotifySummary(v: Boolean) = context.settingsStore.edit { it[Keys.notifySummary] = v }
    suspend fun setNotifySummaryHour(v: Int) = context.settingsStore.edit { it[Keys.notifySummaryHour] = v.coerceIn(0, 23) }
    suspend fun setNotifyRain(v: Boolean) = context.settingsStore.edit { it[Keys.notifyRain] = v }
    suspend fun setNotifyWarnings(v: Boolean) = context.settingsStore.edit { it[Keys.notifyWarnings] = v }

    /**
     * Chooses a place and moves it to the head of the recent list, which is what the cache keeps.
     * Both in one edit, so a reader who switches place can never end up with a chosen place the
     * cache has already been told to evict.
     */
    suspend fun setPlace(istat: String) = context.settingsStore.edit { prefs ->
        prefs[Keys.placeIstat] = istat
        val current = prefs[Keys.recentPlaces].orEmpty().split(',').filter { it.isNotBlank() }
        prefs[Keys.recentPlaces] = (listOf(istat) + current.filterNot { it == istat })
            .take(RECENT_PLACES)
            .joinToString(",")
    }

    private companion object {
        /** How many places the cache keeps, and therefore how many are worth remembering. */
        const val RECENT_PLACES = 3
    }

    suspend fun toggleCompareSource(source: Source) = context.settingsStore.edit { prefs ->
        val current = prefs[Keys.compareSources]?.mapNotNull { runCatching { Source.valueOf(it) }.getOrNull() }?.toSet()
            ?: Source.entries.toSet()
        val updated = if (source in current) current - source else current + source
        prefs[Keys.compareSources] = updated.map { it.name }.toSet()
    }
}
