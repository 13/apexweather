package it.apexweather.data

import androidx.test.core.app.ApplicationProvider
import it.apexweather.domain.model.Source
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {
    private val repo = SettingsRepository(ApplicationProvider.getApplicationContext())

    // The DataStore file backing this repo lives on disk under the shared Robolectric temp dir
    // and is not reset between test methods, so tests must not depend on JUnit's (unspecified)
    // execution order. Reset to defaults before each test so every test starts clean.
    @Before
    fun resetToDefaults() = runTest {
        repo.setLanguage(LanguageSetting.SYSTEM)
        repo.setWindUnit(WindUnit.KMH)
        repo.setAnimations(true)
        repo.setCompareSources(Source.entries.toSet())
        repo.setCompareVariable(CompareVariable.TEMPERATURE)
    }

    @Test
    fun `defaults`() = runTest {
        val s = repo.settings.first()
        assertEquals(LanguageSetting.SYSTEM, s.language)
        assertEquals(WindUnit.KMH, s.windUnit)
        assertTrue(s.animations)
        assertEquals(Source.entries.toSet(), s.compareSources)
        assertEquals(CompareVariable.TEMPERATURE, s.compareVariable)
    }

    @Test
    fun `bulletin language resolves system locale and falls back to German`() {
        val s = AppSettings()
        assertEquals("it", s.bulletinLanguage("it-IT"))
        assertEquals("en", s.bulletinLanguage("en-US"))
        assertEquals("de", s.bulletinLanguage("fr-FR"))
        assertEquals("en", s.copy(language = LanguageSetting.EN).bulletinLanguage("de-DE"))
    }

    @Test
    fun `writes round-trip`() = runTest {
        repo.setLanguage(LanguageSetting.IT)
        repo.setCompareSources(setOf(Source.ICON_D2))
        repo.setCompareVariable(CompareVariable.WIND)
        repo.setAnimations(false)
        repo.setWindUnit(WindUnit.MS)
        val s = repo.settings.first()
        assertEquals(LanguageSetting.IT, s.language)
        assertEquals(setOf(Source.ICON_D2), s.compareSources)
        assertEquals(CompareVariable.WIND, s.compareVariable)
        assertEquals(false, s.animations)
        assertEquals(WindUnit.MS, s.windUnit)
    }

    @Test
    fun `toggleCompareSource adds and removes atomically`() = runTest {
        val allSources = Source.entries.toSet()
        assertEquals(allSources, repo.settings.first().compareSources)

        repo.toggleCompareSource(Source.ICON_D2)
        var s = repo.settings.first()
        assertFalse(Source.ICON_D2 in s.compareSources)
        assertEquals(allSources - Source.ICON_D2, s.compareSources)

        repo.toggleCompareSource(Source.ICON_D2)
        s = repo.settings.first()
        assertEquals(allSources, s.compareSources)

        val other = Source.entries.first { it != Source.ICON_D2 }
        coroutineScope {
            launch { repo.toggleCompareSource(Source.ICON_D2) }
            launch { repo.toggleCompareSource(other) }
        }
        s = repo.settings.first()
        assertFalse(Source.ICON_D2 in s.compareSources)
        assertFalse(other in s.compareSources)
        assertEquals(allSources - Source.ICON_D2 - other, s.compareSources)
    }
}
