# PWS reading unmoved, and strong wind on the home screen

Two independent features. Branch each separately (`feat/pws-raw-reading`, `feat/strong-wind`).

## Part A: an amateur station's reading is shown as read

**Decision (Ben, 2026-09-25):** "don't make adjustments if there is a pws". A private station is
chosen because it stands in or beside the village (DEM-checked height, stability-ranked, or picked
by hand on the stations screen), so carrying it "up the hill" corrects a height difference that
barely exists and replaces a real village thermometer with a model-shaped number. The provincial
(SIAG) station keeps `StationDownscale` unchanged: it stands on the valley floor, up to 400 m away.

**Assumption to confirm:** "a pws near" is read as "the place reads a PWS" (catalogue `pws` or a
chosen amateur station). Not implemented: skipping the move for a *SIAG* station that happens to be
level and close; that would be a separate rule on `|heightDifferenceM| <= LEVEL_STATION_M` plus a
distance, and is one line if wanted.

**Latent bug this also removes:** `HomeStateBuilder` computes `heightDifferenceM` from
`place.station` (SIAG) even when the reading and the `stationReference` are at `place.pws`'s
coordinates (`WeatherRepository` fetches the reference at `readingStation`). A PWS reading was being
moved by the SIAG station's height gap. After this change no PWS reading is moved at all.

### Tasks

1. **Test first** (`HomeStateBuilderTest`): place with `pws`, snapshot whose `observation` is the
   amateur one (differs from `officialObservation`), reference that would move it by −2 K. Expect
   `heroTempC == obs.tempC`, `heroAdjustmentC == null`, `observationIsPrivate == true`.
   Second test: same place but the amateur reading fell back to SIAG (`observation ==
   officialObservation`) is still moved exactly as today.
2. **`HomeState.kt` ~L232–255:** compute `isPrivate` before `heroFromStation`; when private,
   `heroFromStation = obs?.tempC` and `adjustment = null`. Leave the SIAG path untouched.
   `StationFault` remains the only guard on a PWS reading (it already drops a broken one and the
   repository falls back to SIAG) — note that in the comment, since the bracket no longer applies.
3. **Feels-like:** `FeelsLike.shown(heroFromStation …)` then takes the raw PWS temperature — right,
   it is the village's own. No change needed; add an assertion to the test in 1.
4. **Hero line:** nothing to change: `heroAdjustmentC == null` already selects `now_from_station`
   ("Jetzt: Station Privat ITIROL16, …"). Share card already suppresses the adjustment for private
   readings (`ShareCardState.kt:171`); leave it, it becomes redundant but harmless.
5. **`StationDownscale` KDoc + `StationFault` KDoc:** one paragraph each saying the downscale is for
   the provincial station only. `StationFault`'s "everything downstream is already defended" line
   is no longer true for a PWS — rewrite it.
6. **CLAUDE.md:** in the "hero temperature is the station's reading only after StationDownscale"
   bullet, add that an amateur reading is shown unmoved, and why.
7. Verify: `:app:testDebugUnitTest`, `:app:lintDebug`, then on the phone with a PWS place (Dorf
   Tirol / ITIROL16): hero equals the station card's reading, footnote has no "umgerechnet".
   Switch the place to SIAG on the stations screen: "umgerechnet (…)" comes back.

## Part B: strong wind — hero line and strip markers

Today wind appears only in the hour sheet and the station card. Nothing on the main screen warns
of a gusty afternoon.

### Rule (pure, `domain/StrongWind.kt`)

- **Gust, not mean wind**: gusts are what knock things over and what the Föhn brings in these valleys.
- **Thresholds** (DWD/ Beaufort, km/h, internal): `STRONG_GUST = 50` (Bft 7, "starke Böen"),
  `STORM_GUST = 75` (Bft 9, "Sturmböen"). Two levels so the marker can say how bad.
- **Not the consensus `gustKmh`.** `ConsensusBlender` makes that the *maximum* across models on
  purpose (for the hour sheet), which is exactly the "single most alarmist model sets the figure"
  mistake the precip chance used to make. The trigger uses the **weighted median of the models'
  gusts** (`ConsensusBlender.weightedMedian` over `perSource`, family weights), and requires at
  least `MIN_SOURCES = 3` models publishing gusts. Add `gustMedianKmh: Double?` to `ConsensusHour`
  in the blender rather than recomputing it in UI code. The number shown is that median, rounded
  to 5 km/h: the hour sheet's max stays as the "up to" figure.
- **Current hour:** where the station publishes a gust (`StationObservation.gustKmh`, SIAG and WU
  both carry one) and the reading is fresh, the measured gust wins for the current hour in both
  directions, as `MeasuredRain` does for rain. Keep to the current hour.

### Hero line

- `HomeUiState.windWarning: WindLine?` = first hour within the next **12 h** whose median gust
  ≥ `STRONG_GUST`, plus the peak median gust over the run of windy hours from there.
  - Already windy now: "Böen bis 60 km/h" / "Raffiche fino a 60 km/h" / "Gusts up to 60 km/h".
  - Later: "Böen bis 60 km/h ab 15:00" / "… dalle 15:00" / "… from 15:00".
  - Speed via `Format.wind` so the wind-unit setting applies.
- Placement: right column under the icon, stacked under "Regen ab …" when both exist (same style,
  `bodyMedium`, white 0.9). Storm level tints amber-ish like `silent_sources`? **No** — amber is
  forecast chrome in this app. Use the warning glyph's orange only for `STORM_GUST`, white otherwise.
- Test tag `wind_line`. Must fit at font scale 2 on the phone without pushing the hero taller than
  today when absent; when present it costs at most one line.
- Widget and share card: not in scope.

### Strip markers

- **48 h strip** (`HourStrip`): under the precipitation bar/number, a small wind glyph plus the
  median gust ("55") on windy hours only. The row is reserved only if *any* hour in the strip is
  windy, so a calm two days keep today's height. Storm hours in the orange, others white 0.85.
  Column width is `HourColumnBaseWidth × fontScale`; check "75" plus 12 dp glyph fits 46 dp.
- **Day list:** add `gustMaxKmh: Double?` to `ConsensusDay` in `DailyAggregator` (max over the
  **daylight** window `worstCondition` already uses, of the hourly *median* gust — same reason the
  day's rain chance uses that window). Windy days get the wind glyph beside the condition icon;
  no number (the row has no room at 44 dp). TalkBack: row description gets "Starke Böen".
- Day sheet (`DayDetail`): add "Böen bis N km/h" to its stats where windy.
- **Glyph:** Meteocons monochrome `wind.svg` via `tools/svg2vector.py` (check it is in
  `tools/meteocons/`, else add it from the 3.0 set with the licence already shipped). New
  Roborazzi golden for it; look at the PNG before committing.

### Tasks (TDD order)

1. `StrongWindTest`: median not max; fewer than 3 sources → nothing; thresholds inclusive; storm
   level; measured gust overrides current hour both ways; first windy hour and peak over its run.
2. Blender: `gustMedianKmh` on `ConsensusHour` (+ `ModelFamily` weights) with `ConsensusBlenderTest`.
3. `DailyAggregator.gustMaxKmh` over the daylight window, with test.
4. `HomeStateBuilder` → `windWarning`; `HomeStateBuilderTest` cases (none, now, later, >12 h out).
5. Strings in `values`, `values-it`, `values-en`: `wind_gusts_now`, `wind_gusts_from`,
   `day_strong_wind` (content description), `hour_gust_short`.
6. UI: hero line, strip row, day-row glyph, day sheet line. `HomeScreenTest` for `wind_line`
   present/absent (no German asserted — resolve from resources; `mainClock.autoAdvance = false`).
7. Goldens: wind glyph, a windy strip. `verifyRoborazziDebug`.
8. CLAUDE.md: a bullet on why the trigger is the median gust, not the blender's max, and the
   thresholds' source.
9. Verify: unit tests, lint, release build + `tools/release-smoke.sh` on the emulator, then on the
   phone at font scale 1 and 2. No real wind likely on the day: say so, and rely on hand-built
   states for screenshots.

## Open questions

- Is 50 km/h the right "strong" gust for a valley village? Föhn days in the Burggrafenamt
  regularly reach 60–80 km/h; 50 may fire often in spring. Could be a setting later, not now.
- Should a SIAG station that is level and close also stay unmoved (see Part A assumption)?
