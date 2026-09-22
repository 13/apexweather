package it.apexweather.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `station_history` is the one table nobody can fetch again, so the move to version 2 is proven on
 * a real version-1 file: the table exactly as Room created it, two rows in it, then the migration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "history-migration-test.db"

    @After fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun `version 1 rows survive every migration and gain empty rain, wind and station`() = runTest {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `station_history` (`place` TEXT NOT NULL, `hourEpoch` INTEGER NOT NULL, " +
                    "`observedC` REAL, `modelsJson` TEXT NOT NULL, PRIMARY KEY(`place`, `hourEpoch`))",
            )
            db.execSQL("INSERT INTO station_history VALUES ('021101', 1789830000, 18.5, '{\"NOW\":{\"ICON_D2\":18.1}}')")
            db.execSQL("INSERT INTO station_history VALUES ('021101', 1789833600, NULL, '{\"SIX\":{\"ICON_D2\":17.0}}')")
            db.version = 1
        }

        val room = Room.databaseBuilder(context, HistoryDatabase::class.java, name)
            .addMigrations(HistoryDatabase.MIGRATION_1_2, HistoryDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val rows = room.stationHistoryDao().history("021101", 0L).first()
            assertEquals(2, rows.size)
            assertEquals(18.5, rows[0].observedC!!, 0.0)
            assertEquals("{\"NOW\":{\"ICON_D2\":18.1}}", rows[0].modelsJson)
            assertNull(rows[1].observedC)
            assertEquals("{\"SIX\":{\"ICON_D2\":17.0}}", rows[1].modelsJson)
            rows.forEach {
                assertNull(it.observedWindKmh)
                assertNull(it.observedPrecipTodayMm)
                assertNull(it.modelsRainJson)
                assertNull(it.modelsWindJson)
                // Null is "the provincial station", which is the only thermometer a version-1 row
                // can be about; see StationHistoryEntity.station.
                assertNull(it.station)
            }
        } finally {
            room.close()
        }
    }

    /**
     * Version 3 adds the station a row's reading came from.
     *
     * Until a place could change thermometer there was only one answer and the column was not
     * needed. Now that an amateur station can displace the provincial one, a row without it is a
     * measurement of an unknown instrument, and BiasCorrector would subtract a habit averaged over
     * two thermometers 264 m apart — silently, in the one table this app never throws away.
     *
     * Existing rows are left null rather than guessed at from inside SQL. Null means "the
     * provincial station", because that is the only thermometer this app had ever read; the read
     * side says so where it interprets it.
     */
    @Test
    fun `version 2 rows survive the migration to 3 and their station is unknown`() = runTest {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `station_history` (`place` TEXT NOT NULL, `hourEpoch` INTEGER NOT NULL, " +
                    "`observedC` REAL, `modelsJson` TEXT NOT NULL, `observedWindKmh` REAL, " +
                    "`observedPrecipTodayMm` REAL, `modelsRainJson` TEXT, `modelsWindJson` TEXT, " +
                    "PRIMARY KEY(`place`, `hourEpoch`))",
            )
            db.execSQL(
                "INSERT INTO station_history VALUES ('021101', 1789830000, 18.5, " +
                    "'{\"NOW\":{\"ICON_D2\":18.1}}', 7.0, 0.4, NULL, NULL)",
            )
            db.version = 2
        }

        val room = Room.databaseBuilder(context, HistoryDatabase::class.java, name)
            .addMigrations(HistoryDatabase.MIGRATION_1_2, HistoryDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val rows = room.stationHistoryDao().history("021101", 0L).first()
            assertEquals(1, rows.size)
            assertEquals(18.5, rows[0].observedC!!, 0.0)
            assertEquals(7.0, rows[0].observedWindKmh!!, 0.0)
            assertEquals(0.4, rows[0].observedPrecipTodayMm!!, 0.0)
            assertNull(rows[0].station)
        } finally {
            room.close()
        }
    }
}
