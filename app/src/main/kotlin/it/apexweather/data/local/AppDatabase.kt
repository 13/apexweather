package it.apexweather.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "source_forecast", primaryKeys = ["place", "source"])
data class SourceForecastEntity(
    val place: String,
    val source: String,
    val json: String?,
    val issuedAtMs: Long?,
    val fetchedAtMs: Long?,
    val lastError: String?,
    val lastErrorAtMs: Long?,
)

/** Keyed by district, not by place: one document serves every municipality in the valley. */
@Entity(tableName = "bulletin", primaryKeys = ["district", "language"])
data class BulletinEntity(
    val district: Int,
    val language: String,
    val json: String?,
    val fetchedAtMs: Long?,
    val lastError: String?,
    val lastErrorAtMs: Long?,
)

@Entity(tableName = "observation")
data class ObservationEntity(
    @PrimaryKey val place: String,
    val json: String?,
    val fetchedAtMs: Long?,
    val lastError: String?,
    val lastErrorAtMs: Long?,
)

/** The civil-protection warnings for the province, as one JSON list in a single row. */
@Entity(tableName = "warnings")
data class WarningsEntity(
    /** A single row: MeteoAlarm publishes for the region, not for a place inside it. */
    @PrimaryKey val id: Int = 0,
    val json: String?,
    val fetchedAtMs: Long?,
    val lastError: String?,
    val lastErrorAtMs: Long?,
)

/** The models' temperature down at the weather station, used to carry its reading up to the village. */
@Entity(tableName = "station_reference")
data class StationReferenceEntity(
    @PrimaryKey val place: String,
    val json: String?,
    val fetchedAtMs: Long?,
    val lastError: String?,
    val lastErrorAtMs: Long?,
)

/** ICON-D2's ensemble spread for this place: how uncertain the forecast is, hour by hour. */
@Entity(tableName = "ensemble")
data class EnsembleEntity(
    @PrimaryKey val place: String,
    val json: String?,
    val fetchedAtMs: Long?,
    val lastError: String?,
    val lastErrorAtMs: Long?,
)

/**
 * One hour of ground truth: what the station read, and what each model said it would read — from
 * how far away.
 *
 * This is the only record in the app that is not a cache of something fetchable — nobody publishes
 * what a model said yesterday about an hour that has since happened. It is what
 * [it.apexweather.domain.BiasCorrector] learns from, and a row is filled in over half a day rather
 * than all at once: the twelve-hour-ahead forecast is written twelve hours before the hour, the
 * six-hour one six hours before, and the observation when the hour finally arrives.
 */
@Entity(tableName = "station_history", primaryKeys = ["place", "hourEpoch"])
data class StationHistoryEntity(
    val place: String,
    val hourEpoch: Long,
    /** Null while the hour is still in the future and only forecasts have been written down. */
    val observedC: Double?,
    /** Lead bucket name → source name → temperature at the station, as JSON. */
    val modelsJson: String,
)

@Entity(tableName = "refresh_meta")
data class RefreshMetaEntity(
    @PrimaryKey val place: String,
    val lastSuccessMs: Long?,
    val lastAttemptMs: Long?,
    val lastAttemptFailed: Boolean,
)

@Dao
interface WeatherDao {
    @Query("SELECT * FROM source_forecast WHERE place = :place") fun forecasts(place: String): Flow<List<SourceForecastEntity>>
    @Query("SELECT * FROM source_forecast WHERE place = :place AND source = :source") suspend fun forecastOnce(place: String, source: String): SourceForecastEntity?
    @Upsert suspend fun upsertForecast(entity: SourceForecastEntity)

    @Query("SELECT * FROM bulletin WHERE district = :district AND language = :language") fun bulletin(district: Int, language: String): Flow<BulletinEntity?>
    @Query("SELECT * FROM bulletin WHERE district = :district AND language = :language") suspend fun bulletinOnce(district: Int, language: String): BulletinEntity?
    @Upsert suspend fun upsertBulletin(entity: BulletinEntity)

    @Query("SELECT * FROM observation WHERE place = :place") fun observation(place: String): Flow<ObservationEntity?>
    @Query("SELECT * FROM observation WHERE place = :place") suspend fun observationOnce(place: String): ObservationEntity?
    @Upsert suspend fun upsertObservation(entity: ObservationEntity)

    @Query("SELECT * FROM station_reference WHERE place = :place") fun stationReference(place: String): Flow<StationReferenceEntity?>
    @Query("SELECT * FROM station_reference WHERE place = :place") suspend fun stationReferenceOnce(place: String): StationReferenceEntity?
    @Upsert suspend fun upsertStationReference(entity: StationReferenceEntity)

    @Query("SELECT * FROM warnings WHERE id = 0") fun warnings(): Flow<WarningsEntity?>
    @Query("SELECT * FROM warnings WHERE id = 0") suspend fun warningsOnce(): WarningsEntity?
    @Upsert suspend fun upsertWarnings(entity: WarningsEntity)

    @Query("SELECT * FROM ensemble WHERE place = :place") fun ensemble(place: String): Flow<EnsembleEntity?>
    @Query("SELECT * FROM ensemble WHERE place = :place") suspend fun ensembleOnce(place: String): EnsembleEntity?
    @Upsert suspend fun upsertEnsemble(entity: EnsembleEntity)


    @Query("SELECT * FROM refresh_meta WHERE place = :place") fun meta(place: String): Flow<RefreshMetaEntity?>
    @Query("SELECT * FROM refresh_meta WHERE place = :place") suspend fun metaOnce(place: String): RefreshMetaEntity?
    @Upsert suspend fun upsertMeta(entity: RefreshMetaEntity)

    /**
     * Drops everything belonging to a place the reader has not been near lately. Called after a
     * refresh that produced something, never after one that failed — a failed refresh must not
     * clear the cache it was supposed to top up.
     */
    @Transaction
    suspend fun evict(keepPlaces: List<String>, keepDistricts: List<Int>) {
        evictForecasts(keepPlaces)
        evictObservations(keepPlaces)
        evictStationReferences(keepPlaces)
        evictMeta(keepPlaces)
        evictEnsembles(keepPlaces)
        evictBulletins(keepDistricts)
    }

    @Query("DELETE FROM source_forecast WHERE place NOT IN (:keep)") suspend fun evictForecasts(keep: List<String>)
    @Query("DELETE FROM observation WHERE place NOT IN (:keep)") suspend fun evictObservations(keep: List<String>)
    @Query("DELETE FROM station_reference WHERE place NOT IN (:keep)") suspend fun evictStationReferences(keep: List<String>)
    @Query("DELETE FROM refresh_meta WHERE place NOT IN (:keep)") suspend fun evictMeta(keep: List<String>)
    @Query("DELETE FROM ensemble WHERE place NOT IN (:keep)") suspend fun evictEnsembles(keep: List<String>)
    @Query("DELETE FROM bulletin WHERE district NOT IN (:keep)") suspend fun evictBulletins(keep: List<Int>)
}

@Database(
    entities = [
        SourceForecastEntity::class, BulletinEntity::class, ObservationEntity::class,
        WarningsEntity::class, StationReferenceEntity::class, EnsembleEntity::class,
        RefreshMetaEntity::class,
    ],
    // 2: the warnings table. 3: the station reference. 4: every row keyed by the place it belongs
    // to, and the bulletin by its district. Every row here is a cache of something fetchable, so a
    // schema change drops the database rather than migrating it; the next refresh fills it again.
    // 6: the ensemble spread. 7: station_history records a forecast per lead time rather than one
    // analysis, and holds a row before its hour has happened. 8: station_history left for a
    // database of its own — see HistoryDatabase — because it was the one table here that a version
    // bump really cost something, and it had already been paid twice.
    version = 8,
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
