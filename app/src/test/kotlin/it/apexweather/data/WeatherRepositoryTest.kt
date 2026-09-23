package it.apexweather.data

import androidx.test.core.app.ApplicationProvider
import it.apexweather.Fixtures
import it.apexweather.data.local.AppDatabase
import it.apexweather.data.local.HistoryDatabase
import it.apexweather.data.local.SourceForecastEntity
import it.apexweather.data.local.StationHistoryEntity
import it.apexweather.data.local.WeatherDao
import it.apexweather.data.remote.GeoSphereApi
import it.apexweather.data.remote.GeoSphereMapper
import it.apexweather.data.remote.GeoSphereResponse
import it.apexweather.data.remote.KmosResponse
import it.apexweather.data.remote.OdhApi
import it.apexweather.data.remote.OdhDistrictResponse
import it.apexweather.data.remote.OdhWeatherResponse
import it.apexweather.data.remote.OpenMeteoApi
import it.apexweather.data.remote.OpenMeteoResponse
import it.apexweather.data.remote.OpenMeteoStationMapper
import it.apexweather.data.remote.SiagApi
import it.apexweather.data.remote.SiagStationsResponse
import it.apexweather.domain.DORF_TIROL
import it.apexweather.domain.DORF_TIROL_WITH_PWS
import it.apexweather.domain.STERZING
import it.apexweather.domain.model.Source
import it.apexweather.domain.DORF_TIROL
import it.apexweather.domain.STERZING
import it.apexweather.domain.model.SourceStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
    private val wu = FakeWeatherUnderground()

    /**
     * A stand-in for the contributor key, mutable because the repository now reads it per fetch:
     * a key typed while the app is open has to work on the next refresh without anything being
     * rebuilt, and that is only testable if the test can change it between calls.
     */
    private var key: String? = "test-key"
    private val odh = FakeOdh()
    private val meteoAlarm = FakeMeteoAlarm()
    private val ensemble = FakeEnsemble()
    private val clock = MutableClock(Instant.parse("2026-09-08T14:00:00Z"))
    private lateinit var repo: WeatherRepository

    @Before fun setUp() {
        db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
        history = HistoryDatabase.inMemory(ApplicationProvider.getApplicationContext())
        repo = WeatherRepository(db.weatherDao(), history.stationHistoryDao(), openMeteo, geoSphere, siag, wu, WuKeySource { key }, odh, meteoAlarm, ensemble, Fixtures.json, clock)
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
     * And about the station's, separately, because that is the only way AROME can ever be
     * bias-corrected.
     *
     * `station_history` holds what a model said about the thermometer's own coordinates, and the
     * Open-Meteo call that fills it carries eight of the ten sources. The other two were therefore
     * the two [it.apexweather.domain.BiasCorrector] could never touch — and they are the two
     * regional non-ICON runs the consensus leans on hardest. AROME takes arbitrary coordinates, so
     * this closes one of them; SIAG KMOS is addressed by municipality and cannot be closed at all.
     */
    @Test
    fun `geosphere is asked about the station as well, for temperature, rain and wind`() = runTest {
        repo.refresh(DORF_TIROL, "de")
        val station = requireNotNull(DORF_TIROL.station)
        assertEquals("${station.lat},${station.lon}", geoSphere.askedForStation)
        assertNotEquals(geoSphere.askedFor, geoSphere.askedForStation)
        assertEquals(
            listOf(GeoSphereMapper.STATION_PARAMS),
            geoSphere.asked.filter { it.first == geoSphere.askedForStation }.map { it.second },
        )
    }

    /** And it lands in the reference series the history is written from. */
    @Test
    fun `the station reference carries AROME beside the open-meteo models`() = runTest {
        repo.refresh(DORF_TIROL, "de")
        val reference = requireNotNull(repo.snapshot(DORF_TIROL, "de").first().stationReference)
        assertTrue(
            "AROME is missing from ${reference.bySource.keys}",
            Source.GEOSPHERE_AROME.name in reference.bySource,
        )
        assertTrue("and so are the Open-Meteo models", Source.ICON_D2.name in reference.bySource)
    }

    /**
     * The two station calls fail independently. Losing AROME must not cost the eight models the
     * hero temperature is corrected with, which is what writing the reference over would have done.
     */
    @Test
    fun `a failed AROME station call leaves the open-meteo reference alone`() = runTest {
        geoSphere.fail = true
        repo.refresh(DORF_TIROL, "de")
        val reference = requireNotNull(repo.snapshot(DORF_TIROL, "de").first().stationReference)
        assertTrue(Source.ICON_D2.name in reference.bySource)
        assertFalse(Source.GEOSPHERE_AROME.name in reference.bySource)
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
        // Nor the second half of it: AROME is asked about the station only where there is one.
        assertNull(geoSphere.askedForStation)
    }

    @Test
    fun `a failing source keeps its cached data and reports Failed`() = runTest {
        repo.refresh(DORF_TIROL, "de")
        geoSphere.fail = true
        clock.now = clock.now.plus(Duration.ofMinutes(30))
        val result = repo.refresh(DORF_TIROL, "de")
        // Both GeoSphere calls fail together here, since it is the one upstream that is down.
        assertEquals(setOf("GEOSPHERE_AROME", "GEOSPHERE_AROME_STATION"), result.failed.keys)
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
            openMeteo, geoSphere, siag, wu, WuKeySource { key }, odh, meteoAlarm, ensemble, Fixtures.json, clock,
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

    private val leadModel = MapSerializer(String.serializer(), MapSerializer(String.serializer(), Double.serializer()))

    /** Both station calls ask for what the statistics need, and the fakes are what prove it. */
    @Test
    fun `the station calls ask for temperature, rain and wind`() = runTest {
        repo.refresh(DORF_TIROL, "de")
        // Literal strings rather than the constants, so a changed constant fails here.
        assertEquals("temperature_2m,precipitation,wind_speed_10m", openMeteo.stationHourly)
        val station = requireNotNull(DORF_TIROL.station)
        assertEquals(
            listOf("t2m,rr_acc,u10m,v10m"),
            geoSphere.asked.filter { it.first == "${station.lat},${station.lon}" }.map { it.second },
        )
    }

    /**
     * What goes into history is rounded to what the quantity can honestly carry: a wind derived from
     * u and v, or rain differenced from an accumulation, otherwise arrives with fifteen digits of
     * float noise and is stored ninety days.
     */
    @Test
    fun `history values are rounded before they are written`() = runTest {
        openMeteo.stationFixture = "openmeteo_station_rain_wind.json"
        clock.now = Instant.parse("2026-09-09T20:00:00Z")
        repo.refresh(DORF_TIROL, "de")
        val rows = history.stationHistoryDao().history(DORF_TIROL.istat, 0L).first()
        assertTrue(rows.any { it.modelsRainJson != null && it.modelsWindJson != null })
        fun check(label: String, v: Double, scale: Double) =
            assertEquals("$label $v is not rounded", Math.round(v * scale) / scale, v, 0.0)
        fun values(text: String?) = text?.let { Fixtures.json.decodeFromString(leadModel, it) }.orEmpty()
            .values.flatMap { it.entries }
        var checked = 0
        rows.forEach { row ->
            row.observedC?.let { check("observed temperature", it, 10.0) }
            row.observedWindKmh?.let { check("observed wind", it, 10.0) }
            row.observedPrecipTodayMm?.let { check("observed rain total", it, 100.0) }
            values(row.modelsJson).forEach { (s, v) -> check("$s temperature", v, 10.0); checked++ }
            values(row.modelsRainJson).forEach { (s, v) -> check("$s rain", v, 100.0); checked++ }
            values(row.modelsWindJson).forEach { (s, v) -> check("$s wind", v, 10.0); checked++ }
        }
        assertTrue(checked > 0)
    }

    @Test
    fun `a reading writes the station's wind and its rain total`() = runTest {
        repo.refresh(DORF_TIROL, "de")
        val observed = history.stationHistoryDao().history(DORF_TIROL.istat, 0L).first().single { it.observedC != null }
        // siag_stations.json, Meran: ff 5.0 m/s, n 0.0 mm.
        assertEquals(18.0, observed.observedWindKmh!!, 1e-9)
        assertEquals(0.0, observed.observedPrecipTodayMm!!, 1e-9)
    }

    @Test
    fun `forecast rain and wind are filed per lead beside the temperature`() = runTest {
        openMeteo.stationFixture = "openmeteo_station_rain_wind.json"
        // Six hours on is 2026-09-10T02:00Z, 04:00 local, where ICON-D2 recorded 3,4 mm.
        clock.now = Instant.parse("2026-09-09T20:00:00Z")
        repo.refresh(DORF_TIROL, "de")
        val target = Instant.parse("2026-09-10T02:00:00Z").epochSecond
        val row = history.stationHistoryDao().history(DORF_TIROL.istat, 0L).first().single { it.hourEpoch == target }
        val rain = Fixtures.json.decodeFromString(leadModel, row.modelsRainJson!!)
        assertEquals(3.4, rain.getValue("SIX").getValue("ICON_D2"), 1e-9)
        val wind = Fixtures.json.decodeFromString(leadModel, row.modelsWindJson!!)
        assertTrue(wind.getValue("SIX").containsKey("ICON_D2"))
        assertTrue("temperature must still be filed", row.modelsJson.contains("SIX"))
    }

    /**
     * A refresh inside an hour must merge rain and wind, never replace them, exactly as temperature
     * already does — modelled on `refreshing again in the same hour keeps what was written twelve
     * hours ago`, which only ever checked `modelsJson`.
     */
    @Test
    fun `rain and wind forecasts merge across refreshes like temperature`() = runTest {
        openMeteo.stationFixture = "openmeteo_station_rain_wind.json"
        // Twelve hours on is 2026-09-10T02:00Z, where ICON-D2 recorded 3,4 mm.
        clock.now = Instant.parse("2026-09-09T14:00:00Z")
        repo.refresh(DORF_TIROL, "de")
        val target = Instant.parse("2026-09-10T02:00:00Z").epochSecond
        val afterTwelve = history.stationHistoryDao().history(DORF_TIROL.istat, 0L).first().single { it.hourEpoch == target }
        val rainTwelve = Fixtures.json.decodeFromString(leadModel, afterTwelve.modelsRainJson!!)
        val windTwelve = Fixtures.json.decodeFromString(leadModel, afterTwelve.modelsWindJson!!)
        assertTrue("TWELVE rain missing", rainTwelve.getValue("TWELVE").containsKey("ICON_D2"))
        assertTrue("TWELVE wind missing", windTwelve.getValue("TWELVE").containsKey("ICON_D2"))

        // Six hours on, the same hour is six hours away and is written again. Both leads must
        // survive, because the twelve-hour-old forecast can never be fetched a second time.
        clock.now = clock.now.plus(Duration.ofHours(6))
        repo.refresh(DORF_TIROL, "de")
        val row = history.stationHistoryDao().history(DORF_TIROL.istat, 0L).first().single { it.hourEpoch == target }
        val rain = Fixtures.json.decodeFromString(leadModel, row.modelsRainJson!!)
        val wind = Fixtures.json.decodeFromString(leadModel, row.modelsWindJson!!)
        val temps = Fixtures.json.decodeFromString(leadModel, row.modelsJson)
        assertTrue("the twelve-hour-old rain forecast was overwritten", rain.getValue("TWELVE").containsKey("ICON_D2"))
        assertTrue("the newer six-hour rain forecast was not added", rain.getValue("SIX").containsKey("ICON_D2"))
        assertTrue("the twelve-hour-old wind forecast was overwritten", wind.getValue("TWELVE").containsKey("ICON_D2"))
        assertTrue("the newer six-hour wind forecast was not added", wind.getValue("SIX").containsKey("ICON_D2"))
        assertTrue("temperature must still hold both leads too", temps.getValue("TWELVE").containsKey("ICON_D2"))
        assertTrue("temperature must still hold both leads too", temps.getValue("SIX").containsKey("ICON_D2"))
    }

    @Test
    fun `history is kept ninety days`() = runTest {
        val dao = history.stationHistoryDao()
        val old = clock.now.minus(java.time.Duration.ofDays(91)).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val recent = clock.now.minus(java.time.Duration.ofDays(30)).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        dao.upsert(StationHistoryEntity(DORF_TIROL.istat, old.epochSecond, 10.0, "{}"))
        dao.upsert(StationHistoryEntity(DORF_TIROL.istat, recent.epochSecond, 11.0, "{}"))
        repo.refresh(DORF_TIROL, "de")
        val hours = dao.history(DORF_TIROL.istat, 0L).first().map { it.hourEpoch }
        assertFalse("91 days old must be pruned", old.epochSecond in hours)
        assertTrue("30 days old must be kept", recent.epochSecond in hours)
    }

    @Test
    fun `the statistics read decodes hours and derives hourly rain`() = runTest {
        val dao = history.stationHistoryDao()
        val h0 = clock.now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).minusSeconds(7200)
        val h1 = h0.plusSeconds(3600)
        dao.upsert(StationHistoryEntity(DORF_TIROL.istat, h0.epochSecond, 20.0, """{"SIX":{"ICON_D2":19.0}}""", 10.0, 1.0, """{"SIX":{"ICON_D2":0.5}}""", """{"SIX":{"ICON_D2":12.0}}"""))
        dao.upsert(StationHistoryEntity(DORF_TIROL.istat, h1.epochSecond, 21.0, """{"SIX":{"ICON_D2":20.5}}""", 11.0, 1.6, null, null))
        val hours = repo.stationHistory(DORF_TIROL).first()
        assertEquals(listOf(h0, h1), hours.map { it.time })
        assertEquals(0.6, hours[1].observedRainMm!!, 1e-9)
        val p0 = hours[0].predicted.getValue(it.apexweather.domain.LeadBucket.SIX).getValue(Source.ICON_D2)
        assertEquals(it.apexweather.domain.Predicted(19.0, 0.5, 12.0), p0)
    }

    // ---- the station on its own, for the loop that runs while the app is open ----

    /**
     * The whole point of the station-only path: one small call, and nothing else touched.
     *
     * Asserted through what landed in the cache rather than only through a call counter, because a
     * path that fetched the models and failed to store them would pass a counter check and still be
     * wrong.
     */
    @Test
    fun `a station refresh fetches the station and nothing else`() = runTest {
        assertTrue(repo.refreshObservation(DORF_TIROL))

        assertEquals(1, siag.stationCalls)
        assertEquals(0, openMeteo.forecastCalls)
        val s = repo.snapshot(DORF_TIROL, "de").first()
        assertNotNull(s.observation)
        assertTrue("a station refresh must not fetch forecasts", s.forecasts.isEmpty())
        assertNull("nor the bulletin", s.bulletin)
        assertTrue("nor the warnings", s.warnings.isEmpty())
    }

    /**
     * `StationDry` needs two readings to tell a gauge that has not moved from one that has, and it
     * gets the second by the repository carrying the first forward. Both write paths go through
     * `storeObservation` so that this cannot hold on one of them and not the other.
     *
     * The cache is seeded by hand rather than by refreshing twice: `FakeSiag` returns one recorded
     * fixture, so two refreshes are two copies of one reading at one time — and `withPrevious` is
     * right to carry nothing forward from a reading to itself. An older point is what the rule is
     * actually for.
     */
    @Test
    fun `a station refresh carries the previous reading forward`() = runTest {
        val earlier = Instant.parse("2026-09-08T13:00:00Z")
        db.weatherDao().upsertObservation(
            it.apexweather.data.local.ObservationEntity(
                DORF_TIROL.istat,
                "siag",
                Fixtures.json.encodeToString(
                    it.apexweather.domain.model.StationObservation.serializer(),
                    it.apexweather.domain.model.StationObservation(
                        stationName = "Meran", time = earlier, tempC = 18.0, humidityPct = 50,
                        windKmh = 4.0, windDir = "W", gustKmh = null, precipTodayMm = 1.2,
                        pressureHpa = 1010.0,
                    ),
                ),
                earlier.toEpochMilli(), null, null,
            ),
        )

        assertTrue(repo.refreshObservation(DORF_TIROL))

        val o = repo.snapshot(DORF_TIROL, "de").first().observation!!
        assertEquals("the gauge lost its previous point", earlier, o.previousTime)
        assertEquals(1.2, o.previousPrecipTodayMm!!, 0.0)
    }

    /** The reason this exists at all: an hour the hourly worker was too late for is still filed. */
    @Test
    fun `a station refresh records the hour in station history`() = runTest {
        repo.refreshObservation(DORF_TIROL)
        val rows = history.stationHistoryDao().history(DORF_TIROL.istat, 0).first()
        assertEquals(1, rows.size)
        assertNotNull(rows.single().observedC)
    }

    /**
     * Pruning is the hourly worker's job. A ten-minute poll sweeping ninety days of history would be
     * 144 sweeps a day for nothing.
     */
    @Test
    fun `a station refresh does not prune the history`() = runTest {
        val old = clock.now.minus(Duration.ofDays(120)).epochSecond / 3600 * 3600
        history.stationHistoryDao().upsert(
            StationHistoryEntity(DORF_TIROL.istat, old, observedC = 1.0, modelsJson = "{}"),
        )
        repo.refreshObservation(DORF_TIROL)
        val rows = history.stationHistoryDao().history(DORF_TIROL.istat, 0).first()
        assertTrue("the ancient row was pruned by the station path", rows.any { it.hourEpoch == old })
    }

    @Test
    fun `a place without a station makes no station call and says so`() = runTest {
        assertFalse(repo.refreshObservation(STERZING))
        assertEquals(0, siag.stationCalls)
    }

    /** A failed poll leaves the cached reading where it is and reports the failure to its caller. */
    @Test
    fun `a failed station refresh keeps what was cached`() = runTest {
        repo.refresh(DORF_TIROL, "de")
        val before = repo.snapshot(DORF_TIROL, "de").first().observation
        siag.fail = true
        clock.now = clock.now.plus(Duration.ofMinutes(10))

        assertFalse(repo.refreshObservation(DORF_TIROL))
        assertEquals(before, repo.snapshot(DORF_TIROL, "de").first().observation)
    }

    /**
     * The reason `refreshObservation` takes the same mutex as `refresh` rather than slipping past
     * it: the observation write is a read-modify-write, and two of them interleaving would lose the
     * carried previous reading.
     */
    @Test
    fun `a station refresh and a full refresh do not interleave`() = runTest {
        val full = async { repo.refresh(DORF_TIROL, "de") }
        val station = async { repo.refreshObservation(DORF_TIROL) }
        full.await()
        station.await()

        val s = repo.snapshot(DORF_TIROL, "de").first()
        assertNotNull(s.observation)
        assertEquals(Source.entries.toSet(), s.forecasts.keys)
    }

    /** What the loop reads to know when it last asked, as opposed to when SIAG last measured. */
    @Test
    fun `the snapshot says when the station was last asked`() = runTest {
        assertNull(repo.snapshot(DORF_TIROL, "de").first().lastObservationFetch)
        repo.refreshObservation(DORF_TIROL)
        assertEquals(clock.now, repo.snapshot(DORF_TIROL, "de").first().lastObservationFetch)
    }

    // ---- amateur stations ------------------------------------------------------------------

    /**
     * A fake that ignores its arguments cannot fail for the reason that matters; see FakeGeoSphere,
     * which learned that the hard way after the app asked GeoSphere for an un-interpolated string
     * template for months while every test passed.
     */
    @Test
    fun `the amateur station is asked for by its own id, with the key`() = runTest {
        repo.refresh(DORF_TIROL_WITH_PWS, "de")
        assertEquals("ITIROL16", wu.askedFor)
        assertEquals("test-key", wu.askedKeys.last())
    }

    /** No amateur station in the catalogue means no request at all, not a request that fails. */
    @Test
    fun `a place with no amateur station never asks`() = runTest {
        repo.refresh(DORF_TIROL, "de")
        assertTrue(wu.asked.isEmpty())
    }

    /**
     * The case every other checkout and CI are in. An empty key must switch the path off entirely
     * rather than send a request that will be refused.
     */
    @Test
    fun `with no key the amateur station is never requested`() = runTest {
        val keyless = WeatherRepository(
            db.weatherDao(), history.stationHistoryDao(), openMeteo, geoSphere, siag, wu,
            WuKeySource { null }, odh, meteoAlarm, ensemble, Fixtures.json, clock,
        )
        keyless.refresh(DORF_TIROL_WITH_PWS, "de")
        assertTrue(wu.asked.isEmpty())
    }

    /** Where it answers and is plausible, the amateur reading is the one the app leads with. */
    @Test
    fun `a fresh amateur reading becomes the observation`() = runTest {
        clock.now = Instant.parse("2026-09-22T20:10:00Z")
        repo.refresh(DORF_TIROL_WITH_PWS, "de")
        val snapshot = repo.snapshot(DORF_TIROL_WITH_PWS, "de").first()
        assertEquals("Tirolo - Tirol", snapshot.observation?.stationName)
        // And the provincial reading is still there, because it supplies what the amateur station
        // does not publish — ITIROL16 has no pyranometer at all.
        assertEquals("Meran", snapshot.officialObservation?.stationName)
    }

    /**
     * HTTP 204 — "nothing in the last 60 minutes" — is an ordinary hour, not a failure. A live
     * station produces it: ITIROL26 answered 204 on three endpoints minutes before answering 200.
     */
    @Test
    fun `a 204 leaves the provincial reading leading`() = runTest {
        wu.noContent = true
        repo.refresh(DORF_TIROL_WITH_PWS, "de")
        val snapshot = repo.snapshot(DORF_TIROL_WITH_PWS, "de").first()
        assertEquals("Meran", snapshot.observation?.stationName)
    }

    /**
     * And so does a reading the models cannot account for at all; see StationFault.
     *
     * The reading is moved onto an hour the other fixtures cover, because the rule compares it
     * against the models' median *at the station* — with no model coverage for the hour there is
     * nothing to compare against and the reading is deliberately left to stand.
     */
    @Test
    fun `an impossible amateur reading falls back to the provincial one`() = runTest {
        // Inside the station fixture's own range, which begins on 2026-09-10. Outside it the models
        // have no opinion about the hour, and the rule then deliberately lets the reading stand.
        val hour = Instant.parse("2026-09-10T12:00:00Z")
        clock.now = hour
        wu.time = hour
        wu.tempC = 99.0
        repo.refresh(DORF_TIROL_WITH_PWS, "de")
        val snapshot = repo.snapshot(DORF_TIROL_WITH_PWS, "de").first()
        // The premise: without this the median is null and the test would pass for the wrong reason.
        assertTrue(snapshot.stationReference!!.at(hour).isNotEmpty())
        assertEquals("Meran", snapshot.observation?.stationName)
    }

    /** An amateur reading half a day old is a station that has stopped, whatever it last said. */
    @Test
    fun `a stale amateur reading falls back to the provincial one`() = runTest {
        clock.now = Instant.parse("2026-09-23T08:00:00Z")
        repo.refresh(DORF_TIROL_WITH_PWS, "de")
        val snapshot = repo.snapshot(DORF_TIROL_WITH_PWS, "de").first()
        assertEquals("Meran", snapshot.observation?.stationName)
    }

    /** The two rows are kept apart, so one never overwrites the other. */
    @Test
    fun `the amateur reading does not overwrite the provincial row`() = runTest {
        repo.refresh(DORF_TIROL_WITH_PWS, "de")
        assertNotNull(db.weatherDao().observationOnce(DORF_TIROL_WITH_PWS.istat, "siag"))
        assertNotNull(db.weatherDao().observationOnce(DORF_TIROL_WITH_PWS.istat, "wu"))
    }

    /**
     * Read per fetch, not captured once: a key typed while the app is open has to work on the next
     * refresh, without the repository being rebuilt or the process restarted.
     */
    @Test
    fun `the key is read at each fetch`() = runTest {
        key = null
        repo.refresh(DORF_TIROL_WITH_PWS, "de")
        assertTrue(wu.asked.isEmpty())

        key = "typed-later"
        // Past COALESCE_WITHIN, or the second call is handed the first one's result and nothing is
        // fetched at all — which would make this test pass for a reason that has nothing to do with
        // the key.
        clock.now = clock.now.plusSeconds(600)
        repo.refresh(DORF_TIROL_WITH_PWS, "de")
        assertEquals("ITIROL16", wu.askedFor)
        assertEquals("typed-later", wu.askedKeys.last())
    }

    /** Blank is how a text field says "I have taken my key back", and it must not be sent. */
    @Test
    fun `a blank key makes no request`() = runTest {
        key = "   "
        repo.refresh(DORF_TIROL_WITH_PWS, "de")
        assertTrue(wu.asked.isEmpty())
    }
}
