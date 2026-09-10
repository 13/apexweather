package it.apexweather.data

import it.apexweather.data.remote.RadarFrame
import it.apexweather.data.remote.RainViewerApi
import it.apexweather.data.remote.RainViewerMapper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The radar frames currently on offer, held in memory for as long as they are current.
 *
 * Nothing here goes into Room. Every other upstream in this app is cached because the app renders
 * whatever it has while offline and says how old it is; radar frames are two hours of imagery whose
 * tiles are not stored either, so a cached frame list would name pictures that can no longer be
 * fetched.
 *
 * A failed fetch keeps whatever was already held and does not restart the clock, so the next time
 * the reader opens the map it tries again rather than waiting out the interval.
 */
@Singleton
class RadarRepository @Inject constructor(
    private val api: RainViewerApi,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private var frames: List<RadarFrame> = emptyList()
    private var fetchedAt: Instant? = null

    suspend fun frames(): List<RadarFrame> = mutex.withLock {
        val at = fetchedAt
        if (at != null && Duration.between(at, clock.instant()) < FRESH_FOR) return@withLock frames
        runCatching { RainViewerMapper.map(api.weatherMaps()) }
            .onSuccess { frames = it; fetchedAt = clock.instant() }
        frames
    }

    companion object {
        /** RainViewer publishes a new frame every ten minutes; asking sooner returns the same list. */
        val FRESH_FOR: Duration = Duration.ofMinutes(10)
    }
}
