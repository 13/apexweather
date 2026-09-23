package it.apexweather.ui.stations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WuKeySource
import it.apexweather.data.remote.OpenMeteoApi
import it.apexweather.data.remote.WeatherUndergroundApi
import it.apexweather.data.remote.WeatherUndergroundMapper
import it.apexweather.data.runCatchingCancellable
import it.apexweather.domain.NearbyStation
import it.apexweather.domain.model.StationObservation
import it.apexweather.ui.WeatherStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Every Weather Underground station around the chosen place, fetched once when the screen opens.
 *
 * **The fetch lives here and not in `WeatherRepository` on purpose.** One open costs a `near` call
 * plus a `current` per station — around eleven requests against a cap of 1500 a day — which is fine
 * for something a reader opens deliberately and ruinous behind anything on a schedule. In the
 * repository it would eventually acquire a caller with a timer; here the only thing that can ask is
 * a screen somebody navigated to.
 *
 * The result is held for the life of the ViewModel, so coming back to the tab inside a session
 * spends nothing, the way `RadarRepository` holds its frames.
 */
@HiltViewModel
class NearbyStationsViewModel @Inject constructor(
    private val holder: WeatherStateHolder,
    private val wu: WeatherUndergroundApi,
    private val wuKey: WuKeySource,
    private val openMeteo: OpenMeteoApi,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NearbyStationsUiState())
    val state: StateFlow<NearbyStationsUiState> = _state

    init {
        viewModelScope.launch { load() }
    }

    /**
     * Picks this station for this place, and reloads so the screen shows the new state.
     *
     * A row whose ground is unknown has no record to send — see [StationRow.asChosen] — and its card
     * offers no action, so this is a no-op rather than a guard that can be got round.
     */
    fun choose(row: StationRow) {
        val place = holder.weather.value.place ?: return
        val record = row.asChosen(place.istat) ?: return
        viewModelScope.launch {
            settings.setChosenStation(place.istat, record)
            load()
        }
    }

    private suspend fun load() = withContext(Dispatchers.Default) {
        // One read of the shared state: the place the reader is on and the province's own reading of
        // it, which is already in memory and costs nothing to take.
        val weather = holder.weather.first { it.place != null }
        val place = checkNotNull(weather.place)
        val provincial = weather.snapshot.officialObservation
        val key = wuKey.key()?.takeIf { it.isNotBlank() }
        if (key == null) {
            // The entry point should not have been reachable, but a key can be cleared while the
            // screen is open. Saying so beats an empty list that looks like "no stations here".
            _state.value = NearbyStationsUiState(
                placeName = place.name(Locale.getDefault()), loading = false, failed = NO_KEY,
            )
            return@withContext
        }

        val listed = runCatchingCancellable {
            val near = wu.near("${place.lat},${place.lon}", key).location
            near.stationId.mapIndexed { i, code ->
                code to (near.stationName.getOrNull(i) to near.distanceKm.getOrNull(i))
            }
        }.getOrElse {
            _state.value = NearbyStationsUiState(
                placeName = place.name(Locale.getDefault()), loading = false,
                failed = it.message ?: it.javaClass.simpleName,
            )
            return@withContext
        }

        // The catalogue's own station first, because `near` can omit it — ITIROL26 is 0,68 km from
        // Dorf Tirol and is not among the ten this returns. A screen built to show the neighbourhood
        // that left out the station the app is actually reading would be absurd.
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
                Fetched(
                    code = code,
                    name = o?.neighborhood ?: byCode[code]?.first ?: code,
                    lat = o?.lat ?: catalogued?.lat,
                    lon = o?.lon ?: catalogued?.lon,
                    distanceKm = byCode[code]?.second ?: catalogued?.distanceKm ?: 0.0,
                    claimedAltitudeM = o?.metric?.elev?.toInt(),
                    reading = body?.let { WeatherUndergroundMapper.map(it, stationFor(code, place)) },
                    // A station that answered 204 has no reading and no error: it is quiet, which is
                    // an ordinary hour and not a fault.
                    error = attempt.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName },
                )
            }
        }.awaitAll()

        // One request for every station on the screen. A station that did not answer has no
        // coordinates to ask about and is left with no ground, which makes it unselectable — which
        // is right, because nothing is known about where it stands at all.
        val located = fetched.filter { it.lat != null && it.lon != null }
        val points = located.map { it.lat!! to it.lon!! }
        val elevations = if (points.isEmpty()) null else runCatchingCancellable {
            openMeteo.elevation(joinLatitudes(points), joinLongitudes(points)).elevation
        }.getOrNull()
        val ground = heightsFrom(elevations, points.size)
        val groundByCode = located.mapIndexed { i, f -> f.code to ground[i] }.toMap()

        _state.value = NearbyStationsStateBuilder.build(
            place = place,
            probes = fetched.map { it.toProbe(groundByCode[it.code]) },
            provincial = provincial,
            locale = Locale.getDefault(),
        )
    }

    /** What one station answered, before the ground under it is known. */
    private data class Fetched(
        val code: String,
        val name: String,
        val lat: Double?,
        val lon: Double?,
        val distanceKm: Double,
        val claimedAltitudeM: Int?,
        val reading: StationObservation?,
        val error: String?,
    ) {
        fun toProbe(demAltitudeM: Int?) = StationProbe(
            code = code, name = name, distanceKm = distanceKm,
            lat = lat ?: 0.0, lon = lon ?: 0.0,
            claimedAltitudeM = claimedAltitudeM, demAltitudeM = demAltitudeM,
            reading = reading, error = error,
        )
    }

    /** The mapper wants a station to fall back to for a name; any of them will do for that. */
    private fun stationFor(code: String, place: it.apexweather.domain.Place): NearbyStation =
        place.pws?.takeIf { it.code == code }
            ?: NearbyStation(code = code, name = code, lat = 0.0, lon = 0.0, altitudeM = 0, distanceKm = 0.0, network = "wu")

    companion object {
        const val NO_KEY = "no-key"

        fun joinLatitudes(points: List<Pair<Double, Double>>): String = points.joinToString(",") { it.first.toString() }

        fun joinLongitudes(points: List<Pair<Double, Double>>): String = points.joinToString(",") { it.second.toString() }

        /**
         * The ground under each station, or nulls throughout.
         *
         * An answer of the wrong length cannot be matched to the stations that were asked about, and
         * pairing them off by index anyway would put one station's ground under another's name —
         * which is the mistake this whole check exists to prevent, one level up.
         */
        fun heightsFrom(elevations: List<Double>?, stations: Int): List<Int?> =
            if (elevations == null || elevations.size != stations) List(stations) { null }
            else elevations.map { it.roundToInt() }
    }
}
