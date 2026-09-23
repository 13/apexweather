package it.apexweather.data

import it.apexweather.data.remote.OpenMeteoApi
import it.apexweather.data.remote.WeatherUndergroundApi
import it.apexweather.data.remote.WeatherUndergroundMapper
import it.apexweather.domain.NearbyStation
import it.apexweather.domain.Place
import it.apexweather.domain.model.StationObservation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Every weather station round a place, with what each is reading and the ground under it.
 *
 * **Shared, and cached, because it is expensive.** One neighbourhood costs a `near` call plus a
 * `current` per station — about eleven Weather Underground requests against a cap of 1500 a day —
 * plus one Open-Meteo elevation call for the ground under all of them. Two callers want it: the
 * stations screen, which a reader opens deliberately, and the comparison screen's card, which is
 * on a bottom-bar tab and therefore tapped far more often. Each fetching for itself is how a quota
 * gets spent.
 *
 * Held in memory for [FRESH_FOR] and keyed by place, the same shape as `RadarRepository`'s frame
 * cache and for the same reason: an amateur station uploads every few minutes, so ten minutes is
 * about as fresh as the data itself, and it is what makes a card on a tab affordable at all.
 * Nothing is written to Room — a stored reading would be a measurement with no timestamp anybody
 * checks, and the whole point of these is that they are current.
 */
@Singleton
class NearbyStationsRepository @Inject constructor(
    private val wu: WeatherUndergroundApi,
    private val wuKey: WuKeySource,
    private val openMeteo: OpenMeteoApi,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private var cached: Neighbourhood? = null

    /**
     * The neighbourhood, fetched or remembered.
     *
     * Null where there is no key: there is then nothing to ask, and no neighbourhood exists as far
     * as this app is concerned. A failure to reach Weather Underground throws, so a caller can say
     * *why* rather than showing an empty list that reads as "no stations here".
     */
    suspend fun neighbourhood(place: Place, force: Boolean = false): Neighbourhood? = mutex.withLock {
        val key = wuKey.key()?.takeIf { it.isNotBlank() } ?: return@withLock null
        val now = clock.instant()
        cached?.takeIf { !force && it.istat == place.istat && Duration.between(it.fetchedAt, now) < FRESH_FOR }
            ?.let { return@withLock it }
        // Deliberately assigned only on success: a failed fetch must not be remembered as an answer
        // for ten minutes, or one dropped connection costs the card its whole cache window.
        val fetched = fetch(place, key, now)
        cached = fetched
        fetched
    }

    private suspend fun fetch(place: Place, key: String, now: Instant): Neighbourhood = coroutineScope {
        val near = wu.near("${place.lat},${place.lon}", key).location
        val listed = near.stationId.mapIndexed { i, code ->
            code to (near.stationName.getOrNull(i) to near.distanceKm.getOrNull(i))
        }
        // The catalogue's own station first, because `near` can omit it — ITIROL26 is 0,68 km from
        // Dorf Tirol and is not among the ten this returns. A neighbourhood that left out the
        // station the app is actually reading would be absurd.
        val codes = (listOfNotNull(place.pws?.code) + listed.map { it.first }).distinct()
        val byCode = listed.toMap()

        val fetched = codes.map { code ->
            async {
                val attempt = runCatchingCancellable {
                    val response = wu.current(code, key)
                    response.body().takeIf { response.isSuccessful }
                }
                val body = attempt.getOrNull()
                val o = body?.observations?.firstOrNull()
                val catalogued = place.pws?.takeIf { it.code == code }
                StationProbe(
                    code = code,
                    name = o?.neighborhood ?: byCode[code]?.first ?: code,
                    distanceKm = byCode[code]?.second ?: catalogued?.distanceKm ?: 0.0,
                    lat = o?.lat ?: catalogued?.lat ?: 0.0,
                    lon = o?.lon ?: catalogued?.lon ?: 0.0,
                    claimedAltitudeM = o?.metric?.elev?.toInt(),
                    demAltitudeM = null,
                    reading = body?.let { WeatherUndergroundMapper.map(it, nameFor(code, place)) },
                    // A station that answered 204 has no reading and no error: it is quiet, which
                    // is an ordinary hour and not a fault.
                    error = attempt.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName },
                )
            }
        }.awaitAll()

        // One request for the ground under every station at once. A station that did not answer has
        // no coordinates to ask about and keeps no ground, which is right: nothing is known about
        // where it stands at all, so it cannot be chosen.
        val located = fetched.filter { it.lat != 0.0 || it.lon != 0.0 }
        val elevations = if (located.isEmpty()) null else runCatchingCancellable {
            openMeteo.elevation(
                located.joinToString(",") { it.lat.toString() },
                located.joinToString(",") { it.lon.toString() },
            ).elevation
        }.getOrNull()
        val ground = heightsFrom(elevations, located.size)
        val groundByCode = located.mapIndexed { i, p -> p.code to ground[i] }.toMap()

        Neighbourhood(
            istat = place.istat,
            fetchedAt = now,
            stations = fetched.map { it.copy(demAltitudeM = groundByCode[it.code]) }.sortedBy { it.distanceKm },
        )
    }

    /** The mapper wants a station to fall back to for a name; any of them will do for that. */
    private fun nameFor(code: String, place: Place): NearbyStation =
        place.pws?.takeIf { it.code == code }
            ?: NearbyStation(code = code, name = code, lat = 0.0, lon = 0.0, altitudeM = 0, distanceKm = 0.0, network = "wu")

    companion object {
        /**
         * How long a neighbourhood is worth reusing.
         *
         * An amateur station uploads every few minutes, so ten is about as fresh as the data. It is
         * also the affordability: eleven requests every ten minutes is roughly the daily cap if
         * somebody stared at the card all day, and anything shorter is not payable at all.
         */
        val FRESH_FOR: Duration = Duration.ofMinutes(10)

        /**
         * The ground under each station, or nulls throughout.
         *
         * An answer of the wrong length cannot be matched to the stations that were asked about, and
         * pairing them off by index anyway would put one station's ground under another's name —
         * which is the mistake the whole height check exists to prevent, one level up.
         */
        fun heightsFrom(elevations: List<Double>?, stations: Int): List<Int?> =
            if (elevations == null || elevations.size != stations) List(stations) { null }
            else elevations.map { it.roundToInt() }
    }
}

/** One place's stations, and when they were read. */
data class Neighbourhood(
    val istat: String,
    val fetchedAt: Instant,
    /** Nearest first. */
    val stations: List<StationProbe>,
)

/**
 * One station as the network answered for it.
 *
 * [reading] is null where the station answered but had nothing recent — Weather Underground returns
 * HTTP 204 for a live station that has not reported within the hour, and going quiet for an hour is
 * not the same thing as failing. [error] is for the second case.
 *
 * [claimedAltitudeM] is the station's *own* record of where it stands and [demAltitudeM] the ground
 * under its coordinates. Both, because the difference is the information: WU's elevation form is in
 * feet, and ITIROL26 stood in its records at 204 m against real ground at 654.
 */
data class StationProbe(
    val code: String,
    val name: String,
    val distanceKm: Double,
    val lat: Double,
    val lon: Double,
    val claimedAltitudeM: Int?,
    val demAltitudeM: Int?,
    val reading: StationObservation?,
    val error: String? = null,
)
