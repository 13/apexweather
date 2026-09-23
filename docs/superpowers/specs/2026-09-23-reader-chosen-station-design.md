# The reader chooses the thermometer

**Date:** 2026-09-23
**Status:** approved

Three asks that turn out to be one feature: say whether the Weather Underground key works,
name the private station on screen and let the reader pick which one the app reads, and make
"Stationen in der Nähe" a screen somebody can actually use — modern, legible, and with a way
back out of it.

They are one feature because the station screen is where a choice belongs. It is the only
place that lists the candidates, shows what each of them is reading right now, and can say
which one the app is using. A separate picker in settings would be a second list of the same
stations with less information on it.

## Why this is worth building

The catalogue picks a place's private station by measurement: `tools/generate-places.py`
checks every candidate's claimed height against SRTM, drops anything more than
`PWS_MAX_DEM_DISAGREEMENT_M` (100 m) out, costs height like distance, and ranks what survives
by eight weeks of ICON-D2 village-minus-station stability. That is a good rule and it stays
the default.

It is not the reader's rule. For Dorf Tirol it chose **ITIROL16** (0,49 km, 634 m,
stability 0,05 K) over **ITIROL26** (0,68 km, 669 m) — and ITIROL26 is Ben's own station,
publishes `solarRadiation`, which `StationSun` needs and ITIROL16 does not have at all, and
is maintained by somebody who will notice when it stops. None of that is in the catalogue's
arithmetic and none of it can be. Whoever can see the instrument out of the window knows
things the generator cannot.

And the key's silence made all of this hard to trust. Entering a key changes nothing visible:
the 30-minute staleness rule means the app does not refetch, so the screen goes on reading
the provincial station and there is no way to tell a wrong key from a key that is simply not
being used yet. That was diagnosed by hand on the phone on 2026-09-23 and cost more time than
the feature it was hiding.

## 1 · What decides which thermometer is read

A new field, stored in DataStore like every other preference:

```kotlin
@Serializable
data class ChosenStation(
    val istat: String,
    /** "wu" or "siag", matching NearbyStation.network. */
    val network: String,
    val code: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    /** The DEM's answer, never the station's claim. See section 2. */
    val altitudeM: Int,
    val distanceKm: Double,
)
```

`AppSettings.chosenStations: List<ChosenStation>`, held under one preferences key as a JSON
array. A list and not a map because DataStore holds strings and `kotlinx.serialization` makes
a list of records the plainest thing to read back; at most one record per place, and there
are 116 places.

`Place.forSettings` grows a third branch and keeps the two it has:

| stored for this place | what the place reads |
|---|---|
| nothing | the catalogue's own `pws`, exactly as today |
| `network = "siag"` | the province's own — `pws` dropped |
| `network = "wu"` | that station, built as a `NearbyStation` from the record |

The global `amateurStations` switch keeps its meaning and keeps its precedence: off means the
provincial network everywhere, and it overrides a per-place choice rather than being overridden
by one. That switch says "I do not trust instruments nobody maintains", which is a statement
about all of them. A blank key does the same, for the reason already in `forSettings`' doc:
a place holding a `pws` the app has no key to fetch would have the models asked about a point
whose thermometer is never read.

A chosen station carries **no `horizon` and no `stabilityK`**. `Horizon.usable` already returns
false for a missing profile and `SunPhaseCalculator` already falls back to the astronomical
rule, so the cost is the terrain sunrise at that station and nothing else. The card says so
rather than leaving it to be discovered.

A stored record whose place is no longer in the catalogue, or whose station the network no
longer knows, is dropped — the same rule as a stale pinned ISTAT.

## 2 · The height check

`StationDownscale`'s cap is `2 K + 9,8 K/km × |height difference|`, so the height of the chosen
station is load-bearing: a station claiming to be 450 m below where it stands buys itself four
and a half degrees of licence to move the hero temperature. Weather Underground's elevation is
whatever the owner typed into a web form, and its form is in **feet** — which is exactly how
ITIROL26 came to claim 204 m while standing at 654.

So a station may not be chosen on its own word about where it is.

Open-Meteo's elevation endpoint answers a whole list in one request:

```
https://api.open-meteo.com/v1/elevation?latitude=46.693246,46.694926&longitude=11.155237,11.154523
→ {"elevation":[639.0, 659.0]}
```

One request when the screen loads covers every station on it. Three consequences:

- **The stored altitude is the DEM's**, never the claim — which is what the generator does
  (`"altitude": int(round(dem))`).
- **The card shows both where they differ** (`182 → 631 m`), because seeing them apart is how
  a reader understands why a station is marked.
- **Past 100 m of disagreement the card is marked**, using the generator's own
  `PWS_MAX_DEM_DISAGREEMENT_M`. Marked, not forbidden: the height the app will use is the
  DEM's either way, so the mark is information about the station's owner rather than a veto.
  What it warns about is real — a station whose recorded height is out by 450 m may well be
  somewhere else entirely.

Open-Meteo serves Copernicus GLO-90 where `tools/horizons.py` used SRTM 30 m. Two models of the
same ground, metres apart, against a 100 m gate: the difference does not reach the decision.
Measured 2026-09-23 — ITIROL16 catalogue 634 m, Open-Meteo 639; ITIROL26 claim 669,
Open-Meteo 659.

**If the elevation request fails, nothing can be selected** and the screen says why. The
alternative is storing a height on the owner's word, which is the thing this section exists to
prevent. The stations still list and still show their readings; only the `wählen` action is
unavailable.

`NearbyStationsStateBuilder.MAX_PLAUSIBLE_SLOPE_M_PER_KM` is **deleted**. It exists because the
app had no DEM and could only catch a claim that was impossible rather than merely untrue — its
own doc says "Only the generator, which has SRTM tiles, can". The app can ask now, so the
heuristic is replaced by the measurement, and the case its doc admits it could not catch
(ITIROL24, claiming 128 m at 1,76 km, wrong by 291 m) is caught.

## 3 · The key's verdict

A line under the key field in settings, in six states:

| state | when | what it says |
|---|---|---|
| unchecked | no key, or one never checked | nothing shown |
| checking | a check is in flight | "wird geprüft …" |
| good | the last check or fetch succeeded | "gültig · zuletzt geprüft 08:47" |
| refused | 401/403 | "abgelehnt — Schlüssel prüfen" |
| over quota | 429 | "Tageskontingent erschöpft" |
| no connection | transport failure | "keine Verbindung" |

Three of those are failures rather than one, because *refused*, *over quota* and *no
connection* need three different actions from the reader and a single "funktioniert nicht"
would name none of them.

Two things set it. A **check when the key changes**, debounced, one `v3/location/near` call at
the place's coordinates — it proves the key end to end and is the same call the stations screen
makes, so nothing is spent that was not going to be. And **every ordinary amateur fetch**
afterwards writes the same field, so a key revoked next month, or a quota exhausted at four in
the afternoon, stops claiming to be good without anybody re-checking by hand.

Stored in DataStore beside the key, so it survives a restart and the reader is not told "unchecked"
about a key that has been working for a week.

`WuKeySource` stays what it is — a `fun interface` returning the key — and the verdict is
written through a separate small interface so that `WeatherRepository` does not acquire a
settings dependency it has spent its life not having.

## 4 · The screen

One card per station, the chosen one outlined:

```
┌───────────────────────────────┐
│ ← Stationen in der Nähe       │
│   Dorf Tirol                  │
├───────────────────────────────┤
│ ╔═══════════════════════════╗ │
│ ║ Tirolo - Tirol   ● benutzt║ │
│ ║ 0,5 km · 634 m            ║ │
│ ║ 11,0°  58 %  0,0 mm       ║ │
│ ║ — W/m²        vor 2 min   ║ │
│ ╚═══════════════════════════╝ │
│ ┌───────────────────────────┐ │
│ │ Tirol           [ wählen ]│ │
│ │ 0,7 km · 669 m            │ │
│ │ 13,0°  52 %  0,0 mm       │ │
│ │ 147,7 W/m²    vor 7 min   │ │
│ └───────────────────────────┘ │
│ ┌───────────────────────────┐ │
│ │ Tirol        ⚠ Höhe       │ │
│ │ 0,4 km · 182 → 631 m      │ │
│ │ 18,0°  39 %   vor 1 min   │ │
│ └───────────────────────────┘ │
└───────────────────────────────┘
```

Changes from what is there now:

- **A top bar with a back arrow**, and `statusBarsPadding()`. The screen currently draws under
  the status bar and offers no way out but the system gesture. It follows `PlacePickerContent`,
  which is the same shape of screen and already does this.
- **A card per station** rather than a run of text blocks separated by nothing.
- **A `wählen` action on every card but the chosen one**, which is outlined and marked
  `● benutzt`.
- **The provincial station under its own heading**, and selectable like the rest — choosing it
  is how a reader says "read the province's own here" for one place without switching amateur
  stations off everywhere.
- **Quantities laid out rather than joined with spaces**: temperature, humidity, rain, radiation,
  and the reading's age. A dash where a station publishes nothing, never a zero — ITIROL16 has
  no pyranometer and printing 0 W/m² for an instrument that does not exist would be inventing a
  measurement. That rule is already in the code and is kept verbatim.
- **Sorted by distance, the chosen card not hoisted.** Where a station stands is the point of
  the list, and a card that jumps to the top when picked loses the reader the geography they
  came for.
- The station's **code** (`ITIROL26`) is shown small under the name. Two of the stations round
  Dorf Tirol are both called "Tirol" and the name alone cannot tell them apart.

## 5 · What the reader cannot break

- A chosen station that goes quiet or reads absurdly falls back to the provincial one through
  the existing path: `PWS_MAX_AGE` and `StationFault.usable` in `WeatherRepository`, per hour,
  silently. A reader's choice is a preference about which instrument to prefer, not an
  instruction to believe a broken one.
- Changing station starts that hour's `station_history` row again. `belongsTo` already does
  this and it is why the `station` column exists: a row whose models were written about one
  point and whose reading came from another describes two places.
- The share card still refuses to redistribute an amateur reading, whichever one is chosen. WU
  data is not licensed for redistribution and that does not change because the reader picked the
  station themselves.
- The widget builds from the same `Place.forSettings`, so it follows the choice without knowing
  about it.

## 6 · Testing

Pure, therefore JVM tests:

- `ChosenStation` resolution in `Place.forSettings`: each of the three branches, the
  `amateurStations = false` override, the blank-key override, and a record naming a station the
  catalogue cannot resolve.
- The height gate: claim within 100 m, claim outside it, claim absent, DEM absent.
- The key verdict: 401, 403, 429, a transport failure, and success, each mapping to its own
  state.
- `NearbyStationsStateBuilder` with the DEM heights merged in, including the card that should
  be marked.

Instrumented, because they are the screen:

- The back arrow pops.
- `wählen` on a card reports that station and no other.
- The chosen card is outlined and carries no `wählen`.
- With the elevation request failed, no card offers `wählen` and the reason is on screen.

And then the phone, as every time: enter a key, watch the verdict; choose ITIROL26; confirm the
hero names it; confirm the province's own can be chosen back.

## What is deliberately not here

- **No AWEKAS.** One amateur network is the feature; a second is a second spec.
- **No horizon for a chosen station.** Computing a skyline needs DEM tiles and tens of thousands
  of samples — `tools/horizons.py` exists because an elevation API is the wrong tool for it, and
  that has not changed. The flat-horizon fallback is already correct and already tested.
- **No stability score for a chosen station.** It is eight weeks of ICON-D2 against the station,
  which is a generator job. The reader choosing a station is choosing to override that number,
  not to recompute it.
- **No re-ranking of the catalogue.** `generate-places.py` keeps its rule. If ITIROL26 should
  win on its merits — it publishes radiation and ITIROL16 does not — that is a change to the
  generator's scoring, a separate piece of work, and this feature is what makes it unnecessary
  to hurry.
