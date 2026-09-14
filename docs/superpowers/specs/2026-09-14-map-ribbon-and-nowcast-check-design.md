# A radar that can overrule its forecast, and a timeline that shows your rain

Design, 2026-09-14. Two changes to the map tab. The first is a bug fix: the map drew rain over Dorf
Tirol at 08:00 that the radar had already stopped seeing, while the forecast on the home screen
said 0,0 mm and the ground stayed dry. The second replaces the timeline card with a ribbon that
shows the rain at the reader's place, step by step, before they press play.

The fix ships first and can ship alone. The ribbon needs the fix's radar reading, so it cannot
ship alone.

## What happened on the morning of 2026-09-14

Measured live, all times local (CEST) unless marked Z:

- RainViewer saw a small echo over Dorf Tirol at 06:30 and 06:40. From 06:50 on every frame was dry
  there.
- Meran, Naturns and St. Martin in Passeier logged 0,0 mm since midnight at 07:30. Meran read
  99 % humidity and 27 W/m². The reader, who lives in Dorf Tirol, confirmed it stayed dry.
- GeoSphere INCA's 05:00Z run, still carrying the echo, put 0,7 to 1,0 mm/h over the village at
  07:30 and 0,3 mm/h at 08:00, dry from 08:15.
- INCA's 05:15Z run had dropped it (at most 0,08 mm/h near the place, under the map's 0,1 mm/h
  floor). It was on offer at about 07:49, so GeoSphere's publication lag was about 35 minutes.
- All eight Open-Meteo models checked said 0,0 mm hourly and quarter-hourly from 07:00 to 11:00.

So the home screen was right, the map was wrong, and the map was wrong in a way it could have seen
for itself: its own newest radar frame was dry where the forecast frame straight after it was wet.
`NowcastRepository.FRESH_FOR` (a flat ten minutes) held the stale run; nothing compared the two.

**The home screen does not start reading INCA.** It was the obvious fix before the ground truth
came in, and the ground truth refused it.

Also recorded, not changed: "08:00" means different things on the two screens. On the map it is
the rate at that instant; in the hour strip it is Open-Meteo's sum over the hour ending then.

## 1. The radar's word at the place: `RadarAtPlace`

A pure function from radar tile pixels to reflectivity, and from reflectivity to a rate, at one
coordinate.

**RainViewer publishes what its colours mean**, which the app had recorded as not so.
`https://www.rainviewer.com/files/rainviewer_api_colors_table.csv` gives every scheme's RGBA for
every whole dBZ from -32 to 75, in two blocks: rain, then snow. The tiles the app receives match
the **Universal Blue** column exactly, although the URL asks for scheme 4 — the pixel over Dorf
Tirol at 04:30Z is `#9e93756e`, Universal Blue at 6 dBZ, to the byte — and `PrecipColors`' eight
stops are that column's colours at 15, 18, 20, 23, 29, 45, 50 and 54 dBZ. The table is committed as
a fixture, and a test pins that a live tile's pixels are all in it, so a scheme change upstream
fails a test instead of silently mis-reading rain.

- For every radar frame the app fetches the single zoom-7 tile containing the place (and, where
  it differs, the one containing its station). Over this province that is one tile, rarely two:
  thirteen PNGs of about 10 kB per frame list, held in memory beside the frames in
  `RadarRepository` and refreshed with them.
- It reads a 3x3 patch of pixels centred on the place's pixel, because RainViewer's `smooth` option
  blurs edges and one pixel lands on a rim too easily. The patch's value is its highest dBZ.
- A pixel's dBZ is an exact lookup of its RGBA in the Universal Blue rain and snow blocks. The
  smoothed tiles blend neighbouring colours, so a pixel with no exact entry takes the nearest entry
  by RGBA distance, and one further than a small tolerance counts as unreadable and is skipped.
  Snow is flagged as well as measured.
- dBZ becomes a rate with Marshall–Palmer, `Z = 200·R^1.6`: 15 dBZ ≈ 0,3 mm/h, 20 ≈ 0,65,
  30 ≈ 2,7, 45 ≈ 24. It is the stratiform relation and an approximation, not a measurement, and it
  is said so wherever a number is derived from it.
- **Below 15 dBZ is not rain.** Universal Blue draws 0–14 dBZ as a translucent beige, under
  0,3 mm/h. This morning's echo was 6 dBZ. `RadarAtPlace` reports it as `DRY` for the purposes of
  §3, which is the reading the gauges agreed with.
- Decoding is `BitmapFactory` on a device and `javax.imageio` in JVM tests; the lookup takes an
  `IntArray` so both feed it the same way.

Tested against tiles recorded on 2026-09-14: the 04:30Z tile reads 6 dBZ (dry) at Dorf Tirol, the
05:30Z one transparent, and every pixel of every recorded tile resolves within tolerance.

## 1b. One scale for both layers: `PrecipColors` re-anchored

Today the forecast paints the lightest blue at 0,1 mm/h and orange at 8 mm/h, where the radar
paints them at about 0,3 and 24. The same colour therefore means three times more rain on the
radar than on the forecast, and INCA's drizzle arrives looking like the radar's rain.

The stops move to the rates their colours stand for on the radar, from the table and
Marshall–Palmer: 15 dBZ ≈ 0,3, 18 ≈ 0,5, 20 ≈ 0,65, 23 ≈ 1,0, 29 ≈ 2,4, 45 ≈ 24, 50 ≈ 49,
54 ≈ 87 mm/h. Forecast cells under 0,3 mm/h are drawn in the radar's beige at its alpha (as
"possible", not as rain) rather than as the first blue; under 0,1 mm/h they are not drawn, as now.
The rain words in §4 use the same boundaries, so the ribbon, the map and the legend agree.

The comments in `PrecipColors`, `RainViewerMapper.COLOR_SCHEME` and CLAUDE.md that say RainViewer
does not publish its scale, and that the tiles are scheme 4, are corrected. The legend stays
labelled in words: the numbers now exist, but Marshall–Palmer is an approximation and "leicht" does
not claim a precision "0,3 mm/h" would.

## 2. Fix A: ask for INCA when a newer run is due

`NowcastRepository` stops using a flat ten minutes. It remembers the last run's reference time and
a publication lag: seeded at 35 minutes, and replaced by `fetchedAt − issuedAt` whenever a fetch
returns a run newer than the one held and that difference is smaller. Only a smaller value is
taken, because a fetch can land long after the run appeared but never before it. The next run is
due at `issuedAt + 15 min + lag`. The repository
asks again once that instant has passed, and never more often than every three minutes, so a run
that is late costs at most a request every three minutes rather than one per resume.

Switching place still asks immediately. A failed fetch still keeps what was held.

Tested with a fake clock: holding a 05:00Z run with 35 minutes of lag, a call at 05:45Z returns the
held run without a request, and a call at 05:51Z makes one.

## 3. Fix B: the newest radar frame overrules the first hour of forecast near the place

In `MapUiState.timeline`, after merging, each forecast step within 60 minutes of the newest radar
frame is checked against that frame's `RadarAtPlace` level:

- If the radar reads `DRY` at the place (under 15 dBZ, see §1) and the step has any cell drawn at
  all (0,1 mm/h or more) within 5 km of the place, those cells are marked `unconfirmed = true` (a
  new field on `NowcastCell`).
- Past 60 minutes nothing is marked. INCA may be right about rain arriving from outside the
  patch, and a single radar frame cannot speak for an hour it has not seen.
- Cells further than 5 km are never marked. The patch is where the pixels were read, and where the
  reader is looking.

`NowcastOverlay` draws an unconfirmed cell the way it already draws a "possible" (p90-only) cell:
at `POSSIBLE_ALPHA_NUMERATOR` of the usual alpha. The ribbon draws those steps as dashed outlines,
the chip reads "unsicher", and one line under the header reads "Radar sieht hier seit HH:MM nichts"
(the time of the first dry frame in the run of dry frames up to now).

Tested with this morning as a fixture: radar frames 04:30Z to 05:40Z, INCA's 05:00Z run. Steps
05:45Z and 06:00Z come out unconfirmed at Dorf Tirol; the test must fail against today's
`timeline`.

## 4. The ribbon

`RainRibbon` replaces the `Slider` in `Timeline`.

**Bars.** One bar per step, height by `PrecipScale.fillFraction` on the rate so the square-root
scale matches the hour strip's. Past steps are solid blue from their `RadarAtPlace` rate (§1), and a
sub-rain echo under 15 dBZ draws as a dry stub.
Forecast steps are amber hatched from the rate at the place's nearest grid cell. Unconfirmed steps
are dashed outlines. A dry step is a 2 dp stub, so time still has a place on the ribbon. The
selected bar is outlined in white; a white line marks "jetzt" (the newest radar frame).

**Two zooms.** Chips "Jetzt | Heute":

- *Jetzt*: two hours back to three ahead, 15-minute steps. Radar for the past, INCA ahead, AROME
  hours only where INCA stops short of three hours.
- *Heute*: now to 24 h ahead, hourly, AROME ensemble median, with a thin amber cap at the p90 rate
  wherever p90 exceeds the median. No radar.

Switching keeps the selected instant if the other zoom contains it, otherwise lands on "jetzt".
The zoom is held in `MapUiState` and not persisted. Play loops inside the current zoom.

**Header.** Play/pause, the time via `Format.dayTime` in large type, how far it is from "jetzt"
("in 35 min", "vor 20 min", "in 9 h"), and under it "<place> · <word>". The word comes from the
selected step, on §1b's boundaries: `trocken` under 0,3 mm/h, `leichter Regen` to 2,4, `Regen` to
24, `starker Regen` above; `Regen möglich` for an unconfirmed step, a p90-only step, or a forecast
between 0,1 and 0,3 mm/h; and in *Heute* `bis N mm/h` beside it. The kind chip reads `Radar`, `Nowcast`,
`Ensemble` or `unsicher`.

**Interaction.** Drag or tap on the ribbon selects a step; releasing within one step of "jetzt"
snaps to it. A `HapticFeedbackType.SegmentTick` on each whole hour while dragging, none while
playing. Semantics expose it as a slider: `progressBarRangeInfo`, `setProgress`, and a state
description of time and word, so TalkBack reads "08:15, leichter Regen".

**Colour.** Amber (`#FFC861`) is for forecast chrome only: chip, hatching, cap, dashed track. It
never appears in the rain itself, which keeps `PrecipColors` as the one ramp.

**The rest of the card.** The legend ramp moves under the ribbon at half its height, labelled
leicht / mäßig / stark. The attribution becomes one dimmed line. The recenter button stays above
the card. Bar pitch and labels are a base width times the font scale, as the hour strip's
columns are, and are checked on the phone at 2x.

## 5. Animation

**Crossfade.** When the frame changes, the outgoing overlay stays and the incoming one fades from 0
to its alpha over 250 ms, then the outgoing one is removed. The radar's alpha already lives in a
`ColorMatrix` because `TilesOverlay` has none, so the fade animates that matrix; the forecast
overlay takes an alpha multiplier. Scrubbing fast cancels a running fade and snaps to the latest
frame rather than queueing fades.

**Preload.** Entering a zoom requests, for every radar frame in it, the tiles covering the visible
box plus their zoom-7 parents (`fetchRadarParents`), through one provider per frame kept for the
life of the zoom. Play stays disabled, with a small progress ring on the button, until the next
three frames' visible tiles are in cache. That is what removes the blank flashes between frames.
Thirteen frames of about six 256 px tiles is around 20 MB of bitmaps at worst, so osmdroid's tile
cache capacity is raised to cover one zoom's worth and no more.

**Timing.** 350 ms per step in *Jetzt*, 450 ms in *Heute*; 1,2 s held on "jetzt" and on the last
step. Opening the tab never starts playback.

**Softer forecast.** `NowcastOverlay` paints its cells into an offscreen bitmap at one pixel per grid
cell and draws it scaled with bilinear filtering. Cells stay visibly coarser than radar; the square
edges that read as pixel blocks go.

**Motion off.** `AppSettings.animations` off, or the system's reduced motion on: no crossfade,
frames switch in one draw. Play still works, because the reader asked for it.

**Not added:** tap-the-map to play or pause. It competes with panning and gives a stray tap
something to do.

## 6. Strings

New keys in `values`, `values-it`, `values-en`: the zoom chips (2), the kind chips `Nowcast`,
`Ensemble`, `unsicher` (3, `Radar` exists), the relative times "in %s" and "vor %s" (2), the rain
words (5), "up to %s mm/h" (1), the radar-overrule line (1). The existing `map_kind_forecast` and
`map_kind_outlook` are replaced, not kept beside.

## 7. Testing

- `RadarAtPlaceTest`: recorded tiles, the 6 dBZ echo reading dry, the snow block, a transparent
  rim, and every pixel of every recorded tile resolving within tolerance against the committed
  colour table.
- `PrecipColorsTest`: each stop's colour is the Universal Blue colour at its dBZ, and its rate is
  Marshall–Palmer's for that dBZ.

The fixtures recorded this morning — thirteen z7 tiles (67/45, 04:30Z to 06:30Z), INCA's 05:00Z
run and the ensemble over the place box, RainViewer's frame list and the colour table — are
committed with this spec under `app/src/test/resources/fixtures/map-2026-09-14/`, because the
tiles leave RainViewer's two-hour window by mid-morning and cannot be re-recorded.
- `NowcastRepositoryTest` (new): next-run timing with a fake clock; place switch fetches at once.
- `MapTimelineTest`: this morning's unconfirmed steps; nothing marked past 60 minutes or beyond
  5 km; nothing marked when the radar is wet.
- `RibbonModelTest` (new, pure): bars per zoom, rain words, zoom switch keeps the instant.
- `MapViewModelTest`: play loops inside the zoom; play waits for preload. Keeps
  `Dispatchers.setMain` and stops the loop in a `finally`.
- Roborazzi: `RainRibbon` in the three states of the brainstorm mockup (forecast step, unconfirmed,
  *Heute*). Look at each PNG before committing.
- `MapContentTest` (device): zoom chips switch, the "unsicher" chip appears for an unconfirmed
  step. Strings resolved from resources, since CI's emulator is en-US.
- `./gradlew :app:lintDebug`, `assembleRelease`, `tools/release-smoke.sh`.
- On the phone: a full loop in both zooms with no blank frames; the card at font scale 2,0.

Not verified by any of this: how often fix B is right on real mornings. One fixture proves the
rule, not its hit rate.

## Out of scope

The province's Macaion radar (still no published bounds), anything offline, and the home screen.
