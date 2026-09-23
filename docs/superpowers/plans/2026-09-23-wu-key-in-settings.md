# The Weather Underground key in settings — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the Weather Underground key from build time to a field the reader types, add the one generator override the `near` endpoint makes necessary, and fill the catalogue's `pws` entries without touching anything else in it.

**Architecture:** The key becomes `AppSettings.wuApiKey` in DataStore and reaches `WeatherRepository` as a `suspend () -> String?` rather than an injected value — the same shape `ForegroundRefreshLoop` takes its inputs in, and the shape that removes the compile-time constant R8 folded away in v0.29.0. One function, `Place.forSettings`, decides whether a place reads its amateur station at all.

**Tech Stack:** Kotlin, DataStore Preferences, Hilt, Jetpack Compose, JUnit4, Python 3 for the generator.

Spec: `docs/superpowers/specs/2026-09-23-wu-key-in-settings-design.md`

## Global Constraints

- **`tools/release-smoke.sh` must pass on a minified build before any tag.** This change exists because the previous shape of it shipped an APK that could not start, and the smoke is the only check that exercises Hilt's graph and R8 output.
- Every new string key goes into all three of `values/strings.xml`, `values-it/strings.xml`, `values-en/strings.xml`. An apostrophe in an Android string must be escaped (`\'`) or aapt2 fails with "Invalid unicode escape sequence".
- Nothing that runs on a device may assert a German string; CI emulators are en-US.
- `./gradlew :app:lintDebug` runs with `warningsAsErrors`.
- Device tests run on an emulator (`r8verify34`, API 34), **never** the phone `RZCXA1ZEXJE`.
- **Compile all three source sets** after changing a public signature: `:app:compileDebugKotlin`, `:app:compileDebugUnitTestKotlin`, **and `:app:compileDebugAndroidTestKotlin`**. Skipping the last one put a broken `SettingsContentTest` on `main` in this session.
- Never commit a key. `local.properties` is gitignored; after this change it should hold no `wu.apiKey` at all.

---

### Task 1: The key is a setting

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/data/SettingsRepository.kt`
- Test: `app/src/test/kotlin/it/apexweather/data/SettingsRepositoryTest.kt`

**Interfaces:**
- Produces: `AppSettings.wuApiKey: String?` (null when unset **or blank**) and `SettingsRepository.setWuApiKey(v: String)`. Tasks 2, 3 and 4 read the field.

- [ ] **Step 1: Write the failing test**

Append to `SettingsRepositoryTest`, following the shape of the neighbouring tests in that file:

```kotlin
    @Test
    fun `the weather underground key round-trips`() = runTest {
        repo.setWuApiKey("abc123")
        assertEquals("abc123", repo.settings.first().wuApiKey)
    }

    /**
     * Blank reads back as absent, so every consumer has one condition to check rather than two.
     * A reader who clears the field has removed the key, and "" is how a text field says that.
     */
    @Test
    fun `a blank key reads back as absent`() = runTest {
        repo.setWuApiKey("abc123")
        repo.setWuApiKey("   ")
        assertNull(repo.settings.first().wuApiKey)
    }

    @Test
    fun `no key by default`() = runTest {
        assertNull(repo.settings.first().wuApiKey)
    }
```

If the existing tests name the repository or the flow differently, use their names — do not rename production members to suit a new test.

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.SettingsRepositoryTest'`
Expected: FAIL — unresolved `wuApiKey` / `setWuApiKey`.

- [ ] **Step 3: Add the field, the key and the setter**

In `AppSettings`, directly after `amateurStations`:

```kotlin
    /**
     * The reader's Weather Underground contributor key, or null where they have given none.
     *
     * Free to anyone who runs their own station and uploads to it, capped at 1500 requests a day.
     * Without it the amateur path is off and every place reads the provincial network, which is
     * what the app did before this existed.
     *
     * Stored in DataStore like every other preference, which means **plaintext in the app's own
     * storage**. That is said here rather than left implied: it is a credential, it is readable by
     * anything with root or a copy of the data directory, and encrypting this one preference while
     * the rest sit beside it in the clear would be a gesture rather than a defence.
     *
     * Null rather than empty when unset *or blank*, so consumers have one condition and not two.
     */
    val wuApiKey: String? = null,
```

In `Keys`, beside the others:

```kotlin
        val wuApiKey = stringPreferencesKey("wu_api_key")
```

In the mapping from preferences to `AppSettings`, beside `amateurStations`:

```kotlin
            wuApiKey = p[Keys.wuApiKey]?.takeIf { it.isNotBlank() },
```

And beside `setAmateurStations`:

```kotlin
    suspend fun setWuApiKey(v: String) = context.settingsStore.edit { it[Keys.wuApiKey] = v }
```

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.SettingsRepositoryTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/data/SettingsRepository.kt app/src/test/kotlin/it/apexweather/data/SettingsRepositoryTest.kt
git commit -m "feat: the Weather Underground key is a setting

Blank reads back as absent so every consumer has one condition, and the KDoc
says plainly that a credential in DataStore is plaintext in app storage.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NXkEqmLy6tRRAHVomjDsz1"
```

---

### Task 2: One rule decides whether a place reads its amateur station

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/domain/Place.kt`
- Modify: `app/src/main/kotlin/it/apexweather/ui/WeatherStateHolder.kt`, `app/src/main/kotlin/it/apexweather/work/RefreshWorker.kt`, `app/src/main/kotlin/it/apexweather/widget/ApexWidget.kt`
- Test: `app/src/test/kotlin/it/apexweather/domain/AmateurStationSettingTest.kt`

**Interfaces:**
- Consumes: `AppSettings.wuApiKey` (Task 1), the existing `Place.withAmateurStation(allowed: Boolean)`.
- Produces: `Place.forSettings(settings: AppSettings): Place`. The three call sites use it instead of `withAmateurStation`.

- [ ] **Step 1: Write the failing tests**

Append to `AmateurStationSettingTest`:

```kotlin
    /**
     * A station the app has no key to fetch is not a station, and keeping it would be worse than
     * dropping it: `readingStation` is `pws ?: station`, and the Open-Meteo *station reference* is
     * fetched at readingStation's coordinates. A place that kept its pws with no key would have the
     * models asked about a point whose thermometer is never read, while the hero fell back to the
     * provincial reading — and StationDownscale would then subtract two different places from each
     * other, which is the mistake its own documentation is about.
     */
    @Test
    fun `no key drops the amateur station even with the switch on`() {
        val settings = AppSettings(amateurStations = true, wuApiKey = null)
        assertNull(DORF_TIROL_WITH_PWS.forSettings(settings).pws)
        assertEquals("23200MS", DORF_TIROL_WITH_PWS.forSettings(settings).readingStation?.code)
    }

    @Test
    fun `a key and the switch on keeps it`() {
        val settings = AppSettings(amateurStations = true, wuApiKey = "abc123")
        assertEquals("ITIROL16", DORF_TIROL_WITH_PWS.forSettings(settings).readingStation?.code)
    }

    @Test
    fun `the switch off drops it however good the key`() {
        val settings = AppSettings(amateurStations = false, wuApiKey = "abc123")
        assertNull(DORF_TIROL_WITH_PWS.forSettings(settings).pws)
    }

    @Test
    fun `a place with no amateur station is returned unchanged`() {
        val settings = AppSettings(amateurStations = true, wuApiKey = "abc123")
        assertSame(DORF_TIROL, DORF_TIROL.forSettings(settings))
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.domain.AmateurStationSettingTest'`
Expected: FAIL — unresolved `forSettings`.

- [ ] **Step 3: Add `forSettings` and move the call sites to it**

In `Place.kt`, directly after `withAmateurStation`:

```kotlin
    /**
     * This place as these settings have it.
     *
     * Two conditions, in one function, because three callers apply it — the app's place flow, the
     * hourly worker and the widget — and a rule spelled out three times is a rule that will
     * eventually be spelled out differently in one of them.
     */
    fun forSettings(settings: it.apexweather.data.AppSettings): Place =
        withAmateurStation(settings.amateurStations && !settings.wuApiKey.isNullOrBlank())
```

Then replace each `withAmateurStation(...)` call site with `forSettings(...)`:

- `WeatherStateHolder`: the place flow maps `settings` to `Triple(placeIstat, amateurStations, wuApiKey)` — or simply keeps the whole `AppSettings` — before `distinctUntilChanged()`, so a key typed while the app is open re-subscribes the repository exactly as choosing a new place does.
- `RefreshWorker`: both the chosen place and each pinned one, from `current`/`appSettings`.
- `ApexWidget`: from the `settings` it already reads.

`withAmateurStation` stays as the narrow mechanism and keeps its own test; `forSettings` is the policy.

- [ ] **Step 4: Compile all three source sets and run the tests**

```bash
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin
./gradlew :app:testDebugUnitTest --tests 'it.apexweather.domain.AmateurStationSettingTest'
```

Expected: both PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/domain/Place.kt app/src/main/kotlin/it/apexweather/ui/WeatherStateHolder.kt app/src/main/kotlin/it/apexweather/work/RefreshWorker.kt app/src/main/kotlin/it/apexweather/widget/ApexWidget.kt app/src/test/kotlin/it/apexweather/domain/AmateurStationSettingTest.kt
git commit -m "feat: a place reads its amateur station only with the switch on and a key set

A station the app has no key to fetch is not a station, and keeping it would
point the station reference at a thermometer that is never read.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NXkEqmLy6tRRAHVomjDsz1"
```

---

### Task 3: The repository takes the key as a function

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/data/WeatherRepository.kt`
- Modify: `app/src/main/kotlin/it/apexweather/di/AppModule.kt` (remove `@WuApiKey` and its provider; add the function binding)
- Modify: `app/build.gradle.kts` (remove `wuApiKey`, `localProps` and the `WU_API_KEY` buildConfigField)
- Test: `app/src/test/kotlin/it/apexweather/data/WeatherRepositoryTest.kt`, and every other construction site of `WeatherRepository`

**Interfaces:**
- Consumes: `AppSettings.wuApiKey` (Task 1).
- Produces: `WeatherRepository(…, wuKey: suspend () -> String?)`. `BuildConfig.WU_API_KEY` and `@WuApiKey` no longer exist.

- [ ] **Step 1: Write the failing tests**

In `WeatherRepositoryTest`, replace the `WU_KEY` constant with a mutable one and add:

```kotlin
    /** Read per fetch, not captured once: a key typed while the app is open works on the next refresh. */
    @Test
    fun `the key is read at each fetch`() = runTest {
        key = null
        repo.refresh(DORF_TIROL_WITH_PWS, "de")
        assertTrue(wu.asked.isEmpty())

        key = "typed-later"
        repo.refresh(DORF_TIROL_WITH_PWS, "de")
        assertEquals("ITIROL16", wu.askedFor)
        assertEquals("typed-later", wu.askedKeys.last())
    }

    @Test
    fun `a blank key makes no request`() = runTest {
        key = "   "
        repo.refresh(DORF_TIROL_WITH_PWS, "de")
        assertTrue(wu.asked.isEmpty())
    }
```

with, beside the other fixtures in that class:

```kotlin
    private var key: String? = "test-key"
```

and the repository constructed with `wuKey = { key }`.

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.WeatherRepositoryTest'`
Expected: FAIL to compile — the constructor still takes `@WuApiKey String?`.

- [ ] **Step 3: Change the constructor and `fetchAmateur`**

In `WeatherRepository`, replace the `@WuApiKey private val wuApiKey: String?` parameter with:

```kotlin
    /**
     * The reader's Weather Underground key, read when it is needed rather than injected as a value.
     *
     * A function for the reason ForegroundRefreshLoop takes functions: the repository stays ignorant
     * of SettingsRepository — which is what lets the worker, the widget and the resume hook all call
     * `refresh` without agreeing about preferences first — and a test can hand it a key without
     * standing up a DataStore.
     *
     * It is also what closes the hole v0.29.0 fell into. `BuildConfig.WU_API_KEY` was a compile-time
     * constant, empty in every build but one; R8 proved this path dead, dropped the constructor
     * arguments, and the app stopped starting before it drew a frame. A suspend call into DataStore
     * is opaque to it, so there is nothing left to fold.
     */
    private val wuKey: suspend () -> String?,
```

and in `fetchAmateur`:

```kotlin
    private suspend fun fetchAmateur(station: NearbyStation): StationObservation? {
        val key = wuKey()?.takeIf { it.isNotBlank() } ?: return null
        val response = wu.current(station.code, key)
        return WeatherUndergroundMapper.map(response.body().takeIf { response.isSuccessful }, station)
    }
```

`wu` goes back to non-null `WeatherUndergroundApi` and the nullability comment on it is removed: with no constant, R8 cannot prove the field unused. **Keep that reasoning in the KDoc above** — it is the whole point of the change.

- [ ] **Step 4: Remove the build-time key**

In `app/build.gradle.kts`, delete the `localProps` block, the `wuApiKey` val and the
`buildConfigField("String", "WU_API_KEY", …)` line. In `AppModule.kt`, delete the `@WuApiKey`
annotation class and its `@Provides`, and add:

```kotlin
    /** The reader's key, read from settings at the moment it is used; see WeatherRepository.wuKey. */
    @Provides fun wuKey(settings: SettingsRepository): suspend () -> String? =
        { settings.settings.first().wuApiKey }
```

Use whatever `SettingsRepository`'s flow is actually called; do not rename it to match this snippet.

Then remove `wu.apiKey` from your own `local.properties`:

```bash
grep -v '^wu.apiKey' local.properties > /tmp/lp && mv /tmp/lp local.properties
git check-ignore -v local.properties   # must still print a .gitignore line
```

- [ ] **Step 5: Fix every other construction site**

`WeatherRepository` is constructed in `BiasAccumulationTest`, `WeatherStateHolderTest`,
`CompareViewModelTest`, `MapViewModelTest` and a second time inside `WeatherRepositoryTest`. Each
takes a literal in place of the old key argument: `{ null }` where the test has nothing to do with
amateur stations, `{ "test-key" }` where it does.

- [ ] **Step 6: Compile all three source sets, then run the suite**

```bash
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin
./gradlew :app:testDebugUnitTest
```

Expected: both PASS.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts app/src/main/kotlin/it/apexweather/ app/src/test/kotlin/it/apexweather/
git commit -m "feat: the repository takes the key as a function, and BuildConfig loses it

A compile-time constant is something R8 can reason about, and in v0.29.0 it
reasoned the whole amateur path dead, dropped the constructor arguments and
shipped an APK that could not start. A suspend read from DataStore gives it
nothing to fold.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NXkEqmLy6tRRAHVomjDsz1"
```

---

### Task 4: The field in settings

**Files:**
- Modify: `app/src/main/res/values/strings.xml`, `values-it/strings.xml`, `values-en/strings.xml`
- Modify: `app/src/main/kotlin/it/apexweather/ui/settings/SettingsScreen.kt`, `SettingsViewModel.kt`
- Test: `app/src/androidTest/kotlin/it/apexweather/ui/settings/SettingsContentTest.kt`

**Interfaces:**
- Consumes: `AppSettings.wuApiKey`, `SettingsRepository.setWuApiKey` (Task 1).
- Produces: `SettingsContent(…, onWuApiKey: (String) -> Unit)` and the node tagged `setting_wu_key`.

- [ ] **Step 1: Add the strings**

`values/strings.xml`:

```xml
    <string name="setting_wu_key">Weather-Underground-Schlüssel</string>
    <string name="setting_wu_key_note">Kostenlos für Betreiber einer eigenen Station. Ohne Schlüssel liest die App nur das Landesmessnetz.</string>
```

`values-it/strings.xml`:

```xml
    <string name="setting_wu_key">Chiave Weather Underground</string>
    <string name="setting_wu_key_note">Gratuita per chi gestisce una propria stazione. Senza chiave l\'app legge solo la rete provinciale.</string>
```

`values-en/strings.xml`:

```xml
    <string name="setting_wu_key">Weather Underground key</string>
    <string name="setting_wu_key_note">Free for anyone running their own station. Without a key the app reads the provincial network only.</string>
```

Note the escaped apostrophe in the Italian string. An unescaped one fails `mergeDebugResources`
with "Invalid unicode escape sequence in string", which is what it did in this session.

- [ ] **Step 2: Write the failing test**

Append to `SettingsContentTest`, and add `onWuApiKey = {}` to the existing `SettingsContent(...)`
call in its helper:

```kotlin
    @Test
    fun theKeyFieldIsShownWhenPrivateStationsAreOn() {
        show(AppSettings(amateurStations = true))
        rule.onNodeWithTag("setting_wu_key").assertIsDisplayed()
    }

    /** With the feature off the field is noise: there is nothing for a key to do. */
    @Test
    fun theKeyFieldIsHiddenWhenPrivateStationsAreOff() {
        show(AppSettings(amateurStations = false))
        rule.onNodeWithTag("setting_wu_key").assertDoesNotExist()
    }

    @Test
    fun typingAKeyReachesTheCallback() {
        var typed: String? = null
        show(AppSettings(amateurStations = true), onWuApiKey = { typed = it })
        rule.onNodeWithTag("setting_wu_key").performTextInput("abc123")
        assertEquals("abc123", typed)
    }
```

Match `show(...)`'s real name and parameters in that file rather than inventing them.

- [ ] **Step 3: Boot the emulator and watch it fail**

```bash
~/Android/Sdk/emulator/emulator -avd r8verify34 -no-window -no-audio &
adb wait-for-device
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.settings.SettingsContentTest
```

Expected: FAIL — no node tagged `setting_wu_key`.

- [ ] **Step 4: Draw the field**

In `SettingsScreen.kt`, directly under the private-stations row, inside an
`if (settings.amateurStations) { … }`:

```kotlin
                // Only while the feature is on: a key field under a switch that is off is a
                // question about something that is not happening.
                OutlinedTextField(
                    value = settings.wuApiKey.orEmpty(),
                    onValueChange = onWuApiKey,
                    singleLine = true,
                    label = { Text(stringResource(R.string.setting_wu_key)) },
                    supportingText = { Text(stringResource(R.string.setting_wu_key_note)) },
                    // Shown as typed rather than masked. It is a quota key for a free weather API,
                    // not a password, and the one thing a reader does with it is paste it and check
                    // by eye that it arrived whole.
                    modifier = Modifier.fillMaxWidth().testTag("setting_wu_key"),
                )
```

Add `onWuApiKey: (String) -> Unit` to `SettingsContent`'s parameters, wire it in `SettingsScreen` to
`viewModel::setWuApiKey`, and add that method to `SettingsViewModel` beside `setAmateurStations`.

- [ ] **Step 5: Run it and watch it pass**

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.settings.SettingsContentTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/settings/ app/src/main/res/values*/strings.xml app/src/androidTest/kotlin/it/apexweather/ui/settings/SettingsContentTest.kt
git commit -m "feat: the reader can type their own Weather Underground key

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NXkEqmLy6tRRAHVomjDsz1"
```

---

### Task 5: The generator's override, and filling the catalogue

**Files:**
- Modify: `tools/generate-places.py`
- Modify: `app/src/main/assets/places.json` (backfilled, reviewed by hand)

**Interfaces:**
- Consumes: the existing `pws_candidates`, `pick_by_stability`, `--pws-check`.
- Produces: `PWS_EXTRA`, and a `--pws-fill` mode that adds only `pws` fields.

- [ ] **Step 1: Add the override**

Beside `PWS_MAX_DEM_DISAGREEMENT_M`:

```python
# Stations the `near` endpoint does not return but which are worth considering anyway, by ISTAT.
#
# ITIROL26 sits 0,68 km from Dorf Tirol and answers `current` perfectly well; the endpoint returns
# ten stations for that place, stopping at 1,92 km, and simply does not include it. There is no
# documented reason, so the only way to consider it is to name it.
#
# Anything here is a *candidate and nothing more*. It goes through the same DEM gate and the same
# stability measure as a station the endpoint did offer, and is dropped by them as readily.
#
# ITIROL26 was dropped until 2026-09-23, and the reason is worth keeping: WU recorded its elevation
# as 204 m against a DEM of 654, because its station form is in **feet** and 669 had been entered
# there — 669 ft is 203,9 m, which is exactly what the metric API returned. The same instrument
# reads 669 m on AWEKAS, which is metric. Corrected to 2195 ft it reports 669 m and passes at
# Δ15 m. Altitude is what StationDownscale carries a reading up by, so the gate was right to refuse
# it and right to accept it once the metadata was true.
PWS_EXTRA = {
    "021101": ["ITIROL26"],  # Dorf Tirol
}
```

In `pws_candidates`, take the ISTAT code as an argument and merge its extras into the ids from
`near` before the per-station loop, de-duplicated and in the same order the loop already uses.

- [ ] **Step 2: Check it against the live API**

```bash
APEX_WU_API_KEY=<key> python3 tools/generate-places.py --pws-check
```

Expected: `ITIROL26` now appears in the output and is **kept** — claims 669 m against a DEM of
654, Δ15 m — beside `ITIROL16` (586 claimed, DEM 634, Δ48). `ITIROL25`, `ITIROL23` and `ITIROL24`
are still dropped on their impossible altitudes. Which of the two Dorf Tirol candidates wins is
then `pick_by_stability`'s to decide, not the cost ordering's.

- [ ] **Step 3: Add `--pws-fill`**

A mode that reads `PLACES`, walks every place, runs the same candidate-and-stability selection the
full run does, sets or clears each place's `pws`, and writes the file back **touching no other
field**. Print a one-line summary per place that gains or loses one, and the total at the end. Then
run `horizons.fill` over the result so a new `pws` gets its skyline, exactly as the full run does.

Model it on `tools/horizons.py`'s `main`, which reads the committed catalogue, fills one kind of
field and writes it back.

- [ ] **Step 4: Fill the catalogue and review the diff by hand**

```bash
APEX_WU_API_KEY=<key> python3 tools/generate-places.py --pws-fill
git diff --stat app/src/main/assets/places.json
git diff app/src/main/assets/places.json | grep -E '^[-+]' | grep -vE '^[-+]{3}' | grep -v '"pws"' | head
```

The third command must print **nothing but `horizon` lines for new pws entries** — any other changed
field means the pass is not as narrow as it claims and should be fixed before committing.

Then read the list of places that gained a station. This is a committed catalogue; an eye over it is
the point of doing it this way.

- [ ] **Step 5: Commit**

```bash
git add tools/generate-places.py app/src/main/assets/places.json
git commit -m "feat: name the stations the near endpoint hides, and backfill pws

--pws-fill adds only the pws field to the committed catalogue, on the same
terms horizons.py backfills only horizons: a full run would re-measure all 116
provincial stations against eight fresh weeks of ICON-D2 and no reviewer could
tell that apart from the amateur stations being added.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NXkEqmLy6tRRAHVomjDsz1"
```

---

### Task 6: Prove it, then release

**Files:** none changed unless something fails.

- [ ] **Step 1: The full gate**

```bash
./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintDebug
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

Expected: all PASS.

- [ ] **Step 2: The release smoke, on a minified build, with no key**

```bash
./gradlew :app:assembleRelease
tools/release-smoke.sh emulator-5554
```

Expected: `release smoke: OK`. **This is the step that was skipped before v0.29.0 and it is why that
release could not start.** It is not optional and it is not satisfied by the unit suite.

If it fails on `the map never read a forecast grid`, read the log it now prints: a
`SocketTimeoutException` from `NowcastSource` is GeoSphere being slow, not the app, and the run
should be repeated rather than the app changed.

- [ ] **Step 3: On the phone**

```bash
adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/debug/ApexWeather-debug.apk
```

Type the key in settings. The hero should name a private station within a refresh; clear the key and
it should fall back to Meran. Both directions, because the fallback is the half that has only ever
been proven by tests.

- [ ] **Step 4: Tag**

Only once every box above is ticked. Bump `apexVersionName`/`apexVersionCode` in
`app/build.gradle.kts`, commit as `chore: v0.30.0`, tag, and push `main` and the tag.

**Then download the published asset and launch it** before saying it shipped:

```bash
gh release download v0.30.0 -p "*.apk" -D /tmp && \
  adb -s emulator-5554 install -r /tmp/ApexWeather-0.30.0.apk && \
  adb -s emulator-5554 shell am start -W -n it.apexweather/.MainActivity && \
  sleep 8 && adb -s emulator-5554 shell pidof it.apexweather
```

An empty pid means the release is broken; draft it immediately and fix before anything else.

---

## Self-review notes

- Spec coverage: the setting (Task 1), the one rule (Task 2), the function-shaped key and the removal of the constant (Task 3), the field (Task 4), the override and the backfill (Task 5), the verification the last release skipped (Task 6).
- Names consistent throughout: `wuApiKey`, `setWuApiKey`, `forSettings`, `wuKey`, `PWS_EXTRA`, `--pws-fill`, tag `setting_wu_key`.
- Task 3 is the one that can break the build in a way tests do not catch, which is why Task 6 Step 2 exists and why every task that changes a signature compiles `androidTest` too.
