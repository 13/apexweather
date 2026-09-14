package it.apexweather.data

import it.apexweather.data.remote.NowcastApi
import it.apexweather.data.remote.NowcastMapper
import it.apexweather.data.remote.PrecipNowcast
import it.apexweather.domain.Place
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The rain forecast for the place the reader is looking at, held in memory the way the radar is.
 *
 * A day of it, in two resolutions: GeoSphere's INCA for the two and a half hours it runs, at a
 * kilometre and a quarter hour, then AROME hourly at 2,5 km to twenty-four. The join is where INCA
 * stops.
 *
 * Nothing here goes into Room, for the same reason [RadarRepository] stores nothing: this is a
 * picture of the next two hours, and a stale one is worse than none. It is keyed by place, because
 * the box it covers is drawn around the place — switching village asks again.
 *
 * A failed fetch keeps whatever was held; the next ask after [MIN_GAP] tries again.
 */
@Singleton
class NowcastRepository @Inject constructor(
    private val api: NowcastApi,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private var istat: String? = null
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

    suspend fun forPlace(place: Place): PrecipNowcast = mutex.withLock {
        val now = clock.instant()
        if (istat == place.istat && !due(now)) return@withLock nowcast
        askedAt = now
        val box = NowcastApi.boxAround(place)
        // The two halves are fetched independently and either is worth having on its own: INCA
        // carries the next two and a half hours at a kilometre and a quarter hour, AROME the rest of
        // the day at 2,5 km and an hour. A failure in one leaves the other's stretch of the timeline
        // standing.
        // An answer with no steps is no answer: the mappers return EMPTY for a response without a
        // reference time, and treating that as a fetch would overwrite a good held run with nothing.
        val near = runCatchingCancellable { NowcastMapper.map(api.precipitation(box)) }.getOrNull()?.takeIf { it.steps.isNotEmpty() }
        val far = runCatchingCancellable {
            NowcastMapper.mapOutlook(
                api.outlook(box, NowcastApi.endOf(now)),
                after = near?.steps?.lastOrNull()?.time,
            )
        }.getOrNull()?.takeIf { it.steps.isNotEmpty() }
        val fetched = when {
            near == null && far == null -> null
            else -> PrecipNowcast(
                issuedAt = near?.issuedAt ?: far!!.issuedAt,
                steps = near?.steps.orEmpty() + far?.steps.orEmpty(),
            )
        }
        if (fetched != null) {
            // A fetch can land long after a run appeared but never before it, so only a shorter
            // delay than the one held is evidence — floored at zero, because a run can be stamped
            // ahead of the clock (GeoSphere's clock and the reader's are not perfectly synced) and a
            // negative lag would ask for the next run before it exists.
            if (near != null && (incaIssuedAt == null || near.issuedAt.isAfter(incaIssuedAt))) {
                val seen = maxOf(Duration.between(near.issuedAt, now), Duration.ZERO)
                if (seen < lag) lag = seen
                incaIssuedAt = near.issuedAt
            }
            if (near == null && istat != place.istat) {
                // AROME alone answered for a place INCA has not; there is no known INCA schedule here.
                incaIssuedAt = null
            }
            nowcast = fetched
            istat = place.istat
        } else if (istat != place.istat) {
            // The held forecast is about somewhere else, and somewhere else's rain is worse than none.
            nowcast = PrecipNowcast.EMPTY
            istat = null
            incaIssuedAt = null
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
