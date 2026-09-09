<img src="assets/apexweather-logo.svg" alt="" width="88" align="right">

# Apex Weather

[![CI](https://github.com/13/apexweather/actions/workflows/ci.yml/badge.svg)](https://github.com/13/apexweather/actions/workflows/ci.yml)

An Android weather app for one place: Dorf Tirol near Meran, South Tyrol. Instead of trusting a single
forecast, it fetches seven models plus the provincial weather service and blends them into a consensus —
the median line with the min/max band of the models, so the spread between them is visible rather than
hidden. The home screen shows the current conditions (the live Meran station drives the hero temperature
while its reading is under 90 minutes old), the next 48 hours and the next 7 days, over an animated sky
whose colours follow the real sun position and the forecast condition. A compare tab puts every source on
one chart, labelled with its model run time where the source publishes one and with the fetch time where it
does not, a bulletin tab shows the Landeswetterdienst text and district maps in German, Italian or English,
and a home-screen widget carries the same consensus. Everything is cached in Room, so the app renders
offline and says how old its data is.

## Data sources

- [Landeswetterdienst Südtirol / Servizio Meteo Alto Adige](https://weather.provinz.bz.it/) — KMOS municipality
  forecast, live station Meran, and the provincial bulletin via the
  [Open Data Hub South Tyrol](https://opendatahub.com/) (© Autonome Provinz Bozen – Südtirol)
- [GeoSphere Austria](https://data.hub.geosphere.at/) — AROME nowcast/forecast, [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)
- [Open-Meteo](https://open-meteo.com/) (non-commercial use) for
  [MeteoSwiss](https://www.meteoswiss.admin.ch/) ICON-CH1 and ICON-CH2,
  [ARPAE / ItaliaMeteo](https://www.italiameteo.org/) ICON-2I,
  [DWD](https://www.dwd.de/) ICON-D2 and [ECMWF](https://www.ecmwf.int/) IFS

Weather icons and district maps in the bulletin are served by the Landeswetterdienst and shown with their
copyright notice intact.

## Build and test

```bash
./gradlew :app:assembleDebug                       # debug APK
adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest                   # JVM tests
ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest   # device tests
./gradlew :app:assembleRelease                     # R8 release APK (unsigned)
```

The toolchain is pinned: JDK 21 via `org.gradle.java.home` in `gradle.properties`, the SDK via `sdk.dir` in
`local.properties`. AGP 9 with built-in Kotlin, KSP only. See `CLAUDE.md` for the architecture notes.

## Releases

Pushing a `v*` tag builds the release APK and publishes it as `ApexWeather-<version>.apk`:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

The workflow signs the APK when the repository has the `APEX_KEYSTORE_BASE64`, `APEX_KEYSTORE_PASSWORD`,
`APEX_KEY_ALIAS` and `APEX_KEY_PASSWORD` secrets, and otherwise publishes it with an `-unsigned` suffix.
The version in the tag becomes the `versionName`, and `versionCode` is derived from it (1.2.3 → 10203).
Locally the same override works with `-PapexVersionName=1.0.0 -PapexVersionCode=10000`.

## Logo

The mark is the [Apex Maps](https://github.com/13/apexmaps) A-sharp glyph at the same geometry and
optical size, recoloured for weather: a sunlit peak over its cool blue reflection, on night sky
instead of warm paper.
