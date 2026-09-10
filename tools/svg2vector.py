#!/usr/bin/env python3
"""Converts the Meteocons monochrome SVGs in tools/meteocons/ into Android VectorDrawables.

Run by hand, never in CI, and look at the icons before committing them:

    python3 tools/svg2vector.py

This is not a general SVG converter and must not be used as one. It handles exactly what these
seventeen files contain, and refuses anything else rather than guessing:

  * <path>, <circle> and <rect>, with fill, stroke, stroke-width, stroke-linecap, stroke-linejoin
    and stroke-miterlimit
  * fill="currentColor", which becomes black and is tinted at the use site, as the app's icons
    always have been
  * fill-rule="evenodd"
  * transform="translate(x, y)" on a path, which becomes a <group>
  * <clipPath>, dropped where it covers the whole canvas (all seventeen do) and emitted as a
    <clip-path> otherwise
  * <mask style="mask-type:alpha"> holding exactly one shape, which becomes a <clip-path>

That last one is the only interesting case. Android has no mask. Every mask in these files is
either a full-canvas rectangle with the occluding cloud subtracted from it under
fill-rule="evenodd", or a plain rectangle covering the top of the canvas. Both are exactly a
clip-path, so nothing is approximated here.

An unhandled tag or attribute raises. If a future Meteocons release brings gradients — the fill,
flat and line styles already have them — this script will say so instead of quietly dropping them.
"""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

NS = "{http://www.w3.org/2000/svg}"

# Meteocons draws on a 128-unit canvas. The app asks for icons at 20-26 dp, and a 24 dp drawable
# scaled by the caller is what every other icon in res/drawable already is.
SIZE_DP = 24

# Every stroke and fill in the monochrome style is this, and the app tints it at the use site.
INK = "#FF000000"

KNOWN_ATTRS = {
    "path": {"d", "fill", "fill-rule", "clip-rule", "stroke", "stroke-width", "stroke-linecap",
             "stroke-linejoin", "stroke-miterlimit", "transform", "id"},
    "circle": {"cx", "cy", "r", "fill", "stroke", "stroke-width", "id"},
    "rect": {"x", "y", "width", "height", "fill", "id"},
    "g": {"clip-path", "mask", "id"},
    "svg": {"viewBox", "fill", "xmlns", "width", "height"},
    "mask": {"id", "style", "maskUnits", "x", "y", "width", "height"},
    "clipPath": {"id"},
    "defs": {"id"},
}


def fail(msg):
    raise SystemExit(f"svg2vector: {msg}")


def num(v):
    """Formats a number the way the rest of the drawables in this repo are written."""
    f = float(v)
    return f"{f:g}"


def tag_of(e):
    return e.tag.split("}")[-1]


def check(e):
    t = tag_of(e)
    if t not in KNOWN_ATTRS:
        fail(f"unhandled element <{t}>; this converter is deliberately narrow, see its docstring")
    unknown = set(e.attrib) - KNOWN_ATTRS[t]
    if unknown:
        fail(f"<{t}> carries attributes this converter does not handle: {sorted(unknown)}")


def rect_path(e):
    x, y = float(e.get("x", 0)), float(e.get("y", 0))
    w, h = float(e.get("width")), float(e.get("height"))
    return f"M{num(x)},{num(y)}h{num(w)}v{num(h)}h{num(-w)}z"


def circle_path(e):
    cx, cy, r = float(e.get("cx")), float(e.get("cy")), float(e.get("r"))
    return (f"M{num(cx - r)},{num(cy)}"
            f"a{num(r)},{num(r)} 0 1,0 {num(2 * r)},0"
            f"a{num(r)},{num(r)} 0 1,0 {num(-2 * r)},0z")


def shape_path(e):
    """The `d` of any of the three shapes, as one path string."""
    t = tag_of(e)
    if t == "path":
        return e.get("d")
    if t == "rect":
        return rect_path(e)
    if t == "circle":
        return circle_path(e)
    fail(f"<{t}> is not a shape")


def covers_canvas(d, w, h):
    """True for a rectangle path that is the whole viewBox, which is a clip worth dropping."""
    return d.replace(" ", "").lower() in {
        rect_path(ET.Element("rect", {"width": str(w), "height": str(h)})).replace(" ", "").lower(),
        f"m0,0h{num(w)}v{num(h)}h{num(-w)}z",
    }


class Converter:
    def __init__(self, root):
        self.root = root
        vb = root.get("viewBox")
        if not vb:
            fail("the <svg> has no viewBox")
        parts = [float(p) for p in vb.replace(",", " ").split()]
        if len(parts) != 4 or parts[0] != 0 or parts[1] != 0:
            fail(f"viewBox {vb!r} is not a 0-origin box; not handled")
        self.w, self.h = parts[2], parts[3]
        self.clips = {}
        self.masks = {}
        for e in root.iter():
            t = tag_of(e)
            if t == "clipPath":
                self.clips[e.get("id")] = self._single_shape(e, "clipPath")
            elif t == "mask":
                if "alpha" not in (e.get("style") or ""):
                    fail(f"mask {e.get('id')!r} is not mask-type:alpha; not handled")
                self.masks[e.get("id")] = self._single_shape(e, "mask")

    def _single_shape(self, holder, kind):
        """A clipPath or mask must hold exactly one shape; anything else has no Android equivalent."""
        shapes = [e for e in holder.iter() if tag_of(e) in ("path", "rect", "circle") and e is not holder]
        if len(shapes) != 1:
            fail(f"{kind} {holder.get('id')!r} holds {len(shapes)} shapes; only one can become a clip-path")
        s = shapes[0]
        even = s.get("fill-rule") == "evenodd" or s.get("clip-rule") == "evenodd"
        return shape_path(s), even

    # ---- emitting ----

    def convert(self, name):
        out = [
            '<?xml version="1.0" encoding="utf-8"?>',
            f'<!-- {name}, from Meteocons (MIT). Generated by tools/svg2vector.py; do not edit by hand. -->',
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
            f'    android:width="{SIZE_DP}dp" android:height="{SIZE_DP}dp"',
            f'    android:viewportWidth="{num(self.w)}" android:viewportHeight="{num(self.h)}">',
        ]
        out += self.children(self.root, indent=1)
        out.append("</vector>")
        out.append("")
        return "\n".join(out)

    def children(self, parent, indent):
        lines = []
        for e in parent:
            check(e)
            t = tag_of(e)
            if t in ("defs", "mask", "clipPath"):
                continue  # referenced by id, emitted where they are used
            if t == "g":
                lines += self.group(e, indent)
            elif t in ("path", "rect", "circle"):
                lines += self.shape(e, indent)
            else:
                fail(f"unhandled element <{t}>")
        return lines

    def _ref(self, value):
        m = re.fullmatch(r"url\(#(.+)\)", (value or "").strip())
        if not m:
            fail(f"expected a url(#id) reference, got {value!r}")
        return m.group(1)

    def group(self, e, indent):
        pad = "    " * indent
        clips = []
        if "clip-path" in e.attrib:
            d, even = self.clips[self._ref(e.get("clip-path"))]
            if not covers_canvas(d, self.w, self.h):
                clips.append((d, even))
        if "mask" in e.attrib:
            clips.append(self.masks[self._ref(e.get("mask"))])
        if not clips:
            return self.children(e, indent)
        lines = [f"{pad}<group>"]
        for d, even in clips:
            fill = ' android:fillType="evenOdd"' if even else ""
            lines.append(f'{pad}    <clip-path android:pathData="{d}"{fill} />')
        lines += self.children(e, indent + 1)
        lines.append(f"{pad}</group>")
        return lines

    def shape(self, e, indent):
        pad = "    " * indent
        d = shape_path(e)
        fill = e.get("fill")
        stroke = e.get("stroke")
        if fill in (None, "none") and stroke in (None, "none"):
            return []
        attrs = [f'android:pathData="{d}"']
        if fill not in (None, "none"):
            if fill != "currentColor":
                fail(f"fill {fill!r} outside a mask; only currentColor is handled")
            attrs.append(f'android:fillColor="{INK}"')
            if e.get("fill-rule") == "evenodd":
                attrs.append('android:fillType="evenOdd"')
        if stroke not in (None, "none"):
            if stroke != "currentColor":
                fail(f"stroke {stroke!r}; only currentColor is handled")
            attrs.append(f'android:strokeColor="{INK}"')
            attrs.append(f'android:strokeWidth="{num(e.get("stroke-width", 1))}"')
            if e.get("stroke-linecap"):
                attrs.append(f'android:strokeLineCap="{e.get("stroke-linecap")}"')
            if e.get("stroke-linejoin"):
                attrs.append(f'android:strokeLineJoin="{e.get("stroke-linejoin")}"')
            if e.get("stroke-miterlimit"):
                attrs.append(f'android:strokeMiterLimit="{num(e.get("stroke-miterlimit"))}"')
        body = [f"{pad}<path", f"{pad}    " + "\n{}    ".format(pad).join(attrs) + " />"]
        transform = e.get("transform")
        if not transform:
            return body
        m = re.fullmatch(r"translate\(\s*(-?[\d.]+)[ ,]+(-?[\d.]+)\s*\)", transform.strip())
        if not m:
            fail(f"transform {transform!r}; only translate(x, y) is handled")
        tx, ty = num(m.group(1)), num(m.group(2))
        inner = [f"    {line}" for line in body]
        return [f'{pad}<group android:translateX="{tx}" android:translateY="{ty}">', *inner, f"{pad}</group>"]


# Meteocons name -> the app's drawable name. Both directions of this table are load-bearing:
# WeatherIcons.kt maps a Condition to the resource name on the right.
ICONS = {
    "clear-day": "ic_wx_sun",
    "clear-night": "ic_wx_moon",
    "partly-cloudy-day": "ic_wx_sun_cloud",
    "partly-cloudy-night": "ic_wx_moon_cloud",
    "overcast-day": "ic_wx_cloud_sun",
    "overcast-night": "ic_wx_cloud_moon",
    "cloudy": "ic_wx_cloud",
    "fog-day": "ic_wx_fog",
    "fog-night": "ic_wx_fog_night",
    "drizzle": "ic_wx_drizzle",
    "rain": "ic_wx_rain",
    "extreme-rain": "ic_wx_heavy_rain",
    "sleet": "ic_wx_sleet",
    "snow": "ic_wx_snow",
    "extreme-snow": "ic_wx_heavy_snow",
    "thunderstorms-day": "ic_wx_storm",
    "thunderstorms-night": "ic_wx_storm_night",
}


def main():
    src = pathlib.Path("tools/meteocons")
    out = pathlib.Path("app/src/main/res/drawable")
    if not src.is_dir():
        fail(f"{src} not found; run this from the repository root")
    for svg_name, res_name in sorted(ICONS.items()):
        path = src / f"{svg_name}.svg"
        if not path.is_file():
            fail(f"missing {path}")
        root = ET.parse(path).getroot()
        check(root)
        xml = Converter(root).convert(svg_name)
        (out / f"{res_name}.xml").write_text(xml, encoding="utf-8")
        print(f"  {svg_name:<22} -> {res_name}.xml  ({len(xml)} bytes)", file=sys.stderr)
    print(f"\nwrote {len(ICONS)} drawables to {out}", file=sys.stderr)


if __name__ == "__main__":
    main()
