# Icons, rain bars, the station card, and a radar tab

Design, 2026-09-10. Four changes, in the order they should be built. Only the last is large.

---

## 1. A professional icon set

### The problem

The fifteen weather glyphs in `res/drawable/ic_wx_*.xml` are hand-drawn. They are correct — every
condition has its own drawing and `WeatherIconsTest` asserts it — but they are the work of an
afternoon, and they are the most-looked-at thing in the app: the hero, forty-eight hours of columns,
fourteen days of rows, and the widget.

### What was rejected before, and why that changed

**Meteocons 2.0 was rejected on 2026-09-10** because it had no heavy-rain and no heavy-snow icon,
and adopting it would have collapsed a distinction this app makes deliberately — rain in three
weights, snow in two. That finding was correct for 2.0. It is wrong for 3.0.

Meteocons **3.0** (`@meteocons/svg-static`, MIT, Bas Milius) ships 519 icons in four styles and adds
the `extreme-*` tier. Every one of the app's twelve conditions maps one-to-one, day and night:

| Condition | Day | Night |
|-----------|-----|-------|
| CLEAR | `clear-day` | `clear-night` |
| MOSTLY_CLEAR | `partly-cloudy-day` | `partly-cloudy-night` |
| PARTLY_CLOUDY | `overcast-day` | `overcast-night` |
| CLOUDY | `cloudy` | `cloudy` |
| FOG | `fog-day` | `fog-night` |
| DRIZZLE | `drizzle` | `drizzle` |
| RAIN | `rain` | `rain` |
| HEAVY_RAIN | `extreme-rain` | `extreme-rain` |
| SLEET | `sleet` | `sleet` |
| SNOW | `snow` | `snow` |
| HEAVY_SNOW | `extreme-snow` | `extreme-snow` |
| THUNDERSTORM | `thunderstorms-day` | `thunderstorms-night` |

FOG and THUNDERSTORM gain a night form they did not have. Seventeen drawables in total.

### The monochrome style, not the colour one

Meteocons offers `fill`, `flat`, `line` and `monochrome`. **`monochrome`** is taken. Three reasons,
and the third is the one that decides it:

- The app tints every weather icon white today. Monochrome keeps the screen, the widget and the
  Compare chart working with no colour decisions to re-take against a sky palette that moves from
  dawn to night.
- At 20 dp — the size of a column in the 48 h strip — colour does not survive anyway, and the
  heavy-versus-normal distinction is carried by the number of drops, not by hue.
- **The colour styles cannot be converted.** They carry four to six `linearGradient`s each, and
  gradients inside a `mask` have no Android VectorDrawable equivalent. Monochrome has no gradients
  at all: one `currentColor` fill, plus `path`, `rect`, `circle`, `clipPath`, `mask` and `g`.

### Converting SVG to VectorDrawable

`tools/svg2vector.py`, run by hand and its output committed, on the same terms as
`tools/generate-places.py` and the recorded fixtures. It handles exactly what these seventeen files
use:

- `path` → `<path android:pathData>`; `rect` and `circle` → the equivalent path
- `g transform` → `<group>` with scale/translate/rotate
- `stroke` → `android:strokeColor`, `android:strokeWidth`, `android:strokeLineCap`
- `currentColor` → `#FF000000`, tinted at the use site as today
- `clipPath` → `<clip-path>`
- **`mask` → `<clip-path android:fillType="evenOdd">`.** Every mask in these files has the same
  shape: a full-canvas rect with the occluding cloud subtracted from it under `fill-rule="evenodd"`.
  That is an exact `clip-path`, not an approximation. `android:fillType` on `<clip-path>` needs
  API 24; `minSdk` is 31.

Android's clip paths antialias worse than fills. If the edges look ragged at 20 dp **on the phone**
— renders will not show this, the same way renders did not show the launcher ray hitting the ridge —
the fallback is to flatten each mask into a single path with a boolean difference (`skia-pathops` in
a throwaway virtualenv; it is not installed on this machine) and emit a plain `<path>` instead. Do
not do this pre-emptively.

### Scope

- `tools/svg2vector.py`, and `tools/meteocons/` holding the seventeen source SVGs, so the conversion
  is reproducible without a network fetch
- the seventeen `res/drawable/ic_wx_*.xml`, replaced. **Resource names do not change**, so
  `WeatherIcons.kt` changes only where FOG and THUNDERSTORM gain a night branch
- `LICENSE-meteocons` at the repo root (MIT requires the notice to ship), and Meteocons named in
  `AttributionFooter`
- `app/src/test/screenshots/` re-recorded with `:app:recordRoborazziDebug`, and **the PNGs looked at
  before committing**, which is the whole point of those goldens
- `ic_notification.xml` is untouched — it is the A-sharp silhouette and stays that way

---

## 2. Rain bars that can be read

### The problem, seen on the phone

At 14:45 on 2026-09-10 the 48 h strip showed `64% 100% 100% 91% 85%` under five bars that were all
the same two-pixel sliver. The bar's height is millimetres on a fixed 0–5 mm scale, so an hour that
is certain to rain 0,4 mm draws 1.8 dp of bar. The reader sees a number saying *certain* above a bar
saying *nothing*, and nothing on the card says which of the two the bar is.

`PrecipBar` in `ui/home/HomeSections.kt`:

```kotlin
val heightFraction = (mm / 5.0).coerceIn(0.0, 1.0).toFloat()
```

### What it becomes

**Height is probability. Colour is intensity. The number underneath is millimetres.** Bar and
caption then say two different things, and neither is redundant.

- **A visible track.** 18 × 26 dp, `Color.White.copy(alpha = 0.12f)`, 4 dp corners. The track is
  what makes a small bar legible as *small* rather than as *absent*, and it gives every hour the
  same frame to be compared in.
- **Fill height = `precipProb / 100`**, from the bottom, with a 2 dp floor once the probability is
  at least 5 % so a real chance is never invisible.
- **Fill colour = intensity**, from the hour's own millimetres, because that is what the height no
  longer carries:

  | mm in the hour | Colour | Reads as |
  |----|----|----|
  | < 0,5 | `#7FB2FF` | drizzle |
  | < 2,5 | `#4A90E8` | rain |
  | ≥ 2,5 | `#3457C8` | heavy |

  When the hour's condition is `SNOW`, `HEAVY_SNOW` or `SLEET` the fill is `#DDEBFF` instead, so
  frozen precipitation is never painted as rain.
- **Caption = millimetres**, through `Format.mm`, printed only at 0,1 mm and above. A dry hour gets
  an empty track and no number, which is the honest drawing of nothing expected.
- **A legend on the card**, one line under "NÄCHSTE 48 STUNDEN": bar = probability, number = mm.
  Three new string keys in `values`, `values-it`, `values-en`.

`PrecipBar` gains the hour's `Condition` as a parameter; the day sheet's strip gets the same
treatment for free, because both go through `HourStrip`.

Accessibility: `hour_column_desc` already speaks the probability. It gains the millimetres, so the
spoken column says the same two facts the drawn one does.

### Not done

The mountain silhouette of `SkyBackground` shows through the glass card and sits behind the bars.
Darkening the card would fix the contrast and would also change every other card in the app. Left
alone; the track's own contrast is what this change buys.

---

## 3. The measured-station card goes to the bottom

`StationSection` sits third on the home screen, between the 48 h strip and the day list. It is a
card of six observed rows — a reference, not a headline — and it currently interrupts the forecast.

It moves to last, after the bulletin teaser and immediately above `AttributionFooter`, with its
entrance animation re-timed to match its new position.

The one thing this costs: the hero's line *"Jetzt: Station Meran, 14:30 · auf Dorf Tirol umgerechnet
(-2,5°)"* is explained by that card, and the explanation is now a scroll away. The hero line says
enough on its own, so this is accepted rather than mitigated.

`HomeScreenTest` gains an assertion on the order, because a section list is exactly the kind of thing
that gets reshuffled by accident.

---

## 4. A map tab with rain radar

### What it is for

The app can already say *when* rain starts, to the quarter hour, from the regional models. What it
cannot say is **where the rain is and which way it is going** — the question you ask by looking out
of the window at a dark ridge. That is a map question, and it is the only one of these four changes
that needs a new screen.

### The radar

**RainViewer's public Weather Maps API.** No key, no registration, no account.

- `https://api.rainviewer.com/public/weather-maps.json` returns `host`, and `radar.past` — **13
  frames covering the last two hours at 10-minute steps**, each with a `time` and a `path`.
- Tiles: `{host}{path}/{size}/{z}/{x}/{y}/{color}/{smooth}_{snow}.png`. The app uses size 256,
  colour scheme 4, options `1_1` (smoothed, snow coloured separately).
- Rate limits come back in the response headers: 500 per minute, burst 300. One reader panning a map
  is nowhere near that.

**The hard limit, found by testing rather than by reading:** radar tiles exist only to **zoom 7**.
Zoom 8 returns a 1370-byte PNG that says "Zoom Level Not Supported" in white letters. Verified at
z5, z6, z7 (real data, 65 colours) and z8 (the error tile) over Dorf Tirol on 2026-09-10. So the
radar tile source declares `maxZoom = 7` and osmdroid upscales beyond it; the map's own zoom is
clamped to 6…11, past which the upscaled radar is mush pretending to be detail.

`radar.nowcast` came back **empty** on the day this was written. Future frames are therefore not
designed for: the timeline is the past two hours, and the app never promises a forecast it did not
receive.

### The basemap

**osmdroid with the standard OpenStreetMap tile layer**, dark-filtered. It is the library Apex Maps
already uses, it needs no API key, and the OSM Tile Usage Policy permits live app use on three
conditions the design meets:

- a unique `User-Agent` naming the app — `Configuration.getInstance().userAgentValue` is set to the
  application id, never left at okhttp's default, which the policy says is blocked
- honouring cache headers, which osmdroid's tile cache does
- **no pre-emptive fetching.** No download-for-offline button, no seeding the province. Offline
  means the map shows what the cache happens to hold and says so.

Attribution is not optional and is not behind a toggle: a permanent line reading
**"© OpenStreetMap contributors · Weather data by RainViewer"**. RainViewer's free terms require
their half of it too.

The OSM raster style is bright and fights a night-sky app, so the tiles overlay carries a colour
matrix that darkens and desaturates them. The radar sits above, at full opacity, because a radar you
have to squint at is worse than no radar.

### The screen

A fourth bottom-bar destination, **second in the bar**: Heute · Karte · Vergleich · Bericht. It is a
tab, so it is opened through `openTopLevel` like the other three — the one-way-in rule in
`ui/navigation/AppNavigation.kt` applies.

Split the way every screen in this app is split:

- `MapScreen` wires the ViewModel
- `MapContent(state, callbacks)` draws an `AndroidView { MapView }` with the Compose controls above
  it, so the timeline matches the app's glass style rather than osmdroid's
- the chosen place gets a marker, and the map opens centred on it — the place comes from
  `WeatherStateHolder`, like everything else

The timeline is a play/pause button, a scrubber across the thirteen frames, and the frame's own time
through `Format.time`. It loops, and it holds a beat on the newest frame so the eye can find where
the animation restarts.

### Data flow

- `data/remote/RainViewerClient` fetches and parses the JSON. One mapper, tested on the JVM against
  a fixture recorded on 2026-09-10, exactly like every other upstream in `data/remote/`.
- `data/RadarRepository` keeps the frame list in memory and re-fetches when it is older than ten
  minutes, which is the rate the upstream publishes at. Radar frames are not written to Room: they
  are two hours of imagery, worthless by tomorrow, and Room in this app holds things worth showing
  while offline.
- `ui/map/MapViewModel` exposes frames, the selected index, whether it is playing, and the place.

Failure is a first-class state, as it is everywhere else here: no frame list means the map still
draws, with the basemap and the marker, and a line saying the radar could not be reached.

### Considered and not taken: the GeoSphere nowcast

`https://dataset.api.hub.geosphere.at/v1/grid/forecast/nowcast-v1-15min-1km` is INCA: **1 km, 15
minutes, three hours ahead**, with `rr` and a precipitation type, over a bounding box of
45.50–49.48 N and 8.10–17.74 E — all of South Tyrol, comfortably. It is a *forecast*, which the
radar is not, and it comes from GeoSphere, which this app already calls for AROME.

It is not in this design because of what it costs to fetch. For a South Tyrol bounding box it
returns **4.6 MB of GeoJSON**, the server sends no gzip, and trimming the timesteps barely helps
because the coordinates dominate the payload. The same request as NetCDF is **198 kB** — 23 times
smaller — but there is no lightweight NetCDF reader for Android, so that is a few hundred lines of
binary parsing.

That is the right next step for this tab and the wrong first one. Radar ships first; this gets its
own design when the tab exists and has proved worth deepening.

### Testing

- **JVM:** the RainViewer mapper against the recorded fixture — frames parsed, ordered oldest to
  newest, tile URL assembled exactly, and a malformed payload yielding no frames rather than an
  exception.
- **JVM:** `RadarRepository` re-fetches after ten minutes and not before.
- **Device:** `MapContent` driven with a hand-built state — the timeline renders thirteen steps, play
  advances the selection, the attribution line is present. The osmdroid view itself is not asserted
  on; a tile-rendering assertion on an emulator tests the network, not the app.
- **Device:** the tab opens through `TopLevelNavigationTest`'s existing shape, and home can bring
  itself back afterwards.
- No device test may assert a German string. CI's emulators are en-US.

### Risks

**RainViewer can withdraw a radar.** Their terms say so plainly: they do not contract with the radar
owners, and an owner can have their data pulled. The failure state above is what that looks like.

**osmdroid is a View library in a Compose app.** One `AndroidView`, and its lifecycle — `onResume`,
`onPause`, `onDetach` — has to be driven from a `DisposableEffect` or the map leaks. This is the
single most likely place for this feature to go wrong.

**~1 MB of APK** for osmdroid, on an app that is currently small.

---

## Order

1. Rain bars and the station card. Both are `ui/home/`, both are small, and both are visible
   immediately on the phone.
2. The icon set. Self-contained, and the goldens make it verifiable.
3. The map tab. The only part that adds a dependency, a screen and an upstream.
