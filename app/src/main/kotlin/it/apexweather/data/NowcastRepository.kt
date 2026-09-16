package it.apexweather.data

import android.util.Log
import it.apexweather.data.remote.NowcastApi
import it.apexweather.data.remote.NowcastGrid
import it.apexweather.data.remote.NowcastKind
import it.apexweather.data.remote.PrecipNowcast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Where the forecast comes from: GeoSphere in the app, a fake in tests. */
interface NowcastSource {
    /** INCA, the next two and a half hours; [PrecipNowcast.EMPTY] when the answer holds nothing. */
    suspend fun nowcast(): PrecipNowcast

    /** AROME's ensemble, up to [end] (see [NowcastApi.endOf]). */
    suspend fun outlook(end: String): PrecipNowcast
}

/** The province's NetCDF, read off the main thread: a download on IO, the decoding on Default. */
class GeoSphereNowcastSource @Inject constructor(private val api: NowcastApi) : NowcastSource {
    override suspend fun nowcast() = decode(logged(NowcastKind.NOWCAST) { api.precipitation() }, NowcastKind.NOWCAST)
    override suspend fun outlook(end: String) = decode(logged(NowcastKind.OUTLOOK) { api.outlook(end) }, NowcastKind.OUTLOOK)

    private suspend fun logged(kind: NowcastKind, fetch: suspend () -> ResponseBody): ResponseBody = try {
        fetch()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "cannot fetch the $kind grid", e)
        throw e
    }

    private companion object {
        const val TAG = "NowcastSource"
    }

    private suspend fun decode(body: ResponseBody, kind: NowcastKind): PrecipNowcast {
        val bytes = withContext(Dispatchers.IO) { body.use { it.bytes() } }
        // Logged, because a reader that fails quietly looks exactly like a dry province: that is how
        // the first minified build shipped no forecast at all (see proguard-rules.pro).
        // A successful read is logged too, once per fetch: tools/release-smoke.sh looks for it.
        return withContext(Dispatchers.Default) {
            NowcastGrid.map(bytes, kind) { Log.w(TAG, "cannot read the $kind grid", it) }
                .also { if (it.steps.isNotEmpty()) Log.i(TAG, "read the $kind grid: ${it.steps.size} steps") }
        }
    }
}

/**
 * The rain forecast for the province, held in memory the way the radar is.
 *
 * A day of it, in two resolutions: GeoSphere's INCA for the two and a half hours it runs, at a
 * kilometre and a quarter hour, then AROME hourly at 2,5 km to twenty-four. The join is where INCA
 * stops.
 *
 * Nothing here goes into Room, for the same reason [RadarRepository] stores nothing: this is a
 * picture of the next two hours, and a stale one is worse than none. It covers the whole province,
 * as the radar does, so one run serves every place and switching village asks nothing new.
 *
 * A failed fetch keeps whatever was held; the next ask after [MIN_GAP] tries again.
 */
/** Whether the connection costs the reader money; [NowcastRepository] asks less often when it does. */
fun interface MeteredNetwork {
    fun isMetered(): Boolean
}

@Singleton
class NowcastRepository @Inject constructor(
    private val source: NowcastSource,
    private val clock: Clock,
    private val network: MeteredNetwork,
) {
    /** For tests, which run unmetered. */
    constructor(source: NowcastSource, clock: Clock) : this(source, clock, MeteredNetwork { false })

    private val mutex = Mutex()

    /** INCA and AROME are held apart: either failing keeps the other, and either can be shown first. */
    private var near: PrecipNowcast? = null
    private var far: PrecipNowcast? = null
    private var askedAt: Instant? = null

    /** How long after its reference time a run appears. Only ever lowered; see [current]. */
    private var lag: Duration = SEED_LAG

    /**
     * The reference time of the newest INCA run held, independent of AROME. [due] reads this rather
     * than the merged forecast's `issuedAt`, which is AROME's when INCA has failed, and AROME's
     * hourly schedule says nothing about when INCA's next run appears.
     */
    private var incaIssuedAt: Instant? = null

    /**
     * The held forecast, fetching a newer one first where one is due.
     *
     * [onPartial] hears the forecast as soon as either half of a fetch has landed, while the other is
     * still on its way: on 2026-09-16 GeoSphere took 17 s for INCA and half a second for AROME, and
     * the map showed nothing ahead of the radar for all of it.
     */
    suspend fun current(onPartial: suspend (PrecipNowcast) -> Unit = {}): PrecipNowcast = mutex.withLock {
        val now = clock.instant()
        if (!due(now)) return@withLock merged()
        askedAt = now
        // The two halves are fetched independently and either is worth having on its own. An
        // answer with no steps is no answer: treating it as a fetch would overwrite a good held run
        // with nothing. Side by side, because neither should wait out the other.
        coroutineScope {
            val landed = Mutex()
            var pending = 2
            suspend fun land(update: () -> Unit) = landed.withLock {
                update()
                pending--
                if (pending == 1) onPartial(merged())
            }
            launch {
                val fetched = runCatchingCancellable { source.nowcast() }.getOrNull()?.takeIf { it.steps.isNotEmpty() }
                land {
                    if (fetched != null) {
                        // A fetch can land long after a run appeared but never before it, so only a
                        // shorter delay than the one held is evidence — floored at zero, because a run
                        // can be stamped ahead of the clock and a negative lag would ask for the next
                        // run before it exists.
                        if (incaIssuedAt == null || fetched.issuedAt.isAfter(incaIssuedAt)) {
                            val seen = maxOf(Duration.between(fetched.issuedAt, now), Duration.ZERO)
                            if (seen < lag) lag = seen
                            incaIssuedAt = fetched.issuedAt
                        }
                        near = fetched
                    }
                }
            }
            // The outlook is kept whole, with nothing trimmed at INCA's end: Heute needs every AROME
            // hour, including the ones Jetzt's steps drop as already covered by INCA's finer run.
            launch {
                val fetched = runCatchingCancellable { source.outlook(NowcastApi.endOf(now)) }.getOrNull()?.takeIf { it.steps.isNotEmpty() }
                land { if (fetched != null) far = fetched }
            }
        }
        merged()
    }

    /** INCA's steps, then AROME's past INCA's end; AROME whole for the Heute zoom. */
    private fun merged(): PrecipNowcast {
        val n = near
        val f = far
        if (n == null && f == null) return PrecipNowcast.EMPTY
        val nearEnd = n?.steps?.lastOrNull()?.time
        return PrecipNowcast(
            issuedAt = n?.issuedAt ?: f!!.issuedAt,
            steps = n?.steps.orEmpty() + f?.steps.orEmpty().filter { nearEnd == null || it.time.isAfter(nearEnd) },
            outlook = f?.steps.orEmpty(),
        )
    }

    /**
     * Whether a newer INCA run should be on offer: the held run's reference time, plus the fifteen
     * minutes to the next run, plus how late runs appear. Never more often than [MIN_GAP].
     */
    private fun due(now: Instant): Boolean {
        val asked = askedAt ?: return true
        if (Duration.between(asked, now) < MIN_GAP) return false
        // About 730 kB a fetch, every quarter of an hour while the map is open: nothing on Wi-Fi,
        // real money on a phone roaming over a pass. The same bargain the pinned places make.
        if (network.isMetered() && Duration.between(asked, now) < METERED_GAP) return false
        // The schedule is INCA's alone: AROME's hourly reference time says nothing about when
        // INCA's next quarter-hourly run appears.
        val inca = incaIssuedAt ?: return true
        return !now.isBefore(inca.plus(RUN_STEP).plus(lag))
    }

    companion object {
        /** INCA's cadence. */
        val RUN_STEP: Duration = Duration.ofMinutes(15)

        /** Measured 2026-09-14: the 05:00Z run was on offer at 05:36Z and the 05:15Z one by 05:49Z. */
        val SEED_LAG: Duration = Duration.ofMinutes(35)

        /** How often a metered connection is asked to carry the province's forecast. */
        val METERED_GAP: Duration = Duration.ofMinutes(30)

        /** The floor under all of it, so a late run costs a request every three minutes. */
        val MIN_GAP: Duration = Duration.ofMinutes(3)
    }
}
