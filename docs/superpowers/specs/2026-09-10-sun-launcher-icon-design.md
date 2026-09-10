# A sun where the peak was

Design, 2026-09-10. Changes the launcher icon of Apex Weather.

## The problem

The launcher icon is the Apex Maps A-sharp glyph — two stacked chevrons — recoloured. It is a
handsome mark and it says nothing about weather. On a home screen it reads as an upward arrow, and
the app it opens is a weather app.

## What is being given up

`CLAUDE.md` recorded the glyph as deliberate: the same geometry at the same scale and translate as
the Apex Maps launcher icon, "so the two sit at identical optical size; only the palette differs".
Replacing the upper chevron ends that. The two apps will no longer be recognisably the same house.

That was put to the reader as the decision, and taken knowingly. What survives of the family is the
palette — the amber and the cool blue on the same night-sky ground — and the ridge, which is the
Apex Maps chevron unchanged.

## The mark

Everything sits in the existing `scale 0.27 / translate 27, 27.81` group, so the mark keeps its
optical size and its adaptive-icon safe margin.

| Part | Geometry | Colour |
|------|----------|--------|
| Ridge | `M10.02,176L100,151.9L189.98,176L100,100.5Z` — the lower chevron, unchanged | `#9CC9FF` |
| Sun | circle, centre (100, 74), r 38 | `#FFD166` |
| Rays | 8 strokes, r 50 to r 62, width 9, round caps, every 45° from 0° | `#FFD166` |

The upper chevron is deleted. The background colour is unchanged at `#0B1020`.

Furthest extent: rays reach y 12 and x 38..162; the ridge still spans x 10..190. Scaled and
translated that is 22.9 dp from the canvas centre vertically and 24.3 dp horizontally, inside the
33 dp adaptive-icon safe radius.

## Why the sun has rays

The first drafts were a plain disc above the ridge. In colour they work, because amber and blue
separate the two shapes. Flattened to one colour — which is exactly what Android's themed icons do —
they read as **head and shoulders**. A person, not a sunrise.

Rays fix it in both renditions, and there is room for them only because the disc is smaller than the
chevron it replaces. This was not in the first sketches; rendering the monochrome layer is what found
it, and it is the reason the monochrome layer is part of the design rather than an afterthought.

## Scope

- `app/src/main/res/drawable/ic_launcher_foreground.xml`
- `app/src/main/res/drawable/ic_launcher_monochrome.xml` — the same shapes, flat, since the themed
  icon tints every opaque pixel one colour
- `assets/apexweather-logo.svg` — kept in step with the drawables
- `CLAUDE.md` — the sentence about Apex Maps parity becomes false and is replaced

**Out of scope: `ic_notification.xml`.** At 24 dp the rays are a pixel wide and turn to mush; the
A-sharp silhouette is crisp at that size and is already verified on a device. The status bar keeps
it. This does leave the identity split between launcher and status bar, which is a deliberate trade:
a status-bar glyph has different constraints from a launcher icon, and legibility wins there.

`mipmap-anydpi-v26/ic_launcher.xml` is untouched. It must not be renamed — aapt2 then cannot find
the launcher icon, which `CLAUDE.md` already records.

## Verification

Nothing here is testable by assertion: no Roborazzi golden covers the launcher, and an icon is
correct when it looks right, not when it parses. Verification is therefore on the phone:

- install, and look at the launcher icon at its real size among other icons
- turn on themed icons and check the monochrome layer, which is the rendition that failed first
- confirm the icon still appears in the launcher at all, since a broken vector drawable fails at
  inflation rather than at build

## Risks

An icon is a matter of taste, and this one is being changed on the strength of renders rather than
of living with it. The old mark is one revert away and the palette is unchanged either way.
