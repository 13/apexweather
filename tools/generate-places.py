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

The weather district is the one datum no upstream provides: /api/v2/district lists the seven
districts by name and nothing about their membership, the municipality record's Region is an
unnamed tourism region, and the district record's TourismVereinIds is null. It is derived here
instead, from the tourism region each municipality belongs to, with an override list for the
municipalities where the two divisions disagree. That is far less to review than 116 hand-typed
rows, and the overrides are where the judgement actually lives.
"""
import json
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


def main():
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

    out = pathlib.Path("app/src/main/assets/places.json")
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
