# Every nearby station, side by side — design

2026-09-23

## What this is

The catalogue now says which thermometer each place reads, and the reasoning behind that choice —
a DEM check, eight weeks of ICON-D2 stability, a height-weighted cost — lives in a Python file
nobody can see from a phone. When the hero says 14° and the window says otherwise, there is no way
to ask *what do the other instruments around here say*.

This is a screen that answers it: every Weather Underground station near the place, with its live
reading beside the provincial one, and enough metadata to see why one was chosen.

## What it is not

**Not a chooser.** The reader cannot pick a different station. The selection is made offline against
a DEM and eight weeks of model output, reviewed by a human, and committed; a tap that overrode it
would be a preference beating a measurement, and the app would have no way to tell the reader their
choice had made the hero worse. If a station here is plainly better than the chosen one, that is a
bug report about `generate-places.py`, not a setting.

**Not on a refresh path.** Every station costs a request against a 1500/day cap. The screen fetches
when it is opened and not otherwise — no polling, no prefetch, nothing on the home screen's behalf.

## Where it lives

A destination reached from the station card on the home screen, which is the place the question
arises: that card already names the station and says when it was read. `SettingsRoute` +
`SettingsScreen` + `SettingsContent` is the shape every screen here uses, and this follows it —
`NearbyStationsRoute`/`Screen`/`Content(state, callbacks)`, with the content driven by a hand-built
state in tests.

## What a row says

Per station, in one block:

```
ITIROL16   0,49 km   634 m   ● gewählt
  14,2°   44 %   0,0 mm   — W/m²   vor 3 min
```

- **The code, distance and altitude**, because that is what the choice was made on. The altitude is
  the **DEM's**, not the station's claim — the catalogue stores the DEM's answer for exactly the
  reason this screen should show it.
- **A marker on the chosen one**, and on the provincial one, so the two the app actually uses are
  identifiable at a glance.
- **The live values it publishes**, and a dash where it publishes none. `ITIROL16` has no
  pyranometer and `ITIROL26` does; that difference decides whether `StationSun` has anything to work
  with, and it should be visible rather than inferred.
- **How old the reading is.** A station that stopped an hour ago looks identical to a working one
  until you print the time.

And for stations the generator refused, a short reason instead of values — `Höhe falsch` where the
DEM disagreed with the claim, with both numbers. That is the single most useful thing this screen
can say: it is how the 204 m / 654 m error on `ITIROL26` would have been visible in seconds rather
than found by reading a JSON payload.

## Where the list comes from

The catalogue holds one station per place, not the neighbourhood. So the screen asks
`/v3/location/near` at open, exactly as the generator does, and then `current` per station.

**That is up to eleven requests for one screen**, against 1500 a day. Acceptable because it is
opened deliberately and rarely, and unacceptable if it were ever put behind anything automatic —
which is why the fetch belongs to the screen's ViewModel and not to `WeatherRepository`, where
something would eventually call it on a schedule.

The result is held in memory for the life of the ViewModel, the way `RadarRepository` holds its
frames, so returning to the screen inside a session does not spend the quota again.

**`PWS_EXTRA`'s stations are included**, read from the catalogue's own `pws` entry: a station the
`near` endpoint hides would otherwise be missing from the one screen built to show it, which is the
opposite of the point.

## The DEM check, on a phone

The generator has SRTM tiles; the app does not, and shipping them is out of the question. So the
screen cannot recompute the check — it can only show what the station claims and what the catalogue
recorded for the one station it knows.

Rather than pretend otherwise, a station's altitude is shown as **claimed**, and the chosen one
additionally shows the catalogue's DEM figure beside it. Where a station's claim differs from the
chosen station's DEM altitude by more than the village's own plausible spread, it is marked
*unverified* rather than *wrong* — the app does not have the evidence to say wrong, and saying it
anyway would be the kind of confident falsehood this app is careful about elsewhere.

## Errors

A station that answers HTTP 204 shows *keine Meldung* with its distance still listed: that is an
ordinary state and a live station does it intermittently. A station that fails outright shows the
error text, short. The screen never fails as a whole because one station did — the same rule
`supervisorScope` gives the refresh.

No key means no screen: the entry point on the station card is absent, because every row would be
an error about a missing key, which the settings screen already says once.

## Tests

- **`NearbyStationsStateBuilderTest`** — pure: ordering by distance, the chosen and provincial
  markers, a 204 becoming "no reading" rather than an error, a station with no pyranometer showing a
  dash rather than zero, and the unverified-altitude marker firing on a claim far from the DEM.
- **`NearbyStationsContentTest`** — the rows render, the chosen one is marked, and the screen at
  font scale 2 does not clip a row (this is a table of numbers on a phone; it is the obvious thing
  to get wrong).
- **`NearbyStationsViewModelTest`** — opening fetches once, returning does not fetch again, one
  station failing leaves the others, and **the fake is asked for each station by id** — a fake that
  ignores its arguments cannot fail for the reason that matters.

## Verification

On the phone at Dorf Tirol: the list should show `ITIROL16` marked as chosen, `ITIROL26` beside it
with a radiation figure `ITIROL16` lacks, the three impossible-altitude stations marked unverified,
and Meran at the bottom as the provincial fallback. Compare two or three readings against
`wunderground.com` to confirm the screen is not inventing anything.
