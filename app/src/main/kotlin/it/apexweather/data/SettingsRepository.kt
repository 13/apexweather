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
    /**
     * Places the reader has pinned, in the order they pinned them.
     *
     * The app has always had exactly one place and three cached ones, which is right for somebody
     * who lives in one valley and wrong for everybody here in the other half of their life: home,
     * and the hut they are walking to on Saturday. A pin is the smallest thing that fixes it — the
     * picker puts them at the top, and, more usefully, [keptPlaces] keeps them cached, so a pinned
     * place opens instantly and works with no signal, which is the state a mountain is usually in.
     *
     * Deliberately not a reordering of [recentPlaces]: recency is what the app observed and a pin is
     * what the reader said, and a list that mixed the two would quietly lose the second.
     */
    val favouritePlaces: List<String> = emptyList(),
) {
    /**
     * Every place the cache is to keep: the one on screen, the pins, then the most recent.
     *
     * One list, because two callers evict — the resume hook and the hourly worker — and they must
     * never disagree about what is worth keeping. Capped, because each place is a full set of model
     * runs and an unbounded pin list is an unbounded database.
     */
    val keptPlaces: List<String>
        get() = (listOf(placeIstat) + favouritePlaces + recentPlaces)
            .distinct()
            .take(SettingsRepository.MAX_KEPT_PLACES)

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
        // The sources the reader has switched **off**, not the ones left on.
        //
        // Storing the visible set froze the list at whatever existed when they last touched it: add
        // an eleventh model and everybody who had ever toggled anything had it permanently hidden,
        // with nothing on screen to hint that it was there. Storing the exclusions means the
        // default is always "everything, including whatever is new". The key is a new one, so a set
        // written by an older build is forgotten rather than read backwards — which costs a reader
        // who had hidden something one visit to this screen, and reads as everything being on.
        val hiddenCompareSources = stringSetPreferencesKey("compare_hidden_sources")
        val compareVariable = stringPreferencesKey("compare_variable")
        val notifySummary = booleanPreferencesKey("notify_summary")
        val notifySummaryHour = intPreferencesKey("notify_summary_hour")
        val notifyRain = booleanPreferencesKey("notify_rain")
        val notifyWarnings = booleanPreferencesKey("notify_warnings")
        val placeIstat = stringPreferencesKey("place_istat")
        // A comma-joined list rather than a string set: the order is the whole point of it, and
        // DataStore's set preference does not keep one.
        val recentPlaces = stringPreferencesKey("recent_places")
        // Comma-joined, like the recents and for the same reason: the reader's own order is the
        // point of it, and DataStore's set preference does not keep one.
        val favouritePlaces = stringPreferencesKey("favourite_places")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }.map { p ->
        AppSettings(
            language = p[Keys.language]?.let { runCatching { LanguageSetting.valueOf(it) }.getOrNull() } ?: LanguageSetting.SYSTEM,
            windUnit = p[Keys.windUnit]?.let { runCatching { WindUnit.valueOf(it) }.getOrNull() } ?: WindUnit.KMH,
            animations = p[Keys.animations] ?: true,
            compareSources = visibleSources(p[Keys.hiddenCompareSources]),
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
            favouritePlaces = p[Keys.favouritePlaces].orEmpty().split(',').filter { it.isNotBlank() },
        )
    }

    suspend fun setLanguage(v: LanguageSetting) = context.settingsStore.edit { it[Keys.language] = v.name }
    suspend fun setWindUnit(v: WindUnit) = context.settingsStore.edit { it[Keys.windUnit] = v.name }
    suspend fun setAnimations(v: Boolean) = context.settingsStore.edit { it[Keys.animations] = v }
    suspend fun setCompareSources(v: Set<Source>) = context.settingsStore.edit {
        it[Keys.hiddenCompareSources] = (Source.entries.toSet() - v).map { s -> s.name }.toSet()
    }
    suspend fun setCompareVariable(v: CompareVariable) = context.settingsStore.edit { it[Keys.compareVariable] = v.name }
    suspend fun setNotifySummary(v: Boolean) = context.settingsStore.edit { it[Keys.notifySummary] = v }
    suspend fun setNotifySummaryHour(v: Int) = context.settingsStore.edit { it[Keys.notifySummaryHour] = v.coerceIn(0, 23) }
    suspend fun setNotifyRain(v: Boolean) = context.settingsStore.edit { it[Keys.notifyRain] = v }
    suspend fun setNotifyWarnings(v: Boolean) = context.settingsStore.edit { it[Keys.notifyWarnings] = v }

    /**
     * Pins or unpins a place, keeping the reader's own order and refusing to grow without bound.
     *
     * A pin past [MAX_FAVOURITE_PLACES] is dropped rather than silently evicting something the
     * reader also asked for: the list is short enough that "the oldest pin quietly vanished" would
     * be a worse surprise than "that one did not stick".
     */
    suspend fun setFavourite(istat: String, favourite: Boolean) = context.settingsStore.edit { prefs ->
        val current = prefs[Keys.favouritePlaces].orEmpty().split(',').filter { it.isNotBlank() }
        val next = if (!favourite) current.filterNot { it == istat }
        else if (istat in current || current.size >= MAX_FAVOURITE_PLACES) current
        else current + istat
        prefs[Keys.favouritePlaces] = next.joinToString(",")
    }

    /**
     * Chooses a place and moves it to the head of the recent list, which is what the cache keeps.
     * Both in one edit, so a reader who switches place can never end up with a chosen place the
     * cache has already been told to evict.
     */
    suspend fun setPlace(istat: String) = context.settingsStore.edit { prefs ->
        val previous = prefs[Keys.placeIstat]?.takeIf { it.isNotBlank() } ?: SouthTyrol.DEFAULT_ISTAT
        prefs[Keys.placeIstat] = istat
        // The place being left has to enter the list here, or it is not in it when eviction reads
        // it — and the very first switch would throw away the cache of the place the app opened on.
        val current = prefs[Keys.recentPlaces].orEmpty().split(',').filter { it.isNotBlank() }
            .ifEmpty { listOf(previous) }
        prefs[Keys.recentPlaces] = (listOf(istat) + current.filterNot { it == istat })
            .take(RECENT_PLACES)
            .joinToString(",")
    }

    internal companion object {
        /** How many places the cache keeps, and therefore how many are worth remembering. */
        const val RECENT_PLACES = 3

        /**
         * How many places may be pinned.
         *
         * Four: home, work, and the two valleys anyone actually goes to. It is a cap on the cache as
         * much as on the list — every kept place is eleven model runs, a bulletin and an
         * observation, refreshed on the hour.
         */
        const val MAX_FAVOURITE_PLACES = 4

        /**
         * And the ceiling on everything the cache keeps at once: the current place, the pins and the
         * recents together. One more than the pins, so pinning four still leaves room for the place
         * being looked at right now.
         */
        const val MAX_KEPT_PLACES = MAX_FAVOURITE_PLACES + RECENT_PLACES

        /**
         * The sources switched off, read from what is stored.
         *
         * A stored name that no longer matches a [Source] is dropped rather than kept, so a model
         * removed from the app stops hiding anything — and, the direction that matters, a model
         * *added* to the app is in nobody's stored set and is therefore shown to everybody.
         */
        fun hiddenSources(stored: Set<String>?): Set<Source> =
            stored?.mapNotNull { runCatching { Source.valueOf(it) }.getOrNull() }?.toSet().orEmpty()

        /** And what the screen shows, which is everything else. */
        fun visibleSources(stored: Set<String>?): Set<Source> = Source.entries.toSet() - hiddenSources(stored)
    }

    suspend fun toggleCompareSource(source: Source) = context.settingsStore.edit { prefs ->
        val hidden = hiddenSources(prefs)
        val updated = if (source in hidden) hidden - source else hidden + source
        prefs[Keys.hiddenCompareSources] = updated.map { it.name }.toSet()
    }

    /**
     * A name that no longer matches a [Source] is dropped rather than kept: a model removed from
     * the app must not go on hiding anything, and cannot hide itself.
     */
    private fun hiddenSources(p: Preferences): Set<Source> = hiddenSources(p[Keys.hiddenCompareSources])
}
