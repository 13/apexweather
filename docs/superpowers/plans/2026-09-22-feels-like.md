# Feels-like on the hero — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put the apparent temperature on the home screen's hero as a clause of the condition line, computed on the hero's own temperature and shown only where wind chill or heat index is actually defined.

**Architecture:** `ConsensusBlender` gains a paired offset — the weighted median of each model's own `feelsLikeC − tempC` — which is added to the hero temperature rather than the models' median being printed beside it. A new pure `domain/FeelsLike.kt` decides whether the result is worth showing. `HomeStateBuilder` wires both and also fixes the current hour's `feelsLikeC`, which was left behind when that hour's `tempC` was swapped for the station reading.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit4 (JVM unit tests), Compose UI tests on an API 34 emulator.

Spec: `docs/superpowers/specs/2026-09-22-feels-like-design.md`

## Global Constraints

- Never apply `org.jetbrains.kotlin.android` in `app/build.gradle.kts`; KSP only, no kapt.
- `./gradlew :app:lintDebug` runs with `warningsAsErrors` and must be clean before a push.
- Every new string key goes into all three of `values/strings.xml` (German, default), `values-it/strings.xml`, `values-en/strings.xml`.
- Nothing that runs on a device may assert a German string: CI emulators are en-US. Resolve strings through the resources the code itself uses.
- Numbers and times go through `ui/common/Format.kt` with an explicit `Formats`; never `Locale.ROOT`, never interpolate a number into a string.
- Device tests run on an emulator, never the phone `RZCXA1ZEXJE`: a connected run uninstalls the app and deletes `station_history`. Boot `~/Android/Sdk/emulator/emulator -avd r8verify34 -no-window` (API 34, **not** `Medium_Phone_API_36.1`).
- Any Compose test that renders `SkyBackground` (directly or through `MainActivity`) must set `rule.mainClock.autoAdvance = false` and advance the clock by hand.
- JVM tests: `./gradlew :app:testDebugUnitTest`, single class with `--tests 'it.apexweather.domain.FeelsLikeTest'`.

---

### Task 1: The paired offset in the blender

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/domain/model/Models.kt` (the `ConsensusHour` data class, around line 469)
- Modify: `app/src/main/kotlin/it/apexweather/domain/ConsensusBlender.kt` (`blendHour`, around lines 224 and 235)
- Modify: `app/src/test/kotlin/it/apexweather/domain/TestData.kt` (the `point` helper)
- Test: `app/src/test/kotlin/it/apexweather/domain/ConsensusBlenderTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `ConsensusHour.feelsOffsetC: Double?` — the weighted median of each contributing model's `feelsLikeC - tempC`, null where no contributing model publishes an apparent temperature. Tasks 3 and 4 read it. Also `point(..., feelsLike: Double? = null, ...)` in `TestData.kt`, used by Tasks 3 and 4.

- [ ] **Step 1: Add the `feelsLike` parameter to the `point` test helper**

In `app/src/test/kotlin/it/apexweather/domain/TestData.kt`, replace the whole `point` function with:

```kotlin
fun point(
    i: Int,
    temp: Double,
    precip: Double = 0.0,
    prob: Int? = null,
    wind: Double? = 5.0,
    gust: Double? = null,
    freezing: Double? = null,
    snowCm: Double? = null,
    feelsLike: Double? = null,
    condition: Condition = Condition.CLEAR,
) = HourlyPoint(
    time = hour(i), tempC = temp, feelsLikeC = feelsLike, precipMm = precip, precipProb = prob,
    windKmh = wind, gustKmh = gust, freezingLevelM = freezing, snowCm = snowCm, condition = condition,
)
```

- [ ] **Step 2: Write the failing tests**

Append these three tests to `app/src/test/kotlin/it/apexweather/domain/ConsensusBlenderTest.kt`, inside the class:

```kotlin
    /**
     * The offset is the median of the per-model differences, not the difference of the two medians.
     *
     * Constructed so the two arithmetics disagree: every model publishes a temperature, only two
     * publish an apparent one, so the temperature median is over three models and the apparent
     * median over two. Median of temps = 12,0; median of the two published feels = 17,0; their
     * difference is 5,0. The paired differences are +2,0 and +4,0, whose median is 3,0.
     */
    @Test
    fun `feels-like offset pairs each model with itself`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0, feelsLike = 12.0))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 12.0))),
            Source.ICON_2I to forecast(Source.ICON_2I, listOf(point(0, 18.0, feelsLike = 22.0))),
        )
        val h = blender.blend(f).hourly.single()
        assertEquals(12.0, h.tempC, 0.0)
        assertEquals(17.0, h.feelsLikeC!!, 0.0)
        assertEquals(3.0, h.feelsOffsetC!!, 0.0)
    }

    @Test
    fun `feels-like offset is null when no model publishes an apparent temperature`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 14.0))),
        )
        assertNull(blender.blend(f).hourly.single().feelsOffsetC)
    }

    /**
     * BiasCorrector subtracts the same correction from tempC and from feelsLikeC, so inside the
     * pair it cancels exactly. That is what makes the offset safe to add to the hero, which has
     * been through a different correction path entirely.
     */
    @Test
    fun `bias correction cancels inside the feels-like offset`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0, feelsLike = 13.0))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 12.0, feelsLike = 15.0))),
        )
        val plain = blender.blend(f).hourly.single()
        val corrected = blender.blend(f, bias = bias(Source.ICON_CH1, 2.0)).hourly.single()
        assertEquals(3.0, plain.feelsOffsetC!!, 0.0)
        assertEquals(3.0, corrected.feelsOffsetC!!, 0.0)
        assertTrue(corrected.tempC < plain.tempC)
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.domain.ConsensusBlenderTest'`

Expected: FAIL. The three new tests do not compile — `feelsOffsetC` is not a member of `ConsensusHour`. (A compile failure is the expected "red" here; the helper change in Step 1 compiles on its own.)

If `blender.blend(f, bias = …)` does not match the real signature, open `ConsensusBlender.blend` and use the parameter name it actually declares — do not change the production signature to suit the test.

- [ ] **Step 4: Add the field to `ConsensusHour`**

In `app/src/main/kotlin/it/apexweather/domain/model/Models.kt`, in the `ConsensusHour` data class, directly after the existing `val feelsLikeC: Double?,` line, add:

```kotlin
    /**
     * How much warmer or colder the models say this hour feels than it is, as the weighted median
     * of each model's own (feelsLikeC - tempC).
     *
     * Deliberately **not** [feelsLikeC] minus [tempC]. Those are two medians over two populations —
     * every model publishes a temperature and only the Open-Meteo runs publish an apparent one —
     * and subtracting them carries the difference between the populations into the answer. Pairing
     * each model with itself first makes that go away, for the same reason
     * [it.apexweather.domain.StationDownscale] measures its offset model by model and then medians,
     * never as one median minus another.
     *
     * It is also bias-independent by construction: [BiasCorrector]'s correction is subtracted from
     * `tempC` and from `feelsLikeC` alike, so inside the pair it cancels. That is what makes it safe
     * to add to the hero temperature, which has been through a different correction path.
     *
     * Null where no contributing model publishes an apparent temperature.
     */
    val feelsOffsetC: Double? = null,
```

- [ ] **Step 5: Compute it in `blendHour`**

In `app/src/main/kotlin/it/apexweather/domain/ConsensusBlender.kt`, find the line

```kotlin
        val feels = points.mapNotNullValues { it.feelsLikeC }
```

and add directly beneath it:

```kotlin
        // Paired with each model's own temperature before the median is taken; see
        // ConsensusHour.feelsOffsetC for why this is not `feels` minus `temps`.
        val feelsOffsets = points.mapNotNullValues { p -> p.feelsLikeC?.let { it - p.tempC } }
```

Then find

```kotlin
            feelsLikeC = feels.takeIf { it.isNotEmpty() }?.let(::weightedMedian),
```

and add directly beneath it:

```kotlin
            feelsOffsetC = feelsOffsets.takeIf { it.isNotEmpty() }?.let(::weightedMedian),
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.domain.ConsensusBlenderTest'`

Expected: PASS, all tests in the class.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/domain/model/Models.kt \
        app/src/main/kotlin/it/apexweather/domain/ConsensusBlender.kt \
        app/src/test/kotlin/it/apexweather/domain/TestData.kt \
        app/src/test/kotlin/it/apexweather/domain/ConsensusBlenderTest.kt
git commit -m "feat: pair each model with itself before medianing the feels-like offset

Two medians over two populations are not comparable: every model publishes a
temperature and only the Open-Meteo runs publish an apparent one. The paired
offset also cancels BiasCorrector, which is what makes it safe to add to the
hero.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NXkEqmLy6tRRAHVomjDsz1"
```

---

### Task 2: The gate

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/domain/FeelsLike.kt`
- Test: `app/src/test/kotlin/it/apexweather/domain/FeelsLikeTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `FeelsLike.shown(tempC: Double?, offsetC: Double?): Double?` and the constants `FeelsLike.COLD_MAX_C = 10.0`, `FeelsLike.WARM_MIN_C = 26.0`, `FeelsLike.MIN_DELTA_C = 2.0`. Task 3 calls `shown`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/it/apexweather/domain/FeelsLikeTest.kt`:

```kotlin
package it.apexweather.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeelsLikeTest {
    @Test
    fun `cold and colder is shown`() {
        assertEquals(3.0, FeelsLike.shown(8.0, -5.0)!!, 0.0)
    }

    @Test
    fun `hot and hotter is shown`() {
        assertEquals(33.0, FeelsLike.shown(30.0, 3.0)!!, 0.0)
    }

    /** The boundaries are inclusive on both axes, so exactly 10,0 with exactly -2,0 counts. */
    @Test
    fun `boundaries are inclusive`() {
        assertEquals(8.0, FeelsLike.shown(10.0, -2.0)!!, 0.0)
        assertEquals(28.0, FeelsLike.shown(26.0, 2.0)!!, 0.0)
    }

    @Test
    fun `just inside the boundary is not shown`() {
        assertNull(FeelsLike.shown(10.1, -5.0))
        assertNull(FeelsLike.shown(25.9, 5.0))
    }

    @Test
    fun `a separation under the threshold is not shown`() {
        assertNull(FeelsLike.shown(5.0, -1.9))
        assertNull(FeelsLike.shown(30.0, 1.9))
    }

    /**
     * A breeze at 30 degrees is relief, not news, and a humid morning at 2 degrees feeling warmer
     * is not a fact anybody acts on. Each side fires in one direction only.
     */
    @Test
    fun `the wrong sign on either side is not shown`() {
        assertNull(FeelsLike.shown(30.0, -5.0))
        assertNull(FeelsLike.shown(2.0, 5.0))
    }

    @Test
    fun `the mild middle is silent whatever the offset`() {
        assertNull(FeelsLike.shown(18.0, 5.0))
        assertNull(FeelsLike.shown(18.0, -5.0))
    }

    @Test
    fun `nulls in, null out`() {
        assertNull(FeelsLike.shown(null, -5.0))
        assertNull(FeelsLike.shown(2.0, null))
        assertNull(FeelsLike.shown(null, null))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.domain.FeelsLikeTest'`

Expected: FAIL — unresolved reference `FeelsLike`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/it/apexweather/domain/FeelsLike.kt`:

```kotlin
package it.apexweather.domain

/**
 * Whether an apparent temperature is worth putting beside the real one, and what it is.
 *
 * "Gefühlt" only means something where something is doing the feeling. Wind chill is defined in the
 * cold and the heat index in the heat; in the mild middle an apparent temperature is arithmetic over
 * humidity and wind, and printing it there gives the reader a second number to reconcile in exchange
 * for nothing. So this is silent unless the hour is cold *and* feels colder, or hot *and* feels
 * hotter.
 *
 * The one-sidedness is deliberate on both ends. A breeze at 30 °C that takes the edge off is relief
 * rather than news, and a humid morning at 2 °C that feels a degree warmer is not a fact anybody
 * acts on.
 *
 * The thresholds are judgements rather than measurements, and should be changed as judgements:
 * 10 °C and 26 °C are where wind chill and heat index are conventionally published, and 2 K is the
 * separation [it.apexweather.domain.ForecastScores] already treats as the edge of a temperature hit.
 *
 * There is deliberately no second guard that the two round to different degrees. A separation of
 * [MIN_DELTA_C] cannot round to the same integer, so such a rule could never fire — and a rule that
 * can never fire is one that will be quietly wrong the day a threshold moves.
 */
object FeelsLike {
    /** At or below this, wind chill is defined and a colder-feeling hour is worth saying. */
    const val COLD_MAX_C = 10.0

    /** At or above this, humidity and sun are what make an hour feel hotter than it is. */
    const val WARM_MIN_C = 26.0

    /** Any less than this and the two numbers are the same fact twice. */
    const val MIN_DELTA_C = 2.0

    /**
     * The apparent temperature to print beside [tempC], or null to say nothing.
     *
     * [tempC] is the **hero's** temperature — the air the reader is standing in, which in this app
     * is usually a station reading carried up the hill and not the models' median. [offsetC] is
     * [it.apexweather.domain.model.ConsensusHour.feelsOffsetC], which is safe to add to it because
     * it is a per-model difference and so carries neither the population gap nor the bias
     * correction.
     */
    fun shown(tempC: Double?, offsetC: Double?): Double? {
        if (tempC == null || offsetC == null) return null
        val cold = tempC <= COLD_MAX_C && offsetC <= -MIN_DELTA_C
        val warm = tempC >= WARM_MIN_C && offsetC >= MIN_DELTA_C
        return if (cold || warm) tempC + offsetC else null
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.domain.FeelsLikeTest'`

Expected: PASS, 8 tests.

If the KDoc reference to `ForecastScores` does not resolve (wrong package), drop the `[…]` brackets and leave the plain name in prose. Lint runs with `warningsAsErrors` and an unresolved KDoc link can be a warning.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/domain/FeelsLike.kt \
        app/src/test/kotlin/it/apexweather/domain/FeelsLikeTest.kt
git commit -m "feat: show an apparent temperature only where one is defined

Wind chill in the cold, heat index in the heat, silence in the mild middle,
and one direction only on each side.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NXkEqmLy6tRRAHVomjDsz1"
```

---

### Task 3: Wiring into the home state

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/ui/home/HomeState.kt` (the `HomeUiState` data class near `heroAdjustmentC`, and `HomeStateBuilder.build` at the `currentShown` block and the `HomeUiState(...)` construction)
- Test: `app/src/test/kotlin/it/apexweather/ui/home/HomeStateBuilderTest.kt`

**Interfaces:**
- Consumes: `ConsensusHour.feelsOffsetC` (Task 1), `FeelsLike.shown` (Task 2), `point(..., feelsLike = …)` (Task 1).
- Produces: `HomeUiState.heroFeelsLikeC: Double?` — already gated, so the screen prints it when it is non-null and nothing when it is null. Task 4 reads it.

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/kotlin/it/apexweather/ui/home/HomeStateBuilderTest.kt`, inside the class:

```kotlin
    /**
     * A hot afternoon: the models put the hour at 27 °C and feeling 4 K hotter, so the clause is
     * shown at 31.
     */
    @Test
    fun `hero feels-like is shown on a hot hour`() {
        val hot = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, (0 until 48).map { point(it, 27.0, feelsLike = 31.0) }),
            Source.ICON_D2 to forecast(Source.ICON_D2, (0 until 48).map { point(it, 27.0, feelsLike = 31.0) }),
        )
        val snap = WeatherSnapshot.EMPTY.copy(forecasts = hot)
        val s = HomeStateBuilder.build(DORF_TIROL, snap, AppSettings(), blender.blend(hot), now = hour(3).plusSeconds(600))
        assertEquals(31.0, s.heroFeelsLikeC!!, 0.0)
    }

    @Test
    fun `hero feels-like is silent in the mild middle`() {
        val mild = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, (0 until 48).map { point(it, 18.0, feelsLike = 23.0) }),
            Source.ICON_D2 to forecast(Source.ICON_D2, (0 until 48).map { point(it, 18.0, feelsLike = 23.0) }),
        )
        val snap = WeatherSnapshot.EMPTY.copy(forecasts = mild)
        val s = HomeStateBuilder.build(DORF_TIROL, snap, AppSettings(), blender.blend(mild), now = hour(3).plusSeconds(600))
        assertNull(s.heroFeelsLikeC)
    }

    @Test
    fun `hero feels-like is null when no model publishes an apparent temperature`() {
        val s = HomeStateBuilder.build(DORF_TIROL, snapshot, AppSettings(), consensus, now = hour(3).plusSeconds(600))
        assertNull(s.heroFeelsLikeC)
    }

    /**
     * The gate reads the hero, not the models. Here the models put a cold morning at 12 °C — above
     * COLD_MAX_C, so on the models alone nothing would be shown — while the station, carried up the
     * hill, puts the village at 8. The clause belongs on the air the reader is standing in.
     */
    @Test
    fun `hero feels-like is gated on the station-carried hero and not on the consensus`() {
        val cold = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, (0 until 48).map { point(it, 12.0, feelsLike = 8.0) }),
            Source.ICON_D2 to forecast(Source.ICON_D2, (0 until 48).map { point(it, 12.0, feelsLike = 8.0) }),
        )
        val c = blender.blend(cold)
        val snap = WeatherSnapshot.EMPTY.copy(
            forecasts = cold,
            observation = observation(8.0),
            stationReference = reference(warmerBy = 0.0),
        )
        val s = HomeStateBuilder.build(DORF_TIROL, snap, AppSettings(), c, now = hour(3).plusSeconds(600))
        assertEquals(8.0, s.heroTempC!!, 0.2)
        // offset is -4,0; hero is 8, so the clause is 4.
        assertEquals(4.0, s.heroFeelsLikeC!!, 0.3)
    }

    /**
     * The hero and the strip's first column are the same hour. Its temperature was already swapped
     * for the station's; its apparent temperature has to move with it, or tapping that column
     * quotes the models' feels-like over the station's air.
     */
    @Test
    fun `the current hour's feels-like moves with its temperature`() {
        val warm = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, (0 until 48).map { point(it, 20.0, feelsLike = 23.0) }),
            Source.ICON_D2 to forecast(Source.ICON_D2, (0 until 48).map { point(it, 20.0, feelsLike = 23.0) }),
        )
        val c = blender.blend(warm)
        val snap = WeatherSnapshot.EMPTY.copy(
            forecasts = warm,
            observation = observation(26.0),
            stationReference = reference(warmerBy = 0.0),
        )
        val s = HomeStateBuilder.build(DORF_TIROL, snap, AppSettings(), c, now = hour(3).plusSeconds(600))
        val hero = s.heroTempC!!
        assertEquals(hero + 3.0, s.currentHour!!.feelsLikeC!!, 0.001)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.home.HomeStateBuilderTest'`

Expected: FAIL — unresolved reference `heroFeelsLikeC`.

- [ ] **Step 3: Add the field to `HomeUiState`**

In `app/src/main/kotlin/it/apexweather/ui/home/HomeState.kt`, directly after the `val heroAdjustmentC: Double? = null,` property and its KDoc, add:

```kotlin
    /**
     * What the hero's hour feels like, or null where saying so would tell the reader nothing.
     *
     * Already gated: see [it.apexweather.domain.FeelsLike]. The screen prints it where it is
     * present and prints nothing where it is not — the decision is not the composable's.
     *
     * It is the **hero's** temperature plus the models' paired offset, never the models' own
     * apparent median, because the hero is usually a station reading carried up the hill and those
     * two numbers come from different populations.
     */
    val heroFeelsLikeC: Double? = null,
```

- [ ] **Step 4: Carry the offset onto the current hour**

In the same file, in `HomeStateBuilder.build`, replace the `currentShown` block:

```kotlin
        val currentShown = current?.let { h ->
            if (heroFromStation == null || heroFromStation == h.tempC) h else h.copy(tempC = heroFromStation)
        }
```

with:

```kotlin
        val currentShown = current?.let { h ->
            if (heroFromStation == null || heroFromStation == h.tempC) h else h.copy(
                tempC = heroFromStation,
                // The apparent temperature has to move with the real one. Only the condition was
                // ever carried across to this hour and the temperature quietly was not; this is the
                // same omission one field further along, and it shows up as a tile in the hour sheet
                // quoting the models' feels-like over the station's air.
                feelsLikeC = h.feelsOffsetC?.let { heroFromStation + it },
            )
        }
```

- [ ] **Step 5: Set the field in the returned state**

In the same function, in the `return HomeUiState(` block, directly after the line

```kotlin
            heroAdjustmentC = adjustment?.takeIf { heroFromStation != null },
```

add:

```kotlin
            heroFeelsLikeC = FeelsLike.shown(heroFromStation ?: current?.tempC ?: obs?.tempC, current?.feelsOffsetC),
```

Then add the import at the top of the file, in alphabetical position among the other `it.apexweather.domain.*` imports:

```kotlin
import it.apexweather.domain.FeelsLike
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.home.HomeStateBuilderTest'`

Expected: PASS, the whole class.

If the two station-backed tests do not reach the station path (`heroTempC` comes back as the consensus), check `observation(...)` and `reference(...)` in that test class: they already exist and are used by the neighbouring tests, so copy the call shape from the nearest passing station test rather than inventing arguments.

- [ ] **Step 7: Run the whole unit suite**

Run: `./gradlew :app:testDebugUnitTest`

Expected: PASS. `ConsensusHour` gained a defaulted field, so nothing that constructs one positionally should break — if something does, it is constructing `ConsensusHour` positionally and should be fixed to use named arguments.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/home/HomeState.kt \
        app/src/test/kotlin/it/apexweather/ui/home/HomeStateBuilderTest.kt
git commit -m "feat: gate the hero's feels-like on the hero's own temperature

And carry the offset onto the current hour, whose temperature was swapped for
the station's while its apparent temperature was left behind.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NXkEqmLy6tRRAHVomjDsz1"
```

---

### Task 4: The clause on screen

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-it/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`
- Modify: `app/src/main/kotlin/it/apexweather/ui/home/HomeSections.kt` (the `FlowRow` holding the condition and the rain line, around lines 165-180)
- Test: `app/src/androidTest/kotlin/it/apexweather/ui/home/HomeScreenTest.kt`

**Interfaces:**
- Consumes: `HomeUiState.heroFeelsLikeC` (Task 3).
- Produces: the node tagged `hero_feels` — which is the *same node* as the condition, since the clause is part of the condition's own string.

- [ ] **Step 1: Add the string to all three languages**

In `app/src/main/res/values/strings.xml`, beside the other hero strings:

```xml
    <string name="hero_condition_feels">%1$s · gefühlt %2$s</string>
```

In `app/src/main/res/values-it/strings.xml`:

```xml
    <string name="hero_condition_feels">%1$s · percepita %2$s</string>
```

In `app/src/main/res/values-en/strings.xml`:

```xml
    <string name="hero_condition_feels">%1$s · feels like %2$s</string>
```

- [ ] **Step 2: Write the failing tests**

Append to `app/src/androidTest/kotlin/it/apexweather/ui/home/HomeScreenTest.kt`, inside the class:

```kotlin
    /** A hot hour, built from the models so the whole path is exercised rather than the state faked. */
    private fun hotState(): HomeUiState {
        fun hot(source: Source) = SourceForecast(
            source, t0, t0,
            hourly = (0 until 168).map {
                HourlyPoint(
                    t0.plusSeconds(it * 3600L), 30.0, feelsLikeC = 34.0,
                    precipMm = 0.0, windKmh = 6.0, gustKmh = 21.0,
                    condition = Condition.CLEAR,
                )
            },
            daily = emptyList(),
        )
        val snap = WeatherSnapshot.EMPTY.copy(
            forecasts = mapOf(Source.ICON_CH1 to hot(Source.ICON_CH1), Source.ICON_D2 to hot(Source.ICON_D2)),
        )
        return HomeStateBuilder.build(dorfTirol, snap, AppSettings(), ConsensusBlender().blend(snap.forecasts), t0.plusSeconds(60))
    }

    @Test
    fun hotHourShowsTheFeelsLikeClause() {
        val hot = hotState()
        rule.setContent { ApexTheme { HomeContent(hot, onRefresh = {}, onOpenBulletin = {}) } }
        val expected = context.getString(
            R.string.hero_condition_feels,
            context.getString(Condition.CLEAR.labelRes()),
            "34°",
        )
        rule.onNodeWithTag("hero_feels").assertTextContains(expected)
    }

    /** The default fixture sits in the mild middle, so the hero says nothing about how it feels. */
    @Test
    fun mildHourShowsTheConditionAlone() {
        rule.setContent { ApexTheme { HomeContent(state, onRefresh = {}, onOpenBulletin = {}) } }
        rule.onNodeWithTag("hero_feels")
            .assertTextContains(context.getString(Condition.PARTLY_CLOUDY.labelRes()))
    }
```

`Condition.labelRes()` is the non-composable form of the condition's label. If it is named differently in `ui/common/WarningVisuals.kt`'s neighbourhood or on `Condition` itself, use whatever the widget uses — `widget/ApexWidget.kt` renders outside a composition and must already resolve a condition's label from resources.

- [ ] **Step 3: Boot the emulator and run the tests to verify they fail**

```bash
~/Android/Sdk/emulator/emulator -avd r8verify34 -no-window &
adb wait-for-device
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.home.HomeScreenTest
```

Expected: FAIL — no node with tag `hero_feels`.

- [ ] **Step 4: Draw the clause**

In `app/src/main/kotlin/it/apexweather/ui/home/HomeSections.kt`, replace the condition `Text` inside the `FlowRow`:

```kotlin
            Text(
                state.heroCondition.label(),
                style = MaterialTheme.typography.headlineMedium, color = Color.White,
                modifier = Modifier.alignByBaseline(),
            )
```

with:

```kotlin
            // The apparent temperature is a clause of the condition, not a third child of this row.
            // This FlowRow is SpaceBetween with two children — the condition on the left, the rain
            // line on the right under the icon — and a third child would take the middle and push
            // the rain line's right edge around at every font scale. Inside the condition's own
            // string the row's layout is untouched.
            //
            // Whether there is a clause at all was decided in HomeStateBuilder; see FeelsLike.
            val condition = state.heroCondition.label()
            Text(
                state.heroFeelsLikeC
                    ?.let { stringResource(R.string.hero_condition_feels, condition, Format.temp(it, formats)) }
                    ?: condition,
                style = MaterialTheme.typography.headlineMedium, color = Color.White,
                modifier = Modifier.alignByBaseline().testTag("hero_feels"),
            )
```

Check the imports at the top of the file: `androidx.compose.ui.platform.testTag` is not the right one — it is `androidx.compose.ui.platform.testTag`'s sibling `androidx.compose.ui.platform.testTag`. Use whatever import the other `testTag(` calls in this same file already rely on; `Format` and `formats` are already in scope in this composable, since the rain line beside it uses both.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.home.HomeScreenTest
```

Expected: PASS, the whole class.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/home/HomeSections.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-it/strings.xml \
        app/src/main/res/values-en/strings.xml \
        app/src/androidTest/kotlin/it/apexweather/ui/home/HomeScreenTest.kt
git commit -m "feat: the hero says how the hour feels, as a clause of the condition

One Text rather than a third child of a SpaceBetween row, so the rain line
keeps its right edge and its existing wrap at every font scale.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NXkEqmLy6tRRAHVomjDsz1"
```

---

### Task 5: The large-text case, lint, and the phone

**Files:**
- Test: `app/src/androidTest/kotlin/it/apexweather/ui/home/HomeScreenTest.kt`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

Append to `app/src/androidTest/kotlin/it/apexweather/ui/home/HomeScreenTest.kt`:

```kotlin
    /**
     * The clause lives inside the condition's own string precisely so this row does not change
     * shape. At a 2x font scale the rain line still has to be on screen beside it — that is the
     * assertion that the FlowRow was not turned into a three-child SpaceBetween.
     */
    @Test
    fun atLargeTextTheRainLineSurvivesTheFeelsLikeClause() {
        val hot = hotState().copy(minutelyStart = t0.plusSeconds(3600))
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    density = androidx.compose.ui.platform.LocalDensity.current.density,
                    fontScale = 2.0f,
                ),
            ) {
                ApexTheme { HomeContent(hot, onRefresh = {}, onOpenBulletin = {}) }
            }
        }
        rule.onNodeWithTag("hero_feels").assertIsDisplayed()
        rule.onNodeWithTag("rain_starts_at").assertIsDisplayed()
    }
```

- [ ] **Step 2: Run it**

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.home.HomeScreenTest
```

Expected: PASS. If `rain_starts_at` is not displayed, the FlowRow has been changed into something that no longer wraps — go back to Task 4 Step 4 and keep the clause inside the condition's `Text`.

- [ ] **Step 3: Run everything**

```bash
./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintDebug
```

Expected: all PASS. The goldens are untouched — nothing new is drawn, only a longer string in an existing `Text`. If `verifyRoborazziDebug` fails, do **not** re-record: find out what moved first.

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/kotlin/it/apexweather/ui/home/HomeScreenTest.kt
git commit -m "test: the rain line survives the feels-like clause at font scale 2

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NXkEqmLy6tRRAHVomjDsz1"
```

- [ ] **Step 5: Install on the phone and look**

```bash
./gradlew :app:assembleDebug
adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/debug/ApexWeather-debug.apk
```

Open the app on Dorf Tirol. **The expected result is that the clause is absent**, because in late September this province sits in the mild middle most of the day — that proves the gate, not the clause. To see the clause itself, temporarily lower `FeelsLike.WARM_MIN_C` to 15.0 on a scratch build, check it at font scale 1,0 and 2,0 in all three languages, then revert the constant and rebuild.

Report which of those two you actually did. Do not write "verified on the phone" for the half you did not see.

---

## Self-review notes

- Spec coverage: the offset (Task 1), the gate (Task 2), the wiring including the current hour's tile (Task 3), the screen and the strings (Task 4), the large-text case and the phone (Task 5). The spec's "out of scope" list — strip, day list, share card, widget — is not implemented anywhere, which is correct.
- `ConsensusHour.feelsOffsetC` is defaulted to null, so no existing construction site breaks; Task 3 Step 7 is the check for that.
- Names used consistently throughout: `feelsOffsetC`, `FeelsLike.shown`, `heroFeelsLikeC`, `hero_condition_feels`, tag `hero_feels`.
