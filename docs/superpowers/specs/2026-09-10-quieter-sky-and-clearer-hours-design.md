# A quieter sky, and an hour worth opening

Design, 2026-09-10. Three changes to the home screen: the animation switch also stops the sky's
colour crossfade, the condition icon moves up beside the hero temperature, and the hour sheet stops
being one long sentence.

They are one document because they are one screen and one afternoon's work, not because they depend
on each other. Any of the three can ship alone.

## 1. The switch turns the sky off, not half of it

`SkyBackground` takes `animationsEnabled` and today gates exactly one thing with it: the particle
frame loop. The gradient's 1.5-second crossfade between palettes runs regardless, on the grounds
recorded in the file's own comment — "it is a colour change, not motion".

That reasoning does not survive contact with the switch's name. A reader who turns *Himmel
animieren* off and then watches the sky fade from noon blue to evening amber over a second and a
half has been told one thing and shown another. The same holds for the system's reduced-motion
setting, which the file already honours for particles alone.

So both gates move up one level:

```kotlin
// One decision, read once: the reader's switch and the system's reduced-motion setting say the
// same thing, and both the particles and the crossfade obey it.
val motion = animationsEnabled && !reduceMotion
val spec = if (motion) tween<Color>(1500) else snap<Color>()
```

`motion` guards the `LaunchedEffect` that drives the particles, exactly as `animationsEnabled &&
!reduceMotion` does now, and `spec` is what the three `animateColorAsState` calls take. With motion
off the palette still changes — the sky is still the right colour for the hour, and the sunset still
happens — it simply arrives in one frame instead of ninety.

Nothing else changes. The preference key `animations` stays, the switch stays where it is, and the
three strings stay as they are, because "Himmel animieren" is now more true than it was. The home
cards keep their one-shot slide-in entrance: it plays once when the screen opens, it is not the sky,
and folding it in here would mean renaming the setting in three languages for a fade nobody
complained about.

### What this makes testable

Today `SkyBackground` never lets a Compose test rule go idle — the frame loop asks for another frame
forever, which is why `CLAUDE.md` requires `mainClock.autoAdvance = false` in every test that renders
it. With motion off there is no loop, so the composable *does* go idle, and that is the assertion:

> `SkyBackgroundTest`, new case: render with `animationsEnabled = false` and `autoAdvance` left at
> its default `true`; change the palette; `waitForIdle()` returns, and the sky is drawn in the new
> colours. The same test written against today's code hangs.

It is a narrow test and it is the whole feature: it fails if either gate is forgotten, because a
running loop hangs it and a `tween` spec leaves the old colour on screen for a frame the idle rule
will not wait through.

## 2. The condition icon belongs next to the number

The hero reads, top to bottom: place, temperature, then a row of condition icon and condition label,
then feels-like and the agreement badge. The icon is on the label's line, at 30 dp, level with a
`headlineMedium` — small, low, and a full line away from the number it describes.

Every weather app the reader also has on their phone puts the picture beside the degrees. That is
not a reason on its own, but the reason behind it is: the temperature and the sky condition are the
two halves of one answer, and a reader who takes in only the top line should get both. Splitting
them costs a glance for nothing.

```
Meran ⌄
18°  ⛅
Wolkig
gefühlt 16°  ● ±2°
```

The temperature `Text` and the condition `Icon` become one `Row` with `Alignment.CenterVertically`
and 12 dp between them; the condition label keeps its own line below, at `headlineMedium`, without
the icon. The icon grows from 30 dp to 56 dp so it holds its own beside `displayLarge`, and keeps
its `palette.accent` tint and its null content description — the label directly underneath is the
text an accessibility reader gets, and duplicating it into the icon would have the condition spoken
twice.

`hero_temp` stays on the temperature `Text`. `HomeScreenTest` and `NavigationTest` both find the hero
by that tag and neither is touched.

The widget has its own layout in its own file and is not part of this.

## 3. The hour sheet

Tapping an hour opens `HourDetail`, which is fifteen lines at the bottom of `HomeScreen.kt` and
shows:

- a heading, `14:00 · Wolkig`
- one interpolated sentence carrying temperature, band, millimetres, chance and wind
- the freezing level, where there is one
- a row per model: name on the left, three numbers run together on the right

The sentence is the problem. Five quantities in one line of prose cannot be scanned, cannot be
aligned, and cannot be extended — the gust is already in `ConsensusHour` and was left out because
there is nowhere to put it. The per-source rows have the same trouble one row down: three numbers
separated by two spaces do not form columns, so a model reading 4 K warm than the rest is invisible.

### Its own file

`HourDetail` moves to `ui/home/HourDetail.kt`, beside `DayDetail.kt`, which is the same composable
for a day and already lives on its own. `HomeScreen.kt` keeps the sheet wiring and loses the
content, which is the split `CLAUDE.md` describes for screens and which keeps the file at the size
where its navigation is readable.

### What it shows

```
⛅  14:00  Wolkig                    ● ±2°   ✕
Konsens aus 8 Modellen

Temperatur          Gefühlt
18°                 16°
15–20°

Niederschlag        Wind
0,4 mm              12 km/h
40 %                Böen 28 km/h

Nullgradgrenze
3.200 m

Modell            Temp     mm    Wind
● ICON-D2        17,8°    0,3      11
● AROME          18,4°    0,5      13
● ECMWF          20,1°    0,0       9
```

**The header** is the condition icon, the time, the condition label, the agreement badge and a
cross. The badge is `AgreementBadge` unchanged, fed the same half-width the hero's is, under the tag
`hour_agreement_badge` so the two are distinguishable in a test. Under it, the model count, through
the existing `plurals.now_from_consensus` — the hero already says "Konsens aus 8 Modellen" and the
sheet is where the reader went to find out which eight.

**The cross** is required, not decoration. Ten sources plus five stat tiles run past a phone screen
at a large font scale, so the content takes `verticalScroll`, and `CLAUDE.md` records what a
scrolling sheet without a cross does: the drag goes to the inner scroll first and the sheet bounces
instead of closing. `DayDetail` learned this already; this sheet would be the next to learn it.

**The stat grid** is two columns of labelled tiles, label small and dim above the value:
Temperatur (with the min–max band on a second line under it), Gefühlt, Niederschlag (with the chance
under it), Wind (with Böen under it). A tile whose value
is null is left out entirely rather than drawn with a dash — the reader does not need to be told the
models publish no apparent temperature. Wind is the single exception: it keeps the `–` it has today,
because `windKmh` null means *no contributing model publishes wind for this hour*, which is a fact
about the hour worth showing where the other quantities are.

**The freezing level** stays a full-width sentence under the grid rather than becoming a tile,
because its wording flips: `freezingLevelText` chooses between `freezing_level`
("Nullgradgrenze 3.200 m") and `freezing_level_low` ("Schneefallgrenze bis 1.100 m"), and a tile
labelled Nullgradgrenze would contradict the second of those. Its existing tag
`hour_freezing_level` is kept.

**The per-source table** gets a header row (Modell / Temp / mm / Wind) and real columns: fixed
widths, numbers right-aligned, and the source's own colour from `SourceColors` as a 7 dp dot before
its name — the same dot the compare chart uses for the same model, so the two screens agree.

### Strings

`hour_summary` is deleted from `values`, `values-it` and `values-en`. New keys, in all three:
`stat_temp`, `stat_feels`, `stat_precip`, `stat_wind`, `stat_gust`, `col_model`, `col_temp`,
`col_precip`, `col_wind`. `freezing_level`, `freezing_level_low` and `per_source` are reused as they
are. Every number goes through `Format` with `LocalFormats.current`,
as everything on this screen already does.

### Tests

`HomeScreenTest` opens the sheet already. It gains: the gust tile is present when
`gustKmh` is set and absent when it is null; the agreement badge is in the header; the cross
dismisses the sheet. Tags for each. No assertion names a German string — CI's emulator is en-US, and
the tags plus resource lookups are what the existing tests in that file use.

## What is deliberately not here

- No new preference. The one switch was the choice; a second key for the crossfade would be two
  switches for one thing the reader perceives as one thing.
- No thermometer glyph. A degree sign already says the number is a temperature, and Meteocons'
  thermometer would be the only icon on the screen that names a unit rather than the weather.
- No per-source bars in the hour sheet. Aligned columns already make an outlier visible; a bar per
  row is a second encoding of the same number and a chart the compare screen already draws better.
- The home cards' entrance animation stays outside the switch, as above.
