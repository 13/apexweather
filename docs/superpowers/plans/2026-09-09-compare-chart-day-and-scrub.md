# Compare chart: pick a day, touch to read values

## Goal

Two changes to the Vergleich chart. Choose which day it plots, instead of a fixed three-day sweep. Touch it to read the values at one hour, per model.

## Why they belong in one plan

They need the same structural work. Today the chart's window is written down twice, and its geometry only exists inside the draw lambda, so nothing outside can turn a touch back into a time. Neither feature is possible until both are fixed, and fixing them for one gets the other nearly free.

There is also a plain readability argument. Seventy-two points across roughly 330 dp is about 4.5 dp per hour. Even standing still that is too dense to read a value off. A one-day window is 24 points and about 14 dp per hour, which is where touching a point starts to mean something.

---

## What is wrong today

Four things, all of which the new features would trip over.

**The window is written down twice.** `CompareStateBuilder` owns `WINDOW_HOURS = 72L` (`ui/compare/CompareViewModel.kt:50`) and filters the series with it. The screen then passes `from` and `hours = 72` to the chart as literals (`ui/compare/CompareScreen.kt:92`). Nothing ties the two together. Change one and the axis quietly mislabels data that is plotted correctly, or plots data the axis has no room for. Making the window selectable would break this on the first tap.

**The geometry lives inside the draw lambda.** `left`, `right`, `top`, `bottom`, `x(Instant)` and `y(Double)` are all local to the `Canvas` block (`ui/compare/MultiLineChart.kt:62-66`). A pointer handler sits outside that block and cannot see any of it, so there is no way to answer "which hour is under the finger".

**The x labels are hardcoded to every twelve hours** (`ui/compare/MultiLineChart.kt:81`). On a one-day window that is three labels, two of them at the edges.

**`distinctUntilChanged` on the compare state does nothing.** The comment at `ui/compare/CompareViewModel.kt:104-106` says the 72-hour window is truncated to the hour so the minute tick produces an identical state 59 minutes out of 60. It does not: `CompareUiState.now` carries the untruncated instant straight from the holder (`ui/WeatherStateHolder.kt:84` passes `clock.instant()`), so the state differs every minute and the operator never dedupes anything. The whole compare state, `DailyAggregator.perSource` across all seven models included, is rebuilt every sixty seconds while the tab is open. This is a defect that exists now, not one the new features introduce, but they make the rebuild more expensive and it should go first.

---

## Part 1 — one window, owned by the state

Introduce a window value and let the state carry it, so the builder and the chart cannot disagree.

- `CompareWindow(from: Instant, hours: Long, selection: DaySelection)` on `CompareUiState`.
- `DaySelection` is either `Sweep` (the current three days) or `Day(offsetFromToday: Int)`.
- `CompareStateBuilder.build` takes the selection and derives the window from it. The screen reads `state.window` instead of writing `72` again.

Fold in the `now` fix here, since it is the same file and the same rebuild path: truncate `now` to the hour when it enters `CompareUiState`. The only thing that reads it is `Format.timestamp`, which uses it to decide whether a timestamp is from today, so hour precision is enough and `distinctUntilChanged` starts doing the job its comment claims.

**Tests.** The series contains only points inside the window. The window on the state is the one the chart is handed. Two states built a minute apart from the same snapshot are equal.

---

## Part 2 — the day chips

A scrollable row of chips above the chart: `3 Tage`, `Heute`, `Morgen`, then the weekday for each further day.

**One chip per day that actually exists**, taken from `consensus.daily`, which is where the day table below already gets its rows. Seven today. Models reach different distances, so a later day simply has fewer lines. That is honest and matches how the table already renders a model that does not reach a date.

**Filter chips, not a segmented button.** The screen already uses `FilterChip` for sources (`ui/compare/CompareScreen.kt:103-118`), so the vocabulary is established, and the count varies with how far the models reach. Segmented buttons want a fixed, small, known set.

**A day is the full local calendar day, not "now plus 24 hours".** Midnight to midnight, so two days are comparable and the axis does not creep along by the minute. Open-Meteo is requested with `timezone` and `forecast_days=7` (`data/remote/OpenMeteoApi.kt:24-27`), so today's already-elapsed hours are in the cache; today's chart shows them, behind a marker at the current hour, rather than starting in mid-air. Models whose run began later in the day just start their line later.

**Derive the length from the zone, never assume 24.** Europe/Rome has a 25-hour day on 25 October 2026 and a 23-hour day on 29 March. Compute the span as the duration between the start of the selected day and the start of the next one. A hardcoded 24 puts an hour of data outside the axis twice a year.

**Where the selection lives: `SavedStateHandle`, as an offset in days, not in DataStore and not as a date.**

Not DataStore, because which day you are looking at is view state, not a preference. The two settings that are persisted, variable and sources, are answers to "how do I like to read this"; the day is "what am I looking at right now".

An offset rather than a date, because an absolute date restored the next morning would open the app pinned to yesterday, showing a chart with no lines and no explanation. An offset of 0 means today whenever it is read, so it cannot go stale. `SavedStateHandle` carries it across process death without any of that risk.

**Default stays `3 Tage`,** so nothing about the current screen changes for someone who does not touch the chips. A per-day default would be more readable and is a one-line change if you would rather land on Heute; say so and I will flip it.

**Clearing.** Changing the day clears any point selection from Part 4.

**Strings** in all three languages: `compare_range_3d`, `compare_day_today`, `compare_day_tomorrow`. Further days reuse `Format.weekday`.

**Tests.** Offset 0 in Europe/Rome starts at local midnight and runs to the next local midnight. On 25 October 2026 the window is 25 hours long. Chips exist only for days present in the consensus. The selection survives a `SavedStateHandle` round trip.

---

## Part 3 — chart geometry as a value

Pull the layout arithmetic out of the draw lambda into a small value that both the drawing and the pointer handler can use.

`ChartGeometry` holds the plot rectangle, the value range and the time span, and offers `x(Instant)`, `y(Double)` and the inverse `hourAt(x: Float): Instant`. It is a pure function of the canvas size, the window and the data range, so it is computed once per size-and-data change behind a `remember` and read by both.

While in here, derive the x-tick interval from the span rather than hardcoding twelve hours: every three hours below about 36 hours, every twelve above.

**Tests.** `x(from)` is the left edge and `x(from + hours)` is the right edge. `hourAt(x(t))` returns `t` for every hour in the window. A one-hour window and a zero-width canvas do not divide by zero.

---

## Part 4 — touch to read values

**The gesture.** `awaitEachGesture`: select on the first down and consume the event, follow the finger while it moves, keep the selection when it lifts. Consuming is what stops the enclosing `LazyColumn` (`ui/compare/CompareScreen.kt:73`) from claiming the drag and scrolling the page instead. Keeping the selection after the lift is the point, otherwise the values vanish exactly when the finger stops covering them.

Snap to the nearest hour that has data rather than to a raw pixel.

**On the canvas:** a vertical crosshair at the selected hour, a filled dot on each visible model's line and a larger one on the consensus.

**The readout goes outside the canvas, above the chips.** A tooltip that follows the finger sits under the finger. A fixed panel does not, and it can hold one row per model without fighting the lines for space.

**The readout is always present.** With nothing selected it shows the current hour, or the first hour of the chosen day when that day is not today. That means the layout never jumps when a finger lands, and the affordance is visible before anyone touches anything.

Contents: the hour, the consensus value, then one row per selected model in `Source.ordinal` order, each with its `SourceColors` dot and its value, and an en dash where a model publishes nothing for that hour. This is the same shape as the per-source rows in the hour sheet on the home screen, so it reads as the same idea.

**Haptics.** A light tick each time the snapped hour changes, through `LocalHapticFeedback`. This is the first haptic feedback in the app; keep it to this one place.

**Clearing.** Changing the day, the variable or the set of sources clears the selection, because all three change what the values under the finger mean.

**Accessibility.** The readout rows are ordinary text nodes, so TalkBack reads the values without anyone having to scrub a canvas it cannot describe. The canvas keeps the summary it already has (`ui/compare/MultiLineChart.kt:51-54`) and gains a `stateDescription` naming the selected hour. The values must not live only inside the canvas.

**Tests.** The geometry round trip is a JVM test from Part 3. A device test presses at a known fraction across the chart and asserts the readout names the expected hour and the expected value, and that pressing the chart does not scroll the list.

---

## Deliberately not in this plan

- **The day table and the status list are untouched.** They already answer the seven-day question and the provenance question.
- **KMOS on the wind chart.** Its chip stays selectable while its line is silently absent, because the model publishes no wind. A one-day view makes that absence more visible, not less. It is a real gap and it already has a backlog entry; fixing it means deciding what a chip should look like when the model cannot answer the current question, which is a design decision of its own.
- **Precipitation probability being a maximum rather than a median.** Unrelated, and a product decision.

## Risk to watch

The canvas redraws on every frame of a drag. The geometry and the line paths are remembered per size and data, so a drag should only move the crosshair and the dots. If the paths end up rebuilt inside the draw lambda the drag will stutter, which is the same mistake the sky ridge had before it was hoisted.

## Verification

```bash
./gradlew :app:testDebugUnitTest --console=plain
./gradlew :app:lintDebug --console=plain
ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest --console=plain
```

On the phone:

1. Tap through every day chip and confirm the axis labels match the day and the lines move with it.
2. On Heute, confirm the elapsed hours are drawn and the now marker sits at the current hour.
3. Drag across the chart and confirm the readout follows, the page does not scroll, and lifting the finger leaves the values on screen.
4. Pick a day beyond AROME's reach and confirm its line is absent rather than flat.
5. Switch to Wind and confirm KMOS has no line, and to Niederschlag and confirm the axis stays at zero or above.
6. Sweep the readout with TalkBack.
7. Set the phone to Italian and confirm the chips and the readout read naturally.
