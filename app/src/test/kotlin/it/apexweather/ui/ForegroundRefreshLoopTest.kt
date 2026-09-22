package it.apexweather.ui

import it.apexweather.domain.DORF_TIROL
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The loop that keeps the app fresh while somebody is looking at it.
 *
 * `StaleRefresher` owns the whole refresh path — fetch, eviction, dismissal pruning, widget update —
 * and had no test of any kind, which is how the bug it exists to fix got in: the check used to live
 * in `HomeViewModel.init` and therefore ran once per ViewModel. Adding a `while (true)` to an
 * untested class is the worst version of that, so the decision came out as `RefreshDue` and the
 * timing came out into [ForegroundRefreshLoop] — which takes functions rather than a
 * `WeatherStateHolder` and a `WeatherRepository`, because otherwise a test of one `delay` has to
 * stand up a DataStore, a Room database, an asset read and three `WhileSubscribed` flows to watch it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundRefreshLoopTest {

    private val t0: Instant = Instant.parse("2026-09-22T09:00:00Z")

    private lateinit var loop: ForegroundRefreshLoop

    private var fulls = 0
    private var stations = 0
    private var fullFails = false
    private var stationFails = false
    private var metered = false
    private var online = true

    /**
     * Wall time that moves exactly as the scheduler's virtual time does.
     *
     * The parameter is not called `millis`: inside a `Clock`, `millis()` resolves to the inherited
     * `Clock.millis()` member rather than to a constructor property of that name, and `Clock.millis`
     * is `instant().toEpochMilli()` — so the clock calls itself until the stack ends.
     */
    private class SchedulerClock(private val base: Instant, private val elapsed: () -> Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = base.plusMillis(elapsed())
    }

    /**
     * Builds the loop on `runTest`'s `backgroundScope` and runs [body] against it.
     *
     * `backgroundScope` is what makes this testable at all, and two simpler arrangements were tried
     * first and are dead ends worth not repeating. A loop on an ordinary scope never lets `runTest`
     * finish, because it drains its scheduler to idle and a `while (true)` never reaches idle. And a
     * bare `TestCoroutineScheduler` advanced from outside any test coroutine does not resume the
     * loop's continuations at all: every count stays at one iteration, which reads exactly like a
     * broken loop rather than a broken harness. `backgroundScope` is cancelled when the test ends
     * and is never waited on.
     *
     * **The clock is the same scheduler's**, deliberately: `RefreshDue` compares wall-clock instants
     * while the loop sleeps in virtual time, and two independent clocks advance the sleep without
     * advancing the rule — the same shape as the trap `MapViewModelTest` records about
     * `Dispatchers.setMain`.
     */
    private fun loopTest(body: TestScope.() -> Unit) = runTest {
        loop = ForegroundRefreshLoop(
            clock = SchedulerClock(t0) { testScheduler.currentTime },
            scope = backgroundScope,
            seed = { ForegroundRefreshLoop.Seed(lastFull = null, lastStation = null) },
            place = { DORF_TIROL },
            fullRefresh = {
                fulls++
                // The station rides along with a full refresh, exactly as the repository does it.
                if (!fullFails) stations++
                !fullFails
            },
            stationRefresh = {
                stations++
                // A failure that throws rather than returning false: the loop has to count both the
                // same way, because an upstream can do either.
                if (stationFails) throw IOException("siag down")
                true
            },
            metered = { metered },
            online = { online },
        )
        body()
        loop.stop()
    }

    /** Advances virtual time, draining whatever the loop scheduled on either side of it. */
    private fun TestScope.run(minutes: Long) {
        runCurrent()
        advanceTimeBy(Duration.ofMinutes(minutes).toMillis())
        runCurrent()
    }

    /**
     * The test this whole feature exists to be able to make: twenty-five minutes open is one full
     * refresh and two station polls, not twenty-five of anything.
     *
     * Counts rather than "something happened", because the failure worth guarding against is a loop
     * that works and is merely too eager — which costs somebody's data and nothing else says so.
     */
    @Test
    fun `twenty-five minutes open is one full refresh and two station polls`() = loopTest {
        loop.start()
        run(25)

        assertEquals("full refreshes", 1, fulls)
        // The station rides along with the full refresh at zero, then is polled at 10 and at 20.
        assertEquals("station fetches", 3, stations)
    }

    /** And the full refresh comes round again on its own thirty, taking the station with it. */
    @Test
    fun `the full refresh comes round again after thirty minutes`() = loopTest {
        loop.start()
        run(31)

        assertEquals(2, fulls)
        assertEquals(4, stations)
    }

    /**
     * The half that matters more than the loop.
     *
     * A loop of delays that outlives the app in the background is a battery bug and a data bug at
     * once — it is what `MapScreen.onPauseOrDispose` exists to prevent one package over, where the
     * frames went on turning and the tiles went on downloading behind the home screen.
     */
    @Test
    fun `stopping ends it, however much time passes`() = loopTest {
        loop.start()
        run(15)
        val seen = stations

        loop.stop()
        run(180)

        assertEquals("the loop outlived the app", seen, stations)
    }

    /** A configuration change takes the started-activity count 1 → 0 → 1; two loops double everything. */
    @Test
    fun `starting twice runs one loop`() = loopTest {
        loop.start()
        loop.start()
        run(25)

        assertEquals(1, fulls)
        assertEquals(3, stations)
    }

    /** And it can be started again after being stopped, which is what leaving and returning is. */
    @Test
    fun `it can be restarted`() = loopTest {
        loop.start()
        run(5)
        loop.stop()
        run(60)
        loop.start()
        run(1)

        assertEquals("a restart should refresh the now-stale cache", 2, fulls)
    }

    /**
     * Offline it asks for nothing — and the point is that it does not *try*. A loop attempting and
     * failing every ten minutes on a mountain is the battery bug this feature is most likely to be
     * accused of.
     */
    @Test
    fun `offline it makes no calls at all`() = loopTest {
        online = false
        loop.start()
        run(120)

        assertEquals(0, fulls)
        assertEquals(0, stations)
    }

    /**
     * And the reason the network callback earns its registration: a reader walking back into
     * coverage must not wait out the backoff the dead half hour built up.
     */
    @Test
    fun `the network coming back ends the sleep at once`() = loopTest {
        online = false
        loop.start()
        run(1)
        assertEquals(0, fulls)

        online = true
        loop.wake()
        run(0)

        assertEquals("it should have refreshed the moment signal returned", 1, fulls)
    }

    /** A metered connection asks the station every twenty minutes, and the rest keeps its thirty. */
    @Test
    fun `metered halves the station polling`() = loopTest {
        metered = true
        loop.start()
        run(25)

        assertEquals(1, fulls)
        // The full refresh at zero, then one poll at twenty instead of two at ten and twenty.
        assertEquals(2, stations)
    }

    /**
     * A failure delays the next attempt on the 1, 2, 4 ladder, and the cadence is untouched once it
     * works again: one bad minute on a pass must not leave a working app refreshing half as often
     * for the rest of the day.
     *
     * Driven through the full refresh, which is due from the first instant, so every step of the
     * ladder is a countable attempt rather than something inferred from a gap.
     */
    @Test
    fun `a failing refresh backs off on the ladder and then recovers its cadence`() = loopTest {
        fullFails = true
        loop.start()
        run(0)
        assertEquals("the first attempt", 1, fulls)

        run(1)
        assertEquals("one minute later", 2, fulls)

        run(2)
        assertEquals("two minutes after that", 3, fulls)

        // Nothing at three: the next step of the ladder is four minutes away.
        run(3)
        assertEquals("it retried inside its own backoff", 3, fulls)

        run(1)
        assertEquals("four minutes after the third", 4, fulls)

        fullFails = false
        run(8)
        assertTrue("it never succeeded again", fulls > 4)

        // And straight back to ten minutes for the station, not to the backoff it had climbed.
        val stationsThen = stations
        run(11)
        assertTrue("the cadence never came back", stations > stationsThen)
    }

    /** A throwing upstream is a failure to count, never a crashed loop. */
    @Test
    fun `an upstream that throws does not kill the loop`() = loopTest {
        stationFails = true
        loop.start()
        run(60)
        stationFails = false
        run(15)

        assertTrue("the loop died on the first exception", stations > 2)
    }
}
