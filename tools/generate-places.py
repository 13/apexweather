#!/usr/bin/env python3
"""Generates app/src/main/assets/places.json, the catalogue of South Tyrolean municipalities.

Run by hand, never in CI, and review the output before committing it:

    python3 tools/generate-places.py

Three upstreams are combined:

  * Open Data Hub /v1/Municipality  — the ISTAT code, coordinates and altitude of all 116.
  * SIAG KMOS, once per ISTAT code  — the German, Italian and English names, and proof that the
    code actually serves a forecast. A code that returns no series is left out of the catalogue.
  * SIAG /api/v2/station            — the candidate weather stations, if any is close enough.
  * Open-Meteo ICON-D2, once per municipality — which of those candidates actually stands in the
    same air as the village. See stability(); this is what makes the run take minutes.
  * SRTM elevation tiles, through tools/horizons.py — the skyline around each place and each chosen
    station, so the app can say when the sun actually clears the ridge rather than when a horizon
    with no mountains in it would have had it rise.

The weather district is the one datum no upstream provides: /api/v2/district lists the seven
districts by name and nothing about their membership, the municipality record's Region is an
unnamed tourism region, and the district record's TourismVereinIds is null. It is derived here
instead, from the tourism region each municipality belongs to, with an override list for the
municipalities where the two divisions disagree. That is far less to review than 116 hand-typed
rows, and the overrides are where the judgement actually lives.
"""
import horizons
import json
import os
import math
import pathlib
import sys
import time
import urllib.request

ODH_MUNICIPALITIES = "https://tourism.opendatahub.com/v1/Municipality?language=de"
ODH_REGIONS = "https://tourism.opendatahub.com/v1/Region?language=de"
SIAG_STATIONS = "https://api-weather.services.siag.it/api/v2/station?categoryId=1&visibility=11"
KMOS = "https://api-weather.services.siag.it/api/v2/municipality/MunicipalityBulletin/{istat}"

# Beyond this a station is not speaking about the place any more; 20 km in this province can cross
# a ridge. A place with no station within the cap shows the consensus and no measurements card.
PLACES = pathlib.Path("app/src/main/assets/places.json")

STATION_MAX_KM = 20.0

# And beyond this it is not speaking about the place's *air*. Picking the nearest station by ground
# distance alone is the wrong measure in a province where four kilometres can be a kilometre of
# height: it gave Hafling (1290 m) the thermometer at Gargazon on the valley floor at 254 m, Aldein
# (1225 m) the one at Auer at 250 m, and Karneid (290 m) the one on the Ritten plateau 697 m above
# it. A reading a kilometre below the village is not that village's weather however close it is —
# it is a different climate, and on a clear afternoon it is five to ten degrees out.
#
# So height is part of the distance, and a station further than STATION_MAX_DZ_M in height is not
# considered at all. Four hundred metres is about four degrees of dry adiabat, which is as much as
# StationDownscale can honestly carry.
STATION_MAX_DZ_M = 400

# Those two gates decide who is a *candidate*. Which candidate is chosen is measured, not guessed —
# see pick_by_stability. HEIGHT_COST_KM_PER_M only orders them when the measurement is unavailable:
# a hundred metres of height costs as much as a kilometre of ground.
HEIGHT_COST_KM_PER_M = 1 / 100

# Weather Underground's PWS contributor API. Amateur stations stand where the province's own do not:
# three of them are inside Dorf Tirol, and one, ITIROL16, sits within a few tens of metres of the
# village's own height where the provincial thermometer is 264 m below it.
#
# The key is the operator's own — personal, capped at 1500 requests a day, and not licensed for
# redistribution — so it is read from the environment and never committed. Without it this whole
# step is skipped and every place keeps its provincial station, which is what every other checkout
# and CI will do.
WU_KEY = os.environ.get("APEX_WU_API_KEY", "")
WU_NEAR = "https://api.weather.com/v3/location/near?geocode={lat},{lon}&product=pws&format=json&apiKey={key}"
WU_CURRENT = "https://api.weather.com/v2/pws/observations/current?stationId={id}&format=json&units=m&apiKey={key}"

# How far a station's claimed altitude may sit from the ground under its own coordinates before the
# claim is treated as fiction rather than as data.
#
# The altitude is typed into a web form by whoever put the station up, and it is wrong far more
# often than it is right. Measured on the eight stations around Dorf Tirol on 2026-09-22: the three
# believable ones sat 2, 27 and 48 m from the DEM, and four claimed 93 to 182 m while standing on
# ground the DEM puts at 303 to 598 — out by 210, 291, 415 and 416 m. The gap between the two groups
# is an order of magnitude, so anything between about 60 and 200 gives the same seven answers and
# this threshold is not delicate.
#
# It is not tighter than this because SRTM is a 30 m grid over steep ground and is itself out by
# tens of metres here: Dorf Tirol's own catalogue altitude of 594 m sits 23 m from the DEM under it.
PWS_MAX_DEM_DISAGREEMENT_M = 100

# Stations the `near` endpoint does not return but which are worth considering anyway, by ISTAT.
#
# ITIROL26 sits 0,68 km from Dorf Tirol and answers `current` perfectly well; the endpoint returns
# ten stations for that place, stopping at 1,92 km, and simply does not include it. There is no
# documented reason, so the only way to consider it is to name it.
#
# Anything here is a *candidate and nothing more*. It goes through the same DEM gate and the same
# stability measure as a station the endpoint did offer, and is dropped by them as readily.
#
# ITIROL26 was dropped until 2026-09-23, and the reason is worth keeping: WU reported its elevation
# as 204 m against a DEM of 654, because its station form is in **feet** and 669 had been entered
# there — 669 ft is 203,9 m, which is exactly what the metric API returned, while AWEKAS had the
# same instrument at 669 m. Re-entered as 2195 ft it reports 669 m and passes at 15 m. Altitude is
# what StationDownscale carries a reading up by, so the gate was right to refuse the first and right
# to accept the second.
PWS_EXTRA = {
    "021101": ["ITIROL26"],  # Dorf Tirol
}

# The model the stations are judged against, and how much of its past to judge them on. ICON-D2 runs
# at about 2 km, which is the coarsest resolution at which two points five kilometres apart in
# different valleys are still different places. Open-Meteo keeps roughly eight weeks of it, which is
# several weather regimes rather than one settled fortnight.
STABILITY_MODEL = "icon_d2"
STABILITY_PAST_DAYS = 60
OPEN_METEO = "https://api.open-meteo.com/v1/forecast"

# Below this many usable hours the measurement is not worth trusting and the ordering above is used.
STABILITY_MIN_HOURS = 200

# One call per municipality is enough to be rate-limited partway down the list, and the fallback is
# quiet by design — which is how a run once wrote a catalogue whose last fourteen multi-candidate
# places had been chosen by the ordering while the rest were measured. Retry, pace, and refuse to
# write a catalogue that is only half measured.
STABILITY_RETRIES = 3
STABILITY_BACKOFF_S = (5, 20, 60)
STABILITY_PACE_S = 1.0
STABILITY_MAX_FALLBACKS = 5

# How much worse than the steadiest candidate a nearer one may be and still be preferred.
#
# The measurement decides which candidates are *acceptable*, not which one wins. Picking the
# steadiest outright is picking model noise: over eight weeks of hourly values with the
# autocorrelation weather has, the standard error on one of these standard deviations is around
# 0,08 K, so the difference between two of them carries about 0,12 K of nothing. Argmin on that
# gave Jenesien the thermometer on the Ritten ten kilometres away instead of the one standing in
# Jenesien, and took Prettau's own station off Prettau — trading real information the model cannot
# see, that this is the village's actual air, for a rounding error in ICON-D2.
#
# So: of the candidates within this much of the steadiest, the nearest by distance-and-height wins.
STABILITY_TOLERANCE_K = 0.15

DISTRICT_NAMES = {
    1: "Bozen, Überetsch und Unterland",
    2: "Burggrafenamt – Meran und Umgebung",
    3: "Vinschgau",
    4: "Eisacktal und Sarntal",
    5: "Wipptal – Sterzing und Umgebung",
    6: "Pustertal",
    7: "Ladinien – Dolomiten",
}

# Tourism region -> weather district. Covers 110 of the 116; the rest are in OVERRIDES.
REGION_TO_DISTRICT = {
    "Meran und Umgebung": 2,
    "Brixen und Umgebung": 4,
    "Vinschgau": 3,
    "Südtiroler Weinstraße": 1,
    "Bozen und Umgebung": 1,
    "Sterzing und Umgebung": 5,
    "Ahrntal": 6,
    "Dolomitenregion Kronplatz": 6,
    "Dolomitenregion 3 Zinnen": 6,
    "Dolomitenregion Alta Badia": 7,
    "Dolomitenregion Gröden": 7,
    "Dolomitenregion Eggental": 1,
    "Dolomitenregion Seiser Alm": 4,
    "Dolomitenregion Lüsen Villnöss": 4,
}

# Where the tourism region is absent or disagrees with the weather district.
OVERRIDES = {
    # No tourism region at all in the Open Data Hub record; placed by valley.
    "021001": 1,  # Aldein — Unterland
    "021003": 1,  # Altrei — Unterland
    "021102": 1,  # Truden — Unterland
    "021039": 4,  # Lajen — Eisacktal
    "021052": 6,  # Welsberg-Taisten — Pustertal
    "021109": 6,  # Gsies — Pustertal
    # Sarntal's tourism region is Bozen, but the district is named for the valley.
    "021086": 4,
    # The two Ladin municipalities of the Gadertal that market themselves under Kronplatz.
    "021082": 7,  # San Martin de Tor / St. Martin in Thurn
    "021047": 7,  # San Vigilio / Enneberg
}

# Reviewed on 2026-09-09 and left as the tourism region has them. Listed because these are the
# places where a reader who knows the valleys might reasonably disagree, and this is the list to
# revisit if one of them ever looks wrong on screen:
#   Naturns (2, at the mouth of the Vinschgau)
#   Mühlbach, Vintl, Terenten, Natz-Schabs, Rodeneck (4, at the mouth of the Pustertal)
#   Kastelruth, Völs am Schlern, Tiers am Rosengarten (4, the Seiser Alm)
#   Karneid, Deutschnofen, Welschnofen (1, the Eggental)


def fetch(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return json.load(r)


def distance_km(lat1, lon1, lat2, lon2):
    """Flat-earth, which over the tens of kilometres this province spans is accurate to centimetres."""
    dx = (lon2 - lon1) * math.cos(math.radians((lat1 + lat2) / 2)) * 111.32
    dy = (lat2 - lat1) * 110.57
    return math.hypot(dx, dy)


def siag_float(v):
    try:
        return float(str(v).replace(",", "."))
    except (TypeError, ValueError):
        return None


def stability(place_point, candidate_points):
    """
    How steady the difference between the village and each candidate station is, in kelvin.

    This is most of the question that matters, and it is not "which thermometer is nearest".
    `StationDownscale` quotes the village as the models' village plus however much the thermometer
    disagrees with the models about the station, so what it needs is a station standing in the same
    air: one whose difference from the village is *stable*, whatever its size. A station over a
    ridge can sit at the right altitude four kilometres away and still spend every clear night in a
    different inversion.

    So each candidate is scored by the standard deviation of village-minus-station across eight
    weeks of ICON-D2, and anything much worse than the steadiest is ruled out — see
    STABILITY_TOLERANCE_K for why the steadiest does not simply win. Measured rather than assumed,
    because assuming got it wrong: ordering by distance and height alone put eleven of eighteen
    places on a station that was not the steadiest available, and three of them on one less steady
    than the station they had just been moved off.

    Returns a list of standard deviations, one per candidate, with None where a candidate could not
    be measured.
    """
    points = [place_point] + candidate_points
    query = (
        f"{OPEN_METEO}?latitude={','.join(f'{p[0]:.6f}' for p in points)}"
        f"&longitude={','.join(f'{p[1]:.6f}' for p in points)}"
        f"&elevation={','.join(str(int(p[2])) for p in points)}"
        f"&timezone=Europe%2FRome&past_days={STABILITY_PAST_DAYS}&forecast_days=1"
        f"&models={STABILITY_MODEL}&hourly=temperature_2m"
    )
    for attempt in range(STABILITY_RETRIES):
        try:
            response = fetch(query)
            break
        except Exception:  # noqa: BLE001 — retried; the caller falls back if all attempts fail
            if attempt == STABILITY_RETRIES - 1:
                raise
            time.sleep(STABILITY_BACKOFF_S[attempt])
    series = [x["hourly"]["temperature_2m"] for x in response]
    village = series[0]
    out = []
    for candidate in series[1:]:
        diffs = [a - b for a, b in zip(village, candidate) if a is not None and b is not None]
        if len(diffs) < STABILITY_MIN_HOURS:
            out.append(None)
            continue
        mean = sum(diffs) / len(diffs)
        out.append(math.sqrt(sum((d - mean) ** 2 for d in diffs) / len(diffs)))
    return out


def wu_fetch(url):
    """A WU call whose 204 is data rather than an error.

    `current` answers **HTTP 204 with no body** when the station has reported nothing in the last
    60 minutes, and a live station does that intermittently: ITIROL26 answered 204 and then, minutes
    later, 200 with a reading. So an empty body means "no reading right now", not "no such station",
    and it is the caller's job to decide what that is worth.
    """
    try:
        with urllib.request.urlopen(url, timeout=60) as r:
            body = r.read()
            return json.loads(body) if body else None
    except Exception:  # noqa: BLE001 — an amateur station that will not answer is simply not a candidate
        return None


def pws_candidates(lat, lon, altitude, istat=None):
    """Amateur stations near a place that are worth putting to the stability test.

    Three gates before a candidate is even measured, and each one earns its place:

    1. **It answers.** See [wu_fetch]: no body means nothing observed in the last hour. This drops
       some stations that are merely having a quiet hour, which is the safe direction for a choice
       that gets committed to a catalogue and reviewed by hand.
    2. **The DEM agrees with the claimed altitude.** [PWS_MAX_DEM_DISAGREEMENT_M] has the measured
       numbers. The DEM's answer, not the station's claim, is what is written into the catalogue —
       the claim is what somebody typed into a web form.
    3. **It is close enough and level enough**, by the same [STATION_MAX_KM] and [STATION_MAX_DZ_M]
       the provincial stations are held to, ordered by the same [HEIGHT_COST_KM_PER_M].

    `qcStatus` is **recorded and obeyed by nothing**. It is a neighbour-consistency test, and in
    this terrain a correctly sited station fails it for being right: ITIROL16 was flagged 0 on 40 of
    263 readings on 2026-09-22, every one of them between 10:44 and 19:04, which is exactly when it
    disagrees with ITIROL23, ITIROL25 and ITIROL24 — the three that gate 2 drops for claiming 128 to
    182 m on ground the DEM puts at 419 to 598 m. Obeying the flag would throw away the best
    thermometer's whole afternoon in favour of one 300 m below it.

    Returns a list shaped like the SIAG `candidates` list: (cost, record, distance_km, dz).
    """
    if not WU_KEY:
        return []
    near = wu_fetch(WU_NEAR.format(lat=lat, lon=lon, key=WU_KEY))
    offered = (near or {}).get("location", {}).get("stationId", []) or []
    # Named stations first, then whatever the endpoint offered, de-duplicated. Order only decides
    # which duplicate is dropped; the gates below decide everything that matters.
    codes = list(dict.fromkeys(PWS_EXTRA.get(istat, []) + list(offered)))
    if not codes:
        return []
    out = []
    for code in codes:
        body = wu_fetch(WU_CURRENT.format(id=code, key=WU_KEY))
        observations = (body or {}).get("observations") or []
        if not observations:
            continue
        o = observations[0]
        claimed = (o.get("metric") or {}).get("elev")
        if claimed is None or o.get("lat") is None or o.get("lon") is None:
            continue
        s_lat, s_lon = o["lat"], o["lon"]
        dem = horizons.ground(s_lat, s_lon)
        if abs(dem - claimed) > PWS_MAX_DEM_DISAGREEMENT_M:
            print(f"    {code}: claims {claimed:.0f} m, DEM {dem:.0f} m — dropped", file=sys.stderr)
            continue
        d = distance_km(lat, lon, s_lat, s_lon)
        dz = abs(altitude - dem)
        if d > STATION_MAX_KM or dz > STATION_MAX_DZ_M:
            continue
        out.append((
            d + dz * HEIGHT_COST_KM_PER_M,
            {
                "network": "wu",
                "code": code,
                "name": o.get("neighborhood") or code,
                "latitude": s_lat,
                "longitude": s_lon,
                # The DEM's altitude, never the claim.
                "altitude": int(round(dem)),
                "qcStatus": o.get("qcStatus"),
            },
            d,
            dz,
        ))
    out.sort(key=lambda c: c[0])
    return out


def pick_by_stability(place_point, candidates):
    """
    The steadiest candidate, or the nearest-by-cost one where the models cannot be asked.

    [candidates] is a list of (cost, station, distance_km, height_difference_m), already ordered by
    cost. Returns (station, distance_km, score) where score is the standard deviation that chose it,
    or None when the ordering did the choosing.
    """
    if len(candidates) == 1:
        return candidates[0][1], candidates[0][2], None
    points = [(siag_float(c[1]["latitude"]), siag_float(c[1]["longitude"]), int(c[1]["altitude"])) for c in candidates]
    try:
        scores = stability(place_point, points)
    except Exception as e:  # noqa: BLE001 — a place the models will not answer about falls back
        print(f"    stability unavailable ({e}); ordering by distance and height", file=sys.stderr)
        return candidates[0][1], candidates[0][2], None
    scored = [(sd, i) for i, sd in enumerate(scores) if sd is not None]
    if not scored:
        return candidates[0][1], candidates[0][2], None
    best = min(scored)[0]
    # `candidates` is already ordered by distance and height, so the first acceptable one is the
    # nearest acceptable one.
    for i, sd in enumerate(scores):
        if sd is not None and sd <= best + STABILITY_TOLERANCE_K:
            return candidates[i][1], candidates[i][2], sd
    raise AssertionError("the steadiest candidate is always within tolerance of itself")


def district_of(istat, region_name):
    if istat in OVERRIDES:
        return OVERRIDES[istat]
    return REGION_TO_DISTRICT.get(region_name)


def pws_check(names):
    """Print the amateur-station candidates for a few places and write nothing.

    The counterpart of `horizons.py --check`: a full run takes minutes and rewrites a committed
    catalogue, and the one decision worth eyeballing first is which stations the DEM gate keeps.

        APEX_WU_API_KEY=... python3 tools/generate-places.py --pws-check "Dorf Tirol"
    """
    if not WU_KEY:
        print("APEX_WU_API_KEY is not set; nothing to check", file=sys.stderr)
        return
    catalogue = json.loads(PLACES.read_text(encoding="utf-8"))
    wanted = [p for p in catalogue if p["nameDe"] in names] if names else \
        [p for p in catalogue if p["nameDe"] == "Dorf Tirol"]
    for place in wanted:
        lat, lon, altitude = place["lat"], place["lon"], place["altitudeM"]
        print(f"\n{place['nameDe']} ({altitude} m, DEM {horizons.ground(lat, lon):.0f} m)", file=sys.stderr)
        for cost, record, d, dz in pws_candidates(lat, lon, altitude, place['istat']):
            print(f"  kept    {record['code']:12s} DEM {record['altitude']:4d} m  "
                  f"{d:5.2f} km  dz {dz:4.0f} m  qc {record['qcStatus']}  cost {cost:.2f}",
                  file=sys.stderr)
        station = place.get("station")
        if station:
            print(f"  provincial: {station['code']} {station['name']} "
                  f"{station['distanceKm']} km  dz {abs(altitude - station['altitudeM'])} m  "
                  f"sd {station.get('stabilityK')}", file=sys.stderr)
    print("\n--pws-check: nothing written", file=sys.stderr)


def beats_provincial(place_altitude, station, chosen, chosen_km, score):
    """Whether the amateur station should displace the provincial one.

    Two conditions, and the second was added after the first picked twelve places badly.

    **Steadier**, by `pick_by_stability` — which is the measure the provincial station was itself
    chosen on, so this is a like-for-like comparison. An unmeasured amateur never displaces a
    measured provincial one; a measured amateur does displace an unmeasured provincial one, because
    evidence beats none.

    **And not worse sited**, by the same `km + dz/100` this file orders candidates with. Stability
    alone put Terenten on a station 5,6 km away and 389 m up — just inside STATION_MAX_DZ_M, which
    is the limit because four hundred metres is about four degrees of dry adiabat — in exchange for
    0,26 K of steadiness, abandoning a thermometer standing in the village. And it moved Laurein
    from a station 8 m from its own height to one 190 m away purely because the provincial had no
    score. Both are the kind of trade the height cost exists to refuse, and a steadier reading of
    the wrong air is still the wrong air.
    """
    if station is None:
        return True
    provincial_sd = station.get("stabilityK")
    if score is None:
        return False
    if provincial_sd is not None and score >= provincial_sd:
        return False
    amateur_cost = chosen_km + abs(place_altitude - int(chosen["altitude"])) * HEIGHT_COST_KM_PER_M
    provincial_cost = (
        siag_float(station["distanceKm"])
        + abs(place_altitude - int(station["altitudeM"])) * HEIGHT_COST_KM_PER_M
    )
    return amateur_cost <= provincial_cost


def pws_fill():
    """Add a `pws` to the committed catalogue and touch nothing else.

    The counterpart of `horizons.py`, which backfills only `horizon` fields on a catalogue it
    otherwise leaves alone, and for the same reason. A full run re-queries KMOS and SIAG for all 116
    and re-measures every provincial station against eight fresh weeks of ICON-D2, so the amateur
    stations would land in one commit together with whatever drifted upstream since the last run —
    and no reviewer could tell the two apart. This keeps the diff to the thing being decided.

        APEX_WU_API_KEY=... python3 tools/generate-places.py --pws-fill
    """
    if not WU_KEY:
        print("APEX_WU_API_KEY is not set; nothing to fill", file=sys.stderr)
        return
    places = json.loads(PLACES.read_text(encoding="utf-8"))
    gained, lost, kept = [], [], 0
    for n, place in enumerate(places, start=1):
        istat = place["istat"]
        lat, lon, altitude = place["lat"], place["lon"], place["altitudeM"]
        station = place.get("station")
        before = (place.get("pws") or {}).get("code")
        print(f"{n:>3}/{len(places)} {place['nameDe']}", file=sys.stderr)

        pws = None
        candidates = pws_candidates(lat, lon, altitude, istat)
        if candidates:
            chosen, chosen_km, score = pick_by_stability((lat, lon, altitude), candidates)
            time.sleep(STABILITY_PACE_S)
            provincial_sd = (station or {}).get("stabilityK")
            better = beats_provincial(altitude, station, chosen, chosen_km, score)
            if better:
                pws = {
                    "network": "wu",
                    "code": chosen["code"],
                    "name": chosen["name"],
                    "lat": round(chosen["latitude"], 6),
                    "lon": round(chosen["longitude"], 6),
                    "altitudeM": int(chosen["altitude"]),
                    "distanceKm": round(chosen_km, 2),
                    "stabilityK": None if score is None else round(score, 2),
                }
                print(f"    {chosen['code']} (sd {score}) beats "
                      f"{(station or {}).get('code')} (sd {provincial_sd})", file=sys.stderr)
            else:
                print(f"    {chosen['code']} (sd {score}) does not beat "
                      f"{(station or {}).get('code')} (sd {provincial_sd})", file=sys.stderr)

        after = (pws or {}).get("code")
        if after and not before:
            gained.append((place["nameDe"], after))
        elif before and not after:
            lost.append((place["nameDe"], before))
        elif after:
            kept += 1
        # Written even when None, so a place that lost its station loses the field rather than
        # keeping a stale one.
        if pws is None:
            place.pop("pws", None)
        else:
            place["pws"] = pws

    # A new pws needs its own skyline, exactly as a full run gives it one: StationSun asks its
    # question at the pyranometer, and a thermometer on a different shoulder has a different ridge.
    horizons.fill(places)

    # indent=1, matching what the full run writes. indent=2 would reformat all 116 records and
    # bury the one change this pass exists to make.
    PLACES.write_text(json.dumps(places, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"\n{len(gained)} gained, {len(lost)} lost, {kept} unchanged", file=sys.stderr)
    for name, code in gained:
        print(f"  + {name}: {code}", file=sys.stderr)
    for name, code in lost:
        print(f"  - {name}: {code}", file=sys.stderr)


def main():
    if "--pws-check" in sys.argv:
        pws_check([a for a in sys.argv[1:] if not a.startswith("--")])
        return
    if "--pws-fill" in sys.argv:
        pws_fill()
        return
    municipalities = fetch(ODH_MUNICIPALITIES)
    region_names = {r["Id"]: (r.get("Detail") or {}).get("de", {}).get("Title") for r in fetch(ODH_REGIONS)}
    stations = [
        s for s in fetch(SIAG_STATIONS)["rows"]
        if siag_float(s.get("latitude")) is not None and siag_float(s.get("longitude")) is not None
    ]
    print(f"{len(municipalities)} municipalities, {len(stations)} stations", file=sys.stderr)

    # Every municipality must land in a district. A missing one is a mapping to fix, not a default
    # to fall back on: a wrong district shows the wrong valley's bulletin, silently and plausibly.
    unplaced = [
        m["IstatNumber"] for m in municipalities
        if district_of(m["IstatNumber"], region_names.get(m.get("RegionId"))) is None
    ]
    if unplaced:
        print(f"No district for: {', '.join(sorted(unplaced))}", file=sys.stderr)
        sys.exit(1)

    places = []
    # Places with a real choice to make that had to be made without the models. A handful is the
    # weather; a pile of them is a rate limit, and means the run must not be committed.
    fell_back = []
    for n, m in enumerate(sorted(municipalities, key=lambda x: x["IstatNumber"]), start=1):
        istat = m["IstatNumber"]
        try:
            kmos = fetch(KMOS.format(istat=istat))["municipality"]
        except Exception as e:  # noqa: BLE001 — a municipality KMOS will not serve is left out
            print(f"{istat}: KMOS failed ({e}); skipped", file=sys.stderr)
            continue
        if not ((kmos.get("temp3") or {}).get("data")):
            print(f"{istat}: KMOS serves no forecast; skipped", file=sys.stderr)
            continue

        lat, lon, altitude = m["Latitude"], m["Longitude"], int(m["Altitude"])
        candidates = []
        for s in stations:
            d = distance_km(lat, lon, siag_float(s["latitude"]), siag_float(s["longitude"]))
            dz = abs(altitude - int(s["altitude"]))
            if d > STATION_MAX_KM or dz > STATION_MAX_DZ_M:
                continue
            candidates.append((d + dz * HEIGHT_COST_KM_PER_M, s, d, dz))
        candidates.sort(key=lambda c: c[0])

        station = None
        if candidates:
            chosen, chosen_km, score = pick_by_stability((lat, lon, altitude), candidates)
            if score is None and len(candidates) > 1:
                fell_back.append(kmos["nameDe"])
            time.sleep(STABILITY_PACE_S)
            station = {
                "code": chosen["code"],
                "name": chosen["name"],
                "lat": round(siag_float(chosen["latitude"]), 6),
                "lon": round(siag_float(chosen["longitude"]), 6),
                "altitudeM": int(chosen["altitude"]),
                # The ground distance, which is what the app shows the reader. The height difference
                # it was weighed by is the two altitudes, and needs no field of its own.
                "distanceKm": round(chosen_km, 2),
                # How steady village-minus-station is in ICON-D2, in kelvin: the number this station
                # was chosen on, kept so the choice can be reviewed without re-measuring. Null where
                # there was only one candidate or the models could not be asked.
                "stabilityK": None if score is None else round(score, 2),
            }

        # The amateur station competes on exactly the measure the provincial one was chosen by:
        # pick_by_stability, eight weeks of ICON-D2, village-minus-station. Distance and height only
        # order the candidates; what decides is which thermometer stands in the same air. It is kept
        # only where it wins, so most of the 116 keep the station they have and should.
        pws = None
        pws_here = pws_candidates(lat, lon, altitude, istat)
        if pws_here:
            chosen_p, chosen_p_km, score_p = pick_by_stability((lat, lon, altitude), pws_here)
            time.sleep(STABILITY_PACE_S)
            provincial_sd = (station or {}).get("stabilityK")
            better = beats_provincial(altitude, station, chosen_p, chosen_p_km, score_p)
            if better:
                pws = {
                    "network": "wu",
                    "code": chosen_p["code"],
                    "name": chosen_p["name"],
                    "lat": round(chosen_p["latitude"], 6),
                    "lon": round(chosen_p["longitude"], 6),
                    "altitudeM": int(chosen_p["altitude"]),
                    "distanceKm": round(chosen_p_km, 2),
                    "stabilityK": None if score_p is None else round(score_p, 2),
                }
                print(f"    {chosen_p['code']} (sd {score_p}) beats "
                      f"{(station or {}).get('code')} (sd {provincial_sd})", file=sys.stderr)
            else:
                print(f"    {chosen_p['code']} (sd {score_p}) does not beat "
                      f"{(station or {}).get('code')} (sd {provincial_sd})", file=sys.stderr)

        places.append({
            "istat": istat,
            "nameDe": kmos["nameDe"],
            "nameIt": kmos["nameIt"] or kmos["nameDe"],
            "nameEn": kmos.get("nameEn") or kmos["nameDe"],
            "lat": round(lat, 6),
            "lon": round(lon, 6),
            "altitudeM": int(m["Altitude"]),
            "district": district_of(istat, region_names.get(m.get("RegionId"))),
            "station": station,
            "pws": pws,
        })
        print(f"  {n:>3}/{len(municipalities)} {istat} {kmos['nameDe']}", file=sys.stderr)

    if len(fell_back) > STABILITY_MAX_FALLBACKS:
        print(
            f"\n{len(fell_back)} places with more than one candidate were chosen without a "
            f"measurement: {', '.join(fell_back)}\nThat is a rate limit, not weather. Nothing was "
            "written; wait and run again.",
            file=sys.stderr,
        )
        sys.exit(1)

    # The skylines, last, because they need the chosen stations. A catalogue must never be written
    # without them: a place with no `horizon` silently falls back to a flat horizon, which in this
    # province is the horizon of somewhere else entirely. See tools/horizons.py.
    print("\nmeasuring skylines", file=sys.stderr)
    horizons.fill(places)

    out = PLACES
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(places, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

    without = [p["nameDe"] for p in places if p["station"] is None]
    print(f"\nwrote {len(places)} places to {out}", file=sys.stderr)
    print(
        f"{len(without)} without a station within {STATION_MAX_KM:.0f} km and "
        f"{STATION_MAX_DZ_M} m: {', '.join(without)}",
        file=sys.stderr,
    )
    # The height difference is what StationDownscale has to carry, so it is worth looking at before
    # committing the file: these are the places whose reading travels furthest up or down.
    with_station = [p for p in places if p["station"]]
    steepest = sorted(with_station, key=lambda p: -abs(p["altitudeM"] - p["station"]["altitudeM"]))[:5]
    for p in steepest:
        dz = p["altitudeM"] - p["station"]["altitudeM"]
        print(
            f"  steepest: {p['nameDe']} {p['altitudeM']} m ← {p['station']['name']} "
            f"{p['station']['altitudeM']} m ({dz:+d} m, {p['station']['distanceKm']} km)",
            file=sys.stderr,
        )
    # And the places whose station is the least steady of those chosen: the ones where no candidate
    # really shares the village's air, and where the hero will lean on the consensus most often.
    measured = [p for p in with_station if p["station"]["stabilityK"] is not None]
    print(
        f"  {len(with_station) - len(measured)} chosen without a measurement, "
        f"{len(fell_back)} of them with a choice to make: {', '.join(fell_back) or 'none'}",
        file=sys.stderr,
    )
    for p in sorted(measured, key=lambda p: -p["station"]["stabilityK"])[:5]:
        print(
            f"  least steady: {p['nameDe']} ← {p['station']['name']} "
            f"(sd {p['station']['stabilityK']} K, {p['station']['distanceKm']} km)",
            file=sys.stderr,
        )
    for d in sorted(DISTRICT_NAMES):
        members = [p["nameDe"] for p in places if p["district"] == d]
        print(f"  {d} {DISTRICT_NAMES[d]}: {len(members)}", file=sys.stderr)


if __name__ == "__main__":
    main()
