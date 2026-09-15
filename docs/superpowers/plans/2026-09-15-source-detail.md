# Source Detail Sheet Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tapping a source in the comparison screen's "Quellenstatus" card opens a sheet that says what the model is, whether it is working, how much it counts in the consensus, and how wrong it has been at the station.

**Architecture:** A static `SourceInfo` table in `domain/`, a pure `SourceDetailStateBuilder` over the cached `WeatherSnapshot`, and one small live fetch — provider run metadata — through `SourceMetaRepository` (in memory, 10 min). `CompareViewModel` holds which source is open in `SavedStateHandle`; `CompareContent` draws the sheet.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Hilt, Retrofit + kotlinx-serialization (`JsonObject`), OkHttp, JUnit4, Robolectric, Roborazzi, Compose UI tests.

Spec: `docs/superpowers/specs/2026-09-15-source-detail-design.md`.

## Global Constraints

- Work on branch `source-detail` in `/home/ben/repo/apexweather`. Do not push, tag or merge.
- Run every shell script as `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c '…'` (the zsh cd hook corrupts output otherwise). Gradle in the foreground only, never two builds at once.
- Every new string key goes into `app/src/main/res/values/strings.xml` (German), `values-it/strings.xml` and `values-en/strings.xml`.
- Nothing that runs on a device may assert a German string (CI emulators are en-US).
- Numbers and times go through `ui/common/Format.kt` with `LocalFormats.current`; times in `SouthTyrol.ZONE`.
- Suspend fetches use `runCatchingCancellable`, never `runCatching`.
- A fake that takes a URL must assert the URL it was given.
- The sheet is a `ModalBottomSheet` with `skipPartiallyExpanded = true` **and a close cross** (CLAUDE.md bottom-sheet rule).
- Reach is never taken from provider metadata (Open-Meteo's `data_end_time` gave UKMO 61 h and IFS 147 h against 171 h and 336 h on the real call).
- `./gradlew :app:lintDebug` runs with `warningsAsErrors` and must be clean.
- Commit per task. End every commit message with exactly:
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_016gqqzcABukZdCYES9zAg21
  ```

## Adjustments to the spec, decided while planning

- **Global sources.** `WeatherSnapshot.forecastsForBlend` does include the globals; the blender drops them hour by hour where two regional sources reach. So the state carries `onlyFillsGaps` (a non-regional source while two or more regional sources are in the blend) and the sheet says it in one sentence, instead of calling the global "not in the consensus".
- **Weight.** Computed the way the blender does it on a normal hour: among the regional sources in the blend when the source is regional and at least two regional sources are there, otherwise among everything in the blend.
- **Licence** is the licence of the data as the app receives it. For Open-Meteo that is CC BY 4.0, except UK Met Office data, which Open-Meteo redistributes under CC BY-SA 4.0 (checked on `open-meteo.com/en/licence` and the UKMO docs page, 2026-09-15).
- **KMOS provider name** is a string resource (`source_provider_province`), because the province's weather service has a different name in each language; every other provider is a proper noun.
- **Bias** is shown for `LeadBucket.SIX`, labelled as the error six hours ahead.

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/kotlin/it/apexweather/domain/SourceInfo.kt` (create) | Static facts per `Source`: provider, grid, delivery, licence, website, Open-Meteo metadata dataset. |
| `app/src/main/kotlin/it/apexweather/data/remote/SourceMetaApi.kt` (create) | Retrofit interface for metadata by absolute URL; `SourceMeta`; `SourceMetaMapper`. |
| `app/src/main/kotlin/it/apexweather/data/SourceMetaRepository.kt` (create) | URL per source, 10-minute memory cache, null on failure. |
| `app/src/main/kotlin/it/apexweather/di/AppModule.kt` (modify) | Provide `SourceMetaApi` with a 5 s call timeout. |
| `app/src/main/kotlin/it/apexweather/ui/compare/SourceDetailState.kt` (create) | `SourceDetailState`, `StationBlock`, `BiasCell`, `SourceMetaUi`, `SourceDetailStateBuilder`. |
| `app/src/main/kotlin/it/apexweather/ui/common/Format.kt` (modify) | `Format.kelvinDelta`. |
| `app/src/main/kotlin/it/apexweather/ui/compare/SourceDetailSheet.kt` (create) | The sheet's content. |
| `app/src/main/kotlin/it/apexweather/ui/compare/CompareScreen.kt` (modify) | Tappable status rows, the sheet, `statusText`/`statusColor` made internal. |
| `app/src/main/kotlin/it/apexweather/ui/compare/CompareViewModel.kt` (modify) | Open/close source, detail flow, meta fetch job. |
| `app/src/main/res/values*/strings.xml` (modify) | New keys in three languages. |
| Tests | `SourceInfoTest`, `SourceMetaMapperTest`, `SourceMetaRepositoryTest`, `SourceDetailStateBuilderTest`, `KelvinDeltaTest`, `SourceDetailScreenshotTest`, `CompareScreenTest` (device). |
| Fixtures (already recorded 2026-09-15, do not re-record) | `app/src/test/resources/fixtures/meta_dwd_icon_d2.json`, `meta_ecmwf_ifs025.json`, `geosphere_nwp_metadata.json`. |

---

### Task 1: SourceInfo table

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/domain/SourceInfo.kt`
- Test: `app/src/test/kotlin/it/apexweather/domain/SourceInfoTest.kt`

**Interfaces:**
- Consumes: `Source`, `OpenMeteoMapper.MODELS`.
- Produces: `enum class Delivery { OPEN_METEO, GEOSPHERE, SIAG }`; `data class SourceInfo(provider: String?, gridKm: Double?, delivery: Delivery, licence: String, website: String, metaDataset: String?)`; `SourceInfo.of(source: Source): SourceInfo`.

- [ ] **Step 1: Write the failing test**

```kotlin
package it.apexweather.domain

import it.apexweather.data.remote.OpenMeteoMapper
import it.apexweather.domain.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceInfoTest {

    @Test
    fun `every source has facts, a licence and an https website`() {
        Source.entries.forEach { s ->
            val info = SourceInfo.of(s)
            assertTrue("$s has no licence", info.licence.isNotBlank())
            assertTrue("$s website ${info.website}", info.website.startsWith("https://"))
            assertTrue("$s provider is blank", info.provider == null || info.provider.isNotBlank())
        }
    }

    /** The metadata dataset is how the sheet finds a run time; it must exist exactly where Open-Meteo delivers. */
    @Test
    fun `Open-Meteo sources and only they carry a metadata dataset`() {
        Source.entries.forEach { s ->
            val info = SourceInfo.of(s)
            assertEquals("$s delivery", s in OpenMeteoMapper.MODELS, info.delivery == Delivery.OPEN_METEO)
            if (info.delivery == Delivery.OPEN_METEO) assertNotNull("$s dataset", info.metaDataset) else assertNull("$s dataset", info.metaDataset)
        }
        val datasets = Source.entries.mapNotNull { SourceInfo.of(it).metaDataset }
        assertEquals("a dataset is named twice", datasets.size, datasets.toSet().size)
    }

    @Test
    fun `only KMOS is not a grid, and only the province goes unnamed`() {
        Source.entries.forEach { s ->
            val info = SourceInfo.of(s)
            assertEquals("$s grid", s == Source.SIAG_KMOS, info.gridKm == null)
            assertEquals("$s provider", s == Source.SIAG_KMOS, info.provider == null)
        }
        assertEquals(Delivery.SIAG, SourceInfo.of(Source.SIAG_KMOS).delivery)
        assertEquals(Delivery.GEOSPHERE, SourceInfo.of(Source.GEOSPHERE_AROME).delivery)
    }

    /** The one Open-Meteo source whose licence is not CC BY. */
    @Test
    fun `UK Met Office data is share-alike`() {
        assertEquals("CC BY-SA 4.0", SourceInfo.of(Source.UKMO).licence)
        assertEquals("CC BY 4.0", SourceInfo.of(Source.ICON_D2).licence)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c "./gradlew :app:testDebugUnitTest --tests 'it.apexweather.domain.SourceInfoTest' -q"`
Expected: FAIL, compilation error `Unresolved reference 'SourceInfo'`.

- [ ] **Step 3: Write the implementation**

```kotlin
package it.apexweather.domain

import it.apexweather.domain.model.Source

/** Which upstream the app fetches a source's forecast from. */
enum class Delivery { OPEN_METEO, GEOSPHERE, SIAG }

/**
 * What a source is, in facts that do not change from one refresh to the next — for the source sheet
 * on the comparison screen.
 *
 * Every value was checked on 2026-09-15 against the provider's documentation, not written from
 * memory: resolutions and update cycles from Open-Meteo's model pages (`open-meteo.com/en/docs/…`)
 * and GeoSphere's dataset metadata (`spatial_resolution_m` 2500), licences from
 * `open-meteo.com/en/licence` ("API data are offered under CC BY 4.0", and the UKMO page: "UK Met
 * Office data is provided under the CC BY-SA 4.0 licence"), KMOS's CC0 from the Open Data Hub
 * record's `LicenseInfo`, and every website answered HTTP 200.
 *
 * **[metaDataset] is not the model id of the forecast call.** Open-Meteo files run metadata under
 * `/data/<dataset>/static/meta.json`, and the names differ: the forecast asks for `icon_d2`, the
 * metadata lives under `dwd_icon_d2`; `gfs_seamless` has none and GFS's global run is `ncep_gfs013`.
 * All eleven answered HTTP 200 on 2026-09-14.
 */
data class SourceInfo(
    /** The institution, as a proper noun. Null for the province's own weather service, whose name is per language. */
    val provider: String?,
    /** Horizontal grid spacing in kilometres; null where the forecast is not a grid (KMOS is per municipality). */
    val gridKm: Double?,
    val delivery: Delivery,
    /** The licence of the data as this app receives it. */
    val licence: String,
    val website: String,
    /** Open-Meteo's metadata dataset; null for every other delivery. */
    val metaDataset: String? = null,
) {
    companion object {
        fun of(source: Source): SourceInfo = TABLE.getValue(source)

        private const val CC_BY = "CC BY 4.0"

        private val TABLE: Map<Source, SourceInfo> = mapOf(
            Source.SIAG_KMOS to SourceInfo(null, null, Delivery.SIAG, "CC0", "https://weather.provinz.bz.it"),
            Source.GEOSPHERE_AROME to SourceInfo("GeoSphere Austria", 2.5, Delivery.GEOSPHERE, CC_BY, "https://www.geosphere.at"),
            // MeteoSwiss docs: ICON CH1 0.01° (~1 km), every 3 h; ICON CH2 0.02° (~2 km), every 6 h.
            Source.ICON_CH1 to SourceInfo("MeteoSwiss", 1.0, Delivery.OPEN_METEO, CC_BY, "https://www.meteoswiss.admin.ch", "meteoswiss_icon_ch1"),
            Source.ICON_CH2 to SourceInfo("MeteoSwiss", 2.0, Delivery.OPEN_METEO, CC_BY, "https://www.meteoswiss.admin.ch", "meteoswiss_icon_ch2"),
            // ItaliaMeteo-ARPAE docs: ICON 2I 0.02° (~2 km), every 12 h.
            Source.ICON_2I to SourceInfo("ItaliaMeteo-ARPAE", 2.0, Delivery.OPEN_METEO, CC_BY, "https://www.arpae.it", "italia_meteo_arpae_icon_2i"),
            // DWD docs: ICON D2 0.02° (~2 km), every 3 h.
            Source.ICON_D2 to SourceInfo("Deutscher Wetterdienst", 2.0, Delivery.OPEN_METEO, CC_BY, "https://www.dwd.de", "dwd_icon_d2"),
            // KNMI docs: HARMONIE AROME Europe 5.5 km, every hour (the Netherlands 2 km run does not reach here).
            Source.KNMI_HARMONIE to SourceInfo("KNMI", 5.5, Delivery.OPEN_METEO, CC_BY, "https://www.knmi.nl", "knmi_harmonie_arome_europe"),
            // DMI docs: HARMONIE AROME DINI 2 km, every 3 h.
            Source.DMI_HARMONIE to SourceInfo("DMI", 2.0, Delivery.OPEN_METEO, CC_BY, "https://www.dmi.dk", "dmi_harmonie_arome_europe"),
            // ECMWF docs: IFS 0.25° (~25 km), AIFS Single 0.25° (~28 km), both every 6 h.
            Source.ECMWF to SourceInfo("ECMWF", 25.0, Delivery.OPEN_METEO, CC_BY, "https://www.ecmwf.int", "ecmwf_ifs025"),
            Source.ECMWF_AIFS to SourceInfo("ECMWF", 28.0, Delivery.OPEN_METEO, CC_BY, "https://www.ecmwf.int", "ecmwf_aifs025_single"),
            // GFS docs: GFS Global 0.11° (~13 km), every 6 h.
            Source.GFS to SourceInfo("NOAA NCEP", 13.0, Delivery.OPEN_METEO, CC_BY, "https://www.ncei.noaa.gov/products/weather-climate-models/global-forecast", "ncep_gfs013"),
            // UKMO docs: Global 0.09° (~10 km), every 6 h; redistributed CC BY-SA 4.0.
            Source.UKMO to SourceInfo("Met Office", 10.0, Delivery.OPEN_METEO, "CC BY-SA 4.0", "https://www.metoffice.gov.uk", "ukmo_global_deterministic_10km"),
            // GEM docs: GEM Global 0.15° (~15 km), every 12 h.
            Source.GEM to SourceInfo("ECCC", 15.0, Delivery.OPEN_METEO, CC_BY, "https://weather.gc.ca", "cmc_gem_gdps"),
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c "./gradlew :app:testDebugUnitTest --tests 'it.apexweather.domain.SourceInfoTest' -q"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/domain/SourceInfo.kt app/src/test/kotlin/it/apexweather/domain/SourceInfoTest.kt
git commit -m "feat: static facts for every source, checked against the providers" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016gqqzcABukZdCYES9zAg21"
```

---

### Task 2: Metadata API and mapper

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/data/remote/SourceMetaApi.kt`
- Test: `app/src/test/kotlin/it/apexweather/data/remote/SourceMetaMapperTest.kt`
- Fixtures (exist): `meta_dwd_icon_d2.json`, `meta_ecmwf_ifs025.json`, `geosphere_nwp_metadata.json`

**Interfaces:**
- Consumes: nothing new.
- Produces: `interface SourceMetaApi { suspend fun metadata(url: String): JsonObject }` with `SourceMetaApi.BASE_URL`, `SourceMetaApi.openMeteoUrl(dataset: String): String`, `SourceMetaApi.GEOSPHERE_AROME_URL`; `data class SourceMeta(runStartedAt: Instant?, publishedAt: Instant?, updateEvery: Duration?)`; `object SourceMetaMapper { fun openMeteo(json: JsonObject): SourceMeta; fun geoSphere(json: JsonObject): SourceMeta }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package it.apexweather.data.remote

import it.apexweather.Fixtures
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Recorded 2026-09-15 from the live endpoints; see SourceInfo for where each URL comes from. */
class SourceMetaMapperTest {

    private fun fixture(name: String): JsonObject = Fixtures.json.parseToJsonElement(Fixtures.read(name)).jsonObject

    @Test
    fun `ICON-D2's metadata gives the run, its publication and the three-hour cycle`() {
        val meta = SourceMetaMapper.openMeteo(fixture("meta_dwd_icon_d2.json"))
        assertEquals(Instant.parse("2026-09-15T00:00:00Z"), meta.runStartedAt)
        assertEquals(Instant.parse("2026-09-15T01:27:51Z"), meta.publishedAt)
        assertEquals(Duration.ofHours(3), meta.updateEvery)
    }

    @Test
    fun `ECMWF IFS runs every six hours`() {
        val meta = SourceMetaMapper.openMeteo(fixture("meta_ecmwf_ifs025.json"))
        assertEquals(Instant.parse("2026-09-14T18:00:00Z"), meta.runStartedAt)
        assertEquals(Instant.parse("2026-09-15T01:11:44Z"), meta.publishedAt)
        assertEquals(Duration.ofHours(6), meta.updateEvery)
    }

    @Test
    fun `GeoSphere gives the newest reference time and the gap to the one before`() {
        val meta = SourceMetaMapper.geoSphere(fixture("geosphere_nwp_metadata.json"))
        assertEquals(Instant.parse("2026-09-14T21:00:00Z"), meta.runStartedAt)
        assertNull("GeoSphere publishes no publication time", meta.publishedAt)
        assertEquals(Duration.ofHours(3), meta.updateEvery)
    }

    /** A field the provider drops or garbles costs that line of the sheet and nothing else. */
    @Test
    fun `a missing or malformed field is null, not a failure`() {
        val meta = SourceMetaMapper.openMeteo(buildJsonObject {
            put("last_run_initialisation_time", 1789430400)
            put("update_interval_seconds", "soon")
        })
        assertEquals(Instant.parse("2026-09-15T00:00:00Z"), meta.runStartedAt)
        assertNull(meta.publishedAt)
        assertNull(meta.updateEvery)
    }

    @Test
    fun `GeoSphere with a single reference time has no interval`() {
        val meta = SourceMetaMapper.geoSphere(buildJsonObject {
            putJsonArray("available_forecast_reftimes") { add("2026-09-14T21:00+00:00") }
        })
        assertEquals(Instant.parse("2026-09-14T21:00:00Z"), meta.runStartedAt)
        assertNull(meta.updateEvery)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c "./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.remote.SourceMetaMapperTest' -q"`
Expected: FAIL, compilation error `Unresolved reference 'SourceMetaMapper'`.

- [ ] **Step 3: Write the implementation**

```kotlin
package it.apexweather.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import retrofit2.http.GET
import retrofit2.http.Url
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder

/**
 * When a model's current run started, as its provider publishes it — for the source sheet only.
 *
 * Asked for by absolute URL because the answers live on two hosts. Nothing here feeds the forecast,
 * the consensus or staleness: it is fetched when the reader opens a sheet, and a failure costs one
 * line of that sheet.
 */
interface SourceMetaApi {
    @GET
    suspend fun metadata(@Url url: String): JsonObject

    companion object {
        /** Retrofit wants a base; every call passes an absolute URL. */
        const val BASE_URL = "https://api.open-meteo.com/"

        fun openMeteoUrl(dataset: String): String = "https://api.open-meteo.com/data/$dataset/static/meta.json"

        /** The dataset the AROME forecast call itself uses (`GeoSphereApi`), not the grid one. */
        const val GEOSPHERE_AROME_URL = "https://dataset.api.hub.geosphere.at/v1/timeseries/forecast/nwp-v1-1h-2500m/metadata"
    }
}

/** A model's latest run. Every field may be missing; each is a line of its own on the sheet. */
data class SourceMeta(
    val runStartedAt: Instant?,
    val publishedAt: Instant?,
    val updateEvery: Duration?,
)

object SourceMetaMapper {

    /** Open-Meteo's `meta.json`: Unix seconds. `data_end_time` is deliberately not read — see SourceInfo. */
    fun openMeteo(json: JsonObject): SourceMeta = SourceMeta(
        runStartedAt = json.long("last_run_initialisation_time")?.let(Instant::ofEpochSecond),
        publishedAt = json.long("last_run_availability_time")?.let(Instant::ofEpochSecond),
        updateEvery = json.long("update_interval_seconds")?.takeIf { it > 0 }?.let(Duration::ofSeconds),
    )

    /**
     * GeoSphere's dataset metadata. It publishes reference times but no publication time, and its
     * cycle is the gap between the two newest reference times.
     */
    fun geoSphere(json: JsonObject): SourceMeta {
        val times = (json["available_forecast_reftimes"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(::parse) }
            .sortedDescending()
        val latest = (json["last_forecast_reftime"] as? JsonPrimitive)?.contentOrNull?.let(::parse) ?: times.firstOrNull()
        val every = if (times.size >= 2) Duration.between(times[1], times[0]).takeIf { !it.isNegative && !it.isZero } else null
        return SourceMeta(runStartedAt = latest, publishedAt = null, updateEvery = every)
    }

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    /** GeoSphere writes `2026-09-14T21:00+00:00`: an offset and no seconds. */
    private val TIME: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd'T'HH:mm")
        .optionalStart().appendPattern(":ss").optionalEnd()
        .appendOffset("+HH:MM", "Z")
        .toFormatter()

    private fun parse(text: String): Instant? = runCatching { OffsetDateTime.parse(text, TIME).toInstant() }.getOrNull()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c "./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.remote.SourceMetaMapperTest' -q"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/data/remote/SourceMetaApi.kt app/src/test/kotlin/it/apexweather/data/remote/SourceMetaMapperTest.kt app/src/test/resources/fixtures/meta_dwd_icon_d2.json app/src/test/resources/fixtures/meta_ecmwf_ifs025.json app/src/test/resources/fixtures/geosphere_nwp_metadata.json
git commit -m "feat: read model run metadata from Open-Meteo and GeoSphere" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016gqqzcABukZdCYES9zAg21"
```

---

### Task 3: SourceMetaRepository and its wiring

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/data/SourceMetaRepository.kt`
- Modify: `app/src/main/kotlin/it/apexweather/di/AppModule.kt`
- Test: `app/src/test/kotlin/it/apexweather/data/SourceMetaRepositoryTest.kt`

**Interfaces:**
- Consumes: `SourceInfo.of`, `Delivery`, `SourceMetaApi`, `SourceMetaMapper`, `SourceMeta`, `runCatchingCancellable`, `MutableClock` (test, package `it.apexweather.data`).
- Produces: `class SourceMetaRepository(api: SourceMetaApi, clock: Clock)` with `suspend fun metaFor(source: Source): SourceMeta?`; `SourceMetaRepository.urlFor(source: Source): String?`; `SourceMetaRepository.FRESH_FOR`.

- [ ] **Step 1: Write the failing test**

```kotlin
package it.apexweather.data

import it.apexweather.Fixtures
import it.apexweather.data.remote.SourceMetaApi
import it.apexweather.domain.model.Source
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

class SourceMetaRepositoryTest {

    private class FakeApi : SourceMetaApi {
        val urls = mutableListOf<String>()
        var fail = false
        var cancel = false
        override suspend fun metadata(url: String): JsonObject {
            urls += url
            if (cancel) throw CancellationException("the sheet was closed")
            if (fail) throw IOException("offline")
            val name = if ("geosphere" in url) "geosphere_nwp_metadata.json" else "meta_dwd_icon_d2.json"
            return Fixtures.json.parseToJsonElement(Fixtures.read(name)).jsonObject
        }
    }

    private val t0 = Instant.parse("2026-09-15T08:00:00Z")

    /** The fake answers whatever it is asked; this is what proves the right thing was asked. */
    @Test
    fun `each source is asked for at its own URL`() = runTest {
        val api = FakeApi()
        val repo = SourceMetaRepository(api, MutableClock(t0))
        repo.metaFor(Source.ICON_D2)
        repo.metaFor(Source.GFS)
        repo.metaFor(Source.GEOSPHERE_AROME)
        assertEquals(
            listOf(
                "https://api.open-meteo.com/data/dwd_icon_d2/static/meta.json",
                "https://api.open-meteo.com/data/ncep_gfs013/static/meta.json",
                "https://dataset.api.hub.geosphere.at/v1/timeseries/forecast/nwp-v1-1h-2500m/metadata",
            ),
            api.urls,
        )
    }

    @Test
    fun `an answer is kept for ten minutes`() = runTest {
        val api = FakeApi()
        val clock = MutableClock(t0)
        val repo = SourceMetaRepository(api, clock)
        assertNotNull(repo.metaFor(Source.ICON_D2))
        clock.now = t0.plus(Duration.ofMinutes(9))
        assertNotNull(repo.metaFor(Source.ICON_D2))
        assertEquals("asked again inside ten minutes", 1, api.urls.size)
        clock.now = t0.plus(Duration.ofMinutes(11))
        repo.metaFor(Source.ICON_D2)
        assertEquals(2, api.urls.size)
    }

    @Test
    fun `a failure is null and the next ask tries again`() = runTest {
        val api = FakeApi().apply { fail = true }
        val repo = SourceMetaRepository(api, MutableClock(t0))
        assertNull(repo.metaFor(Source.ICON_D2))
        api.fail = false
        assertNotNull(repo.metaFor(Source.ICON_D2))
        assertEquals(2, api.urls.size)
    }

    /** KMOS's run time is already its status time; there is nothing to ask. */
    @Test
    fun `KMOS is never asked`() = runTest {
        val api = FakeApi()
        assertNull(SourceMetaRepository(api, MutableClock(t0)).metaFor(Source.SIAG_KMOS))
        assertTrue(api.urls.isEmpty())
    }

    /** Plain runCatching would swallow this and return null; a closed sheet must stop the fetch. */
    @Test
    fun `cancellation is passed on, not turned into a missing answer`() = runTest {
        val api = FakeApi().apply { cancel = true }
        val thrown = runCatching { SourceMetaRepository(api, MutableClock(t0)).metaFor(Source.ICON_D2) }.exceptionOrNull()
        assertTrue("got $thrown", thrown is CancellationException)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c "./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.SourceMetaRepositoryTest' -q"`
Expected: FAIL, compilation error `Unresolved reference 'SourceMetaRepository'`.

- [ ] **Step 3: Write the implementation**

`app/src/main/kotlin/it/apexweather/data/SourceMetaRepository.kt`:

```kotlin
package it.apexweather.data

import it.apexweather.data.remote.SourceMeta
import it.apexweather.data.remote.SourceMetaApi
import it.apexweather.data.remote.SourceMetaMapper
import it.apexweather.domain.Delivery
import it.apexweather.domain.SourceInfo
import it.apexweather.domain.model.Source
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A model's latest run as its provider publishes it, for the source sheet.
 *
 * Held in memory for ten minutes — the radar's rule — and never written to Room: it describes the
 * provider right now, and an old answer is worth less than asking again. A failure is null and is
 * not remembered, so the next open of the sheet tries again. Cancellation is passed on: a sheet
 * closed mid-fetch stops the fetch.
 */
@Singleton
class SourceMetaRepository @Inject constructor(
    private val api: SourceMetaApi,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private val held = HashMap<Source, Pair<Instant, SourceMeta>>()

    suspend fun metaFor(source: Source): SourceMeta? {
        val url = urlFor(source) ?: return null
        mutex.withLock {
            held[source]?.let { (at, meta) -> if (Duration.between(at, clock.instant()) < FRESH_FOR) return meta }
        }
        val meta = runCatchingCancellable {
            val json = api.metadata(url)
            if (SourceInfo.of(source).delivery == Delivery.GEOSPHERE) SourceMetaMapper.geoSphere(json) else SourceMetaMapper.openMeteo(json)
        }.getOrNull() ?: return null
        mutex.withLock { held[source] = clock.instant() to meta }
        return meta
    }

    companion object {
        val FRESH_FOR: Duration = Duration.ofMinutes(10)

        /** Null where the provider publishes nothing to ask: KMOS's status time is already its run. */
        fun urlFor(source: Source): String? {
            val info = SourceInfo.of(source)
            return when (info.delivery) {
                Delivery.OPEN_METEO -> info.metaDataset?.let(SourceMetaApi::openMeteoUrl)
                Delivery.GEOSPHERE -> SourceMetaApi.GEOSPHERE_AROME_URL
                Delivery.SIAG -> null
            }
        }
    }
}
```

In `app/src/main/kotlin/it/apexweather/di/AppModule.kt`, add the import `import it.apexweather.data.remote.SourceMetaApi` beside the other `data.remote` imports, and add this provider directly after the `nowcast` provider line:

```kotlin
    // Five seconds for the whole call, not the forecast client's 45: this answers a sheet the reader
    // is looking at, and "not available" beats a spinner that outlasts their interest.
    @Provides @Singleton fun sourceMeta(c: OkHttpClient, j: Json): SourceMetaApi =
        retrofit(SourceMetaApi.BASE_URL, c.newBuilder().callTimeout(5, TimeUnit.SECONDS).build(), j).create(SourceMetaApi::class.java)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c "./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.SourceMetaRepositoryTest' -q && ./gradlew :app:assembleDebug -q"`
Expected: PASS (5 tests), and the debug build compiles (Hilt graph resolves `SourceMetaApi`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/data/SourceMetaRepository.kt app/src/main/kotlin/it/apexweather/di/AppModule.kt app/src/test/kotlin/it/apexweather/data/SourceMetaRepositoryTest.kt
git commit -m "feat: fetch a source's run metadata on demand, kept ten minutes" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016gqqzcABukZdCYES9zAg21"
```

---

### Task 4: SourceDetailState and its builder

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/ui/compare/SourceDetailState.kt`
- Modify: `app/src/main/kotlin/it/apexweather/ui/common/Format.kt`
- Test: `app/src/test/kotlin/it/apexweather/ui/compare/SourceDetailStateBuilderTest.kt`, `app/src/test/kotlin/it/apexweather/ui/common/KelvinDeltaTest.kt`

**Interfaces:**
- Consumes: `SourceInfo`, `SourceMeta`, `WeatherSnapshot` (`forecasts`, `status`, `forecastsForBlend`, `modelBias`), `ConsensusBlender.weightOf(source, among)`, `ModelBias.byPart`, `DayPart`, `LeadBucket.SIX`, `Place.station`, `Source.checkableAtStation`, `Source.staleAfterHours`, test helpers `forecast`, `point`, `hour`, `DORF_TIROL`, `STERZING` (package `it.apexweather.domain`).
- Produces:
  - `data class BiasCell(val part: DayPart, val kelvin: Double?)`
  - `sealed interface StationBlock { data object NoStation; data object NeverCheckable; data class Checked(val stationName: String, val cells: List<BiasCell>) }`
  - `sealed interface SourceMetaUi { data object NotApplicable; data object Loading; data class Loaded(val meta: SourceMeta); data object Unavailable }`
  - `data class SourceDetailState(source, info, status, staleAfterHours, reachUntil, weight, familySize, inConsensus, onlyFillsGaps, station, now)`
  - `object SourceDetailStateBuilder { fun build(source: Source, snapshot: WeatherSnapshot, place: Place?, now: Instant): SourceDetailState }`
  - `Format.kelvinDelta(k: Double, f: Formats): String`

- [ ] **Step 1: Write the failing tests**

`app/src/test/kotlin/it/apexweather/ui/compare/SourceDetailStateBuilderTest.kt`:

```kotlin
package it.apexweather.ui.compare

import it.apexweather.domain.DORF_TIROL
import it.apexweather.domain.DayPart
import it.apexweather.domain.LeadBucket
import it.apexweather.domain.ModelBias
import it.apexweather.domain.STERZING
import it.apexweather.domain.forecast
import it.apexweather.domain.hour
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceStatus
import it.apexweather.domain.model.WeatherSnapshot
import it.apexweather.domain.point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDetailStateBuilderTest {

    private val present = listOf(
        Source.ICON_CH1, Source.ICON_CH2, Source.ICON_2I, Source.ICON_D2,
        Source.GEOSPHERE_AROME, Source.ECMWF,
    )
    private val snapshot = WeatherSnapshot.EMPTY.copy(
        forecasts = present.associateWith { s -> forecast(s, (0 until if (s == Source.ECMWF) 240 else 48).map { point(it, 12.0) }) },
        status = present.associateWith { SourceStatus.Ok(hour(0)) },
        modelBias = ModelBias(mapOf(Source.ICON_CH1 to mapOf(DayPart.AFTERNOON to mapOf(LeadBucket.SIX to 1.4, LeadBucket.NOW to 9.9)))),
    )

    @Test
    fun `an ICON run shares its vote with the other three`() {
        val s = SourceDetailStateBuilder.build(Source.ICON_CH1, snapshot, DORF_TIROL, hour(2))
        assertEquals(0.5, s.weight!!, 1e-9)
        assertEquals(4, s.familySize)
        assertTrue(s.inConsensus)
        assertFalse(s.onlyFillsGaps)
    }

    @Test
    fun `AROME is the only one of its core and has a whole vote`() {
        val s = SourceDetailStateBuilder.build(Source.GEOSPHERE_AROME, snapshot, DORF_TIROL, hour(2))
        assertEquals(1.0, s.weight!!, 1e-9)
        assertEquals(1, s.familySize)
    }

    /** The blender drops globals hour by hour where two regional runs reach; the sheet says so. */
    @Test
    fun `a global only fills gaps while two regional sources are in the blend`() {
        val s = SourceDetailStateBuilder.build(Source.ECMWF, snapshot, DORF_TIROL, hour(2))
        assertTrue(s.inConsensus)
        assertTrue(s.onlyFillsGaps)
        val alone = snapshot.copy(forecasts = snapshot.forecasts.filterKeys { it == Source.ECMWF || it == Source.GEOSPHERE_AROME })
        assertFalse(SourceDetailStateBuilder.build(Source.ECMWF, alone, DORF_TIROL, hour(2)).onlyFillsGaps)
    }

    @Test
    fun `a stale source is not in the consensus and has no weight`() {
        val stale = snapshot.copy(status = snapshot.status + (Source.ICON_D2 to SourceStatus.Stale(hour(-20))))
        val s = SourceDetailStateBuilder.build(Source.ICON_D2, stale, DORF_TIROL, hour(2))
        assertFalse(s.inConsensus)
        assertNull(s.weight)
        assertEquals(6, s.staleAfterHours)
    }

    /** Reach is counted from what the app holds, never from provider metadata. */
    @Test
    fun `reach is the last hour with a value`() {
        assertEquals(hour(239), SourceDetailStateBuilder.build(Source.ECMWF, snapshot, DORF_TIROL, hour(2)).reachUntil)
        assertEquals(hour(47), SourceDetailStateBuilder.build(Source.ICON_D2, snapshot, DORF_TIROL, hour(2)).reachUntil)
    }

    @Test
    fun `the station block shows the six-hour error per part of the day`() {
        val block = SourceDetailStateBuilder.build(Source.ICON_CH1, snapshot, DORF_TIROL, hour(2)).station as StationBlock.Checked
        assertEquals("Meran", block.stationName)
        assertEquals(DayPart.entries, block.cells.map { it.part })
        assertEquals(1.4, block.cells.single { it.part == DayPart.AFTERNOON }.kelvin!!, 1e-9)
        assertNull("too few hours is null, not zero", block.cells.single { it.part == DayPart.NIGHT }.kelvin)
    }

    @Test
    fun `KMOS can never be checked, and a place without a station has no block`() {
        assertEquals(StationBlock.NeverCheckable, SourceDetailStateBuilder.build(Source.SIAG_KMOS, snapshot, DORF_TIROL, hour(2)).station)
        assertEquals(StationBlock.NoStation, SourceDetailStateBuilder.build(Source.ICON_CH1, snapshot, STERZING, hour(2)).station)
        assertEquals(StationBlock.NoStation, SourceDetailStateBuilder.build(Source.SIAG_KMOS, snapshot, null, hour(2)).station)
    }

    @Test
    fun `a source never loaded has facts and nothing else`() {
        val s = SourceDetailStateBuilder.build(Source.GEM, snapshot, DORF_TIROL, hour(2))
        assertNull(s.status)
        assertNull(s.reachUntil)
        assertNull(s.weight)
        assertFalse(s.inConsensus)
        assertEquals("ECCC", s.info.provider)
    }
}
```

`app/src/test/kotlin/it/apexweather/ui/common/KelvinDeltaTest.kt`:

```kotlin
package it.apexweather.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class KelvinDeltaTest {
    private val de = Formats(Locale.GERMANY, true)
    private val en = Formats(Locale.US, true)

    @Test
    fun `a difference always carries its sign`() {
        assertEquals("+1,4 K", Format.kelvinDelta(1.4, de))
        assertEquals("-0,8 K", Format.kelvinDelta(-0.8, de))
        assertEquals("+1.4 K", Format.kelvinDelta(1.4, en))
    }

    /** "-0,0" is a rounding artefact; spot on reads as spot on. */
    @Test
    fun `anything that rounds to zero is plus-minus zero`() {
        assertEquals("±0,0 K", Format.kelvinDelta(0.0, de))
        assertEquals("±0,0 K", Format.kelvinDelta(-0.04, de))
        assertEquals("±0,0 K", Format.kelvinDelta(0.04, de))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c "./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.compare.SourceDetailStateBuilderTest' --tests 'it.apexweather.ui.common.KelvinDeltaTest' -q"`
Expected: FAIL, compilation errors `Unresolved reference 'SourceDetailStateBuilder'` and `Unresolved reference 'kelvinDelta'`.

- [ ] **Step 3: Write the implementation**

`app/src/main/kotlin/it/apexweather/ui/compare/SourceDetailState.kt`:

```kotlin
package it.apexweather.ui.compare

import it.apexweather.data.remote.SourceMeta
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.DayPart
import it.apexweather.domain.LeadBucket
import it.apexweather.domain.Place
import it.apexweather.domain.SourceInfo
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceStatus
import it.apexweather.domain.model.WeatherSnapshot
import java.time.Instant

/** A model's measured error at the station for one part of the day; null where too few hours are in. */
data class BiasCell(val part: DayPart, val kelvin: Double?)

/** The sheet's last block, which exists only where a thermometer can check the model. */
sealed interface StationBlock {
    /** The place has no station near enough; the block is left out. */
    data object NoStation : StationBlock

    /** KMOS is addressed by municipality and can never be checked against a thermometer. */
    data object NeverCheckable : StationBlock

    data class Checked(val stationName: String, val cells: List<BiasCell>) : StationBlock
}

/** The one line fetched when the sheet opens. */
sealed interface SourceMetaUi {
    /** The provider publishes nothing to ask (KMOS). */
    data object NotApplicable : SourceMetaUi
    data object Loading : SourceMetaUi
    data class Loaded(val meta: SourceMeta) : SourceMetaUi
    data object Unavailable : SourceMetaUi
}

data class SourceDetailState(
    val source: Source,
    val info: SourceInfo,
    /** Null when the source has never been loaded. */
    val status: SourceStatus?,
    val staleAfterHours: Int,
    /** The last hour with a value in the cached forecast. Never from provider metadata. */
    val reachUntil: Instant?,
    /** The vote this source casts on a normal hour; null when it is not in the blend. */
    val weight: Double?,
    /** How many sources in the same count share its core, itself included. */
    val familySize: Int,
    val inConsensus: Boolean,
    /** A global source while two or more regional ones are in the blend: it only counts where they do not reach. */
    val onlyFillsGaps: Boolean,
    val station: StationBlock,
    val now: Instant,
)

object SourceDetailStateBuilder {

    fun build(source: Source, snapshot: WeatherSnapshot, place: Place?, now: Instant): SourceDetailState {
        val blend = snapshot.forecastsForBlend.keys
        val regionalInBlend = blend.filter { it.regional }.toSet()
        val inConsensus = source in blend
        // The blender counts a regional run among the regional runs whenever two of them report,
        // and everything else among everything present — so the weight is worked out the same way.
        val among = if (source.regional && regionalInBlend.size >= 2) regionalInBlend else blend
        val station = place?.station
        return SourceDetailState(
            source = source,
            info = SourceInfo.of(source),
            status = snapshot.status[source],
            staleAfterHours = source.staleAfterHours,
            reachUntil = snapshot.forecasts[source]?.hourly?.maxOfOrNull { it.time },
            weight = if (inConsensus) ConsensusBlender.weightOf(source, among) else null,
            familySize = among.count { it.family == source.family }.coerceAtLeast(1),
            inConsensus = inConsensus,
            onlyFillsGaps = inConsensus && !source.regional && regionalInBlend.size >= 2,
            station = when {
                station == null -> StationBlock.NoStation
                !source.checkableAtStation -> StationBlock.NeverCheckable
                else -> StationBlock.Checked(
                    stationName = station.name,
                    cells = DayPart.entries.map { part -> BiasCell(part, snapshot.modelBias.byPart[source]?.get(part)?.get(LeadBucket.SIX)) },
                )
            },
            now = now,
        )
    }
}
```

In `app/src/main/kotlin/it/apexweather/ui/common/Format.kt`, add inside `object Format`, directly after `tempDelta`:

```kotlin
    /**
     * A model's error in kelvin, for the source sheet. Always signed; anything that rounds to zero
     * reads "±0,0 K", because "-0,0" is a rounding artefact and not a measurement.
     */
    fun kelvinDelta(k: Double, f: Formats): String {
        val tenths = kotlin.math.round(k * 10.0) / 10.0
        val sign = when {
            tenths > 0.0 -> "+"
            tenths < 0.0 -> ""
            else -> "±"
        }
        return sign + f.oneDecimal(if (tenths == 0.0) 0.0 else tenths) + " K"
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c "./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.compare.SourceDetailStateBuilderTest' --tests 'it.apexweather.ui.common.KelvinDeltaTest' -q"`
Expected: PASS (8 + 2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/compare/SourceDetailState.kt app/src/main/kotlin/it/apexweather/ui/common/Format.kt app/src/test/kotlin/it/apexweather/ui/compare/SourceDetailStateBuilderTest.kt app/src/test/kotlin/it/apexweather/ui/common/KelvinDeltaTest.kt
git commit -m "feat: work out a source's share, reach and station error for its sheet" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016gqqzcABukZdCYES9zAg21"
```

---

### Task 5: The sheet, the tappable rows and the ViewModel

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/ui/compare/SourceDetailSheet.kt`
- Modify: `app/src/main/kotlin/it/apexweather/ui/compare/CompareScreen.kt`
- Modify: `app/src/main/kotlin/it/apexweather/ui/compare/CompareViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-it/strings.xml`, `app/src/main/res/values-en/strings.xml`
- Test: `app/src/androidTest/kotlin/it/apexweather/ui/compare/CompareScreenTest.kt`, `app/src/test/kotlin/it/apexweather/ui/screenshot/SourceDetailScreenshotTest.kt`
- Golden (new): `app/src/test/screenshots/source_detail_icon_ch1.png`

**Interfaces:**
- Consumes: everything from Tasks 1–4; `statusText`, `statusColor` in `CompareScreen.kt`.
- Produces: `SourceDetailSheet(state: SourceDetailState, meta: SourceMetaUi, onClose: () -> Unit)`; `CompareContent(…, onOpenSource: (Source) -> Unit = {}, detail: SourceDetailState? = null, meta: SourceMetaUi = SourceMetaUi.NotApplicable, onCloseSource: () -> Unit = {})`; `CompareViewModel.detail`, `.meta`, `.openSource(source)`, `.closeSource()`; test tags `status_row_<SOURCE>`, `source_detail_sheet`, `source_detail`, `source_detail_close`, `source_detail_error`, `source_detail_website`.

- [ ] **Step 1: Add the strings**

Add before `</resources>` in `app/src/main/res/values/strings.xml`:

```xml
    <string name="source_detail_open">%1$s, %2$s, Details öffnen</string>
    <string name="source_block_what">WAS ES IST</string>
    <string name="source_block_state">ZUSTAND</string>
    <string name="source_block_share">ANTEIL AM KONSENS</string>
    <string name="source_block_station">GEGEN DIE STATION</string>
    <string name="source_provider_province">Landeswetterdienst Südtirol</string>
    <string name="source_family">Modellkern: %1$s</string>
    <string name="source_grid_regional">%1$s-km-Gitter, regional</string>
    <string name="source_grid_global">%1$s-km-Gitter, global</string>
    <string name="source_grid_municipality">Punktvorhersage je Gemeinde</string>
    <string name="source_delivery_open_meteo">Abgerufen über Open-Meteo</string>
    <string name="source_delivery_geosphere">Abgerufen über den GeoSphere Data Hub</string>
    <string name="source_delivery_siag">Abgerufen über die Wetter-API des Landes</string>
    <string name="source_licence">Lizenz: %1$s</string>
    <string name="source_website">Website von %1$s öffnen</string>
    <string name="source_stale_after">Gilt nach %1$d h ohne neuen Lauf als veraltet und zählt dann nicht mehr zum Konsens.</string>
    <string name="source_last_good">Letzte gute Daten: %1$s</string>
    <string name="source_reach">Reicht bis %1$s</string>
    <string name="source_run_published">Lauf von %1$s, veröffentlicht %2$s</string>
    <string name="source_run">Lauf von %1$s</string>
    <string name="source_update_every">Neuer Lauf alle %1$s</string>
    <string name="source_run_loading">Laufzeit wird abgerufen …</string>
    <string name="source_run_unavailable">Laufzeit nicht abrufbar</string>
    <string name="source_weight_shared">Zählt %1$s Stimmen – %2$d Modelle teilen den %3$s-Kern</string>
    <string name="source_weight_alone">Volle Stimme – einziges Modell mit %1$s-Kern</string>
    <string name="source_in_consensus">Im Konsens: ja</string>
    <string name="source_not_in_consensus">Im Konsens: nein</string>
    <string name="source_fills_gaps">Als globales Modell zählt es nur in Stunden, die weniger als zwei regionale Modelle erreichen.</string>
    <string name="source_station">Station %1$s: Fehler 6 Stunden voraus, letzte 7 Tage</string>
    <string name="source_bias_too_few">noch zu wenige Stunden</string>
    <string name="source_part_night">Nachts</string>
    <string name="source_part_morning">Morgens</string>
    <string name="source_part_afternoon">Nachmittags</string>
    <string name="source_part_evening">Abends</string>
```

Add before `</resources>` in `app/src/main/res/values-it/strings.xml`:

```xml
    <string name="source_detail_open">%1$s, %2$s, apri dettagli</string>
    <string name="source_block_what">COS\'È</string>
    <string name="source_block_state">STATO</string>
    <string name="source_block_share">PESO NEL CONSENSO</string>
    <string name="source_block_station">CONFRONTO CON LA STAZIONE</string>
    <string name="source_provider_province">Servizio Meteo Alto Adige</string>
    <string name="source_family">Nucleo del modello: %1$s</string>
    <string name="source_grid_regional">griglia di %1$s km, regionale</string>
    <string name="source_grid_global">griglia di %1$s km, globale</string>
    <string name="source_grid_municipality">previsione puntuale per comune</string>
    <string name="source_delivery_open_meteo">Ottenuto tramite Open-Meteo</string>
    <string name="source_delivery_geosphere">Ottenuto tramite il GeoSphere Data Hub</string>
    <string name="source_delivery_siag">Ottenuto tramite l\'API meteo della Provincia</string>
    <string name="source_licence">Licenza: %1$s</string>
    <string name="source_website">Apri il sito di %1$s</string>
    <string name="source_stale_after">Senza una nuova corsa per %1$d h è considerato obsoleto e non conta più nel consenso.</string>
    <string name="source_last_good">Ultimi dati validi: %1$s</string>
    <string name="source_reach">Arriva fino a %1$s</string>
    <string name="source_run_published">Corsa delle %1$s, pubblicata %2$s</string>
    <string name="source_run">Corsa delle %1$s</string>
    <string name="source_update_every">Nuova corsa ogni %1$s</string>
    <string name="source_run_loading">Recupero dell\'orario della corsa …</string>
    <string name="source_run_unavailable">Orario della corsa non disponibile</string>
    <string name="source_weight_shared">Vale %1$s voti – %2$d modelli condividono il nucleo %3$s</string>
    <string name="source_weight_alone">Voto pieno – unico modello con nucleo %1$s</string>
    <string name="source_in_consensus">Nel consenso: sì</string>
    <string name="source_not_in_consensus">Nel consenso: no</string>
    <string name="source_fills_gaps">Come modello globale conta solo nelle ore raggiunte da meno di due modelli regionali.</string>
    <string name="source_station">Stazione %1$s: errore a 6 ore, ultimi 7 giorni</string>
    <string name="source_bias_too_few">ore ancora insufficienti</string>
    <string name="source_part_night">Notte</string>
    <string name="source_part_morning">Mattina</string>
    <string name="source_part_afternoon">Pomeriggio</string>
    <string name="source_part_evening">Sera</string>
```

Add before `</resources>` in `app/src/main/res/values-en/strings.xml`:

```xml
    <string name="source_detail_open">%1$s, %2$s, open details</string>
    <string name="source_block_what">WHAT IT IS</string>
    <string name="source_block_state">STATUS</string>
    <string name="source_block_share">SHARE OF THE CONSENSUS</string>
    <string name="source_block_station">AGAINST THE STATION</string>
    <string name="source_provider_province">South Tyrol weather service</string>
    <string name="source_family">Model core: %1$s</string>
    <string name="source_grid_regional">%1$s km grid, regional</string>
    <string name="source_grid_global">%1$s km grid, global</string>
    <string name="source_grid_municipality">Point forecast per municipality</string>
    <string name="source_delivery_open_meteo">Fetched through Open-Meteo</string>
    <string name="source_delivery_geosphere">Fetched through the GeoSphere Data Hub</string>
    <string name="source_delivery_siag">Fetched through the province\'s weather API</string>
    <string name="source_licence">Licence: %1$s</string>
    <string name="source_website">Open the %1$s website</string>
    <string name="source_stale_after">Counts as stale after %1$d h without a new run, and then leaves the consensus.</string>
    <string name="source_last_good">Last good data: %1$s</string>
    <string name="source_reach">Reaches until %1$s</string>
    <string name="source_run_published">Run of %1$s, published %2$s</string>
    <string name="source_run">Run of %1$s</string>
    <string name="source_update_every">New run every %1$s</string>
    <string name="source_run_loading">Fetching run time …</string>
    <string name="source_run_unavailable">Run time not available</string>
    <string name="source_weight_shared">Counts %1$s votes – %2$d models share the %3$s core</string>
    <string name="source_weight_alone">Full vote – the only model with the %1$s core</string>
    <string name="source_in_consensus">In the consensus: yes</string>
    <string name="source_not_in_consensus">In the consensus: no</string>
    <string name="source_fills_gaps">As a global model it only counts in hours that fewer than two regional models reach.</string>
    <string name="source_station">Station %1$s: error 6 hours ahead, last 7 days</string>
    <string name="source_bias_too_few">too few hours yet</string>
    <string name="source_part_night">Night</string>
    <string name="source_part_morning">Morning</string>
    <string name="source_part_afternoon">Afternoon</string>
    <string name="source_part_evening">Evening</string>
```

- [ ] **Step 2: Write the failing device test and screenshot test**

Append to `app/src/androidTest/kotlin/it/apexweather/ui/compare/CompareScreenTest.kt`, inside the class:

```kotlin
    /**
     * The status row is where "what is this model and why is it red" gets asked. The error line on
     * the card is cut at 40 characters; the sheet must carry all of it.
     */
    @Test
    fun tappingAStatusRowOpensTheSourceSheetAndTheCrossClosesIt() {
        val reason = "java.net.UnknownHostException: Unable to resolve host \"api.open-meteo.com\": No address associated with hostname"
        val failing = snapshot.copy(status = mapOf(Source.ICON_CH1 to SourceStatus.Failed(reason, t0)))
        var opened: Source? = null
        var detail by mutableStateOf<SourceDetailState?>(null)
        rule.setContent {
            ApexTheme {
                CompareContent(
                    state, {}, {},
                    onOpenSource = { opened = it; detail = SourceDetailStateBuilder.build(it, failing, null, t0) },
                    detail = detail, meta = SourceMetaUi.Unavailable, onCloseSource = { detail = null },
                )
            }
        }
        scrollTo("status_row_ICON_CH1")
        rule.onNodeWithTag("status_row_ICON_CH1").performClick()
        assertEquals(Source.ICON_CH1, opened)
        rule.onNodeWithTag("source_detail_error").performScrollTo().assertIsDisplayed().assertTextEquals(reason)
        rule.onNodeWithTag("source_detail_close").performScrollTo().performClick()
        rule.onNodeWithTag("source_detail_sheet").assertDoesNotExist()
    }
```

and add these imports to the file:

```kotlin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performScrollTo
import it.apexweather.domain.model.SourceStatus
```

Create `app/src/test/kotlin/it/apexweather/ui/screenshot/SourceDetailScreenshotTest.kt`:

```kotlin
package it.apexweather.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import it.apexweather.data.remote.SourceMeta
import it.apexweather.domain.DORF_TIROL
import it.apexweather.domain.DayPart
import it.apexweather.domain.LeadBucket
import it.apexweather.domain.ModelBias
import it.apexweather.domain.forecast
import it.apexweather.domain.hour
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceStatus
import it.apexweather.domain.model.WeatherSnapshot
import it.apexweather.domain.point
import it.apexweather.ui.common.Formats
import it.apexweather.ui.common.LocalFormats
import it.apexweather.ui.compare.SourceDetailSheet
import it.apexweather.ui.compare.SourceDetailStateBuilder
import it.apexweather.ui.compare.SourceMetaUi
import it.apexweather.ui.theme.ApexTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.util.Locale

/** The source sheet as the reader first sees it. Look at the PNG after recording. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w400dp-h1400dp-xhdpi")
class SourceDetailScreenshotTest {

    @Test
    fun `ICON-CH1 at Dorf Tirol`() {
        val icons = listOf(Source.ICON_CH1, Source.ICON_CH2, Source.ICON_2I, Source.ICON_D2, Source.GEOSPHERE_AROME)
        val snapshot = WeatherSnapshot.EMPTY.copy(
            forecasts = icons.associateWith { s -> forecast(s, (0 until 34).map { point(it, 12.0) }) },
            status = icons.associateWith { SourceStatus.Ok(hour(1)) },
            modelBias = ModelBias(mapOf(Source.ICON_CH1 to mapOf(
                DayPart.MORNING to mapOf(LeadBucket.SIX to 0.2),
                DayPart.AFTERNOON to mapOf(LeadBucket.SIX to 1.4),
                DayPart.EVENING to mapOf(LeadBucket.SIX to -0.04),
            ))),
        )
        val state = SourceDetailStateBuilder.build(Source.ICON_CH1, snapshot, DORF_TIROL, hour(3))
        val meta = SourceMetaUi.Loaded(SourceMeta(hour(0), hour(1), Duration.ofHours(3)))
        captureRoboImage("src/test/screenshots/source_detail_icon_ch1.png") {
            ApexTheme {
                CompositionLocalProvider(LocalFormats provides Formats(Locale.GERMANY, true)) {
                    Box(Modifier.background(Color(0xFF14213A)).width(400.dp)) {
                        SourceDetailSheet(state, meta, onClose = {})
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c "./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.screenshot.SourceDetailScreenshotTest' -q"`
Expected: FAIL, compilation error `Unresolved reference 'SourceDetailSheet'`.

- [ ] **Step 4: Write the sheet**

`app/src/main/kotlin/it/apexweather/ui/compare/SourceDetailSheet.kt`:

```kotlin
package it.apexweather.ui.compare

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import it.apexweather.R
import it.apexweather.domain.DayPart
import it.apexweather.domain.Delivery
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.model.SourceStatus
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.Formats
import it.apexweather.ui.common.LocalFormats

/**
 * One source, in four blocks: what it is, whether it is working, how much it counts, and how wrong
 * it has been at the station.
 *
 * Everything but the run line is already in the app and shows at once. The sheet scrolls and skips
 * its half state, so it carries a cross (see CLAUDE.md on bottom sheets).
 */
@Composable
fun SourceDetailSheet(state: SourceDetailState, meta: SourceMetaUi, onClose: () -> Unit) {
    val formats = LocalFormats.current
    val uri = LocalUriHandler.current
    val info = state.info
    val provider = info.provider ?: stringResource(R.string.source_provider_province)
    Column(
        Modifier.verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 32.dp)
            .testTag("source_detail"),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.source.displayName,
                style = MaterialTheme.typography.headlineMedium, color = Color.White,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            IconButton(onClick = onClose, modifier = Modifier.testTag("source_detail_close")) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = Color.White.copy(alpha = 0.8f))
            }
        }
        Text(provider, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))

        Block(R.string.source_block_what) {
            Line(stringResource(R.string.source_family, state.source.family.name))
            Line(
                when (val km = info.gridKm) {
                    null -> stringResource(R.string.source_grid_municipality)
                    else -> stringResource(if (state.source.regional) R.string.source_grid_regional else R.string.source_grid_global, kilometres(km, formats))
                },
            )
            Line(
                stringResource(
                    when (info.delivery) {
                        Delivery.OPEN_METEO -> R.string.source_delivery_open_meteo
                        Delivery.GEOSPHERE -> R.string.source_delivery_geosphere
                        Delivery.SIAG -> R.string.source_delivery_siag
                    },
                ),
            )
            Line(stringResource(R.string.source_licence, info.licence))
        }

        Block(R.string.source_block_state) {
            Text(
                statusText(state.source, state.status, state.now, formats),
                style = MaterialTheme.typography.bodyMedium, color = statusColor(state.status),
            )
            (state.status as? SourceStatus.Failed)?.let { failed ->
                SelectionContainer {
                    Text(
                        failed.reason,
                        style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(top = 4.dp).testTag("source_detail_error"),
                    )
                }
                failed.lastIssuedAt?.let { Line(stringResource(R.string.source_last_good, Format.timestamp(it, SouthTyrol.ZONE, state.now, formats))) }
            }
            state.reachUntil?.let { Line(stringResource(R.string.source_reach, Format.dayTime(it, SouthTyrol.ZONE, state.now, formats))) }
            MetaLines(meta, state, formats)
            Line(stringResource(R.string.source_stale_after, state.staleAfterHours), dim = true)
        }

        Block(R.string.source_block_share) {
            state.weight?.let { w ->
                Line(
                    if (state.familySize > 1) stringResource(R.string.source_weight_shared, formats.oneDecimal(w), state.familySize, state.source.family.name)
                    else stringResource(R.string.source_weight_alone, state.source.family.name),
                )
            }
            Line(stringResource(if (state.inConsensus) R.string.source_in_consensus else R.string.source_not_in_consensus))
            if (!state.source.regional) Line(stringResource(R.string.source_fills_gaps), dim = true)
        }

        when (val station = state.station) {
            StationBlock.NoStation -> Unit
            StationBlock.NeverCheckable -> Block(R.string.source_block_station) {
                Line(stringResource(R.string.compare_never_checkable), dim = true)
            }
            is StationBlock.Checked -> Block(R.string.source_block_station) {
                Line(stringResource(R.string.source_station, station.stationName), dim = true)
                station.cells.forEach { cell ->
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(stringResource(cell.part.labelRes()), style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.weight(1f))
                        Text(
                            cell.kelvin?.let { Format.kelvinDelta(it, formats) } ?: stringResource(R.string.source_bias_too_few),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (cell.kelvin == null) Color.White.copy(alpha = 0.55f) else Color.White,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = { uri.openUri(info.website) }, modifier = Modifier.fillMaxWidth().testTag("source_detail_website")) {
            Text(stringResource(R.string.source_website, provider))
        }
    }
}

@Composable
private fun MetaLines(meta: SourceMetaUi, state: SourceDetailState, formats: Formats) {
    when (meta) {
        SourceMetaUi.NotApplicable -> Unit
        SourceMetaUi.Loading -> Line(stringResource(R.string.source_run_loading), dim = true)
        SourceMetaUi.Unavailable -> Line(stringResource(R.string.source_run_unavailable), dim = true)
        is SourceMetaUi.Loaded -> {
            // A provider's clock and the phone's are not the same clock; a run stamped a minute ahead
            // is shown as now rather than in the future.
            fun stamp(t: java.time.Instant) = Format.timestamp(minOf(t, state.now), SouthTyrol.ZONE, state.now, formats)
            val run = meta.meta.runStartedAt
            val published = meta.meta.publishedAt
            when {
                run != null && published != null -> Line(stringResource(R.string.source_run_published, stamp(run), stamp(published)))
                run != null -> Line(stringResource(R.string.source_run, stamp(run)))
            }
            meta.meta.updateEvery?.let { Line(stringResource(R.string.source_update_every, Format.shortDuration(it, formats))) }
        }
    }
}

@Composable
private fun Block(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(20.dp))
    Text(
        stringResource(title),
        style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f),
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(6.dp))
    Column(content = content)
}

@Composable
private fun Line(text: String, dim: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = if (dim) 0.65f else 0.9f),
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** Whole kilometres without a decimal ("2"), the rest with one ("2,5"). */
private fun kilometres(km: Double, f: Formats): String =
    if (km % 1.0 == 0.0) f.whole(km.toInt()) else f.oneDecimal(km)

private fun DayPart.labelRes(): Int = when (this) {
    DayPart.NIGHT -> R.string.source_part_night
    DayPart.MORNING -> R.string.source_part_morning
    DayPart.AFTERNOON -> R.string.source_part_afternoon
    DayPart.EVENING -> R.string.source_part_evening
}
```

`Formats.oneDecimal` and `Formats.whole` are `internal` in the same module, so they are reachable here.

- [ ] **Step 5: Make the rows tappable and draw the sheet**

In `app/src/main/kotlin/it/apexweather/ui/compare/CompareScreen.kt`:

1. Change `private fun statusText(` to `internal fun statusText(` and `private fun statusColor(` to `internal fun statusColor(`.

2. Add imports:

```kotlin
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.semantics.Role
```

3. Replace `CompareScreen` with:

```kotlin
@Composable
fun CompareScreen(viewModel: CompareViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val meta by viewModel.meta.collectAsStateWithLifecycle()
    CompareContent(
        state, viewModel::toggleSource, viewModel::setVariable, viewModel::setDay,
        onOpenSource = viewModel::openSource, detail = detail, meta = meta, onCloseSource = viewModel::closeSource,
    )
}
```

4. Change the `CompareContent` signature to:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareContent(
    state: CompareUiState,
    onToggleSource: (Source) -> Unit,
    onVariable: (CompareVariable) -> Unit,
    onDay: (DaySelection) -> Unit = {},
    onOpenSource: (Source) -> Unit = {},
    detail: SourceDetailState? = null,
    meta: SourceMetaUi = SourceMetaUi.NotApplicable,
    onCloseSource: () -> Unit = {},
) {
```

5. Replace the status row inside `Source.entries.forEach { s -> … }` in the `status_list` card with:

```kotlin
                Source.entries.forEach { s ->
                    val st = state.statuses[s]
                    val status = statusText(s, st, state.now, formats)
                    val label = stringResource(R.string.source_detail_open, s.displayName, status)
                    Row(
                        Modifier.fillMaxWidth()
                            // Tappable because this is where "what is this model, and why is it red"
                            // gets asked; the sheet answers it. The row reads as one button.
                            .clickable(role = Role.Button) { onOpenSource(s) }
                            .semantics(mergeDescendants = true) { contentDescription = label }
                            .padding(vertical = 8.dp)
                            .testTag("status_row_${s.name}"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(SourceColors.of(s)))
                            Text(s.displayName, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                        }
                        Text(status, style = MaterialTheme.typography.labelSmall, color = statusColor(st))
                    }
                }
```

6. After the closing brace of the `LazyColumn { … }` call and before the closing brace of `CompareContent`, add:

```kotlin
    if (detail != null) {
        ModalBottomSheet(
            onDismissRequest = onCloseSource,
            // Full height: four blocks run past half a phone, and at a large font scale past a whole
            // one. The sheet scrolls, so it carries a cross (CLAUDE.md, bottom sheets).
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.testTag("source_detail_sheet"),
        ) {
            SourceDetailSheet(detail, meta, onCloseSource)
        }
    }
```

- [ ] **Step 6: Wire the ViewModel**

In `app/src/main/kotlin/it/apexweather/ui/compare/CompareViewModel.kt`:

1. Add imports:

```kotlin
import it.apexweather.data.SourceMetaRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
```

2. Change the constructor to:

```kotlin
class CompareViewModel @Inject constructor(
    holder: WeatherStateHolder,
    private val settingsRepository: SettingsRepository,
    private val savedState: SavedStateHandle,
    private val metaRepository: SourceMetaRepository,
) : ViewModel() {
```

3. Directly after the `val state: StateFlow<CompareUiState> = …stateIn(…)` declaration, add:

```kotlin
    /** Which source's sheet is open, by name, so it survives process death like the day does. */
    private val openSourceName: StateFlow<String?> = savedState.getStateFlow<String?>(SOURCE_KEY, null)

    /**
     * The open sheet, rebuilt from the same weather flow as the screen: a refresh while it is open
     * changes its status and timestamps at once.
     */
    val detail: StateFlow<SourceDetailState?> = combine(holder.weather, openSourceName) { weather, name ->
        sourceNamed(name)?.let { SourceDetailStateBuilder.build(it, weather.snapshot, weather.place, weather.now) }
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _meta = MutableStateFlow<SourceMetaUi>(SourceMetaUi.NotApplicable)
    val meta: StateFlow<SourceMetaUi> = _meta.asStateFlow()
    private var metaJob: Job? = null

    init {
        // A sheet restored after process death asks for its run line again.
        sourceNamed(openSourceName.value)?.let(::fetchMeta)
    }

    fun openSource(source: Source) {
        savedState[SOURCE_KEY] = source.name
        fetchMeta(source)
    }

    fun closeSource() {
        savedState[SOURCE_KEY] = null
        metaJob?.cancel()
        metaJob = null
        _meta.value = SourceMetaUi.NotApplicable
    }

    private fun fetchMeta(source: Source) {
        metaJob?.cancel()
        if (SourceMetaRepository.urlFor(source) == null) {
            _meta.value = SourceMetaUi.NotApplicable
            return
        }
        _meta.value = SourceMetaUi.Loading
        metaJob = viewModelScope.launch {
            _meta.value = metaRepository.metaFor(source)?.let { SourceMetaUi.Loaded(it) } ?: SourceMetaUi.Unavailable
        }
    }
```

4. In the `private companion object`, add:

```kotlin
        const val SOURCE_KEY = "compare_source"

        fun sourceNamed(name: String?): Source? = name?.let { n -> Source.entries.firstOrNull { it.name == n } }
```

- [ ] **Step 7: Record the golden and look at it**

Run: `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c "./gradlew :app:recordRoborazziDebug --tests 'it.apexweather.ui.screenshot.SourceDetailScreenshotTest' -q"`
Expected: `app/src/test/screenshots/source_detail_icon_ch1.png` is written. Open it with the Read tool and check: German text, title "MeteoSwiss ICON-CH1" with a cross, "Modellkern: ICON", "1-km-Gitter, regional", "Lizenz: CC BY 4.0", a green status line, "Zählt 0,5 Stimmen – 4 Modelle teilen den ICON-Kern", station "Meran" with "+1,4 K" afternoon, "+0,2 K" morning, "±0,0 K" evening and "noch zu wenige Stunden" at night, and the website button. Nothing clipped or overlapping. Report what you saw.

- [ ] **Step 8: Run the unit suite, goldens and lint**

Run: `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c "./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintDebug -q"`
Expected: BUILD SUCCESSFUL, no test failures, lint clean.

- [ ] **Step 9: Run the device test on the phone**

Run: `env -i HOME=$HOME PATH=$PATH TERM=dumb ANDROID_SERIAL=RZCXA1ZEXJE bash --noprofile --norc -c "./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.compare.CompareScreenTest -q"`
Expected: BUILD SUCCESSFUL, all `CompareScreenTest` tests pass, including `tappingAStatusRowOpensTheSourceSheetAndTheCrossClosesIt`.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/compare/SourceDetailSheet.kt app/src/main/kotlin/it/apexweather/ui/compare/CompareScreen.kt app/src/main/kotlin/it/apexweather/ui/compare/CompareViewModel.kt app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml app/src/main/res/values-en/strings.xml app/src/androidTest/kotlin/it/apexweather/ui/compare/CompareScreenTest.kt app/src/test/kotlin/it/apexweather/ui/screenshot/SourceDetailScreenshotTest.kt app/src/test/screenshots/source_detail_icon_ch1.png
git commit -m "feat: tapping a source in Quellenstatus opens a sheet about it" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016gqqzcABukZdCYES9zAg21"
```

---

### Task 6: CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: the finished feature.
- Produces: documentation only.

- [ ] **Step 1: Add the bullet**

In `CLAUDE.md`, in the `## Conventions` section, directly after the bullet that begins `- **The compare screen stores which sources are switched *off*, never which are on**`, add:

```markdown
- **Tapping a source in Quellenstatus opens its sheet** (`SourceDetailSheet`, built by the pure
  `SourceDetailStateBuilder`). Four blocks: what it is (`domain/SourceInfo.kt`, every value checked
  against the provider's documentation on 2026-09-15 — change one only with a source for it),
  whether it is working (the **full** error text, which the card cuts at 40 characters), its share of
  the consensus (worked out as the blender does: among the regional runs when two report, otherwise
  among everything in the blend), and its six-hour error per part of the day at the station
  (`LeadBucket.SIX`). The one live fact is the run time, fetched when the sheet opens by
  `SourceMetaRepository` — Open-Meteo's `/data/<dataset>/static/meta.json`, whose dataset name is
  **not** the forecast's model id (`icon_d2` is `dwd_icon_d2`, `gfs_seamless` is read as
  `ncep_gfs013`), and GeoSphere's `/v1/timeseries/…/metadata`. It is kept ten minutes in memory,
  times out after 5 s and changes nothing but that line. **Reach comes from the cached forecast,
  never from `meta.json`**: its `data_end_time` gave UKMO 61 h and ECMWF IFS 147 h on 2026-09-14,
  against 171 h and 336 h on the real call.
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: the source sheet and where its facts come from" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016gqqzcABukZdCYES9zAg21"
```

---

## Verification after all tasks (controller, not a task)

1. `./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintDebug`
2. `ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest` (full suite)
3. `./gradlew :app:assembleRelease && ./tools/release-smoke.sh RZCXA1ZEXJE`
4. On the phone: open Vergleich, tap each of the thirteen sources and screenshot the sheet; once with airplane mode on (run line reads "Laufzeit nicht abrufbar", rest complete); once at font scale 2 (`adb shell settings put system font_scale 2.0`, then back to `1.0`).
5. Release as v0.24.0 when the user agrees.
