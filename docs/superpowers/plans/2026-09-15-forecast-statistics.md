# Forecast Statistics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Statistik screen, reached from a card on Vergleich, that ranks the models by how right they have been at the place's station for temperature, rain and wind, over 7, 30 or 90 days and 0, 6 or 12 hours ahead.

**Architecture:**
- `station_history` gains rain and wind, observed and forecast, through a column-adding Room migration, and is kept for 90 days.
- A pure `VerificationHistory` turns rows into scorable hours, deriving hourly rain from the station's daily total.
- A pure `ForecastScores` ranks models against the consensus and a "same as yesterday" baseline.
- `StatsStateBuilder` feeds a `StatsViewModel` and the Vergleich card.

**Tech Stack:** Kotlin, Room 2.8 (KSP), Jetpack Compose Material 3, Hilt, Navigation Compose (type-safe routes), kotlinx-serialization, JUnit4, Robolectric, Roborazzi.

Spec: `docs/superpowers/specs/2026-09-15-forecast-statistics-design.md`.

## Global Constraints

- **Git:** work on branch `forecast-statistics` in `/home/ben/repo/apexweather`. Do not push, tag or merge.
- **Shell:** run every command as `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c '…'`.
- **Gradle:** in the foreground only (Bash timeout up to 600000 ms), never `run_in_background`, never two Gradle runs at once. If the daemon reports "stop command received", rerun the same command once.
- **Device tests and adb:** do **not** run any device/connected test and do **not** use adb. A connected test run on the phone uninstalls the app and deletes `station_history`. Compile androidTest code with `./gradlew :app:compileDebugAndroidTestKotlin`; the controller runs device tests.
- **History database:** `HistoryDatabase` has **no destructive fallback**. Every schema change is a written `Migration`.
- **Bias correction:** `BiasCorrector` and its 7-day `WINDOW` must behave exactly as before. The snapshot flow keeps reading only the 7-day window.
- **Strings:** every new key goes into `app/src/main/res/values/strings.xml` (German), `values-it/strings.xml` and `values-en/strings.xml`. Nothing that runs on a device may assert a German string.
- **Formatting:** numbers and times go through `ui/common/Format.kt` / `Formats` from `LocalFormats.current`, and times are in `SouthTyrol.ZONE`.
- **Fakes:** a fake that takes request parameters must assert what it was given.
- **Bottom sheets:** a `ModalBottomSheet` has `skipPartiallyExpanded = true` and a close cross.
- **Lint:** `./gradlew :app:lintDebug` runs with `warningsAsErrors` and must be clean. Never add to the disabled-checks list. Use `<plurals>` where lint's `PluralsCandidate` asks.
- **Commits:** one commit per task. Every commit message ends with exactly these two lines, whatever model you are:
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_016gqqzcABukZdCYES9zAg21
  ```

## Adjustments to the spec, decided while planning

- **Storage shape.** New nullable columns instead of reshaping `modelsJson`. The spec reshaped the temperature JSON into `{t, rr, ff}`. This plan leaves `modelsJson` exactly as it is and adds `observedWindKmh`, `observedPrecipTodayMm`, `modelsRainJson` and `modelsWindJson`. The migration is four `ALTER TABLE … ADD COLUMN` statements, no row is rewritten, and `BiasCorrector`'s read path is untouched. Same outcome, far less risk to the one table that cannot be refetched.
- **Test fixtures.** A new recorded fixture for the station call. `openmeteo_station.json` is left untouched, because calibrated temperature tests depend on its numbers. `openmeteo_station_rain_wind.json` was recorded on 2026-09-15 from the live API with `hourly=temperature_2m,precipitation,wind_speed_10m`, `past_days=5`, `forecast_days=2`, for Meran's coordinates. It covers 2026-09-10 00:00 to 2026-09-16 23:00 local time, 168 hours, with ICON-D2 wet 2026-09-10 02:00 to 06:00. `FakeOpenMeteo` serves it when a test selects it.
- **Consensus for rain** is the weighted **mean** (`ConsensusBlender.weightedMean`), as the app's own rain blend is. Temperature and wind use the weighted median.
- **Hour alignment for rain.** Open-Meteo's `precipitation` at hour T is the sum over the preceding hour. The station row at T holds the daily total read at that hour's reading (T:00 to T:59). The derived hourly rain is therefore off by the reading's minutes. This is accepted and documented, not corrected.
- **"Details zur Quelle ›"** in the model detail sheet sets `compare_source` on the Vergleich back-stack entry and pops back. `CompareViewModel` watches that key, so its source sheet opens with its run line.
- **The daily error chart** exists for temperature and wind. For rain the detail sheet shows the part-of-day scores only, because a daily rain score is mostly undefined on dry days.

## File Structure

| File | Responsibility |
|---|---|
| `data/local/AppDatabase.kt` (modify) | `StationHistoryEntity` gains four nullable columns. |
| `data/local/HistoryDatabase.kt` (modify) | Version 2, `MIGRATION_1_2`, `exportSchema = true`. |
| `app/build.gradle.kts` (modify) | `room.schemaLocation` KSP arg. |
| `app/schemas/it.apexweather.data.local.HistoryDatabase/2.json` (generated, committed) | Exported schema. |
| `data/remote/OpenMeteoApi.kt` (modify) | `StationReference` rain and wind maps; `OpenMeteoStationMapper` reads them; station call asks for them. |
| `data/remote/GeoSphereApi.kt` (modify) | `GeoSphereMapper.STATION_PARAMS`. |
| `domain/VerificationHistory.kt` (create) | `StationHistoryRow`, `Predicted`, `VerificationHour`, hourly rain derivation, `KEEP`. |
| `data/WeatherRepository.kt` (modify) | Records rain and wind; prunes at 90 days; `stationHistory(place)` flow. |
| `domain/ForecastScores.kt` (create) | `Quantity`, `Contender`, `Score`, `RankedRow`, `Ranking`, scoring and ranking, part-of-day and daily series. |
| `ui/stats/StatsState.kt` (create) | `StatsPeriod`, `StatsRow`, `StatsUiState`, `StatsDetail`, `StatsCardState`, `SourceRank`, `StatsStateBuilder`. |
| `ui/stats/StatsViewModel.kt` (create) | Selection in `SavedStateHandle`, state flow. |
| `ui/stats/StatsScreen.kt` (create) | `StatsScreen`, `StatsContent`, rows, info dialog, detail sheet, chart. |
| `ui/stats/StatsCard.kt` (create) | The Vergleich card. |
| `ui/compare/CompareViewModel.kt`, `CompareScreen.kt`, `SourceDetailSheet.kt` (modify) | Card, rank line, `compare_source` watch, `onOpenStats`. |
| `ui/navigation/AppNavigation.kt` (modify) | `StatsRoute`. |
| `res/values*/strings.xml` (modify) | New keys. |
| Tests | `HistoryMigrationTest`, `StationReferenceRainWindTest`, `VerificationHistoryTest`, `WeatherRepositoryTest` (additions), `ForecastScoresTest`, `StatsStateBuilderTest`, `StatsScreenshotTest`, androidTest `StatsContentTest`. |

All paths below are relative to `app/src/main/kotlin/it/apexweather/` or `app/src/test/kotlin/it/apexweather/` unless written in full.

---

### Task 1: History database version 2

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/data/local/AppDatabase.kt` (`StationHistoryEntity`)
- Modify: `app/src/main/kotlin/it/apexweather/data/local/HistoryDatabase.kt`
- Modify: `app/build.gradle.kts` (`ksp { }` block)
- Create: `app/src/test/kotlin/it/apexweather/data/local/HistoryMigrationTest.kt`
- Generated and committed: `app/schemas/it.apexweather.data.local.HistoryDatabase/2.json`

**Interfaces:**
- Produces:
  - `StationHistoryEntity(place, hourEpoch, observedC, modelsJson, observedWindKmh: Double? = null, observedPrecipTodayMm: Double? = null, modelsRainJson: String? = null, modelsWindJson: String? = null)`
  - `HistoryDatabase.MIGRATION_1_2`

- [ ] **Step 1: Write the failing test**

```kotlin
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
```

- [ ] **Step 2: Run the test and see it fail**

Run: `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c "./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.local.HistoryMigrationTest' -q"`
Expected: FAIL, compilation error `Unresolved reference 'MIGRATION_1_2'`.

- [ ] **Step 3: Implement**

In `AppDatabase.kt`, replace `StationHistoryEntity` with:

```kotlin
@Entity(tableName = "station_history", primaryKeys = ["place", "hourEpoch"])
data class StationHistoryEntity(
    val place: String,
    val hourEpoch: Long,
    /** Null while the hour is still in the future and only forecasts have been written down. */
    val observedC: Double?,
    /** Lead bucket name → source name → temperature at the station, as JSON. */
    val modelsJson: String,
    /** The station's mean wind speed at the reading, km/h. Null in rows written before version 2. */
    val observedWindKmh: Double? = null,
    /**
     * The station's rain since local midnight at the reading, mm — SIAG's `n`, stored as reported.
     * The rain of one hour is derived from two consecutive rows; see `VerificationHistory`.
     */
    val observedPrecipTodayMm: Double? = null,
    /** Lead bucket name → source name → rain in that hour, mm, as JSON. */
    val modelsRainJson: String? = null,
    /** Lead bucket name → source name → wind speed, km/h, as JSON. */
    val modelsWindJson: String? = null,
)
```

In `HistoryDatabase.kt`, add these imports:

```kotlin
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
```

Then change the annotation and companion:

```kotlin
@Database(entities = [StationHistoryEntity::class], version = 2, exportSchema = true)
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

        fun build(context: Context): HistoryDatabase =
            Room.databaseBuilder(context, HistoryDatabase::class.java, "apexweather-history.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        fun inMemory(context: Context): HistoryDatabase =
            Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
```

In `app/build.gradle.kts`, change the `ksp` block to:

```kotlin
ksp {
    arg("room.generateKotlin", "true")
    // HistoryDatabase exports its schema so its migrations can be checked against the real shape.
    // AppDatabase is a cache and keeps exportSchema = false.
    arg("room.schemaLocation", "$projectDir/schemas")
}
```

- [ ] **Step 4: Run the test and see it pass**

Run the Step 2 command. Expected: PASS. The build writes `app/schemas/it.apexweather.data.local.HistoryDatabase/2.json`. Then run `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.WeatherRepositoryTest' -q`; it is still green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/data/local/AppDatabase.kt app/src/main/kotlin/it/apexweather/data/local/HistoryDatabase.kt app/build.gradle.kts app/schemas/it.apexweather.data.local.HistoryDatabase/2.json app/src/test/kotlin/it/apexweather/data/local/HistoryMigrationTest.kt
git commit -m "feat: station history gains rain and wind columns through a real migration" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016gqqzcABukZdCYES9zAg21"
```

---

### Task 2: Rain and wind in the station reference

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/data/remote/OpenMeteoApi.kt` (`stationForecast` default `hourly`, `StationReference`, `OpenMeteoStationMapper`)
- Modify: `app/src/main/kotlin/it/apexweather/data/remote/GeoSphereApi.kt` (`GeoSphereMapper.STATION_PARAMS`)
- Modify: `app/src/main/kotlin/it/apexweather/data/WeatherRepository.kt` (the AROME station call uses `STATION_PARAMS`)
- Modify: `app/src/test/kotlin/it/apexweather/data/FakeApis.kt`
- Create: `app/src/test/kotlin/it/apexweather/data/remote/StationReferenceRainWindTest.kt`
- Fixture (exists, untracked, commit it here): `app/src/test/resources/fixtures/openmeteo_station_rain_wind.json`

**Interfaces:**
- Produces:
  - `StationReference.rainBySource: Map<String, Map<Long, Double>>`, `windBySource` (same shape)
  - `StationReference.rainAt(t: Instant): Map<Source, Double>`, `windAt(t)`
  - `OpenMeteoStationMapper.STATION_HOURLY = "temperature_2m,precipitation,wind_speed_10m"`
  - `GeoSphereMapper.STATION_PARAMS = "t2m,rr_acc,u10m,v10m"`
  - `FakeOpenMeteo.stationFixture: String` and `FakeOpenMeteo.stationHourly: String?`

- [ ] **Step 1: Write the failing test**

```kotlin
package it.apexweather.data.remote

import it.apexweather.Fixtures
import it.apexweather.domain.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Recorded 2026-09-15 for Meran with temperature, rain and wind; see the plan's adjustments. */
class StationReferenceRainWindTest {

    private fun station(name: String) = OpenMeteoStationMapper.map(
        Fixtures.json.decodeFromString(OpenMeteoStationResponse.serializer(), Fixtures.read(name)),
        Instant.parse("2026-09-15T08:00:00Z"),
    )

    @Test
    fun `the station call's rain and wind are read per model`() {
        val ref = station("openmeteo_station_rain_wind.json")
        // 2026-09-10T03:00 local is 01:00Z: ICON-D2 had 1,0 mm in that hour.
        assertEquals(1.0, ref.rainAt(Instant.parse("2026-09-10T01:00:00Z")).getValue(Source.ICON_D2), 1e-9)
        // 2026-09-11T15:00 local is 13:00Z: 23,0 °C and 1,1 km/h.
        assertEquals(23.0, ref.at(Instant.parse("2026-09-11T13:00:00Z")).getValue(Source.ICON_D2), 1e-9)
        assertEquals(1.1, ref.windAt(Instant.parse("2026-09-11T13:00:00Z")).getValue(Source.ICON_D2), 1e-9)
        assertEquals(OpenMeteoMapper.MODELS.keys.map { it.name }.toSet(), ref.rainBySource.keys)
    }

    /** The temperature-only recording still maps, with nothing invented for rain or wind. */
    @Test
    fun `a response without rain or wind leaves those series empty`() {
        val ref = station("openmeteo_station.json")
        assertTrue(ref.bySource.isNotEmpty())
        assertTrue(ref.rainBySource.isEmpty())
        assertTrue(ref.windBySource.isEmpty())
    }

    @Test
    fun `AROME's rain and wind are carried with its temperature`() {
        val arome = GeoSphereMapper.map(
            Fixtures.json.decodeFromString(GeoSphereResponse.serializer(), Fixtures.read("geosphere.json")),
            Instant.parse("2026-09-08T17:00:00Z"),
        )
        val ref = station("openmeteo_station.json").plus(Source.GEOSPHERE_AROME, arome.hourly)
        assertTrue(ref.bySource.getValue(Source.GEOSPHERE_AROME.name).isNotEmpty())
        assertEquals(arome.hourly.size, ref.rainBySource.getValue(Source.GEOSPHERE_AROME.name).size)
        assertTrue(ref.windBySource.getValue(Source.GEOSPHERE_AROME.name).isNotEmpty())
    }
}
```

- [ ] **Step 2: Run the test and see it fail**

Run: `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c "./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.remote.StationReferenceRainWindTest' -q"`
Expected: FAIL, compilation error `Unresolved reference 'rainAt'`.

- [ ] **Step 3: Implement**

In `OpenMeteoApi.kt`:

1. **`stationForecast`:** change the `hourly` default to `OpenMeteoStationMapper.STATION_HOURLY`. Extend its KDoc with one sentence:

   > Rain and wind are asked for too, for the statistics screen: 3,3 kB gzipped for the eleven models against 1,8 kB for temperature alone, measured 2026-09-15.

2. **`StationReference`:** replace its property list and `at`, and replace `plus`, with:

```kotlin
    /** Source name → epoch second → temperature. Names, because a JSON map key is a string anyway. */
    val bySource: Map<String, Map<Long, Double>> = emptyMap(),
    /** Source name → epoch second → rain in the hour ending then, mm. Empty in rows cached before rain was asked for. */
    val rainBySource: Map<String, Map<Long, Double>> = emptyMap(),
    /** Source name → epoch second → wind speed, km/h. */
    val windBySource: Map<String, Map<Long, Double>> = emptyMap(),
) {
    /** Every model's temperature for that hour, keyed by source. */
    fun at(t: Instant): Map<Source, Double> = valuesAt(bySource, t)

    /** Every model's rain for that hour, keyed by source. */
    fun rainAt(t: Instant): Map<Source, Double> = valuesAt(rainBySource, t)

    /** Every model's wind speed for that hour, keyed by source. */
    fun windAt(t: Instant): Map<Source, Double> = valuesAt(windBySource, t)

    private fun valuesAt(series: Map<String, Map<Long, Double>>, t: Instant): Map<Source, Double> {
        val second = t.truncatedTo(java.time.temporal.ChronoUnit.HOURS).epochSecond
        return series.mapNotNull { (name, values) ->
            val source = runCatching { Source.valueOf(name) }.getOrNull() ?: return@mapNotNull null
            values[second]?.let { source to it }
        }.toMap()
    }
```

   Keep `tempAt`, `interpolatedAt` and `interpolatedTempAt` unchanged. Replace `plus` with:

```kotlin
    fun plus(source: Source, hourly: List<HourlyPoint>): StationReference {
        fun key(p: HourlyPoint) = p.time.truncatedTo(java.time.temporal.ChronoUnit.HOURS).epochSecond
        val wind = hourly.mapNotNull { p -> p.windKmh?.let { key(p) to it } }.toMap()
        return copy(
            bySource = bySource + (source.name to hourly.associate { key(it) to it.tempC }),
            rainBySource = rainBySource + (source.name to hourly.associate { key(it) to it.precipMm }),
            windBySource = if (wind.isEmpty()) windBySource else windBySource + (source.name to wind),
        )
    }
```

3. **`OpenMeteoStationMapper`:** replace it with:

```kotlin
object OpenMeteoStationMapper {
    /** What the station call asks for: the temperature for the hill, rain and wind for the statistics. */
    const val STATION_HOURLY = "temperature_2m,precipitation,wind_speed_10m"

    fun map(resp: OpenMeteoStationResponse, fetchedAt: Instant): StationReference {
        val times = resp.hourly.strings("time").map { parseLocal(it!!, SouthTyrol.ZONE) }
        fun series(variable: String): Map<String, Map<Long, Double>> = OpenMeteoMapper.MODELS.mapNotNull { (source, key) ->
            val name = "${variable}_$key"
            // A response recorded before a variable was asked for simply has no such key.
            if (!resp.hourly.containsKey(name)) return@mapNotNull null
            val values = resp.hourly.doubles(name)
            // A model that does not reach these hours contributes nothing rather than a zero.
            val byHour = times.indices.mapNotNull { i -> values.getOrNull(i)?.let { times[i].epochSecond to it } }.toMap()
            if (byHour.isEmpty()) null else source.name to byHour
        }.toMap()
        return StationReference(
            fetchedAt = fetchedAt,
            elevationM = resp.elevation,
            bySource = series("temperature_2m"),
            rainBySource = series("precipitation"),
            windBySource = series("wind_speed_10m"),
        )
    }
}
```

In `GeoSphereApi.kt`, inside `object GeoSphereMapper`, directly after `const val PARAMS = …`, add:

```kotlin
    /** The station call: temperature, rain and wind — the three quantities the statistics score. */
    const val STATION_PARAMS = "t2m,rr_acc,u10m,v10m"
```

In `WeatherRepository.kt`, in the `sr` block:
- Change `geoSphere.forecast("${station.lat},${station.lon}", parameters = "t2m")` to `geoSphere.forecast("${station.lat},${station.lon}", parameters = GeoSphereMapper.STATION_PARAMS)`.
- Replace the comment line `` // `t2m` alone: this series exists to be compared with a temperature. `` with `` // Temperature for the bias correction, rain and wind for the statistics screen. ``

In `FakeApis.kt`:

- **`FakeOpenMeteo`:** add these fields:

```kotlin
    /** The recording the station call answers with; tests about rain and wind choose the newer one. */
    var stationFixture: String = "openmeteo_station.json"

    /** The `hourly` the station call last asked for. */
    var stationHourly: String? = null
```

  In its `stationForecast`, set `stationHourly = hourly` next to `stationAt = …`, and read `Fixtures.read(stationFixture)` instead of the fixed name.
- **`FakeGeoSphere`:** change `askedFor` to `asked.lastOrNull { it.second != GeoSphereMapper.STATION_PARAMS }?.first` and `askedForStation` to `asked.lastOrNull { it.second == GeoSphereMapper.STATION_PARAMS }?.first`. Update the two KDoc lines that say "the temperature and nothing else" to "temperature, rain and wind". Add `import it.apexweather.data.remote.GeoSphereMapper`.

- [ ] **Step 4: Run the tests and see them pass**

Run: `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c "./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.remote.StationReferenceRainWindTest' --tests 'it.apexweather.data.WeatherRepositoryTest' --tests 'it.apexweather.domain.*' -q"`
Expected: PASS. The existing station, downscale and bias tests are unchanged and green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/data/remote/OpenMeteoApi.kt app/src/main/kotlin/it/apexweather/data/remote/GeoSphereApi.kt app/src/main/kotlin/it/apexweather/data/WeatherRepository.kt app/src/test/kotlin/it/apexweather/data/FakeApis.kt app/src/test/kotlin/it/apexweather/data/remote/StationReferenceRainWindTest.kt app/src/test/resources/fixtures/openmeteo_station_rain_wind.json
git commit -m "feat: the station forecasts carry rain and wind as well as temperature" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016gqqzcABukZdCYES9zAg21"
```

---

### Task 3: Verification history (pure)

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/domain/VerificationHistory.kt`
- Test: `app/src/test/kotlin/it/apexweather/domain/VerificationHistoryTest.kt`

**Interfaces:**
- Consumes: `LeadBucket`, `Source`.
- Produces:
  - `data class Predicted(val tempC: Double?, val rainMm: Double?, val windKmh: Double?)`
  - `data class StationHistoryRow(time, observedC, observedWindKmh, precipTodayMm, temps: Map<LeadBucket, Map<Source, Double>>, rain: Map<LeadBucket, Map<Source, Double>>, wind: Map<LeadBucket, Map<Source, Double>>)`
  - `data class VerificationHour(time, observedC, observedWindKmh, observedRainMm, predicted: Map<LeadBucket, Map<Source, Predicted>>)`
  - `VerificationHistory.KEEP: Duration` (90 days)
  - `VerificationHistory.hours(rows, zone): List<VerificationHour>`
  - `VerificationHistory.hourlyRain(row, previous, zone): Double?`

- [ ] **Step 1: Write the failing test**

```kotlin
package it.apexweather.domain

import it.apexweather.domain.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class VerificationHistoryTest {
    private val rome: ZoneId = ZoneId.of("Europe/Rome")

    private fun row(iso: String, total: Double?, temp: Double? = 15.0) = StationHistoryRow(
        time = Instant.parse(iso), observedC = temp, observedWindKmh = 7.2, precipTodayMm = total,
        temps = mapOf(LeadBucket.SIX to mapOf(Source.ICON_D2 to 14.0)),
        rain = mapOf(LeadBucket.SIX to mapOf(Source.ICON_D2 to 0.4)),
        wind = mapOf(LeadBucket.SIX to mapOf(Source.ICON_D2 to 9.0), LeadBucket.NOW to mapOf(Source.GFS to 5.0)),
    )

    @Test
    fun `an hour's rain is the difference of two daily totals`() {
        // 12:00 and 13:00 local on 2026-09-10 (CEST = UTC+2).
        val hours = VerificationHistory.hours(listOf(row("2026-09-10T10:00:00Z", 1.0), row("2026-09-10T11:00:00Z", 1.6)), rome)
        assertNull("no previous hour", hours[0].observedRainMm)
        assertEquals(0.6, hours[1].observedRainMm!!, 1e-9)
    }

    @Test
    fun `the first hour of a local day is its own total`() {
        // 00:00 local on 2026-09-11 is 22:00Z the day before.
        val hours = VerificationHistory.hours(listOf(row("2026-09-10T21:00:00Z", 5.0), row("2026-09-10T22:00:00Z", 0.2)), rome)
        assertEquals(0.2, hours[1].observedRainMm!!, 1e-9)
    }

    @Test
    fun `a missing hour or a falling total is not scored for rain`() {
        val gap = VerificationHistory.hours(listOf(row("2026-09-10T10:00:00Z", 1.0), row("2026-09-10T12:00:00Z", 1.6)), rome)
        assertNull(gap[1].observedRainMm)
        val reset = VerificationHistory.hours(listOf(row("2026-09-10T10:00:00Z", 1.0), row("2026-09-10T11:00:00Z", 0.4)), rome)
        assertNull(reset[1].observedRainMm)
    }

    /** 2026-03-29 02:00 local does not exist; 01:00Z is 03:00 CEST and still the same local day. */
    @Test
    fun `the spring clock change keeps the day together`() {
        val hours = VerificationHistory.hours(listOf(row("2026-03-29T00:00:00Z", 0.5), row("2026-03-29T01:00:00Z", 0.9)), rome)
        assertEquals(0.4, hours[1].observedRainMm!!, 1e-9)
    }

    /** 2026-10-25 02:00 local happens twice; both are the same local day. */
    @Test
    fun `the autumn clock change keeps the day together`() {
        val hours = VerificationHistory.hours(listOf(row("2026-10-25T00:00:00Z", 0.5), row("2026-10-25T01:00:00Z", 0.7)), rome)
        assertEquals(0.2, hours[1].observedRainMm!!, 1e-9)
    }

    @Test
    fun `the three quantities meet per lead and source`() {
        val hour = VerificationHistory.hours(listOf(row("2026-09-10T10:00:00Z", 0.0)), rome).single()
        assertEquals(Predicted(14.0, 0.4, 9.0), hour.predicted.getValue(LeadBucket.SIX).getValue(Source.ICON_D2))
        assertEquals(Predicted(null, null, 5.0), hour.predicted.getValue(LeadBucket.NOW).getValue(Source.GFS))
        assertEquals(7.2, hour.observedWindKmh!!, 1e-9)
    }
}
```

- [ ] **Step 2: Run the test and see it fail**

Run: `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c "./gradlew :app:testDebugUnitTest --tests 'it.apexweather.domain.VerificationHistoryTest' -q"`
Expected: FAIL, `Unresolved reference 'StationHistoryRow'`.

- [ ] **Step 3: Implement**

```kotlin
package it.apexweather.domain

import it.apexweather.domain.model.Source
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** What one model said about one hour at the station, at one lead. A quantity it did not give is null. */
data class Predicted(val tempC: Double?, val rainMm: Double?, val windKmh: Double?)

/** One `station_history` row, decoded: names turned into enums, nothing derived yet. */
data class StationHistoryRow(
    val time: Instant,
    val observedC: Double?,
    val observedWindKmh: Double?,
    /** Rain since local midnight at the reading, as the station reports it. */
    val precipTodayMm: Double?,
    val temps: Map<LeadBucket, Map<Source, Double>>,
    val rain: Map<LeadBucket, Map<Source, Double>>,
    val wind: Map<LeadBucket, Map<Source, Double>>,
)

/** One hour ready to be scored: what the station measured and what each model said, per lead. */
data class VerificationHour(
    val time: Instant,
    val observedC: Double?,
    val observedWindKmh: Double?,
    /** Rain in this hour, derived from two daily totals; null where that cannot be done honestly. */
    val observedRainMm: Double?,
    val predicted: Map<LeadBucket, Map<Source, Predicted>>,
)

object VerificationHistory {
    /**
     * How long `station_history` is kept for the statistics screen. [BiasCorrector.WINDOW] stays seven
     * days; it filters its own window and is not affected by keeping more.
     */
    val KEEP: Duration = Duration.ofDays(90)

    fun hours(rows: List<StationHistoryRow>, zone: ZoneId): List<VerificationHour> {
        val sorted = rows.sortedBy { it.time }
        val byTime = sorted.associateBy { it.time }
        return sorted.map { row ->
            VerificationHour(
                time = row.time,
                observedC = row.observedC,
                observedWindKmh = row.observedWindKmh,
                observedRainMm = hourlyRain(row, byTime[row.time.minusSeconds(3600)], zone),
                predicted = merge(row),
            )
        }
    }

    /**
     * The station publishes rain since local midnight, so one hour's rain is this reading's total
     * minus the previous hour's — on the same local day. The first hour of a day is its own total.
     * A missing previous hour, or a total that went down (a station reset), is not guessed at.
     */
    fun hourlyRain(row: StationHistoryRow, previous: StationHistoryRow?, zone: ZoneId): Double? {
        val total = row.precipTodayMm ?: return null
        val day = row.time.atZone(zone).toLocalDate()
        if (row.time.minusSeconds(3600).atZone(zone).toLocalDate() != day) return total
        val before = previous?.precipTodayMm ?: return null
        val diff = total - before
        return if (diff < 0.0) null else diff
    }

    private fun merge(row: StationHistoryRow): Map<LeadBucket, Map<Source, Predicted>> =
        (row.temps.keys + row.rain.keys + row.wind.keys).associateWith { lead ->
            val t = row.temps[lead].orEmpty()
            val r = row.rain[lead].orEmpty()
            val w = row.wind[lead].orEmpty()
            (t.keys + r.keys + w.keys).associateWith { source -> Predicted(t[source], r[source], w[source]) }
        }
}
```

- [ ] **Step 4: Run the test and see it pass** (the Step 2 command). Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/domain/VerificationHistory.kt app/src/test/kotlin/it/apexweather/domain/VerificationHistoryTest.kt
git commit -m "feat: turn station history into scorable hours, with hourly rain from the daily total" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016gqqzcABukZdCYES9zAg21"
```

---

### Task 4: Record rain and wind, keep 90 days, serve the hours

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/data/WeatherRepository.kt`
- Test: `app/src/test/kotlin/it/apexweather/data/WeatherRepositoryTest.kt` (add tests)

**Interfaces:**
- Consumes:
  - `StationReference.rainAt`, `windAt`
  - `StationObservation.windKmh`, `precipTodayMm`
  - `VerificationHistory`, `StationHistoryRow`
- Produces: `WeatherRepository.stationHistory(place: Place): Flow<List<VerificationHour>>`

- [ ] **Step 1: Write the failing tests**

Add to `WeatherRepositoryTest` (imports as needed: `it.apexweather.data.local.StationHistoryEntity`, `it.apexweather.data.remote.OpenMeteoStationMapper`, `it.apexweather.data.remote.GeoSphereMapper`, `it.apexweather.domain.VerificationHistory`, `kotlinx.serialization.builtins.MapSerializer`, `kotlinx.serialization.builtins.serializer`):

```kotlin
    private val leadModel = MapSerializer(String.serializer(), MapSerializer(String.serializer(), Double.serializer()))

    /** Both station calls ask for what the statistics need, and the fakes are what prove it. */
    @Test
    fun `the station calls ask for temperature, rain and wind`() = runTest {
        repo.refresh(DORF_TIROL, "de")
        assertEquals(OpenMeteoStationMapper.STATION_HOURLY, openMeteo.stationHourly)
        assertNotNull("AROME was not asked at the station with STATION_PARAMS", geoSphere.askedForStation)
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
```

- [ ] **Step 2: Run the tests and see them fail**

Run: `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c "./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.WeatherRepositoryTest' -q"`
Expected: FAIL. Compilation fails on `stationHistory`. Once that compiles, the rain and wind assertions fail.

- [ ] **Step 3: Implement**

In `WeatherRepository.kt`:

1. Add these imports:
   - `kotlinx.coroutines.flow.map`
   - `kotlinx.coroutines.flow.flowOn`
   - `it.apexweather.domain.StationHistoryRow`
   - `it.apexweather.domain.VerificationHistory`
   - `it.apexweather.domain.VerificationHour`

2. Replace `historyWindow()` with a parameterised version, and change the snapshot's call to `historyWindow(BiasCorrector.WINDOW)`:

```kotlin
    private fun historyWindow(length: Duration): Flow<Long> = flow {
        while (true) {
            emit(clock.instant().truncatedTo(ChronoUnit.HOURS).minus(length).epochSecond)
            delay(HISTORY_WINDOW_TICK_MS)
        }
    }.distinctUntilChanged()
```

3. Add, directly after `snapshot(...)`:

```kotlin
    /**
     * Every recorded hour at [place]'s station within [VerificationHistory.KEEP], decoded and with
     * hourly rain derived, for the statistics screen. Decoding a season of rows is off the main thread.
     */
    fun stationHistory(place: Place): Flow<List<VerificationHour>> =
        historyWindow(VerificationHistory.KEEP)
            .flatMapLatest { since -> history.history(place.istat, since) }
            .map { rows -> VerificationHistory.hours(rows.mapNotNull(::decodeRow), SouthTyrol.ZONE) }
            .flowOn(Dispatchers.Default)

    private fun decodeRow(row: StationHistoryEntity): StationHistoryRow? {
        fun leads(text: String?): Map<LeadBucket, Map<Source, Double>> =
            text?.let { decode("station history", LEAD_MODEL_TEMPS, it) }.orEmpty().mapNotNull { (leadName, models) ->
                val lead = runCatching { LeadBucket.valueOf(leadName) }.getOrNull() ?: return@mapNotNull null
                lead to models.mapNotNull { (name, value) -> runCatching { Source.valueOf(name) }.getOrNull()?.let { it to value } }.toMap()
            }.toMap()
        return StationHistoryRow(
            time = Instant.ofEpochSecond(row.hourEpoch),
            observedC = row.observedC,
            observedWindKmh = row.observedWindKmh,
            precipTodayMm = row.observedPrecipTodayMm,
            temps = leads(row.modelsJson),
            rain = leads(row.modelsRainJson),
            wind = leads(row.modelsWindJson),
        )
    }
```

4. In `recordStationHour`:
   - In the lead loop, replace the `models`/`mergeStationHour` lines with:

```kotlin
                val target = thisHour.plusSeconds(lead.hours * 3600)
                val forecasts = HourForecasts(
                    temps = reference.at(target).mapKeys { it.key.name },
                    rain = reference.rainAt(target).mapKeys { it.key.name },
                    wind = reference.windAt(target).mapKeys { it.key.name },
                )
                // A model whose run does not reach that far contributes nothing rather than a gap
                // that later reads as agreement.
                if (forecasts.temps.isNotEmpty()) mergeStationHour(place, target, lead = lead, forecasts = forecasts)
```

   - Replace the observation part with:

```kotlin
        val observation = dao.observationOnce(place.istat)?.json
            ?.let { decode("observation", StationObservation.serializer(), it) }
        if (observation?.tempC != null) {
            val hour = observation.time.truncatedTo(ChronoUnit.HOURS)
            val atHour = reference?.let {
                HourForecasts(
                    temps = it.at(hour).mapKeys { e -> e.key.name },
                    rain = it.rainAt(hour).mapKeys { e -> e.key.name },
                    wind = it.windAt(hour).mapKeys { e -> e.key.name },
                )
            }
            mergeStationHour(place, hour, reading = observation, lead = LeadBucket.NOW, forecasts = atHour)
        }
        history.prune(now.minus(VerificationHistory.KEEP).epochSecond)
```

5. Replace `mergeStationHour` with:

```kotlin
    /** What one run says about one hour at the station: source name → value, per quantity. */
    private data class HourForecasts(
        val temps: Map<String, Double>,
        val rain: Map<String, Double>,
        val wind: Map<String, Double>,
    )

    /**
     * Read, change, write one history row. A reading is written where it is given and the stored one
     * kept where it is not, and each quantity's forecasts are merged under [lead] — so the halves of
     * [recordStationHour] can arrive in either order and none overwrites another.
     */
    private suspend fun mergeStationHour(
        place: Place,
        hour: Instant,
        reading: StationObservation? = null,
        lead: LeadBucket? = null,
        forecasts: HourForecasts? = null,
    ) {
        val existing = history.at(place.istat, hour.epochSecond)
        fun stored(text: String?) = text?.let { decode("station history", LEAD_MODEL_TEMPS, it) }.orEmpty()
        fun withLead(map: Map<String, Map<String, Double>>, add: Map<String, Double>?) =
            if (lead == null || add.isNullOrEmpty()) map else map + (lead.name to add)
        val temps = withLead(stored(existing?.modelsJson), forecasts?.temps)
        val rain = withLead(stored(existing?.modelsRainJson), forecasts?.rain)
        val wind = withLead(stored(existing?.modelsWindJson), forecasts?.wind)
        history.upsert(
            StationHistoryEntity(
                place = place.istat,
                hourEpoch = hour.epochSecond,
                observedC = reading?.tempC ?: existing?.observedC,
                modelsJson = json.encodeToString(LEAD_MODEL_TEMPS, temps),
                observedWindKmh = reading?.windKmh ?: existing?.observedWindKmh,
                observedPrecipTodayMm = reading?.precipTodayMm ?: existing?.observedPrecipTodayMm,
                modelsRainJson = rain.takeIf { it.isNotEmpty() }?.let { json.encodeToString(LEAD_MODEL_TEMPS, it) },
                modelsWindJson = wind.takeIf { it.isNotEmpty() }?.let { json.encodeToString(LEAD_MODEL_TEMPS, it) },
            ),
        )
    }
```

6. Also update:
   - `recordStationHour`'s KDoc: the history now carries rain and wind too.
   - The `sidecars` comment in `snapshot`: the snapshot keeps reading only `BiasCorrector.WINDOW`, while `stationHistory` reads `VerificationHistory.KEEP`.

- [ ] **Step 4: Run the tests and see them pass**

Run: `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c "./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.*' --tests 'it.apexweather.domain.*' -q"`
Expected: PASS. Every existing history test is green, and so are the new five.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/data/WeatherRepository.kt app/src/test/kotlin/it/apexweather/data/WeatherRepositoryTest.kt
git commit -m "feat: station history records rain and wind, is kept 90 days, and is served to the statistics" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016gqqzcABukZdCYES9zAg21"
```

---

### Task 5: Forecast scores (pure)

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/domain/ForecastScores.kt`
- Test: `app/src/test/kotlin/it/apexweather/domain/ForecastScoresTest.kt`

**Interfaces:**
- Consumes: `VerificationHour`, `Predicted`, `LeadBucket`, `DayPart`, `ConsensusBlender.weightedMedian`/`weightedMean`, `Source.checkableAtStation`.
- Produces:
  - `enum class Quantity { TEMPERATURE, RAIN, WIND }`
  - `sealed interface Contender { data class Model(val source: Source); data object Consensus; data object SameAsYesterday }`
  - `data class Score(val hours: Int, val main: Double?, val hitRate: Double?, val lean: Double?, val wetHours: Int = 0, val excluded: Int = 0)`
    - For temperature and wind: `main` is mean absolute error, `hitRate` is the share within tolerance, `lean` is mean signed error.
    - For rain: `main` is "Regen richtig" (the critical success index), `hitRate` is the detection rate, `lean` is the false-alarm ratio.
  - `data class RankedRow(val contender: Contender, val score: Score, val rank: Int?)`
  - `data class Ranking(val quantity: Quantity, val lead: LeadBucket, val ranked: List<RankedRow>, val references: List<RankedRow>, val unranked: List<RankedRow>, val observedHours: Int, val firstHour: Instant?, val excludedHours: Int)`
  - `ForecastScores.rank(hours, quantity, lead, since): Ranking`
  - `ForecastScores.byPart(hours, quantity, lead, since, source, zone): Map<DayPart, Score>`
  - `ForecastScores.dailyError(hours, quantity, lead, since, source, zone): List<Pair<LocalDate, Double>>`
  - `ForecastScores.compare(quantity): Comparator<RankedRow>`
  - Constants: `MIN_HOURS = 24`, `MIN_WET_HOURS = 5`, `WET_MM = 0.1`, `TEMP_HIT_K = 2.0`, `WIND_HIT_KMH = 5.0`, `TEMP_FAULT_K = 15.0`, `WIND_FAULT_KMH = 60.0`

- [ ] **Step 1: Write the failing test**

```kotlin
package it.apexweather.domain

import it.apexweather.domain.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ForecastScoresTest {
    private val t0: Instant = Instant.parse("2026-09-01T00:00:00Z")
    private val since: Instant = t0.minusSeconds(3600)
    private val lead = LeadBucket.SIX

    /** [n] hours; [obs] gives the observation, [models] each model's forecast, for hour i. */
    private fun hours(
        n: Int,
        obs: (Int) -> Triple<Double?, Double?, Double?>,
        models: (Int) -> Map<Source, Predicted>,
    ) = (0 until n).map { i ->
        val (t, rain, wind) = obs(i)
        VerificationHour(t0.plusSeconds(i * 3600L), t, wind, rain, mapOf(lead to models(i)))
    }

    private fun model(r: Ranking, s: Source) = (r.ranked + r.unranked).single { it.contender == Contender.Model(s) }

    @Test
    fun `temperature is ranked by average miss, with hits and lean`() {
        // ICON-D2 is always +1 K; GFS alternates +3 and -3 K, so the same lean of zero but a worse miss.
        val h = hours(24, { Triple(10.0, 0.0, 5.0) }) { i ->
            mapOf(
                Source.ICON_D2 to Predicted(11.0, 0.0, 5.0),
                Source.GFS to Predicted(if (i % 2 == 0) 13.0 else 7.0, 0.0, 5.0),
            )
        }
        val r = ForecastScores.rank(h, Quantity.TEMPERATURE, lead, since)
        assertEquals(listOf(Contender.Model(Source.ICON_D2), Contender.Model(Source.GFS)), r.ranked.map { it.contender })
        val d2 = model(r, Source.ICON_D2).score
        assertEquals(1.0, d2.main!!, 1e-9)
        assertEquals(1.0, d2.hitRate!!, 1e-9)
        assertEquals(1.0, d2.lean!!, 1e-9)
        val gfs = model(r, Source.GFS).score
        assertEquals(3.0, gfs.main!!, 1e-9)
        assertEquals(0.0, gfs.hitRate!!, 1e-9)
        assertEquals(0.0, gfs.lean!!, 1e-9)
        assertEquals(1, model(r, Source.ICON_D2).rank)
    }

    @Test
    fun `fewer than 24 hours is listed, not ranked`() {
        val h = hours(23, { Triple(10.0, 0.0, 5.0) }) { mapOf(Source.ICON_D2 to Predicted(10.0, 0.0, 5.0)) }
        val r = ForecastScores.rank(h, Quantity.TEMPERATURE, lead, since)
        assertTrue(r.ranked.isEmpty())
        assertEquals(23, model(r, Source.ICON_D2).score.hours)
        assertNull(model(r, Source.ICON_D2).rank)
    }

    /** 83 % of hours are dry here; a model that never says rain must not win on plain accuracy. */
    @Test
    fun `a model that never forecasts rain does not win`() {
        // 30 hours, wet (1 mm) in hours 0..5.
        val h = hours(30, { i -> Triple(10.0, if (i < 6) 1.0 else 0.0, 5.0) }) { i ->
            mapOf(
                Source.ICON_D2 to Predicted(10.0, if (i < 5 || i == 10) 0.8 else 0.0, 5.0), // 5 hits, 1 miss, 1 false alarm
                Source.GFS to Predicted(10.0, 0.0, 5.0), // never wet: 0 hits, 6 misses
            )
        }
        val r = ForecastScores.rank(h, Quantity.RAIN, lead, since)
        val d2 = model(r, Source.ICON_D2).score
        assertEquals(5.0 / 7.0, d2.main!!, 1e-9)   // hits / (hits + misses + false alarms)
        assertEquals(5.0 / 6.0, d2.hitRate!!, 1e-9) // detected
        assertEquals(1.0 / 6.0, d2.lean!!, 1e-9)    // false alarms
        assertEquals(Contender.Model(Source.ICON_D2), r.ranked.first().contender)
        assertEquals(0.0, model(r, Source.GFS).score.main!!, 1e-9)
    }

    @Test
    fun `rain needs five wet hours before it is ranked`() {
        val h = hours(30, { i -> Triple(10.0, if (i < 2) 1.0 else 0.0, 5.0) }) { i ->
            mapOf(Source.ICON_D2 to Predicted(10.0, if (i < 2) 1.0 else 0.0, 5.0))
        }
        val r = ForecastScores.rank(h, Quantity.RAIN, lead, since)
        assertTrue(r.ranked.isEmpty())
        assertEquals(2, model(r, Source.ICON_D2).score.wetHours)
    }

    @Test
    fun `equal misses are ordered by hits, then list order`() {
        // Both average 1 K, but ICON_CH1 hits 2 K every hour and ECMWF misses by 0 or 2.5 alternately.
        val h = hours(24, { Triple(10.0, 0.0, 5.0) }) { i ->
            mapOf(
                Source.ECMWF to Predicted(if (i % 2 == 0) 10.0 else 12.5, 0.0, 5.0).let { if (i % 4 == 1) it.copy(tempC = 7.5) else it }.let { if (i % 4 == 3) it.copy(tempC = 12.5) else it },
                Source.ICON_CH1 to Predicted(11.0, 0.0, 5.0),
                Source.ICON_CH2 to Predicted(11.0, 0.0, 5.0),
            )
        }
        val r = ForecastScores.rank(h, Quantity.TEMPERATURE, lead, since)
        assertEquals(
            listOf(Contender.Model(Source.ICON_CH1), Contender.Model(Source.ICON_CH2), Contender.Model(Source.ECMWF)),
            r.ranked.map { it.contender },
        )
    }

    @Test
    fun `an absurd miss is excluded and counted`() {
        val h = hours(25, { Triple(10.0, 0.0, 5.0) }) { i -> mapOf(Source.ICON_D2 to Predicted(if (i == 0) 40.0 else 11.0, 0.0, 5.0)) }
        val s = model(ForecastScores.rank(h, Quantity.TEMPERATURE, lead, since), Source.ICON_D2).score
        assertEquals(24, s.hours)
        assertEquals(1, s.excluded)
        assertEquals(1.0, s.main!!, 1e-9)
    }

    @Test
    fun `the consensus is the weighted median, and same-as-yesterday reads 24 hours back`() {
        // Four ICON runs at 12 and AROME at 8: the four share one core (weight 0.5 each = 2.0 against 1.0).
        val h = hours(48, { i -> Triple(if (i < 24) 9.0 else 10.0, 0.0, 5.0) }) {
            mapOf(
                Source.ICON_CH1 to Predicted(12.0, 0.0, 5.0), Source.ICON_CH2 to Predicted(12.0, 0.0, 5.0),
                Source.ICON_2I to Predicted(12.0, 0.0, 5.0), Source.ICON_D2 to Predicted(12.0, 0.0, 5.0),
                Source.GEOSPHERE_AROME to Predicted(8.0, 0.0, 5.0),
            )
        }
        val r = ForecastScores.rank(h, Quantity.TEMPERATURE, lead, since)
        val consensus = r.references.single { it.contender == Contender.Consensus }.score
        // Weighted median 12 → error +3 for the first 24 h, +2 for the last 24 h.
        assertEquals(2.5, consensus.main!!, 1e-9)
        val yesterday = r.references.single { it.contender == Contender.SameAsYesterday }.score
        // Only the last 24 hours have a reading 24 h earlier: 9 against 10 → 1 K.
        assertEquals(24, yesterday.hours)
        assertEquals(1.0, yesterday.main!!, 1e-9)
        assertTrue(r.references.none { it.rank != null })
    }

    @Test
    fun `rain has no same-as-yesterday row`() {
        val h = hours(48, { i -> Triple(10.0, if (i % 6 == 0) 1.0 else 0.0, 5.0) }) { mapOf(Source.ICON_D2 to Predicted(10.0, 1.0, 5.0)) }
        assertTrue(ForecastScores.rank(h, Quantity.RAIN, lead, since).references.none { it.contender == Contender.SameAsYesterday })
    }

    @Test
    fun `KMOS and hours before the period are left out`() {
        val h = hours(30, { Triple(10.0, 0.0, 5.0) }) { mapOf(Source.SIAG_KMOS to Predicted(10.0, 0.0, 5.0), Source.ICON_D2 to Predicted(10.0, 0.0, 5.0)) }
        val r = ForecastScores.rank(h, Quantity.TEMPERATURE, lead, t0.plusSeconds(10 * 3600L))
        assertTrue((r.ranked + r.unranked).none { it.contender == Contender.Model(Source.SIAG_KMOS) })
        assertEquals(20, model(r, Source.ICON_D2).score.hours)
        assertEquals(20, r.observedHours)
        assertEquals(t0.plusSeconds(10 * 3600L), r.firstHour)
    }

    @Test
    fun `part of day and daily error`() {
        val rome = ZoneId.of("Europe/Rome")
        val h = hours(48, { Triple(10.0, 0.0, 5.0) }) { i -> mapOf(Source.ICON_D2 to Predicted(if (i < 24) 11.0 else 13.0, 0.0, 5.0)) }
        val parts = ForecastScores.byPart(h, Quantity.TEMPERATURE, lead, since, Source.ICON_D2, rome)
        assertEquals(DayPart.entries.toSet(), parts.keys)
        val daily = ForecastScores.dailyError(h, Quantity.TEMPERATURE, lead, since, Source.ICON_D2, rome)
        assertTrue(daily.isNotEmpty())
        assertTrue(ForecastScores.dailyError(h, Quantity.RAIN, lead, since, Source.ICON_D2, rome).isEmpty())
    }
}
```

The tie test's ECMWF series averages 1 K with fewer hits than ICON-CH1. Before relying on it, the implementer must confirm by hand in the report that the series hits within 2 K less often. If it does not, adjust that series (never the assertion) so ECMWF's mean absolute error is exactly 1.0 and its hit rate is below 1.0, and say so.

- [ ] **Step 2: Run the test and see it fail**

Run: `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c "./gradlew :app:testDebugUnitTest --tests 'it.apexweather.domain.ForecastScoresTest' -q"`
Expected: FAIL, `Unresolved reference 'ForecastScores'`.

- [ ] **Step 3: Implement**

```kotlin
package it.apexweather.domain

import it.apexweather.domain.model.Source
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

enum class Quantity { TEMPERATURE, RAIN, WIND }

/** Who a row of the table is about: a model, or one of the two things a model has to beat. */
sealed interface Contender {
    data class Model(val source: Source) : Contender
    /** The weighted median of the models at the station (weighted mean for rain). */
    data object Consensus : Contender
    /** The station's own reading 24 hours earlier. Not for rain. */
    data object SameAsYesterday : Contender
}

/**
 * One contender's record over a period.
 *
 * - Temperature, wind: [main] mean absolute error; [hitRate] share within the tolerance; [lean] mean
 *   signed error (positive = too warm / too windy).
 * - Rain: [main] critical success index, hits ÷ (hits + misses + false alarms); [hitRate] share of wet
 *   hours forecast wet; [lean] share of wet forecasts that stayed dry.
 */
data class Score(
    val hours: Int,
    val main: Double?,
    val hitRate: Double?,
    val lean: Double?,
    /** Rain: hours wet observed or forecast. */
    val wetHours: Int = 0,
    /** Hours left out as a station fault. */
    val excluded: Int = 0,
)

data class RankedRow(val contender: Contender, val score: Score, val rank: Int?)

data class Ranking(
    val quantity: Quantity,
    val lead: LeadBucket,
    val ranked: List<RankedRow>,
    val references: List<RankedRow>,
    val unranked: List<RankedRow>,
    /** Hours in the period with an observation of [quantity]. */
    val observedHours: Int,
    val firstHour: Instant?,
    val excludedHours: Int,
)

object ForecastScores {
    const val MIN_HOURS = 24
    const val MIN_WET_HOURS = 5
    const val WET_MM = 0.1
    const val TEMP_HIT_K = 2.0
    const val WIND_HIT_KMH = 5.0
    const val TEMP_FAULT_K = 15.0
    const val WIND_FAULT_KMH = 60.0

    private val MODELS: List<Source> = Source.entries.filter { it.checkableAtStation }

    fun rank(hours: List<VerificationHour>, quantity: Quantity, lead: LeadBucket, since: Instant): Ranking {
        val period = hours.filter { !it.time.isBefore(since) && observed(it, quantity) != null }
        val modelRows = MODELS.map { source ->
            RankedRow(Contender.Model(source), score(pairs(period, quantity) { predicted(it, lead, source, quantity) }, quantity), rank = null)
        }.filter { it.score.hours > 0 || it.score.excluded > 0 }
        val (rankable, notYet) = modelRows.partition { rankable(it.score, quantity) }
        val ranked = rankable.sortedWith(compare(quantity)).mapIndexed { i, row -> row.copy(rank = i + 1) }
        val references = buildList {
            score(pairs(period, quantity) { consensus(it, lead, quantity) }, quantity).takeIf { it.hours > 0 }
                ?.let { add(RankedRow(Contender.Consensus, it, null)) }
            if (quantity != Quantity.RAIN) {
                val byTime = hours.associateBy { it.time }
                score(pairs(period, quantity) { byTime[it.time.minusSeconds(24 * 3600)]?.let { y -> observed(y, quantity) } }, quantity)
                    .takeIf { it.hours > 0 }?.let { add(RankedRow(Contender.SameAsYesterday, it, null)) }
            }
        }
        return Ranking(
            quantity = quantity, lead = lead, ranked = ranked, references = references,
            unranked = notYet.sortedBy { (it.contender as Contender.Model).source.ordinal },
            observedHours = period.size, firstHour = period.minOfOrNull { it.time },
            excludedHours = modelRows.sumOf { it.score.excluded },
        )
    }

    fun byPart(hours: List<VerificationHour>, quantity: Quantity, lead: LeadBucket, since: Instant, source: Source, zone: ZoneId): Map<DayPart, Score> {
        val period = hours.filter { !it.time.isBefore(since) }
        return DayPart.entries.associateWith { part ->
            score(pairs(period.filter { DayPart.of(it.time, zone) == part }, quantity) { predicted(it, lead, source, quantity) }, quantity)
        }
    }

    /** Mean absolute error per local day, oldest first. Empty for rain. */
    fun dailyError(hours: List<VerificationHour>, quantity: Quantity, lead: LeadBucket, since: Instant, source: Source, zone: ZoneId): List<Pair<LocalDate, Double>> {
        if (quantity == Quantity.RAIN) return emptyList()
        return hours.filter { !it.time.isBefore(since) }
            .groupBy { it.time.atZone(zone).toLocalDate() }
            .toSortedMap()
            .mapNotNull { (day, dayHours) ->
                score(pairs(dayHours, quantity) { predicted(it, lead, source, quantity) }, quantity).main?.let { day to it }
            }
    }

    /** Best first. Temperature and wind: smaller miss; rain: larger CSI. Then more hits, then list order. */
    fun compare(quantity: Quantity): Comparator<RankedRow> {
        val byMain = if (quantity == Quantity.RAIN) compareByDescending<RankedRow> { it.score.main ?: -1.0 }
        else compareBy<RankedRow> { it.score.main ?: Double.MAX_VALUE }
        return byMain
            .thenByDescending { it.score.hitRate ?: -1.0 }
            .thenBy { (it.contender as? Contender.Model)?.source?.ordinal ?: -1 }
    }

    private fun rankable(score: Score, quantity: Quantity): Boolean =
        score.main != null && score.hours >= MIN_HOURS && (quantity != Quantity.RAIN || score.wetHours >= MIN_WET_HOURS)

    private fun observed(hour: VerificationHour, quantity: Quantity): Double? = when (quantity) {
        Quantity.TEMPERATURE -> hour.observedC
        Quantity.RAIN -> hour.observedRainMm
        Quantity.WIND -> hour.observedWindKmh
    }

    private fun predicted(hour: VerificationHour, lead: LeadBucket, source: Source, quantity: Quantity): Double? {
        val p = hour.predicted[lead]?.get(source) ?: return null
        return when (quantity) {
            Quantity.TEMPERATURE -> p.tempC
            Quantity.RAIN -> p.rainMm
            Quantity.WIND -> p.windKmh
        }
    }

    private fun consensus(hour: VerificationHour, lead: LeadBucket, quantity: Quantity): Double? {
        val values = MODELS.mapNotNull { s -> predicted(hour, lead, s, quantity)?.let { s to it } }.toMap()
        if (values.isEmpty()) return null
        return if (quantity == Quantity.RAIN) ConsensusBlender.weightedMean(values) else ConsensusBlender.weightedMedian(values)
    }

    /** (forecast, observed) for every hour where both exist. */
    private fun pairs(hours: List<VerificationHour>, quantity: Quantity, forecast: (VerificationHour) -> Double?): List<Pair<Double, Double>> =
        hours.mapNotNull { h -> val o = observed(h, quantity) ?: return@mapNotNull null; forecast(h)?.let { it to o } }

    private fun score(pairs: List<Pair<Double, Double>>, quantity: Quantity): Score = when (quantity) {
        Quantity.TEMPERATURE -> continuous(pairs, TEMP_HIT_K, TEMP_FAULT_K)
        Quantity.WIND -> continuous(pairs, WIND_HIT_KMH, WIND_FAULT_KMH)
        Quantity.RAIN -> rain(pairs)
    }

    private fun continuous(pairs: List<Pair<Double, Double>>, hit: Double, fault: Double): Score {
        val errors = pairs.map { (p, o) -> p - o }
        val kept = errors.filter { abs(it) <= fault }
        val excluded = errors.size - kept.size
        if (kept.isEmpty()) return Score(0, null, null, null, excluded = excluded)
        return Score(
            hours = kept.size,
            main = kept.sumOf { abs(it) } / kept.size,
            hitRate = kept.count { abs(it) <= hit }.toDouble() / kept.size,
            lean = kept.sum() / kept.size,
            excluded = excluded,
        )
    }

    private fun rain(pairs: List<Pair<Double, Double>>): Score {
        var hits = 0
        var misses = 0
        var falseAlarms = 0
        pairs.forEach { (p, o) ->
            val forecastWet = p >= WET_MM
            val observedWet = o >= WET_MM
            when {
                forecastWet && observedWet -> hits++
                !forecastWet && observedWet -> misses++
                forecastWet && !observedWet -> falseAlarms++
            }
        }
        val wet = hits + misses + falseAlarms
        return Score(
            hours = pairs.size,
            main = if (wet == 0) null else hits.toDouble() / wet,
            hitRate = if (hits + misses == 0) null else hits.toDouble() / (hits + misses),
            lean = if (hits + falseAlarms == 0) null else falseAlarms.toDouble() / (hits + falseAlarms),
            wetHours = wet,
        )
    }
}
```

- [ ] **Step 4: Run the test and see it pass** (the Step 2 command). Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/domain/ForecastScores.kt app/src/test/kotlin/it/apexweather/domain/ForecastScoresTest.kt
git commit -m "feat: score and rank the models for temperature, rain and wind against consensus and yesterday" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016gqqzcABukZdCYES9zAg21"
```

---

### Task 6: Screen state, view models and navigation state

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/ui/stats/StatsState.kt`
- Create: `app/src/main/kotlin/it/apexweather/ui/stats/StatsViewModel.kt`
- Modify: `app/src/main/kotlin/it/apexweather/ui/compare/CompareViewModel.kt`
- Modify: `app/src/test/kotlin/it/apexweather/ui/compare/CompareViewModelTest.kt`
- Test: `app/src/test/kotlin/it/apexweather/ui/stats/StatsStateBuilderTest.kt`

**Interfaces:**
- Consumes: `ForecastScores`, `Ranking`, `WeatherRepository.stationHistory`, `WeatherStateHolder.weather` (`place`, `now`, `settings`).
- Produces:
  - `enum class StatsPeriod(val days: Long) { WEEK(7), MONTH(30), SEASON(90) }`
  - `data class StatsRow(val contender: Contender, val rank: Int?, val score: Score)`
  - `data class StatsUiState(loading, hasStation, stationName, quantity, period, lead, rows, unranked, observedHours, firstHour, excludedHours, now, detail)`
  - `data class StatsDetail(source, quantity, score, byPart, daily)`
  - `data class StatsCardState(hasStation, top: List<StatsRow>, enoughData)`
  - `data class SourceRank(rank: Int?, of: Int, score: Score?)`
  - `object StatsStateBuilder` with `build(hours, place, quantity, period, lead, now, detailSource): StatsUiState`, `card(hours, place, now): StatsCardState` and `sourceRanks(hours, now): Map<Source, SourceRank>`
  - `StatsViewModel`: `state`, `setQuantity`, `setPeriod`, `setLead`, `openDetail`, `closeDetail`
  - `CompareViewModel`: `stats: StateFlow<CompareStats>`, where `data class CompareStats(val card: StatsCardState = StatsCardState(), val ranks: Map<Source, SourceRank> = emptyMap())`; its source sheet also opens when `compare_source` is set from outside

- [ ] **Step 1: Write the failing test**

```kotlin
package it.apexweather.ui.stats

import it.apexweather.domain.Contender
import it.apexweather.domain.DORF_TIROL
import it.apexweather.domain.LeadBucket
import it.apexweather.domain.Predicted
import it.apexweather.domain.Quantity
import it.apexweather.domain.STERZING
import it.apexweather.domain.VerificationHour
import it.apexweather.domain.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class StatsStateBuilderTest {
    private val now: Instant = Instant.parse("2026-09-15T12:00:00Z")

    /** 60 hours: ICON-D2 misses by 1 K, AROME by 2 K, GFS only has 10 hours. */
    private val hours = (1..60).map { i ->
        val t = now.minusSeconds(i * 3600L)
        VerificationHour(
            t, 15.0, 8.0, 0.0,
            mapOf(LeadBucket.SIX to buildMap {
                put(Source.ICON_D2, Predicted(16.0, 0.0, 8.0))
                put(Source.GEOSPHERE_AROME, Predicted(13.0, 0.0, 8.0))
                if (i <= 10) put(Source.GFS, Predicted(15.0, 0.0, 8.0))
            }),
        )
    }

    @Test
    fun `rows interleave references by score and unranked go last`() {
        val s = StatsStateBuilder.build(hours, DORF_TIROL, Quantity.TEMPERATURE, StatsPeriod.MONTH, LeadBucket.SIX, now, null)
        assertTrue(s.hasStation)
        assertEquals("Meran", s.stationName)
        val order = s.rows.map { it.contender }
        // The consensus of 16 and 13 is 14,5, half a kelvin off, so it sits above ICON-D2 unranked.
        assertEquals(Contender.Consensus, order.first())
        assertNull(s.rows.first().rank)
        val firstRanked = s.rows.first { it.rank != null }
        assertEquals(Contender.Model(Source.ICON_D2), firstRanked.contender)
        assertEquals(1, firstRanked.rank)
        assertEquals(listOf(Contender.Model(Source.GFS)), s.unranked.map { it.contender })
        assertEquals(60, s.observedHours)
    }

    @Test
    fun `a place without a station says so and has no rows`() {
        val s = StatsStateBuilder.build(emptyList(), STERZING, Quantity.TEMPERATURE, StatsPeriod.MONTH, LeadBucket.SIX, now, null)
        assertFalse(s.hasStation)
        assertTrue(s.rows.isEmpty())
    }

    @Test
    fun `the card shows the top three models and the source ranks follow the same ranking`() {
        val card = StatsStateBuilder.card(hours, DORF_TIROL, now)
        assertTrue(card.enoughData)
        assertEquals(listOf(Contender.Model(Source.ICON_D2), Contender.Model(Source.GEOSPHERE_AROME)), card.top.map { it.contender })
        val ranks = StatsStateBuilder.sourceRanks(hours, now)
        assertEquals(1, ranks.getValue(Source.ICON_D2).rank)
        assertEquals(2, ranks.getValue(Source.ICON_D2).of)
        assertNull(ranks.getValue(Source.GFS).rank)
    }

    @Test
    fun `an empty history is not enough data`() {
        assertFalse(StatsStateBuilder.card(emptyList(), DORF_TIROL, now).enoughData)
    }

    @Test
    fun `the detail carries parts of the day and daily error`() {
        val s = StatsStateBuilder.build(hours, DORF_TIROL, Quantity.TEMPERATURE, StatsPeriod.MONTH, LeadBucket.SIX, now, Source.ICON_D2)
        val d = s.detail!!
        assertEquals(Source.ICON_D2, d.source)
        assertEquals(4, d.byPart.size)
        assertTrue(d.daily.isNotEmpty())
    }
}
```

- [ ] **Step 2: Run the test and see it fail**

Run: `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c "./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.stats.StatsStateBuilderTest' -q"`
Expected: FAIL, `Unresolved reference 'StatsStateBuilder'`.

- [ ] **Step 3: Implement `StatsState.kt`**

```kotlin
package it.apexweather.ui.stats

import it.apexweather.domain.Contender
import it.apexweather.domain.DayPart
import it.apexweather.domain.ForecastScores
import it.apexweather.domain.LeadBucket
import it.apexweather.domain.Place
import it.apexweather.domain.Quantity
import it.apexweather.domain.Score
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.VerificationHour
import it.apexweather.domain.model.Source
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

enum class StatsPeriod(val days: Long) { WEEK(7), MONTH(30), SEASON(90) }

data class StatsRow(val contender: Contender, val rank: Int?, val score: Score)

data class StatsDetail(
    val source: Source,
    val quantity: Quantity,
    val score: Score?,
    val byPart: Map<DayPart, Score>,
    val daily: List<Pair<LocalDate, Double>>,
)

data class StatsUiState(
    val loading: Boolean = true,
    val hasStation: Boolean = true,
    val stationName: String? = null,
    val quantity: Quantity = Quantity.TEMPERATURE,
    val period: StatsPeriod = StatsPeriod.MONTH,
    val lead: LeadBucket = LeadBucket.SIX,
    /** Ranked models with the reference rows placed among them by score. */
    val rows: List<StatsRow> = emptyList(),
    val unranked: List<StatsRow> = emptyList(),
    val observedHours: Int = 0,
    val firstHour: Instant? = null,
    val excludedHours: Int = 0,
    val now: Instant = Instant.EPOCH,
    val detail: StatsDetail? = null,
)

data class StatsCardState(
    val hasStation: Boolean = true,
    val top: List<StatsRow> = emptyList(),
    val enoughData: Boolean = false,
)

/** A source's place in the card's ranking (temperature, 30 days, 6 h); [rank] null while not ranked. */
data class SourceRank(val rank: Int?, val of: Int, val score: Score?)

object StatsStateBuilder {

    fun build(
        hours: List<VerificationHour>,
        place: Place?,
        quantity: Quantity,
        period: StatsPeriod,
        lead: LeadBucket,
        now: Instant,
        detailSource: Source?,
    ): StatsUiState {
        val station = place?.station
        if (station == null) return StatsUiState(loading = false, hasStation = false, quantity = quantity, period = period, lead = lead, now = now)
        val since = now.minus(Duration.ofDays(period.days))
        val ranking = ForecastScores.rank(hours, quantity, lead, since)
        val rows = (ranking.ranked + ranking.references)
            .sortedWith(ForecastScores.compare(quantity))
            .map { StatsRow(it.contender, it.rank, it.score) }
        val detail = detailSource?.let { source ->
            StatsDetail(
                source = source,
                quantity = quantity,
                score = (ranking.ranked + ranking.unranked).firstOrNull { it.contender == Contender.Model(source) }?.score,
                byPart = ForecastScores.byPart(hours, quantity, lead, since, source, SouthTyrol.ZONE),
                daily = ForecastScores.dailyError(hours, quantity, lead, since, source, SouthTyrol.ZONE),
            )
        }
        return StatsUiState(
            loading = false, hasStation = true, stationName = station.name,
            quantity = quantity, period = period, lead = lead,
            rows = rows, unranked = ranking.unranked.map { StatsRow(it.contender, null, it.score) },
            observedHours = ranking.observedHours, firstHour = ranking.firstHour,
            excludedHours = ranking.excludedHours, now = now, detail = detail,
        )
    }

    fun card(hours: List<VerificationHour>, place: Place?, now: Instant): StatsCardState {
        if (place?.station == null) return StatsCardState(hasStation = false)
        val ranking = cardRanking(hours, now)
        return StatsCardState(
            hasStation = true,
            top = ranking.ranked.take(3).map { StatsRow(it.contender, it.rank, it.score) },
            enoughData = ranking.ranked.isNotEmpty(),
        )
    }

    fun sourceRanks(hours: List<VerificationHour>, now: Instant): Map<Source, SourceRank> {
        val ranking = cardRanking(hours, now)
        val of = ranking.ranked.size
        return (ranking.ranked + ranking.unranked).associate { row ->
            (row.contender as Contender.Model).source to SourceRank(row.rank, of, row.score)
        }
    }

    private fun cardRanking(hours: List<VerificationHour>, now: Instant) =
        ForecastScores.rank(hours, Quantity.TEMPERATURE, LeadBucket.SIX, now.minus(Duration.ofDays(StatsPeriod.MONTH.days)))
}
```

- [ ] **Step 4: Implement `StatsViewModel.kt`**

```kotlin
package it.apexweather.ui.stats

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.apexweather.data.WeatherRepository
import it.apexweather.domain.LeadBucket
import it.apexweather.domain.Quantity
import it.apexweather.domain.model.Source
import it.apexweather.ui.WeatherStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.temporal.ChronoUnit
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    holder: WeatherStateHolder,
    repository: WeatherRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private data class Selection(val quantity: Quantity, val period: StatsPeriod, val lead: LeadBucket, val detail: Source?)

    private val place = holder.weather.map { it.place }.distinctUntilChanged { a, b -> a?.istat == b?.istat }
    private val now = holder.weather.map { it.now.truncatedTo(ChronoUnit.HOURS) }.distinctUntilChanged()
    private val hours = place.flatMapLatest { p -> if (p?.station == null) flowOf(emptyList()) else repository.stationHistory(p) }

    private val selection = combine(
        savedState.getStateFlow(QUANTITY_KEY, Quantity.TEMPERATURE.name),
        savedState.getStateFlow(PERIOD_KEY, StatsPeriod.MONTH.name),
        savedState.getStateFlow(LEAD_KEY, LeadBucket.SIX.name),
        savedState.getStateFlow<String?>(DETAIL_KEY, null),
    ) { q, p, l, d ->
        Selection(
            quantity = Quantity.entries.firstOrNull { it.name == q } ?: Quantity.TEMPERATURE,
            period = StatsPeriod.entries.firstOrNull { it.name == p } ?: StatsPeriod.MONTH,
            lead = LeadBucket.entries.firstOrNull { it.name == l } ?: LeadBucket.SIX,
            detail = d?.let { name -> Source.entries.firstOrNull { it.name == name } },
        )
    }

    val state: StateFlow<StatsUiState> = combine(hours, place, now, selection) { h, p, n, s ->
        StatsStateBuilder.build(h, p, s.quantity, s.period, s.lead, n, s.detail)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun setQuantity(q: Quantity) { savedState[QUANTITY_KEY] = q.name }
    fun setPeriod(p: StatsPeriod) { savedState[PERIOD_KEY] = p.name }
    fun setLead(l: LeadBucket) { savedState[LEAD_KEY] = l.name }
    fun openDetail(source: Source) { savedState[DETAIL_KEY] = source.name }
    fun closeDetail() { savedState[DETAIL_KEY] = null }

    private companion object {
        const val QUANTITY_KEY = "stats_quantity"
        const val PERIOD_KEY = "stats_period"
        const val LEAD_KEY = "stats_lead"
        const val DETAIL_KEY = "stats_detail"
    }
}
```

- [ ] **Step 5: Wire `CompareViewModel`**

1. **Constructor:** add `private val repository: WeatherRepository` as the last parameter, with its import.
2. **Stats flow:** add

```kotlin
    /** The Treffsicherheit card and each source's rank, from the station's recorded hours. */
    val stats: StateFlow<CompareStats> = holder.weather
        .map { it.place }
        .distinctUntilChanged { a, b -> a?.istat == b?.istat }
        .flatMapLatest { p ->
            if (p?.station == null) flowOf(CompareStats(StatsCardState(hasStation = false)))
            else combine(repository.stationHistory(p), holder.weather.map { it.now.truncatedTo(ChronoUnit.HOURS) }.distinctUntilChanged()) { hours, now ->
                CompareStats(StatsStateBuilder.card(hours, p, now), StatsStateBuilder.sourceRanks(hours, now))
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompareStats())
```

   and, at file level, `data class CompareStats(val card: StatsCardState = StatsCardState(), val ranks: Map<Source, SourceRank> = emptyMap())`. Add the imports: `flatMapLatest`, `flowOf`, `it.apexweather.ui.stats.*`, `it.apexweather.data.WeatherRepository`.
3. **Opening the sheet from outside:** replace the `init` block with

```kotlin
    init {
        // The sheet opens from outside too: the statistics screen sets this key on the way back.
        // A name already being fetched for is not fetched again.
        viewModelScope.launch {
            openSourceName.collect { name -> sourceNamed(name)?.takeIf { it != metaFor }?.let(::fetchMeta) }
        }
    }
```

   add `private var metaFor: Source? = null`, set `metaFor = source` at the start of `fetchMeta`, and set `metaFor = null` in `closeSource`. `openSource` keeps setting the saved state **and** calling `fetchMeta` directly, so its `Loading` stays synchronous for `CompareViewModelTest`.
4. **Test:** in `CompareViewModelTest`, pass the repository it already builds for `WeatherStateHolder` as the new last constructor argument. Add one test: setting `SavedStateHandle["compare_source"] = "ICON_D2"` after construction fetches, giving `Loading(ICON_D2)` then `Loaded(ICON_D2, …)`.

- [ ] **Step 6: Run the tests**

Run: `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c "./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.stats.*' --tests 'it.apexweather.ui.compare.*' -q"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/stats/StatsState.kt app/src/main/kotlin/it/apexweather/ui/stats/StatsViewModel.kt app/src/main/kotlin/it/apexweather/ui/compare/CompareViewModel.kt app/src/test/kotlin/it/apexweather/ui/stats/StatsStateBuilderTest.kt app/src/test/kotlin/it/apexweather/ui/compare/CompareViewModelTest.kt
git commit -m "feat: statistics screen state, its view model, and ranks for the Vergleich card" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016gqqzcABukZdCYES9zAg21"
```

---

### Task 7: The screens

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/ui/stats/StatsScreen.kt`
- Create: `app/src/main/kotlin/it/apexweather/ui/stats/StatsCard.kt`
- Modify: `app/src/main/kotlin/it/apexweather/ui/compare/CompareScreen.kt`
- Modify: `app/src/main/kotlin/it/apexweather/ui/compare/SourceDetailSheet.kt`
- Modify: `app/src/main/kotlin/it/apexweather/ui/navigation/AppNavigation.kt`
- Modify: `app/src/main/res/values/strings.xml`, `values-it/strings.xml`, `values-en/strings.xml`
- Create: `app/src/test/kotlin/it/apexweather/ui/screenshot/StatsScreenshotTest.kt`, goldens `stats_temperature.png`, `stats_rain.png`, `stats_card.png`
- Create: `app/src/androidTest/kotlin/it/apexweather/ui/stats/StatsContentTest.kt` (compile only)

**Interfaces:**
- Consumes: everything from Task 6.
- Produces:
  - `StatsScreen(onBack: () -> Unit, onOpenSource: (Source) -> Unit, viewModel: StatsViewModel = hiltViewModel())`
  - `StatsContent(state, onQuantity, onPeriod, onLead, onOpenDetail, onCloseDetail, onOpenSource, onBack)`
  - `StatsCard(state: StatsCardState, onOpen: () -> Unit)`
  - `CompareContent(…, stats: CompareStats = CompareStats(), onOpenStats: () -> Unit = {})`
  - `SourceDetailSheet(state, meta, onClose, rank: SourceRank? = null)`
  - Route: `@Serializable object StatsRoute`
  - Test tags: `stats_screen`, `stats_quantity_<NAME>`, `stats_period_<NAME>`, `stats_lead_<NAME>`, `stats_row_<SOURCE>`, `stats_row_consensus`, `stats_row_yesterday`, `stats_unranked`, `stats_info`, `stats_no_station`, `stats_empty`, `stats_detail_sheet`, `stats_detail_close`, `stats_detail_source`, `stats_card`, `stats_card_open`, `source_detail_rank`

- [ ] **Step 1: Strings**

Add every key below to all three files. Where lint's `PluralsCandidate` flags a `%d` beside a noun, convert that key to `<plurals>` with `one`/`other` (Italian also `many`, following `source_station`) and use `pluralStringResource`.

| key | de | it | en |
|---|---|---|---|
| stats_title | Treffsicherheit | Affidabilità | Accuracy |
| stats_card_title | TREFFSICHERHEIT | AFFIDABILITÀ | ACCURACY |
| stats_card_subtitle | Temperatur · 30 Tage · 6 h voraus | Temperatura · 30 giorni · 6 h prima | Temperature · 30 days · 6 h ahead |
| stats_card_all | Alle Statistiken › | Tutte le statistiche › | All statistics › |
| stats_not_enough | Noch zu wenig Daten – erste Werte nach etwa einem Tag | Ancora pochi dati – i primi valori dopo circa un giorno | Not enough data yet – first values after about a day |
| stats_no_station | Für diesen Ort gibt es keine Station in der Nähe, gegen die geprüft werden kann. | Per questo luogo non c\'è una stazione vicina con cui verificare. | There is no station near this place to check the forecasts against. |
| stats_quantity_temperature | Temperatur | Temperatura | Temperature |
| stats_quantity_rain | Regen | Pioggia | Rain |
| stats_quantity_wind | Wind | Vento | Wind |
| stats_period | %1$d T | %1$d g | %1$d d |
| stats_lead_now | jetzt | ora | now |
| stats_lead_hours | %1$d h | %1$d h | %1$d h |
| stats_basis (plurals) | one: %1$d Stunde seit %2$s · Station %3$s / other: %1$d Stunden seit %2$s · Station %3$s | one: %1$d ora dal %2$s · stazione %3$s / other: %1$d ore dal %2$s · stazione %3$s | one: %1$d hour since %2$s · station %3$s / other: %1$d hours since %2$s · station %3$s |
| stats_col_error | Ø Fehler | Ø errore | Avg. error |
| stats_col_hits | Treffer | Centrati | Hits |
| stats_col_lean | Tendenz | Tendenza | Lean |
| stats_col_rain_right | Regen richtig | Pioggia giusta | Rain right |
| stats_col_detected | erkannt | rilevata | detected |
| stats_col_false_alarm | Fehlalarm | falso allarme | false alarm |
| stats_consensus | Konsens | Consenso | Consensus |
| stats_persistence | wie gestern | come ieri | same as yesterday |
| stats_unranked | zu wenig Daten (%1$d h) | pochi dati (%1$d h) | not enough data (%1$d h) |
| stats_percent | %1$s %% | %1$s%% | %1$s%% |
| stats_info | So wird gerechnet | Come si calcola | How it is scored |
| stats_info_body | Ø Fehler ist die durchschnittliche Abweichung von der Messung. Treffer zählt Stunden innerhalb von 2 K (Wind: 5 km/h). Tendenz zeigt, ob ein Modell meist zu warm (+) oder zu kalt (−) liegt. Beim Regen zählt „Regen richtig" nur Stunden, in denen es geregnet hat oder Regen vorhergesagt war, damit ein Modell, das nie Regen meldet, nicht gewinnt. Unter 24 Stunden (Regen: 5 nasse Stunden) wird nicht gereiht. Konsens und „wie gestern" sind Vergleichswerte. SIAG KMOS fehlt, weil es nach Gemeinde abgefragt wird und nicht gegen eine Station prüfbar ist. | Ø errore è lo scostamento medio dalla misura. Centrati conta le ore entro 2 K (vento: 5 km/h). Tendenza indica se un modello è di solito troppo caldo (+) o troppo freddo (−). Per la pioggia „pioggia giusta" conta solo le ore in cui ha piovuto o era prevista pioggia, così un modello che non la prevede mai non vince. Sotto 24 ore (pioggia: 5 ore bagnate) non c\'è classifica. Consenso e „come ieri" sono riferimenti. SIAG KMOS manca perché è interrogato per comune e non può essere verificato su una stazione. | Avg. error is the average difference from the measurement. Hits counts hours within 2 K (wind: 5 km/h). Lean shows whether a model usually runs warm (+) or cold (−). For rain, "rain right" counts only hours where it rained or rain was forecast, so a model that never forecasts rain cannot win. Below 24 hours (rain: 5 wet hours) nothing is ranked. Consensus and "same as yesterday" are references. SIAG KMOS is missing because it is asked for by municipality and cannot be checked against a station. |
| stats_info_excluded (plurals) | one: %1$d Stunde wurde als Messfehler ausgeschlossen. / other: %1$d Stunden wurden als Messfehler ausgeschlossen. | one: %1$d ora esclusa come errore di misura. / other: %1$d ore escluse come errore di misura. | one: %1$d hour was excluded as a measurement fault. / other: %1$d hours were excluded as measurement faults. |
| stats_row_desc_ranked | Platz %1$d, %2$s, %3$s | %1$d° posto, %2$s, %3$s | Rank %1$d, %2$s, %3$s |
| stats_row_desc_other | %1$s, %2$s | %1$s, %2$s | %1$s, %2$s |
| stats_detail_by_part | NACH TAGESZEIT | PER FASCIA ORARIA | BY TIME OF DAY |
| stats_detail_daily | TÄGLICHER Ø FEHLER | Ø ERRORE GIORNALIERO | DAILY AVG. ERROR |
| stats_detail_open_source | Details zur Quelle › | Dettagli della fonte › | Source details › |
| source_rank_line | Treffsicherheit: Platz %1$d von %2$d · Ø %3$s (30 Tage, 6 h) | Affidabilità: %1$d° su %2$d · Ø %3$s (30 giorni, 6 h) | Accuracy: rank %1$d of %2$d · avg. %3$s (30 days, 6 h) |
| source_rank_not_enough | Treffsicherheit: noch zu wenig Daten | Affidabilità: ancora pochi dati | Accuracy: not enough data yet |

- [ ] **Step 2: Formatting helpers** (top of `StatsScreen.kt`)

```kotlin
/** A score's numbers in the reader's units: K, km/h (or their wind unit), percent. */
@Composable
internal fun mainValue(quantity: Quantity, score: Score, windUnit: WindUnit, f: Formats): String = when (quantity) {
    Quantity.TEMPERATURE -> score.main?.let { f.oneDecimal(it) + " K" } ?: "–"
    Quantity.WIND -> score.main?.let { Format.wind(it, windUnit, f) } ?: "–"
    Quantity.RAIN -> percent(score.main, f)
}

@Composable
internal fun percent(share: Double?, f: Formats): String =
    share?.let { stringResource(R.string.stats_percent, f.whole((it * 100).roundToInt())) } ?: "–"

@Composable
internal fun leanValue(quantity: Quantity, score: Score, windUnit: WindUnit, f: Formats): String = when (quantity) {
    Quantity.TEMPERATURE -> score.lean?.let { Format.kelvinDelta(it, f) } ?: "–"
    Quantity.WIND -> score.lean?.let { (if (it > 0) "+" else "") + Format.wind(it, windUnit, f) } ?: "–"
    Quantity.RAIN -> percent(score.lean, f)
}
```

Read `WindUnit` from the weather state's settings. `StatsUiState` gets a `windUnit: WindUnit = WindUnit.KMH` field set by the builder's caller: add a `windUnit` parameter to `StatsStateBuilder.build` with default `WindUnit.KMH`, and `StatsViewModel` passes `holder.weather`'s `settings.windUnit`. Check the actual enum and setting names in `data/AppSettings`/`WindUnit` and adapt.

- [ ] **Step 3: `StatsContent` and friends** (in `StatsScreen.kt`)

Structure, following `CompareScreen`/`SourceDetailSheet` for colours, `GlassCard`, typography and insets:

- **`StatsScreen`:**
  - Collects `viewModel.state`.
  - Calls `StatsContent` with the view model's setters.
  - Passes `onOpenSource = { viewModel.closeDetail(); onOpenSource(it) }`.
- **`StatsContent`:** a `LazyColumn` tagged `stats_screen`, with the status-bar top inset and a bottom padding of 96 dp.
  1. Header row: a back `IconButton` (`Icons.AutoMirrored.Rounded.ArrowBack`, contentDescription `R.string.close`), the title `stats_title`, and an info `IconButton` tagged `stats_info` that opens an `AlertDialog`. The dialog shows `stats_info_body` plus `stats_info_excluded` when `excludedHours > 0`, and a close button.
  2. If `!state.hasStation`: a `GlassCard` with `stats_no_station`, tagged `stats_no_station`; nothing else below.
  3. `SingleChoiceSegmentedButtonRow` for `Quantity.entries`, segments tagged `stats_quantity_<NAME>`, `icon = {}`, labels in `CompactLabel`.
  4. A `FlowRow` of `FilterChip`s: `StatsPeriod.entries` (tag `stats_period_<NAME>`, label `stats_period` with `days`), then a spacer, then `LeadBucket.entries` (tag `stats_lead_<NAME>`, label `stats_lead_now` for `NOW`, else `stats_lead_hours` with `hours`).
  5. The basis line, `labelMedium`, white 0.75: `stats_basis` with `observedHours`, `Format.dayMonth(firstHour date in SouthTyrol.ZONE)` and `stationName`. It is shown only when `observedHours > 0`.
  6. If `rows.isEmpty() && unranked.isEmpty()`: a `GlassCard` with `stats_not_enough`, tagged `stats_empty`.
  7. Otherwise a `GlassCard` holding the table.
     - **Header row:** `#`, model, then for temperature and wind `stats_col_error`/`stats_col_hits`/`stats_col_lean`, for rain `stats_col_rain_right`/`stats_col_detected`/`stats_col_false_alarm`. Labels are `labelSmall`, white 0.7.
     - **Row:** a composable `StatsRowView` for each `rows` entry. Model rows are tappable (`clickable(role = Role.Button)`) and call `onOpenDetail(source)`; reference rows are not. Model rows are tagged `stats_row_<SOURCE>`, the consensus row `stats_row_consensus`, the yesterday row `stats_row_yesterday`.
     - **Row layout:** `heightIn(min = 44.dp)`; a rank column 28 dp wide (the number, or empty for a reference); the colour dot from `SourceColors.of` for models; the name, with `Source.shortName` for models and `stats_consensus` / `stats_persistence` in *italic, white 0.8* for references; then three value `Text`s, each `Modifier.widthIn(min = 56.dp)` with `TextAlign.End`, formatted via the Step 2 helpers. For rain the second and third columns are `percent(hitRate)` and `percent(lean)`; for temperature and wind the second is `percent(hitRate)` and the third `leanValue`.
     - **Large text:** let the name `Text` take `Modifier.weight(1f)` and wrap. At a large font scale the three values move to a second line: implement with `FlowRow(horizontalArrangement = Arrangement.End)` for the value group, so they wrap instead of clipping.
     - **Semantics:** `semantics(mergeDescendants = true) { contentDescription = … }`, using `stats_row_desc_ranked` for models with a rank and `stats_row_desc_other` otherwise. The summary sentence is built from the column labels and values, e.g. "Ø Fehler 1,1 K, Treffer 78 %, Tendenz +0,3 K".
     - **Below the rows:** each `unranked` entry as a dimmed row, tagged `stats_unranked`, reading `<shortName> · stats_unranked(hours)`.
  8. The detail sheet: when `state.detail != null`, a `ModalBottomSheet` (`skipPartiallyExpanded = true`, `containerColor = MaterialTheme.colorScheme.surface`, tag `stats_detail_sheet`) containing a scrolling `Column`:
     - title `source.displayName` with `heading()`;
     - a close `IconButton` tagged `stats_detail_close`;
     - the overall score row, using the same `StatsRowView` formatting;
     - the block `stats_detail_by_part` with one row per `DayPart`, using the `source_part_*` labels, main value and hits;
     - for temperature and wind with `daily.size >= 2`, the block `stats_detail_daily` with a `Canvas` line chart, 120 dp tall and full width. Draw: a polyline of `daily` values scaled between 0 and the maximum value rounded up; a baseline; the first and last date labels under it via `Format.dayMonth`; and the maximum value label at top left. Stroke 2 dp in `SourceColors.of(source)`. Give it a `contentDescription` like "Täglicher Ø Fehler von <first> bis <last>, zwischen <min> und <max>" built from the same formatted strings (use `stats_detail_daily` plus the values; no new key is needed if you join them with " · ");
     - a `TextButton` tagged `stats_detail_source` reading `stats_detail_open_source` and calling `onOpenSource(source)`.

- [ ] **Step 4: `StatsCard.kt`**

```kotlin
@Composable
fun StatsCard(state: StatsCardState, windUnit: WindUnit, onOpen: () -> Unit, modifier: Modifier = Modifier)
```

It renders a `GlassCard` tagged `stats_card`:
- the title `stats_card_title` (`labelSmall`, white 0.7) and subtitle `stats_card_subtitle`;
- if `!state.hasStation`, `stats_no_station`;
- else if `!state.enoughData`, `stats_not_enough`;
- else up to three rows showing rank, dot, `shortName`, `mainValue(TEMPERATURE)` and `percent(hitRate)`;
- then a full-width `TextButton` tagged `stats_card_open` reading `stats_card_all` and calling `onOpen`.

The whole card is also `clickable(role = Role.Button, onClick = onOpen)`.

- [ ] **Step 5: Wire Vergleich, source sheet and navigation**

- **`CompareScreen`:**
  - takes `onOpenStats: () -> Unit = {}`;
  - collects `viewModel.stats`;
  - passes `stats` and `onOpenStats` to `CompareContent`.
- **`CompareContent`:**
  - adds `stats: CompareStats = CompareStats()` and `onOpenStats: () -> Unit = {}`;
  - inserts `item { StatsCard(stats.card, state.settings.windUnit, onOpenStats, Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }` directly before the `status_list` item;
  - passes `rank = stats.ranks[detail.source]` to `SourceDetailSheet`.
- **`SourceDetailSheet`:** add the parameter `rank: SourceRank? = null`. Inside the share block, after the in-consensus line, add `rank?.let { r -> Line(if (r.rank != null && r.score?.main != null) stringResource(R.string.source_rank_line, r.rank, r.of, formats.oneDecimal(r.score.main) + " K") else stringResource(R.string.source_rank_not_enough), modifier = Modifier.testTag("source_detail_rank")) }`.
- **`AppNavigation`:**
  - add `@Serializable object StatsRoute`;
  - `composable<CompareRoute> { CompareScreen(onOpenStats = { nav.navigate(StatsRoute) }) }`;
  - `composable<StatsRoute> { StatsScreen(onBack = { nav.popBackStack() }, onOpenSource = { source -> nav.getBackStackEntry<CompareRoute>().savedStateHandle["compare_source"] = source.name; nav.popBackStack() }) }`;
  - in the bar, the compare item counts as selected while the statistics screen is showing: `val selected = dest?.hasRoute(item.route::class) == true || (item.route == CompareRoute && dest?.hasRoute(StatsRoute::class) == true)`.

- [ ] **Step 6: Screenshot goldens**

Create `StatsScreenshotTest` in the style of `SourceDetailScreenshotTest`: `@Config(qualifiers = "de-w400dp-h1400dp-xhdpi")`, `ApexTheme`, `LocalFormats` German. Build a realistic 30-day history in the test with a fixed generator:
- 720 hours ending `2026-09-15T12:00Z`;
- observation `15 + 6 * sin(2π·i/24)`, wind 8 km/h, rain 0.6 mm on every 13th hour for hours 0–100, else 0;
- models with fixed offsets: ICON-CH1 +0.3, ICON-D2 −0.6, AROME +0.9, KNMI +1.4, GFS −2.1, and GEM for the last 10 hours only (so it stays unranked);
- rain forecast per model following the observation with one deliberate model (GFS) never wet.

Build `StatsUiState` via `StatsStateBuilder.build` and capture:
- `stats_temperature.png`: temperature, 30 days, 6 h;
- `stats_rain.png`;
- `stats_card.png`: `StatsCard`.

Record with `./gradlew :app:recordRoborazziDebug --tests 'it.apexweather.ui.screenshot.StatsScreenshotTest'`. **Open each PNG with the Read tool and describe it in the report:** ranking order, references among the rows, the unranked GEM, nothing clipped. Then run `verifyRoborazziDebug`.

- [ ] **Step 7: Device test (compile only)**

`app/src/androidTest/kotlin/it/apexweather/ui/stats/StatsContentTest.kt`, a hand-built `StatsUiState` from `StatsStateBuilder` with a small inline history:
- (a) tapping `stats_quantity_RAIN` calls `onQuantity(RAIN)`;
- (b) tapping `stats_row_ICON_D2` calls `onOpenDetail(ICON_D2)`;
- (c) with a `detail` in the state, `stats_detail_close` exists; tapping it calls `onCloseDetail`; tapping `stats_detail_source` calls `onOpenSource(ICON_D2)`;
- (d) a place without a station shows `stats_no_station`.

Also add a case to `CompareScreenTest`: tapping `stats_card_open` (after `scrollTo("stats_card")`) calls `onOpenStats`. No German is asserted. Compile with `./gradlew :app:compileDebugAndroidTestKotlin`.

- [ ] **Step 8: Verify**

Run: `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c "./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleDebug -q"`
Expected: BUILD SUCCESSFUL, lint clean.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/stats app/src/main/kotlin/it/apexweather/ui/compare app/src/main/kotlin/it/apexweather/ui/navigation/AppNavigation.kt app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml app/src/main/res/values-en/strings.xml app/src/test/kotlin/it/apexweather/ui/screenshot/StatsScreenshotTest.kt app/src/test/screenshots/stats_temperature.png app/src/test/screenshots/stats_rain.png app/src/test/screenshots/stats_card.png app/src/androidTest/kotlin/it/apexweather/ui/stats/StatsContentTest.kt app/src/androidTest/kotlin/it/apexweather/ui/compare/CompareScreenTest.kt
git commit -m "feat: the Statistik screen, its card on Vergleich, and a rank line in the source sheet" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016gqqzcABukZdCYES9zAg21"
```

---

### Task 8: CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add after the `BiasCorrector` architecture bullet** (the one that begins `- **\`BiasCorrector\` is the accuracy lever.**`):

```markdown
- **The statistics screen scores the models against the station, and its history is kept 90 days.**
  `station_history` (version 2, `HistoryDatabase.MIGRATION_1_2`, schema exported to `app/schemas`)
  carries rain and wind beside the temperature: the station's `ff` and its rain since midnight `n`
  as read, and each model's rain and wind at 0, 6 and 12 hours ahead in `modelsRainJson` /
  `modelsWindJson`. Columns were added rather than `modelsJson` reshaped, so `BiasCorrector`'s read
  path is untouched; it still filters its own seven-day `WINDOW` while `VerificationHistory.KEEP`
  holds 90 days for `WeatherRepository.stationHistory`. An hour's rain is the difference of two daily
  totals on the same local day (`VerificationHistory.hourlyRain`) — the first hour of a day is its own
  total, a gap or a falling total is not scored — and it is off from Open-Meteo's preceding-hour sum
  by the reading's minutes, which is accepted. `ForecastScores` ranks temperature and wind by mean
  absolute error (hits within 2 K / 5 km/h, lean = mean signed error) and rain by the critical
  success index, **never by plain accuracy**: 83 % of hours are dry, so a model that never forecasts
  rain would win. Under 24 hours, or for rain under 5 wet hours, a model is listed and not ranked; a
  miss beyond 15 K or 60 km/h is a station fault, excluded and counted. The consensus row is the
  weighted median (mean for rain) and "wie gestern" the reading 24 hours earlier; neither is ranked.
  The station calls ask for `temperature_2m,precipitation,wind_speed_10m` (3,3 kB gzipped against
  1,8 for temperature alone, 2026-09-15) and `t2m,rr_acc,u10m,v10m`.
  **A connected test run on the phone uninstalls the app and deletes this history**; run device
  tests on an emulator, or ask first.
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: the statistics screen, its 90-day history and how it scores" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016gqqzcABukZdCYES9zAg21"
```

---

## Verification after all tasks (controller, not a task)

1. `./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintDebug :app:assembleRelease`.
2. Device tests (`StatsContentTest`, `CompareScreenTest`) on an emulator if one is attached. Otherwise ask the user before running them on the phone.
3. **Migration on the phone, without uninstalling:**
   - copy `apexweather-history.db` with its `-wal`/`-shm` and count `station_history` rows;
   - `adb install -r` the release APK;
   - launch, refresh, copy again, and confirm the row count did not fall and the new columns exist.
4. On the phone: Vergleich card → Statistik screen, switch Temperatur / Regen / Wind, tap a model, then "Details zur Quelle", which returns to the source sheet with its rank line. Check at font scale 2.
5. Release as v0.25.0 when the user agrees.
