package it.apexweather.data.local

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeatherDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: WeatherDao

    @Before fun setUp() {
        db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
        dao = db.weatherDao()
    }

    @After fun tearDown() = db.close()

    private fun forecast(place: String, source: String, json: String, issuedAtMs: Long = 1L) =
        SourceForecastEntity(place, source, json, issuedAtMs, 2L, null, null)

    @Test
    fun `upsert replaces forecast row and flow emits it`() = runTest {
        dao.upsertForecast(forecast("021101", "ICON_D2", "{}"))
        dao.upsertForecast(forecast("021101", "ICON_D2", """{"a":1}""", issuedAtMs = 3L))
        val rows = dao.forecasts("021101").first()
        assertEquals(1, rows.size)
        assertEquals("""{"a":1}""", rows[0].json)
        assertEquals(3L, rows[0].issuedAtMs)
    }

    /** Two places, two caches: switching between them must not show one the other's forecast. */
    @Test
    fun `two places keep their own forecasts`() = runTest {
        dao.upsertForecast(forecast("021101", "ICON_D2", """{"a":1}"""))
        dao.upsertForecast(forecast("021115", "ICON_D2", """{"a":2}"""))
        assertEquals("""{"a":1}""", dao.forecasts("021101").first().single().json)
        assertEquals("""{"a":2}""", dao.forecasts("021115").first().single().json)
    }

    /** Two municipalities in one valley read one bulletin; caching it twice would be a second copy. */
    @Test
    fun `the bulletin is keyed by district and language`() = runTest {
        dao.upsertBulletin(BulletinEntity(2, "de", "{}", 1L, null, null))
        assertEquals("{}", dao.bulletin(2, "de").first()?.json)
        assertNull(dao.bulletin(2, "it").first())
        assertNull(dao.bulletin(5, "de").first())
    }

    @Test
    fun `meta and observation are per place`() = runTest {
        assertNull(dao.meta("021101").first())
        dao.upsertMeta(RefreshMetaEntity("021101", lastSuccessMs = 5L, lastAttemptMs = 6L, lastAttemptFailed = false))
        assertEquals(5L, dao.meta("021101").first()?.lastSuccessMs)
        assertNull(dao.meta("021115").first())

        dao.upsertObservation(ObservationEntity("021101", "{}", 1L, null, null))
        assertEquals("{}", dao.observation("021101").first()?.json)
        assertNull(dao.observation("021115").first())
    }

    @Test
    fun `eviction keeps the places it is told to and drops the rest`() = runTest {
        listOf("021101", "021115", "021008", "021051").forEach {
            dao.upsertForecast(forecast(it, "ICON_D2", "{}"))
            dao.upsertObservation(ObservationEntity(it, "{}", 1L, null, null))
            dao.upsertStationReference(StationReferenceEntity(it, "{}", 1L, null, null))
            dao.upsertMeta(RefreshMetaEntity(it, 1L, 1L, false))
        }
        dao.evict(keepPlaces = listOf("021101", "021115", "021008"), keepDistricts = listOf(2))

        listOf("021101", "021115", "021008").forEach {
            assertEquals(1, dao.forecasts(it).first().size)
            assertNotNull(dao.observationOnce(it))
            assertNotNull(dao.stationReferenceOnce(it))
        }
        assertEquals(0, dao.forecasts("021051").first().size)
        assertNull(dao.observationOnce("021051"))
        assertNull(dao.stationReferenceOnce("021051"))
        assertNull(dao.meta("021051").first())
    }

    @Test
    fun `eviction keeps the bulletins of the districts it is told to`() = runTest {
        dao.upsertBulletin(BulletinEntity(2, "de", """{"b":2}""", 1L, null, null))
        dao.upsertBulletin(BulletinEntity(6, "de", """{"b":6}""", 1L, null, null))
        dao.evict(keepPlaces = listOf("021101"), keepDistricts = listOf(2))
        assertNotNull(dao.bulletinOnce(2, "de"))
        assertNull(dao.bulletinOnce(6, "de"))
    }

    /** The warnings are regional, so no place owns them and eviction must never take them. */
    @Test
    fun `eviction leaves the warnings alone`() = runTest {
        dao.upsertWarnings(WarningsEntity(0, "[]", 1L, null, null))
        dao.evict(keepPlaces = emptyList(), keepDistricts = emptyList())
        assertNotNull(dao.warningsOnce())
    }
}
