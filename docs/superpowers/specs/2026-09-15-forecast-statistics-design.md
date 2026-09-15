# Forecast statistics — design

Date: 2026-09-15. Status: approved in conversation, awaiting written review.

## Why

The app blends twelve models checkable at a station, corrects each for its habit over seven days, and
never tells the reader which of them has actually been right here. The only trace of it is a
per-model warm/cold bias inside the source sheet. The reader asked for a screen that shows which
source is right, how wrong each one is, which is best and which is worst.

## Scope, decided

- **Quantities:** temperature, rain and wind speed. Gusts, humidity and sky are out.
- **History kept:** 90 days, with period filters of 7, 30 and 90 days. `BiasCorrector` keeps reading
  only its 7-day `WINDOW`, so the forecast correction is unchanged.
- **Placement:** a "Treffsicherheit" card on Vergleich opens its own Statistik screen. Each source
  sheet gets one line with the source's rank.
- **Scores:** standard verification, presented simply. Average error, hit rate and lean; the
  consensus and a "wie gestern" baseline as reference rows; too little data is listed, not ranked.
- **Storage:** extend `station_history` with a written Room migration.

## Data and storage

### What is recorded

Written in `WeatherRepository` on the path that writes temperature today.

**Observed at the station, per hour**
- temperature (`observedC`, as now)
- wind speed (`observedWindKmh`, SIAG `ff`, km/h)
- rain since midnight (`observedPrecipTodayMm`, SIAG `n`), stored exactly as reported

**Forecast at the station, per model, per lead (`LeadBucket` NOW / SIX / TWELVE), per hour**
- temperature, rain (mm in that hour) and wind speed (km/h)
- Open-Meteo station call: `hourly=temperature_2m,precipitation,wind_speed_10m`. Measured
  2026-09-15 for Meran's coordinates and the eleven models: 1 826 B gzipped with temperature alone,
  3 319 B with rain and wind (4 405 B with gusts, not taken).
- GeoSphere AROME station call: `parameters=t2m,rr_acc,u10m,v10m`. `GeoSphereMapper.map` already
  turns these into hourly `precipMm` and `windKmh`.
- SIAG KMOS stays out: it is addressed by municipality and cannot be checked at a station.

### Schema

`HistoryDatabase` version 1 → 2, with a written `Migration(1, 2)`:

- add nullable columns `observedWindKmh REAL` and `observedPrecipTodayMm REAL` to `station_history`
- `modelsJson` changes from lead → source → temperature to lead → source → `{ "t": …, "rr": …, "ff": … }`,
  every field nullable. The migration rewrites every existing row into the new shape, so temperature
  history survives.
- `exportSchema = true`, with the schema directory configured, so later migrations can be tested
  against exported schemas.

No destructive fallback, as today.

### Retention

`history.prune` keeps 90 days (`ForecastScores.HISTORY`). `BiasCorrector.biases` already filters to
its own `WINDOW` and must give identical results from a 90-day history.

### Hourly rain at the station

- An hour's observed rain is `total(h) − total(h−1)` when both hours exist and fall on the same local
  date in `SouthTyrol.ZONE`.
- The first hour of a local day uses `total(h)` alone.
- A missing previous hour, or a negative difference (a station reset), leaves that hour unscored for rain.

### Reference rows

Computed when scores are computed, never stored.

- **Konsens:** the weighted median of the models' values at the station for that hour and lead, using
  `ConsensusBlender.weightedMedian` and its `weightOf` rule over the models present that hour.
- **Wie gestern:** the station's own observation 24 hours earlier, for temperature and wind; absent if
  that hour is missing. Not shown for rain, because hourly persistence of rain is meaningless.

## Scores

Pure Kotlin in `domain/ForecastScores.kt`. They are computed off the main thread for one quantity,
one period and one lead, over hours that have both an observation and that model's forecast. Counts
are per model.

| Quantity | Ranked by | Also shown |
|---|---|---|
| Temperature | Ø error: mean absolute error, K | hits: share within ±2 K; lean: mean signed error, K |
| Wind | Ø error, km/h | hits: share within ±5 km/h; lean, km/h |
| Rain | "Regen richtig" = hits ÷ (hits + misses + false alarms) | detected = hits ÷ (hits + misses); false alarms = false alarms ÷ (hits + false alarms) |

An hour is wet at ≥ 0,1 mm, observed or forecast. Rain is not ranked by plain accuracy: about 83 %
of hours here are dry, so a model that never forecasts rain would win.

### Rules

- **Minimum to rank:** 24 scored hours. For rain, also at least 5 hours that were wet observed or wet
  forecast. Below that a model is listed at the bottom as "zu wenig Daten (n h)".
- **Ties:** higher hit rate (rain: detected) first, then `Source` declaration order.
- **Station faults:** a single error above 15 K for temperature or 60 km/h for wind is excluded, and
  counted. The count of excluded hours is shown in the info panel.
- **Reference rows** carry no rank number and do not count toward "Platz n von m".
- **Parts of the day** (`DayPart`) and the **daily average error** series are computed for the model
  detail sheet.

## Screens

### Card on Vergleich — "TREFFSICHERHEIT"

- Top 3 for temperature, 30 days, 6 h ahead, and "Alle Statistiken ›".
- Before anything can be ranked: "Noch zu wenig Daten – erste Werte nach etwa einem Tag".
- Without a station: the no-station sentence.

### Statistik screen

A navigation destination with back navigation, reached from the card, following `XScreen` +
`XContent(state, callbacks)`. Top to bottom:

1. Switch: Temperatur / Regen / Wind.
2. Period chips 7 T / 30 T / 90 T (30 preselected). Lead chips jetzt / 6 h / 12 h (6 h preselected).
3. Basis line: "612 Stunden seit 15.09. · Station Meran · 6 h voraus".
4. Table. Each row shows rank, colour dot (`SourceColors`), `Source.shortName`, the ranking score,
   hits, and lean (rain: detected, false alarms). The Konsens and "wie gestern" rows sit between
   ranked rows at their score and are styled as references. Unranked models are listed last.
5. ⓘ opens a plain-words explanation of each score, the thresholds, the excluded-hours count, and
   why KMOS is absent.

Selection of quantity, period and lead lives in `SavedStateHandle`.

### Model detail sheet

A `ModalBottomSheet` with `skipPartiallyExpanded = true` and a close cross, opened by tapping a row:

- the same scores by part of the day
- the daily average error over the period as a small line chart
- "Details zur Quelle ›", which opens the existing source sheet

### Source sheet

One more line in its share block:
"Treffsicherheit: Platz 2 von 12 · Ø 1,3 K (30 Tage, 6 h)", or "noch zu wenig Daten".

### Places

- Statistics belong to the chosen place's station.
- A place without a station shows "Für diesen Ort gibt es keine Station in der Nähe, gegen die geprüft
  werden kann."
- History already follows `AppSettings.keptPlaces`.

### Accessibility and layout

- Each table row is one merged semantics node with a full sentence, e.g. "Platz 1, ICON-CH1,
  durchschnittlich 1,1 Kelvin daneben, 78 Prozent Treffer, neigt 0,3 Kelvin zu warm".
- At a large font scale a row wraps to two lines rather than clipping columns.
- Colour is never the only signal.
- Strings in `values`, `values-it`, `values-en`. Numbers through `Format`.

## Edge cases

- **Daylight saving:** hours are UTC epoch seconds; parts of the day and the rain day boundary use
  local time in `SouthTyrol.ZONE`, so 23- and 25-hour days score correctly.
- **Missing hours:** a gap in the station or in one model removes that hour for that model only.
- **Rows from version 1:** no rain or wind. They count for temperature only.
- **Place switch:** the screen follows the current place.
- **Device tests:** run on an emulator where one is attached. A connected test run on the phone
  uninstalls the app and deletes `station_history`, so the reader is asked first.

## Tests

### Unit
- **`ForecastScoresTest`:**
  - average error, hits and lean for temperature and wind, against hand-computed values
  - rain contingency table
  - a never-wet model not ranked first
  - the 24-hour and 5-wet-hour minimums
  - tie rule
  - fault cut-off and its count
  - Konsens median
  - "wie gestern" at 24 h and absent across a gap
  - part-of-day and daily series
- **`HourlyRainTest`:** midnight, reset, missing hour, spring and autumn DST days.
- **`HistoryMigrationTest`:** a version 1 database with real rows migrated to version 2; temperature
  history reads back unchanged and new fields are null.
- **`WeatherRepositoryTest`:**
  - a refresh writes wind and rain into history
  - both station calls carry the new parameters, asserted by the fakes
  - prune keeps 90 days
  - `BiasCorrector` gives identical biases from a 90-day history
- **`StatsStateBuilderTest`:** filters, unranked last, reference rows, no-station, empty state.

### Screenshot
Roborazzi goldens of the Statistik screen with a realistic 30-day data set, and of the Vergleich card.

### Device
Card opens the screen; switching to Regen; tapping a model opens its sheet; the cross closes it. No
German asserted.

### On the phone
The migration runs on the real history database without losing rows (count before and after), then
the screen is checked.

## Delivery

- After v0.24.0 (source sheet) is merged, on a new branch from main.
- Plan, subagent-driven implementation with task reviews and a final review, then release as v0.25.0.

## Out of scope

- Gusts, humidity, sky condition against radiation.
- Scores for places other than the kept ones.
- Weighting the consensus by these scores. The existing rule stands until months of scores exist; it
  would need its own design.
