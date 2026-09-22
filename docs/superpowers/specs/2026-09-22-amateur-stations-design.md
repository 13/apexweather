# Amateur stations — design

2026-09-22

## What this is

Every place in the catalogue reads a provincial SIAG thermometer, and for most of them the fit is
good: the median place is 3,9 km from its station and 71 m above or below it. At the tail it is bad.
Hafling reads Jenesien 15,1 km away and 320 m down; Villanders reads Barbian Kollmann 390 m down;
Lüsen reads Villnöss 12,1 km away; Feldthurns reads Völs am Schlern 18,6 km away. Dorf Tirol reads
Meran, 1,5 km away and 264 m below, and the whole of `StationDownscale` exists to carry that reading
up the hill.

Weather Underground's contributor network has stations the province does not. Measured on
2026-09-22 with the owner's key, within two kilometres of Dorf Tirol:

```
 0,35 km  ITIROL25    claims 182 m   T 18,0  rh 36  qc -1
 0,44 km  ITIROL23    claims 179 m   T 16,0  rh 39  qc  1
 0,49 km  ITIROL16    claims 586 m   T 15,0  rh 41  qc  1
 1,14 km  IALGUN5     claims  93 m   T 14,0  rh 60  qc  1
 1,26 km  ITRENTIN46  claims 311 m   T 17,0  rh 43  qc  1
 1,42 km  IMERAN3     claims 413 m   T 18,0  rh 35  qc  0
 1,76 km  ITIROL24    claims 128 m   T 20,0  rh 27  qc  1
 1,92 km  IMERAN10    —              T 19,0
```

Dorf Tirol stands at 594 m. **ITIROL16 claims 586 m, 490 m away**, and the DEM under its
coordinates reads 634 — so it is somewhere within a few tens of metres of the village's own height
either way, against the 264 m the app corrects for today. For this place that is not a better input
to the height correction, it is the correction becoming nearly unnecessary — the hero stops being a
converted number and becomes a reading taken at roughly the reader's own altitude.

The same table is the argument for everything defensive in this design. Four of the seven report an
altitude that cannot be true, and one still evening spreads six degrees over two kilometres.

## What a good amateur station actually looks like here

ITIROL16 against the official Meran thermometer, both hourly, 2026-09-22 (Meran from Open Data Hub,
ITIROL16 from 263 WU readings):

```
hour   Meran 330 m   ITIROL16 ~634 m    diff    qc=0 share
00        11,5           14,9          +3,4        0 %
05         7,9           12,0          +4,1        0 %
07        15,7           11,2          -4,5        0 %
08        18,0           11,4          -6,6        0 %
11        23,1           20,1          -3,0       75 %
13        25,8           22,4          -3,3       92 %
15        25,2           25,2          +0,1       33 %
17        20,9           24,8          +3,8        0 %
```

The village-minus-valley difference runs from +4,1 K to −6,6 K inside one day. That is not noise and
it is not a broken sensor: it is the nocturnal inversion this province's CLAUDE.md already describes,
followed by the village sitting in the Texelgruppe's shadow until nine — which is what `Horizon` says
about Dorf Tirol, 19,4° of ridge to the north and the sun arriving 70 minutes after "sunrise" in
September — while the valley floor gains ten degrees in three hours, and then the afternoon
crossover as the slope clears and the valley shades.

Ten and a half kelvin of swing is also the size of the thing `StationDownscale` is currently asked
to model with a station 264 m below. It is the whole case for this feature, and it is the reason the
runtime quality control below refuses to second-guess a reading that disagrees with the models.

## Two facts that constrain the whole design

**The key is personal and quota-limited.** `api.weather.com`'s PWS tier issues keys free to people
who upload a station, capped at 1500 requests a day and 30 a minute, and returns nothing observed
more than 60 minutes ago. It is not licensed for redistribution. Consequences, all of them real:
the key lives in `local.properties` and reaches the code through `BuildConfig`, never a committed
file; the app must build and run **without** it, falling back to SIAG everywhere, or CI cannot build
and nobody else can; and the amateur reading must not be redistributed — see "The share card" below.

**AWEKAS is closed to the API, though the station is real.** The account's key is recognised and the
account is not entitled:
`GET api.awekas.at/current.php?key=…&station=53677` answers
`{"fetchdate":1790106490,"error":"AWEKAS plus not active"}`, with and without a station parameter.
The station page itself is public — `awekas.at/en/instrument.php4?id=53677` — and reports no solar
or UV sensor. The design leaves room for a second network but implements one.

## Choosing the station — `tools/generate-places.py`

Offline, reviewed by a human, and committed with the catalogue, on the same terms as everything else
in `places.json`.

For each of the 116 places the generator asks
`GET /v3/location/near?geocode=<lat>,<lon>&product=pws` — ten candidates, one request — then
`GET /v2/pws/observations/current` per candidate, and puts every candidate through four gates:

1. **It answers.** HTTP 204 means nothing observed in the last hour. A station that is not
   uploading today will not be uploading when a reader opens the app.
2. **`qcStatus` is recorded and never obeyed.** WU's flag — 1 passed, −1 not checked, 0 failed — is
   a *neighbour-consistency* test, and in this terrain a correctly sited station fails it for being
   right. Measured on ITIROL16 over 263 readings on 2026-09-22: 40 flagged 0, **every one of them
   between 10:44 and 19:04**, peaking at 92 % of the 13:00 hour. Those are exactly the hours it
   disagrees with ITIROL23, ITIROL25 and ITIROL24 — the three stations gate 3 drops for claiming
   128 to 182 m on a hillside that is 419 to 598 m high. Obeying the flag would throw away the best
   thermometer's whole afternoon and fall back to one 300 m below it, which is the mistake this
   feature exists to correct. The value is stored with the observation so it can be scored later,
   and it decides nothing.
3. **The DEM agrees with the claimed altitude.** `tools/horizons.py.ground()` already reads 30 m
   SRTM tiles out of `tools/.dem-cache`; the claimed altitude is compared against the ground under
   the claimed coordinates. Measured on the eight stations above:

   ```
   ITIROL16    claims 586   DEM 634   Δ  48 m   keep
   ITRENTIN46  claims 311   DEM 309   Δ   2 m   keep
   IMERAN3     claims 413   DEM 440   Δ  27 m   keep
   IALGUN5     claims  93   DEM 303   Δ 210 m   drop
   ITIROL24    claims 128   DEM 419   Δ 291 m   drop
   ITIROL23    claims 179   DEM 594   Δ 415 m   drop
   ITIROL25    claims 182   DEM 598   Δ 416 m   drop
   ```

   `PWS_MAX_DEM_DISAGREEMENT_M = 100`. The threshold is not delicate and the spec should say why:
   the good stations sit at 2 to 48 m and the bad ones at 210 to 416, so any cut between about 60
   and 200 gives the same seven answers today. It is not tighter than that because SRTM is a 30 m
   grid over steep ground and is itself wrong by tens of metres here — Dorf Tirol's own catalogue
   altitude of 594 m sits 23 m from the DEM under it. **The DEM is the arbiter of the altitude that
   gets written into the catalogue**, not the station's claim, because the claim is what was typed
   into a web form.

4. **It beats the official station on the measure already used.** `generate-places.py` does not pick
   by distance; `pick_by_stability` scores each candidate by the standard deviation of
   village-minus-station across eight weeks of ICON-D2, because `StationDownscale` needs a station
   standing in the *same air*, not a near one. A PWS candidate is a triple of latitude, longitude
   and altitude like any other, so it enters that comparison unchanged, against the same
   `STATION_MAX_DZ_M` of 400 m and the same `HEIGHT_COST_KM_PER_M`. The amateur station is kept only
   where it wins.

A kept station gets its own skyline from `tools/horizons.py`, exactly as `NearbyStation` does — a
thermometer 490 m from the village on a different shoulder has a different horizon, and `StationSun`
reads the station's, not the village's.

`places.json` gains a field beside `station`, and **never replaces it**:

The altitude is the DEM's and the stability is whatever `pick_by_stability` measures; both are
written by the generator, neither is invented here.

```json
"pws": { "network": "wu", "id": "ITIROL16", "lat": 46.6932, "lon": 11.1552,
         "altitudeM": 634, "distanceKm": 0.49, "stabilityK": <measured>, "horizon": [ … ] }
```

The official station stays in the record for every place that has one. It is the fallback, and the
fallback is used more often than the happy path deserves to assume.

## What it feeds, and what it does not

The amateur station is the place's station **for every quantity it publishes**, and SIAG supplies
every quantity it does not. That is not a compromise, it is what the data forces: ITIROL16 publishes
no `solarRadiation` at all, and a rule that handed the whole observation to it would switch
`StationSun` off for Dorf Tirol and bring back the 2026-09-11 "Bedeckt at 834 W/m²" case.

The two readings are therefore **kept as two records, not merged into one**. A merged record would
carry a temperature from one site and a radiation from another under one set of coordinates, and
`StationSun` computes the sun's elevation and reads the skyline *at the pyranometer*. Mixed
provenance under one coordinate pair is a wrong answer that looks like a right one.

`WeatherSnapshot` gains `observation` (the primary, the PWS where there is a usable one) and keeps
the SIAG reading beside it. Each rule takes the record that has what it needs, with that record's own
site:

| rule | reads | from |
|---|---|---|
| hero, `StationDownscale` | `tempC` | primary; its own altitude and coordinates |
| `StationFog` | `humidityPct` | primary |
| `StationDry`, `MeasuredRain` | `precipTodayMm`, `precipRateMm` | primary where it publishes rain, else SIAG |
| `StationSun` | `radiationWm2`, plus lat/lon/horizon | whichever record has radiation, with **its** site |
| hour sheet wind | `windKmh`, `gustKmh`, `windDirDeg` | primary where published, else SIAG |
| snow depth `hs`, sunshine `sd` | — | SIAG only; WU publishes neither |

`StationObservation` gains `network` and `stationId` so a record can say which thermometer it is.

## Runtime quality control

Three gates on the phone, and then the app's own evidence.

**Freshness.** WU returns nothing older than 60 minutes; the app additionally refuses a reading over
`PWS_MAX_AGE` (30 min) old for the hero, because a PWS that uploads every few minutes going quiet
for half an hour is a PWS that has stopped. `Source.staleAfterHours`' existing 90 min for an
observation stays the rule for the SIAG record.

**The fault rule already written.** `ForecastScores` drops an hour for every model when the station
reads more than 15 K or 60 km/h from the models' weighted median at that station, and counts it. The
same test decides whether the primary record is usable at all this refresh: a PWS more than 15 K from
the models at its own coordinates is broken, and the app falls back to SIAG for that refresh without
saying anything dramatic on screen.

Below that it does **not** second-guess the reading. A 3 K disagreement with the models on a clear
night is what a thermometer in a village is *for*, and a rule that rejected it would reject the
feature's entire value. Everything downstream is already defended: `StationDownscale`'s fade, its
bracket, and the fact that the models win when the result leaves the bracket.

**And then it is measured.** `station_history` scores whatever station the place is on, so after a
fortnight the statistics screen says whether ITIROL16 or Meran is the better thermometer, in the
app's own terms rather than this document's. That is the point at which this feature is either
justified or dropped.

## `station_history` must learn which thermometer it holds

`station_history`'s primary key is `(place, hourEpoch)` and it carries no station identity. Every row
is "what the station read and what each model said it would read". Switching a place from Meran to
ITIROL16 would go on writing into the same rows, and `BiasCorrector` would subtract a habit measured
against two different thermometers 264 m apart — silently, and in the one table the app never throws
away.

So `HistoryDatabase` goes to **version 3** with a migration adding `station TEXT`, and:

- rows are written with the id of the station they came from;
- `BiasCorrector` and `VerificationHistory` filter to the current station;
- rows of a station the place is no longer on are kept, not deleted. They are a record of a real
  measurement, they are already inside `VerificationHistory.KEEP`'s 90 days, and deleting data
  because it became inconvenient is what put this table in its own database in the first place.

The migration is `MIGRATION_2_3`, the schema is exported to `app/schemas`, and existing rows get the
place's official station id, since that is what they are.

## Fetching — `data/remote/WeatherUndergroundApi.kt`

One file per upstream, as every other source has. One endpoint in the app:
`/v2/pws/observations/current?stationId=&format=json&units=m&apiKey=`, mapped to
`StationObservation`. The `near` and `history` endpoints belong to the generator, not the phone.

Units are metric, so the mapping is direct: `metric.temp`, `humidity`, `metric.precipTotal` (since
local midnight — the same shape as SIAG's `n`, which is what makes `StationDry.withPrevious` work
unchanged), `metric.precipRate`, `metric.windSpeed`, `metric.windGust`, `winddir`,
`solarRadiation`, `obsTimeUtc`.

It is fetched where the SIAG observation is fetched, inside the same `supervisorScope`, and it fails
independently: losing the PWS must cost nothing but the fallback.

**Quota.** `ForegroundRefreshLoop` asks for the station every 10 minutes while the app is open (20
on a metered connection) and `RefreshWorker` once an hour. Three hours of use a day is about 42
requests, against 1500 — and `MeteredNetwork` and the existing `RefreshDue` rule already hold the
rate down. The cap is not a constraint on one reader's phone, and it is the reason this cannot ship
with a bundled key to other people's.

**Absent key.** `BuildConfig.WU_API_KEY` empty means the whole path is switched off at
construction: no request, no `pws` lookup, `silentSources` unaffected, SIAG everywhere. CI builds
with no key and the instrumented suite runs without one.

## On screen

The hero already names its station and says when it was read. It now says what kind of station it
is, because a reading from a neighbour's garden is not a reading from the provincial network and the
reader is entitled to know which one is on their screen:

```
Tirol (private Station), 21:47          ← no "umgerechnet", because there is nothing to convert
```

Where the reading is still moved, the existing `now_from_station_adjusted` wording is unchanged and
gains the same qualifier. New strings in all three languages; `station_private` is the qualifier.

`SourceDetailSheet` and the statistics screen name the station they scored, which they can now do
because the rows carry it.

**The share card does not carry the amateur reading.** WU's terms do not license redistribution, and
`ShareCapture` writes a PNG that leaves the phone. `ShareCardStateBuilder` falls back to the SIAG
record, or to the consensus where there is none — which is a one-line change there because it
computes no weather of its own. This is the one place the feature is deliberately switched off, and
the reason is legal rather than technical.

## Out of scope

- **AWEKAS**, until the account is entitled. The `network` field exists so adding it is a mapper.
- **MeteoNetwork**, the Italian CC BY 4.0 amateur network, which would be the right way to do this
  for other people's phones and for the share card. Worth its own spec if this one proves out.
- **A map layer of amateur stations.** The radar tab has its own budget and its own argument.
- **Amateur stations for places that already fit well.** The gate is `pick_by_stability`, not
  enthusiasm; most of the 116 will keep their provincial thermometer and should.

## Tests

- **`WeatherUndergroundMapperTest`** — against a recorded fixture of ITIROL16's real response,
  checked in beside the others; the 204 case mapping to "no reading" rather than to an exception;
  `qcStatus` 0 refused, −1 accepted; missing `solarRadiation` leaving the field null rather than
  zero. And, per the `FakeGeoSphere` lesson, **the fake checks its `stationId`** — a fake that
  ignores its arguments cannot fail for the reason that matters.
- **`StationSelectionTest`** (generator, Python) — the DEM gate over the eight measured stations
  above, as a table.
- **`HistoryDatabaseTest`** — `MIGRATION_2_3` preserving rows and stamping them with the official
  station; `BiasCorrector` ignoring rows from another station.
- **`WeatherRepositoryTest`** — the PWS failing leaves the SIAG observation and the hero intact; the
  15 K fault rule falling back; the empty-key build making no request at all.
- **`HomeStateBuilderTest`** — the primary record feeding the hero with no adjustment where the
  altitudes match; `StationSun` still reading radiation from the SIAG record, at the SIAG site.
- **`ShareCardStateBuilderTest`** — the amateur reading absent from the card.

## Verification on the phone

Install, open on Dorf Tirol, and check the hero against ITIROL16's own dashboard and against a
thermometer out of the window — the one place in the catalogue where that is possible. Then leave it
a fortnight and read the statistics screen, which is the only honest verdict available.

Two things that cannot be verified this way and should be said rather than assumed: the fallback
path needs ITIROL16 to actually go quiet, so it is covered by tests and not by observation; and the
intermittency below has to be lived with rather than reproduced on demand.

## The owner's own station, and what it taught the design

`ITIROL26` is the same instrument as AWEKAS 53677, "Dorf Tirol III": 669 m declared on AWEKAS,
654 m under its coordinates on the DEM, 0,68 km from the village centre, and on 2026-09-22 at 21:55
both networks served 16 °C at 37 % within minutes of each other. It passes the DEM gate at Δ15 m and
is a second real candidate for Dorf Tirol beside ITIROL16.

Two things about it are load-bearing for this design rather than incidental:

- **It answered HTTP 204 and then, minutes later, 200.** A live station with a working key returns
  "nothing in the last 60 minutes" intermittently. The fallback to SIAG is therefore an ordinary
  hourly event, not an error path — it must be silent, it must not mark the source as failed, and
  `silentSources` must not accuse anybody over it.
- **`/observations/all/1day` returns 204 for it while ITIROL16 returns 263 readings.** The rapid
  history is not available for every station, so nothing in the app or the generator may depend on
  it. Only `current` is guaranteed, which is all the phone asks for anyway.
