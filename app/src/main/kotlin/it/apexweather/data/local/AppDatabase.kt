package it.apexweather.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "source_forecast")
data class SourceForecastEntity(
    @PrimaryKey val source: String,
    val json: String?,
    val issuedAtMs: Long?,
    val fetchedAtMs: Long?,
    val lastError: String?,
    val lastErrorAtMs: Long?,
)

@Entity(tableName = "bulletin")
data class BulletinEntity(
    @PrimaryKey val language: String,
    val json: String?,
    val fetchedAtMs: Long?,
    val lastError: String?,
    val lastErrorAtMs: Long?,
)

@Entity(tableName = "observation")
data class ObservationEntity(
    @PrimaryKey val id: Int = 0,
    val json: String?,
    val fetchedAtMs: Long?,
    val lastError: String?,
    val lastErrorAtMs: Long?,
)

/** The civil-protection warnings for the province, as one JSON list in a single row. */
@Entity(tableName = "warnings")
data class WarningsEntity(
    @PrimaryKey val id: Int = 0,
    val json: String?,
    val fetchedAtMs: Long?,
    val lastError: String?,
    val lastErrorAtMs: Long?,
)

/** The models' temperature down at the weather station, used to carry its reading up to the village. */
@Entity(tableName = "station_reference")
data class StationReferenceEntity(
    @PrimaryKey val id: Int = 0,
    val json: String?,
    val fetchedAtMs: Long?,
    val lastError: String?,
    val lastErrorAtMs: Long?,
)

@Entity(tableName = "refresh_meta")
data class RefreshMetaEntity(
    @PrimaryKey val id: Int = 0,
    val lastSuccessMs: Long?,
    val lastAttemptMs: Long?,
    val lastAttemptFailed: Boolean,
)

@Dao
interface WeatherDao {
    @Query("SELECT * FROM source_forecast") fun forecasts(): Flow<List<SourceForecastEntity>>
    @Query("SELECT * FROM source_forecast WHERE source = :source") suspend fun forecastOnce(source: String): SourceForecastEntity?
    @Upsert suspend fun upsertForecast(entity: SourceForecastEntity)

    @Query("SELECT * FROM bulletin WHERE language = :language") fun bulletin(language: String): Flow<BulletinEntity?>
    @Query("SELECT * FROM bulletin WHERE language = :language") suspend fun bulletinOnce(language: String): BulletinEntity?
    @Upsert suspend fun upsertBulletin(entity: BulletinEntity)

    @Query("SELECT * FROM observation WHERE id = 0") fun observation(): Flow<ObservationEntity?>
    @Query("SELECT * FROM observation WHERE id = 0") suspend fun observationOnce(): ObservationEntity?
    @Upsert suspend fun upsertObservation(entity: ObservationEntity)

    @Query("SELECT * FROM warnings WHERE id = 0") fun warnings(): Flow<WarningsEntity?>
    @Query("SELECT * FROM warnings WHERE id = 0") suspend fun warningsOnce(): WarningsEntity?
    @Upsert suspend fun upsertWarnings(entity: WarningsEntity)

    @Query("SELECT * FROM station_reference WHERE id = 0") fun stationReference(): Flow<StationReferenceEntity?>
    @Query("SELECT * FROM station_reference WHERE id = 0") suspend fun stationReferenceOnce(): StationReferenceEntity?
    @Upsert suspend fun upsertStationReference(entity: StationReferenceEntity)

    @Query("SELECT * FROM refresh_meta WHERE id = 0") fun meta(): Flow<RefreshMetaEntity?>
    @Upsert suspend fun upsertMeta(entity: RefreshMetaEntity)
}

@Database(
    entities = [
        SourceForecastEntity::class, BulletinEntity::class, ObservationEntity::class,
        WarningsEntity::class, StationReferenceEntity::class, RefreshMetaEntity::class,
    ],
    // 2: the warnings table. 3: the station reference. Every row here is a cache of something
    // fetchable, so a schema change drops the database rather than migrating it; the next refresh
    // fills it again.
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun weatherDao(): WeatherDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "apexweather.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
