# A quieter sky, a hero icon and a readable hour sheet — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The "Himmel animieren" switch also stops the sky's colour crossfade; the condition icon moves up beside the hero temperature; the hour sheet becomes a header, a stat grid and a real table in its own file.

**Architecture:** Three independent UI changes in `app/src/main/kotlin/it/apexweather/ui/`. One shared decision (`motion`) inside `SkyBackground`; one layout change inside `HeroSection`; one composable extracted from `HomeScreen.kt` into `ui/home/HourDetail.kt` and rewritten, with two new pure formatters in `ui/common/Format.kt` underneath it. No new preference, no new dependency, no domain change.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Hilt, JUnit4 + `createComposeRule` (JVM for pure code, instrumented for anything composed), Roborazzi for goldens.

## Global Constraints

- Toolchain: JDK 21 pinned in `gradle.properties`; AGP 9 built-in Kotlin, KSP only, never apply `org.jetbrains.kotlin.android`.
- Device serial for every instrumented run: `RZCXA1ZEXJE` (an emulator is usually attached as well).
- Strings live in `app/src/main/res/values/strings.xml` (German, default), `values-it`, `values-en`. **Every new key goes into all three files.**
- Nothing that runs on a device may assert a German string: CI's emulators are en-US. Resolve wording from resources or assert on test tags.
- Nothing user-visible may name Dorf Tirol.
- Numbers, dates and times go through `ui/common/Format.kt` with an explicit `Formats`; inside a composition take it from `LocalFormats.current`. Never `Locale.ROOT`, never interpolate a raw number.
- `./gradlew :app:lintDebug` runs with `warningsAsErrors` and must be clean.
- Any Compose test that renders `SkyBackground` (directly or through `MainActivity`) sets `rule.mainClock.autoAdvance = false` and advances the clock by hand — **except** the new motion-off test in Task 1, which is precisely about the frame loop not being there.
- Branch: `feat/quieter-sky-and-clearer-hours` (already created; the spec is committed on it).
- Commit trailers, on every commit:

```
Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0149b2vTm5DgMjzaPght2WJE
```

**Spec:** `docs/superpowers/specs/2026-09-10-quieter-sky-and-clearer-hours-design.md`

---

## File Structure

| File | Responsibility | Task |
|------|----------------|------|
| `app/src/main/kotlin/it/apexweather/ui/sky/SkyBackground.kt` | Modify: one `motion` flag gates both the particle loop and the gradient's animation spec | 1 |
| `app/src/androidTest/kotlin/it/apexweather/ui/sky/SkyBackgroundTest.kt` | Modify: new case proving the palette arrives in one frame with motion off | 1 |
| `app/src/main/kotlin/it/apexweather/ui/home/HomeSections.kt` | Modify: `HeroSection` puts the condition icon on the temperature's line | 2 |
| `app/src/main/kotlin/it/apexweather/ui/common/Format.kt` | Modify: `mmValue` / `windValue`, the unit-free forms a table column needs | 3 |
| `app/src/test/kotlin/it/apexweather/ui/common/FormatTest.kt` | Modify: cases for both | 3 |
| `app/src/main/kotlin/it/apexweather/ui/home/HourDetail.kt` | **Create**: the whole hour sheet — header, stat grid, freezing level, per-source table | 4 |
| `app/src/main/kotlin/it/apexweather/ui/home/HomeScreen.kt` | Modify: loses the private `HourDetail` and `MISSING`, passes `onClose` | 4 |
| `app/src/main/res/values{,-it,-en}/strings.xml` | Modify: nine new keys, `hour_summary` deleted | 4 |
| `app/src/androidTest/kotlin/it/apexweather/ui/home/HomeScreenTest.kt` | Modify: hero-icon placement, gust tile present/absent, badge, cross | 2, 4 |
| `CLAUDE.md` | Modify: record the switch's widened meaning and the new file | 5 |

---

### Task 1: The switch stops the crossfade too

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/ui/sky/SkyBackground.kt:35-70`
- Test: `app/src/androidTest/kotlin/it/apexweather/ui/sky/SkyBackgroundTest.kt`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: nothing other tasks rely on. `SkyBackground(palette: SkyPalette, animationsEnabled: Boolean, modifier: Modifier = Modifier)` keeps its exact signature — `AppNavigation.kt:109` calls it and is not touched.

- [ ] **Step 1: Write the failing test**

Append this test and its helper to `app/src/androidTest/kotlin/it/apexweather/ui/sky/SkyBackgroundTest.kt`, inside the class:

```kotlin
    /**
     * With the switch off the sky still changes colour — it simply arrives at once. Two frames after
     * the palette changes, far short of the 1.5 s crossfade, the new colour has to be on screen.
     * This is also the one test in the file that may let the rule idle: with motion off there is no
     * frame loop asking for another frame forever.
     */
    @Test
    fun paletteArrivesAtOnceWhenAnimationsAreOff() {
        val day = SkyPaletteSelector.select(Condition.CLEAR, SunPhase.DAY, 0.0)
        val night = SkyPaletteSelector.select(Condition.CLEAR, SunPhase.NIGHT, 0.0)
        val current = mutableStateOf(day)
        rule.mainClock.autoAdvance = false
        rule.setContent { ApexTheme { SkyBackground(palette = current.value, animationsEnabled = false) } }
        rule.mainClock.advanceTimeBy(64)
        rule.runOnIdle { current.value = night }
        rule.mainClock.advanceTimeBy(32)
        val pixel = rule.onNodeWithTag("sky").captureToImage().toPixelMap()[2, 1]
        assertNear(Color.fromArgb(night.top), pixel)
    }

    /** The gradient interpolates from its first stop, so the topmost row is the top colour but not to the bit. */
    private fun assertNear(expected: Color, actual: Color) {
        val delta = maxOf(
            abs(expected.red - actual.red),
            abs(expected.green - actual.green),
            abs(expected.blue - actual.blue),
        )
        assertTrue("expected about $expected, got $actual", delta < 0.05f)
    }
```

Add these imports at the top of the same file:

```kotlin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import it.apexweather.ui.theme.fromArgb
import org.junit.Assert.assertTrue
import kotlin.math.abs
```

- [ ] **Step 2: Run the test and watch it fail**

```bash
ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.sky.SkyBackgroundTest
```

Expected: `paletteArrivesAtOnceWhenAnimationsAreOff` FAILS with "expected about Color(...), got Color(...)" — 32 ms into a 1500 ms tween the sky is still almost entirely the day colour. `rendersEveryParticleKindWithoutCrashing` still passes.

- [ ] **Step 3: Make the crossfade obey the switch**

In `app/src/main/kotlin/it/apexweather/ui/sky/SkyBackground.kt`, replace the whole composable (KDoc included) with:

```kotlin
/**
 * Full-screen animated sky: crossfading gradient and weather particles.
 * Both the particles and the gradient's crossfade run only while [animationsEnabled] is true and the
 * system is not asking for reduced motion; the particles additionally need the lifecycle RESUMED.
 * With motion off the sky is still the right colour for the hour — it arrives in one frame.
 */
@Composable
fun SkyBackground(palette: SkyPalette, animationsEnabled: Boolean, modifier: Modifier = Modifier) {
    val system = remember(palette.particle, palette.density) { ParticleSystem(palette.particle, palette.density) }
    var frame by remember { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Developer options "animator duration scale = off" and the accessibility "remove animations"
    // setting both zero this scale; either one means the user asked for no motion.
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
            .getOrDefault(1f) == 0f
    }
    // One decision for both kinds of movement. The crossfade used to run regardless, on the grounds
    // that a colour change is not motion — but a reader who switches "animate the sky" off and then
    // watches it fade for a second and a half has been told one thing and shown another.
    val motion = animationsEnabled && !reduceMotion

    val spec: AnimationSpec<Color> = if (motion) tween(1500) else snap()
    val top by animateColorAsState(Color.fromArgb(palette.top), spec, label = "top")
    val mid by animateColorAsState(Color.fromArgb(palette.mid), spec, label = "mid")
    val bottom by animateColorAsState(Color.fromArgb(palette.bottom), spec, label = "bottom")
    val accent = Color.fromArgb(palette.accent)

    if (motion) {
        LaunchedEffect(system, lifecycleOwner) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var last = 0L
                while (true) {
                    withFrameNanos { now ->
                        val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                        last = now
                        system.step(dt)
                        frame = now
                    }
                }
            }
        }
    }

    Box(modifier.fillMaxSize().testTag("sky").background(Brush.verticalGradient(listOf(top, mid, bottom)))) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val f = frame // read state so the canvas redraws every frame
            system.draw(this, accent)
        }
    }
}
```

Add two imports to the same file, keeping the existing alphabetical block:

```kotlin
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
```

- [ ] **Step 4: Run the test and watch it pass**

```bash
ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.sky.SkyBackgroundTest
```

Expected: both tests PASS.

- [ ] **Step 5: Check by hand on the phone**

```bash
./gradlew :app:assembleDebug && adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/debug/ApexWeather-debug.apk
```

Open settings, turn *Himmel animieren* off, switch place (which changes the palette): the sky changes colour without a fade and no particles fall. Turn it back on: the fade returns.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/sky/SkyBackground.kt \
        app/src/androidTest/kotlin/it/apexweather/ui/sky/SkyBackgroundTest.kt
git commit -m "$(cat <<'EOF'
feat: the sky switch stops the crossfade, not only the particles

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0149b2vTm5DgMjzaPght2WJE
EOF
)"
```

---

### Task 2: The condition icon moves beside the temperature

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/ui/home/HomeSections.kt:87-97` (the `HeroSection` temperature and condition rows)
- Test: `app/src/androidTest/kotlin/it/apexweather/ui/home/HomeScreenTest.kt`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: the test tag `hero_condition_icon` on the hero's condition `Icon`. `hero_temp` stays exactly where it is, on the temperature `Text` — `HomeScreenTest` and `NavigationTest` both find the hero by it.

- [ ] **Step 1: Write the failing test**

Add to `app/src/androidTest/kotlin/it/apexweather/ui/home/HomeScreenTest.kt`:

```kotlin
    /**
     * The icon shares the temperature's line rather than sitting a line below it: the degrees and the
     * picture are the two halves of one answer. Geometry, not wording — CI's emulator is en-US.
     */
    @Test
    fun heroConditionIconSitsBesideTheTemperature() {
        rule.setContent { ApexTheme { HomeContent(state, onRefresh = {}, onOpenBulletin = {}) } }
        val temp = rule.onNodeWithTag("hero_temp").fetchSemanticsNode().boundsInRoot
        val icon = rule.onNodeWithTag("hero_condition_icon").fetchSemanticsNode().boundsInRoot
        assertTrue("icon should start right of the number: icon=$icon temp=$temp", icon.left >= temp.right)
        assertTrue("icon should sit on the number's line: icon=$icon temp=$temp",
            icon.center.y > temp.top && icon.center.y < temp.bottom)
    }
```

`assertTrue` and `onNodeWithTag` are already imported in that file; nothing new is needed.

- [ ] **Step 2: Run the test and watch it fail**

```bash
ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.home.HomeScreenTest
```

Expected: FAIL — `hero_condition_icon` does not exist yet ("Failed to fetch semantics node: no node matched").

- [ ] **Step 3: Put the icon on the temperature's line**

In `app/src/main/kotlin/it/apexweather/ui/home/HomeSections.kt`, inside `HeroSection`, replace this:

```kotlin
        Text(
            text = state.heroTempC?.let { Format.temp(it, formats) } ?: "–",
            style = MaterialTheme.typography.displayLarge,
            color = Color.White,
            modifier = Modifier.testTag("hero_temp"),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(state.heroCondition.iconRes(state.phase)), contentDescription = null, tint = Color.fromArgb(state.palette.accent), modifier = Modifier.size(30.dp))
            Text(state.heroCondition.label(), style = MaterialTheme.typography.headlineMedium, color = Color.White)
        }
```

with this:

```kotlin
        // The number and the picture are the two halves of one answer, so they share a line. The
        // icon carries no content description: the word for the condition is directly underneath,
        // and describing the icon as well would have a screen reader say the weather twice.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = state.heroTempC?.let { Format.temp(it, formats) } ?: "–",
                style = MaterialTheme.typography.displayLarge,
                color = Color.White,
                modifier = Modifier.testTag("hero_temp"),
            )
            Icon(
                painterResource(state.heroCondition.iconRes(state.phase)),
                contentDescription = null,
                tint = Color.fromArgb(state.palette.accent),
                modifier = Modifier.size(56.dp).testTag("hero_condition_icon"),
            )
        }
        Text(state.heroCondition.label(), style = MaterialTheme.typography.headlineMedium, color = Color.White)
```

- [ ] **Step 4: Run the test and watch it pass**

```bash
ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.home.HomeScreenTest
```

Expected: every test in the class PASSES, `heroShowsConsensusTemperature` included.

- [ ] **Step 5: Look at it**

```bash
./gradlew :app:assembleDebug && adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/debug/ApexWeather-debug.apk
adb -s RZCXA1ZEXJE shell screencap -p /sdcard/hero.png && adb -s RZCXA1ZEXJE pull /sdcard/hero.png /tmp/hero.png
```

The icon should be optically level with the digits, not with their descenders, and not so large it crowds the place name above.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/home/HomeSections.kt \
        app/src/androidTest/kotlin/it/apexweather/ui/home/HomeScreenTest.kt
git commit -m "$(cat <<'EOF'
feat: the condition icon stands beside the hero temperature

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0149b2vTm5DgMjzaPght2WJE
EOF
)"
```

---

### Task 3: Unit-free formatters for a table column

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/ui/common/Format.kt:48-62`
- Test: `app/src/test/kotlin/it/apexweather/ui/common/FormatTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces, both used by Task 4:
  - `Format.mmValue(mm: Double, f: Formats): String` — millimetres without " mm".
  - `Format.windValue(kmh: Double, unit: WindUnit, f: Formats): String` — the speed in the reader's unit, without the unit.
  - `Format.mm` and `Format.wind` keep their exact current output and are now built from these.

- [ ] **Step 1: Write the failing test**

Add to `app/src/test/kotlin/it/apexweather/ui/common/FormatTest.kt`:

```kotlin
    /**
     * A table column carries its unit in the header, so the cells have to be able to drop it — and
     * the unit-carrying forms have to come out of the same code, or the two drift apart.
     */
    @Test fun `values without their unit`() {
        assertEquals("0,3", Format.mmValue(0.3, german))
        assertEquals("0", Format.mmValue(0.04, german))
        assertEquals("12", Format.mmValue(12.4, german))
        assertEquals("0.3", Format.mmValue(0.3, english))
        assertEquals("12", Format.windValue(12.3, WindUnit.KMH, german))
        assertEquals("3,4", Format.windValue(12.3, WindUnit.MS, german))
        // unchanged, and now assembled from the values above
        assertEquals("0,3 mm", Format.mm(0.3, german))
        assertEquals("12 km/h", Format.wind(12.3, WindUnit.KMH, german))
        assertEquals("3,4 m/s", Format.wind(12.3, WindUnit.MS, german))
    }
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.common.FormatTest'
```

Expected: compilation FAILS — "unresolved reference: mmValue".

- [ ] **Step 3: Add the two formatters**

In `app/src/main/kotlin/it/apexweather/ui/common/Format.kt`, replace the existing `wind` and `mm` functions with:

```kotlin
    /** The speed alone, in the reader's unit, for a column whose header says which unit that is. */
    fun windValue(kmh: Double, unit: WindUnit, f: Formats): String = when (unit) {
        WindUnit.KMH -> f.whole(unit.fromKmh(kmh).roundToInt())
        WindUnit.MS -> f.oneDecimal(unit.fromKmh(kmh))
    }

    fun wind(kmh: Double, unit: WindUnit, f: Formats): String = windValue(kmh, unit, f) + windUnitLabel(unit)

    fun windUnitLabel(unit: WindUnit): String = when (unit) {
        WindUnit.KMH -> " km/h"
        WindUnit.MS -> " m/s"
    }

    /** The millimetres alone, for a column whose header says "mm". */
    fun mmValue(mm: Double, f: Formats): String = when {
        abs(mm) < 0.05 -> f.whole(0)
        mm < 10 -> f.oneDecimal(mm)
        else -> f.whole(mm.roundToInt())
    }

    fun mm(mm: Double, f: Formats): String = "${mmValue(mm, f)} mm"
```

(`windUnitLabel` keeps its leading space; that space is what makes `wind` come out as "12 km/h".)

- [ ] **Step 4: Run it and watch it pass**

```bash
./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.common.FormatTest'
```

Expected: PASS, all cases including the pre-existing `wind`, `mm` and `decimals follow the reader's language`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/common/Format.kt \
        app/src/test/kotlin/it/apexweather/ui/common/FormatTest.kt
git commit -m "$(cat <<'EOF'
refactor: Format can give a number without its unit

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0149b2vTm5DgMjzaPght2WJE
EOF
)"
```

---

### Task 4: The hour sheet

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/ui/home/HourDetail.kt`
- Modify: `app/src/main/kotlin/it/apexweather/ui/home/HomeScreen.kt` (lines 12, 48, 51, 130-136 and 181-209)
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-it/strings.xml`, `app/src/main/res/values-en/strings.xml`
- Test: `app/src/androidTest/kotlin/it/apexweather/ui/home/HomeScreenTest.kt`

**Interfaces:**
- Consumes: `Format.mmValue`, `Format.windValue` (Task 3); `AgreementBadge(halfWidth: Double, agreement: Float, sourceCount: Int, showSpread: Boolean = true, tag: String)` and `freezingLevelText(metres: Double, formats: Formats): String` from `StationSection.kt`; `HomeUiState.phaseAt(t: Instant): SunPhase`.
- Produces: `HourDetail(hour: ConsensusHour, state: HomeUiState, onClose: () -> Unit = {})`, public, in `it.apexweather.ui.home`. Test tags: `hour_stat_temp`, `hour_stat_feels`, `hour_stat_precip`, `hour_stat_wind`, `hour_stat_gust`, `hour_freezing_level` (kept from the old code), `hour_agreement_badge`, `hour_detail_close`, `hour_source_header`.

- [ ] **Step 1: Add the strings, in all three languages**

`hour_summary` is **not** deleted yet: `HomeScreen.kt` still references it, and deleting it here stops
the module compiling, so the failing test in Step 3 would never run. It goes in Step 5, with the code
that reads it.

`app/src/main/res/values/strings.xml` — add, next to the other hour keys:

```xml
    <string name="stat_temp">Temperatur</string>
    <string name="stat_feels">Gefühlt</string>
    <string name="stat_precip">Niederschlag</string>
    <string name="stat_wind">Wind</string>
    <string name="stat_gust">Böen %1$s</string>
    <string name="stat_chance">%1$d %%</string>
    <string name="col_model">Modell</string>
    <string name="col_temp">Temp</string>
    <string name="col_precip">mm</string>
```

`app/src/main/res/values-it/strings.xml` — add:

```xml
    <string name="stat_temp">Temperatura</string>
    <string name="stat_feels">Percepita</string>
    <string name="stat_precip">Precipitazioni</string>
    <string name="stat_wind">Vento</string>
    <string name="stat_gust">Raffiche %1$s</string>
    <string name="stat_chance">%1$d %%</string>
    <string name="col_model">Modello</string>
    <string name="col_temp">Temp</string>
    <string name="col_precip">mm</string>
```

`app/src/main/res/values-en/strings.xml` — add:

```xml
    <string name="stat_temp">Temperature</string>
    <string name="stat_feels">Feels like</string>
    <string name="stat_precip">Precipitation</string>
    <string name="stat_wind">Wind</string>
    <string name="stat_gust">Gusts %1$s</string>
    <string name="stat_chance">%1$d %%</string>
    <string name="col_model">Model</string>
    <string name="col_temp">Temp</string>
    <string name="col_precip">mm</string>
```

There is no `col_wind`: the wind column's header is `Format.windUnitLabel(unit).trim()`, so it says km/h or m/s according to the reader's setting rather than a fixed word. (The spec listed a `col_wind` key; this is the one refinement against it.)

- [ ] **Step 2: Write the failing tests**

In `app/src/androidTest/kotlin/it/apexweather/ui/home/HomeScreenTest.kt`, first give the fixture the quantities the sheet now shows. Replace the existing `fc` function and add a second state below `state`:

```kotlin
    private fun fc(source: Source, offset: Double, gustKmh: Double? = 21.0) = SourceForecast(
        source, t0, t0,
        hourly = (0 until 168).map {
            HourlyPoint(
                t0.plusSeconds(it * 3600L), 15.0 + offset + it % 8,
                feelsLikeC = 13.0 + offset + it % 8,
                precipMm = if (it % 5 == 0) 1.0 else 0.0,
                windKmh = 6.0, gustKmh = gustKmh, freezingLevelM = 3100.0,
                condition = Condition.PARTLY_CLOUDY,
            )
        },
        daily = emptyList(),
    )
```

and, next to the other fixtures:

```kotlin
    /** No model publishing a gust is the case where the tile has to be gone, not drawn empty. */
    private val noGustSnapshot = WeatherSnapshot.EMPTY.copy(
        forecasts = mapOf(Source.ICON_CH1 to fc(Source.ICON_CH1, 0.0, gustKmh = null)),
    )
    private val noGustState = HomeStateBuilder.build(
        dorfTirol, noGustSnapshot, AppSettings(),
        ConsensusBlender().blend(noGustSnapshot.forecasts), t0.plusSeconds(60),
    )

    /** Opens the sheet on the first hour of the strip. */
    private fun openHourSheet() {
        rule.onNodeWithTag("hour_column_0").performScrollTo().performClick()
        rule.onNodeWithTag("hour_detail_sheet").assertIsDisplayed()
    }
```

Then add the three tests:

```kotlin
    @Test
    fun hourSheetShowsItsStatsAndItsAgreement() {
        rule.setContent { ApexTheme { HomeContent(state, onRefresh = {}, onOpenBulletin = {}) } }
        openHourSheet()
        rule.onNodeWithTag("hour_stat_temp").assertIsDisplayed()
        rule.onNodeWithTag("hour_stat_precip").assertIsDisplayed()
        rule.onNodeWithTag("hour_stat_gust").assertIsDisplayed()
        rule.onNodeWithTag("hour_agreement_badge").assertIsDisplayed()
        rule.onNodeWithTag("hour_source_header").assertIsDisplayed()
    }

    /** A quantity nobody publishes is left out rather than drawn as a dash. */
    @Test
    fun hourSheetLeavesOutTheGustNobodyPublishes() {
        rule.setContent { ApexTheme { HomeContent(noGustState, onRefresh = {}, onOpenBulletin = {}) } }
        openHourSheet()
        rule.onNodeWithTag("hour_stat_wind").assertIsDisplayed()
        rule.onAllNodesWithTag("hour_stat_gust").fetchSemanticsNodes().let {
            assertEquals("no model publishes a gust, so there is no gust line", 0, it.size)
        }
    }

    /**
     * The content scrolls, so the sheet needs a cross: once the reader has scrolled, dragging the
     * sheet down scrolls the content back instead of dismissing it.
     */
    @Test
    fun hourSheetClosesFromItsCross() {
        rule.setContent { ApexTheme { HomeContent(state, onRefresh = {}, onOpenBulletin = {}) } }
        openHourSheet()
        rule.onNodeWithTag("hour_detail_close").performClick()
        rule.waitUntil(2_000) { rule.onAllNodesWithTag("hour_detail_sheet").fetchSemanticsNodes().isEmpty() }
    }
```

- [ ] **Step 3: Run them and watch them fail**

```bash
ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.home.HomeScreenTest
```

Expected: the three new tests FAIL — "Failed to fetch semantics node", no node with tag
`hour_stat_temp`, `hour_agreement_badge` or `hour_detail_close`. Everything else in the class passes:
the old sheet is still there, and the fixture change only adds quantities.

- [ ] **Step 4: Create the sheet**

Create `app/src/main/kotlin/it/apexweather/ui/home/HourDetail.kt`:

```kotlin
package it.apexweather.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.apexweather.R
import it.apexweather.domain.SouthTyrol
import it.apexweather.domain.model.ConsensusHour
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.LocalFormats
import it.apexweather.ui.common.SourceColors
import it.apexweather.ui.common.iconRes
import it.apexweather.ui.common.label
import it.apexweather.ui.theme.fromArgb

/** Shown where a model publishes no value at all, so absence never reads as zero. */
private const val MISSING = "–"

/** One labelled number in the grid, with an optional second line under it. */
private data class Stat(
    val label: String,
    val value: String,
    val sub: String? = null,
    val tag: String,
    val subTag: String? = null,
)

/**
 * The whole of one hour: what the models agree on, and what each of them says on its own. Five
 * quantities used to share one sentence, which could not be scanned and had nowhere to put the
 * gust; they are a grid now, and the per-source numbers are columns rather than a run of digits.
 */
@Composable
fun HourDetail(hour: ConsensusHour, state: HomeUiState, onClose: () -> Unit = {}) {
    val formats = LocalFormats.current
    val unit = state.settings.windUnit
    val accent = Color.fromArgb(state.palette.accent)

    // Ten sources under five tiles run past a phone screen at a large font scale, and the cross is
    // what the reader is left with once scrolling has taken the downward drag away from the sheet.
    Column(
        Modifier.verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 32.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                painterResource(hour.condition.iconRes(state.phaseAt(hour.time))),
                contentDescription = null, tint = accent, modifier = Modifier.size(28.dp),
            )
            Text(
                "${Format.time(hour.time, SouthTyrol.ZONE, formats)} · ${hour.condition.label()}",
                style = MaterialTheme.typography.headlineSmall, color = Color.White,
                modifier = Modifier.weight(1f),
            )
            // The ensemble's own spread where it reaches this hour, the model band otherwise —
            // the same order of preference the hero's badge follows.
            AgreementBadge(
                halfWidth = hour.ensembleHalfWidthC ?: ((hour.tempMaxC - hour.tempMinC) / 2),
                agreement = hour.agreement,
                sourceCount = hour.sourceCount,
                tag = "hour_agreement_badge",
            )
            IconButton(onClick = onClose, modifier = Modifier.testTag("hour_detail_close")) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = Color.White.copy(alpha = 0.8f))
            }
        }
        Text(
            pluralStringResource(R.plurals.now_from_consensus, hour.sourceCount, hour.sourceCount),
            style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.65f),
        )
        Spacer(Modifier.height(12.dp))

        // A tile whose value nobody publishes is left out rather than drawn with a dash. Wind is the
        // exception: that no model publishes wind for this hour is itself worth saying, where the
        // other quantities are.
        val stats = buildList {
            add(
                Stat(
                    label = stringResource(R.string.stat_temp),
                    value = Format.temp(hour.tempC, formats),
                    sub = "${Format.temp(hour.tempMinC, formats)} – ${Format.temp(hour.tempMaxC, formats)}",
                    tag = "hour_stat_temp",
                )
            )
            hour.feelsLikeC?.let {
                add(Stat(stringResource(R.string.stat_feels), Format.temp(it, formats), tag = "hour_stat_feels"))
            }
            add(
                Stat(
                    label = stringResource(R.string.stat_precip),
                    value = Format.mm(hour.precipMm, formats),
                    sub = stringResource(R.string.stat_chance, hour.precipProb),
                    tag = "hour_stat_precip",
                )
            )
            add(
                Stat(
                    label = stringResource(R.string.stat_wind),
                    value = hour.windKmh?.let { Format.wind(it, unit, formats) } ?: MISSING,
                    sub = hour.gustKmh?.let { stringResource(R.string.stat_gust, Format.wind(it, unit, formats)) },
                    tag = "hour_stat_wind",
                    subTag = "hour_stat_gust",
                )
            )
        }
        stats.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                row.forEach { StatTile(it, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        // Not a tile: its wording flips between "Nullgradgrenze 3.200 m" and "Schneefallgrenze bis
        // 1.100 m", and a fixed label above it would contradict the second of those.
        hour.freezingLevelM?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                freezingLevelText(it, formats),
                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.testTag("hour_freezing_level"),
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.per_source), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(6.dp))
        // The header is what lets the cells drop their units, and the columns are fixed widths so a
        // model reading four degrees warm than the rest is visible without reading every row.
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("hour_source_header"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.col_model), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.weight(1f))
            Cell(stringResource(R.string.col_temp), MaterialTheme.typography.labelSmall, Color.White.copy(alpha = 0.6f))
            Cell(stringResource(R.string.col_precip), MaterialTheme.typography.labelSmall, Color.White.copy(alpha = 0.6f))
            Cell(Format.windUnitLabel(unit).trim(), MaterialTheme.typography.labelSmall, Color.White.copy(alpha = 0.6f))
        }
        hour.perSource.entries.sortedBy { it.key.ordinal }.forEach { (source, p) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                // The same colour the compare chart draws this model in, so the two screens agree.
                Box(Modifier.size(7.dp).clip(CircleShape).background(SourceColors.of(source)))
                Spacer(Modifier.width(8.dp))
                Text(source.displayName, style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.weight(1f))
                Cell(Format.tempDecimal(p.tempC, formats), MaterialTheme.typography.bodyMedium, Color.White.copy(alpha = 0.85f))
                Cell(Format.mmValue(p.precipMm, formats), MaterialTheme.typography.bodyMedium, Color.White.copy(alpha = 0.85f))
                Cell(p.windKmh?.let { Format.windValue(it, unit, formats) } ?: MISSING, MaterialTheme.typography.bodyMedium, Color.White.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
private fun StatTile(stat: Stat, modifier: Modifier = Modifier) {
    Column(modifier.testTag(stat.tag)) {
        Text(stat.label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
        Text(stat.value, style = MaterialTheme.typography.titleLarge, color = Color.White)
        stat.sub?.let {
            Text(
                it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f),
                modifier = stat.subTag?.let { tag -> Modifier.testTag(tag) } ?: Modifier,
            )
        }
    }
}

/** One right-aligned column of the per-source table. The widths are shared by header and rows. */
@Composable
private fun Cell(text: String, style: androidx.compose.ui.text.TextStyle, color: Color) {
    Text(text, style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.width(62.dp))
}
```

`stat_chance` is the bare percentage — `agreement_short` cannot be reused for it, because that string
reads "Übereinstimmung %1$d %%" and this number is a chance of rain, not an agreement.

- [ ] **Step 5: Take the old sheet out of `HomeScreen.kt`**

Delete from `app/src/main/kotlin/it/apexweather/ui/home/HomeScreen.kt`:

- the whole `private const val MISSING` line and the `private fun HourDetail(...)` composable at the bottom of the file (lines 181-209),
- and now that nothing reads it, the `hour_summary` string from all three of `values/strings.xml`, `values-it/strings.xml` and `values-en/strings.xml`,
- the now-unused imports `androidx.compose.foundation.layout.Row`, `it.apexweather.domain.model.ConsensusHour` and `it.apexweather.ui.common.label`.

And pass the close callback — replace:

```kotlin
            HourDetail(hour, state)
```

with:

```kotlin
            HourDetail(hour, state, onClose = { selectedHour = null })
```

- [ ] **Step 6: Run the tests and watch them pass**

```bash
ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.home.HomeScreenTest
```

Expected: every test in the class PASSES, the three new ones included.

- [ ] **Step 7: Look at the sheet on the phone**

```bash
./gradlew :app:assembleDebug && adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/debug/ApexWeather-debug.apk
```

Tap an hour. Check: the columns line up across all ten rows; the numbers do not wrap at the default font scale; the cross closes it; switching the wind unit in settings changes both the tile and the column header.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/home/HourDetail.kt \
        app/src/main/kotlin/it/apexweather/ui/home/HomeScreen.kt \
        app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml app/src/main/res/values-en/strings.xml \
        app/src/androidTest/kotlin/it/apexweather/ui/home/HomeScreenTest.kt
git commit -m "$(cat <<'EOF'
feat: the hour sheet is a grid and a table, in its own file

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0149b2vTm5DgMjzaPght2WJE
EOF
)"
```

---

### Task 5: Verify the lot and write down what changed

**Files:**
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing code depends on.

- [ ] **Step 1: Run the JVM tests, the goldens and lint**

```bash
./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintDebug
```

Expected: all PASS. If `verifyRoborazziDebug` reports a difference in `sky_palettes.png`, open the PNG before doing anything: that golden draws swatches, not `SkyBackground`, so a change there means something unintended happened in Task 1.

- [ ] **Step 2: Run the whole instrumented suite on the phone**

```bash
ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest
```

Expected: PASS, `NavigationTest` and `TopLevelNavigationTest` included — `hero_temp` moved inside a `Row` and those tests find the hero by that tag.

- [ ] **Step 3: Build and smoke-test the release build**

```bash
./gradlew :app:assembleRelease && ./tools/release-smoke.sh RZCXA1ZEXJE
```

Expected: exits 0, no crash, no `ClassNotFoundException`, no `SerializationException`.

- [ ] **Step 4: Record the two facts that are not derivable from the code**

In `CLAUDE.md`, under **Conventions**, add:

```markdown
- The animations switch (`AppSettings.animations`) stops **all** sky movement: the particles and the
  gradient's 1.5 s crossfade both hang off one `motion` flag in `SkyBackground`, together with the
  system's reduced-motion setting. The crossfade used to run regardless — a colour change was held
  not to be motion — and a reader who turned the switch off still watched the sky fade. With motion
  off `SkyBackground` runs no frame loop, which is the one case where a test that renders it may let
  the rule idle; `SkyBackgroundTest` uses that to prove the palette arrives in a single frame.
- The hour sheet lives in `ui/home/HourDetail.kt`, beside `DayDetail.kt`. Its numbers are a two-column
  grid of labelled tiles rather than one interpolated sentence: five quantities in a line of prose
  could not be scanned and left no room for the gust, which `ConsensusHour` had carried unshown. A
  tile whose value no model publishes is left out; wind alone keeps its dash, because "no model
  publishes wind for this hour" is a fact about the hour. The per-source table's cells drop their
  units into the column headers, which is what `Format.mmValue` and `Format.windValue` exist for.
```

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md
git commit -m "$(cat <<'EOF'
docs: record the widened animation switch and the hour sheet's new home

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0149b2vTm5DgMjzaPght2WJE
EOF
)"
```

---

## Notes for the implementer

- `HomeContent` is what the instrumented tests drive, with hand-built states; `HomeScreen` only wires the ViewModel. Keep that split.
- `AgreementBadge` already handles `sourceCount == 1` by refusing to quote a percentage. Days late in the week reach the hour sheet too, so do not add a second such rule in `HourDetail`.
- Do not touch `widget/` — it has its own layout and its own state builder, and Glance does not tint an `Image` on its own.
- If a Compose preview or a new test renders `MainActivity`, remember the clock rule: `rule.mainClock.autoAdvance = false`, advance by hand.
