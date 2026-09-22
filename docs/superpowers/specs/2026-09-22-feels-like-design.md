# Feels-like on the hero — design

2026-09-22

## What this is

The app has carried an apparent temperature since the first commit and has never shown it on the
home screen. `HourlyPoint.feelsLikeC` is Open-Meteo's `apparent_temperature`, requested per model in
`OpenMeteoApi.HOURLY_VARS`, bias-corrected alongside the temperature in `ConsensusBlender`, medianed
into `ConsensusHour.feelsLikeC`, and read by exactly one composable: the `stat_feels` tile in the
hour sheet. The hero says nothing about it, deliberately — the side-by-side row it was once drawn in
only fitted at font scale 1,0 and needed a stacked fallback above it.

This puts it on the hero as a clause of the condition line, under a gate that keeps it silent on the
days it would be arithmetic rather than a sensation, and on a number that is the hero's own.

## The problem worth stating first

The hero temperature is not the consensus. It is the station's reading carried up the hill by
`StationDownscale`, then re-voted for fog and sun, then held inside the bracket its two sources span.
`ConsensusHour.feelsLikeC` is the models' weighted median at the village, and has been through
`BiasCorrector` and nothing else.

Printing the two beside each other compares quantities that were never the same kind of thing. This
is the mistake `StationDownscale` documents at length and pays for in its own arithmetic: two medians
over differently constituted populations are not comparable, and subtracting one from the other
reports the population difference to the reader as though it were weather.

Measured on 2026-09-12 at 08:14 over Dorf Tirol, with Meran fogged in: the hero read 11,0 where the
models put the village at 13,35. Had the hero carried the models' feels-like of about 12,8 that
morning, the screen would have said the air felt nearly two degrees *warmer* than it was, on a still,
saturated morning where it felt colder. Nothing on screen would have accounted for it.

## The offset

`ConsensusHour` gains

```kotlin
/**
 * How much warmer or colder the models say this hour feels than it is, as the weighted median of
 * each model's own (feelsLikeC - tempC).
 *
 * Deliberately not [feelsLikeC] minus [tempC]: those are two medians over two populations — every
 * model publishes a temperature and only the Open-Meteo eleven publish an apparent one — and
 * subtracting them carries the difference between the populations into the answer. Pairing each
 * model with itself first makes that go away, for the reason StationDownscale.offsetAt does it.
 *
 * Null where no contributing model publishes an apparent temperature.
 */
val feelsOffsetC: Double? = null,
```

computed in `blendHour` beside the existing `feels` median, over the same `contributing` map and
through the same `weightedMedian`, so a model's vote is worth `1/sqrt(n)` here as everywhere else:

```kotlin
val feelsOffsets = points.mapNotNullValues { p -> p.feelsLikeC?.let { it - p.tempC } }
…
feelsOffsetC = feelsOffsets.takeIf { it.isNotEmpty() }?.let(::weightedMedian),
```

One property falls out of this and is worth a test of its own. `BiasCorrector` subtracts the same
correction from `tempC` and from `feelsLikeC` (`ConsensusBlender.kt:70-72`), so inside the pair it
cancels exactly. The offset is therefore bias-independent by construction — which is what makes it
safe to add to the hero, a number that has been through an entirely different correction path.

`feelsLikeC` is unchanged and stays. The hour sheet reads it for the forty-seven hours that are
still purely the models'.

## The gate — `domain/FeelsLike.kt`

A pure object, tested off the phone, in the shape `StationDry` and `MeasuredRain` already use.

```kotlin
object FeelsLike {
    /** Wind chill is defined in the cold; above this the models' apparent temperature is arithmetic. */
    const val COLD_MAX_C = 10.0
    /** Humidity and sun are what make an hour feel hotter than it is. */
    const val WARM_MIN_C = 26.0
    /** Less than this and the two numbers are the same fact twice. */
    const val MIN_DELTA_C = 2.0

    /** What to print beside the condition, or null to say nothing. */
    fun shown(tempC: Double?, offsetC: Double?): Double?
}
```

Rules, and each one is load-bearing:

- **Null in, null out.** No offset means no model published an apparent temperature for this hour.
- **Cold side, colder only**: `tempC <= COLD_MAX_C && offsetC <= -MIN_DELTA_C`.
- **Warm side, warmer only**: `tempC >= WARM_MIN_C && offsetC >= +MIN_DELTA_C`.
- **Silent in the middle**, and silent in the two directions left out. A breeze at 30 °C that makes
  an hour feel cooler is relief, not news, and "gefühlt 12° statt 14°" on a mild windy afternoon is a
  second number for the reader to reconcile in exchange for nothing.

The gate reads the **hero** temperature, not the models'. That is the air the reader is standing in,
and it is the number the line will sit beside — gating on one temperature and printing another is the
hero/strip disagreement one level down.

There is deliberately **no second guard** that the two round to different integers. A separation of
2 K cannot round to the same degree, so such a guard could never fire, and a rule that can never fire
is one that will be quietly wrong the day a threshold moves.

The thresholds are judgements, not measurements: 10 °C and 26 °C are where wind chill and heat index
are conventionally published, and 2 K is the separation `ForecastScores` already treats as the edge
of a temperature hit. Say so when changing them.

## Wiring — `ui/home/HomeState.kt`

Two changes, both in `HomeStateBuilder.build`.

**The hero's own value.** `HomeUiState` gains `heroFeelsLikeC: Double?`, set to
`FeelsLike.shown(heroTempC, current?.feelsOffsetC)`. Since `heroTempC` is
`heroFromStation ?: current?.tempC ?: obs?.tempC`, the line works on a place with no station too —
there the hero is the consensus and the offset is the models' own, which is consistent rather than
merely convenient.

**The current hour's tile.** `currentShown` already replaces that hour's `tempC` with the
station-carried hero, because the hero and the strip's first column answer to the same word and must
be the same weather. Its `feelsLikeC` was left behind. It moves with the temperature now:

```kotlin
val currentShown = current?.let { h ->
    if (heroFromStation == null || heroFromStation == h.tempC) h
    else h.copy(
        tempC = heroFromStation,
        feelsLikeC = h.feelsOffsetC?.let { heroFromStation + it },
    )
}
```

This hour alone. A thermometer speaks for the hour it measured; the other forty-seven are still
exactly what the models say.

## The screen — `ui/home/HomeSections.kt`

The condition word gains a clause:

```
Klar · gefühlt 27°                    Regen ab 18:15
Meran, umgerechnet (+1,2°)
Aktualisiert 09:12
```

It is composed as **one `Text`**, not as a third child of the `FlowRow`. That row is
`SpaceBetween` with two children — the condition on the left, the rain line on the right under the
icon, each `alignByBaseline` so the small line sits on the word's baseline and wraps to a line of its
own where the two do not fit. A third child in a `SpaceBetween` row does not degrade that way: it
takes the middle and pushes the rain line's right edge around. Keeping the clause inside the
condition's own string leaves the row's layout untouched at every font scale.

New string, in all three files:

| key | `values` (de) | `values-it` | `values-en` |
|---|---|---|---|
| `hero_condition_feels` | `%1$s · gefühlt %2$s` | `%1$s · percepita %2$s` | `%1$s · feels like %2$s` |

The existing `stat_feels` ("Gefühlt" / "Percepita" / "Feels like") is untouched and keeps the hour
sheet's tile.

The temperature goes through `Format.temp(value, formats)` like every other number in the app. The
test tag `hero_feels` goes on the same node as the condition, since they are the same node; the
condition's own tag is unchanged.

No new drawing, so no new Roborazzi golden.

## Out of scope, and why

- **The 48-hour strip.** A column is `HourColumnBaseWidth` (46 dp) times the reader's text size and
  already carries an icon, a temperature, a bar, a millimetre figure and a probability. A sixth
  number there is unreadable at any font scale.
- **The day list.** A day has no single apparent temperature; its warmest hour and its coldest are in
  different regimes, and the gate would fire on both halves of the same row.
- **The share card.** `ShareCardStateBuilder` computes no weather of its own, so adding it later is
  reading one more field off `HomeUiState` — but a shared PNG leaves the phone with no screen left to
  correct it, and this gate has not yet been seen over a real heatwave. Not now.
- **The widget.** `WidgetStateBuilder` reuses `HomeStateBuilder` and would have the value for free,
  but the hero is one line of a 250 x 110 dp card and a second number costs a row the six hours need.

## Tests

- **`FeelsLikeTest`** (new, JVM) — walks the gate: both boundaries at 10,0 and 26,0, both signs on
  both sides, the exact `MIN_DELTA_C` of 2,0 in and 1,9 out, null offset, null temperature, and the
  mild middle at 18 °C with an offset of ±5 staying silent.
- **`ConsensusBlenderTest`** — a constructed hour where the paired median and the
  median-minus-median differ, so the arithmetic cannot be switched back without a failure; a model
  publishing no `feelsLikeC` contributing its temperature and not the offset; and a bias correction
  applied to one model leaving `feelsOffsetC` unchanged.
- **`HomeStateBuilderTest`** — `heroFeelsLikeC` gated on the station-carried hero and not on the
  consensus, using the 2026-09-12 shape (station well below the models); `currentShown.feelsLikeC`
  moving with `tempC`; null where the hour has no offset; and the no-station case falling back to the
  consensus on both sides.
- **`HomeScreenTest`** — the node present with the clause on a hot hour, absent on a mild one, and,
  at font scale 2,0, the rain line still displayed beside it (the assertion that the `FlowRow` did not
  change shape). Per the project rule, it sets `mainClock.autoAdvance = false` and advances by hand,
  and asserts no German string — the clause is matched through the resource the code itself reads.

## Verification on the phone

`./gradlew :app:testDebugUnitTest` and `:app:lintDebug` clean, then install the debug APK and look.

The honest caveat: on 2026-09-22 in this province the air is in the mild middle most of the day, so
the line is expected to be **absent** on the phone, which proves the gate and not the clause. The
clause itself will be proven by the instrumented test and by temporarily lowering `WARM_MIN_C` on a
scratch build to see it drawn at the real font scales. Whichever of those is done, say which.
