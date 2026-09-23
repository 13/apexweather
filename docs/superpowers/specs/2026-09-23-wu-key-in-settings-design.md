# The Weather Underground key belongs to the reader — design

2026-09-23

## What this is

The amateur-station feature shipped in v0.29.1 with its key baked in at build time, which means it
works in exactly one build in the world: the one made on a machine with `wu.apiKey` in
`local.properties`. Everybody else — CI, any other checkout, and the published APK — gets an app
with the feature permanently off and no way to turn it on.

This moves the key to where the reader can type it, adds the one override the generator needs, and
fills the catalogue's `pws` fields without disturbing anything else in it.

## Why this is more than a convenience

**The build-time key is what broke v0.29.0.** `BuildConfig.WU_API_KEY` was the empty string in every
build but one, so R8 could prove `fetchAmateur` returned before touching its collaborators, dropped
the constructor arguments, and left Kotlin's non-null checks to throw inside
`ApexApplication.onCreate`. The published APK could not start. It was fixed by making both
collaborators nullable, which is true but treats the symptom.

A key read from `DataStore` at call time is not a compile-time constant. R8 cannot prove anything
about it, cannot fold the branch, and cannot decide the field is unused — so the whole failure mode
goes away rather than being defended against. That is the strongest argument for this change and it
should be stated where the code changes, not only here.

## How the key reaches the repository

`WeatherRepository` deliberately knows nothing about settings, and that is worth keeping: it is why
`refresh` can be called by the worker, the widget and the resume hook without any of them agreeing
about preferences first. So the key arrives the way `ForegroundRefreshLoop`'s inputs do — **as a
function rather than as a dependency**:

```kotlin
class WeatherRepository @Inject constructor(
    …,
    /**
     * The reader's Weather Underground key, read when it is needed rather than injected as a value.
     *
     * A function for the reason ForegroundRefreshLoop takes functions: the repository stays ignorant
     * of SettingsRepository, and a test can hand it a key without standing up a DataStore.
     *
     * It is also what closes the hole v0.29.0 fell into. A key that is a compile-time constant is
     * something R8 can reason about — it proved the empty one made this whole path dead, dropped the
     * constructor arguments, and the app stopped starting. A suspend call into DataStore is opaque
     * to it.
     */
    private val wuKey: suspend () -> String?,
)
```

Hilt provides it from `SettingsRepository`; `WeatherRepositoryTest` passes `{ "test-key" }` or
`{ null }` and needs no DataStore either way.

`BuildConfig.WU_API_KEY`, the Gradle plumbing that fills it, the `@WuApiKey` qualifier and the
`wu.apiKey` line in `local.properties` all go. `WeatherUndergroundApi` stays non-null, because
without a constant there is nothing for R8 to fold; `fetchAmateur` returns early on a blank key as
it does today.

## One rule decides whether a place reads its amateur station

`Place.withAmateurStation(allowed: Boolean)` exists already, applied where settings and place meet.
It gains a second condition, and both live in one function so the three call sites cannot drift:

```kotlin
/** The place as these settings have it: without its amateur station where the reader has switched
 *  them off **or has given no key**, because a station the app cannot fetch is not a station. */
fun Place.forSettings(settings: AppSettings): Place =
    withAmateurStation(settings.amateurStations && !settings.wuApiKey.isNullOrBlank())
```

The key half is not cosmetic. `readingStation` is `pws ?: station`, and the Open-Meteo *station
reference* is fetched at `readingStation`'s coordinates — so a place that keeps its `pws` with no key
would have the models asked about a point whose thermometer is never read, while the hero falls back
to the provincial reading. `StationDownscale` would then subtract two different places from each
other, which is the exact mistake its own documentation is about.

## The field

In settings, under the private-stations switch and only meaningful with it on:

- A single-line text field, `AppSettings.wuApiKey: String?`, stored in `DataStore`.
- **Plaintext in app-private storage**, like every other preference. Said plainly here because it is
  a credential: it is readable by anything with root or a backup of the app's data directory, and
  that is the same protection the rest of this app's settings have. Encrypting one preference and
  not the others would be a gesture rather than a defence.
- Shown as typed, not masked. It is a quota key for a free weather API, not a password, and a masked
  field that cannot be checked by eye is worse for the one thing a reader does with it: paste it and
  see whether it worked.
- A short line beneath: free for anyone who runs their own station, and without it the app reads the
  provincial network only.

Three strings, in all three languages.

## The generator, and the one station it cannot see

`ITIROL26` is live and answers `current`, and `/v3/location/near` does not return it — the endpoint
gives ten stations for Dorf Tirol, stopping at 1,92 km, and this one at 0,68 km is not among them.
So the generator needs a way to consider a station nobody told it about:

```python
# Stations the `near` endpoint does not return but which are worth considering anyway, by ISTAT.
# It answers `current` perfectly well; it is simply missing from the list, and there is no documented
# reason why. Anything here is a *candidate* and nothing more — it goes through the same DEM gate and
# the same stability measure as a station the endpoint did offer, and is dropped by them as readily.
PWS_EXTRA = {
    "021101": ["ITIROL26"],  # Dorf Tirol
}
```

**It was dropped until its metadata was corrected, and that story is the gate earning its keep.**
Measured 2026-09-23: WU reported `ITIROL26` at **204 m** against a DEM of **654 m**, while AWEKAS
had the same instrument at **669 m**. The cause was a unit mismatch rather than a bad station — WU's
station form is in feet, `669` had been entered there, and 669 ft is 203,9 m, which is exactly what
the metric API returned. Re-entered as 2195 ft it reports 669 m and passes at **Δ15 m**.

Altitude is what `StationDownscale` carries a reading up by, so the gate was right to refuse a 450 m
disagreement and right to accept a 15 m one. It caught a real error in data nobody else was checking,
which is the argument for having it.

## Filling the catalogue

`--pws-fill`: a mode that reads the committed `places.json`, adds a `pws` to each place that earns
one, writes nothing else, and prints the table for review. Same shape and same reasoning as
`tools/horizons.py`, which backfills only `horizon` fields on a catalogue it otherwise leaves alone.

A full run would re-query KMOS and SIAG for all 116, re-measure every provincial station's stability
against eight fresh weeks of ICON-D2, and rewrite every record — so the amateur stations would land
in one commit together with whatever drifted upstream since the last run, and no reviewer could tell
the two apart. The narrow pass keeps the diff to the thing being decided.

It still needs a key, from `APEX_WU_API_KEY`, because it is a tool run by hand.

## Tests

- **`SettingsRepositoryTest`** — the key round-trips, and a blank one reads back as absent rather
  than as an empty string, so the one condition downstream is `isNullOrBlank`.
- **`PlaceForSettingsTest`** (new) — the truth table: switch on and key set keeps the `pws`; switch
  off drops it; **key blank drops it even with the switch on**; a place with no `pws` is untouched
  and returned as the same instance.
- **`WeatherRepositoryTest`** — the key function is called per fetch rather than captured once, so a
  key typed while the app is open takes effect on the next refresh; a null key makes no request; the
  station id and the key both reach the fake.
- **`SettingsContentTest`** — the field is present, is disabled or absent while the switch is off,
  and typing into it reaches the callback.
- **`tools/generate-places.py --pws-check`** over Dorf Tirol, which must now show `ITIROL26` as a
  considered candidate and as dropped on the DEM, with its three altitudes in the output.

## Verification

`./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintDebug`, the instrumented suite
on an API 34 emulator, and — **before any tag** — `tools/release-smoke.sh` on a minified build. That
last one is not optional here: this change exists because the previous shape of it shipped an APK
that could not start, and the smoke is the only check that would have caught it.

Then on the phone: type the key, watch the hero name a private station, and clear the key again to
see it fall back.
