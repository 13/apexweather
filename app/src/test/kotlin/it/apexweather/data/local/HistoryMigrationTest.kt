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
    fun `version 1 rows survive the migration and gain empty rain and wind`() = runTest {
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
            .addMigrations(HistoryDatabase.MIGRATION_1_2)
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
            }
        } finally {
            room.close()
        }
    }
}
