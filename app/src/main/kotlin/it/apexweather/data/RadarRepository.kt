package it.apexweather.data

import it.apexweather.data.remote.RadarFrame
import it.apexweather.data.remote.RainViewerApi
import it.apexweather.data.remote.RainViewerMapper
import it.apexweather.domain.RadarAtPlace
import it.apexweather.domain.RadarNow
import it.apexweather.domain.RadarReading
import it.apexweather.domain.TilePixel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** The newest radar reading at a place; [RadarRepository] in the app, a stub in tests. */
fun interface RadarNowSource {
    suspend fun latestAt(lat: Double, lon: Double): RadarNow?
}

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
class RadarRepository internal constructor(
    private val api: RainViewerApi,
    private val decoder: TileDecoder,
    private val clock: Clock,
    /** Where a tile's body is read. */
    private val io: CoroutineDispatcher,
    /** Where it is decoded and its pixels read. */
    private val compute: CoroutineDispatcher,
) : RadarNowSource {
    @Inject constructor(api: RainViewerApi, decoder: TileDecoder, clock: Clock) :
        this(api, decoder, clock, Dispatchers.IO, Dispatchers.Default)

    private val mutex = Mutex()

    /**
     * Serialises whole [readingsAt] calls: init, the place collector and a tab resume can all ask
     * for the same place close together, and without this each built its own missing list and its
     * own semaphore, fetching the same tiles twice with up to twice [MAX_PARALLEL_TILES] in flight
     * at once. A second caller now waits, finds the first caller's tiles already cached, and fetches
     * nothing. [mutex] is unchanged and still guards only the maps themselves.
     */
    private val readingsLock = Mutex()
    private var frames: List<RadarFrame> = emptyList()
    private var fetchedAt: Instant? = null

    /** Readings by frame and pixel. A frame is immutable, so a reading never goes stale, only old. */
    private val readings = HashMap<Pair<Instant, TilePixel>, RadarReading>()

    suspend fun frames(): List<RadarFrame> = mutex.withLock {
        val at = fetchedAt
        if (at != null && Duration.between(at, clock.instant()) < FRESH_FOR) return@withLock frames
        runCatchingCancellable { RainViewerMapper.map(api.weatherMaps()) }
            .onSuccess { frames = it; fetchedAt = clock.instant() }
        frames
    }

    /**
     * What every current frame saw at ([lat], [lon]), from the one zoom-7 tile that covers it.
     *
     * A frame whose tile could not be fetched or decoded is absent from the result rather than
     * reported dry: an unknown must never overrule a forecast.
     *
     * The fetches themselves run outside [mutex]: a network call can take a while, and holding the
     * mutex across thirteen of them serialised every one of them behind the last, which is what made
     * the radar check slow enough to be worth showing the map without. They run up to
     * [MAX_PARALLEL_TILES] at a time instead, and [mutex] is only ever held around the in-memory
     * map — working out what is missing, and writing back what came in. The whole call sits behind
     * [readingsLock] as well, so two overlapping callers cannot each go fetch the same tiles.
     */
    suspend fun readingsAt(lat: Double, lon: Double): Map<Instant, RadarReading> {
        val current = frames()
        return readingsFor(current, current, RadarAtPlace.pixelOf(lat, lon))
    }

    /**
     * The newest frame's reading at ([lat], [lon]), for the home screen's current hour: one tile
     * rather than the loop's thirteen, and none at all while the frame list is fresh and read.
     */
    override suspend fun latestAt(lat: Double, lon: Double): RadarNow? {
        val current = frames()
        val newest = current.maxByOrNull { it.time } ?: return null
        val reading = readingsFor(listOf(newest), current, RadarAtPlace.pixelOf(lat, lon))[newest.time] ?: return null
        return RadarNow(newest.time, reading)
    }

    /** Readings for [wanted] at [pixel], keeping the cache to what [current] still names. */
    private suspend fun readingsFor(wanted: List<RadarFrame>, current: List<RadarFrame>, pixel: TilePixel): Map<Instant, RadarReading> {
        return readingsLock.withLock {
            val missing = mutex.withLock { wanted.filter { (it.time to pixel) !in readings } }
            if (missing.isNotEmpty()) {
                val semaphore = Semaphore(MAX_PARALLEL_TILES)
                coroutineScope {
                    missing.map { frame ->
                        async {
                            semaphore.withPermit {
                                val reading = runCatchingCancellable {
                                    val body = api.tile(frame.tileUrl(pixel.zoom, pixel.x, pixel.y))
                                    // Retrofit resumes on the caller's dispatcher, which is the
                                    // ViewModel's Main: reading the body, decoding the PNG and
                                    // reading its pixels ran there thirteen times on a cold open.
                                    val bytes = withContext(io) { body.use { it.bytes() } }
                                    withContext(compute) {
                                        decoder.decode(bytes)?.let { RadarAtPlace.read(it.argb, it.width, pixel.px, pixel.py) }
                                    }
                                }.getOrNull()
                                // Written down the moment it arrives, not after every tile has: a
                                // refresh is cancelled whenever a newer one starts, and readings held
                                // back for the rest used to be thrown away with it and fetched again.
                                if (reading != null) mutex.withLock { readings[frame.time to pixel] = reading }
                            }
                        }
                    }.awaitAll()
                }
            }
            mutex.withLock {
                val out = LinkedHashMap<Instant, RadarReading>()
                for (frame in wanted) {
                    readings[frame.time to pixel]?.let { out[frame.time] = it }
                }
                readings.keys.retainAll { (time, _) -> current.any { it.time == time } }
                out
            }
        }
    }

    companion object {
        /** RainViewer publishes a new frame every ten minutes; asking sooner returns the same list. */
        val FRESH_FOR: Duration = Duration.ofMinutes(10)

        /** How many tiles fetch at once: enough to be worth it, few enough to not flood the host. */
        const val MAX_PARALLEL_TILES = 4
    }
}
