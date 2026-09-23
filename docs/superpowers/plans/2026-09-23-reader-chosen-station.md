# The reader chooses the thermometer — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the reader see whether their Weather Underground key works, and pick — per place, from a screen that lists every instrument nearby with what each is reading — which thermometer the app reads.

**Architecture:** A per-place `ChosenStation` record in DataStore overrides the catalogue's choice at the one edge where settings and place already meet (`Place.forSettings`), so nothing downstream learns a new concept. A station may only be chosen once its height has been checked against Open-Meteo's elevation endpoint, which answers the whole screen's worth of coordinates in one request; the DEM's answer is what gets stored, never the owner's claim. A key verdict is written by the one place that already calls Weather Underground and by an explicit check when the key changes.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Hilt, Retrofit + kotlinx-serialization, DataStore Preferences, JUnit4 + Robolectric (JVM) and Compose UI tests (instrumented).

## Global Constraints

- Strings live in `values` (German, default), `values-it`, `values-en`; **every new key goes in all three**.
- Nothing that runs on a device may assert a German string: CI's emulators are **en-US**.
- Nothing user-visible may name Dorf Tirol.
- Numbers, dates and times go through `ui/common/Format.kt` with an explicit `Formats`; never `Locale.ROOT`, never a number interpolated into a string.
- Screens split into `XScreen` (ViewModel wiring) and `XContent(state, callbacks)`; UI tests drive `XContent` with hand-built states.
- Lint runs with `warningsAsErrors`: `./gradlew :app:lintDebug` must be clean.
- AGP 9 built-in Kotlin: never apply `org.jetbrains.kotlin.android`; KSP only, no kapt.
- Device tests run on an emulator (`ANDROID_SERIAL=emulator-5554`, AVD `r8verify34`, API 34). **Never on the phone `RZCXA1ZEXJE`** — a connected run uninstalls the app and deletes `station_history`.
- `PWS_MAX_DEM_DISAGREEMENT_M = 100` — the gate value, copied from `tools/generate-places.py:88`.
- Weather Underground data is **not licensed for redistribution**: the share card must never carry an amateur reading, whichever station is chosen.
- A Weather Underground key must never be committed. It lives in DataStore only.
- JVM tests: `./gradlew :app:testDebugUnitTest`. Single class: `--tests 'it.apexweather.domain.FooTest'`.

---

### Task 1: `ChosenStation` and the settings that hold it

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/data/ChosenStation.kt`
- Modify: `app/src/main/kotlin/it/apexweather/data/SettingsRepository.kt`
- Test: `app/src/test/kotlin/it/apexweather/data/ChosenStationsTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `data class ChosenStation(istat, network, code, name, lat, lon, altitudeM, distanceKm)`; `object ChosenStations { fun encode(List<ChosenStation>): String; fun decode(String?): List<ChosenStation>; fun with(List<ChosenStation>, ChosenStation?): List<ChosenStation> }`; `AppSettings.chosenStations: List<ChosenStation>`; `SettingsRepository.setChosenStation(istat: String, station: ChosenStation?)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/it/apexweather/data/ChosenStationsTest.kt`:

```kotlin
package it.apexweather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChosenStationsTest {
    private val itirol26 = ChosenStation(
        istat = "021101", network = "wu", code = "ITIROL26", name = "Tirol",
        lat = 46.694926, lon = 11.154523, altitudeM = 659, distanceKm = 0.68,
    )

    @Test
    fun aRecordSurvivesTheRoundTrip() {
        assertEquals(listOf(itirol26), ChosenStations.decode(ChosenStations.encode(listOf(itirol26))))
    }

    /** Nothing stored is the ordinary state: 116 places and at most a handful ever overridden. */
    @Test
    fun nothingStoredDecodesToNothing() {
        assertTrue(ChosenStations.decode(null).isEmpty())
        assertTrue(ChosenStations.decode("").isEmpty())
    }

    /**
     * A preference written by another version, or corrupted, must not take the screen down with
     * it. Every other stored list in this app drops what it cannot read.
     */
    @Test
    fun rubbishDecodesToNothingRatherThanThrowing() {
        assertTrue(ChosenStations.decode("{not json").isEmpty())
    }

    @Test
    fun choosingReplacesThatPlacesRecordAndLeavesTheOthers() {
        val other = itirol26.copy(istat = "021051", code = "IBOLZANO2")
        val next = ChosenStations.with(listOf(itirol26, other), itirol26.copy(code = "ITIROL16"))
        assertEquals(2, next.size)
        assertEquals("ITIROL16", next.first { it.istat == "021101" }.code)
        assertEquals("IBOLZANO2", next.first { it.istat == "021051" }.code)
    }

    /** Null is "go back to the catalogue's own choice", which is a removal and not a record. */
    @Test
    fun clearingRemovesThatPlacesRecord() {
        assertTrue(ChosenStations.with(listOf(itirol26), null, istat = "021101").isEmpty())
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.ChosenStationsTest'`
Expected: FAIL — `Unresolved reference: ChosenStation`.

- [ ] **Step 3: Write `ChosenStation.kt`**

Create `app/src/main/kotlin/it/apexweather/data/ChosenStation.kt`:

```kotlin
package it.apexweather.data

import it.apexweather.domain.NearbyStation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The station a reader has chosen to read for one place, overriding the catalogue.
 *
 * `tools/generate-places.py` picks a place's amateur station by measurement — claimed height
 * against SRTM, height costed like distance, then eight weeks of ICON-D2 stability. That is a good
 * rule and it stays the default. It is not the reader's rule: it cannot know that one station is
 * maintained by somebody who will notice when it stops, or that another publishes radiation (which
 * `StationSun` needs) while the chosen one has no pyranometer at all.
 *
 * [altitudeM] is **the DEM's answer and never the station's own claim**. Weather Underground's
 * elevation is whatever the owner typed into a web form, and that form is in feet, which is how
 * ITIROL26 came to claim 204 m while standing at 654. `StationDownscale`'s cap is
 * `2 K + 9,8 K/km × |height difference|`, so a station wrong about its height by 450 m buys itself
 * four and a half degrees of licence over the app's most-read number.
 *
 * No `horizon` and no `stabilityK`: a skyline is tens of thousands of DEM samples and a generator
 * job, and the stability score is eight weeks of model runs. `Horizon.usable` already returns false
 * for a missing profile, so a chosen station falls back to the astronomical sun times, which is a
 * documented and tested path.
 */
@Serializable
data class ChosenStation(
    val istat: String,
    /** `"wu"` or `"siag"`, matching [NearbyStation.network]. */
    val network: String,
    val code: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val altitudeM: Int,
    val distanceKm: Double,
) {
    fun toStation(): NearbyStation = NearbyStation(
        code = code, name = name, lat = lat, lon = lon,
        altitudeM = altitudeM, distanceKm = distanceKm, network = network,
    )
}

/**
 * Encoding the list for DataStore, which holds strings.
 *
 * JSON rather than the comma-joined form the recents and the pins use, because a record has eight
 * fields and two of them are names that may contain a comma ("Tirolo - Tirol" does not, but
 * nothing stops one).
 */
object ChosenStations {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(stations: List<ChosenStation>): String = json.encodeToString(stations)

    /** Anything unreadable is nothing, the way every other stored list here drops what it cannot parse. */
    fun decode(stored: String?): List<ChosenStation> {
        if (stored.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<ChosenStation>>(stored) }.getOrDefault(emptyList())
    }

    /**
     * That place's record replaced, or removed where [station] is null — which is how a reader says
     * "go back to whatever the catalogue chose".
     */
    fun with(
        current: List<ChosenStation>,
        station: ChosenStation?,
        istat: String = station?.istat.orEmpty(),
    ): List<ChosenStation> = current.filterNot { it.istat == istat } + listOfNotNull(station)
}
```

- [ ] **Step 4: Run the test and watch it pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.ChosenStationsTest'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Add the field to `AppSettings` and the key to `SettingsRepository`**

In `app/src/main/kotlin/it/apexweather/data/SettingsRepository.kt`, add to `data class AppSettings` (after `wuApiKey`):

```kotlin
    /**
     * The stations the reader has picked themselves, at most one per place.
     *
     * Empty is the ordinary state and means "the catalogue's own choice everywhere", which is what
     * the app did before this existed.
     */
    val chosenStations: List<ChosenStation> = emptyList(),
```

In `object Keys`, after `wuApiKey`:

```kotlin
        // JSON rather than the comma-joined form the recents use: a record has eight fields.
        val chosenStations = stringPreferencesKey("chosen_stations")
```

In the `settings` flow's `AppSettings(...)`, after `wuApiKey = ...`:

```kotlin
            chosenStations = ChosenStations.decode(p[Keys.chosenStations]),
```

And a writer, beside `setWuApiKey`:

```kotlin
    /** [station] null clears this place's choice and hands it back to the catalogue. */
    suspend fun setChosenStation(istat: String, station: ChosenStation?) = context.settingsStore.edit { prefs ->
        val next = ChosenStations.with(ChosenStations.decode(prefs[Keys.chosenStations]), station, istat)
        prefs[Keys.chosenStations] = ChosenStations.encode(next)
    }
```

- [ ] **Step 6: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/data/ChosenStation.kt \
        app/src/main/kotlin/it/apexweather/data/SettingsRepository.kt \
        app/src/test/kotlin/it/apexweather/data/ChosenStationsTest.kt
git commit -m "feat: a place may carry the station its reader chose"
```

---

### Task 2: `Place.forSettings` honours the choice

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/domain/Place.kt`
- Test: `app/src/test/kotlin/it/apexweather/domain/PlaceChosenStationTest.kt`

**Interfaces:**
- Consumes: `ChosenStation`, `ChosenStation.toStation()`, `AppSettings.chosenStations` (Task 1).
- Produces: `Place.forSettings(settings)` resolving a chosen station; `Place.withChosenStation(ChosenStation?): Place`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/it/apexweather/domain/PlaceChosenStationTest.kt`:

```kotlin
package it.apexweather.domain

import it.apexweather.data.AppSettings
import it.apexweather.data.ChosenStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceChosenStationTest {
    private val official = NearbyStation(
        code = "23200MS", name = "Meran", lat = 46.688, lon = 11.1366,
        altitudeM = 330, distanceKm = 1.53, network = "siag",
    )
    private val catalogue = NearbyStation(
        code = "ITIROL16", name = "Tirolo - Tirol", lat = 46.693246, lon = 11.155237,
        altitudeM = 634, distanceKm = 0.49, network = "wu",
    )
    private val place = Place(
        istat = "021101", nameDe = "Dorf Tirol", nameIt = "Tirolo", nameEn = "Tirol",
        lat = 46.688958, lon = 11.156624, altitudeM = 594, district = 2,
        station = official, pws = catalogue,
    )
    private val settings = AppSettings(amateurStations = true, wuApiKey = "k")
    private val chosen = ChosenStation(
        istat = "021101", network = "wu", code = "ITIROL26", name = "Tirol",
        lat = 46.694926, lon = 11.154523, altitudeM = 659, distanceKm = 0.68,
    )

    @Test
    fun withNothingStoredTheCatalogueStationIsRead() {
        assertEquals("ITIROL16", place.forSettings(settings).readingStation?.code)
    }

    @Test
    fun aChosenAmateurStationIsReadInsteadOfTheCatalogueOne() {
        val resolved = place.forSettings(settings.copy(chosenStations = listOf(chosen)))
        assertEquals("ITIROL26", resolved.readingStation?.code)
        assertEquals(659, resolved.readingStation?.altitudeM)
        assertEquals(46.694926, resolved.readingStation?.lat ?: 0.0, 1e-9)
    }

    /** Choosing the province's own for one place, without switching amateur stations off for all 116. */
    @Test
    fun choosingTheProvincialStationDropsTheAmateurOne() {
        val provincial = chosen.copy(network = "siag", code = "23200MS", name = "Meran", altitudeM = 330)
        val resolved = place.forSettings(settings.copy(chosenStations = listOf(provincial)))
        assertEquals("23200MS", resolved.readingStation?.code)
        assertNull(resolved.pws)
    }

    /** The switch is a statement about every amateur instrument and outranks one place's choice. */
    @Test
    fun theGlobalSwitchOffOverridesAChoice() {
        val resolved = place.forSettings(settings.copy(amateurStations = false, chosenStations = listOf(chosen)))
        assertEquals("23200MS", resolved.readingStation?.code)
    }

    /**
     * Without a key there is nothing to fetch the station with, and a place keeping a `pws` it
     * cannot read would have the models asked about a point whose thermometer is never read.
     */
    @Test
    fun noKeyOverridesAChoice() {
        val resolved = place.forSettings(settings.copy(wuApiKey = null, chosenStations = listOf(chosen)))
        assertEquals("23200MS", resolved.readingStation?.code)
    }

    @Test
    fun aRecordForAnotherPlaceIsIgnored() {
        val elsewhere = chosen.copy(istat = "021051")
        assertEquals("ITIROL16", place.forSettings(settings.copy(chosenStations = listOf(elsewhere))).readingStation?.code)
    }

    /** A place with no provincial station and a chosen amateur one still reads the amateur one. */
    @Test
    fun aPlaceWithNoProvincialStationStillReadsItsChoice() {
        val orphan = place.copy(station = null)
        assertEquals("ITIROL26", orphan.forSettings(settings.copy(chosenStations = listOf(chosen))).readingStation?.code)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.domain.PlaceChosenStationTest'`
Expected: FAIL — `aChosenAmateurStationIsReadInsteadOfTheCatalogueOne` reports `ITIROL16`.

- [ ] **Step 3: Implement**

In `app/src/main/kotlin/it/apexweather/domain/Place.kt`, add beside `withAmateurStation`:

```kotlin
    /**
     * This place reading the station its reader picked, where they picked one.
     *
     * `"siag"` means they asked for the province's own here, which is exactly a place with no
     * `pws` — the state 48 of the 116 are in, so nothing downstream needs to learn about it.
     */
    fun withChosenStation(chosen: it.apexweather.data.ChosenStation?): Place = when {
        chosen == null || chosen.istat != istat -> this
        chosen.network == "siag" -> copy(pws = null)
        else -> copy(pws = chosen.toStation())
    }
```

Replace the body of `forSettings`:

```kotlin
    fun forSettings(settings: it.apexweather.data.AppSettings): Place =
        withChosenStation(settings.chosenStations.firstOrNull { it.istat == istat })
            .withAmateurStation(settings.amateurStations && !settings.wuApiKey.isNullOrBlank())
```

Extend `forSettings`' KDoc with a third paragraph:

```
     * The reader's own choice is applied *first* and the two global conditions after it, because
     * both of those are statements about every amateur instrument — "I do not trust ones nobody
     * maintains" and "I have given no key" — and one place's preference does not answer either.
```

- [ ] **Step 4: Run the test and watch it pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.domain.PlaceChosenStationTest'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Run the whole JVM suite — nothing else may move**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/domain/Place.kt \
        app/src/test/kotlin/it/apexweather/domain/PlaceChosenStationTest.kt
git commit -m "feat: a chosen station is what the place reads"
```

---

### Task 3: the ground under a station

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/domain/StationHeight.kt`
- Modify: `app/src/main/kotlin/it/apexweather/data/remote/OpenMeteoApi.kt`
- Test: `app/src/test/kotlin/it/apexweather/domain/StationHeightTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `object StationHeight { const val MAX_DEM_DISAGREEMENT_M = 100; fun heightOf(claimedM: Int?, demM: Int?): Int?; fun disputed(claimedM: Int?, demM: Int?): Boolean }`; `OpenMeteoApi.elevation(latitude: String, longitude: String): ElevationResponse` with `data class ElevationResponse(val elevation: List<Double>)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/it/apexweather/domain/StationHeightTest.kt`:

```kotlin
package it.apexweather.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationHeightTest {
    /** The DEM wins outright: it is ground that was measured, not a number somebody typed. */
    @Test
    fun theGroundIsTheHeightEvenWhereTheClaimAgrees() {
        assertEquals(639, StationHeight.heightOf(claimedM = 634, demM = 639))
    }

    @Test
    fun theGroundIsTheHeightWhereTheClaimIsAbsurd() {
        assertEquals(631, StationHeight.heightOf(claimedM = 182, demM = 631))
    }

    /** No DEM, no height the app will act on. Section 2 of the spec: nothing may be chosen. */
    @Test
    fun withoutTheGroundThereIsNoHeight() {
        assertNull(StationHeight.heightOf(claimedM = 182, demM = null))
        assertNull(StationHeight.heightOf(claimedM = null, demM = null))
    }

    /** A station that publishes no elevation disputes nothing; it merely said nothing. */
    @Test
    fun aStationThatClaimsNothingIsNotDisputed() {
        assertFalse(StationHeight.disputed(claimedM = null, demM = 631))
    }

    @Test
    fun aClaimWithinTheGateIsNotDisputed() {
        assertFalse(StationHeight.disputed(claimedM = 669, demM = 659))
        assertFalse(StationHeight.disputed(claimedM = 559, demM = 659))
    }

    /** ITIROL26 as Weather Underground had it: 669 ft read as metres against real ground at 654. */
    @Test
    fun theFeetForMetresMistakeIsDisputed() {
        assertTrue(StationHeight.disputed(claimedM = 204, demM = 654))
    }

    /** Exactly the gate is inside it; the generator drops only what is strictly further out. */
    @Test
    fun theGateItselfIsNotDisputed() {
        assertFalse(StationHeight.disputed(claimedM = 559, demM = 659))
        assertTrue(StationHeight.disputed(claimedM = 558, demM = 659))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.domain.StationHeightTest'`
Expected: FAIL — `Unresolved reference: StationHeight`.

- [ ] **Step 3: Write `StationHeight.kt`**

Create `app/src/main/kotlin/it/apexweather/domain/StationHeight.kt`:

```kotlin
package it.apexweather.domain

import kotlin.math.abs

/**
 * How high an amateur station really stands, and whether its own account of that is believable.
 *
 * `StationDownscale`'s cap is `2 K + 9,8 K/km × |height difference|`, so this number decides how
 * far the app may move its most-read temperature. Weather Underground's elevation is whatever the
 * station's owner typed into a web form — and that form is in **feet**, which is how ITIROL26 came
 * to be recorded at 204 m while standing at 654. Believing it would have handed that station four
 * and a half degrees of licence over the hero.
 *
 * So the claim is never the height. The ground is, from Open-Meteo's elevation endpoint, which
 * answers a whole list of coordinates in one request — `tools/generate-places.py` does the same
 * against SRTM and stores `int(round(dem))`. Open-Meteo serves Copernicus GLO-90 where the
 * generator used SRTM 30 m: two models of the same ground, metres apart, which a hundred-metre
 * gate does not notice. Measured 2026-09-23 — ITIROL16 catalogue 634 m, Open-Meteo 639;
 * ITIROL26 claim 669, Open-Meteo 659.
 */
object StationHeight {
    /** The generator's own gate, `tools/generate-places.py`'s `PWS_MAX_DEM_DISAGREEMENT_M`. */
    const val MAX_DEM_DISAGREEMENT_M = 100

    /**
     * The height to act on: the ground, or null where the ground is unknown.
     *
     * Null is not a fallback to the claim. A station whose height is only its owner's word is a
     * station this app will not read, because the one thing that height does is set how far the
     * hero may be moved.
     */
    fun heightOf(claimedM: Int?, demM: Int?): Int? = demM

    /**
     * The station's own account of its height is too far from the ground to be believed.
     *
     * Worth showing rather than acting on: the height used is the DEM's either way, so this is
     * information about the station's record — and a record out by hundreds of metres suggests the
     * coordinates may be somewhere else entirely, which no DEM can correct.
     */
    fun disputed(claimedM: Int?, demM: Int?): Boolean {
        if (claimedM == null || demM == null) return false
        return abs(claimedM - demM) > MAX_DEM_DISAGREEMENT_M
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.domain.StationHeightTest'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Add the elevation endpoint**

In `app/src/main/kotlin/it/apexweather/data/remote/OpenMeteoApi.kt`, add inside `interface OpenMeteoApi`, before the `companion object`:

```kotlin
    /**
     * The ground under a list of coordinates, in metres, in the order they were given.
     *
     * Both parameters are comma-joined lists and the response is one array:
     * `?latitude=46.693,46.695&longitude=11.155,11.154` → `{"elevation":[639.0, 659.0]}`.
     * One request for a screenful of stations, which is what makes checking every claimed height
     * affordable — see [it.apexweather.domain.StationHeight].
     */
    @GET("v1/elevation")
    suspend fun elevation(
        @Query("latitude") latitude: String,
        @Query("longitude") longitude: String,
    ): ElevationResponse
```

And after the interface, beside the other response types:

```kotlin
@Serializable
data class ElevationResponse(val elevation: List<Double> = emptyList())
```

Check the file's existing imports for `kotlinx.serialization.Serializable`, `retrofit2.http.GET` and `retrofit2.http.Query`; add whichever are missing.

- [ ] **Step 6: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/domain/StationHeight.kt \
        app/src/main/kotlin/it/apexweather/data/remote/OpenMeteoApi.kt \
        app/src/test/kotlin/it/apexweather/domain/StationHeightTest.kt
git commit -m "feat: a station's height is the ground under it, not its owner's word"
```

---

### Task 4: the key's verdict

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/data/WuKeyVerdict.kt`
- Modify: `app/src/main/kotlin/it/apexweather/data/SettingsRepository.kt`
- Test: `app/src/test/kotlin/it/apexweather/data/WuKeyVerdictTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `enum class WuKeyVerdict { UNCHECKED, CHECKING, GOOD, REFUSED, OVER_QUOTA, OFFLINE }`; `object WuKeyVerdicts { fun of(httpCode: Int): WuKeyVerdict; fun of(failure: Throwable): WuKeyVerdict }`; `AppSettings.wuKeyVerdict: WuKeyVerdict`, `AppSettings.wuKeyCheckedAtMs: Long?`; `SettingsRepository.setWuKeyVerdict(WuKeyVerdict, checkedAtMs: Long?)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/it/apexweather/data/WuKeyVerdictTest.kt`:

```kotlin
package it.apexweather.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class WuKeyVerdictTest {
    @Test
    fun twoHundredIsGood() {
        assertEquals(WuKeyVerdict.GOOD, WuKeyVerdicts.of(200))
    }

    /**
     * 204 is a live station with nothing to report in the last hour. It proves the key worked —
     * an unauthorised request never gets that far.
     */
    @Test
    fun noContentIsAlsoGood() {
        assertEquals(WuKeyVerdict.GOOD, WuKeyVerdicts.of(204))
    }

    @Test
    fun unauthorisedAndForbiddenAreRefusals() {
        assertEquals(WuKeyVerdict.REFUSED, WuKeyVerdicts.of(401))
        assertEquals(WuKeyVerdict.REFUSED, WuKeyVerdicts.of(403))
    }

    @Test
    fun tooManyRequestsIsTheQuota() {
        assertEquals(WuKeyVerdict.OVER_QUOTA, WuKeyVerdicts.of(429))
    }

    /**
     * A server fault says nothing about the key. Reporting "refused" for a 503 would send the
     * reader off to re-type a key that is perfectly good.
     */
    @Test
    fun aServerFaultDoesNotAccuseTheKey() {
        assertEquals(WuKeyVerdict.OFFLINE, WuKeyVerdicts.of(500))
        assertEquals(WuKeyVerdict.OFFLINE, WuKeyVerdicts.of(503))
    }

    @Test
    fun aTransportFailureIsNoConnection() {
        assertEquals(WuKeyVerdict.OFFLINE, WuKeyVerdicts.of(UnknownHostException("api.weather.com")))
        assertEquals(WuKeyVerdict.OFFLINE, WuKeyVerdicts.of(SocketTimeoutException("timeout")))
        assertEquals(WuKeyVerdict.OFFLINE, WuKeyVerdicts.of(IOException("reset")))
    }

    /** A parse failure is the app's fault or the upstream's, and again not the key's. */
    @Test
    fun anythingElseIsAlsoNoConnectionRatherThanARefusal() {
        assertEquals(WuKeyVerdict.OFFLINE, WuKeyVerdicts.of(IllegalStateException("bad json")))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.WuKeyVerdictTest'`
Expected: FAIL — `Unresolved reference: WuKeyVerdict`.

- [ ] **Step 3: Write `WuKeyVerdict.kt`**

Create `app/src/main/kotlin/it/apexweather/data/WuKeyVerdict.kt`:

```kotlin
package it.apexweather.data

/**
 * What the app last observed about the reader's Weather Underground key.
 *
 * Three failures rather than one, because they need three different things from the reader: a
 * refused key wants re-typing, an exhausted quota wants waiting until midnight UTC, and a
 * connection failure wants nothing at all. "Funktioniert nicht" would name none of them.
 *
 * Entering a key used to change nothing visible — the thirty-minute staleness rule means the app
 * does not refetch, so the screen went on reading the provincial station and a wrong key looked
 * exactly like a key that had simply not been used yet. Half an hour of a phone was spent on that
 * on 2026-09-23.
 */
enum class WuKeyVerdict {
    /** No key, or a key nothing has tried yet. Nothing is shown. */
    UNCHECKED,

    /** A check is in flight. */
    CHECKING,

    /** The last request carrying this key was answered. */
    GOOD,

    /** 401 or 403 — the key is wrong, or no longer a contributor's. */
    REFUSED,

    /** 429 — 1500 requests a day, 30 a minute. */
    OVER_QUOTA,

    /** The request never got an answer, or the answer was the server's own fault. Says nothing about the key. */
    OFFLINE,
}

object WuKeyVerdicts {
    fun of(httpCode: Int): WuKeyVerdict = when (httpCode) {
        401, 403 -> WuKeyVerdict.REFUSED
        429 -> WuKeyVerdict.OVER_QUOTA
        in 200..299 -> WuKeyVerdict.GOOD
        // Everything else — a 500, a 404 from a station that has gone — is not evidence about the
        // key, and a verdict that accuses it would send the reader to re-type a good one.
        else -> WuKeyVerdict.OFFLINE
    }

    fun of(failure: Throwable): WuKeyVerdict = WuKeyVerdict.OFFLINE
}
```

- [ ] **Step 4: Run the test and watch it pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.WuKeyVerdictTest'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Store the verdict**

In `SettingsRepository.kt`, add to `AppSettings` after `chosenStations`:

```kotlin
    /** What the app last saw happen to [wuApiKey]. Not a preference — an observation. */
    val wuKeyVerdict: WuKeyVerdict = WuKeyVerdict.UNCHECKED,
    /** When that verdict was reached, so the line can say how fresh it is. */
    val wuKeyCheckedAtMs: Long? = null,
```

In `object Keys`, after `chosenStations`:

```kotlin
        val wuKeyVerdict = stringPreferencesKey("wu_key_verdict")
        val wuKeyCheckedAt = longPreferencesKey("wu_key_checked_at")
```

Add `import androidx.datastore.preferences.core.longPreferencesKey`.

In the `settings` flow's `AppSettings(...)`, after `chosenStations = ...`:

```kotlin
            wuKeyVerdict = p[Keys.wuKeyVerdict]?.let { runCatching { WuKeyVerdict.valueOf(it) }.getOrNull() }
                ?: WuKeyVerdict.UNCHECKED,
            wuKeyCheckedAtMs = p[Keys.wuKeyCheckedAt],
```

Add a writer beside `setWuApiKey`:

```kotlin
    suspend fun setWuKeyVerdict(v: WuKeyVerdict, checkedAtMs: Long?) = context.settingsStore.edit {
        it[Keys.wuKeyVerdict] = v.name
        if (checkedAtMs != null) it[Keys.wuKeyCheckedAt] = checkedAtMs else it.remove(Keys.wuKeyCheckedAt)
    }
```

Change `setWuApiKey` so a new key does not inherit the old one's verdict:

```kotlin
    /**
     * A new key has never been checked, whatever the old one's verdict was. Leaving the verdict
     * alone would show "gültig" over a key nothing had tried.
     */
    suspend fun setWuApiKey(v: String) = context.settingsStore.edit {
        it[Keys.wuApiKey] = v
        it[Keys.wuKeyVerdict] = WuKeyVerdict.UNCHECKED.name
        it.remove(Keys.wuKeyCheckedAt)
    }
```

- [ ] **Step 6: Compile and run the whole JVM suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/data/WuKeyVerdict.kt \
        app/src/main/kotlin/it/apexweather/data/SettingsRepository.kt \
        app/src/test/kotlin/it/apexweather/data/WuKeyVerdictTest.kt
git commit -m "feat: what the app last saw happen to the key"
```

---

### Task 5: who writes the verdict

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/data/WuKeyReporter.kt`
- Create: `app/src/main/kotlin/it/apexweather/data/WuKeyChecker.kt`
- Modify: `app/src/main/kotlin/it/apexweather/data/WeatherRepository.kt` (the `fetchAmateur` function)
- Modify: `app/src/main/kotlin/it/apexweather/di/AppModule.kt`
- Modify: `app/src/main/kotlin/it/apexweather/ui/settings/SettingsViewModel.kt`
- Test: `app/src/test/kotlin/it/apexweather/data/WuKeyCheckerTest.kt`

**Interfaces:**
- Consumes: `WuKeyVerdict`, `WuKeyVerdicts.of` (Task 4); `WuKeySource` (existing, `fun interface WuKeySource { suspend fun key(): String? }`); `WeatherUndergroundApi.near(geocode, apiKey)` (existing).
- Produces: `fun interface WuKeyReporter { suspend fun report(verdict: WuKeyVerdict) }`; `class WuKeyChecker(api, keySource, reporter, clock) { suspend fun check(lat: Double, lon: Double) }`; `SettingsViewModel.checkWuKey()`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/it/apexweather/data/WuKeyCheckerTest.kt`:

```kotlin
package it.apexweather.data

import it.apexweather.data.remote.WeatherUndergroundApi
import it.apexweather.data.remote.WuNearLocation
import it.apexweather.data.remote.WuNearResponse
import it.apexweather.data.remote.WuResponse
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import java.io.IOException

private class FakeWu(
    private val onNear: () -> WuNearResponse,
) : WeatherUndergroundApi {
    override suspend fun current(stationId: String, apiKey: String): Response<WuResponse> =
        Response.success(WuResponse())

    override suspend fun near(geocode: String, apiKey: String): WuNearResponse = onNear()
}

class WuKeyCheckerTest {
    private val reported = mutableListOf<WuKeyVerdict>()
    private val reporter = WuKeyReporter { reported += it }

    private fun checker(key: String?, onNear: () -> WuNearResponse) = WuKeyChecker(
        api = FakeWu(onNear),
        keySource = { key },
        reporter = reporter,
    )

    private fun httpError(code: Int): Throwable = retrofit2.HttpException(
        Response.error<WuNearResponse>(code, "".toResponseBody("application/json".toMediaType())),
    )

    @Test
    fun anAnsweredCallReportsGood() = runTest {
        checker("k") { WuNearResponse(WuNearLocation(stationId = listOf("ITIROL16"))) }
            .check(46.688958, 11.156624)
        assertEquals(listOf(WuKeyVerdict.CHECKING, WuKeyVerdict.GOOD), reported)
    }

    @Test
    fun aRefusedKeyIsReportedAsRefused() = runTest {
        checker("k") { throw httpError(401) }.check(46.688958, 11.156624)
        assertEquals(listOf(WuKeyVerdict.CHECKING, WuKeyVerdict.REFUSED), reported)
    }

    @Test
    fun anExhaustedQuotaIsReportedAsSuch() = runTest {
        checker("k") { throw httpError(429) }.check(46.688958, 11.156624)
        assertEquals(listOf(WuKeyVerdict.CHECKING, WuKeyVerdict.OVER_QUOTA), reported)
    }

    @Test
    fun aDroppedConnectionAccusesNobody() = runTest {
        checker("k") { throw IOException("reset") }.check(46.688958, 11.156624)
        assertEquals(listOf(WuKeyVerdict.CHECKING, WuKeyVerdict.OFFLINE), reported)
    }

    /** No key is nothing to check, and no request may go out for one. */
    @Test
    fun withNoKeyNothingIsCheckedAndNothingIsReported() = runTest {
        var called = false
        checker(null) { called = true; WuNearResponse() }.check(46.688958, 11.156624)
        assertEquals(listOf(WuKeyVerdict.UNCHECKED), reported)
        assertEquals(false, called)
    }

    @Test
    fun aBlankKeyIsTreatedAsNoKey() = runTest {
        checker("   ") { WuNearResponse() }.check(46.688958, 11.156624)
        assertEquals(listOf(WuKeyVerdict.UNCHECKED), reported)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.WuKeyCheckerTest'`
Expected: FAIL — `Unresolved reference: WuKeyReporter`.

- [ ] **Step 3: Write the reporter and the checker**

Create `app/src/main/kotlin/it/apexweather/data/WuKeyReporter.kt`:

```kotlin
package it.apexweather.data

/**
 * Where a verdict about the Weather Underground key is written down.
 *
 * An interface for the same reason [WuKeySource] is one: `WeatherRepository` knows nothing about
 * settings and is not going to start. It observes what the key did and hands that observation over;
 * somewhere else decides that "somewhere else" is DataStore.
 */
fun interface WuKeyReporter {
    suspend fun report(verdict: WuKeyVerdict)
}
```

Create `app/src/main/kotlin/it/apexweather/data/WuKeyChecker.kt`:

```kotlin
package it.apexweather.data

import it.apexweather.data.remote.WeatherUndergroundApi
import retrofit2.HttpException
import javax.inject.Inject

/**
 * One deliberate test of the reader's key, run when they change it.
 *
 * `v3/location/near` at the place's own coordinates, because it proves the key end to end and is
 * the same call the stations screen makes — so the check spends nothing that was not going to be
 * spent anyway. One request against a cap of 1500 a day.
 *
 * Without this, a key is silent until something refreshes, and the thirty-minute staleness rule
 * puts that up to half an hour away. A wrong key and a key that has simply not been used yet then
 * look identical, which is exactly the confusion that cost an afternoon on 2026-09-23.
 */
class WuKeyChecker @Inject constructor(
    private val api: WeatherUndergroundApi,
    private val keySource: WuKeySource,
    private val reporter: WuKeyReporter,
) {
    suspend fun check(lat: Double, lon: Double) {
        val key = keySource.key()?.takeIf { it.isNotBlank() }
        if (key == null) {
            // Nothing to check, and no request may go out carrying an empty key.
            reporter.report(WuKeyVerdict.UNCHECKED)
            return
        }
        reporter.report(WuKeyVerdict.CHECKING)
        val verdict = try {
            api.near("$lat,$lon", key)
            WuKeyVerdict.GOOD
        } catch (e: HttpException) {
            WuKeyVerdicts.of(e.code())
        } catch (e: Exception) {
            WuKeyVerdicts.of(e)
        }
        reporter.report(verdict)
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.WuKeyCheckerTest'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Report from the ordinary fetch too**

In `app/src/main/kotlin/it/apexweather/data/WeatherRepository.kt`, add `private val wuKeyReporter: WuKeyReporter,` to the constructor beside `private val wuKey: WuKeySource,`, and replace `fetchAmateur`'s body:

```kotlin
    private suspend fun fetchAmateur(station: NearbyStation): StationObservation? {
        val key = wuKey.key()?.takeIf { it.isNotBlank() } ?: return null
        val response = try {
            wu.current(station.code, key)
        } catch (e: Exception) {
            // A verdict written from the ordinary path is what keeps the settings line honest
            // between explicit checks: a key revoked next month, or a quota gone at four in the
            // afternoon, stops claiming to be good without anybody re-checking by hand.
            wuKeyReporter.report(WuKeyVerdicts.of(e))
            throw e
        }
        wuKeyReporter.report(WuKeyVerdicts.of(response.code()))
        return WeatherUndergroundMapper.map(response.body().takeIf { response.isSuccessful }, station)
    }
```

Add `import it.apexweather.data.remote.WeatherUndergroundMapper` if it is not already imported; `WuKeyVerdicts` and `WuKeyReporter` are in the same package and need none.

- [ ] **Step 6: Bind the reporter and expose the check**

In `app/src/main/kotlin/it/apexweather/di/AppModule.kt`, beside the existing `WuKeySource` provider, add:

```kotlin
    @Provides @Singleton
    fun wuKeyReporter(settings: SettingsRepository): WuKeyReporter = WuKeyReporter { verdict ->
        // CHECKING is transient and carries no time; the rest stamp the moment they were reached.
        settings.setWuKeyVerdict(verdict, if (verdict == WuKeyVerdict.CHECKING) null else System.currentTimeMillis())
    }
```

Add the imports `it.apexweather.data.WuKeyReporter` and `it.apexweather.data.WuKeyVerdict`.

In `app/src/main/kotlin/it/apexweather/ui/settings/SettingsViewModel.kt`, take the checker and the state holder, and run a check when the key is set:

```kotlin
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val keyChecker: WuKeyChecker,
    private val holder: WeatherStateHolder,
) : ViewModel() {
```

and replace `setWuApiKey`:

```kotlin
    /**
     * The key is written first and checked after, because the checker reads it back through
     * [it.apexweather.data.WuKeySource] — checking before the write would test the old one.
     *
     * Debounced by [KEY_CHECK_DELAY_MS], because this is called on every keystroke: a typed
     * 32-character key would otherwise spend 32 of the day's 1500 requests proving the first
     * thirty-one prefixes wrong.
     */
    fun setWuApiKey(v: String) {
        keyCheck?.cancel()
        keyCheck = viewModelScope.launch {
            repo.setWuApiKey(v)
            delay(KEY_CHECK_DELAY_MS)
            val place = holder.weather.value.place ?: return@launch
            keyChecker.check(place.lat, place.lon)
        }
    }

    private var keyCheck: Job? = null

    companion object {
        /** Long enough that a key being typed is checked once, at the end. */
        const val KEY_CHECK_DELAY_MS = 1_200L
    }
```

Add the imports `kotlinx.coroutines.Job`, `kotlinx.coroutines.delay`, `it.apexweather.data.WuKeyChecker` and `it.apexweather.ui.WeatherStateHolder`.

- [ ] **Step 7: Run the whole JVM suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. If `WeatherRepositoryTest` fails to construct the repository, add a no-op `WuKeyReporter {}` to its constructor call.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/data/WuKeyReporter.kt \
        app/src/main/kotlin/it/apexweather/data/WuKeyChecker.kt \
        app/src/main/kotlin/it/apexweather/data/WeatherRepository.kt \
        app/src/main/kotlin/it/apexweather/di/AppModule.kt \
        app/src/main/kotlin/it/apexweather/ui/settings/SettingsViewModel.kt \
        app/src/test/kotlin/it/apexweather/data/WuKeyCheckerTest.kt \
        app/src/test/kotlin/it/apexweather/data/WeatherRepositoryTest.kt
git commit -m "feat: the key says whether it works"
```

---

### Task 6: the stations screen's state

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/ui/stations/NearbyStationsState.kt`
- Test: `app/src/test/kotlin/it/apexweather/ui/stations/NearbyStationsStateBuilderTest.kt`

**Interfaces:**
- Consumes: `StationHeight.heightOf`, `StationHeight.disputed` (Task 3); `ChosenStation` (Task 1).
- Produces: `StationProbe` gains `demAltitudeM: Int?`; `StationRow` gains `demAltitudeM: Int?`, `selectable: Boolean`, loses `verifiedAltitudeM` and `altitudeUnverified` in favour of `heightDisputed: Boolean`; `NearbyStationsUiState` gains `heightsUnknown: Boolean`; `StationRow.asChosen(istat: String): ChosenStation?`.

- [ ] **Step 1: Write the failing test**

Replace `app/src/test/kotlin/it/apexweather/ui/stations/NearbyStationsStateBuilderTest.kt` (create it if the file does not exist) with:

```kotlin
package it.apexweather.ui.stations

import it.apexweather.domain.NearbyStation
import it.apexweather.domain.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class NearbyStationsStateBuilderTest {
    private val official = NearbyStation(
        code = "23200MS", name = "Meran", lat = 46.688, lon = 11.1366,
        altitudeM = 330, distanceKm = 1.53, network = "siag",
    )
    private val catalogue = NearbyStation(
        code = "ITIROL16", name = "Tirolo - Tirol", lat = 46.693246, lon = 11.155237,
        altitudeM = 634, distanceKm = 0.49, network = "wu",
    )
    private val place = Place(
        istat = "021101", nameDe = "Dorf Tirol", nameIt = "Tirolo", nameEn = "Tirol",
        lat = 46.688958, lon = 11.156624, altitudeM = 594, district = 2,
        station = official, pws = catalogue,
    )

    private fun probe(code: String, claimed: Int?, dem: Int?, km: Double) = StationProbe(
        code = code, name = code, distanceKm = km, lat = 46.69, lon = 11.15,
        claimedAltitudeM = claimed, demAltitudeM = dem, reading = null,
    )

    private fun build(probes: List<StationProbe>) = NearbyStationsStateBuilder.build(
        place = place, probes = probes, provincial = null, locale = Locale.GERMAN,
    )

    @Test
    fun stationsAreOrderedByDistanceWithTheProvincialOneLast() {
        val state = build(listOf(probe("FAR", 600, 610, 2.0), probe("NEAR", 600, 610, 0.3)))
        assertEquals(listOf("NEAR", "FAR", "23200MS"), state.rows.map { it.code })
    }

    @Test
    fun theStationThisPlaceReadsIsMarkedChosen() {
        val state = build(listOf(probe("ITIROL16", 634, 639, 0.49)))
        assertTrue(state.rows.first { it.code == "ITIROL16" }.chosen)
        assertFalse(state.rows.first { it.code == "23200MS" }.chosen)
    }

    /** The ground is what gets stored; the claim is shown beside it so the difference is visible. */
    @Test
    fun aDisputedHeightIsMarkedAndTheGroundIsWhatWouldBeStored() {
        val row = build(listOf(probe("ITIROL25", 182, 631, 0.35))).rows.first { it.code == "ITIROL25" }
        assertTrue(row.heightDisputed)
        assertEquals(631, row.demAltitudeM)
        assertEquals(631, row.asChosen("021101")?.altitudeM)
    }

    @Test
    fun aClaimWithinTheGateIsNotMarked() {
        assertFalse(build(listOf(probe("ITIROL26", 669, 659, 0.68))).rows.first { it.code == "ITIROL26" }.heightDisputed)
    }

    /**
     * Without the ground there is no height to store, so nothing may be chosen — the reading is
     * still worth showing, because looking at what the neighbours say is half of what this screen
     * is for.
     */
    @Test
    fun withoutTheGroundNothingIsSelectable() {
        val state = build(listOf(probe("ITIROL26", 669, null, 0.68)))
        assertTrue(state.heightsUnknown)
        assertTrue(state.rows.none { it.selectable })
        assertNull(state.rows.first { it.code == "ITIROL26" }.asChosen("021101"))
    }

    /** The province's own station has a surveyed height and needs no elevation request. */
    @Test
    fun theProvincialStationIsSelectableEvenWithoutTheGround() {
        val state = build(listOf(probe("ITIROL26", 669, null, 0.68)))
        assertTrue(state.rows.first { it.code == "23200MS" }.selectable)
        assertEquals("siag", state.rows.first { it.code == "23200MS" }.asChosen("021101")?.network)
        assertEquals(330, state.rows.first { it.code == "23200MS" }.asChosen("021101")?.altitudeM)
    }

    /** The one already being read is not offered as something to pick. */
    @Test
    fun theChosenStationIsNotSelectable() {
        assertFalse(build(listOf(probe("ITIROL16", 634, 639, 0.49))).rows.first { it.code == "ITIROL16" }.selectable)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.stations.NearbyStationsStateBuilderTest'`
Expected: FAIL — `No value passed for parameter 'demAltitudeM'`.

- [ ] **Step 3: Rewrite `NearbyStationsState.kt`**

Replace the whole file with:

```kotlin
package it.apexweather.ui.stations

import it.apexweather.data.ChosenStation
import it.apexweather.domain.Place
import it.apexweather.domain.StationHeight
import it.apexweather.domain.model.StationObservation

/**
 * One station as the network answered for it.
 *
 * [reading] is null where the station answered but had nothing recent — Weather Underground returns
 * HTTP 204 for a live station that has not reported within the hour, and going quiet for an hour is
 * not the same thing as failing. [error] is for the second case.
 *
 * [claimedAltitudeM] is the station's *own* record of where it stands and [demAltitudeM] the ground
 * under its coordinates, from Open-Meteo's elevation endpoint. Both, because the difference is the
 * information: WU's elevation form is in feet, and ITIROL26 stood in the catalogue at 204 m against
 * real ground at 654.
 */
data class StationProbe(
    val code: String,
    val name: String,
    val distanceKm: Double,
    /** The station's own coordinates, which is where the ground under it was asked about. */
    val lat: Double,
    val lon: Double,
    val claimedAltitudeM: Int?,
    val demAltitudeM: Int?,
    val reading: StationObservation?,
    val error: String? = null,
)

data class StationRow(
    val code: String,
    val name: String,
    val distanceKm: Double,
    /** Carried so a chosen station is stored with the coordinates the models must be asked about. */
    val lat: Double,
    val lon: Double,
    /** What the station says about itself. Shown, and acted on by nothing. */
    val claimedAltitudeM: Int?,
    /** The ground under it, which is the height this app will use. Null where it could not be asked. */
    val demAltitudeM: Int?,
    val reading: StationObservation?,
    val error: String? = null,
    /** The station this place actually reads, amateur or provincial. */
    val chosen: Boolean = false,
    /** The province's own network: the fallback, and the source of what an amateur station lacks. */
    val provincial: Boolean = false,
    /**
     * The station's claim about its own height is more than [StationHeight.MAX_DEM_DISAGREEMENT_M]
     * from the ground under it.
     *
     * Shown rather than acted on: the height used is the ground's either way. What it warns about
     * is that a record out by hundreds of metres suggests the coordinates may be somewhere else
     * entirely, which no elevation lookup can correct.
     */
    val heightDisputed: Boolean = false,
    /** Whether this station can be picked: it is not already chosen, and its height is known. */
    val selectable: Boolean = false,
) {
    /**
     * This row as something storable, or null where it may not be stored.
     *
     * Null for an amateur station whose ground is unknown. A height the app cannot check is a
     * height it will not act on, and `StationDownscale`'s cap is built out of exactly that number.
     */
    fun asChosen(istat: String): ChosenStation? {
        val reading = reading
        val altitude = if (provincial) claimedAltitudeM else StationHeight.heightOf(claimedAltitudeM, demAltitudeM)
        if (altitude == null) return null
        return ChosenStation(
            istat = istat,
            network = if (provincial) "siag" else "wu",
            code = code,
            // The reading's own name where there is one — "Tirolo - Tirol" is what the station's
            // owner called the place and is a better word for a reader than a station code.
            name = reading?.stationName ?: name,
            lat = lat,
            lon = lon,
            altitudeM = altitude,
            distanceKm = distanceKm,
        )
    }
}

data class NearbyStationsUiState(
    val placeName: String = "",
    val loading: Boolean = true,
    val rows: List<StationRow> = emptyList(),
    /** Nothing answered at all — offline, or the key was refused. */
    val failed: String? = null,
    /** The elevation request failed, so no amateur station's height is known and none may be picked. */
    val heightsUnknown: Boolean = false,
)

object NearbyStationsStateBuilder {
    fun build(
        place: Place,
        probes: List<StationProbe>,
        provincial: StationObservation?,
        locale: java.util.Locale,
    ): NearbyStationsUiState {
        val chosenCode = place.readingStation?.code
        val rows = probes.sortedBy { it.distanceKm }.map { p ->
            val height = StationHeight.heightOf(p.claimedAltitudeM, p.demAltitudeM)
            StationRow(
                code = p.code,
                name = p.name,
                distanceKm = p.distanceKm,
                lat = p.lat,
                lon = p.lon,
                claimedAltitudeM = p.claimedAltitudeM,
                demAltitudeM = p.demAltitudeM,
                reading = p.reading,
                error = p.error,
                chosen = p.code == chosenCode,
                heightDisputed = StationHeight.disputed(p.claimedAltitudeM, p.demAltitudeM),
                selectable = p.code != chosenCode && height != null,
            )
        }

        // The province's own goes last and under a rule rather than among them: it is where every
        // quantity an amateur station does not publish comes from, and its height was surveyed
        // rather than typed, so it needs no elevation request to be selectable.
        val official = place.station?.let { s ->
            StationRow(
                code = s.code,
                name = s.name,
                distanceKm = s.distanceKm,
                lat = s.lat,
                lon = s.lon,
                claimedAltitudeM = s.altitudeM,
                demAltitudeM = s.altitudeM,
                reading = provincial,
                chosen = s.code == chosenCode,
                provincial = true,
                selectable = s.code != chosenCode,
            )
        }

        return NearbyStationsUiState(
            placeName = place.name(locale),
            loading = false,
            rows = rows + listOfNotNull(official),
            heightsUnknown = probes.isNotEmpty() && probes.all { it.demAltitudeM == null },
        )
    }
}
```

`StationRow` no longer carries `verifiedAltitudeM` or `altitudeUnverified`; `demAltitudeM` and `heightDisputed` replace them, and the two old names must not survive anywhere — `./gradlew :app:compileDebugKotlin` will name every site that still uses them.

- [ ] **Step 4: Run the test and watch it pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.stations.NearbyStationsStateBuilderTest'`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/stations/NearbyStationsState.kt \
        app/src/test/kotlin/it/apexweather/ui/stations/NearbyStationsStateBuilderTest.kt
git commit -m "feat: a station row knows its ground and whether it can be picked"
```

---

### Task 7: the ViewModel asks for the ground and writes the choice

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/ui/stations/NearbyStationsViewModel.kt`
- Test: `app/src/test/kotlin/it/apexweather/ui/stations/NearbyStationsViewModelTest.kt`

**Interfaces:**
- Consumes: `OpenMeteoApi.elevation` (Task 3), `SettingsRepository.setChosenStation` (Task 1), `StationRow.asChosen` (Task 6).
- Produces: `NearbyStationsViewModel.choose(row: StationRow)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/it/apexweather/ui/stations/NearbyStationsViewModelTest.kt`:

```kotlin
package it.apexweather.ui.stations

import it.apexweather.data.ChosenStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The ViewModel's own fetch needs a Hilt graph and a network; what is worth pinning here is the
 * arithmetic it does with what comes back, which is pure and lives in these two helpers.
 */
class NearbyStationsViewModelTest {
    @Test
    fun coordinatesGoOutAsTwoCommaJoinedListsInTheSameOrder() {
        val points = listOf(46.693246 to 11.155237, 46.694926 to 11.154523)
        assertEquals("46.693246,46.694926", NearbyStationsViewModel.joinLatitudes(points))
        assertEquals("11.155237,11.154523", NearbyStationsViewModel.joinLongitudes(points))
    }

    /** An answer of the wrong length cannot be matched to its stations, so none of it is used. */
    @Test
    fun anElevationAnswerOfTheWrongLengthIsDiscardedWholesale() {
        assertEquals(listOf(null, null), NearbyStationsViewModel.heightsFrom(listOf(639.0), stations = 2))
        assertEquals(listOf(null, null), NearbyStationsViewModel.heightsFrom(null, stations = 2))
    }

    @Test
    fun anElevationAnswerIsRoundedToWholeMetres() {
        assertEquals(listOf(639, 659), NearbyStationsViewModel.heightsFrom(listOf(639.4, 658.6), stations = 2))
    }

    @Test
    fun chooseSendsTheRowsOwnRecord() {
        val row = StationRow(
            code = "ITIROL26", name = "Tirol", distanceKm = 0.68, lat = 46.694926, lon = 11.154523,
            claimedAltitudeM = 669, demAltitudeM = 659, reading = null, selectable = true,
        )
        val record: ChosenStation? = row.asChosen("021101")
        assertEquals("ITIROL26", record?.code)
        assertEquals(659, record?.altitudeM)
        assertEquals("wu", record?.network)
    }

    @Test
    fun aRowWithNoGroundHasNoRecordToSend() {
        val row = StationRow(
            code = "ITIROL26", name = "Tirol", distanceKm = 0.68, lat = 46.694926, lon = 11.154523,
            claimedAltitudeM = 669, demAltitudeM = null, reading = null, selectable = false,
        )
        assertNull(row.asChosen("021101"))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.stations.NearbyStationsViewModelTest'`
Expected: FAIL — `Unresolved reference: joinLatitudes`.

- [ ] **Step 3: Implement**

In `NearbyStationsViewModel.kt`, add `OpenMeteoApi` and `SettingsRepository` to the constructor:

```kotlin
@HiltViewModel
class NearbyStationsViewModel @Inject constructor(
    private val holder: WeatherStateHolder,
    private val wu: WeatherUndergroundApi,
    private val wuKey: WuKeySource,
    private val openMeteo: OpenMeteoApi,
    private val settings: SettingsRepository,
) : ViewModel() {
```

Add the pure helpers and `choose` to the `companion object` and the class:

```kotlin
    /**
     * Picks this station for this place, and reloads so the screen shows the new state.
     *
     * A row whose ground is unknown has no record to send — see [StationRow.asChosen] — and its
     * card offers no action, so this is a no-op rather than a guard that can be got round.
     */
    fun choose(row: StationRow) {
        val place = holder.weather.value.place ?: return
        val record = row.asChosen(place.istat) ?: return
        viewModelScope.launch {
            settings.setChosenStation(place.istat, record)
            load()
        }
    }

    companion object {
        const val NO_KEY = "no-key"

        fun joinLatitudes(points: List<Pair<Double, Double>>): String = points.joinToString(",") { it.first.toString() }

        fun joinLongitudes(points: List<Pair<Double, Double>>): String = points.joinToString(",") { it.second.toString() }

        /**
         * The ground under each station, or nulls throughout.
         *
         * An answer of the wrong length cannot be matched to the stations that were asked about,
         * and pairing them off by index anyway would put one station's ground under another's
         * name — which is the mistake this whole check exists to prevent, one level up.
         */
        fun heightsFrom(elevations: List<Double>?, stations: Int): List<Int?> =
            if (elevations == null || elevations.size != stations) List(stations) { null }
            else elevations.map { Math.round(it).toInt() }
    }
```

In `load()`, after `codes` and `byCode` are worked out and before the probes are fetched, gather the coordinates and ask for the ground. The `near` response carries no coordinates, so the point for each station is its own reading's — which means the elevation request has to follow the probes rather than run beside them. Restructure the tail of `load()`:

```kotlin
        val fetched = codes.map { code ->
            async {
                val attempt = runCatchingCancellable {
                    val response = wu.current(code, key)
                    response.body().takeIf { response.isSuccessful }
                }
                val body = attempt.getOrNull()
                val o = body?.observations?.firstOrNull()
                Fetched(
                    code = code,
                    name = o?.neighborhood ?: byCode[code]?.first ?: code,
                    lat = o?.lat ?: place.pws?.takeIf { it.code == code }?.lat,
                    lon = o?.lon ?: place.pws?.takeIf { it.code == code }?.lon,
                    distanceKm = byCode[code]?.second
                        ?: place.pws?.takeIf { it.code == code }?.distanceKm
                        ?: 0.0,
                    claimedAltitudeM = o?.metric?.elev?.toInt(),
                    reading = body?.let { WeatherUndergroundMapper.map(it, stationFor(code, place)) },
                    error = attempt.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName },
                )
            }
        }.awaitAll()

        // One request for every station on the screen. A station that did not answer has no
        // coordinates to ask about and is left with no ground, which makes it unselectable — which
        // is right, because nothing is known about it at all.
        val located = fetched.filter { it.lat != null && it.lon != null }
        val points = located.map { it.lat!! to it.lon!! }
        val elevations = if (points.isEmpty()) null else runCatchingCancellable {
            openMeteo.elevation(joinLatitudes(points), joinLongitudes(points)).elevation
        }.getOrNull()
        val ground = heightsFrom(elevations, points.size)
        val groundByCode = located.mapIndexed { i, f -> f.code to ground[i] }.toMap()

        _state.value = NearbyStationsStateBuilder.build(
            place = place,
            probes = fetched.map { it.toProbe(groundByCode[it.code]) },
            provincial = provincial,
            locale = Locale.getDefault(),
        )
    }

    /** What one station answered, before the ground under it is known. */
    private data class Fetched(
        val code: String,
        val name: String,
        val lat: Double?,
        val lon: Double?,
        val distanceKm: Double,
        val claimedAltitudeM: Int?,
        val reading: it.apexweather.domain.model.StationObservation?,
        val error: String?,
    ) {
        fun toProbe(demAltitudeM: Int?) = StationProbe(
            code = code, name = name, distanceKm = distanceKm,
            lat = lat ?: 0.0, lon = lon ?: 0.0,
            claimedAltitudeM = claimedAltitudeM, demAltitudeM = demAltitudeM,
            reading = reading, error = error,
        )
    }
```

`WuObservation` already carries `lat` and `lon` in the response but does not declare them; add them to `app/src/main/kotlin/it/apexweather/data/remote/WeatherUndergroundApi.kt`'s `WuObservation`:

```kotlin
    /** The station's own coordinates, which is where the ground under it gets asked about. */
    val lat: Double? = null,
    val lon: Double? = null,
```

Add the imports `it.apexweather.data.SettingsRepository` and `it.apexweather.data.remote.OpenMeteoApi` to the ViewModel.

- [ ] **Step 4: Run the test and watch it pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.stations.NearbyStationsViewModelTest'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Fix the stale doc on the WU API**

`WeatherUndergroundApi`'s KDoc still opens "The `near` and `history` endpoints are deliberately absent from the app", which stopped being true when the stations screen was built. Replace that paragraph:

```
 * `near` is here for the stations screen and for the key check, and for nothing on a schedule:
 * one open costs a `near` plus a `current` per station, around eleven requests against a cap of
 * 1500 a day, which is fine for something a reader opens deliberately and ruinous behind a timer.
 * Choosing a place's *default* station is still `tools/generate-places.py`'s job, done once against
 * a DEM and reviewed by a human. The `history` endpoint remains absent: the rapid history is not
 * served for every station — ITIROL26 answers 204 to it while ITIROL16 returns 263 readings a day
 * — so nothing may depend on it.
```

- [ ] **Step 6: Run the whole JVM suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/stations/NearbyStationsViewModel.kt \
        app/src/main/kotlin/it/apexweather/data/remote/WeatherUndergroundApi.kt \
        app/src/test/kotlin/it/apexweather/ui/stations/NearbyStationsViewModelTest.kt
git commit -m "feat: the stations screen asks for the ground and can store a choice"
```

---

### Task 8: the screen

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/ui/stations/NearbyStationsScreen.kt`
- Modify: `app/src/main/kotlin/it/apexweather/ui/navigation/AppNavigation.kt:263-265`
- Modify: `app/src/main/res/values/strings.xml`, `values-it/strings.xml`, `values-en/strings.xml`
- Test: `app/src/androidTest/kotlin/it/apexweather/ui/stations/NearbyStationsContentTest.kt`

**Interfaces:**
- Consumes: `NearbyStationsUiState`, `StationRow` (Task 6); `NearbyStationsViewModel.choose` (Task 7).
- Produces: `NearbyStationsContent(state, onBack, onChoose, now)`; `NearbyStationsScreen(onBack, viewModel)`.

- [ ] **Step 1: Add the strings**

To `app/src/main/res/values/strings.xml`, beside the other `stations_` keys:

```xml
    <string name="stations_choose">Wählen</string>
    <string name="stations_in_use">benutzt</string>
    <string name="stations_code">Kennung %1$s</string>
    <string name="stations_private_heading">Private Stationen</string>
    <string name="stations_official_heading">Landesmessnetz</string>
    <string name="stations_height_disputed">Höhenangabe weicht %1$s vom Gelände ab</string>
    <string name="stations_height_both">%1$s → %2$s</string>
    <string name="stations_heights_unknown">Geländehöhen nicht abrufbar — es lässt sich gerade keine Station wählen.</string>
    <string name="stations_no_horizon">Kein Horizontprofil: Sonnenauf- und -untergang ohne Berge gerechnet.</string>
```

To `values-it/strings.xml`:

```xml
    <string name="stations_choose">Scegli</string>
    <string name="stations_in_use">in uso</string>
    <string name="stations_code">Codice %1$s</string>
    <string name="stations_private_heading">Stazioni private</string>
    <string name="stations_official_heading">Rete provinciale</string>
    <string name="stations_height_disputed">Quota dichiarata diversa dal terreno di %1$s</string>
    <string name="stations_height_both">%1$s → %2$s</string>
    <string name="stations_heights_unknown">Quote del terreno non disponibili: per ora non si può scegliere una stazione.</string>
    <string name="stations_no_horizon">Nessun profilo dell\'orizzonte: alba e tramonto calcolati senza montagne.</string>
```

To `values-en/strings.xml`:

```xml
    <string name="stations_choose">Use</string>
    <string name="stations_in_use">in use</string>
    <string name="stations_code">Code %1$s</string>
    <string name="stations_private_heading">Private stations</string>
    <string name="stations_official_heading">Provincial network</string>
    <string name="stations_height_disputed">Recorded height is %1$s off the ground</string>
    <string name="stations_height_both">%1$s → %2$s</string>
    <string name="stations_heights_unknown">Ground heights unavailable — no station can be chosen right now.</string>
    <string name="stations_no_horizon">No horizon profile: sunrise and sunset computed without mountains.</string>
```

Note the escaped apostrophes in the Italian strings: an unescaped `'` fails aapt2.

- [ ] **Step 2: Write the failing instrumented test**

Create `app/src/androidTest/kotlin/it/apexweather/ui/stations/NearbyStationsContentTest.kt`:

```kotlin
package it.apexweather.ui.stations

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import it.apexweather.ui.theme.ApexTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class NearbyStationsContentTest {
    @get:Rule val rule = createComposeRule()

    private val now: Instant = Instant.parse("2026-09-23T06:50:00Z")

    private fun row(code: String, chosen: Boolean = false, selectable: Boolean = true) = StationRow(
        code = code, name = code, distanceKm = 0.5, lat = 46.69, lon = 11.15,
        claimedAltitudeM = 634, demAltitudeM = 639, reading = null,
        chosen = chosen, selectable = selectable,
    )

    private fun show(
        state: NearbyStationsUiState,
        onBack: () -> Unit = {},
        onChoose: (StationRow) -> Unit = {},
    ) = rule.setContent {
        ApexTheme { NearbyStationsContent(state, onBack = onBack, onChoose = onChoose, now = now) }
    }

    @Test
    fun theBackArrowReportsIt() {
        var backed = false
        show(NearbyStationsUiState(loading = false, rows = listOf(row("ITIROL16"))), onBack = { backed = true })
        rule.onNodeWithTag("stations_back").performClick()
        assertTrue("the back arrow did not report", backed)
    }

    @Test
    fun choosingACardReportsThatStationAndNoOther() {
        var picked: String? = null
        show(
            NearbyStationsUiState(loading = false, rows = listOf(row("ITIROL16"), row("ITIROL26"))),
            onChoose = { picked = it.code },
        )
        rule.onNodeWithTag("station_choose_ITIROL26").performClick()
        assertEquals("ITIROL26", picked)
    }

    /** The one already in use is not offered as something to pick. */
    @Test
    fun theChosenCardOffersNoAction() {
        show(NearbyStationsUiState(loading = false, rows = listOf(row("ITIROL16", chosen = true, selectable = false))))
        rule.onNodeWithTag("station_ITIROL16").assertIsDisplayed()
        rule.onNodeWithTag("station_choose_ITIROL16").assertDoesNotExist()
    }

    @Test
    fun withNoGroundNothingCanBeChosenAndTheReasonIsOnScreen() {
        show(
            NearbyStationsUiState(
                loading = false, heightsUnknown = true,
                rows = listOf(row("ITIROL16", selectable = false), row("ITIROL26", selectable = false)),
            ),
        )
        rule.onNodeWithTag("stations_heights_unknown").assertIsDisplayed()
        rule.onNodeWithTag("station_choose_ITIROL16").assertDoesNotExist()
        rule.onNodeWithTag("station_choose_ITIROL26").assertDoesNotExist()
    }

    @Test
    fun aDisputedHeightIsMarkedOnItsCardAndOnNoOther() {
        show(
            NearbyStationsUiState(
                loading = false,
                rows = listOf(row("ITIROL16"), row("ITIROL25").copy(claimedAltitudeM = 182, demAltitudeM = 631, heightDisputed = true)),
            ),
        )
        rule.onNodeWithTag("station_disputed_ITIROL25").assertIsDisplayed()
        rule.onNodeWithTag("station_disputed_ITIROL16").assertDoesNotExist()
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

Run: `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.stations.NearbyStationsContentTest`
Expected: FAIL to compile — `No value passed for parameter 'onBack'`.

- [ ] **Step 4: Rewrite the screen**

Replace `app/src/main/kotlin/it/apexweather/ui/stations/NearbyStationsScreen.kt` with a card-per-station screen. The structure:

```kotlin
@Composable
fun NearbyStationsScreen(onBack: () -> Unit, viewModel: NearbyStationsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NearbyStationsContent(state, onBack = onBack, onChoose = viewModel::choose)
}

@Composable
fun NearbyStationsContent(
    state: NearbyStationsUiState,
    onBack: () -> Unit,
    onChoose: (StationRow) -> Unit,
    now: Instant = Instant.now(),
) {
    val formats = LocalFormats.current
    Column(Modifier.fillMaxSize().statusBarsPadding().testTag("nearby_stations")) {
        // The same shape as PlacePickerContent's header, for the same reason: this is a screen with
        // a back-stack entry and it needs a visible way out, not only the system gesture.
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("stations_back")) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
            }
            Column {
                Text(stringResource(R.string.stations_title), style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(state.placeName, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
            }
        }
        if (state.loading) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
            }
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxWidth().testTag("stations_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.failed?.let { failure ->
                item {
                    Text(
                        if (failure == NearbyStationsViewModel.NO_KEY) stringResource(R.string.stations_no_key) else failure,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("stations_failed"),
                    )
                }
            }
            if (state.heightsUnknown) {
                item {
                    Text(
                        stringResource(R.string.stations_heights_unknown),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("stations_heights_unknown"),
                    )
                }
            }
            val private = state.rows.filterNot { it.provincial }
            val official = state.rows.filter { it.provincial }
            if (private.isNotEmpty()) {
                item { SectionHeading(stringResource(R.string.stations_private_heading)) }
                items(private, key = { it.code }) { StationCard(it, formats, now, onChoose) }
            }
            if (official.isNotEmpty()) {
                item { SectionHeading(stringResource(R.string.stations_official_heading)) }
                items(official, key = { it.code }) { StationCard(it, formats, now, onChoose) }
            }
        }
    }
}
```

`StationCard` is a `GlassCard` (the app's own, from `ui/common`) carrying, in order: a row with the name (`CompactLabel`, `maxLines = 1`), a spacer with `weight(1f)`, and either the `● benutzt` marker (`testTag("station_chosen_${row.code}")`) or — when `row.selectable` — a `TextButton` tagged `station_choose_${row.code}` reading `R.string.stations_choose` and calling `onChoose(row)`; then the station code in `labelSmall` (`R.string.stations_code`); then a metadata line of distance and height; then the quantities; then, where `row.heightDisputed`, a line tagged `station_disputed_${row.code}` in `colorScheme.error`; and where the row is `chosen` and not `provincial`, the `stations_no_horizon` note. The whole card carries `Modifier.testTag("station_${row.code}")`.

The height line uses both numbers where they differ:

```kotlin
// `Format.metres` rounds to the nearest 50 m, which is right for a freezing level and wrong here:
// the whole point of this line is 634 against 639, and a 50 m grid would print them the same.
fun metres(v: Int) = stringResource(R.string.unit_metres, formats.whole(v))
val height = when {
    row.demAltitudeM == null -> row.claimedAltitudeM?.let { metres(it) }
    row.claimedAltitudeM == null || row.claimedAltitudeM == row.demAltitudeM -> metres(row.demAltitudeM)
    else -> stringResource(R.string.stations_height_both, formats.whole(row.claimedAltitudeM), metres(row.demAltitudeM))
}
```

Keep `values(...)` exactly as it is — a dash where a station publishes nothing, never a zero — but lay its parts out as separate `Text`s in a `FlowRow` rather than joining them with spaces, so they wrap at a large font scale.

`SectionHeading` is a `Text` in `labelSmall`, `Color.White.copy(alpha = 0.7f)`, with `Modifier.padding(top = 6.dp)`.

- [ ] **Step 5: Wire the back arrow through navigation**

In `app/src/main/kotlin/it/apexweather/ui/navigation/AppNavigation.kt`, replace the `NearbyStationsRoute` composable:

```kotlin
                composable<NearbyStationsRoute> {
                    // Dropped unless resumed, so a double tap on the arrow pops once — the same
                    // guard the statistics screen uses.
                    NearbyStationsScreen(onBack = dropUnlessResumed { nav.popBackStack() })
                }
```

- [ ] **Step 6: Run the instrumented test and watch it pass**

Run: `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.stations.NearbyStationsContentTest`
Expected: PASS, 5 tests.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/stations/NearbyStationsScreen.kt \
        app/src/main/kotlin/it/apexweather/ui/navigation/AppNavigation.kt \
        app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml app/src/main/res/values-en/strings.xml \
        app/src/androidTest/kotlin/it/apexweather/ui/stations/NearbyStationsContentTest.kt
git commit -m "feat: a stations screen you can get out of, and choose from"
```

---

### Task 9: the key's verdict on the settings screen

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`, `values-it/strings.xml`, `values-en/strings.xml`
- Test: `app/src/androidTest/kotlin/it/apexweather/ui/settings/SettingsContentTest.kt`

**Interfaces:**
- Consumes: `AppSettings.wuKeyVerdict`, `AppSettings.wuKeyCheckedAtMs` (Task 4).
- Produces: nothing later depends on.

- [ ] **Step 1: Add the strings**

`values/strings.xml`:

```xml
    <string name="wu_key_checking">wird geprüft …</string>
    <string name="wu_key_good">gültig · zuletzt geprüft %1$s</string>
    <string name="wu_key_refused">abgelehnt — Schlüssel prüfen</string>
    <string name="wu_key_over_quota">Tageskontingent erschöpft</string>
    <string name="wu_key_offline">keine Verbindung — nicht geprüft</string>
```

`values-it/strings.xml`:

```xml
    <string name="wu_key_checking">verifica in corso …</string>
    <string name="wu_key_good">valida · verificata alle %1$s</string>
    <string name="wu_key_refused">rifiutata — controlla la chiave</string>
    <string name="wu_key_over_quota">quota giornaliera esaurita</string>
    <string name="wu_key_offline">nessuna connessione — non verificata</string>
```

`values-en/strings.xml`:

```xml
    <string name="wu_key_checking">checking …</string>
    <string name="wu_key_good">valid · last checked %1$s</string>
    <string name="wu_key_refused">refused — check the key</string>
    <string name="wu_key_over_quota">daily quota used up</string>
    <string name="wu_key_offline">no connection — not checked</string>
```

- [ ] **Step 2: Write the failing instrumented test**

Add to `app/src/androidTest/kotlin/it/apexweather/ui/settings/SettingsContentTest.kt`:

```kotlin
    /** Nothing is claimed about a key nothing has tried. */
    @Test
    fun anUncheckedKeySaysNothing() {
        show(AppSettings(amateurStations = true, wuApiKey = "k", wuKeyVerdict = WuKeyVerdict.UNCHECKED))
        rule.onNodeWithTag("wu_key_verdict").assertDoesNotExist()
    }

    @Test
    fun aRefusedKeySaysSo() {
        show(AppSettings(amateurStations = true, wuApiKey = "k", wuKeyVerdict = WuKeyVerdict.REFUSED))
        rule.onNodeWithTag("wu_key_verdict").performScrollTo().assertIsDisplayed()
    }

    /**
     * Three failures, three sentences, and each one is its own test method: `createComposeRule`
     * refuses a second `setContent` in one method, so a loop over the verdicts cannot be written.
     *
     * A reader whose quota is gone must not be sent off to re-type a key that is perfectly good,
     * which is what one shared "funktioniert nicht" would do.
     */
    @Test
    fun anExhaustedQuotaSaysSo() {
        show(AppSettings(amateurStations = true, wuApiKey = "k", wuKeyVerdict = WuKeyVerdict.OVER_QUOTA))
        rule.onNodeWithTag("wu_key_verdict").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aConnectionFailureSaysSo() {
        show(AppSettings(amateurStations = true, wuApiKey = "k", wuKeyVerdict = WuKeyVerdict.OFFLINE))
        rule.onNodeWithTag("wu_key_verdict").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aGoodKeySaysSo() {
        show(AppSettings(amateurStations = true, wuApiKey = "k", wuKeyVerdict = WuKeyVerdict.GOOD, wuKeyCheckedAtMs = 1790146074000L))
        rule.onNodeWithTag("wu_key_verdict").performScrollTo().assertIsDisplayed()
    }

    /** With the feature off there is no key field and therefore no verdict under it. */
    @Test
    fun noVerdictIsShownWhilePrivateStationsAreOff() {
        show(AppSettings(amateurStations = false, wuApiKey = "k", wuKeyVerdict = WuKeyVerdict.GOOD))
        rule.onNodeWithTag("wu_key_verdict").assertDoesNotExist()
    }

    /**
     * The five states that are shown each need their own sentence. This does not render anything;
     * it fails the day somebody adds a seventh verdict and leaves the `when` in SettingsScreen
     * with a branch that reads like another state's.
     */
    @Test
    fun everyVerdictButUncheckedHasItsOwnWording() {
        val shown = WuKeyVerdict.entries.filterNot { it == WuKeyVerdict.UNCHECKED }
        val strings = listOf(
            R.string.wu_key_checking, R.string.wu_key_good, R.string.wu_key_refused,
            R.string.wu_key_over_quota, R.string.wu_key_offline,
        )
        assertEquals(shown.size, strings.size)
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val texts = strings.map { context.getString(it, "08:47") }
        assertEquals("two verdicts read the same: $texts", texts.size, texts.toSet().size)
    }
```

Add `import it.apexweather.R` and `import it.apexweather.data.WuKeyVerdict` to the test file.

- [ ] **Step 3: Run the tests and watch them fail**

Run: `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.settings.SettingsContentTest`
Expected: FAIL — `wu_key_verdict` does not exist.

- [ ] **Step 4: Implement**

In `SettingsScreen.kt`, inside the `if (settings.amateurStations)` block and directly after the `OutlinedTextField`, add:

```kotlin
                // What the app last saw happen to this key, rather than what the reader hopes.
                // Three failures and not one, because a refusal wants re-typing, an exhausted
                // quota wants waiting, and a dropped connection wants nothing at all.
                val verdict = when (settings.wuKeyVerdict) {
                    WuKeyVerdict.UNCHECKED -> null
                    WuKeyVerdict.CHECKING -> stringResource(R.string.wu_key_checking)
                    WuKeyVerdict.GOOD -> stringResource(
                        R.string.wu_key_good,
                        settings.wuKeyCheckedAtMs
                            ?.let { Format.time(Instant.ofEpochMilli(it), SouthTyrol.ZONE, formats) }
                            .orEmpty(),
                    )
                    WuKeyVerdict.REFUSED -> stringResource(R.string.wu_key_refused)
                    WuKeyVerdict.OVER_QUOTA -> stringResource(R.string.wu_key_over_quota)
                    WuKeyVerdict.OFFLINE -> stringResource(R.string.wu_key_offline)
                }
                verdict?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (settings.wuKeyVerdict == WuKeyVerdict.GOOD) MaterialTheme.colorScheme.primary
                        else if (settings.wuKeyVerdict == WuKeyVerdict.CHECKING) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp).testTag("wu_key_verdict"),
                    )
                }
```

Add `val formats = LocalFormats.current` at the top of `SettingsContent` if it is not there already. `Format.time(t: Instant, zone: ZoneId, f: Formats)` is the clock — `Format.timestamp` would read "vor 3 Minuten", which is a different sentence and would fight the one around it. Add the imports `it.apexweather.data.WuKeyVerdict`, `it.apexweather.domain.SouthTyrol`, `it.apexweather.ui.common.Format`, `it.apexweather.ui.common.LocalFormats` and `java.time.Instant`.

- [ ] **Step 5: Run the tests and watch them pass**

Run: `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.settings.SettingsContentTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/settings/SettingsScreen.kt \
        app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml app/src/main/res/values-en/strings.xml \
        app/src/androidTest/kotlin/it/apexweather/ui/settings/SettingsContentTest.kt
git commit -m "feat: the settings screen says whether the key works"
```

---

### Task 10: the gates, the phone, and the release

**Files:**
- Modify: `CLAUDE.md` (the `data/Place.kt` and stations bullets)
- Modify: `app/build.gradle.kts` (version bump, at the end)

- [ ] **Step 1: Run every JVM gate**

```bash
./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintDebug
```

Expected: BUILD SUCCESSFUL. Lint runs with `warningsAsErrors`, so an unused import fails it.

- [ ] **Step 2: Run the whole instrumented suite on the emulator**

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

Expected: BUILD SUCCESSFUL. **Not on the phone** — a connected run uninstalls the app and deletes `station_history`.

- [ ] **Step 3: Build the release and smoke it**

```bash
./gradlew :app:assembleRelease && ./tools/release-smoke.sh emulator-5554
```

Expected: `release smoke: OK`. This is the only check that exercises Hilt's graph, kotlinx-serialization and Glance's layout lookup *after* R8 — and `ChosenStation` is a new `@Serializable` type reached through DataStore, which is exactly the shape that broke v0.29.0.

- [ ] **Step 4: Verify on the phone by hand**

Install the release build with `adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/release/ApexWeather-release.apk` — `install -r` keeps the app's data, so `station_history` survives. Then check, and write down what was seen:

1. Settings: clear the key, type a wrong one, and watch the line read *abgelehnt*.
2. Type the real key and watch it read *gültig* with a time.
3. Open the stations screen from the station card; press the back arrow; it returns to the home screen.
4. Choose a different station; the hero's provenance line names it within one refresh (use *Jetzt aktualisieren* — the thirty-minute rule means a restart alone will not refetch).
5. Choose the provincial station; the hero names Meran.
6. Turn *Private Wetterstationen* off; the hero names Meran whatever is chosen.

- [ ] **Step 5: Update `CLAUDE.md`**

Add to the `domain/Place.kt` bullet: that `forSettings` now applies the reader's own per-place choice first and the two global conditions after it, and why. Add to the stations material: that a station may only be chosen once its height has been checked against Open-Meteo's elevation endpoint, that the DEM's answer is what is stored, and that `MAX_PLAUSIBLE_SLOPE_M_PER_KM` is gone because the app can now ask the question the generator was asking. Add to the settings material: the key's verdict, its six states, and that three of them are failures on purpose.

- [ ] **Step 6: Commit, merge, tag, push**

```bash
git add CLAUDE.md && git commit -m "docs: the reader's station choice and the key's verdict"
git checkout main && git merge --no-ff <branch> -m "Merge: the reader chooses the thermometer"
# bump apexVersionName / apexVersionCode in app/build.gradle.kts, then:
git commit -am "chore: v0.31.0"
git tag -a v0.31.0 -m "v0.31.0" && git push origin main && git push origin v0.31.0
```

- [ ] **Step 7: Verify the published APK**

```bash
gh release download v0.31.0 -p "*.apk" -D /tmp/v310
adb -s emulator-5554 install -r /tmp/v310/*.apk
adb -s emulator-5554 shell am start -n it.apexweather/.MainActivity
adb -s emulator-5554 shell pidof it.apexweather
```

Expected: a non-empty pid, and `versionName=0.31.0` from `adb shell dumpsys package it.apexweather`. A broken v0.29.0 shipped because this step was skipped; if the pid is empty, mark the release a draft before anything else.
