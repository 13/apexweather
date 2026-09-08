package it.apexweather.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.emptyPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import it.apexweather.domain.model.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class LanguageSetting(val tag: String?) { SYSTEM(null), DE("de"), IT("it"), EN("en") }
enum class WindUnit { KMH, MS }
enum class CompareVariable { TEMPERATURE, PRECIPITATION, WIND }

data class AppSettings(
    val language: LanguageSetting = LanguageSetting.SYSTEM,
    val windUnit: WindUnit = WindUnit.KMH,
    val animations: Boolean = true,
    val compareSources: Set<Source> = Source.entries.toSet(),
    val compareVariable: CompareVariable = CompareVariable.TEMPERATURE,
) {
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
        )
    }

    suspend fun setLanguage(v: LanguageSetting) = context.settingsStore.edit { it[Keys.language] = v.name }
    suspend fun setWindUnit(v: WindUnit) = context.settingsStore.edit { it[Keys.windUnit] = v.name }
    suspend fun setAnimations(v: Boolean) = context.settingsStore.edit { it[Keys.animations] = v }
    suspend fun setCompareSources(v: Set<Source>) = context.settingsStore.edit { it[Keys.compareSources] = v.map { s -> s.name }.toSet() }
    suspend fun setCompareVariable(v: CompareVariable) = context.settingsStore.edit { it[Keys.compareVariable] = v.name }
}
