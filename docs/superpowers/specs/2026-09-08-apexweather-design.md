# Apex Weather — Design Spec

Date: 2026-09-08
Status: approved design, pending implementation plan

## 1. Goal

A native Android weather app, "Apex Weather" (package `it.apexweather`), for a single fixed
location: Dorf Tirol / Meran, South Tyrol (46.691 N, 11.155 E, ~600 m). It combines several
regional forecast sources into one consensus forecast, shows the official South Tyrol bulletin,
lets the user compare sources, and offers a home-screen widget. Visual priority is high: a
dynamic, animated sky background drives the whole look.

Out of scope for v1: other locations, GPS, notifications/alerts, weather radar, iOS.

## 2. Decisions (from brainstorming)

| Topic | Decision |
|---|---|
| Location | Dorf Tirol only, hardcoded |
| Content | Now + 48 h hourly + 7 d daily; per-source comparison; official bulletin; widget |
| Stack | Kotlin, Jetpack Compose, Material 3, Hilt, Retrofit + OkHttp + kotlinx.serialization, Room, WorkManager, Glance |
| Min SDK | 31 (Android 12); target/compile SDK latest stable available locally (36) |
| Languages | German (default), Italian, English via `values-*` resources; bulletin fetched in app language |
| Visual | Dynamic sky backgrounds (animated gradients + particles), dark-first, frosted cards |
| Blending | Consensus median + min/max spread band |
| Data | Room cache, WorkManager hourly refresh, app renders cached data instantly |
| Tests | JVM unit tests (parsers, blender, palette) + Compose UI tests |
| Architecture | Single Gradle module, layered packages (approach A) |

## 3. Data sources

All free, no API key, verified live on 2026-09-08 for the Dorf Tirol coordinates.

### 3.1 Landeswetterdienst Südtirol (SIAG) via Open Data Hub

- Bulletin: `GET https://tourism.opendatahub.com/v1/Weather?language={de|it|en}`
  Fields used: `Date`, `EvolutionTitle`, `Evolution`, `Conditions[]` (`date`, `Weatherdesc`,
  `WeatherImgurl`, temps where present), `Forecast[]`/district block for district 2
  ("Burggrafenamt - Meran und Umgebung") when present.
- Live station: `GET https://mobility.api.opendatahub.com/v2/flat/MeteoStation/*/latest?where=scode.eq.23200MS`
  Station "Merano" (23200MS). Fields: air temperature, relative humidity, wind speed, precipitation,
  pressure if published. Exact type names are confirmed against the live response during
  implementation and recorded as fixtures.
- Role: official narrative text and day icons; live "now" observation. Not blended numerically.

### 3.2 GeoSphere Austria (formerly ZAMG), AROME 2.5 km

- `GET https://dataset.api.hub.geosphere.at/v1/timeseries/forecast/nwp-v1-1h-2500m?lat_lon=46.691,11.155&parameters=...`
- Parameters: 2 m temperature, accumulated precipitation, 2 m relative humidity, 10 m wind u/v
  (converted to speed/direction), total cloud cover, weather symbol, gusts if available. The
  parameter codes are taken from the dataset's `/metadata` endpoint at implementation time.
- Horizon 60 h hourly, updated every 3 h. Licence CC-BY 4.0 (attribution shown in app).

### 3.3 Open-Meteo multi-model (single request)

- `GET https://api.open-meteo.com/v1/forecast?latitude=46.691&longitude=11.155&timezone=Europe/Rome&forecast_days=7`
  `&models=meteoswiss_icon_ch1,meteoswiss_icon_ch2,italia_meteo_arpae_icon_2i,icon_d2,ecmwf_ifs025`
  `&hourly=temperature_2m,apparent_temperature,precipitation,precipitation_probability,weather_code,cloud_cover,relative_humidity_2m,wind_speed_10m,wind_gusts_10m,wind_direction_10m`
  `&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code,sunrise,sunset`
- Delivers MeteoSwiss ICON-CH1 (1 km) and ICON-CH2 (2 km), Italian ICON-2I, DWD ICON-D2, and
  ECMWF IFS 0.25° as separate series in one JSON. Open-Meteo is used as transport; the sources are
  credited by model name in the UI. Non-commercial use, attribution shown in app.

### 3.4 Source enum

`SIAG`, `GEOSPHERE_AROME`, `ICON_CH1`, `ICON_CH2`, `ICON_2I`, `ICON_D2`, `ECMWF`.
Regional set = all except `SIAG` and `ECMWF`.

## 4. Architecture

Single Android application module `app`, package `it.apexweather`. Layers as packages:

```
it.apexweather
├── data
│   ├── remote        SiagApi, GeoSphereApi, OpenMeteoApi (Retrofit) + DTOs + mappers
│   ├── local         AppDatabase (Room), DAOs, entities
│   └── WeatherRepository
├── domain
│   ├── model         Source, SourceForecast, HourlyPoint, DailyPoint, Condition,
│   │                 Bulletin, StationObservation, WeatherSnapshot, ConsensusForecast
│   └── ConsensusBlender, SunPhaseCalculator, SkyPaletteSelector
├── ui
│   ├── theme         colors, type, shapes
│   ├── sky           SkyBackground, particle systems, palettes
│   ├── home          HomeScreen, HomeViewModel, hourly strip, daily list, hero
│   ├── compare       CompareScreen, CompareViewModel, multi-line chart, table
│   ├── bulletin      BulletinScreen, BulletinViewModel
│   ├── settings      SettingsSheet, SettingsRepository (DataStore)
│   └── navigation    single NavHost, bottom bar
├── widget            ApexWidget (Glance), ApexWidgetReceiver, widget state mapper
├── work              RefreshWorker, RefreshScheduler
└── di                Hilt modules (network, database, repository, work)
```

Dependency direction: `ui`/`widget`/`work` → `domain` + `data`; `data` → `domain.model`;
`domain` has no Android dependencies (pure Kotlin, JVM-testable).

### 4.1 Domain model

```kotlin
enum class Source(val displayName: String, val regional: Boolean)

enum class Condition { CLEAR, MOSTLY_CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN,
                       HEAVY_RAIN, SLEET, SNOW, HEAVY_SNOW, THUNDERSTORM }
// severity order for tie-breaking = declaration order

data class HourlyPoint(
  val time: Instant, val tempC: Double, val feelsLikeC: Double?, val precipMm: Double,
  val precipProb: Int?, val windKmh: Double, val gustKmh: Double?, val windDirDeg: Int?,
  val cloudPct: Int?, val humidityPct: Int?, val condition: Condition)

data class DailyPoint(val date: LocalDate, val minC: Double, val maxC: Double,
  val precipMm: Double, val condition: Condition, val sunrise: Instant?, val sunset: Instant?)

data class SourceForecast(val source: Source, val issuedAt: Instant, val fetchedAt: Instant,
  val hourly: List<HourlyPoint>, val daily: List<DailyPoint>)

data class Bulletin(val language: String, val issuedAt: Instant, val title: String,
  val evolution: String, val days: List<BulletinDay>)
data class BulletinDay(val date: LocalDate, val description: String, val imageUrl: String?,
  val minC: Double?, val maxC: Double?)

data class StationObservation(val time: Instant, val tempC: Double?, val humidityPct: Int?,
  val windKmh: Double?, val precipMm: Double?, val pressureHpa: Double?)

sealed interface SourceStatus { data class Ok(val issuedAt: Instant); data class Stale(val issuedAt: Instant);
                                data class Failed(val reason: String, val lastIssuedAt: Instant?) }

data class WeatherSnapshot(val forecasts: Map<Source, SourceForecast>, val bulletin: Bulletin?,
  val observation: StationObservation?, val status: Map<Source, SourceStatus>, val updatedAt: Instant?)

data class ConsensusHour(val time: Instant, val tempC: Double, val tempMinC: Double, val tempMaxC: Double,
  val precipMm: Double, val precipProb: Int, val windKmh: Double, val gustKmh: Double?,
  val condition: Condition, val agreement: Float /* 0..1 */, val sourceCount: Int)
data class ConsensusDay(val date: LocalDate, val minC: Double, val maxC: Double, val precipMm: Double,
  val condition: Condition, val agreement: Float, val sunrise: Instant?, val sunset: Instant?)
data class ConsensusForecast(val hourly: List<ConsensusHour>, val daily: List<ConsensusDay>)
```

### 4.2 Repository

`WeatherRepository` exposes `fun snapshot(): Flow<WeatherSnapshot>` (Room-backed, emits on every
change) and `suspend fun refresh(language: String): RefreshResult`. `refresh` launches the three
network calls in a `supervisorScope`; each result is written to Room independently, and a failure
in one source only marks that source `Failed`, keeping its last cached data. Sources whose
`issuedAt` is older than 6 h (regional) or 12 h (ECMWF) are marked `Stale`.

Room entities store one row per source with the mapped domain data serialised as JSON (single
`TEXT` column) plus `issuedAt`, `fetchedAt`, `status`. Bulletin and observation each get one row.
Schema versioning uses Room auto-migrations; v1 has no migrations.

### 4.3 ConsensusBlender (pure Kotlin)

Input: `Map<Source, SourceForecast>`. Output: `ConsensusForecast`.

Per hourly timestamp (union of all timestamps, aligned to full hours in Europe/Rome):
1. Collect values from regional sources present at that hour. If fewer than 2 regional sources
   have a value, also include ECMWF.
2. Temperature: median; band = min..max. Wind: median. Gust: max.
3. Precipitation mm: median. Probability: max of model probabilities; if no model provides one,
   `round(100 * count(precipMm > 0.1) / count)`.
4. Condition: majority vote; ties resolved by the more severe condition.
5. `agreement = 1 - clamp(tempSpread / 6.0, 0, 1)` where `tempSpread = max - min` (6 °C spread
   or more counts as zero agreement). With a single source `agreement = 0.5` and no band.
6. `sourceCount` = number of contributing sources.

Daily: aggregated from consensus hourly (min/max temp, precip sum, condition = worst condition
during 06:00–22:00 local, agreement = mean). Sunrise/sunset from any Open-Meteo daily series.

SIAG is never blended numerically. The station observation is not part of the consensus; it is
shown as "now" and overrides the current-hour hero values.

### 4.4 Sky system

`SunPhaseCalculator(now, sunrise, sunset) -> NIGHT | DAWN | DAY | DUSK` (dawn/dusk = ±45 min
around sunrise/sunset).

`SkyPaletteSelector(condition, sunPhase) -> SkyPalette` (top colour, bottom colour, accent,
particle kind, particle density). Twelve base palettes: clear/mostly-clear × {day, night, dawn,
dusk}, cloudy × {day, night}, fog, rain, heavy rain/thunder, snow. Precip density scaled by
consensus `precipMm` of the current hour.

`SkyBackground` composable: full-screen vertical gradient with `animateColorAsState` (1.5 s)
crossfade; particle layer on `Canvas` driven by `withFrameNanos`, pausing when the lifecycle is
not `RESUMED`; a static valley ridge silhouette path at the bottom tinted with the palette;
lightning flash overlay for thunderstorm. Honors `Settings.Global.ANIMATOR_DURATION_SCALE == 0`
and the accessibility "remove animations" setting by disabling particles.

Cards use a translucent surface with a `RenderEffect` blur (API 31+) to read as frosted glass.

## 5. Screens

Single `MainActivity`, Compose `NavHost`, bottom navigation with three destinations. Settings is a
modal bottom sheet from the top app bar.

### 5.1 Home ("Heute")
- Hero: large temperature numeral (station observation if fresher than 90 min, else consensus
  current hour), condition word, feels-like, "Dorf Tirol", last update time, agreement badge
  ("±2°" = half the current band width).
- Hourly section: temperature curve with translucent min/max band, then a horizontal 48 h strip
  (icon, temp, precipitation bar with probability). Tapping an hour opens a detail bottom sheet
  with per-source values for that hour.
- Daily section: 7 rows with icon, min/max range bar on a shared scale, precipitation mm,
  agreement dot.
- Bulletin teaser card: title and first sentence of the SIAG evolution text; tap navigates to
  Bulletin.
- Pull-to-refresh triggers `refresh()`; staggered card entrance animation on first composition.
- Attribution footer: "Daten: Landeswetterdienst Südtirol, GeoSphere Austria (CC BY 4.0),
  MeteoSwiss, DWD, ARPAE, ECMWF via Open-Meteo".

### 5.2 Compare ("Vergleich")
- Line chart (custom Canvas): one line per source, consensus as thick high-contrast line, band
  shaded. Legend chips toggle sources; selection persisted in DataStore.
- Variable selector: temperature / precipitation / wind.
- Day table: rows per day, columns per source (min/max, precip), cell tint by deviation from
  consensus.
- Source status list: issued time, fetched time, status (ok/stale/failed with reason).

### 5.3 Bulletin ("Bericht")
- SIAG evolution title and text, day cards from `BulletinDay`, map image carousel (Coil).
- Language follows app language; changing language triggers a refresh of the bulletin only.

### 5.4 Settings sheet
- Language (system / de / it / en), units (°C only in v1; wind km/h or m/s), animations on/off,
  "refresh now", about/attribution.

## 6. Widget and background refresh

- Glance `ApexWidget` with two size classes: small (2×1: icon, temperature, "Dorf Tirol") and
  medium (4×2: current + next 6 hours). Reads the Room cache through the repository; never
  fetches. Background is a static gradient from the current `SkyPalette`. Tap opens the app.
- `RefreshWorker` (Hilt-injected `CoroutineWorker`): periodic every 60 min, `NETWORK_CONNECTED`
  constraint, exponential backoff. On success calls `ApexWidget().updateAll(context)`.
- `RefreshScheduler.ensureScheduled()` on app start (`ExistingPeriodicWorkPolicy.KEEP`).
- On app open, if `updatedAt` is older than 30 min, the ViewModel triggers an immediate
  in-process `refresh()`.

## 7. Error handling and states

- Home renders whenever any source or bulletin is cached; per-source failures appear only as
  status on the Compare screen and as a subtle banner ("Daten von 14:00 · offline") when the
  latest refresh failed entirely.
- Empty state (no cache ever): full-screen sky with message and retry button.
- Degraded consensus: one source shows without band; zero numeric sources but bulletin present
  shows bulletin-only mode on Home (hero from observation or bulletin day values).
- Network: 15 s connect/read timeouts, no in-process retries; WorkManager handles retry.
- Parsing: each source parser is tolerant of missing optional fields; a missing required series
  (e.g. temperature) marks that source `Failed("missing temperature")`.

## 8. Testing

- JVM unit tests (`src/test`):
  - Each DTO mapper against recorded JSON fixtures captured from the real endpoints on
    2026-09-08 (stored under `src/test/resources/fixtures/`).
  - `ConsensusBlender`: median/band, ECMWF inclusion rule, precipitation probability fallback,
    condition vote and tie-break, daily aggregation, single-source and empty inputs.
  - `SunPhaseCalculator` and `SkyPaletteSelector`.
  - Repository with in-memory Room (Robolectric) and fake APIs: partial failure keeps cached data.
- Compose UI tests (`src/androidTest`): Home renders with a fake repository; hourly strip scrolls
  and opens the detail sheet; Compare source chips toggle lines; offline banner and empty state.
- Commands: `./gradlew test`, `./gradlew connectedAndroidTest` (Samsung A34, API 36, attached).

## 9. Build environment notes

- Android SDK at `/home/ben/Android/Sdk` (platforms 33–37, build-tools 36.1.0). `ANDROID_HOME`
  currently points to `/opt/android-sdk` which only contains the emulator; `local.properties`
  sets `sdk.dir=/home/ben/Android/Sdk`.
- JDK: use `/usr/lib/jvm/java-21-openjdk` (`org.gradle.java.home` in `gradle.properties`); the
  system default JDK 25 is not supported by AGP.
- Gradle wrapper pinned to a version compatible with the chosen AGP; Kotlin 2.x with the Compose
  compiler Gradle plugin; version catalog in `gradle/libs.versions.toml`.
