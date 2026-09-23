# The neighbourhood's thermometers, side by side

**Date:** 2026-09-23
**Status:** approved

A card on the comparison screen showing what every weather station round the place is reading
right now, one column each, under the table where the models have just had their say.

## Why this is worth building

The comparison screen answers "what do the models say" thoroughly — a chart, a day table, a
per-source status list — and cannot answer the question a reader actually has after reading it:
*is any of this what it is like outside?*

The stations screen can answer it, but it is a detail screen reached deliberately, and it is
about **choosing** an instrument. The comparison screen is where somebody is already comparing
things, and what the instruments disagree about is the most useful fact this app has that the
models cannot supply. Measured on the phone on 2026-09-23 round Dorf Tirol: four private
stations within 1,3 km read 11,0°, 13,0°, 14,0° and 18,0° at the same minute, and two of them
sit at heights their owners recorded wrongly by more than 400 m. A model spread of 1,5 K looks
very different next to that.

## 1 · A shared neighbourhood

`NearbyStationsViewModel` fetches the neighbourhood today: `near`, a `current` per station, and
one Open-Meteo elevation call for the ground under all of them — about eleven Weather
Underground requests against a cap of **1500 a day**. Vergleich is a bottom-bar tab, tapped far
more often than a detail screen is opened, so a second caller doing its own fetching is how a
quota gets spent.

`data/NearbyStationsRepository`, app-scoped, holds one place's probes in memory for
`FRESH_FOR` (10 minutes) — the same shape as `RadarRepository`'s frame cache and for the same
reason. Both the card and the stations screen read it, so opening Compare and then the stations
screen costs one fetch rather than two.

`NearbyStationsViewModel` loses its fetching entirely and becomes what its name says.

**Ten minutes is the affordability, not a preference.** Eleven requests every ten minutes is
about 1 580 a day if somebody left the app open and looking at this card continuously, which is
the cap — and nobody does, because the card only asks while Compare is on screen.

## 2 · The card

Under the day table and above the statistics: after the models have had their say and before
the scoring. It scrolls horizontally like the day table and uses the same column widths, so the
two read as the same kind of object.

```
┌──────────────────────────────────┐
│ STATIONEN JETZT                  │
│           Tirolo  Tirol   Meran  │
│           0,5 km  0,7 km  1,3 km │
│ Temperatur 11,0°  13,0°   12,0°  │
│ Feuchte     58 %   52 %    62 %  │
│ Regen      0,0    0,0     0,0 mm │
│ Strahlung    —     148     51 W/m²│
│ gelesen   vor 2m  vor 7m  vor 5m │
│ ──────────────────────────────── │
│ Modelle hier              12,0°  │
└──────────────────────────────────┘
```

Rows: temperature, humidity, rain since midnight, radiation, and when the reading was taken.
Columns: every station the neighbourhood holds, **nearest first**, the one the app reads marked
and its name in a heavier weight, the province's own marked as the province's own.

**A dash where a station publishes nothing, never a zero.** ITIROL16 has no pyranometer and
ITIROL26 does, and that difference is what decides whether `StationSun` has anything to work
with. A zero there would be inventing a measurement. A station that answered **HTTP 204** gets a
column of dashes and "keine Meldung": going quiet for an hour is an ordinary hour, not a fault.

**One anchor row, and it is the models', not the app's.** The row under the rule is the
consensus at the place for the current hour, which `CompareViewModel` already computes.

It is deliberately **not** the hero. The hero has been through `StationDownscale`, `StationFog`,
`StationSun`, `StationDry` and `MeasuredRain`; putting it on a second screen would mean a second
answer about one hour, derived somewhere else, with no way for a reader to tell which is right
when they disagree. That is the rule `ShareCardStateBuilder` follows — it computes no weather —
and it applies here for the same reason. The home screen keeps the single answer.

## 3 · It is a way in, not only a read-out

Tapping the card opens the stations screen, which is where a station can be chosen. Two screens
listing the same instruments should not both be dead ends.

## 4 · Gated like the rest of the feature

No Weather Underground key, no card: there is no neighbourhood to fetch, and the province's own
station is already on the home screen. Amateur stations switched off, no card. The card never
reaches the share sheet — WU data is not licensed for redistribution, which is why the share
card refuses an amateur reading at all.

## 5 · The bug this ships with

**On the stations screen, the bottom bar's *Heute* does nothing.** Reported from the phone on
2026-09-23, live in v0.32.0.

`selectTab` already knows this failure and handles exactly one instance of it:

```kotlin
internal fun NavHostController.selectTab(route: Any) {
    if (route == CompareRoute && currentDestination?.hasRoute(StatsRoute::class) == true) {
        popBackStack(CompareRoute, inclusive = false)
    } else {
        openTopLevel(route)
    }
}
```

Its own doc says why: "The comparison tab stays selected while the statistics show, and
`openTopLevel` would save and restore that same two-entry stack, so tapping it did nothing."

The stations screen sits on Home's back stack in exactly that shape, and since v0.32.0 it can
also sit on Settings'. So the special case was never about Compare and Stats — it is about **any
detail screen pushed on top of a tab**, and naming one pair in an `if` was the mistake.

The fix is the general rule: if the tapped route is already in the back stack below the current
destination, pop back to it; otherwise navigate. That covers Compare/Stats, Home/Stations,
Settings/Stations, and whatever is pushed onto a tab next.

`TopLevelNavigationTest` drives the real routes with stub screens and already guards the
bulletin's two entrances. It gains the stations case, which fails against the current code.

## Testing

Pure, therefore JVM tests:

- `StationsNowState`: column order by distance, the marked column, the dash rules, a quiet
  column, an empty neighbourhood.
- `NearbyStationsRepository`'s cache, with a fake API counting calls: two reads inside ten
  minutes fetch once; a read after eleven minutes fetches again; a different place is a
  different key; a failed fetch is not cached as an answer.

Instrumented:

- The card renders its columns and its anchor row.
- It is absent without a key.
- Tapping it opens the stations screen.
- `TopLevelNavigationTest`: from the stations screen, tapping *Heute* reaches the home screen.

Then the phone, where the real spread between the neighbours is the thing worth looking at.

## What is deliberately not here

- **No history.** The card is "now". What each station read yesterday is the statistics screen's
  kind of question, and `station_history` holds only the chosen one.
- **No chart line per station.** A station has one point where a model has 48 hours; a crowd of
  dots on a single vertical line is not a comparison.
- **No forecast per station.** Nobody forecasts for a private station, and the models' view of
  one is already what `StationReference` fetches for the chosen station alone.
