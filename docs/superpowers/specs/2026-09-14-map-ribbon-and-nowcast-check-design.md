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

A pure function from radar tile pixels to a coarse level at one coordinate: `DRY`, `LIGHT`,
`MODERATE`, `HEAVY`.

- For every radar frame the app fetches the single zoom-7 tile containing the place (and, where
  it differs, the one containing its station). Over this province that is one tile, rarely two:
  thirteen PNGs of about 10 kB per frame list, held in memory beside the frames in
  `RadarRepository` and refreshed with them.
- It reads a 3x3 patch of pixels centred on the place's pixel. RainViewer's `smooth` option blurs
  edges, and a single pixel lands on a blurred rim too easily. The patch's level is the wettest
  pixel's, after a transparent or near-transparent pixel (alpha < 40) counts as dry.
- A pixel's level is the nearest `PrecipColors` stop by RGB distance, bucketed: first two stops
  light, next three moderate, the rest heavy. The snow palette (options `_1`) is a separate set of
  colours; any pixel matching it counts as `LIGHT` at least, because the question this answers is
  wet or dry.
- The PNG decoding is `BitmapFactory` on a device and a `javax.imageio` reader in JVM tests; the
  mapping from pixels to level takes an `IntArray` so both feed it the same way.

Tested against tiles recorded on 2026-09-14 (`app/src/test/resources/fixtures/rainviewer/`): the
04:30Z tile reads wet at Dorf Tirol and the 05:30Z one dry.

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

- If the radar reads `DRY` at the place and the step has any cell at or above 0,1 mm/h within
  5 km of the place, those cells are marked `unconfirmed = true` (a new field on `NowcastCell`).
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
scale matches the hour strip's. Past steps are solid blue from their `RadarAtPlace` level (a level
has no rate, so each draws a fixed height: light 0,3, moderate 1,5, heavy 6 mm/h equivalents).
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
selected step: `trocken`, `leichter Regen`, `Regen`, `starker Regen`, `Regen möglich` (unconfirmed,
or p90-only), and in *Heute* `bis N mm/h` beside it. The kind chip reads `Radar`, `Nowcast`,
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

- `RadarAtPlaceTest`: recorded tiles, wet and dry at the place, the snow palette, a transparent
  rim.
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
