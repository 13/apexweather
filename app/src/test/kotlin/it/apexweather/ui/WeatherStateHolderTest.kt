package it.apexweather.ui

import androidx.test.core.app.ApplicationProvider
import it.apexweather.Fixtures
import it.apexweather.data.FakeGeoSphere
import it.apexweather.data.FakeMeteoAlarm
import it.apexweather.data.FakeOdh
import it.apexweather.data.FakeOpenMeteo
import it.apexweather.data.FakeSiag
import it.apexweather.data.MutableClock
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WeatherRepository
import it.apexweather.data.local.AppDatabase
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.model.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Locale

/**
 * Home and the sky both live for the whole session, so before this holder existed they each ran
 * their own minute tick and blended the same seven forecasts on every screen — and the sky then
 * kept only the palette. The holder is the single place that reads the cache and blends, and every
 * screen maps off its result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeatherStateHolderTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: WeatherRepository
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsRepository
    private lateinit var holder: WeatherStateHolder

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = AppDatabase.inMemory(context)
        repository = WeatherRepository(
            db.weatherDao(), FakeOpenMeteo(), FakeGeoSphere(), FakeSiag(), FakeOdh(), FakeMeteoAlarm(),
            Fixtures.json, MutableClock(Instant.parse("2026-09-08T14:00:00Z")),
        )
        scope = CoroutineScope(UnconfinedTestDispatcher())
        settings = SettingsRepository(context)
        holder = WeatherStateHolder(
            repository, settings, ConsensusBlender(DorfTirol.ZONE),
            MutableClock(Instant.parse("2026-09-08T14:00:00Z")), scope,
        )
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    /**
     * The bulletin is cached per language, so refresh the one the holder will actually ask for.
     * Read it from the settings rather than assuming the defaults: the preferences file is shared
     * across the JVM, so another test may have left a language behind.
     */
    private suspend fun language() =
        settings.settings.first().bulletinLanguage(Locale.getDefault().toLanguageTag())

    @Test
    fun `the shared inputs carry the blended forecast every screen derives from`() = runTest {
        repository.refresh(language())

        // Forecasts, bulletin and observation are separate cache rows and land in separate
        // emissions, so wait for the one that carries everything rather than the first that
        // carries anything.
        val weather = holder.weather.first { it.snapshot.forecasts.keys == Source.entries.toSet() && it.snapshot.bulletin != null }
        assertTrue(weather.consensus.hourly.isNotEmpty())
        assertTrue(weather.consensus.daily.isNotEmpty())
        // Compare and the bulletin tab read these, instead of each re-reading and re-decoding the cache.
        assertNotNull(weather.snapshot.bulletin)
    }

    /**
     * `weather.value` is the placeholder until the cache has been read, and the placeholder claims no
     * successful refresh — which is exactly what made the home screen refetch on every cold start.
     */
    @Test
    fun `awaitCached waits for the cache instead of returning the placeholder`() = runTest {
        repository.refresh(language())

        assertNull("the placeholder must not claim a refresh", WeatherState().snapshot.lastSuccessfulRefresh)
        val cached = holder.awaitCached()
        assertNotNull("awaitCached must not hand back the placeholder", cached.snapshot.lastSuccessfulRefresh)
        assertTrue(cached.snapshot.forecasts.isNotEmpty())
    }

    @Test
    fun `the home state is derived from those same inputs`() = runTest {
        repository.refresh(language())

        val home = holder.home.first { it.days.isNotEmpty() }
        val weather = holder.weather.value

        assertEquals(weather.snapshot.lastSuccessfulRefresh, home.updatedAt)
        assertEquals(weather.consensus.daily.first().date, home.days.first().date)
        // The day sheet needs the hours the 48-hour strip drops; they ride along on the same build.
        assertTrue(home.hoursByDate.size > home.upcomingHours.size / 24)
    }
}
