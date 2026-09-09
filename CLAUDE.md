# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and test

- Build/install: `./gradlew :app:assembleDebug` then `adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/debug/app-debug.apk`
- JVM tests: `./gradlew :app:testDebugUnitTest` (single class: `--tests 'it.apexweather.domain.ConsensusBlenderTest'`)
- Device tests: `ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest` (single class: `-Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.home.HomeScreenTest`). The serial is required: an emulator is usually attached as well.
- Release build: `./gradlew :app:assembleRelease` (R8 on, unsigned APK; keep rules in `app/proguard-rules.pro`).
- Toolchain is pinned in `gradle.properties` (`org.gradle.java.home` = JDK 21) and `local.properties` (`sdk.dir`); the shell's `ANDROID_HOME` points at an incomplete SDK, ignore it.
- AGP 9 built-in Kotlin: never apply `org.jetbrains.kotlin.android` in `app/build.gradle.kts`; KSP only, no kapt.
- Any Compose test that renders `SkyBackground` (directly or through `MainActivity`) must set `rule.mainClock.autoAdvance = false` and advance the clock by hand — the sky's frame loop never lets the test rule go idle.

## Architecture

Single module, package `it.apexweather`, fixed location Dorf Tirol (constants in `domain/DorfTirol.kt`).

- `domain/` is pure Kotlin. `ConsensusBlender` turns `Map<Source, SourceForecast>` into `ConsensusForecast` (median, min/max band, ECMWF only where < 2 regional sources, majority-vote condition). `DailyAggregator` is the single place hourly → daily happens. `SkyPaletteSelector` + `SunPhaseCalculator` drive the UI colours.
- `data/remote/` has one file per upstream: Open-Meteo (5 models in one call, dynamic JSON keys read via `JsonObject`), GeoSphere AROME (condition derived from cloud/precip/CAPE, precipitation is a diff of the accumulated series), SIAG (KMOS municipality forecast, Open Data Hub bulletin, live station). Each mapper is tested against recorded fixtures in `app/src/test/resources/fixtures/` (recorded from the live services on 2026-09-08); re-record with the curl commands in `docs/superpowers/plans/2026-09-08-apexweather.md` Task 4 when an upstream changes.
- `data/WeatherRepository` fetches all sources in a `supervisorScope`, writes each into Room independently, and keeps old JSON when a source fails (`SourceStatus.Failed` carries `lastIssuedAt`). UI always renders whatever is cached.
- Staleness thresholds live on `Source.staleAfterHours`: regional models 6 h, SIAG KMOS 16 h (two runs a day), ECMWF 12 h; the bulletin goes stale after 24 h and a station observation after 90 min.
- `ui/home/HomeStateBuilder` is the pure function that decides hero values (station observation wins if < 90 min old), palette, and the 48 h / 7 d windows; the widget (`widget/WidgetStateBuilder`) and `SkyViewModel` reuse it.
- ViewModels blend on `Dispatchers.Default` (`flowOn` before `stateIn`), so the consensus never runs on the main thread.
- Background refresh: `work/RefreshWorker` (Hilt worker, hourly, network constraint) → repository → `ApexWidget().updateAll`.

## CI and releases

- `.github/workflows/ci.yml` runs unit tests and assembles debug + release on every push to `main` and
  every PR. It deletes the `org.gradle.java.home` line from `gradle.properties` first, because that path
  is this machine's JDK; the runner supplies its own JDK 21. Instrumented tests are not run on CI (no
  emulator); run them locally against the phone.
- `.github/workflows/release.yml` runs on a `v*` tag and publishes `ApexWeather-<version>.apk` to a GitHub
  release. `base { archivesName = "ApexWeather" }` in `app/build.gradle.kts` is what puts the app name in
  the file; `-PapexVersionName` / `-PapexVersionCode` stamp the tag into the build. Signing is optional and
  driven by `APEX_KEYSTORE_*` env vars (repository secrets on CI, `keystore/keystore.properties` locally);
  without them the release APK is unsigned and the asset gets an `-unsigned` suffix.
- The launcher icon is the Apex Maps A-sharp glyph at the same scale and translate as that app's icon, so
  the two sit at identical optical size; only the palette differs. Source copy: `assets/apexmaps-A-sharp-source.svg`.

## Conventions

- Screens are split into `XScreen` (ViewModel wiring) and `XContent(state, callbacks)`; UI tests drive `XContent` with hand-built states.
- `hiltViewModel` comes from `androidx.hilt.lifecycle.viewmodel.compose` (the navigation-package variant is deprecated).
- Strings live in `values` (German, default), `values-it`, `values-en`; add every new key to all three.
- Source colours are in `ui/common/SourceColors.kt`; SIAG letter codes in `domain/SiagCodes.kt`.
