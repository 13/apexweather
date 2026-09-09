# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and test

- Build/install: `./gradlew :app:assembleDebug` then `adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/debug/ApexWeather-debug.apk`
- JVM tests: `./gradlew :app:testDebugUnitTest` (single class: `--tests 'it.apexweather.domain.ConsensusBlenderTest'`)
- Device tests: `ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest` (single class: `-Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.home.HomeScreenTest`). The serial is required: an emulator is usually attached as well.
- Release build: `./gradlew :app:assembleRelease` (R8 on, unsigned APK; keep rules in `app/proguard-rules.pro`).
- Release on a device: `./tools/release-smoke.sh [serial]` after `assembleRelease`. Installs the
  minified APK, launches it, reads the screen back with `uiautomator dump` and fails on any crash,
  `ClassNotFoundException` or `SerializationException` in the log. The instrumented suite runs
  against the debug build, so this is the only thing that exercises Glance's runtime layout lookup,
  kotlinx-serialization and Hilt's graph *after* R8. It cannot place a widget (APPWIDGET_UPDATE is a
  protected broadcast), so it only checks the provider is still registered.
- Screenshot goldens: `./gradlew :app:verifyRoborazziDebug`; re-record deliberately with
  `:app:recordRoborazziDebug` and look at the PNG before committing it. They cover the hand-drawn
  icon set and the sky palettes, which the mapping tests cannot see.
- Baseline profile: `./gradlew :app:generateReleaseBaselineProfile` with a device attached (API 33+,
  no root needed) writes `app/src/release/generated/baselineProfiles/`. Measured on the phone it took
  cold start from a 450 ms median to 390 ms. `:baselineprofile:connectedBenchmarkReleaseAndroidTest`
  re-measures with and without it.
- Toolchain is pinned in `gradle.properties` (`org.gradle.java.home` = JDK 21) and `local.properties` (`sdk.dir`); the shell's `ANDROID_HOME` points at an incomplete SDK, ignore it.
- AGP 9 built-in Kotlin: never apply `org.jetbrains.kotlin.android` in `app/build.gradle.kts`; KSP only, no kapt.
- Lint runs with `warningsAsErrors`: `./gradlew :app:lintDebug` has to be clean before a push. Three checks are switched off in `app/build.gradle.kts` with the reason beside each; add to that list only with a reason, and never rename `mipmap-anydpi-v26` (aapt2 then cannot find the launcher icon).
- Any Compose test that renders `SkyBackground` (directly or through `MainActivity`) must set `rule.mainClock.autoAdvance = false` and advance the clock by hand — the sky's frame loop never lets the test rule go idle.

## Architecture

Single module, package `it.apexweather`, fixed location Dorf Tirol (constants in `domain/DorfTirol.kt`).

- `domain/` is pure Kotlin. `ConsensusBlender` turns `Map<Source, SourceForecast>` into `ConsensusForecast` (median, min/max band, ECMWF only where < 2 regional sources, majority-vote condition). `DailyAggregator` is the single place hourly → daily happens. `SkyPaletteSelector` + `SunPhaseCalculator` drive the UI colours.
- `data/remote/` has one file per upstream: Open-Meteo (5 models in one call, dynamic JSON keys read
  via `JsonObject`), GeoSphere AROME (condition derived from cloud/precip/CAPE, precipitation is a
  diff of the accumulated series), SIAG (KMOS municipality forecast, Open Data Hub bulletin, live
  station) and MeteoAlarm (the Italian civil-protection Atom feed). Each mapper is tested against
  recorded fixtures in `app/src/test/resources/fixtures/`; re-record with the curl commands in
  `docs/superpowers/plans/2026-09-08-apexweather.md` Task 4 when an upstream changes.
- `MeteoAlarmMapper` parses XML with `javax.xml`, and that is the one mapper a JVM test cannot be
  trusted on: Android's `DocumentBuilderFactory` rejects the two hardening features the JVM's
  accepts and throws the feature name back, which is exactly how warnings failed on a phone while
  the unit test passed. `MeteoAlarmMapperDeviceTest` in `androidTest` is the guard; the instrumented
  source set reads `src/test/resources` so both share the fixture. Italy's smallest published area
  is the whole of Trentino-Alto Adige (`EMMA_ID:IT002`), so a warning is regional, never the
  village's, and the card says so.
- Open-Meteo is called twice: once for the village (14 days, 5 models) and once, tiny, for the
  weather station's coordinates and altitude. The second call exists only so the station's live
  reading can be carried up the hill — see `domain/StationDownscale.kt`.
- `data/WeatherRepository` fetches all sources in a `supervisorScope`, writes each into Room independently, and keeps old JSON when a source fails (`SourceStatus.Failed` carries `lastIssuedAt`). UI always renders whatever is cached.
- Staleness thresholds live on `Source.staleAfterHours`: regional models 6 h, SIAG KMOS 16 h (two runs
  a day), ECMWF 12 h; the bulletin goes stale after 24 h, warnings after 6 h and a station observation
  after 90 min.
- `WeatherSnapshot.forecastsForBlend` is what the blender gets, not `forecasts`: a stale run stays
  visible per source with its age beside it, and is kept out of the number the app leads with. If
  every run is stale they are all used and the offline banner carries the message instead.
- `WeatherRepository.refresh` retries once, after 3 s, and only on `IOException`. The hourly worker
  often calls before the radio has settled, and a dropped connection used to cost a source for a
  whole hour. A malformed payload is not retried.
- `ui/WeatherStateHolder` is the single app-scoped place that subscribes to the cache, runs the
  minute tick and blends the models. Every ViewModel maps off it — Home stamps on its refreshing
  flag, Sky takes the palette, Compare and the bulletin derive from the same inputs — so the seven
  models are blended once per change rather than once per screen. The widget keeps its own one-shot
  path, because it updates when no ViewModel is subscribed.
- `ui/home/HomeStateBuilder` is the pure function that decides hero values, palette, and the 48 h /
  14 d windows; the widget (`widget/WidgetStateBuilder`) and `SkyViewModel` reuse it.
- The hero temperature is the station's reading **only after `StationDownscale` has carried it up**.
  The station is 1.4 km away but 270 m below the village, and the models put the village a median
  1.9 K colder across a two-day run (0.3 K overnight, 2.7 K on a clear afternoon), so the raw reading
  was a systematic warm bias on the app's most-read number. The correction is the models' own
  village-minus-station difference for that hour, not a lapse-rate constant, because the sign flips
  on an inversion night. Where it cannot be computed the consensus wins, since it is already at the
  village's height; the raw reading is the last resort. A moved reading always says so on screen.
- The day list runs 14 days. Only ECMWF reaches past about day five, so those days carry
  `sourceCount == 1` and the badge reads "1 model" instead of an invented agreement percentage.
- `notify/` is the notification feature and is self-contained the way `update/` is.
  `NotificationDecider` is pure and holds every rule (morning summary once a day within four hours of
  its hour, rain starting within three hours and not already falling, each warning once);
  `WeatherNotifier` only turns a decision into a platform notification; `NotifyStore` remembers what
  went out. `RefreshWorker` calls them after each refresh, reading the cache rather than the refresh
  result so an offline hour still behaves. Three channels, all off until switched on.
- ViewModels blend on `Dispatchers.Default` (`flowOn` before `stateIn`), so the consensus never runs on the main thread.
- Background refresh: `work/RefreshWorker` (Hilt worker, hourly, network constraint) → repository → `ApexWidget().updateAll`. `RefreshScheduler.refreshNow` is the one-shot version, used by the settings sheet and the widget's refresh button.
- `update/` is the in-app updater and is deliberately self-contained: it reads GitHub releases,
  verifies the download against the asset's sha256 and hands the APK to `PackageInstaller`. Nothing
  in the weather code imports it — the settings sheet takes it as a slot. Removing the feature means
  deleting that package, the `REQUEST_INSTALL_PACKAGES` line and its receiver in the manifest, and
  one call in `AppNavigation`. Keep it that way: the permission is restricted on the Play Store.
  Compare version *names*, never version codes; the code is derived from the name in awk in
  `release.yml` and must not be recomputed in Kotlin.

## CI and releases

- `.github/workflows/ci.yml` runs unit tests, the screenshot goldens and lint, and assembles debug +
  release on every push to `main` and every PR. It deletes the `org.gradle.java.home` line from `gradle.properties` first,
  because that path is this machine's JDK; the runner supplies its own JDK 21. A second job runs the
  instrumented tests on an API 31 emulator, kept separate so a slow emulator never delays the unit-test
  feedback; running them locally against the phone is still the faster loop.
- `.github/workflows/release.yml` runs on a `v*` tag and publishes `ApexWeather-<version>.apk` to a GitHub
  release. `base { archivesName = "ApexWeather" }` in `app/build.gradle.kts` is what puts the app name in
  the file; `-PapexVersionName` / `-PapexVersionCode` stamp the tag into the build.
- Both build types sign with `tools/debug.keystore` (standard Android debug credentials, committed on
  purpose, copied from Apex Maps) so a debug build updates a sideloaded release build in place. A real key
  overrides it through the `APEX_KEYSTORE_*` env vars or `keystore/keystore.properties`. Do not ship the
  debug-signed APK to a store.
- The launcher icon is the Apex Maps A-sharp glyph at the same scale and translate as that app's icon, so
  the two sit at identical optical size; only the palette differs. Source copy: `assets/apexmaps-A-sharp-source.svg`.

## Conventions

- Screens are split into `XScreen` (ViewModel wiring) and `XContent(state, callbacks)`; UI tests drive `XContent` with hand-built states.
- Everything that opens a bottom-bar destination goes through `NavHostController.openTopLevel` in
  `ui/navigation/AppNavigation.kt`. The bulletin has two entrances, its tab and the teaser card on
  home; when the card used a plain `navigate` the home tab could no longer bring itself back.
  `TopLevelNavigationTest` drives the real routes with stub screens and guards this.
- `hiltViewModel` comes from `androidx.hilt.lifecycle.viewmodel.compose` (the navigation-package variant is deprecated).
- Strings live in `values` (German, default), `values-it`, `values-en`; add every new key to all three.
- Source colours are in `ui/common/SourceColors.kt`; SIAG letter codes in `domain/SiagCodes.kt`.
- Weather icons are hand-drawn vectors in `res/drawable/ic_wx_*.xml`, mapped once in
  `ui/common/WeatherIcons.kt` and used by both the app and the widget. Every condition has its own
  drawing and `WeatherIconsTest` asserts it; do not reintroduce a second table. What the vectors
  actually paint is pinned by the Roborazzi goldens in `app/src/test/screenshots/`, because an edited
  path keeps its resource id and every mapping test keeps passing.
- Warnings are written and coloured in `ui/common/WarningVisuals.kt`. The feed's own wording is
  English only, so it is never shown as a label; type and level are translated like everything else,
  and each has a `labelRes()` form as well as a composable one because the widget renders outside a
  composition. They reuse the one warning glyph — the level is what a reader takes in first and the
  colour carries it.
- Numbers, dates and times go through `ui/common/Format.kt`, which takes an explicit `Formats`
  (locale plus the 24-hour flag). Inside a composition take it from `LocalFormats.current`; outside
  one, build it from a `Context`. Never format with `Locale.ROOT` or interpolate a number into a
  string.
