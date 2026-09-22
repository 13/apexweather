package it.apexweather.ui.map

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
import it.apexweather.data.FakeWeatherUnderground
import it.apexweather.data.MutableClock
import it.apexweather.data.NowcastRepository
import it.apexweather.data.PlaceCatalogue
import it.apexweather.data.RadarRepository
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WarningDismissals
import it.apexweather.data.WeatherRepository
import it.apexweather.data.local.AppDatabase
import it.apexweather.data.local.HistoryDatabase
import it.apexweather.data.NowcastSource
import it.apexweather.data.remote.PrecipNowcast
import it.apexweather.data.remote.NowcastResponse
import it.apexweather.data.remote.RainViewerApi
import it.apexweather.data.remote.RainViewerFrame
import it.apexweather.data.remote.RainViewerMaps
import it.apexweather.data.remote.RainViewerRadar
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.DORF_TIROL
import it.apexweather.domain.SouthTyrol
import it.apexweather.ui.WeatherStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * The map's animation, and the ways it lost the reader's place.
 *
 * The reproducible one is here: a refresh reset the selection outright instead of carrying it
 * across, so every ten minutes — and on every return to the tab — the reader was dropped back on
 * the newest frame. `a refresh keeps the reader on the minute they were looking at` fails against
 * the old code and is why this file exists.
 *
 * The other two are not unit-testable and should not be claimed here. That the loop decided each
 * step before sleeping through it is a race whose interleaving virtual time will not schedule. And
 * that it kept running when the tab was left is wiring, not logic: the bottom bar saves the map's
 * back stack rather than popping it, so the ViewModel outlives the trip, and the fix is one line in
 * `MapScreen`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapViewModelTest {

    private val t0: Instant = Instant.parse("2026-09-08T14:00:00Z")

    private companion object {
        const val MERAN_ISTAT = "021051"
    }

    /** Thirteen frames ten minutes apart, as RainViewer serves them. */
    private class FakeRainViewer(private val t0: Instant) : RainViewerApi {
        var frames: Int = 13

        /** Set to model a tile that has not answered yet, without ever actually answering it. */
        var tileDelayMs: Long = 0

        /** Set to hold a refresh's first phase back, so the state before it lands can be read. */
        var mapsDelayMs: Long = 0
        override suspend fun weatherMaps(): RainViewerMaps {
            if (mapsDelayMs > 0) delay(mapsDelayMs)
            return RainViewerMaps(
            host = "https://example.invalid",
            radar = RainViewerRadar(
                past = (0 until frames).map {
                    RainViewerFrame(time = t0.minusSeconds((frames - 1 - it) * 600L).epochSecond, path = "/f$it")
                },
            ),
        )
        }
        override suspend fun tile(url: String): okhttp3.ResponseBody {
            if (tileDelayMs > 0) delay(tileDelayMs)
            throw java.io.IOException("no tiles in this test")
        }
    }

    /** No forecast: this is a test about the loop, and one source of frames is enough to drive it. */
    private class NoNowcast : NowcastSource {
        /** Set to model GeoSphere's slow INCA answer: 383 kB uncompressed, 4,4 s on the phone. */
        var delayMs: Long = 0
        var calls = 0
        override suspend fun nowcast(): PrecipNowcast {
            calls++
            if (delayMs > 0) delay(delayMs)
            return PrecipNowcast.EMPTY
        }
        override suspend fun outlook(end: String): PrecipNowcast {
            if (delayMs > 0) delay(delayMs)
            return PrecipNowcast.EMPTY
        }
    }

    /**
     * `viewModelScope` runs on `Dispatchers.Main`, so the test has to *be* Main for its delays to
     * be under the test clock's control. Without this the loop never turns at all and every
     * assertion about it passes for the wrong reason — which is how the first draft of this file
     * "proved" that pause worked.
     */
    private val mainDispatcher = StandardTestDispatcher()

    private val clock = MutableClock(t0)
    private val rainViewer = FakeRainViewer(t0)
    private val nowcastApi = NoNowcast()

    private lateinit var settings: SettingsRepository

    @Before fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        // Built once, and the place stated once, outside any `runTest`. The DataStore behind this
        // is a delegate on the Context and is shared with every other test class in the process;
        // driving it with `runBlocking` from inside a test body is a good way to hang one.
        settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        runBlocking { settings.setPlace(DORF_TIROL.istat) }
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * One test, with everything it builds torn down inside it.
     *
     * The database, the holder's scope and the ViewModel all used to be set up and torn down around
     * the test by JUnit, which meant each of them outlived the `runTest` that drove it: the holder
     * went on collecting while `tearDown` closed the database under it, and whatever that threw
     * surfaced in the *next* test as `UncaughtExceptionsBeforeTest`. It passed locally and failed on
     * CI, twice. Nothing here escapes the body now.
     *
     * The ViewModel is built through a [ViewModelStore] because clearing one is the only public way
     * to cancel a `viewModelScope`, and `runTest` will not finish while the animation — a
     * `while (true)` of delays — is still scheduled on its clock. A test that leaves it running does
     * not fail, it hangs.
     */
    private fun mapTest(
        frames: Int = 13,
        body: suspend kotlinx.coroutines.test.TestScope.(MapViewModel) -> Unit,
    ) = runTest(mainDispatcher.scheduler) {
        rainViewer.frames = frames
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = AppDatabase.inMemory(context)
        val history = HistoryDatabase.inMemory(context)
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val holder = WeatherStateHolder(
            WeatherRepository(
                db.weatherDao(), history.stationHistoryDao(), FakeOpenMeteo(), FakeGeoSphere(), FakeSiag(), FakeWeatherUnderground(), "", FakeOdh(),
                FakeMeteoAlarm(), FakeEnsemble(), Fixtures.json, clock,
            ),
            settings, PlaceCatalogue(context), WarningDismissals(context),
            ConsensusBlender(SouthTyrol.ZONE), clock, scope, radar = { _, _ -> null },
        )
        val store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = MapViewModel(
                    RadarRepository(rainViewer, { null }, clock), NowcastRepository(nowcastApi, clock), holder, mainDispatcher,
                ) as T
            },
        )[MapViewModel::class.java]
        runCurrent()
        try {
            body(vm)
        } finally {
            vm.pause()
            store.clear()
            runCurrent()
            // Cancel is a request, not an ending: the holder's collectors are still alive for an
            // instant after it, and closing the database under one of them throws into a scope no
            // test owns — which surfaces in whichever test runs next as
            // UncaughtExceptionsBeforeTest. Wait for them.
            scope.cancel()
            runBlocking { scope.coroutineContext.job.join() }
            db.close()
            history.close()
        history.close()
        }
    }

    @Test
    fun `playing walks the timeline forward a frame at a time`() = mapTest { vm ->
        val start = vm.state.value.selected
        vm.play()
        // It opens on the newest frame, which is the end of the line, and the loop holds a beat
        // there before wrapping — so this has to outlast that pause, not just one frame.
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(vm.state.value.playing)
        assertTrue("it did not move from $start", vm.state.value.selected != start)
    }

    /**
     * The tab-switch case. Nothing may advance after `pause`, and the loop must not be sitting on
     * one last delay waiting to write a frame down after it.
     */
    @Test
    fun `pausing stops it dead, and it stays stopped`() = mapTest { vm ->
        vm.play()
        advanceTimeBy(1_000)
        runCurrent()
        vm.pause()
        val parked = vm.state.value.selected
        assertFalse(vm.state.value.playing)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("the loop went on turning after pause", parked, vm.state.value.selected)
        assertFalse(vm.state.value.playing)
    }

    /**
     * An invariant rather than a regression: the list is rebuilt every ten minutes, and whatever
     * the loop and the refresh do to each other, the selection has to stay inside it. A selection
     * past the end leaves `frame` null and the map blank.
     */
    @Test
    fun `the list shrinking mid-play never leaves the selection past its end`() = mapTest { vm ->
        vm.play()
        advanceTimeBy(2_000)
        runCurrent()
        rainViewer.frames = 3
        clock.now = clock.now.plus(java.time.Duration.ofMinutes(20))
        vm.refresh()
        advanceTimeBy(5_000)
        runCurrent()
        val s = vm.state.value
        assertEquals(3, s.frames.size)
        assertTrue("selected ${s.selected} is outside ${s.frames.indices}", s.selected in s.frames.indices)
        assertTrue("the map went blank", s.frame != null)
    }

    /** Scrubbing stops the loop, and the frame the reader chose is the frame that stays up. */
    @Test
    fun `a scrub during a frame's delay is not overwritten by the loop`() = mapTest { vm ->
        vm.play()
        // Part-way through a frame, so the loop is asleep with a decision already made.
        advanceTimeBy(200)
        vm.select(2)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, vm.state.value.selected)
        assertFalse(vm.state.value.playing)
    }

    /**
     * A refresh lands every ten minutes and on every return to the tab. It must not move the reader
     * off the minute they were looking at, and must not leave the selection pointing past the end
     * of a list that has since got shorter.
     */
    @Test
    fun `a refresh keeps the reader on the minute they were looking at`() = mapTest { vm ->
        // Ten frames back from the newest, which the shorter list below still reaches.
        vm.select(10)
        val wasLookingAt = vm.state.value.frame?.time
        assertEquals(10, vm.state.value.selected)
        // The next fetch brings a shorter list — the oldest frames have aged out, so the same
        // minute now sits at a different index.
        rainViewer.frames = 6
        clock.now = clock.now.plus(java.time.Duration.ofMinutes(20))
        vm.refresh()
        runCurrent()
        assertEquals(6, vm.state.value.frames.size)
        assertTrue(vm.state.value.selected in vm.state.value.frames.indices)
        assertEquals(wasLookingAt, vm.state.value.frame?.time)
        assertTrue("the index must have moved, or this proves nothing", vm.state.value.selected != 10)
    }

    /** One frame is not a loop, and a slider with one stop cannot be dragged either. */
    @Test
    fun `a single frame never starts the animation`() = mapTest(frames = 1) { vm ->
        vm.play()
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(vm.state.value.playing)
        assertEquals(0, vm.state.value.selected)
    }

    /**
     * The radar check against the place only marks cells that are already on screen as uncertain; it
     * must not hold up drawing the radar and forecast the app already has in hand. Before this,
     * `refresh` awaited every tile at the place before writing anything down at all, so one slow tile
     * held the whole map back with nothing on screen.
     */
    @Test
    fun `the map shows its frames before the radar check at the place resolves`() = mapTest { vm ->
        // A place is needed for this to prove anything: with none, the old code skipped the check
        // entirely and never exercised the bug.
        vm.state.first { it.place != null }
        rainViewer.frames = 6
        rainViewer.tileDelayMs = 60_000
        clock.now = clock.now.plus(java.time.Duration.ofMinutes(20))
        vm.refresh()
        runCurrent()
        assertEquals("the frames must be drawn while the radar check is still pending", 6, vm.state.value.frames.size)
        assertFalse(vm.state.value.loading)
    }

    /**
     * The radar list answers in about 350 ms on the phone and INCA in about 4,4 s. The map used to put
     * nothing on screen until both forecasts were in, so a first open showed a bare basemap for six
     * seconds (measured 2026-09-14).
     */
    @Test
    fun `the radar is on screen while the forecast is still being fetched`() = mapTest { vm ->
        vm.state.first { it.place != null }
        runCurrent()
        nowcastApi.delayMs = 60_000
        rainViewer.frames = 6
        clock.now = clock.now.plus(java.time.Duration.ofMinutes(20))
        vm.refresh()
        runCurrent()
        assertEquals("the radar waited for the forecast", 6, vm.state.value.frames.size)
        assertFalse(vm.state.value.loading)
    }

    /**
     * A first open asks three times in a few hundred milliseconds — init, the place collector and the
     * tab's resume — and each used to cancel the one before, throwing away an INCA request already in
     * flight and starting it again.
     */
    @Test
    fun `refreshes asked for while one is still fetching share its requests`() = mapTest { vm ->
        vm.state.first { it.place != null }
        runCurrent()
        nowcastApi.delayMs = 5_000
        clock.now = clock.now.plus(java.time.Duration.ofMinutes(20))
        val before = nowcastApi.calls
        vm.refresh()
        runCurrent()
        advanceTimeBy(1_000)
        vm.refresh()
        vm.refresh()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals("a later refresh restarted the forecast request", before + 1, nowcastApi.calls)
    }

    /**
     * Refreshes come from init, the place collector and every resume, and used to run side by side.
     * A slow one's second phase — the radar check at the place — could then land after a newer
     * refresh's first phase and put its own, older timeline back on screen.
     */
    @Test
    fun `an older refresh whose readings resolve last never overwrites a newer one`() = mapTest { vm ->
        vm.state.first { it.place != null }
        runCurrent()
        // A: thirteen frames, and a radar check that takes a long time.
        rainViewer.tileDelayMs = 60_000
        clock.now = clock.now.plus(java.time.Duration.ofMinutes(20))
        vm.refresh()
        runCurrent()
        assertEquals(13, vm.state.value.frames.size)
        // B: a newer list of six, started while A's tiles are still in flight.
        rainViewer.frames = 6
        clock.now = clock.now.plus(java.time.Duration.ofMinutes(20))
        vm.refresh()
        runCurrent()
        assertEquals(6, vm.state.value.frames.size)
        // Long enough for A's readings to resolve, but not for B's to have queued up behind them.
        advanceTimeBy(250_000)
        runCurrent()
        assertEquals("the older refresh landed over the newer one", 6, vm.state.value.frames.size)
        advanceTimeBy(1_000_000)
        runCurrent()
        assertEquals(6, vm.state.value.frames.size)
    }

    /**
     * The bars are the place's own rain. After a switch they used to go on describing the place the
     * reader had just left until the new place's refresh landed — a network round trip later.
     */
    @Test
    fun `a place switch clears the ribbon until the new place's refresh lands`() = mapTest { vm ->
        val before = vm.state.first { it.place?.istat == DORF_TIROL.istat && it.nowBars.isNotEmpty() }
        assertTrue(before.nowBars.isNotEmpty())
        // The next refresh refetches the frame list, and that fetch does not answer yet.
        rainViewer.mapsDelayMs = 60_000
        clock.now = clock.now.plus(java.time.Duration.ofMinutes(20))
        settings.setPlace(MERAN_ISTAT)
        val switched = vm.state.first { it.place?.istat == MERAN_ISTAT }
        assertTrue("Jetzt's bars still describe the place just left", switched.nowBars.isEmpty())
        assertTrue("Heute's bars still describe the place just left", switched.todayBars.isEmpty())
    }

    @Test
    fun `switching zoom stops the loop`() = mapTest { vm ->
        vm.play()
        advanceTimeBy(1_000)
        vm.setZoom(MapZoom.TODAY)
        runCurrent()
        assertFalse(vm.state.value.playing)
        assertEquals(MapZoom.TODAY, vm.state.value.zoom)
    }
}
