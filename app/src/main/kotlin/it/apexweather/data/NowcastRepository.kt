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
 * A failed fetch keeps whatever was held and does not restart the clock, so the next visit tries
 * again rather than waiting out the interval.
 */
@Singleton
class NowcastRepository @Inject constructor(
    private val api: NowcastApi,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private var istat: String? = null
    private var nowcast: PrecipNowcast = PrecipNowcast.EMPTY
    private var fetchedAt: Instant? = null

    suspend fun forPlace(place: Place): PrecipNowcast = mutex.withLock {
        val at = fetchedAt
        if (istat == place.istat && at != null && Duration.between(at, clock.instant()) < FRESH_FOR) {
            return@withLock nowcast
        }
        val box = NowcastApi.boxAround(place)
        // The two halves are fetched independently and either is worth having on its own: INCA
        // carries the next two and a half hours at a kilometre and a quarter hour, AROME the rest of
        // the day at 2,5 km and an hour. A failure in one leaves the other's stretch of the timeline
        // standing.
        val near = runCatching { NowcastMapper.map(api.precipitation(box)) }.getOrNull()
        val far = runCatching {
            NowcastMapper.mapOutlook(
                api.outlook(box, NowcastApi.endOf(clock.instant())),
                after = near?.steps?.lastOrNull()?.time,
            )
        }.getOrNull()
        val fetched = when {
            near == null && far == null -> null
            else -> PrecipNowcast(
                issuedAt = near?.issuedAt ?: far!!.issuedAt,
                steps = near?.steps.orEmpty() + far?.steps.orEmpty(),
            )
        }
        if (fetched != null) {
            nowcast = fetched
            istat = place.istat
            fetchedAt = clock.instant()
        } else if (istat != place.istat) {
            // The held forecast is about somewhere else, and somewhere else's rain is worse than none.
            nowcast = PrecipNowcast.EMPTY
        }
        nowcast
    }

    companion object {
        /** INCA runs quarter-hourly; asking oftener than the radar's own interval buys nothing. */
        val FRESH_FOR: Duration = Duration.ofMinutes(10)
    }
}
