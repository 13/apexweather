package it.apexweather.data

import android.util.Log
import it.apexweather.data.remote.NowcastApi
import it.apexweather.data.remote.NowcastGrid
import it.apexweather.data.remote.NowcastKind
import it.apexweather.data.remote.PrecipNowcast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
@Singleton
class NowcastRepository @Inject constructor(
    private val source: NowcastSource,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private var nowcast: PrecipNowcast = PrecipNowcast.EMPTY
    private var askedAt: Instant? = null

    /** How long after its reference time a run appears. Only ever lowered; see [forPlace]. */
    private var lag: Duration = SEED_LAG

    /**
     * The reference time of the newest INCA run held, independent of AROME. [due] reads this, not
     * [nowcast]'s own `issuedAt` — that field can be AROME's reference time when INCA has failed and
     * AROME has not, and AROME's hourly schedule says nothing about when INCA's next run appears.
     */
    private var incaIssuedAt: Instant? = null

    suspend fun current(): PrecipNowcast = mutex.withLock {
        val now = clock.instant()
        if (!due(now)) return@withLock nowcast
        askedAt = now
        // The two halves are fetched independently and either is worth having on its own: INCA
        // carries the next two and a half hours at a kilometre and a quarter hour, AROME the rest of
        // the day at 2,5 km and an hour. A failure in one leaves the other's stretch of the timeline
        // standing. An answer with no steps is no answer: treating it as a fetch would overwrite a
        // good held run with nothing. Side by side, because neither should wait out the other.
        val (near, far) = coroutineScope {
            val nearAsync = async {
                runCatchingCancellable { source.nowcast() }.getOrNull()?.takeIf { it.steps.isNotEmpty() }
            }
            // The outlook is kept whole, with nothing trimmed at INCA's end: Heute needs every AROME
            // hour, including the ones Jetzt's [steps] drops as already covered by INCA's finer run.
            val farAsync = async {
                runCatchingCancellable { source.outlook(NowcastApi.endOf(now)) }.getOrNull()?.takeIf { it.steps.isNotEmpty() }
            }
            nearAsync.await() to farAsync.await()
        }
        val nearEnd = near?.steps?.lastOrNull()?.time
        if (near != null || far != null) {
            // A fetch can land long after a run appeared but never before it, so only a shorter
            // delay than the one held is evidence — floored at zero, because a run can be stamped
            // ahead of the clock (GeoSphere's clock and the reader's are not perfectly synced) and a
            // negative lag would ask for the next run before it exists.
            if (near != null && (incaIssuedAt == null || near.issuedAt.isAfter(incaIssuedAt))) {
                val seen = maxOf(Duration.between(near.issuedAt, now), Duration.ZERO)
                if (seen < lag) lag = seen
                incaIssuedAt = near.issuedAt
            }
            nowcast = PrecipNowcast(
                issuedAt = near?.issuedAt ?: far!!.issuedAt,
                steps = near?.steps.orEmpty() + far?.steps.orEmpty().filter { nearEnd == null || it.time.isAfter(nearEnd) },
                outlook = far?.steps.orEmpty(),
            )
        }
        nowcast
    }

    /**
     * Whether a newer INCA run should be on offer: the held run's reference time, plus the fifteen
     * minutes to the next run, plus how late runs appear. Never more often than [MIN_GAP].
     */
    private fun due(now: Instant): Boolean {
        val asked = askedAt ?: return true
        if (Duration.between(asked, now) < MIN_GAP) return false
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

        /** The floor under all of it, so a late run costs a request every three minutes. */
        val MIN_GAP: Duration = Duration.ofMinutes(3)
    }
}
