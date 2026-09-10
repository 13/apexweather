# Icons, rain bars, the station card and a radar tab — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hand-drawn weather icons with Meteocons, make the 48 h precipitation bars readable, move the measured-station card to the bottom of the home screen, and add a map tab showing animated rain radar.

**Architecture:** Three of the four changes are local. The rain bar's rules move into a pure `PrecipScale` object so they can be unit-tested without a screen; the station card is a reorder of `LazyColumn` items; the icons are generated from committed SVGs by a committed script, keeping their existing resource names so nothing downstream moves. The map tab is the only new subsystem: a RainViewer client and a small in-memory repository feed a `MapViewModel`, and an osmdroid `MapView` inside an `AndroidView` draws an OSM basemap with the radar frame above it.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Retrofit + kotlinx.serialization, osmdroid 6.1.20, JUnit4 + Robolectric + Roborazzi (JVM), Compose UI tests (device), Python 3 for the icon converter.

## Global Constraints

- Strings live in `values` (German, default), `values-it`, `values-en`; **every new key goes in all three**.
- **No device test may assert a German string.** CI's emulators are en-US. Resolve strings from resources, or match a spelling every language shares.
- `./gradlew :app:lintDebug` runs with `warningsAsErrors` and must be clean before a push.
- Build/install: `./gradlew :app:assembleDebug` then `adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/debug/ApexWeather-debug.apk`.
- JVM tests: `./gradlew :app:testDebugUnitTest`. Device tests: `ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest`.
- Goldens: `./gradlew :app:verifyRoborazziDebug` to check, `:app:recordRoborazziDebug` to re-record. **Look at every re-recorded PNG before committing it.**
- Nothing user-visible may name Dorf Tirol; the place is a setting.
- `minSdk` is 31, `compileSdk` 37. AGP 9 built-in Kotlin: KSP only, never apply `org.jetbrains.kotlin.android`.
- Never rename `mipmap-anydpi-v26`.
- Commit messages end with the two attribution lines used by the rest of this repo's history.

---

## File Structure

**Phase A — rain bars and the station card**

- Create `app/src/main/kotlin/it/apexweather/ui/home/PrecipScale.kt` — the pure rules for the bar: how much of the track to fill, what colour, when to print an amount. No Compose beyond `Color`.
- Create `app/src/test/kotlin/it/apexweather/ui/home/PrecipScaleTest.kt`.
- Modify `app/src/main/kotlin/it/apexweather/ui/home/HomeSections.kt` — `PrecipBar` draws `PrecipScale`; `HourStrip` passes the condition and speaks the amount; `HourlySection` gains the legend.
- Modify `app/src/main/res/values{,-it,-en}/strings.xml` — `precip_legend`, and a fifth argument on `hour_column_desc`.
- Modify `app/src/test/kotlin/it/apexweather/ui/screenshot/WeatherVisualsScreenshotTest.kt` — a golden for the bar row.
- Modify `app/src/main/kotlin/it/apexweather/ui/home/HomeScreen.kt` — station card last.
- Modify `app/src/androidTest/kotlin/it/apexweather/ui/home/HomeScreenTest.kt` — assert the order.

**Phase B — the icon set**

- Create `tools/svg2vector.py` — the converter. Deliberately narrow: it raises on anything these seventeen files do not contain.
- Create `tools/meteocons/*.svg` — the seventeen Meteocons monochrome sources, committed so the conversion is reproducible offline.
- Replace `app/src/main/res/drawable/ic_wx_*.xml` — seventeen generated drawables.
- Create `LICENSE-meteocons` — the MIT notice, which the licence requires to ship.
- Modify `app/src/main/kotlin/it/apexweather/ui/common/WeatherIcons.kt` — FOG and THUNDERSTORM gain a night form.
- Modify `app/src/test/kotlin/it/apexweather/ui/common/WeatherIconsTest.kt` — the day/night set grows by two.
- Modify `app/src/main/res/values{,-it,-en}/strings.xml` — Meteocons named in `attribution`.

**Phase C — the map tab**

- Create `app/src/main/kotlin/it/apexweather/data/remote/RainViewerApi.kt` — the Retrofit interface, its wire types, the `RadarFrame` domain type, and `RainViewerMapper`.
- Create `app/src/test/resources/fixtures/rainviewer.json` and `app/src/test/kotlin/it/apexweather/data/remote/RainViewerMapperTest.kt`.
- Create `app/src/main/kotlin/it/apexweather/data/RadarRepository.kt` and its test.
- Create `app/src/main/kotlin/it/apexweather/ui/map/MapState.kt` — `MapUiState`, pure.
- Create `app/src/main/kotlin/it/apexweather/ui/map/MapViewModel.kt`.
- Create `app/src/main/kotlin/it/apexweather/ui/map/RadarTileSource.kt` — the osmdroid tile source for one frame.
- Create `app/src/main/kotlin/it/apexweather/ui/map/MapScreen.kt` — `MapScreen` (wiring) and `MapContent(state, callbacks)`.
- Create `app/src/androidTest/kotlin/it/apexweather/ui/map/MapContentTest.kt`.
- Modify `gradle/libs.versions.toml`, `app/build.gradle.kts` — osmdroid.
- Modify `app/src/main/kotlin/it/apexweather/di/AppModule.kt` — the RainViewer API binding.
- Modify `app/src/main/kotlin/it/apexweather/ui/navigation/AppNavigation.kt` — `MapRoute` and a fourth tab.
- Modify `app/src/androidTest/kotlin/it/apexweather/ui/navigation/TopLevelNavigationTest.kt`.
- Modify `app/src/main/res/values{,-it,-en}/strings.xml` — the tab label, the attribution line, the failure line.

**Phase D**

- Modify `CLAUDE.md`.

---

## Task 1: The rules behind the precipitation bar

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/ui/home/PrecipScale.kt`
- Test: `app/src/test/kotlin/it/apexweather/ui/home/PrecipScaleTest.kt`

**Interfaces:**
- Consumes: `it.apexweather.domain.model.Condition`.
- Produces: `PrecipScale.fillFraction(probPercent: Int): Float`, `PrecipScale.isDrawn(probPercent: Int): Boolean`, `PrecipScale.hasAmount(mm: Double): Boolean`, `PrecipScale.fillColor(mm: Double, condition: Condition): Color`, and the constants `PrecipScale.TRACK`, `LIGHT`, `MODERATE`, `HEAVY`, `FROZEN`, `MIN_DRAWN_PERCENT`, `MIN_PRINTED_MM`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/it/apexweather/ui/home/PrecipScaleTest.kt`:

```kotlin
package it.apexweather.ui.home

import it.apexweather.domain.model.Condition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bar used to be millimetres on a fixed 0-5 mm scale, so an hour certain to bring 0,4 mm drew
 * under two pixels of bar beneath a caption reading 100 %. Height is probability now, and these are
 * the rules that make that readable.
 */
class PrecipScaleTest {

    @Test
    fun `the fill is the probability`() {
        assertEquals(0f, PrecipScale.fillFraction(0), 1e-6f)
        assertEquals(0.64f, PrecipScale.fillFraction(64), 1e-6f)
        assertEquals(1f, PrecipScale.fillFraction(100), 1e-6f)
    }

    /** Upstreams are not trusted to stay inside 0..100; a bar taller than its track would overdraw. */
    @Test
    fun `a probability outside the scale is clamped`() {
        assertEquals(0f, PrecipScale.fillFraction(-5), 1e-6f)
        assertEquals(1f, PrecipScale.fillFraction(140), 1e-6f)
    }

    @Test
    fun `a chance too small to mean anything leaves the track empty`() {
        assertFalse(PrecipScale.isDrawn(0))
        assertFalse(PrecipScale.isDrawn(4))
        assertTrue(PrecipScale.isDrawn(5))
    }

    /** Absence must never read as zero: below a tenth of a millimetre nothing is printed at all. */
    @Test
    fun `an amount is printed only once there is one`() {
        assertFalse(PrecipScale.hasAmount(0.0))
        assertFalse(PrecipScale.hasAmount(0.04))
        assertTrue(PrecipScale.hasAmount(0.1))
    }

    @Test
    fun `the colour is the intensity`() {
        assertEquals(PrecipScale.LIGHT, PrecipScale.fillColor(0.2, Condition.DRIZZLE))
        assertEquals(PrecipScale.MODERATE, PrecipScale.fillColor(1.4, Condition.RAIN))
        assertEquals(PrecipScale.HEAVY, PrecipScale.fillColor(6.0, Condition.HEAVY_RAIN))
    }

    /** Frozen precipitation is never painted as rain, however much of it there is. */
    @Test
    fun `snow and sleet keep their own colour at every amount`() {
        listOf(Condition.SNOW, Condition.HEAVY_SNOW, Condition.SLEET).forEach {
            assertEquals("$it", PrecipScale.FROZEN, PrecipScale.fillColor(0.2, it))
            assertEquals("$it", PrecipScale.FROZEN, PrecipScale.fillColor(9.0, it))
        }
    }

    /** A thunderstorm's rain is rain; only the frozen three are special-cased. */
    @Test
    fun `a thunderstorm is coloured by its amount like any other rain`() {
        assertEquals(PrecipScale.HEAVY, PrecipScale.fillColor(6.0, Condition.THUNDERSTORM))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.home.PrecipScaleTest'`
Expected: FAIL — `Unresolved reference: PrecipScale`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/it/apexweather/ui/home/PrecipScale.kt`:

```kotlin
package it.apexweather.ui.home

import androidx.compose.ui.graphics.Color
import it.apexweather.domain.model.Condition

/**
 * What the precipitation bar under each hour of the 48-hour strip draws.
 *
 * Height is probability and colour is intensity, because the two used to be one number. The bar was
 * millimetres on a fixed 0-5 mm scale, so an hour certain to bring 0,4 mm drew 1.8 dp of bar under a
 * caption reading 100 %: the reader saw a number saying *certain* above a bar saying *nothing*, and
 * nothing on the card said which of the two the bar was.
 *
 * Probability fills a track that is always the same size, so every hour is comparable and a small
 * chance reads as small rather than as absent. The millimetres moved to the caption, where they are
 * a different fact from the one the bar carries rather than a second drawing of the same one.
 *
 * Kept out of the composable so it can be tested without a screen.
 */
object PrecipScale {

    /** Below this a chance is not worth drawing, and the track is left empty. */
    const val MIN_DRAWN_PERCENT = 5

    /** Below this there is no amount to print. Absence must never read as a zero. */
    const val MIN_PRINTED_MM = 0.1

    /** Millimetres in the hour at which the fill steps up a shade. */
    private const val MODERATE_FROM_MM = 0.5
    private const val HEAVY_FROM_MM = 2.5

    val TRACK = Color(0x1FFFFFFF)
    val LIGHT = Color(0xFF7FB2FF)
    val MODERATE = Color(0xFF4A90E8)
    val HEAVY = Color(0xFF3457C8)
    val FROZEN = Color(0xFFDDEBFF)

    /** How much of the track to fill, 0..1. */
    fun fillFraction(probPercent: Int): Float = (probPercent / 100f).coerceIn(0f, 1f)

    fun isDrawn(probPercent: Int): Boolean = probPercent >= MIN_DRAWN_PERCENT

    fun hasAmount(mm: Double): Boolean = mm >= MIN_PRINTED_MM

    fun fillColor(mm: Double, condition: Condition): Color = when {
        condition == Condition.SNOW || condition == Condition.HEAVY_SNOW || condition == Condition.SLEET -> FROZEN
        mm < MODERATE_FROM_MM -> LIGHT
        mm < HEAVY_FROM_MM -> MODERATE
        else -> HEAVY
    }
}
```

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.home.PrecipScaleTest'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/home/PrecipScale.kt app/src/test/kotlin/it/apexweather/ui/home/PrecipScaleTest.kt
git commit -m "feat: the rules behind a readable precipitation bar"
```

---

## Task 2: The bar, the legend, and the spoken column

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/ui/home/HomeSections.kt` (`HourlySection` ~line 159, `HourStrip` ~line 175, `PrecipBar` ~line 222)
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-it/strings.xml`, `app/src/main/res/values-en/strings.xml`
- Modify: `app/src/test/kotlin/it/apexweather/ui/screenshot/WeatherVisualsScreenshotTest.kt`
- Create: `app/src/test/screenshots/precip_bars.png` (recorded, not written)

**Interfaces:**
- Consumes: `PrecipScale` from Task 1; `Format.mm(mm: Double, f: Formats): String`; `LocalFormats.current`.
- Produces: `PrecipBar(mm: Double, prob: Int, condition: Condition)` — private to `HomeSections.kt`. No other task depends on it.

- [ ] **Step 1: Add the three strings**

In `app/src/main/res/values/strings.xml`, beside `section_hourly` (line 19):

```xml
    <string name="precip_legend">Balken: Wahrscheinlichkeit · Zahl: mm</string>
```

In `app/src/main/res/values-it/strings.xml`:

```xml
    <string name="precip_legend">Barra: probabilità · Numero: mm</string>
```

In `app/src/main/res/values-en/strings.xml`:

```xml
    <string name="precip_legend">Bar: probability · Number: mm</string>
```

- [ ] **Step 2: Give `hour_column_desc` a fifth argument**

The spoken column must say the same two facts the drawn one does. Replace the existing line in each file.

`values/strings.xml` line 74:

```xml
    <string name="hour_column_desc">%1$s Uhr: %2$s, %3$s, %4$d %% Niederschlagswahrscheinlichkeit, %5$s</string>
```

`values-it/strings.xml` line 75:

```xml
    <string name="hour_column_desc">Ore %1$s: %2$s, %3$s, %4$d %% di probabilità di precipitazioni, %5$s</string>
```

`values-en/strings.xml` line 74:

```xml
    <string name="hour_column_desc">%1$s: %2$s, %3$s, %4$d %% chance of precipitation, %5$s</string>
```

- [ ] **Step 3: Replace `PrecipBar`**

In `app/src/main/kotlin/it/apexweather/ui/home/HomeSections.kt`, replace the whole existing function:

```kotlin
@Composable
private fun PrecipBar(mm: Double, prob: Int) {
    val heightFraction = (mm / 5.0).coerceIn(0.0, 1.0).toFloat()
    // ... the rest of the old millimetre version
}
```

with:

```kotlin
/**
 * Height is the probability, colour is the amount, and the number underneath is the amount in
 * millimetres. See [PrecipScale] for why they are split that way.
 */
@Composable
private fun PrecipBar(mm: Double, prob: Int, condition: Condition) {
    val formats = LocalFormats.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.width(18.dp).height(PrecipTrackHeight)
                .clip(RoundedCornerShape(4.dp))
                .background(PrecipScale.TRACK),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (PrecipScale.isDrawn(prob)) {
                Box(
                    Modifier.fillMaxWidth()
                        // The floor is what keeps a real chance from rounding away to nothing.
                        .height((PrecipTrackHeight * PrecipScale.fillFraction(prob)).coerceAtLeast(2.dp))
                        .clip(RoundedCornerShape(4.dp))
                        .background(PrecipScale.fillColor(mm, condition)),
                )
            }
        }
        Text(
            if (PrecipScale.hasAmount(mm)) Format.mm(mm, formats) else "",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = Color(0xFFB9D2F5),
        )
    }
}

/** The track every hour's bar is drawn inside, so a small chance reads as small, not as absent. */
private val PrecipTrackHeight = 26.dp
```

Add these imports to the top of the file if they are not already there — `fillMaxWidth`, `Condition` and `Format` are already imported, the rest are:

```kotlin
import it.apexweather.domain.model.Condition
```

- [ ] **Step 4: Pass the condition and speak the amount**

In `HourStrip`, the `spoken` string and the `PrecipBar` call both change. Replace:

```kotlin
                val spoken = stringResource(
                    R.string.hour_column_desc, Format.hour(h.time, SouthTyrol.ZONE, formats),
                    h.condition.label(), Format.temp(h.tempC, formats), h.precipProb,
                )
```

with:

```kotlin
                val spoken = stringResource(
                    R.string.hour_column_desc, Format.hour(h.time, SouthTyrol.ZONE, formats),
                    h.condition.label(), Format.temp(h.tempC, formats), h.precipProb,
                    Format.mm(h.precipMm, formats),
                )
```

and replace:

```kotlin
                    PrecipBar(h.precipMm, h.precipProb)
```

with:

```kotlin
                    PrecipBar(h.precipMm, h.precipProb, h.condition)
```

- [ ] **Step 5: Put the legend on the card**

In `HourlySection`, replace the body of the `GlassCard` with:

```kotlin
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.section_hourly), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        // Without this the bar is a shape with no stated meaning, which is how it came to be read
        // as the amount when it is the chance.
        Text(
            stringResource(R.string.precip_legend),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.testTag("precip_legend"),
        )
        Spacer(Modifier.height(8.dp))
        HourStrip(hours, phaseAt, accent, tagPrefix = "hour_column", onHourClick = onHourClick, modifier = Modifier.testTag("hourly_strip"))
    }
```

- [ ] **Step 6: Build and run the whole JVM suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. The screenshot goldens are checked by `verifyRoborazziDebug`, not here, so nothing fails yet.

- [ ] **Step 7: Add a golden for the bars**

In `app/src/test/kotlin/it/apexweather/ui/screenshot/WeatherVisualsScreenshotTest.kt`, add this test inside the class, after `the weather icon set`:

```kotlin
    /**
     * The bar row across the cases that used to be indistinguishable: a certain drizzle, a likely
     * downpour, an unlikely shower and a dry hour all drew the same two-pixel sliver.
     */
    @Test
    fun `the precipitation bars`() {
        val cases = listOf(
            Triple(0.0, 0, Condition.CLEAR),
            Triple(0.0, 20, Condition.CLOUDY),
            Triple(0.3, 100, Condition.DRIZZLE),
            Triple(1.4, 64, Condition.RAIN),
            Triple(6.2, 90, Condition.HEAVY_RAIN),
            Triple(2.0, 80, Condition.SNOW),
            Triple(0.8, 55, Condition.SLEET),
        )
        captureRoboImage("src/test/screenshots/precip_bars.png") {
            CompositionLocalProvider(LocalFormats provides Formats(java.util.Locale.GERMANY, true)) {
                Row(
                    Modifier.background(Color(0xFF14213A)).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    cases.forEach { (mm, prob, condition) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$prob%", color = Color.White, fontSize = 9.sp)
                            PrecipBarPreview(mm, prob, condition)
                        }
                    }
                }
            }
        }
    }

    /**
     * `PrecipBar` is private to HomeSections, and it should stay that way — this repeats the six
     * lines of drawing rather than widening its visibility for a test. `PrecipScale` is the part
     * that has to agree, and it does, because both sides read it.
     */
    @Composable
    private fun PrecipBarPreview(mm: Double, prob: Int, condition: Condition) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.width(18.dp).height(26.dp).clip(RoundedCornerShape(4.dp)).background(PrecipScale.TRACK),
                contentAlignment = Alignment.BottomCenter,
            ) {
                if (PrecipScale.isDrawn(prob)) {
                    Box(
                        Modifier.fillMaxWidth()
                            .height((26.dp * PrecipScale.fillFraction(prob)).coerceAtLeast(2.dp))
                            .clip(RoundedCornerShape(4.dp))
                            .background(PrecipScale.fillColor(mm, condition)),
                    )
                }
            }
            Text(
                if (PrecipScale.hasAmount(mm)) Format.mm(mm, LocalFormats.current) else "",
                color = Color(0xFFB9D2F5), fontSize = 10.sp,
            )
        }
    }
```

Add the imports this needs at the top of that file:

```kotlin
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.Formats
import it.apexweather.ui.common.LocalFormats
import it.apexweather.ui.home.PrecipScale
```

- [ ] **Step 8: Record the golden and look at it**

Run: `./gradlew :app:recordRoborazziDebug`
Then **open `app/src/test/screenshots/precip_bars.png` and check it by eye**: seven bars, the dry ones empty, the 100 % drizzle full and pale, the 90 % downpour nearly full and dark, the snow pair white, and a millimetre caption under every wet one.

Also open `app/src/test/screenshots/weather_icons.png` — it should be unchanged at this point; if it moved, something else changed and that is worth understanding before going on.

- [ ] **Step 9: Verify the goldens hold**

Run: `./gradlew :app:verifyRoborazziDebug`
Expected: PASS.

- [ ] **Step 10: Lint and install**

```bash
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/debug/ApexWeather-debug.apk
```

Open the app and look at the 48 h strip. Every hour must have a visible track; an hour at 100 % must be a full bar.

- [ ] **Step 11: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/home/HomeSections.kt \
        app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml app/src/main/res/values-en/strings.xml \
        app/src/test/kotlin/it/apexweather/ui/screenshot/WeatherVisualsScreenshotTest.kt \
        app/src/test/screenshots/precip_bars.png
git commit -m "fix: a rain bar that says how likely, and a number that says how much"
```

---

## Task 3: The measured-station card goes to the bottom

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/ui/home/HomeScreen.kt` (the `LazyColumn` in `HomeContent`)
- Modify: `app/src/androidTest/kotlin/it/apexweather/ui/home/HomeScreenTest.kt`

**Interfaces:**
- Consumes: `StationSection(station, currentHour, windUnit, now)` and the `station_card` test tag, both unchanged.
- Produces: nothing new.

- [ ] **Step 1: Write the failing device test**

Add to `app/src/androidTest/kotlin/it/apexweather/ui/home/HomeScreenTest.kt`:

```kotlin
    /**
     * The station card is a reference, not a headline, and it used to interrupt the forecast between
     * the 48-hour strip and the day list. A section list is exactly the kind of thing that gets
     * reshuffled by accident, so the order is asserted rather than assumed.
     */
    @Test
    fun theStationCardSitsBelowTheDayList() {
        rule.setContent { ApexTheme { HomeContent(state, onRefresh = {}, onOpenBulletin = {}) } }
        val dayList = rule.onNodeWithTag("daily_list").performScrollTo().fetchSemanticsNode()
        val station = rule.onNodeWithTag("station_card").performScrollTo().fetchSemanticsNode()
        assertTrue(
            "the station card is above the day list",
            station.positionInRoot.y > dayList.positionInRoot.y,
        )
    }
```

Add the import it needs:

```kotlin
import org.junit.Assert.assertTrue
```

- [ ] **Step 2: Run it and watch it fail**

Run: `ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.home.HomeScreenTest`
Expected: FAIL on `theStationCardSitsBelowTheDayList` — "the station card is above the day list".

Note: both nodes are scrolled to before their positions are read, so this compares where each sits once it has been brought on screen. Because a `LazyColumn` scrolls, the assertion holds only if the two are read in list order; that is why the day list is fetched first.

- [ ] **Step 3: Move the item**

In `app/src/main/kotlin/it/apexweather/ui/home/HomeScreen.kt`, delete this block from between the hourly and daily items:

```kotlin
                item {
                    AnimatedVisibility(appeared, enter = fadeIn(tween(600, 100)) + slideInVertically(tween(600, 100)) { it / 4 }) {
                        StationSection(state.station, state.currentHour, state.settings.windUnit, state.now)
                    }
                }
```

and insert it after the bulletin teaser block and before `item { AttributionFooter() }`, with its timing moved to the end of the sequence:

```kotlin
                // Last, above the footer: everything on this card is an observation rather than a
                // forecast, so it reads as a reference the day list has earned rather than as an
                // interruption of it. The hero still says on its own line when the temperature it
                // shows came from the station.
                item {
                    AnimatedVisibility(appeared, enter = fadeIn(tween(700, 250)) + slideInVertically(tween(700, 250)) { it / 4 }) {
                        StationSection(state.station, state.currentHour, state.settings.windUnit, state.now)
                    }
                }
                item { AttributionFooter() }
```

Then change the daily section's timing back one step so the sequence still runs in order — replace `tween(600, 150)` with `tween(600, 100)` in the `DailySection` item.

- [ ] **Step 4: Run the device test again**

Run: `ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.home.HomeScreenTest`
Expected: PASS, every test in the class.

- [ ] **Step 5: Look at it on the phone**

```bash
./gradlew :app:assembleDebug
adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/debug/ApexWeather-debug.apk
```

Scroll the home screen to the bottom: hero, 48 h, 14 days, bulletin teaser, station card, footer.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/home/HomeScreen.kt app/src/androidTest/kotlin/it/apexweather/ui/home/HomeScreenTest.kt
git commit -m "refactor: the measured-station card reads as a reference, so it goes last"
```

---

## Task 4: The SVG-to-VectorDrawable converter, and the seventeen icons

**Files:**
- Create: `tools/svg2vector.py`
- Create: `tools/meteocons/*.svg` (17 files)
- Create: `LICENSE-meteocons`
- Replace: `app/src/main/res/drawable/ic_wx_sun.xml`, `ic_wx_moon.xml`, `ic_wx_sun_cloud.xml`, `ic_wx_moon_cloud.xml`, `ic_wx_cloud_sun.xml`, `ic_wx_cloud_moon.xml`, `ic_wx_cloud.xml`, `ic_wx_fog.xml`, `ic_wx_drizzle.xml`, `ic_wx_rain.xml`, `ic_wx_heavy_rain.xml`, `ic_wx_sleet.xml`, `ic_wx_snow.xml`, `ic_wx_heavy_snow.xml`, `ic_wx_storm.xml`
- Create: `app/src/main/res/drawable/ic_wx_fog_night.xml`, `app/src/main/res/drawable/ic_wx_storm_night.xml`

**Interfaces:**
- Consumes: nothing in the app.
- Produces: seventeen drawable resources. `ic_wx_fog_night` and `ic_wx_storm_night` are new names; the other fifteen keep the names `WeatherIcons.kt` already uses.

- [ ] **Step 1: Fetch and commit the seventeen sources**

```bash
mkdir -p tools/meteocons
cd /tmp && curl -sL "https://registry.npmjs.org/@meteocons/svg-static/-/svg-static-0.1.0.tgz" -o meteocons.tgz && mkdir -p meteocons && tar xzf meteocons.tgz -C meteocons
cd -
for n in clear-day clear-night partly-cloudy-day partly-cloudy-night overcast-day overcast-night \
         cloudy fog-day fog-night drizzle rain extreme-rain sleet snow extreme-snow \
         thunderstorms-day thunderstorms-night; do
  cp "/tmp/meteocons/package/monochrome/$n.svg" tools/meteocons/
done
ls tools/meteocons | wc -l   # 17
```

The package is `@meteocons/svg-static` version `0.1.0`, MIT, the publish of Meteocons 3.0. The `fill`,
`flat` and `line` styles are not used and are not copied: they carry `linearGradient`s inside `mask`
elements, which have no Android equivalent.

- [ ] **Step 2: Write the licence file**

Create `LICENSE-meteocons`:

```
The weather icons in app/src/main/res/drawable/ic_wx_*.xml are generated from Meteocons,
by Bas Milius — https://github.com/basmilius/meteocons — and are used under its licence.
The sources they are generated from are committed in tools/meteocons/.

MIT License

Copyright (c) 2019-2026 Bas Milius <bas@mili.us>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

Check the copyright line against `/tmp/meteocons/package/LICENSE` and copy that file's wording exactly if it differs.

- [ ] **Step 3: Write the converter**

Create `tools/svg2vector.py`, exactly as below. This script has been run against these seventeen
files and its output rendered back and compared with the sources: the largest difference was 0.33 %
of image RMSE, which is the antialiasing on a clip edge and nothing else.

```python
#!/usr/bin/env python3
"""Converts the Meteocons monochrome SVGs in tools/meteocons/ into Android VectorDrawables.

Run by hand, never in CI, and look at the icons before committing them:

    python3 tools/svg2vector.py

This is not a general SVG converter and must not be used as one. It handles exactly what these
seventeen files contain, and refuses anything else rather than guessing:

  * <path>, <circle> and <rect>, with fill, stroke, stroke-width, stroke-linecap, stroke-linejoin
    and stroke-miterlimit
  * fill="currentColor", which becomes black and is tinted at the use site, as the app's icons
    always have been
  * fill-rule="evenodd"
  * transform="translate(x, y)" on a path, which becomes a <group>
  * <clipPath>, dropped where it covers the whole canvas (all seventeen do) and emitted as a
    <clip-path> otherwise
  * <mask style="mask-type:alpha"> holding exactly one shape, which becomes a <clip-path>

That last one is the only interesting case. Android has no mask. Every mask in these files is
either a full-canvas rectangle with the occluding cloud subtracted from it under
fill-rule="evenodd", or a plain rectangle covering the top of the canvas. Both are exactly a
clip-path, so nothing is approximated here.

An unhandled tag or attribute raises. If a future Meteocons release brings gradients — the fill,
flat and line styles already have them — this script will say so instead of quietly dropping them.
"""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

NS = "{http://www.w3.org/2000/svg}"

# Meteocons draws on a 128-unit canvas. The app asks for icons at 20-26 dp, and a 24 dp drawable
# scaled by the caller is what every other icon in res/drawable already is.
SIZE_DP = 24

# Every stroke and fill in the monochrome style is this, and the app tints it at the use site.
INK = "#FF000000"

KNOWN_ATTRS = {
    "path": {"d", "fill", "fill-rule", "clip-rule", "stroke", "stroke-width", "stroke-linecap",
             "stroke-linejoin", "stroke-miterlimit", "transform", "id"},
    "circle": {"cx", "cy", "r", "fill", "stroke", "stroke-width", "id"},
    "rect": {"x", "y", "width", "height", "fill", "id"},
    "g": {"clip-path", "mask", "id"},
    "svg": {"viewBox", "fill", "xmlns", "width", "height"},
    "mask": {"id", "style", "maskUnits", "x", "y", "width", "height"},
    "clipPath": {"id"},
    "defs": {"id"},
}


def fail(msg):
    raise SystemExit(f"svg2vector: {msg}")


def num(v):
    """Formats a number the way the rest of the drawables in this repo are written."""
    f = float(v)
    return f"{f:g}"


def tag_of(e):
    return e.tag.split("}")[-1]


def check(e):
    t = tag_of(e)
    if t not in KNOWN_ATTRS:
        fail(f"unhandled element <{t}>; this converter is deliberately narrow, see its docstring")
    unknown = set(e.attrib) - KNOWN_ATTRS[t]
    if unknown:
        fail(f"<{t}> carries attributes this converter does not handle: {sorted(unknown)}")


def rect_path(e):
    x, y = float(e.get("x", 0)), float(e.get("y", 0))
    w, h = float(e.get("width")), float(e.get("height"))
    return f"M{num(x)},{num(y)}h{num(w)}v{num(h)}h{num(-w)}z"


def circle_path(e):
    cx, cy, r = float(e.get("cx")), float(e.get("cy")), float(e.get("r"))
    return (f"M{num(cx - r)},{num(cy)}"
            f"a{num(r)},{num(r)} 0 1,0 {num(2 * r)},0"
            f"a{num(r)},{num(r)} 0 1,0 {num(-2 * r)},0z")


def shape_path(e):
    """The `d` of any of the three shapes, as one path string."""
    t = tag_of(e)
    if t == "path":
        return e.get("d")
    if t == "rect":
        return rect_path(e)
    if t == "circle":
        return circle_path(e)
    fail(f"<{t}> is not a shape")


def covers_canvas(d, w, h):
    """True for a rectangle path that is the whole viewBox, which is a clip worth dropping."""
    return d.replace(" ", "").lower() in {
        rect_path(ET.Element("rect", {"width": str(w), "height": str(h)})).replace(" ", "").lower(),
        f"m0,0h{num(w)}v{num(h)}h{num(-w)}z",
    }


class Converter:
    def __init__(self, root):
        self.root = root
        vb = root.get("viewBox")
        if not vb:
            fail("the <svg> has no viewBox")
        parts = [float(p) for p in vb.replace(",", " ").split()]
        if len(parts) != 4 or parts[0] != 0 or parts[1] != 0:
            fail(f"viewBox {vb!r} is not a 0-origin box; not handled")
        self.w, self.h = parts[2], parts[3]
        self.clips = {}
        self.masks = {}
        for e in root.iter():
            t = tag_of(e)
            if t == "clipPath":
                self.clips[e.get("id")] = self._single_shape(e, "clipPath")
            elif t == "mask":
                if "alpha" not in (e.get("style") or ""):
                    fail(f"mask {e.get('id')!r} is not mask-type:alpha; not handled")
                self.masks[e.get("id")] = self._single_shape(e, "mask")

    def _single_shape(self, holder, kind):
        """A clipPath or mask must hold exactly one shape; anything else has no Android equivalent."""
        shapes = [e for e in holder.iter() if tag_of(e) in ("path", "rect", "circle") and e is not holder]
        if len(shapes) != 1:
            fail(f"{kind} {holder.get('id')!r} holds {len(shapes)} shapes; only one can become a clip-path")
        s = shapes[0]
        even = s.get("fill-rule") == "evenodd" or s.get("clip-rule") == "evenodd"
        return shape_path(s), even

    # ---- emitting ----

    def convert(self, name):
        out = [
            '<?xml version="1.0" encoding="utf-8"?>',
            f'<!-- {name}, from Meteocons (MIT). Generated by tools/svg2vector.py; do not edit by hand. -->',
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
            f'    android:width="{SIZE_DP}dp" android:height="{SIZE_DP}dp"',
            f'    android:viewportWidth="{num(self.w)}" android:viewportHeight="{num(self.h)}">',
        ]
        out += self.children(self.root, indent=1)
        out.append("</vector>")
        out.append("")
        return "\n".join(out)

    def children(self, parent, indent):
        lines = []
        for e in parent:
            check(e)
            t = tag_of(e)
            if t in ("defs", "mask", "clipPath"):
                continue  # referenced by id, emitted where they are used
            if t == "g":
                lines += self.group(e, indent)
            elif t in ("path", "rect", "circle"):
                lines += self.shape(e, indent)
            else:
                fail(f"unhandled element <{t}>")
        return lines

    def _ref(self, value):
        m = re.fullmatch(r"url\(#(.+)\)", (value or "").strip())
        if not m:
            fail(f"expected a url(#id) reference, got {value!r}")
        return m.group(1)

    def group(self, e, indent):
        pad = "    " * indent
        clips = []
        if "clip-path" in e.attrib:
            d, even = self.clips[self._ref(e.get("clip-path"))]
            if not covers_canvas(d, self.w, self.h):
                clips.append((d, even))
        if "mask" in e.attrib:
            clips.append(self.masks[self._ref(e.get("mask"))])
        if not clips:
            return self.children(e, indent)
        lines = [f"{pad}<group>"]
        for d, even in clips:
            fill = ' android:fillType="evenOdd"' if even else ""
            lines.append(f'{pad}    <clip-path android:pathData="{d}"{fill} />')
        lines += self.children(e, indent + 1)
        lines.append(f"{pad}</group>")
        return lines

    def shape(self, e, indent):
        pad = "    " * indent
        d = shape_path(e)
        fill = e.get("fill")
        stroke = e.get("stroke")
        if fill in (None, "none") and stroke in (None, "none"):
            return []
        attrs = [f'android:pathData="{d}"']
        if fill not in (None, "none"):
            if fill != "currentColor":
                fail(f"fill {fill!r} outside a mask; only currentColor is handled")
            attrs.append(f'android:fillColor="{INK}"')
            if e.get("fill-rule") == "evenodd":
                attrs.append('android:fillType="evenOdd"')
        if stroke not in (None, "none"):
            if stroke != "currentColor":
                fail(f"stroke {stroke!r}; only currentColor is handled")
            attrs.append(f'android:strokeColor="{INK}"')
            attrs.append(f'android:strokeWidth="{num(e.get("stroke-width", 1))}"')
            if e.get("stroke-linecap"):
                attrs.append(f'android:strokeLineCap="{e.get("stroke-linecap")}"')
            if e.get("stroke-linejoin"):
                attrs.append(f'android:strokeLineJoin="{e.get("stroke-linejoin")}"')
            if e.get("stroke-miterlimit"):
                attrs.append(f'android:strokeMiterLimit="{num(e.get("stroke-miterlimit"))}"')
        body = [f"{pad}<path", f"{pad}    " + "\n{}    ".format(pad).join(attrs) + " />"]
        transform = e.get("transform")
        if not transform:
            return body
        m = re.fullmatch(r"translate\(\s*(-?[\d.]+)[ ,]+(-?[\d.]+)\s*\)", transform.strip())
        if not m:
            fail(f"transform {transform!r}; only translate(x, y) is handled")
        tx, ty = num(m.group(1)), num(m.group(2))
        inner = [f"    {line}" for line in body]
        return [f'{pad}<group android:translateX="{tx}" android:translateY="{ty}">', *inner, f"{pad}</group>"]


# Meteocons name -> the app's drawable name. Both directions of this table are load-bearing:
# WeatherIcons.kt maps a Condition to the resource name on the right.
ICONS = {
    "clear-day": "ic_wx_sun",
    "clear-night": "ic_wx_moon",
    "partly-cloudy-day": "ic_wx_sun_cloud",
    "partly-cloudy-night": "ic_wx_moon_cloud",
    "overcast-day": "ic_wx_cloud_sun",
    "overcast-night": "ic_wx_cloud_moon",
    "cloudy": "ic_wx_cloud",
    "fog-day": "ic_wx_fog",
    "fog-night": "ic_wx_fog_night",
    "drizzle": "ic_wx_drizzle",
    "rain": "ic_wx_rain",
    "extreme-rain": "ic_wx_heavy_rain",
    "sleet": "ic_wx_sleet",
    "snow": "ic_wx_snow",
    "extreme-snow": "ic_wx_heavy_snow",
    "thunderstorms-day": "ic_wx_storm",
    "thunderstorms-night": "ic_wx_storm_night",
}


def main():
    src = pathlib.Path("tools/meteocons")
    out = pathlib.Path("app/src/main/res/drawable")
    if not src.is_dir():
        fail(f"{src} not found; run this from the repository root")
    for svg_name, res_name in sorted(ICONS.items()):
        path = src / f"{svg_name}.svg"
        if not path.is_file():
            fail(f"missing {path}")
        root = ET.parse(path).getroot()
        check(root)
        xml = Converter(root).convert(svg_name)
        (out / f"{res_name}.xml").write_text(xml, encoding="utf-8")
        print(f"  {svg_name:<22} -> {res_name}.xml  ({len(xml)} bytes)", file=sys.stderr)
    print(f"\nwrote {len(ICONS)} drawables to {out}", file=sys.stderr)


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run it**

Run: `python3 tools/svg2vector.py`
Expected: seventeen lines on stderr, then `wrote 17 drawables to app/src/main/res/drawable`.
If it exits with a `svg2vector:` message instead, the sources are not the ones this was written
against — read the message, do not loosen the converter to make it pass.

- [ ] **Step 5: Prove the app still builds, which is what proves the XML parses**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. A malformed vector drawable fails at aapt2, so this is the check that
the generated XML is valid; a broken one that *does* parse fails later, at inflation, on the phone.

- [ ] **Step 6: Commit the tool and its output**

```bash
git add tools/svg2vector.py tools/meteocons LICENSE-meteocons app/src/main/res/drawable/ic_wx_*.xml
git commit -m "feat: generate the weather icon set from Meteocons"
```

---

## Task 5: Wire the icon set in

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/ui/common/WeatherIcons.kt`
- Modify: `app/src/test/kotlin/it/apexweather/ui/common/WeatherIconsTest.kt`
- Modify: `app/src/main/res/values/strings.xml`, `values-it/strings.xml`, `values-en/strings.xml`
- Modify: `app/src/test/screenshots/weather_icons.png` (re-recorded)

**Interfaces:**
- Consumes: the seventeen drawables from Task 4, including the new `R.drawable.ic_wx_fog_night` and `R.drawable.ic_wx_storm_night`.
- Produces: `Condition.iconRes(phase: SunPhase = SunPhase.DAY): Int`, unchanged in signature. FOG and THUNDERSTORM now answer differently by phase.

- [ ] **Step 1: Update the mapping test first**

In `app/src/test/kotlin/it/apexweather/ui/common/WeatherIconsTest.kt`, replace the two tests that
partition the conditions:

```kotlin
    /** A clear night must not be drawn as a sunny day, and neither must a foggy or stormy one. */
    @Test
    fun `the sky conditions differ between day and night`() {
        DAY_AND_NIGHT.forEach {
            assertNotEquals("$it looks the same by night as by day", it.iconRes(SunPhase.DAY), it.iconRes(SunPhase.NIGHT))
        }
    }

    /** Below the clouds there is no sun to draw, so those icons must not change with the hour. */
    @Test
    fun `weather below the cloud looks the same whatever the hour`() {
        (Condition.entries - DAY_AND_NIGHT).forEach {
            assertEquals(it.iconRes(SunPhase.DAY), it.iconRes(SunPhase.NIGHT))
        }
    }

    private companion object {
        /**
         * Every condition whose drawing contains the sun or the moon. Fog and the thunderstorm
         * joined when the set became Meteocons: both of those icons have a sun in them by day.
         */
        val DAY_AND_NIGHT = setOf(
            Condition.CLEAR, Condition.MOSTLY_CLEAR, Condition.PARTLY_CLOUDY,
            Condition.FOG, Condition.THUNDERSTORM,
        )
    }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.common.WeatherIconsTest'`
Expected: FAIL on `the sky conditions differ between day and night` — FOG and THUNDERSTORM still
return the same drawable for both phases.

- [ ] **Step 3: Give fog and the thunderstorm a night**

In `app/src/main/kotlin/it/apexweather/ui/common/WeatherIcons.kt`, replace the two lines in
`iconRes`:

```kotlin
        Condition.FOG -> if (night) R.drawable.ic_wx_fog_night else R.drawable.ic_wx_fog
```

```kotlin
        Condition.THUNDERSTORM -> if (night) R.drawable.ic_wx_storm_night else R.drawable.ic_wx_storm
```

Also update the KDoc at the top of the file, which claims the set is hand-drawn:

```kotlin
/**
 * The one icon table, used by the app and by the widget alike.
 *
 * The drawings are Meteocons' monochrome style, generated by `tools/svg2vector.py` from the SVGs
 * committed in `tools/meteocons/` and tinted at the use site. Every condition gets its own icon
 * rather than a nearby stock glyph: rain falls in three weights and snow in two, which is a
 * distinction this app makes deliberately and was the reason Meteocons 2.0 could not be used.
 * The widget used to keep a second copy of this table with nothing asserting the two agreed.
 */
```

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.common.WeatherIconsTest'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Name Meteocons in the attribution**

Replace the `attribution` string in each of the three files.

`values/strings.xml`:

```xml
    <string name="attribution">Daten: Landeswetterdienst Südtirol · GeoSphere Austria (CC BY 4.0) · MeteoSwiss, DWD, ARPAE, ECMWF via Open-Meteo · Symbole: Meteocons (MIT)</string>
```

`values-it/strings.xml`:

```xml
    <string name="attribution">Dati: Servizio Meteo Alto Adige · GeoSphere Austria (CC BY 4.0) · MeteoSwiss, DWD, ARPAE, ECMWF via Open-Meteo · Icone: Meteocons (MIT)</string>
```

`values-en/strings.xml`:

```xml
    <string name="attribution">Data: Landeswetterdienst Südtirol · GeoSphere Austria (CC BY 4.0) · MeteoSwiss, DWD, ARPAE, ECMWF via Open-Meteo · Icons: Meteocons (MIT)</string>
```

- [ ] **Step 6: Re-record the icon golden and look at it**

Run: `./gradlew :app:recordRoborazziDebug`

**Open `app/src/test/screenshots/weather_icons.png`.** Twelve rows, two icons each. Check by eye:

- clear day and clear night differ, and the night one is a moon
- mostly clear, partly cloudy and cloudy are three distinguishable amounts of cloud, not three of the same
- rain, heavy rain and drizzle differ by how many drops fall
- snow and heavy snow differ
- sleet is neither of those two
- fog and the thunderstorm now differ between the columns
- nothing is clipped at the edge of its box, and no icon is blank

A blank icon means the converter dropped something; a clipped one means a clip-path went wrong.
Either is a reason to stop and read the generated XML, not to record over it.

- [ ] **Step 7: Verify and lint**

```bash
./gradlew :app:verifyRoborazziDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

Expected: all PASS.

- [ ] **Step 8: Look at them on the phone, including the widget**

```bash
./gradlew :app:assembleDebug
adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/debug/ApexWeather-debug.apk
```

Check the hero, the 48 h strip and the 14-day rows. Then check the widget on the home screen — it
draws these same drawables through Glance, which renders into a `RemoteViews` rather than into the
app's own process, and a vector that works in the app can still fail there.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/common/WeatherIcons.kt \
        app/src/test/kotlin/it/apexweather/ui/common/WeatherIconsTest.kt \
        app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml app/src/main/res/values-en/strings.xml \
        app/src/test/screenshots/weather_icons.png
git commit -m "feat: the weather icons are Meteocons, and fog and storms get a night"
```

---

## Task 6: The RainViewer client

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/data/remote/RainViewerApi.kt`
- Create: `app/src/test/resources/fixtures/rainviewer.json`
- Test: `app/src/test/kotlin/it/apexweather/data/remote/RainViewerMapperTest.kt`
- Modify: `app/src/main/kotlin/it/apexweather/di/AppModule.kt`

**Interfaces:**
- Consumes: `Fixtures.read(name)` and `Fixtures.json` from `app/src/test/kotlin/it/apexweather/Fixtures.kt`.
- Produces:
  - `interface RainViewerApi { suspend fun weatherMaps(): RainViewerMaps }`, `RainViewerApi.BASE_URL`
  - `@Serializable data class RainViewerMaps(val host: String, val radar: RainViewerRadar)`
  - `data class RadarFrame(val time: Instant, val base: String)` with `fun tileUrl(z: Int, x: Int, y: Int): String`
  - `object RainViewerMapper { const val MAX_ZOOM = 7; fun map(maps: RainViewerMaps): List<RadarFrame> }`

- [ ] **Step 1: Record the fixture**

Create `app/src/test/resources/fixtures/rainviewer.json` with exactly this, which is a real response
of 2026-09-10 trimmed to its first three frames:

```json
{
  "version": "2.0",
  "generated": 1789044331,
  "host": "https://tilecache.rainviewer.com",
  "radar": {
    "past": [
      {
        "time": 1789036800,
        "path": "/v2/radar/5fa7198de455"
      },
      {
        "time": 1789037400,
        "path": "/v2/radar/4634995ae527"
      },
      {
        "time": 1789038000,
        "path": "/v2/radar/82522fde4547"
      }
    ],
    "nowcast": []
  }
}
```

Re-record with `curl -s https://api.rainviewer.com/public/weather-maps.json` when the upstream
changes, and trim it the same way. `nowcast` came back empty on the day this was recorded, and the
app does not use it either way.

- [ ] **Step 2: Write the failing test**

Create `app/src/test/kotlin/it/apexweather/data/remote/RainViewerMapperTest.kt`:

```kotlin
package it.apexweather.data.remote

import it.apexweather.Fixtures
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * RainViewer's public Weather Maps API, against a response recorded on 2026-09-10.
 *
 * The zoom ceiling asserted here was found by fetching tiles rather than by reading the docs: z7
 * returns real radar and z8 returns a 1370-byte PNG with "Zoom Level Not Supported" written on it.
 * The map clamps itself to that, so if the ceiling ever moves, this is where it is written down.
 */
class RainViewerMapperTest {

    private fun maps(name: String = "rainviewer.json"): RainViewerMaps =
        Fixtures.json.decodeFromString(RainViewerMaps.serializer(), Fixtures.read(name))

    @Test
    fun `every past frame becomes a radar frame`() {
        val frames = RainViewerMapper.map(maps())
        assertEquals(3, frames.size)
        assertEquals(Instant.parse("2026-09-10T10:40:00Z"), frames.first().time)
        assertEquals(Instant.parse("2026-09-10T11:00:00Z"), frames.last().time)
    }

    @Test
    fun `the frames run oldest to newest, so the animation plays forwards`() {
        val frames = RainViewerMapper.map(maps())
        assertEquals(frames.map { it.time }.sorted(), frames.map { it.time })
    }

    /** The tile URL is assembled here rather than in the map view, so it can be asserted. */
    @Test
    fun `a tile url carries the host, the frame, the size, the scheme and the options`() {
        val frame = RainViewerMapper.map(maps()).first()
        assertEquals(
            "https://tilecache.rainviewer.com/v2/radar/5fa7198de455/256/7/67/45/4/1_1.png",
            frame.tileUrl(z = 7, x = 67, y = 45),
        )
    }

    @Test
    fun `the radar stops at zoom seven`() {
        assertEquals(7, RainViewerMapper.MAX_ZOOM)
    }

    /** A frame with no path cannot be fetched, and a frame with no time cannot be placed. */
    @Test
    fun `frames that could not be used are dropped rather than drawn`() {
        val broken = RainViewerMaps(
            host = "https://tilecache.rainviewer.com",
            radar = RainViewerRadar(
                past = listOf(
                    RainViewerFrame(time = 1789036800, path = ""),
                    RainViewerFrame(time = 0, path = "/v2/radar/abc"),
                    RainViewerFrame(time = 1789037400, path = "/v2/radar/def"),
                ),
            ),
        )
        assertEquals(listOf("/v2/radar/def"), RainViewerMapper.map(broken).map { it.base.removePrefix("https://tilecache.rainviewer.com") })
    }

    /** A response with no host is a response nothing can be fetched from. */
    @Test
    fun `no host means no frames`() {
        val hostless = RainViewerMaps(host = "", radar = RainViewerRadar(past = listOf(RainViewerFrame(1789036800, "/v2/radar/abc"))))
        assertTrue(RainViewerMapper.map(hostless).isEmpty())
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.remote.RainViewerMapperTest'`
Expected: FAIL — `Unresolved reference: RainViewerMaps`.

- [ ] **Step 4: Write the client**

Create `app/src/main/kotlin/it/apexweather/data/remote/RainViewerApi.kt`:

```kotlin
package it.apexweather.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import java.time.Instant

/**
 * RainViewer's public Weather Maps API: a tile pyramid over the radars of 150-odd countries, free,
 * with no key and no account.
 *
 * One JSON call returns the frames that currently exist — thirteen of them, the last two hours at
 * ten-minute steps — each naming a path under a tile host. There is a `nowcast` array beside them
 * that was empty every time this was looked at, so the app animates the past and promises no
 * future; see [RainViewerMapper].
 *
 * Their free terms require the credit "Weather data by RainViewer" to be visible wherever this is
 * drawn, and say plainly that a radar's owner can have their data withdrawn at any time. The map
 * treats an empty frame list as a normal state for that reason.
 */
interface RainViewerApi {
    @GET("public/weather-maps.json")
    suspend fun weatherMaps(): RainViewerMaps

    companion object { const val BASE_URL = "https://api.rainviewer.com/" }
}

@Serializable
data class RainViewerMaps(
    val host: String = "",
    val radar: RainViewerRadar = RainViewerRadar(),
)

@Serializable
data class RainViewerRadar(
    val past: List<RainViewerFrame> = emptyList(),
    val nowcast: List<RainViewerFrame> = emptyList(),
)

@Serializable
data class RainViewerFrame(
    /** Seconds since the epoch, UTC. */
    val time: Long = 0,
    /** The frame's own path under the tile host, e.g. `/v2/radar/5fa7198de455`. */
    val path: String = "",
)

/** One radar image, and everything needed to fetch its tiles. */
data class RadarFrame(val time: Instant, val base: String) {
    fun tileUrl(z: Int, x: Int, y: Int): String =
        "$base/${RainViewerMapper.TILE_SIZE}/$z/$x/$y/${RainViewerMapper.COLOR_SCHEME}/${RainViewerMapper.OPTIONS}.png"
}

object RainViewerMapper {

    /**
     * Radar tiles exist only this far in. Zoom 8 does not 404 — it returns a 1370-byte PNG with
     * "Zoom Level Not Supported" written across it, which would appear on the map as a grey label
     * where the rain should be. Verified against the live service at z5, z6, z7 and z8 on
     * 2026-09-10. The map's own zoom goes further and lets the tiles upscale.
     */
    const val MAX_ZOOM = 7

    const val TILE_SIZE = 256

    /** RainViewer's colour scheme 4: blue for light rain through yellow and red for heavy. */
    const val COLOR_SCHEME = 4

    /** `{smooth}_{snow}`: blurred rather than blocky, and snow coloured apart from rain. */
    const val OPTIONS = "1_1"

    /**
     * The past frames, oldest first, as fetchable images.
     *
     * `nowcast` is deliberately ignored. It has been empty every time this was checked, and a
     * timeline that sometimes reaches into the future and sometimes does not is worse than one that
     * never claims to.
     */
    fun map(maps: RainViewerMaps): List<RadarFrame> {
        if (maps.host.isBlank()) return emptyList()
        return maps.radar.past
            .filter { it.time > 0 && it.path.isNotBlank() }
            .map { RadarFrame(Instant.ofEpochSecond(it.time), maps.host + it.path) }
            .sortedBy { it.time }
    }
}
```

- [ ] **Step 5: Run it and watch it pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.remote.RainViewerMapperTest'`
Expected: PASS, 6 tests.

- [ ] **Step 6: Bind it**

In `app/src/main/kotlin/it/apexweather/di/AppModule.kt`, add the import:

```kotlin
import it.apexweather.data.remote.RainViewerApi
```

and add this line beside the other `@Provides` API bindings:

```kotlin
    @Provides @Singleton fun rainViewer(c: OkHttpClient, j: Json): RainViewerApi = retrofit(RainViewerApi.BASE_URL, c, j).create(RainViewerApi::class.java)
```

- [ ] **Step 7: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/data/remote/RainViewerApi.kt \
        app/src/test/resources/fixtures/rainviewer.json \
        app/src/test/kotlin/it/apexweather/data/remote/RainViewerMapperTest.kt \
        app/src/main/kotlin/it/apexweather/di/AppModule.kt
git commit -m "feat: read RainViewer's radar frames"
```

---

## Task 7: The radar repository

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/data/RadarRepository.kt`
- Test: `app/src/test/kotlin/it/apexweather/data/RadarRepositoryTest.kt`

**Interfaces:**
- Consumes: `RainViewerApi`, `RadarFrame`, `RainViewerMapper` from Task 6; `java.time.Clock`, which `AppModule` already provides.
- Produces: `class RadarRepository @Inject constructor(api: RainViewerApi, clock: Clock)` with `suspend fun frames(): List<RadarFrame>` and `RadarRepository.FRESH_FOR: Duration`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/it/apexweather/data/RadarRepositoryTest.kt`:

```kotlin
package it.apexweather.data

import it.apexweather.data.remote.RainViewerApi
import it.apexweather.data.remote.RainViewerFrame
import it.apexweather.data.remote.RainViewerMaps
import it.apexweather.data.remote.RainViewerRadar
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Radar frames are two hours of imagery that are worthless by tomorrow, so they are held in memory
 * and never written to Room — Room in this app holds things worth showing while offline.
 */
class RadarRepositoryTest {

    private class MovableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
    }

    private class FakeApi(var fail: Boolean = false) : RainViewerApi {
        var calls = 0
        override suspend fun weatherMaps(): RainViewerMaps {
            calls++
            if (fail) throw IOException("no network")
            return RainViewerMaps(
                host = "https://tilecache.rainviewer.com",
                radar = RainViewerRadar(past = listOf(RainViewerFrame(1789036800, "/v2/radar/5fa7198de455"))),
            )
        }
    }

    private val t0: Instant = Instant.parse("2026-09-10T11:00:00Z")

    @Test
    fun `the first call fetches`() = runTest {
        val api = FakeApi()
        val frames = RadarRepository(api, MovableClock(t0)).frames()
        assertEquals(1, api.calls)
        assertEquals(1, frames.size)
    }

    /** The upstream publishes every ten minutes; asking more often than that is asking for nothing. */
    @Test
    fun `a second call inside ten minutes does not fetch again`() = runTest {
        val api = FakeApi()
        val clock = MovableClock(t0)
        val repo = RadarRepository(api, clock)
        repo.frames()
        clock.now = t0.plusSeconds(9 * 60)
        repo.frames()
        assertEquals(1, api.calls)
    }

    @Test
    fun `after ten minutes it fetches again`() = runTest {
        val api = FakeApi()
        val clock = MovableClock(t0)
        val repo = RadarRepository(api, clock)
        repo.frames()
        clock.now = t0.plusSeconds(10 * 60)
        repo.frames()
        assertEquals(2, api.calls)
    }

    /** Nothing to draw is a state the map is built for, not an exception it has to catch. */
    @Test
    fun `a failed fetch yields no frames rather than throwing`() = runTest {
        val frames = RadarRepository(FakeApi(fail = true), MovableClock(t0)).frames()
        assertTrue(frames.isEmpty())
    }

    /** Two hours of imagery beats nothing, and the map's own time label says how old it is. */
    @Test
    fun `a failed refresh keeps the frames it already had`() = runTest {
        val api = FakeApi()
        val clock = MovableClock(t0)
        val repo = RadarRepository(api, clock)
        assertEquals(1, repo.frames().size)
        api.fail = true
        clock.now = t0.plusSeconds(11 * 60)
        assertEquals(1, repo.frames().size)
    }

    /** And it tries again on the next ask rather than waiting out another ten minutes. */
    @Test
    fun `a failed refresh does not start a fresh ten minutes`() = runTest {
        val api = FakeApi()
        val clock = MovableClock(t0)
        val repo = RadarRepository(api, clock)
        repo.frames()
        api.fail = true
        clock.now = t0.plusSeconds(11 * 60)
        repo.frames()
        api.fail = false
        repo.frames()
        assertEquals(3, api.calls)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.RadarRepositoryTest'`
Expected: FAIL — `Unresolved reference: RadarRepository`.

- [ ] **Step 3: Write the repository**

Create `app/src/main/kotlin/it/apexweather/data/RadarRepository.kt`:

```kotlin
package it.apexweather.data

import it.apexweather.data.remote.RadarFrame
import it.apexweather.data.remote.RainViewerApi
import it.apexweather.data.remote.RainViewerMapper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The radar frames currently on offer, held in memory for as long as they are current.
 *
 * Nothing here goes into Room. Every other upstream in this app is cached because the app renders
 * whatever it has while offline and says how old it is; radar frames are two hours of imagery whose
 * tiles are not stored either, so a cached frame list would name pictures that can no longer be
 * fetched.
 *
 * A failed fetch keeps whatever was already held and does not restart the clock, so the next time
 * the reader opens the map it tries again rather than waiting out the interval.
 */
@Singleton
class RadarRepository @Inject constructor(
    private val api: RainViewerApi,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private var frames: List<RadarFrame> = emptyList()
    private var fetchedAt: Instant? = null

    suspend fun frames(): List<RadarFrame> = mutex.withLock {
        val at = fetchedAt
        if (at != null && Duration.between(at, clock.instant()) < FRESH_FOR) return@withLock frames
        runCatching { RainViewerMapper.map(api.weatherMaps()) }
            .onSuccess { frames = it; fetchedAt = clock.instant() }
        frames
    }

    companion object {
        /** RainViewer publishes a new frame every ten minutes; asking sooner returns the same list. */
        val FRESH_FOR: Duration = Duration.ofMinutes(10)
    }
}
```

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.RadarRepositoryTest'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/data/RadarRepository.kt app/src/test/kotlin/it/apexweather/data/RadarRepositoryTest.kt
git commit -m "feat: hold the radar frames for as long as they are current"
```

---

## Task 8: The map screen

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/kotlin/it/apexweather/ui/map/MapState.kt`
- Create: `app/src/main/kotlin/it/apexweather/ui/map/MapViewModel.kt`
- Create: `app/src/main/kotlin/it/apexweather/ui/map/RadarTileSource.kt`
- Create: `app/src/main/kotlin/it/apexweather/ui/map/MapScreen.kt`
- Create: `app/src/androidTest/kotlin/it/apexweather/ui/map/MapContentTest.kt`
- Modify: `app/src/main/res/values{,-it,-en}/strings.xml`

**Interfaces:**
- Consumes: `RadarRepository.frames(): List<RadarFrame>`, `RadarFrame.time`/`tileUrl`, `RainViewerMapper.MAX_ZOOM`, `WeatherStateHolder.home: StateFlow<HomeUiState>` (whose `place` is a `Place?` with `lat`, `lon` and `name(locale)`).
- Produces:
  - `data class MapUiState(val frames: List<RadarFrame>, val selected: Int, val playing: Boolean, val place: Place?, val loading: Boolean)` with `val frame: RadarFrame?`
  - `class MapViewModel` exposing `state: StateFlow<MapUiState>`, `fun play()`, `fun pause()`, `fun select(index: Int)`, `fun refresh()`
  - `@Composable fun MapScreen()` and `@Composable fun MapContent(state: MapUiState, onPlayPause: () -> Unit, onSelect: (Int) -> Unit)`

- [ ] **Step 1: Add osmdroid**

In `gradle/libs.versions.toml`, add to `[versions]`:

```toml
osmdroid = "6.1.20"
```

and to `[libraries]`:

```toml
osmdroid = { group = "org.osmdroid", name = "osmdroid-android", version.ref = "osmdroid" }
```

In `app/build.gradle.kts`, add to the `dependencies` block beside the other `implementation` lines:

```kotlin
    // The map tab. Same library Apex Maps uses, and the only one that draws OpenStreetMap tiles
    // without an API key. Its cache and user agent are configured in MapScreen.
    implementation(libs.osmdroid)
```

- [ ] **Step 2: Add the four strings**

`app/src/main/res/values/strings.xml`:

```xml
    <string name="nav_map">Karte</string>
    <string name="map_attribution">© OpenStreetMap-Mitwirkende · Wetterdaten von RainViewer</string>
    <string name="map_radar_unavailable">Radarbilder nicht erreichbar</string>
    <string name="map_play">Abspielen</string>
    <string name="map_pause">Pause</string>
```

`app/src/main/res/values-it/strings.xml`:

```xml
    <string name="nav_map">Mappa</string>
    <string name="map_attribution">© Contributori OpenStreetMap · Dati meteo di RainViewer</string>
    <string name="map_radar_unavailable">Immagini radar non raggiungibili</string>
    <string name="map_play">Riproduci</string>
    <string name="map_pause">Pausa</string>
```

`app/src/main/res/values-en/strings.xml`:

```xml
    <string name="nav_map">Map</string>
    <string name="map_attribution">© OpenStreetMap contributors · Weather data by RainViewer</string>
    <string name="map_radar_unavailable">Radar images unavailable</string>
    <string name="map_play">Play</string>
    <string name="map_pause">Pause</string>
```

- [ ] **Step 3: Write the state**

Create `app/src/main/kotlin/it/apexweather/ui/map/MapState.kt`:

```kotlin
package it.apexweather.ui.map

import it.apexweather.data.remote.RadarFrame
import it.apexweather.domain.Place

/**
 * What the map tab draws. Pure, so the screen can be driven from a hand-built state in a test
 * without a network, a tile server or a real MapView.
 */
data class MapUiState(
    val frames: List<RadarFrame> = emptyList(),
    /** Index into [frames]. Out-of-range values resolve to no frame rather than throwing. */
    val selected: Int = 0,
    val playing: Boolean = false,
    val place: Place? = null,
    val loading: Boolean = true,
) {
    val frame: RadarFrame? get() = frames.getOrNull(selected)

    /** The radar could not be reached, and the map is basemap and marker only. */
    val radarUnavailable: Boolean get() = !loading && frames.isEmpty()
}
```

- [ ] **Step 4: Write the view model**

Create `app/src/main/kotlin/it/apexweather/ui/map/MapViewModel.kt`:

```kotlin
package it.apexweather.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.apexweather.data.RadarRepository
import it.apexweather.ui.WeatherStateHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The map tab's state: the radar frames, which one is showing, and the chosen place to centre on.
 *
 * The place comes from the same holder every other screen reads, so switching place in the picker
 * moves the map with everything else.
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val radar: RadarRepository,
    holder: WeatherStateHolder,
) : ViewModel() {

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private var animation: Job? = null

    init {
        viewModelScope.launch {
            holder.home.collect { home -> _state.update { it.copy(place = home.place) } }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val frames = radar.frames()
            _state.update {
                // Open on the newest frame: it is the one the reader came to see, and the animation
                // that follows runs from the beginning back round to it.
                it.copy(frames = frames, selected = (frames.size - 1).coerceAtLeast(0), loading = false)
            }
        }
    }

    fun select(index: Int) {
        pause()
        _state.update { it.copy(selected = index.coerceIn(0, (it.frames.size - 1).coerceAtLeast(0))) }
    }

    fun play() {
        if (_state.value.frames.size < 2) return
        animation?.cancel()
        _state.update { it.copy(playing = true) }
        animation = viewModelScope.launch {
            while (true) {
                val s = _state.value
                val last = s.frames.lastIndex
                val next = if (s.selected >= last) 0 else s.selected + 1
                // A beat on the newest frame, so the eye can find where the loop restarts.
                delay(if (s.selected >= last) LOOP_PAUSE_MS else FRAME_MS)
                _state.update { it.copy(selected = next) }
            }
        }
    }

    fun pause() {
        animation?.cancel()
        animation = null
        _state.update { it.copy(playing = false) }
    }

    override fun onCleared() {
        animation?.cancel()
    }

    private companion object {
        const val FRAME_MS = 400L
        const val LOOP_PAUSE_MS = 1200L
    }
}
```

- [ ] **Step 5: Write the tile source**

Create `app/src/main/kotlin/it/apexweather/ui/map/RadarTileSource.kt`:

```kotlin
package it.apexweather.ui.map

import it.apexweather.data.remote.RadarFrame
import it.apexweather.data.remote.RainViewerMapper
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.util.MapTileIndex

/**
 * One radar frame as an osmdroid tile source.
 *
 * `maximumZoomLevel` is [RainViewerMapper.MAX_ZOOM] and that is not a guess: zoom 8 returns a PNG
 * reading "Zoom Level Not Supported" rather than a 404, so a source that asked for it would draw
 * that label over the province. Declaring the ceiling makes osmdroid upscale the z7 tiles instead,
 * which is what the reader wants when they pinch in on a shower.
 *
 * The name carries the frame's own timestamp, which is what keeps osmdroid's cache from serving one
 * frame's tiles for another.
 */
class RadarTileSource(private val frame: RadarFrame) : OnlineTileSourceBase(
    "rainviewer-${frame.time.epochSecond}",
    RADAR_MIN_ZOOM,
    RainViewerMapper.MAX_ZOOM,
    RainViewerMapper.TILE_SIZE,
    ".png",
    arrayOf(frame.base),
    "Weather data by RainViewer",
    TileSourcePolicy(
        2,
        TileSourcePolicy.FLAG_NO_BULK or TileSourcePolicy.FLAG_NO_PREVENTIVE,
    ),
) {
    override fun getTileURLString(pMapTileIndex: Long): String = frame.tileUrl(
        z = MapTileIndex.getZoom(pMapTileIndex),
        x = MapTileIndex.getX(pMapTileIndex),
        y = MapTileIndex.getY(pMapTileIndex),
    )

    companion object {
        /** Below this the province is a smudge and the whole Alps fit on one tile. */
        const val RADAR_MIN_ZOOM = 4
    }
}
```

- [ ] **Step 6: Write the screen**

Create `app/src/main/kotlin/it/apexweather/ui/map/MapScreen.kt`:

```kotlin
package it.apexweather.ui.map

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.apexweather.BuildConfig
import it.apexweather.R
import it.apexweather.domain.SouthTyrol
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.GlassCard
import it.apexweather.ui.common.LocalFormats
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import java.time.Instant

@Composable
fun MapScreen(viewModel: MapViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MapContent(state, onPlayPause = { if (state.playing) viewModel.pause() else viewModel.play() }, onSelect = viewModel::select)
}

@Composable
fun MapContent(state: MapUiState, onPlayPause: () -> Unit, onSelect: (Int) -> Unit) {
    Box(Modifier.fillMaxSize().testTag("map_screen")) {
        RadarMap(state, Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.radarUnavailable) {
                Text(
                    stringResource(R.string.map_radar_unavailable),
                    style = MaterialTheme.typography.labelMedium, color = Color.White,
                    modifier = Modifier.testTag("map_radar_unavailable"),
                )
            } else {
                Timeline(state, onPlayPause, onSelect)
            }
            // Both halves of this are required: OpenStreetMap's tile policy and RainViewer's free
            // terms each ask for their credit to be visible where the map is, not behind a toggle.
            Text(
                stringResource(R.string.map_attribution),
                style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.testTag("map_attribution"),
            )
        }
    }
}

@Composable
private fun Timeline(state: MapUiState, onPlayPause: () -> Unit, onSelect: (Int) -> Unit) {
    val formats = LocalFormats.current
    GlassCard(Modifier.fillMaxWidth().testTag("map_timeline")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onPlayPause, modifier = Modifier.testTag("map_play_pause")) {
                Icon(
                    if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(if (state.playing) R.string.map_pause else R.string.map_play),
                    tint = Color.White,
                )
            }
            Text(
                state.frame?.time?.let { Format.time(it, SouthTyrol.ZONE, formats) }.orEmpty(),
                style = MaterialTheme.typography.labelLarge, color = Color.White,
                modifier = Modifier.testTag("map_frame_time"),
            )
        }
        if (state.frames.size > 1) {
            Slider(
                value = state.selected.toFloat(),
                onValueChange = { onSelect(it.toInt()) },
                valueRange = 0f..(state.frames.size - 1).toFloat(),
                steps = state.frames.size - 2,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White.copy(alpha = 0.8f)),
                modifier = Modifier.testTag("map_scrubber"),
            )
        }
    }
}

/**
 * osmdroid's MapView, which is a View and therefore has a lifecycle of its own to drive. The
 * `DisposableEffect` below is the single most likely place for this screen to leak: without
 * `onPause`/`onDetach` the map keeps its tile threads running after the tab is left.
 */
@Composable
private fun RadarMap(state: MapUiState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        // A unique user agent is what the OpenStreetMap tile policy asks for first; the okhttp
        // default is named there as one that gets blocked. The cache goes in the app's own cache
        // directory so no storage permission is involved.
        Configuration.getInstance().apply {
            userAgentValue = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = context.cacheDir
            osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid-tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // The OSM raster style is a daylight map and this app is a night sky. Darkened and
            // desaturated it stops fighting the radar drawn over it.
            overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(ColorMatrix().apply {
                setSaturation(0.35f)
                postConcat(ColorMatrix(floatArrayOf(
                    0.55f, 0f, 0f, 0f, 0f,
                    0f, 0.55f, 0f, 0f, 0f,
                    0f, 0f, 0.62f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )))
            }))
            minZoomLevel = MIN_ZOOM
            // Past this the z7 radar is upscaled past the point of meaning anything.
            maxZoomLevel = MAX_ZOOM
            controller.setZoom(START_ZOOM)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.testTag("map_view"),
        update = { map ->
            state.place?.let { place ->
                val point = GeoPoint(place.lat, place.lon)
                if (map.overlays.none { it is Marker }) {
                    map.controller.setCenter(point)
                    map.overlays.add(Marker(map).apply { position = point; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) })
                }
            }
            // One radar overlay at a time: the frame changes several times a second while playing,
            // and overlays left behind would stack every frame on top of the last.
            map.overlays.filterIsInstance<TilesOverlay>().forEach { map.overlays.remove(it) }
            state.frame?.let { frame ->
                map.overlays.add(
                    0,
                    TilesOverlay(org.osmdroid.tileprovider.MapTileProviderBasic(map.context, RadarTileSource(frame)), map.context)
                        .apply { loadingBackgroundColor = android.graphics.Color.TRANSPARENT },
                )
            }
            map.invalidate()
        },
    )
}

private const val MIN_ZOOM = 6.0
private const val MAX_ZOOM = 11.0
private const val START_ZOOM = 8.5
```

- [ ] **Step 7: Write the device test**

Create `app/src/androidTest/kotlin/it/apexweather/ui/map/MapContentTest.kt`:

```kotlin
package it.apexweather.ui.map

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import it.apexweather.data.remote.RadarFrame
import it.apexweather.domain.NearbyStation
import it.apexweather.domain.Place
import it.apexweather.ui.theme.ApexTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * The map's own controls, driven from a hand-built state. The osmdroid view is not asserted on: a
 * tile-rendering assertion on an emulator tests the network and the tile server, not this app.
 */
class MapContentTest {
    @get:Rule val rule = createComposeRule()

    private val place = Place(
        istat = "021101", nameDe = "Dorf Tirol", nameIt = "Tirolo", nameEn = "Tirol",
        lat = 46.688958, lon = 11.156624, altitudeM = 594, district = 2,
        station = NearbyStation("23200MS", "Meran", 46.688, 11.1366, 330, 1.53),
    )

    private val frames = (0 until 13).map {
        RadarFrame(Instant.parse("2026-09-10T09:00:00Z").plusSeconds(it * 600L), "https://tilecache.rainviewer.com/v2/radar/$it")
    }

    private fun state(vararg frame: RadarFrame) = MapUiState(
        frames = frame.toList(), selected = 0, playing = false, place = place, loading = false,
    )

    @Test
    fun theTimelineAndTheAttributionAreBothOnScreen() {
        rule.setContent { ApexTheme { MapContent(state(*frames.toTypedArray()), onPlayPause = {}, onSelect = {}) } }
        rule.onNodeWithTag("map_timeline").assertIsDisplayed()
        rule.onNodeWithTag("map_scrubber").assertIsDisplayed()
        rule.onNodeWithTag("map_frame_time").assertIsDisplayed()
        rule.onNodeWithTag("map_attribution").assertIsDisplayed()
    }

    @Test
    fun thePlayButtonReportsItsPress() {
        var pressed = false
        rule.setContent { ApexTheme { MapContent(state(*frames.toTypedArray()), onPlayPause = { pressed = true }, onSelect = {}) } }
        rule.onNodeWithTag("map_play_pause").performClick()
        assertTrue(pressed)
    }

    /** No radar is a state the map is built for: the basemap and the marker are still worth drawing. */
    @Test
    fun withNoFramesTheMapSaysSoAndKeepsTheAttribution() {
        rule.setContent { ApexTheme { MapContent(MapUiState(place = place, loading = false), onPlayPause = {}, onSelect = {}) } }
        rule.onNodeWithTag("map_radar_unavailable").assertIsDisplayed()
        rule.onNodeWithTag("map_attribution").assertIsDisplayed()
        rule.onNodeWithTag("map_view").assertIsDisplayed()
    }

    /** A single frame has nothing to scrub through, and a slider with one stop crashes. */
    @Test
    fun oneFrameShowsNoScrubber() {
        rule.setContent { ApexTheme { MapContent(state(frames.first()), onPlayPause = {}, onSelect = {}) } }
        rule.onNodeWithTag("map_frame_time").assertIsDisplayed()
        rule.onNodeWithTag("map_scrubber").assertDoesNotExist()
    }
}
```

Add the import for `assertDoesNotExist`:

```kotlin
import androidx.compose.ui.test.assertDoesNotExist
```

- [ ] **Step 8: Build and run the device test**

```bash
./gradlew :app:assembleDebug
ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.map.MapContentTest
```

Expected: PASS, 4 tests. The screen is not reachable from the app yet — that is Task 9.

- [ ] **Step 9: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
        app/src/main/kotlin/it/apexweather/ui/map \
        app/src/androidTest/kotlin/it/apexweather/ui/map \
        app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml app/src/main/res/values-en/strings.xml
git commit -m "feat: a map of the last two hours of radar"
```

---

## Task 9: The tab

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/ui/navigation/AppNavigation.kt`
- Modify: `app/src/androidTest/kotlin/it/apexweather/ui/navigation/TopLevelNavigationTest.kt`

**Interfaces:**
- Consumes: `MapScreen()` from Task 8, `R.string.nav_map`.
- Produces: `@Serializable object MapRoute`, and a fourth `NavigationBarItem` tagged `nav_map`.

- [ ] **Step 1: Extend the navigation test first**

In `app/src/androidTest/kotlin/it/apexweather/ui/navigation/TopLevelNavigationTest.kt`, add the
route to the stub graph inside `show()`:

```kotlin
            composable<MapRoute> { Text("map", Modifier.testTag("screen_map")) }
```

extend the round trip in `everyTabReachesEveryOtherTab`:

```kotlin
        listOf(CompareRoute, MapRoute, BulletinRoute, HomeRoute, MapRoute, BulletinRoute, CompareRoute, HomeRoute).forEach { route ->
            open(route)
            val tag = when (route) {
                HomeRoute -> "screen_home"
                CompareRoute -> "screen_compare"
                MapRoute -> "screen_map"
                else -> "screen_bulletin"
            }
            rule.onNodeWithTag(tag).assertIsDisplayed()
        }
```

and add a test of its own, because the map is the newest way into the back stack:

```kotlin
    /** The map is a tab like the others: leaving it must not strand the home tab. */
    @Test
    fun theHomeTabReturnsAfterTheMap() {
        show()
        open(MapRoute)
        rule.onNodeWithTag("screen_map").assertIsDisplayed()
        open(HomeRoute)
        rule.onNodeWithTag("screen_home").assertIsDisplayed()
    }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.navigation.TopLevelNavigationTest`
Expected: FAIL to compile — `Unresolved reference: MapRoute`.

- [ ] **Step 3: Add the route, the tab and the destination**

In `app/src/main/kotlin/it/apexweather/ui/navigation/AppNavigation.kt`:

Add the imports:

```kotlin
import androidx.compose.material.icons.rounded.Map
import it.apexweather.ui.map.MapScreen
```

Add the route beside the other three:

```kotlin
@Serializable object MapRoute
```

Add the tab, second in the bar — the map is the second thing a reader reaches for after today's
weather:

```kotlin
                    val items = listOf(
                        NavItem(HomeRoute, "home", R.string.nav_home, Icons.Rounded.Home),
                        NavItem(MapRoute, "map", R.string.nav_map, Icons.Rounded.Map),
                        NavItem(CompareRoute, "compare", R.string.nav_compare, Icons.Rounded.StackedLineChart),
                        NavItem(BulletinRoute, "bulletin", R.string.nav_bulletin, Icons.Rounded.Article),
                    )
```

Add the destination inside the `NavHost`, after the place picker:

```kotlin
                composable<MapRoute> { MapScreen() }
```

- [ ] **Step 4: Run the navigation test again**

Run: `ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.navigation.TopLevelNavigationTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Check the bar still fits on a small screen**

Run: `ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest`
Expected: PASS, the whole suite. A fourth tab is the kind of change that pushes a label into two
lines on a narrow screen; the settings sheet has already been caught by exactly this once.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/navigation/AppNavigation.kt \
        app/src/androidTest/kotlin/it/apexweather/ui/navigation/TopLevelNavigationTest.kt
git commit -m "feat: a map tab, second in the bar"
```

---

## Task 10: Prove it on the phone, and write down what changed

**Files:**
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing in code.

- [ ] **Step 1: Run every suite**

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:verifyRoborazziDebug
./gradlew :app:lintDebug
ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest
```

Expected: all PASS.

- [ ] **Step 2: Check the release build, which is the one R8 touches**

```bash
./gradlew :app:assembleRelease
./tools/release-smoke.sh RZCXA1ZEXJE
```

Expected: PASS. This is the only thing that exercises Glance's runtime layout lookup,
kotlinx-serialization and Hilt's graph after R8 — and this change adds a serialized wire type
(`RainViewerMaps`) and a new Hilt binding, both of which are exactly what R8 breaks.

If `RainViewerMaps` is stripped, add a keep rule to `app/proguard-rules.pro` beside the existing
ones and say in the comment which class and why.

- [ ] **Step 3: Look at the app**

```bash
./gradlew :app:assembleDebug
adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/debug/ApexWeather-debug.apk
```

Check, in this order:

1. the 48 h strip: a visible track under every hour, a full bar at 100 %, a millimetre caption on the wet ones, and the legend line under the heading
2. the icons: hero, strip and day rows, and the widget on the home screen
3. the home screen scrolled to the bottom: the station card is above the footer and below the days
4. the map tab: the basemap draws dark, the marker is on the chosen place, the timeline scrubs, play animates, and the attribution line is visible
5. the map with the network off: the radar line says it is unavailable and the tab does not crash
6. switch place in the picker, come back to the map: it is centred on the new place

- [ ] **Step 4: Write it down**

In `CLAUDE.md`, under **Conventions**, replace the weather-icons bullet:

```markdown
- Weather icons are Meteocons' monochrome style, generated from the SVGs committed in
  `tools/meteocons/` by `tools/svg2vector.py` and mapped once in `ui/common/WeatherIcons.kt`, used
  by both the app and the widget. Regenerate deliberately and look at the icons before committing.
  That converter is narrow on purpose and raises rather than guessing: Android has no mask, and the
  only reason the monochrome style converts at all is that each of its masks is a full-canvas
  rectangle with the cloud subtracted under `evenodd`, which is exactly a `<clip-path>`. The colour
  styles carry gradients inside those masks and cannot be converted. Meteocons 2.0 was rejected for
  having no heavy-rain or heavy-snow icon; 3.0's `extreme-*` tier is what made this possible. Every
  condition has its own drawing and `WeatherIconsTest` asserts it; do not reintroduce a second
  table. What the vectors actually paint is pinned by the Roborazzi goldens in
  `app/src/test/screenshots/`, because an edited path keeps its resource id and every mapping test
  keeps passing. `LICENSE-meteocons` ships the MIT notice.
```

and add, under **Architecture**:

```markdown
- `ui/map/` is the radar tab and is self-contained the way `update/` and `notify/` are. The frames
  come from RainViewer's public API — no key, thirteen frames of the last two hours — through
  `RadarRepository`, which holds them in memory for ten minutes and writes nothing to Room: the
  tiles are not cached either, so a stored frame list would name pictures that can no longer be
  fetched. **RainViewer's radar stops at zoom 7.** Zoom 8 returns a PNG reading "Zoom Level Not
  Supported" rather than a 404, so `RadarTileSource` declares the ceiling and lets osmdroid upscale;
  the map's own zoom is clamped to 6-11. `nowcast` has been empty every time it was checked and is
  ignored. The basemap is osmdroid over the standard OpenStreetMap tiles, which the OSM tile policy
  allows for live app use given a unique User-Agent, honoured cache headers and **no pre-emptive
  fetching** — so there is no download-for-offline here and there must not be one. Both credits in
  `map_attribution` are required, by OSM and by RainViewer respectively. GeoSphere's
  `nowcast-v1-15min-1km` is the better forecast for this province — 1 km, 15 minutes, three hours
  ahead, covering all of South Tyrol — and is not used because a South Tyrol bounding box costs
  4.6 MB of ungzipped GeoJSON against 198 kB of NetCDF; see the design doc.
```

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: record the icon pipeline and the radar tab's limits"
```

---

## Self-review

**Spec coverage.** Every section of `docs/superpowers/specs/2026-09-10-icons-bars-and-radar-design.md` has a task: the icon set (Tasks 4, 5), the conversion rules and the clip-path fallback (Task 4 step 3's docstring and the note in Task 5 step 6), the licence and attribution (Tasks 4, 5), the rain bars (Tasks 1, 2), the station card (Task 3), the radar source and its zoom ceiling (Tasks 6, 8), the basemap and its policy conditions (Task 8), the screen split and the timeline (Task 8), the failure state (Tasks 7, 8), the tab (Task 9), and every test the spec names (Tasks 1, 2, 3, 6, 7, 8, 9). The GeoSphere nowcast is a non-goal and is recorded as one in Task 10 rather than implemented.

**Naming.** `RadarFrame.time`/`base`/`tileUrl`, `RainViewerMapper.map`/`MAX_ZOOM`/`TILE_SIZE`/`COLOR_SCHEME`/`OPTIONS`, `RadarRepository.frames`/`FRESH_FOR`, `MapUiState.frames`/`selected`/`playing`/`place`/`loading`/`frame`/`radarUnavailable`, `PrecipScale.fillFraction`/`isDrawn`/`hasAmount`/`fillColor` are used identically everywhere they appear.

**One thing deliberately repeated.** Task 2's golden re-states the six lines of `PrecipBar` rather than making it internal. The part that must not drift is `PrecipScale`, and both sides read it.
