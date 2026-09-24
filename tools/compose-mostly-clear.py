#!/usr/bin/env python3
"""Composes the "Heiter" (mostly clear) icons from Meteocons' own parts.

Run by hand from the repository root, then run svg2vector.py, and look at the icons before
committing them:

    python3 tools/compose-mostly-clear.py && python3 tools/svg2vector.py

Meteocons 3.0 has no mostly-sunny drawing. Its `partly-cloudy-day` is a small sun almost hidden
behind a large cloud, which was what the app drew for "Heiter" until 2026-09-24 and which read as
*more* cloud than sun — the complaint. So the clear-sky glyph is kept whole and a small cloud is
tucked into its lower right, the sun cut away behind it the way Meteocons cuts every sun behind
every cloud.

Scaling the cloud is the one thing that cannot be done by transform. Meteocons draws a cloud as a
filled ring 4 units wide, and a ring scaled by 0.72 is a ring 2.9 units wide beside a sun whose
stroke is still 4: two line weights in one glyph. So only the cloud's **outer edge** is scaled, and
the ring's inner edge is rebuilt 4 units inside it. The cut-out behind the cloud is Meteocons' own
halo outline from the same file, scaled with the cloud: its gap comes out at 2.9 units rather than
4, which nobody can see at 24 dp, and offsetting outward instead folds into loops at the notches
between the cloud's lobes.

Everything is written out as plain coordinates — svg2vector.py understands `translate` and nothing
else, deliberately, and a full-canvas mask with the cloud subtracted under evenodd, which is exactly
the form every Meteocons mask already has.
"""
import importlib.util
import math
import pathlib
import re
import sys

ROOT = pathlib.Path("tools/meteocons")

# Where the small cloud goes, found by eye against the clear-sky sun (see the 2026-09-24 session):
# 0.72 of Meteocons' size, its right edge at 106 and its bottom at 104, the sun moved 12 up and 12
# left so the cloud covers the lower right of its disc rather than only a ray.
CLOUD_SCALE = 0.72
CLOUD_RIGHT = 106.0
CLOUD_BOTTOM = 104.0
BODY_SHIFT = -12.0

# Meteocons' ring width.
RING = 4.0


def load_converter():
    spec = importlib.util.spec_from_file_location("svg2vector", "tools/svg2vector.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def cloud_paths():
    """
    partly-cloudy-day's cloud as path data: the outer edge of its ring, and the halo its mask cuts
    out of the sun, which runs 4 units outside that edge.
    """
    svg = (ROOT / "partly-cloudy-day.svg").read_text()
    ring = re.search(r'id="Cloud_2"[^>]*\bd="([^"]+)"', svg).group(1)
    mask = re.search(r'id="Subtract"[^>]*\bd="([^"]+)"', svg).group(1)
    return ring[: ring.index("Z") + 1], mask[mask.index("Z") + 1:]  # after the full-canvas rectangle


def flattened(d, points):
    """[d] as a closed polygon without repeated points, curves flattened by svg2vector's own code."""
    pts = []
    for p in points(d):
        if not pts or math.dist(p, pts[-1]) > 0.05:
            pts.append(p)
    if math.dist(pts[0], pts[-1]) < 0.05:
        pts.pop()
    return pts


def transformed(d, scale, tx, ty):
    """
    [d] scaled and moved, its curves kept as curves. Meteocons' cloud uses absolute M, L, H, V, C
    and Z only; anything else raises rather than being moved wrongly.
    """
    out = []
    tokens = TOKEN.findall(d)
    i = 0
    cmd = None
    while i < len(tokens):
        if tokens[i].isalpha():
            cmd = tokens[i]
            i += 1
            out.append(cmd)
            if cmd == "Z":
                continue
        if cmd in ("M", "L", "C"):
            x, y = float(tokens[i]), float(tokens[i + 1])
            out.append(f"{x * scale + tx:.2f} {y * scale + ty:.2f}")
            i += 2
        elif cmd == "H":
            out.append(f"{float(tokens[i]) * scale + tx:.2f}")
            i += 1
        elif cmd == "V":
            out.append(f"{float(tokens[i]) * scale + ty:.2f}")
            i += 1
        else:
            raise SystemExit(f"path command {cmd!r} is not handled")
    return " ".join(out).replace(" Z", "Z")


TOKEN = re.compile(r"[MLHVCZ]|-?\d*\.?\d+(?:[eE][-+]?\d+)?")


def simplified(pts, tolerance=0.1):
    """
    Ramer-Douglas-Peucker on a closed polygon: points no further than [tolerance] units from the
    line their neighbours already draw are dropped. The flattened inner edge has a point every
    fraction of a unit, which is what made the first build of these icons a 9 000-character path
    that lint refuses; a tenth of a unit is a fortieth of a pixel at 24 dp.
    """
    def rdp(seq):
        if len(seq) < 3:
            return seq
        (ax, ay), (bx, by) = seq[0], seq[-1]
        length = math.hypot(bx - ax, by - ay) or 1e-9
        far, index = 0.0, 0
        for k in range(1, len(seq) - 1):
            px, py = seq[k]
            dist = abs((bx - ax) * (ay - py) - (ax - px) * (by - ay)) / length
            if dist > far:
                far, index = dist, k
        if far <= tolerance:
            return [seq[0], seq[-1]]
        return rdp(seq[: index + 1])[:-1] + rdp(seq[index:])

    half = len(pts) // 2
    return rdp(pts[: half + 1])[:-1] + rdp(pts[half:] + pts[:1])[:-1]


def signed_area(pts):
    return sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(pts, pts[1:] + pts[:1])) / 2


def offset(pts, distance):
    """
    Moves a closed polygon's edge outward by [distance] (inward where negative), vertex by vertex
    along the bisector of the two edge normals, with the miter capped.

    Only ever used inward here. Outward, the offset folds into small loops at the cloud's sharp
    notches, which is why the halo is Meteocons' own outline scaled instead (see [compose]).
    """
    outward = -1 if signed_area(pts) < 0 else 1  # SVG's y points down
    n = len(pts)
    moved = []
    for i in range(n):
        a, b, c = pts[i - 1], pts[i], pts[(i + 1) % n]
        normals = []
        for p, q in ((a, b), (b, c)):
            dx, dy = q[0] - p[0], q[1] - p[1]
            length = math.hypot(dx, dy)
            normals.append((dy / length * outward, -dx / length * outward))
        bx, by = normals[0][0] + normals[1][0], normals[0][1] + normals[1][1]
        blen = math.hypot(bx, by) or 1.0
        bx, by = bx / blen, by / blen
        cos = max(bx * normals[0][0] + by * normals[0][1], 0.5)  # miter capped at twice the distance
        moved.append((b[0] + bx * distance / cos, b[1] + by * distance / cos))
    return moved


def placement(edge):
    """The scale and offset that put the cloud [edge] in the lower right of the canvas."""
    right = max(p[0] for p in edge)
    bottom = max(p[1] for p in edge)
    return CLOUD_SCALE, CLOUD_RIGHT - right * CLOUD_SCALE, CLOUD_BOTTOM - bottom * CLOUD_SCALE


def subpath(pts):
    head, *rest = pts
    return f"M{head[0]:.2f} {head[1]:.2f}" + "".join(f"L{x:.2f} {y:.2f}" for x, y in rest) + "Z"


def compose(name, body):
    points = load_converter().points
    edge_d, halo_d = cloud_paths()
    raw = flattened(edge_d, points)
    scale, tx, ty = placement(raw)
    edge = [(x * scale + tx, y * scale + ty) for x, y in raw]
    ring = transformed(edge_d, scale, tx, ty) + subpath(simplified(offset(edge, -RING)))
    halo = transformed(halo_d, scale, tx, ty)
    return f"""<svg viewBox="0 0 128 128" fill="none" xmlns="http://www.w3.org/2000/svg">
<!-- Generated by tools/compose-mostly-clear.py from Meteocons' clear-{name} and partly-cloudy-day. Do not edit by hand. -->
<g id="mostly-clear-{name}">
<g id="Sky">
<g id="Mask group">
<mask id="mask_mostly_clear_{name}" style="mask-type:alpha" maskUnits="userSpaceOnUse" x="0" y="0" width="128" height="128">
<g id="Cloud Mask">
<path id="Subtract" fill-rule="evenodd" clip-rule="evenodd" d="M128 0H0V128H128V0Z{halo}" fill="black"/>
</g>
</mask>
<g mask="url(#mask_mostly_clear_{name})">
{body}
</g>
</g>
</g>
<g id="Clouds">
<path id="Cloud" fill-rule="evenodd" clip-rule="evenodd" d="{ring}" fill="currentColor"/>
</g>
</g>
</svg>
"""


def sun():
    svg = (ROOT / "clear-day.svg").read_text()
    core = re.search(r"<circle[^>]*/>", svg).group(0)
    cx = float(re.search(r'cx="([\d.]+)"', core).group(1)) + BODY_SHIFT
    cy = float(re.search(r'cy="([\d.]+)"', core).group(1)) + BODY_SHIFT
    core = re.sub(r'cx="[\d.]+"', f'cx="{cx:.4g}"', core)
    core = re.sub(r'cy="[\d.]+"', f'cy="{cy:.4g}"', core)
    rays = re.search(r'<path id="Rays"[^>]*/>', svg).group(0)
    rays = rays.replace("<path ", f'<path transform="translate({BODY_SHIFT:g}, {BODY_SHIFT:g})" ', 1)
    return f'<g id="Sun">\n{core}\n{rays}\n</g>'


def moon():
    svg = (ROOT / "clear-night.svg").read_text()
    path = re.search(r'<path id="Moon_2"[^>]*/>', svg).group(0)
    path = path.replace("<path ", f'<path transform="translate({BODY_SHIFT:g}, {BODY_SHIFT:g})" ', 1)
    return f'<g id="Moon">\n{path}\n</g>'


def main():
    if not ROOT.is_dir():
        raise SystemExit(f"{ROOT} not found; run this from the repository root")
    for name, body in (("day", sun()), ("night", moon())):
        out = ROOT / f"mostly-clear-{name}.svg"
        out.write_text(compose(name, body))
        print(f"  wrote {out}", file=sys.stderr)


if __name__ == "__main__":
    main()
