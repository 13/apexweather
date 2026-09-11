package it.apexweather.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
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
@Database(entities = [StationHistoryEntity::class], version = 1, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun stationHistoryDao(): StationHistoryDao

    companion object {
        fun build(context: Context): HistoryDatabase =
            Room.databaseBuilder(context, HistoryDatabase::class.java, "apexweather-history.db").build()

        fun inMemory(context: Context): HistoryDatabase =
            Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
