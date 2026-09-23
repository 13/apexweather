package it.apexweather.ui.stations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.apexweather.data.WuKeySource
import it.apexweather.data.remote.WeatherUndergroundApi
import it.apexweather.data.remote.WeatherUndergroundMapper
import it.apexweather.data.runCatchingCancellable
import it.apexweather.domain.NearbyStation
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
) : ViewModel() {

    private val _state = MutableStateFlow(NearbyStationsUiState())
    val state: StateFlow<NearbyStationsUiState> = _state

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() = withContext(Dispatchers.Default) {
        // One read of the shared state: the place the reader is on and the province's own reading
        // of it, which is already in memory and costs nothing to take.
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
        // Dorf Tirol and is not among the ten this returns. A screen built to show the
        // neighbourhood that left out the station the app is actually reading would be absurd.
        val codes = (listOfNotNull(place.pws?.code) + listed.map { it.first }).distinct()
        val byCode = listed.toMap()

        val probes = codes.map { code ->
            async {
                val attempt = runCatchingCancellable {
                    val response = wu.current(code, key)
                    response.body().takeIf { response.isSuccessful }
                }
                val body = attempt.getOrNull()
                val observation = body?.let {
                    WeatherUndergroundMapper.map(it, stationFor(code, place))
                }
                StationProbe(
                    code = code,
                    name = body?.observations?.firstOrNull()?.neighborhood
                        ?: byCode[code]?.first
                        ?: code,
                    distanceKm = byCode[code]?.second
                        ?: place.pws?.takeIf { it.code == code }?.distanceKm
                        ?: 0.0,
                    claimedAltitudeM = body?.observations?.firstOrNull()?.metric?.elev?.toInt(),
                    reading = observation,
                    // A station that answered 204 has no reading and no error: it is quiet, which
                    // is an ordinary hour and not a fault.
                    error = attempt.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName },
                )
            }
        }.awaitAll()

        _state.value = NearbyStationsStateBuilder.build(
            place = place,
            probes = probes,
            provincial = provincial,
            locale = Locale.getDefault(),
        )
    }

    /** The mapper wants a station to fall back to for a name; any of them will do for that. */
    private fun stationFor(code: String, place: it.apexweather.domain.Place): NearbyStation =
        place.pws?.takeIf { it.code == code }
            ?: NearbyStation(code = code, name = code, lat = 0.0, lon = 0.0, altitudeM = 0, distanceKm = 0.0, network = "wu")

    companion object {
        const val NO_KEY = "no-key"
    }
}
