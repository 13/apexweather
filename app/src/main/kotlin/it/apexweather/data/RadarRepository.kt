package it.apexweather.data

import it.apexweather.data.remote.RadarFrame
import it.apexweather.data.remote.RainViewerApi
import it.apexweather.data.remote.RainViewerMapper
import it.apexweather.domain.RadarAtPlace
import it.apexweather.domain.RadarReading
import it.apexweather.domain.TilePixel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The radar frames currently on offer, held in memory for as long as they are current, and what
 * each of them saw at a place.
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
    private val decoder: TileDecoder,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private var frames: List<RadarFrame> = emptyList()
    private var fetchedAt: Instant? = null

    /** Readings by frame and pixel. A frame is immutable, so a reading never goes stale, only old. */
    private val readings = HashMap<Pair<Instant, TilePixel>, RadarReading>()

    suspend fun frames(): List<RadarFrame> = mutex.withLock {
        val at = fetchedAt
        if (at != null && Duration.between(at, clock.instant()) < FRESH_FOR) return@withLock frames
        runCatching { RainViewerMapper.map(api.weatherMaps()) }
            .onSuccess { frames = it; fetchedAt = clock.instant() }
        frames
    }

    /**
     * What every current frame saw at ([lat], [lon]), from the one zoom-7 tile that covers it.
     *
     * A frame whose tile could not be fetched or decoded is absent from the result rather than
     * reported dry: an unknown must never overrule a forecast.
     */
    suspend fun readingsAt(lat: Double, lon: Double): Map<Instant, RadarReading> {
        val current = frames()
        val pixel = RadarAtPlace.pixelOf(lat, lon)
        return mutex.withLock {
            val out = LinkedHashMap<Instant, RadarReading>()
            for (frame in current) {
                val key = frame.time to pixel
                val reading = readings[key] ?: runCatching {
                    val bytes = api.tile(frame.tileUrl(pixel.zoom, pixel.x, pixel.y)).use { it.bytes() }
                    decoder.decode(bytes)?.let { RadarAtPlace.read(it.argb, it.width, pixel.px, pixel.py) }
                }.getOrNull()?.also { readings[key] = it }
                if (reading != null) out[frame.time] = reading
            }
            readings.keys.retainAll { (time, _) -> current.any { it.time == time } }
            out
        }
    }

    companion object {
        /** RainViewer publishes a new frame every ten minutes; asking sooner returns the same list. */
        val FRESH_FOR: Duration = Duration.ofMinutes(10)
    }
}
