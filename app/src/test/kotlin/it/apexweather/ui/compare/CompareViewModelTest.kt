package it.apexweather.ui.compare

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import it.apexweather.Fixtures
import it.apexweather.data.FakeEnsemble
import it.apexweather.data.FakeGeoSphere
import it.apexweather.data.FakeMeteoAlarm
import it.apexweather.data.FakeOdh
import it.apexweather.data.FakeOpenMeteo
import it.apexweather.data.FakeSiag
import it.apexweather.data.MutableClock
import it.apexweather.data.PlaceCatalogue
import it.apexweather.data.SettingsRepository
import it.apexweather.data.SourceMetaRepository
import it.apexweather.data.WarningDismissals
import it.apexweather.data.WeatherRepository
import it.apexweather.data.local.AppDatabase
import it.apexweather.data.local.HistoryDatabase
import it.apexweather.data.remote.SourceMetaApi
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.DORF_TIROL
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.model.Source
import it.apexweather.ui.WeatherStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant

/**
 * `openSource`/`closeSource` and the meta fetch they drive — untested until now, unlike the state
 * builder they feed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompareViewModelTest {

    private val t0: Instant = Instant.parse("2026-09-15T08:00:00Z")

    /** Records every URL it was asked for; can be told to fail or to hang, like the mapper test's fake. */
    private class FakeMetaApi : SourceMetaApi {
        val urls = mutableListOf<String>()
        var fail = false
        var delayMs = 0L
        override suspend fun metadata(url: String): JsonObject {
            urls += url
            if (delayMs > 0) delay(delayMs)
            if (fail) throw IOException("offline")
            val name = if ("geosphere" in url) "geosphere_nwp_metadata.json" else "meta_dwd_icon_d2.json"
            return Fixtures.json.parseToJsonElement(Fixtures.read(name)).jsonObject
        }
    }

    /** `viewModelScope` runs on Main, so its delays only move with the test clock if this is Main. */
    private val mainDispatcher = StandardTestDispatcher()

    private val clock = MutableClock(t0)
    private lateinit var settings: SettingsRepository

    @Before fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        runBlocking { settings.setPlace(DORF_TIROL.istat) }
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * One test, with everything it builds torn down inside it — see MapViewModelTest for why: the
     * ViewModel is built through a ViewModelStore because clearing one is the only public way to
     * cancel a `viewModelScope`, and nothing here may outlive the body.
     */
    private fun compareTest(
        savedState: Map<String, Any?> = emptyMap(),
        body: suspend TestScope.(CompareViewModel, FakeMetaApi) -> Unit,
    ) = runTest(mainDispatcher.scheduler) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = AppDatabase.inMemory(context)
        val history = HistoryDatabase.inMemory(context)
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val holder = WeatherStateHolder(
            WeatherRepository(
                db.weatherDao(), history.stationHistoryDao(), FakeOpenMeteo(), FakeGeoSphere(), FakeSiag(), FakeOdh(),
                FakeMeteoAlarm(), FakeEnsemble(), Fixtures.json, clock,
            ),
            settings, PlaceCatalogue(context), WarningDismissals(context),
            ConsensusBlender(SouthTyrol.ZONE), clock, scope,
        )
        val metaApi = FakeMetaApi()
        val metaRepository = SourceMetaRepository(metaApi, clock)
        val store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CompareViewModel(
                    holder, settings, SavedStateHandle(savedState), metaRepository,
                ) as T
            },
        )[CompareViewModel::class.java]
        runCurrent()
        try {
            body(vm, metaApi)
        } finally {
            store.clear()
            runCurrent()
            scope.cancel()
            runBlocking { scope.coroutineContext.job.join() }
            db.close()
            history.close()
        }
    }

    @Test
    fun `opening a source fetches its meta at the model's own dataset URL`() = compareTest { vm, api ->
        vm.openSource(Source.ICON_D2)
        assertEquals(SourceMetaUi.Loading(Source.ICON_D2), vm.meta.value)
        runCurrent()
        val loaded = vm.meta.value
        assertTrue("expected Loaded, got $loaded", loaded is SourceMetaUi.Loaded)
        assertEquals(Source.ICON_D2, (loaded as SourceMetaUi.Loaded).source)
        assertEquals(listOf("https://api.open-meteo.com/data/dwd_icon_d2/static/meta.json"), api.urls)
    }

    @Test
    fun `closing a source while its fetch is in flight cancels it`() = compareTest { vm, api ->
        api.delayMs = 60_000
        vm.openSource(Source.ICON_D2)
        runCurrent()
        assertEquals(SourceMetaUi.Loading(Source.ICON_D2), vm.meta.value)
        vm.closeSource()
        runCurrent()
        assertEquals(SourceMetaUi.NotApplicable, vm.meta.value)
        // Long enough for the cancelled fetch to have landed if it were not actually cancelled.
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals("a cancelled fetch must not still arrive", SourceMetaUi.NotApplicable, vm.meta.value)
    }

    /** A sheet restored after process death asks for its run line again — see the ViewModel's init. */
    @Test
    fun `a source restored in SavedStateHandle fetches its meta on construction`() =
        compareTest(savedState = mapOf("compare_source" to Source.GEOSPHERE_AROME.name)) { vm, api ->
            runCurrent()
            val meta = vm.meta.value
            assertTrue("expected Loaded, got $meta", meta is SourceMetaUi.Loaded)
            assertEquals(Source.GEOSPHERE_AROME, (meta as SourceMetaUi.Loaded).source)
            assertEquals(listOf(SourceMetaApi.GEOSPHERE_AROME_URL), api.urls)
        }

    /** KMOS's run time is already its status time; there is nothing to ask. */
    @Test
    fun `opening KMOS asks for nothing`() = compareTest { vm, api ->
        vm.openSource(Source.SIAG_KMOS)
        runCurrent()
        assertEquals(SourceMetaUi.NotApplicable, vm.meta.value)
        assertTrue(api.urls.isEmpty())
    }

    @Test
    fun `a failing fetch gives Unavailable for the source that was asked`() = compareTest { vm, api ->
        api.fail = true
        vm.openSource(Source.ICON_D2)
        runCurrent()
        assertEquals(SourceMetaUi.Unavailable(Source.ICON_D2), vm.meta.value)
    }
}
