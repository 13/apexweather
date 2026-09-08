package it.apexweather.data

import androidx.test.core.app.ApplicationProvider
import it.apexweather.Fixtures
import it.apexweather.data.local.AppDatabase
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
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeatherRepositoryTest {

    private class FakeOpenMeteo(var fail: Boolean = false) : OpenMeteoApi {
        override suspend fun forecast(latitude: Double, longitude: Double, timezone: String, forecastDays: Int, models: String, hourly: String, daily: String): OpenMeteoResponse {
            if (fail) throw IOException("open-meteo down")
            return Fixtures.json.decodeFromString(OpenMeteoResponse.serializer(), Fixtures.read("openmeteo.json"))
        }
    }
    private class FakeGeoSphere(var fail: Boolean = false) : GeoSphereApi {
        override suspend fun forecast(latLon: String, parameters: String): GeoSphereResponse {
            if (fail) throw IOException("geosphere down")
            return Fixtures.json.decodeFromString(GeoSphereResponse.serializer(), Fixtures.read("geosphere.json"))
        }
    }
    private class FakeSiag(var fail: Boolean = false) : SiagApi {
        override suspend fun municipality(istat: String): KmosResponse {
            if (fail) throw IOException("siag down")
            return Fixtures.json.decodeFromString(KmosResponse.serializer(), Fixtures.read("siag_kmos.json"))
        }
        override suspend fun stations(categoryId: Int, visibility: Int): SiagStationsResponse {
            if (fail) throw IOException("siag down")
            return Fixtures.json.decodeFromString(SiagStationsResponse.serializer(), Fixtures.read("siag_stations.json"))
        }
    }
    private class FakeOdh : OdhApi {
        override suspend fun weather(language: String) =
            Fixtures.json.decodeFromString(OdhWeatherResponse.serializer(), Fixtures.read("odh_weather_de.json"))
        override suspend fun district(id: Int, language: String) =
            Fixtures.json.decodeFromString(OdhDistrictResponse.serializer(), Fixtures.read("odh_district2_de.json"))
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
        override fun instant() = now
    }

    private lateinit var db: AppDatabase
    private val openMeteo = FakeOpenMeteo()
    private val geoSphere = FakeGeoSphere()
    private val siag = FakeSiag()
    private val clock = MutableClock(Instant.parse("2026-09-08T14:00:00Z"))
    private lateinit var repo: WeatherRepository

    @Before fun setUp() {
        db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
        repo = WeatherRepository(db.weatherDao(), openMeteo, geoSphere, siag, FakeOdh(), Fixtures.json, clock)
    }

    @After fun tearDown() = db.close()

    @Test
    fun `empty snapshot before any refresh`() = runTest {
        val s = repo.snapshot("de").first()
        assertTrue(s.isEmpty)
    }

    @Test
    fun `refresh fills all seven sources, bulletin and observation`() = runTest {
        val result = repo.refresh("de")
        assertTrue(result.failed.isEmpty())
        val s = repo.snapshot("de").first()
        assertEquals(Source.entries.toSet(), s.forecasts.keys)
        assertNotNull(s.bulletin)
        assertNotNull(s.observation)
        // Nothing may be Failed right after a fully successful refresh. SIAG_KMOS is Stale even so:
        // staleness is measured on issuedAt (spec 4.2, regional 6 h) and the recorded fixture's model run
        // (currentModelRun 2026-09-08T02:00+02:00) is 14 h before the test clock.
        assertTrue(s.status.values.none { it is SourceStatus.Failed })
        assertTrue(s.status.getValue(Source.ICON_D2) is SourceStatus.Ok)
        assertTrue(s.status.getValue(Source.ECMWF) is SourceStatus.Ok)
        assertTrue(s.status.getValue(Source.GEOSPHERE_AROME) is SourceStatus.Ok)
        assertEquals(SourceStatus.Stale(Instant.parse("2026-09-08T00:00:00Z")), s.status.getValue(Source.SIAG_KMOS))
        assertEquals(clock.now, s.lastSuccessfulRefresh)
        assertFalse(s.lastRefreshFailed)
    }

    @Test
    fun `a failing source keeps its cached data and reports Failed`() = runTest {
        repo.refresh("de")
        geoSphere.fail = true
        clock.now = clock.now.plus(Duration.ofMinutes(30))
        val result = repo.refresh("de")
        assertEquals(setOf("GEOSPHERE_AROME"), result.failed.keys)
        val s = repo.snapshot("de").first()
        assertTrue(s.forecasts.containsKey(Source.GEOSPHERE_AROME))
        val st = s.status.getValue(Source.GEOSPHERE_AROME)
        assertTrue(st is SourceStatus.Failed)
        assertNotNull((st as SourceStatus.Failed).lastIssuedAt)
        assertTrue(s.status.getValue(Source.ICON_D2) is SourceStatus.Ok)
        assertFalse(s.lastRefreshFailed) // partial success
    }

    @Test
    fun `all sources failing marks the refresh failed but keeps data`() = runTest {
        repo.refresh("de")
        openMeteo.fail = true; geoSphere.fail = true; siag.fail = true
        val result = repo.refresh("de")
        assertTrue(result.failed.size >= 3)
        val s = repo.snapshot("de").first()
        assertEquals(Source.entries.toSet(), s.forecasts.keys)
        assertNotNull(s.bulletin) // ODH still works
    }

    @Test
    fun `stale after threshold`() = runTest {
        repo.refresh("de")
        clock.now = clock.now.plus(Duration.ofHours(7))
        val s = repo.snapshot("de").first()
        assertTrue(s.status.getValue(Source.ICON_D2) is SourceStatus.Stale)
        assertTrue(s.status.getValue(Source.ECMWF) is SourceStatus.Ok) // 12 h threshold
    }
}
