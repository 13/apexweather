#!/usr/bin/env python3
"""Generates app/src/main/assets/places.json, the catalogue of South Tyrolean municipalities.

Run by hand, never in CI, and review the output before committing it:

    python3 tools/generate-places.py

Three upstreams are combined:

  * Open Data Hub /v1/Municipality  — the ISTAT code, coordinates and altitude of all 116.
  * SIAG KMOS, once per ISTAT code  — the German, Italian and English names, and proof that the
    code actually serves a forecast. A code that returns no series is left out of the catalogue.
  * SIAG /api/v2/station            — the nearest weather station, if one is close enough.

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
import urllib.request

ODH_MUNICIPALITIES = "https://tourism.opendatahub.com/v1/Municipality?language=de"
ODH_REGIONS = "https://tourism.opendatahub.com/v1/Region?language=de"
SIAG_STATIONS = "https://api-weather.services.siag.it/api/v2/station?categoryId=1&visibility=11"
KMOS = "https://api-weather.services.siag.it/api/v2/municipality/MunicipalityBulletin/{istat}"

# Beyond this a station is not speaking about the place any more; 20 km in this province can cross
# a ridge. A place with no station within the cap shows the consensus and no measurements card.
STATION_MAX_KM = 20.0

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

        lat, lon = m["Latitude"], m["Longitude"]
        nearest, nearest_km = None, None
        for s in stations:
            d = distance_km(lat, lon, siag_float(s["latitude"]), siag_float(s["longitude"]))
            if nearest_km is None or d < nearest_km:
                nearest, nearest_km = s, d

        station = None
        if nearest is not None and nearest_km <= STATION_MAX_KM:
            station = {
                "code": nearest["code"],
                "name": nearest["name"],
                "lat": round(siag_float(nearest["latitude"]), 6),
                "lon": round(siag_float(nearest["longitude"]), 6),
                "altitudeM": int(nearest["altitude"]),
                "distanceKm": round(nearest_km, 2),
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

    out = pathlib.Path("app/src/main/assets/places.json")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(places, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

    without = [p["nameDe"] for p in places if p["station"] is None]
    print(f"\nwrote {len(places)} places to {out}", file=sys.stderr)
    print(f"{len(without)} without a station within {STATION_MAX_KM:.0f} km: {', '.join(without)}", file=sys.stderr)
    for d in sorted(DISTRICT_NAMES):
        members = [p["nameDe"] for p in places if p["district"] == d]
        print(f"  {d} {DISTRICT_NAMES[d]}: {len(members)}", file=sys.stderr)


if __name__ == "__main__":
    main()
