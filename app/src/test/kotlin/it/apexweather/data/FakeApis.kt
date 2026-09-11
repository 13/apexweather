package it.apexweather.data

import it.apexweather.Fixtures
import it.apexweather.data.remote.EnsembleApi
import it.apexweather.data.remote.EnsembleResponse
import it.apexweather.data.remote.GeoSphereApi
import it.apexweather.data.remote.GeoSphereResponse
import it.apexweather.data.remote.KmosResponse
import it.apexweather.data.remote.MeteoAlarmApi
import it.apexweather.data.remote.OdhApi
import it.apexweather.data.remote.OdhDistrictResponse
import it.apexweather.data.remote.OdhWeatherResponse
import it.apexweather.data.remote.OpenMeteoApi
import it.apexweather.data.remote.OpenMeteoResponse
import it.apexweather.data.remote.OpenMeteoStationResponse
import it.apexweather.data.remote.SiagApi
import it.apexweather.data.remote.SiagStationsResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException

/**
 * The five upstreams, answered from the recorded fixtures. Shared by the repository tests and by
 * anything else that needs a repository backed by real payloads rather than hand-written ones.
 */
/**
 * Open-Meteo, and what it was asked about.
 *
 * The coordinates are recorded because a fake that ignores its arguments cannot fail for the reason
 * that matters — which is not hypothetical here. GeoSphere spent months being sent an
 * un-interpolated Kotlin template instead of a latitude and answering HTTP 400, and every test
 * passed throughout, because its fake handed back the fixture whatever it was given. These two
 * calls are the pair most worth watching: the village's own forecast and the station's, which
 * `StationDownscale` compares against each other. Swap them and the hero temperature is quietly
 * wrong; nothing else would notice.
 */
internal open class FakeOpenMeteo(var fail: Boolean = false) : OpenMeteoApi {
    /** How many times the forecast call was made, so a retry can be told from a single attempt. */
    var forecastCalls: Int = 0

    /** Fails this many times and then succeeds, for testing the retry. */
    var failuresBeforeSuccess: Int = 0

    /** The village's coordinates, as last asked for. */
    var forecastAt: Pair<Double, Double>? = null

    /** The station's coordinates and the elevation given with them. */
    var stationAt: Triple<Double, Double, Int>? = null

    open override suspend fun forecast(
        latitude: Double, longitude: Double, timezone: String, forecastDays: Int,
        models: String, hourly: String, daily: String, minutely: String, minutelySteps: Int,
    ): OpenMeteoResponse {
        forecastCalls++
        forecastAt = latitude to longitude
        requirePlausible(latitude, longitude)
        if (failuresBeforeSuccess >= forecastCalls) throw IOException("connection reset")
        if (fail) throw IOException("open-meteo down")
        // The recording of the request the app actually makes today: fourteen days, eight models,
        // the freezing level asked for. `openmeteo.json` is kept for OpenMeteoMapperTest alone,
        // where its job is to be a response that predates two of those changes.
        return Fixtures.json.decodeFromString(OpenMeteoResponse.serializer(), Fixtures.read("openmeteo_14d.json"))
    }

    override suspend fun stationForecast(
        latitude: Double, longitude: Double, elevation: Int, timezone: String,
        pastDays: Int, forecastDays: Int, models: String, hourly: String,
    ): OpenMeteoStationResponse {
        stationAt = Triple(latitude, longitude, elevation)
        requirePlausible(latitude, longitude)
        if (fail) throw IOException("open-meteo down")
        return Fixtures.json.decodeFromString(OpenMeteoStationResponse.serializer(), Fixtures.read("openmeteo_station.json"))
    }

    /** Somewhere in this province, which is the only place this app ever asks about. */
    private fun requirePlausible(latitude: Double, longitude: Double) {
        require(latitude in 45.0..49.0 && longitude in 9.0..14.0) {
            "asked about ($latitude, $longitude), which is not in or near South Tyrol"
        }
    }
}

internal class FakeGeoSphere(var fail: Boolean = false, var cancel: Boolean = false) : GeoSphereApi {
    /**
     * What it was asked about, so a test can check it.
     *
     * This fake used to ignore its arguments and hand back the fixture whatever it was given, and
     * for months the app asked GeoSphere for the literal text `${'$'}{place.lat},${'$'}{place.lon}` — an
     * un-interpolated string template — and got HTTP 400 for it every time. Every test passed. A
     * fake that does not look at the request cannot fail for the one reason that mattered.
     */
    var askedFor: String? = null

    override suspend fun forecast(latLon: String, parameters: String): GeoSphereResponse {
        askedFor = latLon
        if (cancel) throw CancellationException("worker stopped")
        if (fail) throw IOException("geosphere down")
        require(latLon.split(",").mapNotNull { it.trim().toDoubleOrNull() }.size == 2) {
            "lat_lon must be two numbers, was \"$latLon\""
        }
        return Fixtures.json.decodeFromString(GeoSphereResponse.serializer(), Fixtures.read("geosphere.json"))
    }
}

internal class FakeSiag(var fail: Boolean = false) : SiagApi {
    override suspend fun municipality(istat: String): KmosResponse {
        if (fail) throw IOException("siag down")
        // Two municipalities, so a test can prove one place's cache is not the other's.
        val fixture = if (istat == "021101") "siag_kmos.json" else "siag_kmos_sterzing.json"
        return Fixtures.json.decodeFromString(KmosResponse.serializer(), Fixtures.read(fixture))
    }
    override suspend fun stations(categoryId: Int, visibility: Int): SiagStationsResponse {
        if (fail) throw IOException("siag down")
        return Fixtures.json.decodeFromString(SiagStationsResponse.serializer(), Fixtures.read("siag_stations.json"))
    }
}

internal class FakeOdh(var fail: Boolean = false) : OdhApi {
    override suspend fun weather(language: String): OdhWeatherResponse {
        if (fail) throw IOException("odh down")
        return Fixtures.json.decodeFromString(OdhWeatherResponse.serializer(), Fixtures.read("odh_weather_de.json"))
    }
    override suspend fun district(id: Int, language: String): OdhDistrictResponse {
        if (fail) throw IOException("odh down")
        return Fixtures.json.decodeFromString(OdhDistrictResponse.serializer(), Fixtures.read("odh_district2_de.json"))
    }
}

internal class FakeMeteoAlarm(var fail: Boolean = false) : MeteoAlarmApi {
    override suspend fun italy(): ResponseBody {
        if (fail) throw IOException("meteoalarm down")
        return Fixtures.read("meteoalarm_italy.xml").toResponseBody("application/atom+xml".toMediaType())
    }
}

internal class FakeEnsemble(var fail: Boolean = false) : EnsembleApi {
    /** Which ensembles were asked for, in order, so a test can prove both halves were fetched. */
    val requested = mutableListOf<String>()

    override suspend fun forecast(
        latitude: Double, longitude: Double, models: String, forecastDays: Int, timezone: String, hourly: String,
    ): EnsembleResponse {
        requested += models
        if (fail) throw IOException("ensemble down")
        val fixture =
            if (models == EnsembleApi.ECMWF_ENS) "openmeteo_ensemble_ecmwf.json" else "openmeteo_ensemble.json"
        return Fixtures.json.decodeFromString(EnsembleResponse.serializer(), Fixtures.read(fixture))
    }
}

internal class MutableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
}
