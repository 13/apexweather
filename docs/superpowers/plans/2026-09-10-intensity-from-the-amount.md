# The precipitation label follows the amount

Plan, 2026-09-10. One focused change, plus the two loose ends it settles for free.

## The bug

On the phone at 18:57:

| Hour | Per-source mm | Mean | Label shown |
|------|---------------|------|-------------|
| 18:00 | 0,4 · 0,2 · 0,4 · 0,5 · 0,1 · 0,5 | 0,35 | **Leichter Regen** (DRIZZLE) |
| 19:00 | 0 · 0 · 0,1 · 0,5 · 0,1 · 0,5 | 0,20 | **Regen** (RAIN) |

Half the rain, a heavier word. The hero says "Leichter Regen" over 0,4 mm while the column two
places to its right says "Regen" over 0,2 mm.

## Why

`ConsensusBlender.blendHour` computes two things from the same models, independently:

- **the amount**, as the mean of every model's millimetres;
- **the label**, as `voteCondition(...)` — a plurality vote over the models' own *categories*.

Nothing ties them together, and the wet-share rule makes them actively diverge. At 19:00 three of
six models are dry, so the wet-share rule drops them from the pool and the label is decided by the
three wet ones alone — two of which sit at 0,5 mm, exactly the boundary at which a single model is
called RAIN rather than DRIZZLE. At 18:00 all six models are wet, four of them light, so the
plurality is DRIZZLE.

So **the fewer models that agree it will rain, the heavier the consensus label becomes**, because
the dry ones stop diluting the vote while they continue to drag the mean down. The two quantities
move in opposite directions by construction.

This is the same family as the bug fixed in v0.8.3 — a label voted from categories while the number
beside it is computed from amounts — and it will keep producing new symptoms until the label is
derived from the number.

## The fix

**Vote for the *type*. Derive the *weight* from the blended amount.**

A new pure table, `domain/PrecipIntensity.kt`, is the single place millimetres become a weight:

| Liquid | | Frozen | |
|--------|---|--------|---|
| < 0,1 mm | not precipitation | < 0,1 mm | not precipitation |
| < 0,5 mm | `DRIZZLE` | < 2,5 mm | `SNOW` |
| < 4,0 mm | `RAIN` | ≥ 2,5 mm | `HEAVY_SNOW` |
| ≥ 4,0 mm | `HEAVY_RAIN` | | |

Those are not new numbers. They are the ones `GeoSphereApi.condition` already uses to label a single
model, lifted out so there is one copy instead of three.

`voteCondition` keeps its job of deciding **what kind** of weather the hour is — dry (and how
cloudy), rain-like, sleet, snow, or thunderstorm — including the wet-share rule and the 0,1 mm gate
added in v0.8.3. What it no longer decides is how hard: once the type is rain-like or snow, the
weight comes from the blended amount.

`SLEET` and `THUNDERSTORM` have no weights in the `Condition` enum and pass through untouched.

Two properties follow, and both get a test:

- **Monotonic.** For one type, more millimetres can never produce a lighter label.
- **Consistent.** The hero, the 48-hour strip, the day rows, the icon and the bar's colour all read
  the same table, so no two of them can disagree about the same hour.

### What this gives up

A model that calls an hour HEAVY_RAIN is often describing a burst inside it, not the hour's total.
That judgement is now discarded: the consensus label describes the consensus amount and nothing
else. That is the point — the label sits directly beside the number, and the two contradicting each
other is worse than either being individually debatable.

## The two loose ends it settles

- **`PrecipScale.fillColor` steps at 0,5 and 2,5 mm**, while the app calls rain heavy at **4,0**. A
  3 mm hour is painted in the heavy dark blue under a label reading plain *Regen*. It reads the
  shared table instead, so the colour cannot disagree with the word.
- **`GeoSphereApi.condition`** keeps its behaviour but stops carrying its own copy of the numbers.

Not included: the **zoned track** (faint ticks on the bar at the two class boundaries), which is a
feature rather than a fix and is still an open question.

## Tasks

1. **`domain/PrecipIntensity.kt` + its test.** The table, and `liquid(mm)` / `frozen(mm)` returning a
   `Condition?` (null below 0,1 mm). Tests: each boundary from both sides, and a monotonicity check
   sweeping 0 to 50 mm asserting the result never gets lighter.
2. **`ConsensusBlender`.** `voteCondition` returns the type; the weight is applied from the amount.
   Tests: the recorded 18:00 and 19:00 cases, asserting 0,35 mm and 0,20 mm both come out light and
   that the heavier label cannot attach to the smaller amount; the v0.7.1 "Bedeckt while raining"
   case still wet; the v0.8.3 "0,0 mm is not rain" case still dry; snow keeps its own threshold.
3. **`PrecipScale`** reads the shared table; the golden is re-recorded and looked at.
4. **`GeoSphereApi.condition`** delegates to the table; its mapper tests must pass unchanged, which
   is the check that the numbers really were the same.
5. **On the phone:** the hero and the strip agree, and no hour shows a heavier word than an hour
   with more rain in it. Then the full suite, lint, goldens and the release smoke.
