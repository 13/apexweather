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
import it.apexweather.data.MutableClock
import it.apexweather.data.NowcastRepository
import it.apexweather.data.PlaceCatalogue
import it.apexweather.data.RadarRepository
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WarningDismissals
import it.apexweather.data.WeatherRepository
import it.apexweather.data.local.AppDatabase
import it.apexweather.data.remote.NowcastApi
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

    /** Thirteen frames ten minutes apart, as RainViewer serves them. */
    private class FakeRainViewer(private val t0: Instant) : RainViewerApi {
        var frames: Int = 13
        override suspend fun weatherMaps() = RainViewerMaps(
            host = "https://example.invalid",
            radar = RainViewerRadar(
                past = (0 until frames).map {
                    RainViewerFrame(time = t0.minusSeconds((frames - 1 - it) * 600L).epochSecond, path = "/f$it")
                },
            ),
        )
    }

    /** No forecast: this is a test about the loop, and one source of frames is enough to drive it. */
    private class NoNowcast : NowcastApi {
        override suspend fun precipitation(bbox: String, parameters: String, outputFormat: String) =
            NowcastResponse()
        override suspend fun outlook(bbox: String, end: String, parameters: String, outputFormat: String) =
            NowcastResponse()
    }

    /**
     * `viewModelScope` runs on `Dispatchers.Main`, so the test has to *be* Main for its delays to
     * be under the test clock's control. Without this the loop never turns at all and every
     * assertion about it passes for the wrong reason — which is how the first draft of this file
     * "proved" that pause worked.
     */
    private val mainDispatcher = StandardTestDispatcher()

    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var holder: WeatherStateHolder
    private val clock = MutableClock(t0)
    private val rainViewer = FakeRainViewer(t0)

    @Before fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = AppDatabase.inMemory(context)
        val repository = WeatherRepository(
            db.weatherDao(), FakeOpenMeteo(), FakeGeoSphere(), FakeSiag(), FakeOdh(),
            FakeMeteoAlarm(), FakeEnsemble(), Fixtures.json, clock,
        )
        scope = CoroutineScope(UnconfinedTestDispatcher())
        val settings = SettingsRepository(context)
        // The DataStore is shared between test classes under Robolectric; state the place rather
        // than inheriting whichever one another test left behind.
        runBlocking { settings.setPlace(DORF_TIROL.istat) }
        holder = WeatherStateHolder(
            repository, settings, PlaceCatalogue(context), WarningDismissals(context),
            ConsensusBlender(SouthTyrol.ZONE), clock, scope,
        )
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    /**
     * Built through a [ViewModelStore] so the test can *clear* it, which is the only public way to
     * cancel a `viewModelScope`.
     *
     * Leaving it uncancelled is not harmless. The ViewModel goes on collecting the holder's state
     * after the test body ends, and `tearDown` then closes the database under it — the exception
     * that throws surfaces in whichever test the dispatcher happens to run next, as
     * `UncaughtExceptionsBeforeTest`. It passed here every time and failed on CI, which is what a
     * leak between tests looks like.
     */
    private fun store() = ViewModelStore()

    private fun ViewModelStore.mapViewModel(): MapViewModel = ViewModelProvider(
        this,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MapViewModel(RadarRepository(rainViewer, clock), NowcastRepository(NoNowcast(), clock), holder) as T
        },
    )[MapViewModel::class.java]

    /**
     * A test with the loop stopped and the ViewModel cleared at the end.
     *
     * `runTest` will not finish while work is still scheduled on its clock, and the animation is a
     * `while (true)` of delays — a test that leaves it running does not fail, it hangs.
     */
    private fun mapTest(
        frames: Int = 13,
        body: suspend kotlinx.coroutines.test.TestScope.(MapViewModel) -> Unit,
    ) =
        runTest(mainDispatcher.scheduler) {
            rainViewer.frames = frames
            val store = store()
            val vm = store.mapViewModel()
            runCurrent()
            try {
                body(vm)
            } finally {
                vm.pause()
                store.clear()
                runCurrent()
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
}
