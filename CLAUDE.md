# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and test

- Build/install: `./gradlew :app:assembleDebug` then `adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/debug/ApexWeather-debug.apk`
- JVM tests: `./gradlew :app:testDebugUnitTest` (single class: `--tests 'it.apexweather.domain.ConsensusBlenderTest'`)
- Device tests: `ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest` (single class: `-Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.home.HomeScreenTest`). The serial is required: an emulator is usually attached as well.
- Release build: `./gradlew :app:assembleRelease` (R8 on, unsigned APK; keep rules in `app/proguard-rules.pro`).
- Toolchain is pinned in `gradle.properties` (`org.gradle.java.home` = JDK 21) and `local.properties` (`sdk.dir`); the shell's `ANDROID_HOME` points at an incomplete SDK, ignore it.
- AGP 9 built-in Kotlin: never apply `org.jetbrains.kotlin.android` in `app/build.gradle.kts`; KSP only, no kapt.
- Lint runs with `warningsAsErrors`: `./gradlew :app:lintDebug` has to be clean before a push. Three checks are switched off in `app/build.gradle.kts` with the reason beside each; add to that list only with a reason, and never rename `mipmap-anydpi-v26` (aapt2 then cannot find the launcher icon).
- Any Compose test that renders `SkyBackground` (directly or through `MainActivity`) must set `rule.mainClock.autoAdvance = false` and advance the clock by hand — the sky's frame loop never lets the test rule go idle.

## Architecture

Single module, package `it.apexweather`, fixed location Dorf Tirol (constants in `domain/DorfTirol.kt`).

- `domain/` is pure Kotlin. `ConsensusBlender` turns `Map<Source, SourceForecast>` into `ConsensusForecast` (median, min/max band, ECMWF only where < 2 regional sources, majority-vote condition). `DailyAggregator` is the single place hourly → daily happens. `SkyPaletteSelector` + `SunPhaseCalculator` drive the UI colours.
- `data/remote/` has one file per upstream: Open-Meteo (5 models in one call, dynamic JSON keys read via `JsonObject`), GeoSphere AROME (condition derived from cloud/precip/CAPE, precipitation is a diff of the accumulated series), SIAG (KMOS municipality forecast, Open Data Hub bulletin, live station). Each mapper is tested against recorded fixtures in `app/src/test/resources/fixtures/` (recorded from the live services on 2026-09-08); re-record with the curl commands in `docs/superpowers/plans/2026-09-08-apexweather.md` Task 4 when an upstream changes.
- `data/WeatherRepository` fetches all sources in a `supervisorScope`, writes each into Room independently, and keeps old JSON when a source fails (`SourceStatus.Failed` carries `lastIssuedAt`). UI always renders whatever is cached.
- Staleness thresholds live on `Source.staleAfterHours`: regional models 6 h, SIAG KMOS 16 h (two runs a day), ECMWF 12 h; the bulletin goes stale after 24 h and a station observation after 90 min.
- `ui/WeatherStateHolder` is the single app-scoped place that subscribes to the cache, runs the
  minute tick and blends the models. Every ViewModel maps off it — Home stamps on its refreshing
  flag, Sky takes the palette, Compare and the bulletin derive from the same inputs — so the seven
  models are blended once per change rather than once per screen. The widget keeps its own one-shot
  path, because it updates when no ViewModel is subscribed.
- `ui/home/HomeStateBuilder` is the pure function that decides hero values (station observation wins if < 90 min old), palette, and the 48 h / 7 d windows; the widget (`widget/WidgetStateBuilder`) and `SkyViewModel` reuse it.
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

- `.github/workflows/ci.yml` runs unit tests and assembles debug + release on every push to `main` and
  every PR, and runs lint. It deletes the `org.gradle.java.home` line from `gradle.properties` first,
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
- `hiltViewModel` comes from `androidx.hilt.lifecycle.viewmodel.compose` (the navigation-package variant is deprecated).
- Strings live in `values` (German, default), `values-it`, `values-en`; add every new key to all three.
- Source colours are in `ui/common/SourceColors.kt`; SIAG letter codes in `domain/SiagCodes.kt`.
- Weather icons are hand-drawn vectors in `res/drawable/ic_wx_*.xml`, mapped once in
  `ui/common/WeatherIcons.kt` and used by both the app and the widget. Every condition has its own
  drawing and `WeatherIconsTest` asserts it; do not reintroduce a second table.
- Numbers, dates and times go through `ui/common/Format.kt`, which takes an explicit `Formats`
  (locale plus the 24-hour flag). Inside a composition take it from `LocalFormats.current`; outside
  one, build it from a `Context`. Never format with `Locale.ROOT` or interpolate a number into a
  string.
