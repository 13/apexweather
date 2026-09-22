package it.apexweather.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface StationHistoryDao {
    @Query("SELECT * FROM station_history WHERE place = :place AND hourEpoch >= :since ORDER BY hourEpoch")
    fun history(place: String, since: Long): Flow<List<StationHistoryEntity>>

    @Query("SELECT * FROM station_history WHERE place = :place AND hourEpoch = :hourEpoch")
    suspend fun at(place: String, hourEpoch: Long): StationHistoryEntity?

    @Upsert suspend fun upsert(entity: StationHistoryEntity)

    @Query("DELETE FROM station_history WHERE hourEpoch < :before") suspend fun prune(before: Long)

    @Query("DELETE FROM station_history WHERE place NOT IN (:keep)") suspend fun evict(keep: List<String>)
}

/**
 * A database of its own, for the one thing in this app that is not a cache.
 *
 * Everything in [AppDatabase] can be fetched again, which is why a schema change there drops it and
 * the next refresh fills it in. `station_history` cannot: nobody publishes what a model said
 * yesterday about an hour that has since happened, so it is only ever accumulated, an hour at a
 * time, and a week of it is a week of [it.apexweather.domain.BiasCorrector]'s evidence. It sat in
 * the cache database and was therefore thrown away by every version bump — twice already.
 *
 * So it lives here, and this database has **no destructive fallback on purpose**. A change to this
 * one table has to be migrated. That is the whole point of the separation, and the day it feels
 * inconvenient is the day it is doing its job.
 */
@Database(entities = [StationHistoryEntity::class], version = 3, exportSchema = true)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun stationHistoryDao(): StationHistoryDao

    companion object {
        /**
         * Version 2: rain and wind beside the temperature, for the statistics screen. Columns are
         * added, never rewritten, because every existing row is evidence nobody can fetch again.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `station_history` ADD COLUMN `observedWindKmh` REAL")
                db.execSQL("ALTER TABLE `station_history` ADD COLUMN `observedPrecipTodayMm` REAL")
                db.execSQL("ALTER TABLE `station_history` ADD COLUMN `modelsRainJson` TEXT")
                db.execSQL("ALTER TABLE `station_history` ADD COLUMN `modelsWindJson` TEXT")
            }
        }

        /**
         * Version 3: which thermometer the reading came from.
         *
         * A place can now read an amateur station instead of the province's own, and the two can be
         * hundreds of metres apart. Mixing their errors into one bias is worse than having no bias
         * at all, and nothing about it would show on screen.
         *
         * Existing rows are left null rather than stamped with a code SQL cannot know. Null is read
         * as "the provincial station" — see [StationHistoryEntity.station] — which is what every
         * row written before this version is.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `station_history` ADD COLUMN `station` TEXT")
            }
        }

        fun build(context: Context): HistoryDatabase =
            Room.databaseBuilder(context, HistoryDatabase::class.java, "apexweather-history.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        fun inMemory(context: Context): HistoryDatabase =
            Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
