package it.apexweather.data

import androidx.test.core.app.ApplicationProvider
import it.apexweather.domain.SouthTyrol
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

    /**
     * The DataStore behind this repo is shared between test methods, so nothing below may assume an
     * empty recent list — only that the list behaves correctly relative to what it was.
     */
    @Test
    fun `the place defaults to Dorf Tirol`() = runTest {
        assertEquals(SouthTyrol.DEFAULT_ISTAT, AppSettings().placeIstat)
        assertEquals(emptyList<String>(), AppSettings().recentPlaces)
    }

    @Test
    fun `choosing a place records it and puts it at the head of the recent list`() = runTest {
        repo.setPlace("021115")
        val s = repo.settings.first()
        assertEquals("021115", s.placeIstat)
        assertEquals("021115", s.recentPlaces.first())
    }

    /** Most recent first, each place once: going back to one moves it to the head, not a duplicate. */
    @Test
    fun `the recent list is ordered by last use and holds each place once`() = runTest {
        repo.setPlace("021115")
        repo.setPlace("021008")
        repo.setPlace("021115")
        val r = repo.settings.first().recentPlaces
        assertEquals(listOf("021115", "021008"), r.take(2))
        assertEquals(r.size, r.toSet().size)
    }

    /** The cache keeps three places, so remembering a fourth would be remembering nothing useful. */
    @Test
    fun `the recent list is capped at what the cache keeps`() = runTest {
        listOf("021115", "021008", "021101", "021051").forEach { repo.setPlace(it) }
        val s = repo.settings.first()
        assertEquals(listOf("021051", "021101", "021008"), s.recentPlaces)
        assertEquals("021051", s.placeIstat)
    }

    /** A blank stored code names no municipality; falling back beats showing no place at all. */
    @Test
    fun `a blank stored place falls back to the default`() = runTest {
        repo.setPlace("")
        assertEquals(SouthTyrol.DEFAULT_ISTAT, repo.settings.first().placeIstat)
    }
}
