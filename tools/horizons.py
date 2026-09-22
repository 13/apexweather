#!/usr/bin/env python3
"""The skyline around a place, so the app can say when the sun actually goes.

    python3 tools/horizons.py            # backfill app/src/main/assets/places.json in place
    python3 tools/horizons.py --check    # recompute three places and print them, changing nothing

In a province that is all valleys, the astronomical sunrise and sunset are the wrong times by a lot.
Dorf Tirol sits under the Texelgruppe: ground reaching 2782 m stands about ten kilometres north of
it, and the sun clears the eastern skyline long after it has "risen" and is gone from the village
well before it "sets". Everything in the app that reads a sun time inherits that error — the sky's
own colours turn to dusk while the village has been in shadow for the better part of an hour, and
`StationSun` has to forbid itself from ever concluding cloud from a dark pyranometer, because with a
flat horizon it cannot tell shade from overcast.

So the skyline is measured once, offline, and shipped with the catalogue.

**Where the ground comes from.** SRTM one-arc-second tiles (about 30 m), from the public
elevation-tiles mirror, cached under `tools/.dem-cache` and read straight out of the `.hgt` grid.
The first run downloads six to nine tiles of about 17 MB each; every run after that is offline and
takes seconds.

An elevation *API* was tried first and is the wrong tool. Open-Meteo answers a hundred coordinates
in under a fifth of a second and returns 429 long before a hundred and seventy points have had their
skylines measured, because a skyline is tens of thousands of samples and there is no batch size at
which that is a polite thing to ask of a free service. A file that is downloaded once is.

**How.** For each point, the ground is walked outward along HORIZON_BEARINGS bearings, and the
horizon angle for a bearing is the largest elevation angle any sample along it subtends — corrected
for the curvature of the earth and for standard atmospheric refraction. The result is one angle per
bearing, in tenths of a degree.

**Why five degrees between bearings.** The sun's azimuth moves about fifteen degrees an hour, so a
fifteen-degree grid would put a ridge crest up to half an hour from where it is. Five degrees is
twenty minutes of sun travel, and the app interpolates between neighbours, so what is left is
minutes against the hour a flat horizon costs. It is not exact and is not claimed to be: the DEM is
bare earth, so a forest or a building on the ridge is not in it, and a spire narrower than the step
along a ray can be stepped over.

**The observer stands on the DEM, not on the catalogue's altitude.** The catalogue altitude is the
municipality's official one — a town hall, usually — and subtracting it from an SRTM sample would be
differencing two different surfaces. Both ends of every angle here come from the same ground.

The stations get their own profile, because a station is somewhere else and usually much lower: the
thermometer Dorf Tirol reads sits 264 m below it on the valley floor, where the sun goes earlier
again. `StationSun` needs the station's skyline, not the village's.

Re-run it whenever the catalogue is regenerated. It is idempotent and touches nothing but the
`horizon` fields.
"""
import array
import gzip
import json
import math
import pathlib
import sys
import urllib.request

TILES = "https://s3.amazonaws.com/elevation-tiles-prod/skadi/{ns}{lat:02d}/{ns}{lat:02d}{ew}{lon:03d}.hgt.gz"
CACHE = pathlib.Path("tools/.dem-cache")

# One sample every five degrees of bearing, from true north, clockwise. See the module docstring.
HORIZON_BEARINGS = 72
BEARING_STEP_DEG = 360 // HORIZON_BEARINGS

# How far out the skyline is looked for. Thirty kilometres is past the far wall of any valley here,
# and a ridge beyond it would have to be enormous to out-subtend the near one — the Ortler is 3905 m
# and 60 km from Bozen, which is about 3,5°, against the 10 to 15° the valley's own sides make.
HORIZON_MAX_M = 30000.0

# The step along a ray: never coarser than a hundredth of the distance already travelled, so the
# angular resolution stays roughly constant, and never finer than the DEM's own 30 m, because a
# smaller step only samples the same cell twice.
HORIZON_MIN_STEP_M = 30.0
HORIZON_STEP_FRACTION = 0.01

# Earth radius, and the refraction that makes distant ground look higher than geometry says. k=0.13
# is the standard coefficient for visible light near the surface; it shrinks the curvature
# correction by about an eighth. Both matter little at these ranges — six metres of drop at ten
# kilometres — and are in because leaving them out would be a choice rather than a simplification.
EARTH_RADIUS_M = 6371000.0
REFRACTION_K = 0.13

# SRTM's own "no data". It appears over water and in a few alpine voids, and must never be read as a
# height of minus thirty-two thousand metres.
VOID = -32768

_tiles = {}


def tile(lat, lon):
    """The one-degree DEM tile containing (lat, lon), downloaded and cached on first use."""
    key = (math.floor(lat), math.floor(lon))
    if key in _tiles:
        return _tiles[key]
    la, lo = key
    name = f"{'N' if la >= 0 else 'S'}{abs(la):02d}{'E' if lo >= 0 else 'W'}{abs(lo):03d}.hgt.gz"
    path = CACHE / name
    if not path.exists():
        CACHE.mkdir(parents=True, exist_ok=True)
        url = TILES.format(ns="N" if la >= 0 else "S", lat=abs(la), ew="E" if lo >= 0 else "W", lon=abs(lo))
        print(f"  downloading {name}", file=sys.stderr)
        with urllib.request.urlopen(url, timeout=300) as r:
            path.write_bytes(r.read())
    raw = gzip.open(path, "rb").read()
    side = math.isqrt(len(raw) // 2)
    if side * side * 2 != len(raw):
        raise RuntimeError(f"{name} is not a square grid: {len(raw)} bytes")
    grid = array.array("h")
    grid.frombytes(raw)
    # .hgt is big-endian; every machine this is run on is not.
    if sys.byteorder == "little":
        grid.byteswap()
    _tiles[key] = (grid, side, la, lo)
    return _tiles[key]


def ground(lat, lon):
    """Height in metres at (lat, lon), or None over a void."""
    grid, side, la, lo = tile(lat, lon)
    row = int(round((la + 1 - lat) * (side - 1)))
    col = int(round((lon - lo) * (side - 1)))
    if not (0 <= row < side and 0 <= col < side):
        return None
    h = grid[row * side + col]
    return None if h == VOID else float(h)


def horizon_profile(lat, lon):
    """Skyline elevation angles in tenths of a degree, one per bearing from true north.

    Never negative: a place on a summit looks down on everything around it, and a negative horizon
    would tell the app the sun rises before it does. Zero — the flat, astronomical horizon — is the
    floor, and is exactly the answer the app had everywhere before this existed.
    """
    observer = ground(lat, lon)
    if observer is None:
        raise RuntimeError(f"no ground height at ({lat}, {lon})")
    # Metres per degree here, which is all the geometry a ray thirty kilometres long needs: over that
    # distance the difference between this and a proper great circle is a few metres of position and
    # nothing at all of angle.
    m_per_deg_lat = 111132.0
    m_per_deg_lon = 111320.0 * math.cos(math.radians(lat))

    profile = []
    for i in range(HORIZON_BEARINGS):
        b = math.radians(i * BEARING_STEP_DEG)
        north, east = math.cos(b), math.sin(b)
        best = 0.0
        d = HORIZON_MIN_STEP_M
        while d <= HORIZON_MAX_M:
            h = ground(lat + north * d / m_per_deg_lat, lon + east * d / m_per_deg_lon)
            if h is not None:
                # The far ground curves away, and refraction bends the line of sight back onto it.
                drop = d * d / (2.0 * EARTH_RADIUS_M) * (1.0 - REFRACTION_K)
                best = max(best, math.degrees(math.atan2(h - observer - drop, d)))
            d += max(HORIZON_MIN_STEP_M, d * HORIZON_STEP_FRACTION)
        profile.append(int(round(best * 10)))
    return profile


def describe(name, profile):
    """The numbers worth reading before committing a catalogue: where the walls are."""
    def around(deg):
        """The highest skyline within thirty degrees of a bearing — the wall the sun has to clear."""
        i = deg // BEARING_STEP_DEG
        return max(profile[(i + k) % HORIZON_BEARINGS] for k in range(-6, 7))
    peak = max(profile)
    return (f"{name}: E {around(90) / 10:.1f}° W {around(270) / 10:.1f}° "
            f"(highest {peak / 10:.1f}° at {profile.index(peak) * BEARING_STEP_DEG}°)")


def fill(places, limit=None):
    """Give every place and every station in [places] its own `horizon`, in place.

    Called by `generate-places.py` as the last step of a full run, so a regenerated catalogue is
    never written without skylines, and by [main] on its own to backfill the committed one.
    """
    # Stations are shared: the 116 places read about 42 thermometers between them, and a skyline is
    # a property of a point rather than of who is looking at it.
    # Both kinds of station, in one pool. An amateur station has a skyline for exactly the reason a
    # provincial one does — StationSun asks its question at the pyranometer — and a thermometer
    # 490 m from the village on a different shoulder has a different ridge in front of it. They
    # share a pool because a code is a point, whichever network issued it.
    by_station = {}
    for p in places:
        for key in ("station", "pws"):
            if p.get(key):
                by_station.setdefault(p[key]["code"], dict(p[key]))

    todo = places[:limit] if limit else places
    for n, p in enumerate(todo, start=1):
        p["horizon"] = horizon_profile(p["lat"], p["lon"])
        print(f"  {n:>3}/{len(todo)} {describe(p['nameDe'], p['horizon'])}", file=sys.stderr)

    stations = list(by_station.values())[:limit] if limit else list(by_station.values())
    for n, st in enumerate(stations, start=1):
        st["horizon"] = horizon_profile(st["lat"], st["lon"])
        by_station[st["code"]] = st
        print(f"  station {n:>3}/{len(stations)} {describe(st['name'], st['horizon'])}", file=sys.stderr)
    # One thermometer is read by many places, so the profile computed once above is copied onto each
    # of their own station objects.
    for p in places:
        for key in ("station", "pws"):
            if p.get(key):
                measured = by_station.get(p[key]["code"], {}).get("horizon")
                if measured is not None:
                    p[key]["horizon"] = measured
    return places


def main():
    check = "--check" in sys.argv
    path = pathlib.Path("app/src/main/assets/places.json")
    places = json.loads(path.read_text(encoding="utf-8"))
    fill(places, limit=3 if check else None)

    if check:
        print("\n--check: nothing written", file=sys.stderr)
        return
    path.write_text(json.dumps(places, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    flat = [p["nameDe"] for p in places if max(p["horizon"]) < 20]
    print(f"\nwrote {len(places)} horizons to {path}", file=sys.stderr)
    print(f"  {len(flat)} places whose whole skyline is under 2°: {', '.join(flat) or 'none'}", file=sys.stderr)
    for p in sorted(places, key=lambda x: -max(x["horizon"]))[:5]:
        print(f"  steepest: {describe(p['nameDe'], p['horizon'])}", file=sys.stderr)


if __name__ == "__main__":
    main()
