package it.apexweather.data

import androidx.test.core.app.ApplicationProvider
import it.apexweather.Fixtures
import it.apexweather.data.local.AppDatabase
import it.apexweather.data.local.SourceForecastEntity
import it.apexweather.data.local.WeatherDao
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
import it.apexweather.domain.DORF_TIROL
import it.apexweather.domain.STERZING
import it.apexweather.domain.model.Source
import it.apexweather.domain.DORF_TIROL
import it.apexweather.domain.STERZING
import it.apexweather.domain.model.SourceStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import kotlin.coroutines.cancellation.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeatherRepositoryTest {

    /** Stores everything except one source, which fails the way a full disk or a corrupt row would. */
    private class FailingStoreDao(private val delegate: WeatherDao, private val failFor: String) : WeatherDao by delegate {
        override suspend fun upsertForecast(entity: SourceForecastEntity) {
            if (entity.source == failFor) throw IOException("disk full")
            delegate.upsertForecast(entity)
        }
    }

    private lateinit var db: AppDatabase
    private val openMeteo = FakeOpenMeteo()
    private val geoSphere = FakeGeoSphere()
    private val siag = FakeSiag()
    private val odh = FakeOdh()
    private val meteoAlarm = FakeMeteoAlarm()
    private val clock = MutableClock(Instant.parse("2026-09-08T14:00:00Z"))
    private lateinit var repo: WeatherRepository

    @Before fun setUp() {
        db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
        repo = WeatherRepository(db.weatherDao(), openMeteo, geoSphere, siag, odh, meteoAlarm, Fixtures.json, clock)
    }

    @After fun tearDown() = db.close()

    @Test
    fun `empty snapshot before any refresh`() = runTest {
        val s = repo.snapshot(DORF_TIROL, "de").first()
        assertTrue(s.isEmpty)
    }

    @Test
    fun `refresh fills every source, the bulletin and the observation`() = runTest {
        val result = repo.refresh(DORF_TIROL, "de")
        assertTrue(result.failed.isEmpty())
        val s = repo.snapshot(DORF_TIROL, "de").first()
        assertEquals(Source.entries.toSet(), s.forecasts.keys)
        assertNotNull(s.bulletin)
        assertNotNull(s.observation)
        assertTrue(s.status.values.all { it is SourceStatus.Ok })
        assertEquals(clock.now, s.lastSuccessfulRefresh)
        assertFalse(s.lastRefreshFailed)
    }

    @Test
    fun `a failing source keeps its cached data and reports Failed`() = runTest {
        repo.refresh(DORF_TIROL, "de")
        geoSphere.fail = true
        clock.now = clock.now.plus(Duration.ofMinutes(30))
        val result = repo.refresh(DORF_TIROL, "de")
        assertEquals(setOf("GEOSPHERE_AROME"), result.failed.keys)
        val s = repo.snapshot(DORF_TIROL, "de").first()
        assertTrue(s.forecasts.containsKey(Source.GEOSPHERE_AROME))
        val st = s.status.getValue(Source.GEOSPHERE_AROME)
        assertTrue(st is SourceStatus.Failed)
        assertNotNull((st as SourceStatus.Failed).lastIssuedAt)
        assertTrue(s.status.getValue(Source.ICON_D2) is SourceStatus.Ok)
        assertFalse(s.lastRefreshFailed) // partial success
    }

    @Test
    fun `all sources failing marks the refresh failed but keeps data`() = runTest {
        repo.refresh(DORF_TIROL, "de")
        val firstRefresh = clock.now
        openMeteo.fail = true; geoSphere.fail = true; siag.fail = true; odh.fail = true; meteoAlarm.fail = true
        clock.now = clock.now.plus(Duration.ofMinutes(30))
        val result = repo.refresh(DORF_TIROL, "de")
        assertTrue(result.succeeded.isEmpty())
        assertTrue(result.allFailed)
        val s = repo.snapshot(DORF_TIROL, "de").first()
        assertTrue(s.lastRefreshFailed)
        assertEquals(firstRefresh, s.lastSuccessfulRefresh) // the failed attempt must not move it forward
        assertEquals(Source.entries.toSet(), s.forecasts.keys) // cached forecasts survive
        assertNotNull(s.bulletin)
        assertTrue(s.status.values.all { it is SourceStatus.Failed })
    }

    @Test
    fun `a store failure is isolated and the refresh still records its meta`() = runTest {
        val failing = WeatherRepository(
            FailingStoreDao(db.weatherDao(), Source.GEOSPHERE_AROME.name),
            openMeteo, geoSphere, siag, odh, meteoAlarm, Fixtures.json, clock,
        )
        val result = failing.refresh(DORF_TIROL, "de")
        assertEquals("store: disk full", result.failed["GEOSPHERE_AROME"])
        val s = failing.snapshot(DORF_TIROL, "de").first()
        assertEquals(Source.entries.toSet() - Source.GEOSPHERE_AROME, s.forecasts.keys) // the others still stored
        assertNotNull(s.bulletin)
        assertNotNull(s.observation)
        assertEquals(clock.now, s.lastSuccessfulRefresh) // upsertMeta was not skipped
        assertFalse(s.lastRefreshFailed)
    }

    @Test
    fun `cancellation propagates and is never recorded as a failed refresh`() = runTest {
        repo.refresh(DORF_TIROL, "de")
        val firstRefresh = clock.now
        geoSphere.cancel = true
        clock.now = clock.now.plus(Duration.ofMinutes(30))
        var thrown: Throwable? = null
        try {
            repo.refresh(DORF_TIROL, "de")
        } catch (e: CancellationException) {
            thrown = e
        }
        assertNotNull(thrown)
        val s = repo.snapshot(DORF_TIROL, "de").first()
        assertFalse(s.lastRefreshFailed) // no bogus Failed state from a cancelled worker
        assertEquals(firstRefresh, s.lastSuccessfulRefresh)
    }

    @Test
    fun `the bulletin cache is per language`() = runTest {
        repo.refresh(DORF_TIROL, "de")
        assertNull(repo.snapshot(DORF_TIROL, "it").first().bulletin) // a German refresh never fills the Italian row
        repo.refresh(DORF_TIROL, "it")
        val italian = repo.snapshot(DORF_TIROL, "it").first().bulletin
        assertNotNull(italian)
        assertEquals("it", italian!!.language)
        assertNotNull(repo.snapshot(DORF_TIROL, "de").first().bulletin) // the German row survives
    }

    @Test
    fun `stale after threshold`() = runTest {
        repo.refresh(DORF_TIROL, "de")
        clock.now = clock.now.plus(Duration.ofHours(7))
        val s = repo.snapshot(DORF_TIROL, "de").first()
        assertTrue(s.status.getValue(Source.ICON_D2) is SourceStatus.Stale)
        assertTrue(s.status.getValue(Source.ECMWF) is SourceStatus.Ok) // 12 h threshold
    }

    /**
     * The hourly worker often wakes the radio and calls straight away, and a connection dropped
     * there used to cost a whole source until the next run an hour later. One retry recovers it.
     */
    @Test
    fun `a dropped connection is retried once and the source still lands`() = runTest {
        openMeteo.failuresBeforeSuccess = 1
        val result = repo.refresh(DORF_TIROL, "de")
        assertEquals(2, openMeteo.forecastCalls)
        assertTrue("OPEN_METEO" in result.succeeded)
        assertEquals(null, result.failed["OPEN_METEO"])
        assertTrue(repo.snapshot(DORF_TIROL, "de").first().forecasts.containsKey(Source.ICON_D2))
    }

    /** A second failure is the source's answer, not the network's; it is not retried forever. */
    @Test
    fun `a source that keeps failing is given up on after one retry`() = runTest {
        openMeteo.failuresBeforeSuccess = 5
        val result = repo.refresh(DORF_TIROL, "de")
        assertEquals(2, openMeteo.forecastCalls)
        assertEquals("connection reset", result.failed["OPEN_METEO"])
    }

    /** Two places, two caches. Switching must never show one place the other's forecast. */
    @Test
    fun `refreshing one place leaves another place's cache alone`() = runTest {
        repo.refresh(DORF_TIROL, "de")
        val dorfTirol = repo.snapshot(DORF_TIROL, "de").first().forecasts
        assertTrue(dorfTirol.isNotEmpty())

        repo.refresh(STERZING, "de")
        assertEquals(dorfTirol.keys, repo.snapshot(DORF_TIROL, "de").first().forecasts.keys)
        assertTrue(repo.snapshot(STERZING, "de").first().forecasts.isNotEmpty())
        // and the two really are different municipalities, not one row read twice
        assertEquals("Dorf Tirol", DORF_TIROL.nameDe)
        assertEquals("Sterzing", STERZING.nameDe)
    }

    /**
     * A place with no station near enough has nothing to observe and nothing to downscale with.
     * That is not a failure — the forecast still arrives, and the app simply says less.
     */
    @Test
    fun `a place without a station stores neither an observation nor a reference`() = runTest {
        repo.refresh(STERZING, "de")
        val s = repo.snapshot(STERZING, "de").first()
        assertNull(s.observation)
        assertNull(s.stationReference)
        assertTrue(s.forecasts.isNotEmpty())
    }

    @Test
    fun `a place the reader has left behind is evicted`() = runTest {
        repo.refresh(STERZING, "de")
        repo.refresh(DORF_TIROL, "de")
        assertTrue(repo.snapshot(STERZING, "de").first().forecasts.isNotEmpty())

        repo.evictAllBut(listOf(DORF_TIROL.istat), listOf(DORF_TIROL.district))
        assertTrue(repo.snapshot(STERZING, "de").first().forecasts.isEmpty())
        assertTrue(repo.snapshot(DORF_TIROL, "de").first().forecasts.isNotEmpty())
    }

    /**
     * Eviction is separate from the refresh precisely so a caller that has lost track of what to
     * keep cannot empty the cache. An empty list means "I do not know", not "keep nothing".
     */
    @Test
    fun `an empty keep list evicts nothing`() = runTest {
        repo.refresh(DORF_TIROL, "de")
        repo.evictAllBut(emptyList(), emptyList())
        assertTrue(repo.snapshot(DORF_TIROL, "de").first().forecasts.isNotEmpty())
    }
}
