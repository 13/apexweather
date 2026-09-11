package it.apexweather.data

import androidx.test.core.app.ApplicationProvider
import it.apexweather.Fixtures
import it.apexweather.data.local.AppDatabase
import it.apexweather.data.local.HistoryDatabase
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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
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
import it.apexweather.data.remote.EnsembleApi
import it.apexweather.domain.LeadBucket
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
    private lateinit var history: HistoryDatabase
    private val openMeteo = FakeOpenMeteo()
    private val geoSphere = FakeGeoSphere()
    private val siag = FakeSiag()
    private val odh = FakeOdh()
    private val meteoAlarm = FakeMeteoAlarm()
    private val ensemble = FakeEnsemble()
    private val clock = MutableClock(Instant.parse("2026-09-08T14:00:00Z"))
    private lateinit var repo: WeatherRepository

    @Before fun setUp() {
        db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
        history = HistoryDatabase.inMemory(ApplicationProvider.getApplicationContext())
        repo = WeatherRepository(db.weatherDao(), history.stationHistoryDao(), openMeteo, geoSphere, siag, odh, meteoAlarm, ensemble, Fixtures.json, clock)
    }

    @After fun tearDown() {
        db.close()
        history.close()
    }

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

    /**
     * Both halves of the ensemble are asked for and stored as one: ICON-D2 for the next two days,
     * ECMWF's fifty members for the fortnight behind it. Before this, every day past about the
     * fifth had no measure of uncertainty at all.
     */
    @Test
    fun `both ensembles are fetched and the far one carries the rest of the fortnight`() = runTest {
        repo.refresh(DORF_TIROL, "de")
        assertEquals(setOf(EnsembleApi.ICON_D2, EnsembleApi.ECMWF_ENS), ensemble.requested.toSet())
        val spread = repo.snapshot(DORF_TIROL, "de").first().ensemble!!
        // Something well past where ICON-D2 stops: the fixtures are recorded around 2026-09-10, so
        // an hour eight days out is ECMWF's alone.
        val far = Instant.parse("2026-09-18T12:00:00Z")
        assertNotNull("the far end of the fortnight has no spread", spread.halfWidthAt(far))
    }

    /**
     * The coordinates have to be the place's, and nothing here used to check.
     *
     * From the commit that made the place choosable until this one, the app asked GeoSphere for the
     * literal text of an un-interpolated Kotlin template and was answered HTTP 400 every time — one
     * of the ten sources simply dead, hidden by the app's own habit of keeping whatever it had and
     * carrying on. The fixture came back regardless of what the fake was asked, so the suite stayed
     * green throughout.
     */
    @Test
    fun `geosphere is asked about the place's own coordinates`() = runTest {
        repo.refresh(DORF_TIROL, "de")
        assertEquals("${DORF_TIROL.lat},${DORF_TIROL.lon}", geoSphere.askedFor)

        repo.refresh(STERZING, "de")
        assertEquals("${STERZING.lat},${STERZING.lon}", geoSphere.askedFor)
    }

    /**
     * Two callers asking at once get one fetch, not two.
     *
     * More than one thing here is entitled to ask — the resume hook above the tabs, the hourly
     * worker, the widget's button — and on a first launch two of them arrive together, because
     * WorkManager runs a newly enqueued periodic job straight away on top of the cold-start refresh.
     * Measured on a fresh install before this: every upstream fetched exactly twice.
     */
    @Test
    fun `two refreshes at once make one set of requests`() = runTest {
        val first = async { repo.refresh(DORF_TIROL, "de") }
        val second = async { repo.refresh(DORF_TIROL, "de") }
        val results = listOf(first.await(), second.await())
        assertEquals(1, openMeteo.forecastCalls)
        assertEquals(results[0], results[1])
    }

    /** And once the moment has passed, asking again really asks again. */
    @Test
    fun `a later refresh is not answered from the last one`() = runTest {
        repo.refresh(DORF_TIROL, "de")
        assertEquals(1, openMeteo.forecastCalls)
        clock.now = clock.now.plus(Duration.ofMinutes(1))
        repo.refresh(DORF_TIROL, "de")
        assertEquals(2, openMeteo.forecastCalls)
    }

    /** A different place is a different question, however close together the two are asked. */
    @Test
    fun `another place is never answered with this one's refresh`() = runTest {
        repo.refresh(DORF_TIROL, "de")
        repo.refresh(STERZING, "de")
        assertEquals(2, openMeteo.forecastCalls)
    }

    /**
     * The two Open-Meteo calls are about two different points, and everything the hero shows rests
     * on that: `StationDownscale` quotes the village as the models' village plus the thermometer's
     * disagreement with the models' *station*. Ask about one point twice and the correction is
     * silently zero; swap them and it is silently backwards. Neither would fail anything else.
     */
    @Test
    fun `the village and the station are asked about separately`() = runTest {
        repo.refresh(DORF_TIROL, "de")
        assertEquals(DORF_TIROL.lat to DORF_TIROL.lon, openMeteo.forecastAt)
        val station = DORF_TIROL.station!!
        assertEquals(Triple(station.lat, station.lon, station.altitudeM), openMeteo.stationAt)
        assertNotEquals(openMeteo.forecastAt, openMeteo.stationAt?.let { it.first to it.second })
    }

    /** A place with no station nearby asks about no station. */
    @Test
    fun `a place without a station makes no station call`() = runTest {
        repo.refresh(STERZING, "de")
        assertEquals(STERZING.lat to STERZING.lon, openMeteo.forecastAt)
        assertNull(openMeteo.stationAt)
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
        openMeteo.fail = true; geoSphere.fail = true; siag.fail = true; odh.fail = true; meteoAlarm.fail = true; ensemble.fail = true
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
            FailingStoreDao(db.weatherDao(), Source.GEOSPHERE_AROME.name), history.stationHistoryDao(),
            openMeteo, geoSphere, siag, odh, meteoAlarm, ensemble, Fixtures.json, clock,
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

    /**
     * The one thing this app keeps that nobody publishes: what a model said about an hour that has
     * since happened. Without it there is no ground truth to measure a model against.
     */
    @Test
    fun `a refresh writes down what the station read and what the models said it would`() = runTest {
        repo.refresh(DORF_TIROL, "de")
        val history = history.stationHistoryDao().history(DORF_TIROL.istat, 0L).first()
        val observed = history.filter { it.observedC != null }
        assertEquals(1, observed.size)
        assertTrue(observed.single().modelsJson.contains("ICON"))
        assertTrue(observed.single().observedC!! in -40.0..45.0)
        assertTrue("the reading's own hour must carry a lead-zero forecast", observed.single().modelsJson.contains("NOW"))
    }

    /**
     * And the half of it that has to be written before the hour happens: what this run says about
     * six and twelve hours from now, filed under the hours it is about. Nothing can go back and
     * ask a model what it thought yesterday.
     */
    @Test
    fun `a refresh files what the models say about the hours still to come`() = runTest {
        // Inside the recorded station series, so the run really does reach twelve hours ahead.
        clock.now = Instant.parse("2026-09-11T06:00:00Z")
        repo.refresh(DORF_TIROL, "de")
        val history = history.stationHistoryDao().history(DORF_TIROL.istat, 0L).first()
        val thisHour = clock.now.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        listOf(LeadBucket.SIX, LeadBucket.TWELVE).forEach { lead ->
            val row = history.single { it.hourEpoch == thisHour.plusSeconds(lead.hours * 3600).epochSecond }
            assertTrue("${lead.name} was not filed", row.modelsJson.contains(lead.name))
            assertNull("an hour that has not happened has nothing measured", row.observedC)
        }
    }

    /**
     * A refresh inside an hour must merge, never replace: the twelve-hour-old forecast already in
     * the row is the only thing about it nobody can fetch again.
     */
    @Test
    fun `refreshing again in the same hour keeps what was written twelve hours ago`() = runTest {
        clock.now = Instant.parse("2026-09-11T06:00:00Z")
        repo.refresh(DORF_TIROL, "de")
        val before = history.stationHistoryDao().history(DORF_TIROL.istat, 0L).first()
        val target = clock.now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusSeconds(12 * 3600)
        assertTrue(before.single { it.hourEpoch == target.epochSecond }.modelsJson.contains("TWELVE"))

        // Six hours on, that same hour is six hours away and is written again. The row has to end
        // up holding both, because the twelve-hour-old forecast can never be fetched a second time.
        clock.now = clock.now.plus(Duration.ofHours(6))
        repo.refresh(DORF_TIROL, "de")
        val row = history.stationHistoryDao().history(DORF_TIROL.istat, 0L).first()
            .single { it.hourEpoch == target.epochSecond }
        assertTrue("the twelve-hour-old forecast was overwritten", row.modelsJson.contains("TWELVE"))
        assertTrue("the newer six-hour forecast was not added", row.modelsJson.contains("SIX"))
    }

    /** Once per hour, however many times the app refreshes inside it. */
    @Test
    fun `refreshing twice in the same hour records that hour once`() = runTest {
        repo.refresh(DORF_TIROL, "de")
        val after = history.stationHistoryDao().history(DORF_TIROL.istat, 0L).first().size
        repo.refresh(DORF_TIROL, "de")
        assertEquals(after, history.stationHistoryDao().history(DORF_TIROL.istat, 0L).first().size)
    }

    /** A place with no station has nothing to measure a model against, and records nothing. */
    @Test
    fun `a place without a station records no history`() = runTest {
        repo.refresh(STERZING, "de")
        assertTrue(history.stationHistoryDao().history(STERZING.istat, 0L).first().isEmpty())
    }
}
