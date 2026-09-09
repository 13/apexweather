package it.apexweather.data

import it.apexweather.Fixtures
import it.apexweather.data.remote.GeoSphereApi
import it.apexweather.data.remote.GeoSphereResponse
import it.apexweather.data.remote.KmosResponse
import it.apexweather.data.remote.OdhApi
import it.apexweather.data.remote.OdhDistrictResponse
import it.apexweather.data.remote.OdhWeatherResponse
import it.apexweather.data.remote.OpenMeteoApi
import it.apexweather.data.remote.OpenMeteoResponse
import it.apexweather.data.remote.SiagApi
import it.apexweather.data.remote.SiagStationsResponse
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException

/**
 * The four upstreams, answered from the recorded fixtures. Shared by the repository tests and by
 * anything else that needs a repository backed by real payloads rather than hand-written ones.
 */
internal open class FakeOpenMeteo(var fail: Boolean = false) : OpenMeteoApi {
    open override suspend fun forecast(latitude: Double, longitude: Double, timezone: String, forecastDays: Int, models: String, hourly: String, daily: String): OpenMeteoResponse {
        if (fail) throw IOException("open-meteo down")
        return Fixtures.json.decodeFromString(OpenMeteoResponse.serializer(), Fixtures.read("openmeteo.json"))
    }
}

internal class FakeGeoSphere(var fail: Boolean = false, var cancel: Boolean = false) : GeoSphereApi {
    override suspend fun forecast(latLon: String, parameters: String): GeoSphereResponse {
        if (cancel) throw CancellationException("worker stopped")
        if (fail) throw IOException("geosphere down")
        return Fixtures.json.decodeFromString(GeoSphereResponse.serializer(), Fixtures.read("geosphere.json"))
    }
}

internal class FakeSiag(var fail: Boolean = false) : SiagApi {
    override suspend fun municipality(istat: String): KmosResponse {
        if (fail) throw IOException("siag down")
        return Fixtures.json.decodeFromString(KmosResponse.serializer(), Fixtures.read("siag_kmos.json"))
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

internal class MutableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
}
