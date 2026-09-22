# Amateur stations — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a place read a Weather Underground amateur station instead of a provincial one, where the amateur station is verifiably better sited — chosen offline against a DEM and against the stability measure the generator already uses, with a silent fallback to SIAG whenever it is not answering.

**Architecture:** The choice is made in `tools/generate-places.py` and committed into `places.json`; the phone never searches for stations. `NearbyStation` gains a `network`, `Place` gains a `pws` beside its unchanged `station`, and `Place.readingStation` is the one both the repository and `HomeStateBuilder` consult. The repository fetches the amateur reading beside the SIAG one and keeps both — they are two records with their own coordinates and skylines, never merged — so a quantity the amateur station does not publish still comes from SIAG at SIAG's site. `station_history` learns which thermometer each row came from, because otherwise `BiasCorrector` would mix two.

**Tech Stack:** Kotlin, Room (two databases), Retrofit/OkHttp, Hilt, Compose, Python 3 for the generator, JUnit4.

Spec: `docs/superpowers/specs/2026-09-22-amateur-stations-design.md`

## Global Constraints

- **The API key is never committed.** It lives in `local.properties` (already gitignored) as `wu.apiKey=…` and reaches the code as `BuildConfig.WU_API_KEY`. **The app must build and run with it absent** — CI has no key and neither does anyone else's checkout — falling back to SIAG everywhere.
- WU's PWS tier: **1500 requests/day, 30/minute**, and `current` returns **HTTP 204** when nothing was observed in the last 60 minutes. 204 is an ordinary outcome, not an error.
- WU data is **not licensed for redistribution**: the amateur reading must never reach the share card.
- Never apply `org.jetbrains.kotlin.android` in `app/build.gradle.kts`; KSP only, no kapt.
- `./gradlew :app:lintDebug` runs with `warningsAsErrors` and must be clean.
- Every new string key goes into all three of `values/strings.xml`, `values-it/strings.xml`, `values-en/strings.xml`.
- Nothing that runs on a device may assert a German string; CI emulators are en-US.
- Device tests run on an emulator (`r8verify34`, API 34), **never** the phone `RZCXA1ZEXJE`: a connected run uninstalls the app and deletes `station_history`.
- `station_history` lives in its own `HistoryDatabase` with **no destructive fallback**. Any change to it is a migration with an exported schema in `app/schemas`.
- `tools/` has no Python test harness; the convention is a `--check`-style dry run that writes nothing, as `tools/horizons.py` has.

---

### Task 1: The generator picks and verifies an amateur station

**Files:**
- Modify: `tools/generate-places.py`
- Modify: `app/src/main/assets/places.json` (regenerated, reviewed by hand)

**Interfaces:**
- Consumes: `tools/horizons.py`'s `ground(lat, lon)` and `horizon_profile(lat, lon)`, both already present.
- Produces: a `"pws"` object in each place record that has one, with exactly these keys, which Task 2 deserialises:
  `{"network": "wu", "code": str, "name": str, "lat": float, "lon": float, "altitudeM": int, "distanceKm": float, "stabilityK": float|null, "horizon": [int]|null}`.

- [ ] **Step 1: Add the constants and the WU endpoints**

Near the other constants at the top of `tools/generate-places.py` (beside `STATION_MAX_DZ_M` and `HEIGHT_COST_KM_PER_M`):

```python
# Weather Underground's contributor API. The key is the operator's own and is read from the
# environment, never committed: this generator is run by hand and reviewed by hand.
WU_KEY = os.environ.get("APEX_WU_API_KEY", "")
WU_NEAR = "https://api.weather.com/v3/location/near?geocode={lat},{lon}&product=pws&format=json&apiKey={key}"
WU_CURRENT = "https://api.weather.com/v2/pws/observations/current?stationId={id}&format=json&units=m&apiKey={key}"

# How far a station's claimed altitude may sit from the ground under its own coordinates before the
# claim is treated as fiction. Measured on the eight stations around Dorf Tirol on 2026-09-22: the
# three believable ones sat at 2, 27 and 48 m and the four impossible ones at 210, 291, 415 and
# 416 — so anything between about 60 and 200 gives the same answer, and the threshold is not
# delicate. It is not tighter than this because SRTM is a 30 m grid over steep ground and is itself
# out by tens of metres here: Dorf Tirol's own catalogue altitude of 594 m sits 23 m from the DEM.
PWS_MAX_DEM_DISAGREEMENT_M = 100
```

Add `import os` to the imports if it is not already there.

- [ ] **Step 2: Add the candidate finder**

Add this function beside `pick_by_stability`:

```python
def pws_candidates(lat, lon, altitude):
    """
    Amateur stations near a place that are worth putting to the stability test.

    Four gates, and each one earns its place:

    1. **It answers.** HTTP 204 means nothing observed in the last hour. A station not uploading
       today will not be uploading when a reader opens the app. Note that a *live* station answers
       204 intermittently too — ITIROL26 did, minutes before answering 200 — so this drops some
       usable stations, which is the safe direction for a choice committed to a catalogue.
    2. **The DEM agrees with the claimed altitude.** The altitude is typed into a web form by
       whoever put the station up, and four of the seven around Dorf Tirol claimed a valley floor
       while standing on a hillside. `horizons.ground()` reads the 30 m SRTM tile already cached
       under `tools/.dem-cache`. The DEM's answer, not the claim, is what goes into the catalogue.
    3. **It is close enough and level enough**, by the same STATION_MAX_KM and STATION_MAX_DZ_M the
       provincial stations are held to, and ordered by the same HEIGHT_COST_KM_PER_M.
    4. `qcStatus` is **recorded and never obeyed** — see below.

    Returns a list shaped like the SIAG `candidates` list: (cost, record, distance_km, dz).
    """
    if not WU_KEY:
        return []
    try:
        near = fetch(WU_NEAR.format(lat=lat, lon=lon, key=WU_KEY))["location"]
    except Exception as e:  # noqa: BLE001 — no amateur station is an ordinary outcome
        print(f"    WU near failed ({e})", file=sys.stderr)
        return []
    out = []
    for i, code in enumerate(near["stationId"]):
        try:
            body = fetch(WU_CURRENT.format(id=code, key=WU_KEY))
        except Exception:  # noqa: BLE001 — 204 arrives here as an empty body
            continue
        obs = (body or {}).get("observations") or []
        if not obs:
            continue
        o = obs[0]
        claimed = (o.get("metric") or {}).get("elev")
        if claimed is None:
            continue
        s_lat, s_lon = o["lat"], o["lon"]
        dem = horizons.ground(s_lat, s_lon)
        if abs(dem - claimed) > PWS_MAX_DEM_DISAGREEMENT_M:
            print(f"    {code}: claims {claimed} m, DEM {dem:.0f} m — dropped", file=sys.stderr)
            continue
        d = distance_km(lat, lon, s_lat, s_lon)
        dz = abs(altitude - dem)
        if d > STATION_MAX_KM or dz > STATION_MAX_DZ_M:
            continue
        out.append((
            d + dz * HEIGHT_COST_KM_PER_M,
            {
                "network": "wu",
                "code": code,
                "name": o.get("neighborhood") or code,
                "latitude": s_lat,
                "longitude": s_lon,
                # The DEM's altitude, never the claim: the claim is what somebody typed in.
                "altitude": int(round(dem)),
                # WU's own flag, kept so it can be scored later and obeyed by nothing. It is a
                # neighbour-consistency test, and in this terrain a correctly sited station fails it
                # for being right: ITIROL16 was flagged 0 on 40 of 263 readings on 2026-09-22, every
                # one of them between 10:44 and 19:04, when it disagrees with three neighbours that
                # claim 128 to 182 m on ground the DEM puts at 419 to 598.
                "qcStatus": o.get("qcStatus"),
            },
            d,
            dz,
        ))
    out.sort(key=lambda c: c[0])
    return out
```

Add `import horizons` at the top if the module is not already imported (`generate-places.py` calls `tools/horizons.py` as its last step, so it may currently invoke it as a subprocess — if so, add the import; both files live in `tools/`).

- [ ] **Step 3: Choose between the amateur and the provincial station**

In the main loop, directly after the existing `station = None` / `if candidates:` block that produces `station`, add:

```python
        # The amateur station competes on exactly the measure the provincial one was chosen by:
        # pick_by_stability, eight weeks of ICON-D2, village-minus-station. Distance and height only
        # order the candidates; what decides is which thermometer stands in the same air. It is kept
        # only where it wins, so most of the 116 keep the station they have and should.
        pws = None
        pws_candidates_here = pws_candidates(lat, lon, altitude)
        if pws_candidates_here:
            chosen_p, chosen_p_km, score_p = pick_by_stability((lat, lon, altitude), pws_candidates_here)
            time.sleep(STABILITY_PACE_S)
            better = station is None or score_p is None or station.get("stabilityK") is None or score_p < station["stabilityK"]
            if better:
                pws = {
                    "network": "wu",
                    "code": chosen_p["code"],
                    "name": chosen_p["name"],
                    "lat": round(chosen_p["latitude"], 6),
                    "lon": round(chosen_p["longitude"], 6),
                    "altitudeM": int(chosen_p["altitude"]),
                    "distanceKm": round(chosen_p_km, 2),
                    "stabilityK": None if score_p is None else round(score_p, 2),
                }
            else:
                print(f"    {chosen_p['code']} sd {score_p} does not beat {station['code']} sd {station['stabilityK']}", file=sys.stderr)
```

and add `"pws": pws,` to the dict appended to `places`, directly after `"station": station,`.

- [ ] **Step 4: Give the amateur station a skyline**

In `tools/horizons.py`, find `fill(places, limit=None)` — it walks each place and its `station` and writes a `horizon` into each. Extend it to do the same for `place.get("pws")`, using exactly the same call it already makes for the station. The skyline is the station's own, not the village's: `StationSun` asks its question at the pyranometer, and a thermometer 490 m away on a different shoulder has a different ridge in front of it.

- [ ] **Step 5: Add the dry run**

Add a `--pws-check` flag to `tools/generate-places.py` that, for a handful of places given on the command line (or for Dorf Tirol by default), prints the candidate table and **writes nothing**:

```
Dorf Tirol (594 m)
  ITIROL25   claims 182   DEM 598   Δ 416   dropped
  ITIROL23   claims 179   DEM 594   Δ 415   dropped
  ITIROL24   claims 128   DEM 419   Δ 291   dropped
  ITIROL16   claims 586   DEM 634   Δ  48   kept    0,49 km   sd ?
  ITIROL26   claims 669   DEM 654   Δ  15   kept    0,68 km   sd ?
  provincial: 23200MS Meran  1,53 km  sd 0,__
```

- [ ] **Step 6: Run the dry run and check it against the measured numbers**

```bash
APEX_WU_API_KEY=<the key from local.properties> python3 tools/generate-places.py --pws-check
```

Expected: `ITIROL25`, `ITIROL23` and `ITIROL24` dropped on the DEM gate with the Δ values above (±1 m for rounding); `ITIROL16` and `ITIROL26` kept. If a station that was kept on 2026-09-22 is now dropped at gate 1, that is a station that has gone quiet, not a bug — note it and carry on.

- [ ] **Step 7: Regenerate the catalogue and review the diff by hand**

```bash
APEX_WU_API_KEY=<key> python3 tools/generate-places.py
git diff --stat app/src/main/assets/places.json
python3 -c "
import json
ps = json.load(open('app/src/main/assets/places.json'))
ps = ps['places'] if isinstance(ps, dict) else ps
w = [p for p in ps if p.get('pws')]
print(len(w), 'of', len(ps), 'places gained an amateur station')
for p in w:
    s, q = p['station'], p['pws']
    print(f\"  {p['nameDe']:20s} {s['name']:20s} dz={abs(p['altitudeM']-s['altitudeM']):4d} -> {q['code']:12s} dz={abs(p['altitudeM']-q['altitudeM']):4d}  {q['distanceKm']} km\")
"
```

Read that list. Anything that looks wrong is wrong — this is a committed catalogue, not a cache.

- [ ] **Step 8: Commit**

```bash
git add tools/generate-places.py tools/horizons.py app/src/main/assets/places.json
git commit -m "feat: the catalogue may name an amateur station, verified against the DEM

A station's altitude is typed into a web form by whoever put it up, and four
of the seven around Dorf Tirol claim a valley floor while standing on a
hillside. The DEM under the coordinates decides, and the stability measure the
provincial stations were chosen by decides between the two.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NXkEqmLy6tRRAHVomjDsz1"
```

---

### Task 2: The model reads it

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/domain/Place.kt`
- Test: `app/src/test/kotlin/it/apexweather/data/PlaceCatalogueTest.kt` (or the nearest existing catalogue test; create it if there is none)

**Interfaces:**
- Consumes: the `"pws"` JSON shape from Task 1.
- Produces: `NearbyStation.network: String` (default `"siag"`), `Place.pws: NearbyStation?`, and `Place.readingStation: NearbyStation?` = `pws ?: station`. Tasks 4, 5 and 6 use `readingStation`; Task 5 also uses `station` directly as the fallback.

- [ ] **Step 1: Write the failing test**

Add to the catalogue test:

```kotlin
    @Test
    fun `a place with an amateur station reads it and keeps the provincial one`() {
        val json = """
            [{"istat":"021101","nameDe":"Dorf Tirol","nameIt":"Tirolo","nameEn":"Tirol",
              "lat":46.688958,"lon":11.156624,"altitudeM":594,"district":2,
              "station":{"code":"23200MS","name":"Meran","lat":46.688,"lon":11.1366,
                         "altitudeM":330,"distanceKm":1.53},
              "pws":{"network":"wu","code":"ITIROL16","name":"Tirolo - Tirol",
                     "lat":46.693246,"lon":11.155237,"altitudeM":634,"distanceKm":0.49}}]
        """.trimIndent()
        val place = Json { ignoreUnknownKeys = true }
            .decodeFromString(kotlinx.serialization.builtins.ListSerializer(Place.serializer()), json)
            .single()
        assertEquals("ITIROL16", place.pws!!.code)
        assertEquals("wu", place.pws!!.network)
        assertEquals("23200MS", place.station!!.code)
        assertEquals("siag", place.station!!.network)
        assertEquals("ITIROL16", place.readingStation!!.code)
    }

    @Test
    fun `a place without an amateur station reads the provincial one`() {
        val json = """
            [{"istat":"021115","nameDe":"Sterzing","nameIt":"Vipiteno","nameEn":"Vipiteno",
              "lat":46.8967,"lon":11.4333,"altitudeM":948,"district":5,
              "station":{"code":"X","name":"X","lat":46.9,"lon":11.4,"altitudeM":900,"distanceKm":2.0}}]
        """.trimIndent()
        val place = Json { ignoreUnknownKeys = true }
            .decodeFromString(kotlinx.serialization.builtins.ListSerializer(Place.serializer()), json)
            .single()
        assertNull(place.pws)
        assertEquals("X", place.readingStation!!.code)
    }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.PlaceCatalogueTest'`

Expected: FAIL — `pws`, `network` and `readingStation` are not members.

- [ ] **Step 3: Add the fields**

In `app/src/main/kotlin/it/apexweather/domain/Place.kt`, add to `NearbyStation`, after `code`:

```kotlin
    /**
     * Which network this thermometer belongs to: `"siag"` for the province's own, `"wu"` for a
     * Weather Underground contributor station.
     *
     * It is not decoration. A provincial station is a screened instrument on a surveyed site with
     * an altitude somebody measured; an amateur one is a thermometer a neighbour put up, with an
     * altitude typed into a web form — four of the seven around Dorf Tirol claim a valley floor
     * while standing on a hillside. The reader is told which of the two is on their screen, the
     * share card refuses to redistribute the amateur one, and the generator verifies its altitude
     * against a DEM before it ever reaches the catalogue.
     *
     * Defaulted, so a catalogue written before this reads as the province's own, which it is.
     */
    val network: String = "siag",
```

and to `Place`, after `station`:

```kotlin
    /**
     * An amateur station that stands closer to this place, or at its height, than the province's
     * own — where one exists, has been checked against the DEM, and beat the provincial station on
     * the stability measure in `tools/generate-places.py`. Null for most of the 116, which keep the
     * thermometer they have and should.
     *
     * It never replaces [station]. The provincial reading is still fetched, and it is what the app
     * falls back to — which is an ordinary hourly event rather than an error path, because Weather
     * Underground answers "nothing in the last 60 minutes" for a live station too.
     */
    val pws: NearbyStation? = null,
```

and inside the `Place` body, beside `name(locale)`:

```kotlin
    /**
     * The thermometer this place actually reads: the amateur one where there is one, the province's
     * otherwise. Everything that asks "what does the station say" asks this; [station] alone is the
     * fallback and the source of the quantities an amateur station does not publish.
     */
    val readingStation: NearbyStation? get() = pws ?: station
```

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.PlaceCatalogueTest'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/domain/Place.kt app/src/test/kotlin/it/apexweather/data/PlaceCatalogueTest.kt
git commit -m "feat: a place may name an amateur station beside its provincial one

readingStation is the one everything asks; station stays as the fallback and
as the source of what an amateur station does not publish.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NXkEqmLy6tRRAHVomjDsz1"
```

---

### Task 3: The Weather Underground client

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/data/remote/WeatherUndergroundApi.kt`
- Create: `app/src/test/resources/fixtures/wu_itirol16_current.json`
- Create: `app/src/test/kotlin/it/apexweather/data/remote/WeatherUndergroundMapperTest.kt`
- Modify: `app/build.gradle.kts` (the `defaultConfig` block, beside the existing `buildConfigField` lines)
- Modify: `app/src/main/kotlin/it/apexweather/di/AppModule.kt` (wherever the other APIs are provided)

**Interfaces:**
- Consumes: `StationObservation` (unchanged), `NearbyStation` (Task 2).
- Produces:
  - `WeatherUndergroundApi.current(stationId: String): WuResponse?` — null on HTTP 204.
  - `WeatherUndergroundMapper.map(body: WuResponse?, station: NearbyStation): StationObservation?` — null where the body is null or carries no observation.
  - `BuildConfig.WU_API_KEY: String` — empty when absent.
  Task 5 calls both.

- [ ] **Step 1: Wire the key through Gradle**

In `app/build.gradle.kts`, beside the `keystoreProps` block near line 23:

```kotlin
// The Weather Underground contributor key, if this checkout has one. It is personal, quota-limited
// (1500/day) and not licensed for redistribution, so it lives in local.properties and never in git.
// Absent is the normal case — CI has no key, and the app falls back to the provincial stations
// everywhere without it.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val wuApiKey: String = (System.getenv("APEX_WU_API_KEY") ?: localProps.getProperty("wu.apiKey") ?: "").trim()
```

and in `defaultConfig`, beside the other `buildConfigField` calls:

```kotlin
        buildConfigField("String", "WU_API_KEY", "\"$wuApiKey\"")
```

Then add the key to your own `local.properties`:

```
wu.apiKey=<the key>
```

Confirm `local.properties` is gitignored before going any further:

```bash
git check-ignore -v local.properties
```

Expected: a line naming `.gitignore`. **If it prints nothing, stop** and add it before doing anything else.

- [ ] **Step 2: Record the fixture**

Create `app/src/test/resources/fixtures/wu_itirol16_current.json` with this real response, recorded 2026-09-22:

```json
{
    "observations": [
        {
            "stationID": "ITIROL16",
            "obsTimeUtc": "2026-09-22T20:04:44Z",
            "obsTimeLocal": "2026-09-22 22:04:44",
            "neighborhood": "Tirolo - Tirol",
            "softwareType": null,
            "country": "IT",
            "solarRadiation": null,
            "lon": 11.155237,
            "realtimeFrequency": null,
            "epoch": 1790107484,
            "lat": 46.693246,
            "uv": null,
            "winddir": 338,
            "humidity": 44,
            "qcStatus": 1,
            "metric": {
                "temp": 13,
                "heatIndex": 13,
                "dewpt": 1,
                "windChill": 13,
                "windSpeed": 0,
                "windGust": 0,
                "pressure": 1023.03,
                "precipRate": 0.0,
                "precipTotal": 0.0,
                "elev": 586
            }
        }
    ]
}
```

- [ ] **Step 3: Write the failing test**

Create `app/src/test/kotlin/it/apexweather/data/remote/WeatherUndergroundMapperTest.kt`:

```kotlin
package it.apexweather.data.remote

import it.apexweather.domain.NearbyStation
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class WeatherUndergroundMapperTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val station = NearbyStation(
        code = "ITIROL16", network = "wu", name = "Tirolo - Tirol",
        lat = 46.693246, lon = 11.155237, altitudeM = 634, distanceKm = 0.49,
    )

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    @Test
    fun `maps the recorded response`() {
        val body = json.decodeFromString(WuResponse.serializer(), fixture("wu_itirol16_current.json"))
        val o = WeatherUndergroundMapper.map(body, station)!!
        assertEquals(Instant.parse("2026-09-22T20:04:44Z"), o.time)
        assertEquals(13.0, o.tempC!!, 0.0)
        assertEquals(44, o.humidityPct)
        assertEquals(0.0, o.windKmh!!, 0.0)
        assertEquals(0.0, o.gustKmh!!, 0.0)
        assertEquals(0.0, o.precipTodayMm!!, 0.0)
        assertEquals(1023.03, o.pressureHpa!!, 0.001)
        assertEquals("Tirolo - Tirol", o.stationName)
    }

    /**
     * ITIROL16 publishes no radiation at all — confirmed across all 263 of its readings on
     * 2026-09-22. Absent must stay absent: a station with no pyranometer must not be made to say
     * the sun is not shining, because StationSun would then have a measurement it never took.
     */
    @Test
    fun `missing radiation stays null rather than becoming zero`() {
        val body = json.decodeFromString(WuResponse.serializer(), fixture("wu_itirol16_current.json"))
        assertNull(WeatherUndergroundMapper.map(body, station)!!.radiationWm2)
    }

    /** HTTP 204 is an ordinary outcome, not an error: a live station answers it intermittently. */
    @Test
    fun `no body maps to no reading`() {
        assertNull(WeatherUndergroundMapper.map(null, station))
    }

    @Test
    fun `an empty observation list maps to no reading`() {
        val body = json.decodeFromString(WuResponse.serializer(), """{"observations":[]}""")
        assertNull(WeatherUndergroundMapper.map(body, station))
    }

    /**
     * qcStatus is recorded and obeyed by nothing. It is a neighbour-consistency test, and in this
     * terrain a correctly sited station fails it for being right: ITIROL16 was flagged 0 on 40 of
     * 263 readings on 2026-09-22, every one between 10:44 and 19:04, when it disagrees with three
     * neighbours claiming 128 to 182 m on ground the DEM puts at 419 to 598 m. Refusing those
     * readings would drop the best thermometer's whole afternoon and fall back to one 300 m below.
     */
    @Test
    fun `a failed qc flag still maps to a reading`() {
        val flagged = fixture("wu_itirol16_current.json").replace("\"qcStatus\": 1", "\"qcStatus\": 0")
        val body = json.decodeFromString(WuResponse.serializer(), flagged)
        assertEquals(13.0, WeatherUndergroundMapper.map(body, station)!!.tempC!!, 0.0)
    }
}
```

- [ ] **Step 4: Run it and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.remote.WeatherUndergroundMapperTest'`

Expected: FAIL — unresolved references `WuResponse`, `WeatherUndergroundMapper`.

- [ ] **Step 5: Write the client and the mapper**

Create `app/src/main/kotlin/it/apexweather/data/remote/WeatherUndergroundApi.kt`. Follow the shape of the neighbouring `SiagApi.kt` for the Retrofit interface and of `SiagMappers.kt` for the mapper object.

```kotlin
package it.apexweather.data.remote

import it.apexweather.domain.NearbyStation
import it.apexweather.domain.model.StationObservation
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.Instant

/**
 * Weather Underground's PWS contributor API — one endpoint, the current observation of one station.
 *
 * The `near` and `history` endpoints are deliberately absent. Choosing a station is
 * `tools/generate-places.py`'s job, done once against a DEM and reviewed by a human; a phone
 * choosing its own thermometer would spend its quota finding stations instead of reading one, and
 * could not check an altitude against anything. And the rapid history is not available for every
 * station — ITIROL26 answers 204 to it while ITIROL16 returns 263 readings — so nothing may depend
 * on it.
 *
 * The key is personal, capped at 1500 requests a day and 30 a minute, and not licensed for
 * redistribution. It is absent in most checkouts, and absent means this whole path is switched off.
 */
interface WeatherUndergroundApi {
    /**
     * Returns null on **HTTP 204**, which is what the service answers when the station has reported
     * nothing in the last 60 minutes. That is an ordinary outcome and not a failure: ITIROL26
     * answered 204 and then, minutes later, 200. The caller falls back to the provincial station
     * quietly, marks nothing failed and accuses nobody.
     */
    @GET("v2/pws/observations/current?format=json&units=m")
    suspend fun current(
        @Query("stationId") stationId: String,
        @Query("apiKey") apiKey: String,
    ): Response<WuResponse>
}

@Serializable
data class WuResponse(val observations: List<WuObservation> = emptyList())

@Serializable
data class WuObservation(
    val stationID: String? = null,
    val obsTimeUtc: String? = null,
    val neighborhood: String? = null,
    val humidity: Int? = null,
    val winddir: Int? = null,
    /** WU's own neighbour-consistency flag: 1 passed, -1 not checked, 0 failed. Recorded, never obeyed. */
    val qcStatus: Int? = null,
    val solarRadiation: Double? = null,
    val metric: WuMetric? = null,
)

@Serializable
data class WuMetric(
    val temp: Double? = null,
    val windSpeed: Double? = null,
    val windGust: Double? = null,
    val pressure: Double? = null,
    val precipRate: Double? = null,
    /** Since local midnight, the same shape as SIAG's `n` — which is what lets StationDry work unchanged. */
    val precipTotal: Double? = null,
    val elev: Double? = null,
)

object WeatherUndergroundMapper {
    /**
     * One amateur reading, or null where there is none.
     *
     * The station's *catalogue* name is used rather than the response's `neighborhood` only when the
     * response has none: the neighbourhood is what the owner called it and is usually the better
     * word for a reader ("Tirolo - Tirol").
     *
     * [WuObservation.solarRadiation] is left null where the station publishes none. Absent must stay
     * absent: a station with no pyranometer must not be made to say the sun is not shining, or
     * StationSun would have a measurement nobody took.
     */
    fun map(body: WuResponse?, station: NearbyStation): StationObservation? {
        val o = body?.observations?.firstOrNull() ?: return null
        val time = o.obsTimeUtc?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        val m = o.metric
        return StationObservation(
            stationName = o.neighborhood ?: station.name,
            time = time,
            tempC = m?.temp,
            humidityPct = o.humidity,
            windKmh = m?.windSpeed,
            windDir = o.winddir?.let { Compass.abbreviationFor(it) },
            gustKmh = m?.windGust,
            precipTodayMm = m?.precipTotal,
            pressureHpa = m?.pressure,
            radiationWm2 = o.solarRadiation,
        )
    }
}
```

`Compass.abbreviationFor` is a guess at the name in `app/src/main/kotlin/it/apexweather/domain/Compass.kt`. Open that file and use whatever it actually declares for "degrees → eight-point abbreviation"; if it only offers a composable or a resource id, pass `windDir = null` here and leave a comment saying the direction is carried in degrees elsewhere — do not invent a second compass.

- [ ] **Step 6: Provide it in Hilt**

In `app/src/main/kotlin/it/apexweather/di/AppModule.kt`, add a provider for `WeatherUndergroundApi` with base URL `https://api.weather.com/`, using the same OkHttp client and `Json` converter as the neighbouring APIs. Copy the shape of the existing `provideSiagApi`-style function exactly.

- [ ] **Step 7: Run the test and watch it pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.remote.WeatherUndergroundMapperTest'`

Expected: PASS, 5 tests.

- [ ] **Step 8: Commit**

```bash
git add app/build.gradle.kts \
        app/src/main/kotlin/it/apexweather/data/remote/WeatherUndergroundApi.kt \
        app/src/main/kotlin/it/apexweather/di/AppModule.kt \
        app/src/test/resources/fixtures/wu_itirol16_current.json \
        app/src/test/kotlin/it/apexweather/data/remote/WeatherUndergroundMapperTest.kt
git commit -m "feat: read one amateur station's current observation

204 is an ordinary outcome and qcStatus is recorded rather than obeyed. The
key comes from local.properties and absent means the path is off.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NXkEqmLy6tRRAHVomjDsz1"
```

---

### Task 4: `station_history` learns which thermometer it holds

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/data/local/AppDatabase.kt` (`StationHistoryEntity`, around line 87)
- Modify: `app/src/main/kotlin/it/apexweather/data/local/HistoryDatabase.kt` (version, migration, DAO queries)
- Modify: `app/src/main/kotlin/it/apexweather/domain/BiasCorrector.kt` and `app/src/main/kotlin/it/apexweather/domain/VerificationHistory.kt` if either reads rows without a station filter
- Test: `app/src/test/kotlin/it/apexweather/data/local/HistoryDatabaseMigrationTest.kt` (create) — or, if Room migration tests in this repo live in `androidTest`, put it there beside them

**Interfaces:**
- Consumes: `Place.readingStation` (Task 2).
- Produces: `StationHistoryEntity.station: String?` and `HistoryDatabase.MIGRATION_2_3`. Task 5 writes the column.

- [ ] **Step 1: Write the failing migration test**

```kotlin
    /**
     * Rows written before this migration are the place's provincial station's, because that is the
     * only thermometer the app has ever read. They are stamped with it rather than left null, so
     * the filter in BiasCorrector does not have to special-case "unknown" forever.
     */
    @Test
    fun migrates2To3AndStampsExistingRowsWithTheProvincialStation() {
        // … open the v2 database, insert one row, run MIGRATION_2_3, assert the row survives and
        // its `station` column is the place's provincial station code.
    }

    /**
     * The whole reason the column exists. Switching a place from Meran to ITIROL16 must not feed
     * BiasCorrector a habit measured against two thermometers 264 m apart — silently, and in the
     * one table the app never throws away.
     */
    @Test
    fun biasCorrectorIgnoresRowsFromAnotherStation() {
        // … two rows for the same hour range, one per station; assert the correction computed for
        // station A is unchanged by the presence of station B's rows.
    }
```

Fill both bodies out using the migration-test shape already in this repo. If there is no Room migration test yet, use `androidx.room.testing.MigrationTestHelper` against the exported schemas in `app/schemas`, and add `androidTestImplementation("androidx.room:room-testing:…")` at the version the other Room artefacts already use.

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*HistoryDatabaseMigrationTest'` (or the connected variant if the test lives in `androidTest`).

Expected: FAIL — no `station` column.

- [ ] **Step 3: Add the column and the migration**

In `StationHistoryEntity`, add:

```kotlin
    /**
     * Which thermometer this row's reading came from — the station's own code, e.g. `23200MS` or
     * `ITIROL16`.
     *
     * A row is "what the station read and what each model said it would read", and until a place
     * could change station there was only ever one answer. Now that it can, a row without this is
     * a measurement of an unknown instrument: BiasCorrector would subtract a habit averaged over
     * two thermometers 264 m apart, silently, in the one table this app never discards.
     *
     * Null only in rows written before version 3, which the migration stamps with the place's
     * provincial station because that is what they are.
     */
    val station: String? = null,
```

In `HistoryDatabase`, bump `version = 2` to `version = 3` and add beside `MIGRATION_1_2`:

```kotlin
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE station_history ADD COLUMN station TEXT")
            }
        }
```

Register it wherever `MIGRATION_1_2` is registered. **Do not add a destructive fallback** — this database deliberately has none.

The migration leaves `station` null rather than guessing a code it cannot know from inside SQL; the *stamping* happens on the first write per place in Task 5, and the read side treats null as "this place's provincial station". Write that as a comment where the null is interpreted, not only here.

- [ ] **Step 4: Filter the reads**

In `BiasCorrector` and `VerificationHistory` (and `WeatherRepository.stationHistory`), keep only rows whose `station` is either the station currently being read or null. Exactly one helper should decide that, so the two callers cannot drift:

```kotlin
/** A row belongs to [code] when it says so, or when it predates the column and is therefore the
 *  provincial station's — which is the only thermometer this app read before version 3. */
fun StationHistoryEntity.isFrom(code: String, provincial: String?): Boolean =
    station == code || (station == null && code == provincial)
```

- [ ] **Step 5: Export the schema and run the tests**

```bash
./gradlew :app:testDebugUnitTest
git status --short app/schemas
```

Expected: tests PASS and `app/schemas` gains a version-3 JSON file. If it does not, `exportSchema = true` is not reaching this database — fix that before going on; an unexported schema is a migration nobody can test later.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/data/local/ app/src/main/kotlin/it/apexweather/domain/ app/schemas app/src/test/kotlin/it/apexweather/data/local/
git commit -m "feat: station_history says which thermometer each row came from

A place can change station now, and a bias measured across two thermometers
264 m apart is worse than no bias at all.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NXkEqmLy6tRRAHVomjDsz1"
```

---

### Task 5: The repository fetches, checks and falls back

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/data/WeatherRepository.kt` (the `ob` async around line 366, `refreshObservation` around line 547, `storeObservation` around line 568, and the snapshot assembly around line 162)
- Modify: `app/src/main/kotlin/it/apexweather/data/local/AppDatabase.kt` (`ObservationEntity`)
- Modify: `app/src/main/kotlin/it/apexweather/domain/model/Models.kt` (`WeatherSnapshot`)
- Create: `app/src/main/kotlin/it/apexweather/domain/StationFault.kt`
- Test: `app/src/test/kotlin/it/apexweather/domain/StationFaultTest.kt`, `app/src/test/kotlin/it/apexweather/data/WeatherRepositoryTest.kt`

**Interfaces:**
- Consumes: `WeatherUndergroundApi.current`, `WeatherUndergroundMapper.map` (Task 3), `Place.readingStation`, `Place.pws`, `Place.station` (Task 2), `StationHistoryEntity.station` (Task 4).
- Produces: `WeatherSnapshot.observation` (the amateur reading where there is a usable one, the provincial one otherwise) and `WeatherSnapshot.officialObservation` (always the provincial one, or null). `StationFault.usable(readingC, modelMedianC): Boolean`. Task 6 reads `observation.stationName` and the place's `pws`.

- [ ] **Step 1: Write the failing fault-rule test**

Create `app/src/test/kotlin/it/apexweather/domain/StationFaultTest.kt`:

```kotlin
package it.apexweather.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationFaultTest {
    @Test
    fun `a reading near the models is usable`() {
        assertTrue(StationFault.usable(14.0, 12.0))
    }

    /**
     * Three degrees off the models on a clear night is what a thermometer standing in a village is
     * *for*, and a rule that rejected it would reject this feature's entire value. Measured on
     * 2026-09-22, ITIROL16 ran +4,1 K above the valley floor at 05:00 and -6,6 K below it at 08:00.
     */
    @Test
    fun `a large but plausible disagreement is usable`() {
        assertTrue(StationFault.usable(4.0, 12.0))
        assertTrue(StationFault.usable(20.0, 12.0))
    }

    @Test
    fun `past the fault threshold it is not usable`() {
        assertFalse(StationFault.usable(30.0, 12.0))
        assertFalse(StationFault.usable(-5.0, 12.0))
    }

    @Test
    fun `no model to compare against leaves the reading usable`() {
        assertTrue(StationFault.usable(14.0, null))
    }

    @Test
    fun `no reading is not usable`() {
        assertFalse(StationFault.usable(null, 12.0))
    }
}
```

- [ ] **Step 2: Run it, watch it fail, then write it**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.domain.StationFaultTest'` — FAIL, unresolved `StationFault`.

Create `app/src/main/kotlin/it/apexweather/domain/StationFault.kt`:

```kotlin
package it.apexweather.domain

import kotlin.math.abs

/**
 * Whether a station reading is a measurement or a fault.
 *
 * Deliberately one rule and a loose one. The statistics screen already decides a station fault this
 * way — a reading more than [MAX_DISAGREEMENT_C] from the models' weighted median at the station
 * drops that hour for every model — and using the same number here means the app has one idea of
 * "that thermometer is broken" rather than two.
 *
 * It must stay loose. A village thermometer disagreeing with the models by several degrees is not
 * an error, it is the entire reason for reading one: on 2026-09-22 ITIROL16 ran 4,1 K above the
 * valley floor at five in the morning and 6,6 K below it at eight. Everything downstream is already
 * defended — [StationDownscale] fades a large anomaly, brackets the result between its two sources
 * and hands the screen back to the models when it leaves that bracket — so this is the last resort
 * and not the first line.
 */
object StationFault {
    /** The same threshold the verification screen counts a station fault at. */
    const val MAX_DISAGREEMENT_C = 15.0

    /**
     * [modelMedianC] is the models' weighted median at the station's own coordinates, or null where
     * there is none — in which case there is nothing to check against and the reading stands.
     */
    fun usable(readingC: Double?, modelMedianC: Double?): Boolean {
        if (readingC == null) return false
        if (modelMedianC == null) return true
        return abs(readingC - modelMedianC) <= MAX_DISAGREEMENT_C
    }
}
```

Run again: PASS.

- [ ] **Step 3: Store two observations rather than one**

`ObservationEntity`'s primary key is `place` alone, so a second row needs a second key column. Change it to:

```kotlin
@Entity(tableName = "observation", primaryKeys = ["place", "network"])
data class ObservationEntity(
    val place: String,
    /** `"siag"` or `"wu"`. The two readings are kept apart, never merged: a merged record would
     *  carry a temperature from one site and a radiation from another under one pair of
     *  coordinates, and StationSun asks its question *at the pyranometer*. */
    val network: String = "siag",
    val json: String?,
    val fetchedAtMs: Long?,
    val lastError: String?,
    val lastErrorAtMs: Long?,
)
```

This is the **cache** database (`AppDatabase`), which does have a destructive fallback, so no migration is required — but check that `AppDatabase`'s version is bumped if it declares one, and confirm the destructive fallback is actually configured there before relying on it.

Add DAO methods alongside the existing ones: `observation(place, network)`, `observationOnce(place, network)`, and keep the existing single-argument shapes only if something outside this feature still needs them.

- [ ] **Step 4: Fetch the amateur reading beside the provincial one**

In `WeatherRepository.refresh`'s `ob` async, keep the existing SIAG fetch exactly as it is and add a second, independent one:

```kotlin
            // The amateur reading, where the catalogue names one and this build has a key. It is a
            // separate isolate from the provincial fetch on purpose: the two fail independently and
            // losing one must not cost the other. And a 204 — "nothing in the last 60 minutes" — is
            // an ordinary outcome that a live station produces too, so it is stored as "no reading"
            // without marking anything failed and without reaching silentSources.
            val pws = async {
                place.pws?.takeIf { BuildConfig.WU_API_KEY.isNotEmpty() }?.let { station ->
                    isolate("WU_STATION") {
                        val o = attempt("WU_STATION") {
                            val response = wu.current(station.code, BuildConfig.WU_API_KEY)
                            WeatherUndergroundMapper.map(response.body().takeIf { response.isSuccessful }, station)
                        }
                        storeObservation(place, station, o, failed["WU_STATION"], now)
                    }
                }
            }
```

`storeObservation` takes the station now, and writes to the row keyed by its `network`. Change its signature to `(place, station: NearbyStation, observation, error, now)` and have both callers pass one; `StationDry.withPrevious` still reads the previous row **of the same network**, which is the point — carrying a valley gauge's previous total onto a village gauge's reading would make `StationDry` compare two instruments.

Do the same in `refreshObservation`, which the foreground loop calls: it should refresh the place's `readingStation`, and the provincial one too where they differ, since SIAG supplies what the amateur station does not.

- [ ] **Step 5: Choose the primary in the snapshot**

Where the snapshot is assembled (around line 162), read both rows and pick:

```kotlin
            // The amateur reading is the primary where it exists, is fresh, and is not a fault.
            // Falling back to the provincial one is an ordinary hourly event — WU answers 204 for a
            // live station too — so it is silent: no banner, no failed source, nothing on screen
            // about it beyond the station's own name, which changes.
            val amateurUsable = amateur != null &&
                Duration.between(amateur.time, now) <= PWS_MAX_AGE &&
                StationFault.usable(amateur.tempC, reference?.at(amateur.time.truncatedTo(ChronoUnit.HOURS))
                    ?.let { ConsensusBlender.weightedMedianBySourceName(it) })
            val primary = if (amateurUsable) amateur else official
```

with, beside the other constants in this file:

```kotlin
        /**
         * How old an amateur reading may be and still lead the screen.
         *
         * Tighter than the 90 minutes a SIAG observation is allowed, and for a different reason: an
         * amateur station uploads every few minutes, so half an hour of silence means it has
         * stopped, where SIAG publishes on a slower cadence by design.
         */
        private val PWS_MAX_AGE: Duration = Duration.ofMinutes(30)
```

`ConsensusBlender.weightedMedianBySourceName` does not exist. `StationReference.at(hour)` returns a map keyed by `Source`, and `ConsensusBlender.weightedMedian(Map<Source, Double>)` is public — use that directly and delete this note. If the reference is null there is no median, which `StationFault.usable` already treats as "nothing to check against".

Add `officialObservation` to `WeatherSnapshot` beside `observation`, and set `observation = primary`.

- [ ] **Step 6: Fill the gaps from SIAG, at SIAG's site**

`StationSun` takes the station's coordinates and horizon. Where the primary publishes no radiation and the provincial reading does, `StationSun` must be given the **provincial** reading together with the **provincial** station's lat/lon/horizon. Find its call site in `HomeStateBuilder` (it currently reads `place.station`) and give it the record and site that belong together. The same applies to any other quantity the amateur station lacks — rain, wind — which should come from the provincial record rather than being absent.

Write a test for exactly this in `HomeStateBuilderTest`: an amateur observation with `radiationWm2 = null` beside a provincial one reading 834 W/m², and `StationSun` still lightening the condition.

- [ ] **Step 7: Write the repository tests**

In `WeatherRepositoryTest`, using the existing fake-API pattern:

```kotlin
    /** A fake that ignores its arguments cannot fail for the reason that matters — see FakeGeoSphere. */
    @Test
    fun `the amateur station is asked for by its own id`() { … }

    @Test
    fun `a 204 leaves the provincial reading leading and marks nothing failed`() { … }

    @Test
    fun `an amateur reading 20 K from the models falls back to the provincial one`() { … }

    @Test
    fun `with no key the amateur station is never requested`() { … }

    @Test
    fun `the amateur reading does not overwrite the provincial row`() { … }
```

Fill each body out against the repository's existing test harness. The fourth is the one that protects every other checkout: assert the fake records **zero** calls.

- [ ] **Step 8: Run everything and commit**

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
git add app/src/main/kotlin/it/apexweather/ app/src/test/kotlin/it/apexweather/
git commit -m "feat: read the amateur station, check it, and fall back quietly

Two observations kept apart rather than merged, because StationSun asks its
question at the pyranometer and a merged record would carry one site's
radiation under another site's coordinates. A 204 is an ordinary hour.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NXkEqmLy6tRRAHVomjDsz1"
```

---

### Task 6: What the reader is told, and what leaves the phone

**Files:**
- Modify: `app/src/main/res/values/strings.xml`, `values-it/strings.xml`, `values-en/strings.xml`
- Modify: `app/src/main/kotlin/it/apexweather/ui/home/HomeState.kt` and `app/src/main/kotlin/it/apexweather/ui/home/HomeSections.kt`
- Modify: `app/src/main/kotlin/it/apexweather/ui/share/ShareCardState.kt`
- Test: `app/src/androidTest/kotlin/it/apexweather/ui/home/HomeScreenTest.kt`, `app/src/test/kotlin/it/apexweather/ui/share/ShareCardStateBuilderTest.kt`

**Interfaces:**
- Consumes: `WeatherSnapshot.observation`, `WeatherSnapshot.officialObservation` (Task 5), `Place.pws` (Task 2).
- Produces: `HomeUiState.observationIsPrivate: Boolean`.

- [ ] **Step 1: Add the strings**

`values/strings.xml`:

```xml
    <string name="station_private">%1$s (private Station)</string>
```

`values-it/strings.xml`:

```xml
    <string name="station_private">%1$s (stazione privata)</string>
```

`values-en/strings.xml`:

```xml
    <string name="station_private">%1$s (private station)</string>
```

- [ ] **Step 2: Write the failing tests**

In `HomeScreenTest`:

```kotlin
    /**
     * A reading from a neighbour's garden is not a reading from the provincial network, and the
     * reader is entitled to know which of the two is on their screen.
     */
    @Test
    fun anAmateurReadingSaysSo() {
        // … build a state whose observation came from a pws, assert hero_source contains
        // context.getString(R.string.station_private, "Tirolo - Tirol")
    }
```

In `ShareCardStateBuilderTest`:

```kotlin
    /**
     * WU's terms do not license redistribution and ShareCapture writes a PNG that leaves the phone.
     * The card falls back to the provincial reading, or to the consensus where there is none.
     */
    @Test
    fun `the shared card never carries the amateur reading`() {
        // … a state whose hero came from a pws; assert the card's temperature is the provincial
        // reading's, and that the amateur station's name appears nowhere in the card state.
    }
```

- [ ] **Step 3: Run them and watch them fail, then implement**

`HomeUiState` gains:

```kotlin
    /**
     * Whether [observation] came from an amateur station rather than the provincial network.
     *
     * The hero names its station and says when it was read; this is what makes it say *what kind*
     * of station. It is not a disclaimer — the station was chosen against a DEM and against eight
     * weeks of model stability, and it is usually the better thermometer — it is the reader being
     * told which instrument they are looking at.
     */
    val observationIsPrivate: Boolean = false,
```

In `HomeSections`, wrap the station name in `R.string.station_private` where it is set. In `ShareCardStateBuilder`, take the hero from the provincial reading — it computes no weather of its own, so this is a change of which field it reads, and the fallback chain is provincial reading, then consensus.

- [ ] **Step 4: Run every suite**

```bash
./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintDebug
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

Expected: all PASS.

- [ ] **Step 5: Build with no key at all and prove the fallback**

```bash
mv local.properties local.properties.bak
printf 'sdk.dir=%s\n' "$HOME/Android/Sdk" > local.properties
./gradlew :app:assembleDebug :app:testDebugUnitTest
mv local.properties.bak local.properties
```

Expected: builds and passes. This is the case every other checkout and CI is in, and it is the one most likely to be broken without anybody noticing.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/ app/src/test/ app/src/androidTest/
git commit -m "feat: say when the reading is a private station, and never share it

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NXkEqmLy6tRRAHVomjDsz1"
```

- [ ] **Step 7: Install on the phone and check it against the window**

```bash
./gradlew :app:assembleDebug
adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/debug/ApexWeather-debug.apk
```

Dorf Tirol is the one place in the catalogue where the hero can be checked by walking outside. Compare it against `wunderground.com/dashboard/pws/<the chosen id>` and against a thermometer. Then leave it a fortnight and read the statistics screen — that is the only honest verdict on whether the amateur station is the better thermometer, and it is the point at which this feature is either justified or dropped.

Two things cannot be verified this way and must be reported as untested rather than assumed: the fallback needs the station to actually go quiet, and the no-key build is covered by Step 5 and not by observation.

---

## Self-review notes

- Spec coverage: choosing and DEM-verifying (Task 1), the catalogue model (Task 2), the client and the key (Task 3), history identity (Task 4), fetching, QC and per-quantity fallback (Task 5), the wording and the share-card refusal (Task 6). The spec's out-of-scope list — AWEKAS, MeteoNetwork, a map layer — is implemented nowhere, which is correct.
- Names used consistently: `network`, `pws`, `readingStation`, `WuResponse`, `WeatherUndergroundMapper.map`, `StationFault.usable`, `MIGRATION_2_3`, `station_private`, `observationIsPrivate`.
- **Two steps are deliberately marked as needing the engineer to look before writing**: `Compass`'s real API in Task 3 Step 5, and the Room migration-test harness in Task 4 Step 1. Both are places where guessing a name would produce code that compiles against an invention.
- Task 5 is the largest and is the one to split if it proves unwieldy: Steps 3-4 (storage and fetch) and Steps 5-6 (choice and gap-filling) are separable, each with its own tests.
