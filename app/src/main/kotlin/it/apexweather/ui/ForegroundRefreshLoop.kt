package it.apexweather.ui

import it.apexweather.data.RefreshDue
import it.apexweather.data.RefreshKind
import it.apexweather.domain.Place
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The loop that keeps the app fresh while somebody is looking at it.
 *
 * It is its own class, taking functions rather than the objects behind them, and that is not
 * ceremony. The loop needs four facts — when each half was last fetched, which place, whether the
 * connection is metered, whether there is one at all — and wiring it to `WeatherStateHolder` and
 * `WeatherRepository` directly would drag a DataStore, a Room database, an asset read and three
 * `WhileSubscribed` flows into every test of a `delay`. What is worth testing here is the *loop*:
 * that it asks on the cadence, that it stops, that starting twice runs one. [StaleRefresher] owns
 * the wiring; this owns the timing.
 *
 * All of *when* to fetch lives in [RefreshDue]; this only does what that says.
 */
class ForegroundRefreshLoop(
    private val clock: Clock,
    private val scope: CoroutineScope,
    /** When each half was last fetched, read once when the loop starts. */
    private val seed: suspend () -> Seed,
    private val place: () -> Place?,
    /** A full refresh; true if it left something newer behind. */
    private val fullRefresh: suspend () -> Boolean,
    private val stationRefresh: suspend (Place) -> Boolean,
    private val metered: () -> Boolean,
    private val online: () -> Boolean,
) {
    /**
     * Where the loop starts counting from.
     *
     * Seeded from the cache rather than from zero: a loop starting on a warm cache must not decide
     * that nothing has ever been fetched and refresh everything on the spot, which would fire on
     * every return to the app and duplicate the resume hook it sits beside.
     */
    data class Seed(val lastFull: Instant?, val lastStation: Instant?)

    private var job: Job? = null

    /** Cancels the current sleep. The network coming back must not wait out a backoff. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * Starts it, and does nothing if it is already running.
     *
     * Idempotent because a configuration change takes the started-activity count 1 → 0 → 1, and two
     * loops would double every request the app makes.
     */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    /**
     * Stops it, and this is the half that matters more than the loop.
     *
     * A `while (true)` of delays that outlives the app in the background is a battery bug and a data
     * bug at once — exactly what `MapScreen.onPauseOrDispose` exists to prevent one package over,
     * where the frames went on turning and the tiles went on downloading behind the home screen.
     */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Ends the current sleep, for the network coming back. */
    fun wake() {
        wake.trySend(Unit)
    }

    private suspend fun run() {
        val start = seed()
        var lastFull = start.lastFull
        var lastStation = start.lastStation
        var failures = 0

        while (true) {
            val decision = RefreshDue.next(
                now = clock.instant(),
                lastStation = lastStation,
                lastFull = lastFull,
                metered = metered(),
                online = online(),
            )
            when (decision.kind) {
                RefreshKind.FULL -> {
                    if (attempt { fullRefresh() }) {
                        failures = 0
                        // A full refresh fetches the station as one of its branches, so both clocks
                        // move. Leaving the station's alone would ask again within the minute for a
                        // reading that refresh had just written.
                        lastFull = clock.instant()
                        lastStation = lastFull
                    } else {
                        failures++
                    }
                }
                RefreshKind.STATION -> {
                    val p = place()
                    if (p != null && attempt { stationRefresh(p) }) {
                        failures = 0
                        lastStation = clock.instant()
                    } else {
                        failures++
                    }
                }
                null -> Unit
            }

            // After acting, ask again rather than sleeping on the wait that came with the action.
            // A full refresh is due every thirty minutes, so sleeping on *its* wait meant doing one
            // and then going quiet for thirty — the station never polled at ten and twenty at all.
            // The rule only knows how long to wait once there is nothing left to do.
            when {
                failures > 0 -> sleepOrWake(RefreshDue.backoff(failures))
                decision.kind != null -> continue
                else -> sleepOrWake(decision.wait)
            }
        }
    }

    /**
     * Sleeps, unless [wake] arrives first.
     *
     * `withTimeoutOrNull` rather than a `select` with `onTimeout`: it is ordinary `delay` semantics,
     * which is what a test scheduler drives.
     */
    private suspend fun sleepOrWake(wait: Duration) {
        withTimeoutOrNull(wait.toMillis()) { wake.receive() }
    }

    /** A failed fetch is a failure to count, never a crashed loop. Cancellation is neither. */
    private suspend fun attempt(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}
