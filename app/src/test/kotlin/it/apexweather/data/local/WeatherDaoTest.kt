package it.apexweather.data.local

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test
    fun `upsert replaces forecast row and flow emits it`() = runTest {
        dao.upsertForecast(SourceForecastEntity("ICON_D2", "{}", 1L, 2L, null, null))
        dao.upsertForecast(SourceForecastEntity("ICON_D2", "{\"a\":1}", 3L, 4L, null, null))
        val rows = dao.forecasts().first()
        assertEquals(1, rows.size)
        assertEquals("{\"a\":1}", rows[0].json)
        assertEquals(3L, rows[0].issuedAtMs)
    }

    @Test
    fun `bulletin is keyed by language`() = runTest {
        dao.upsertBulletin(BulletinEntity("de", "{}", 1L, null, null))
        assertEquals("{}", dao.bulletin("de").first()?.json)
        assertNull(dao.bulletin("it").first())
    }

    @Test
    fun `meta and observation singletons`() = runTest {
        assertNull(dao.meta().first())
        dao.upsertMeta(RefreshMetaEntity(lastSuccessMs = 5L, lastAttemptMs = 6L, lastAttemptFailed = false))
        assertEquals(5L, dao.meta().first()?.lastSuccessMs)
        dao.upsertObservation(ObservationEntity(json = "{}", fetchedAtMs = 1L, lastError = null, lastErrorAtMs = null))
        assertEquals("{}", dao.observation().first()?.json)
    }
}
