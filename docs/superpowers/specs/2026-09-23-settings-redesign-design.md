# Settings joins the rest of the app

**Date:** 2026-09-23
**Status:** approved

The settings screen is the last one that looks like it came from somewhere else, and it is the
only one that has grown by accretion: fourteen controls in one flat column, separated by
all-caps headings that float equidistant between what they label and what came before.

## Why this is worth building

Two concrete faults, both visible in one screenshot taken on the phone on 2026-09-23.

**It is the only screen that paints over the sky.** `SkyBackground` is drawn once in
`AppNavigation`, behind every destination. Heute, Karte, Vergleich, Bericht and the stations
screen are all white text on `GlassCard`s over that gradient. Settings puts
`Surface(color = MaterialTheme.colorScheme.surface)` over the whole thing — an opaque slab in a
colour that belongs to no part of this app and does not change with the weather. Opening
Settings does not look like moving to another page of the same app; it looks like leaving it.

**It has no shape.** Fourteen controls, `Arrangement.spacedBy(16.dp)` throughout, so the gap
between a heading and its own control is the same as the gap between two unrelated settings.
Nothing is grouped, nothing has an icon, and the two full-width segmented rows — language and
wind — take about 270 dp between them, a third of the screen, for two settings.

Neither is a bug. Both are the reason the screen is tiring to use.

## 1 · It joins the sky

The `Surface` goes. The column sits on the shared gradient with white text, and each group of
settings is a `GlassCard`.

This is safe rather than merely consistent. `SkyContrast.darkenForWhiteText` holds every palette
to 4,5:1 against white, and `GlassCard` paints **black** at 22 % to 12 % — it can only darken
what is behind it, which is exactly why it stopped being a white wash. So a screen of white text
on glass over a held-down sky keeps the floor it was given, on a dawn sky as on a midnight one.

The title becomes the same white `headlineMedium` that `CompareScreen` and `BulletinScreen` use,
scrolling with the content. **No pinned top bar**: no screen in this app has one, Settings is a
bottom-bar destination and needs no back arrow, and being the only screen with a collapsing bar
would reintroduce exactly the inconsistency this section removes.

## 2 · Five groups, five cards

| card | holds |
|---|---|
| **Ort & Sprache** | the place row, the language segments |
| **Anzeige** | wind unit (inline), animate the sky |
| **Stationen** | private stations and its note, the key field, the key's verdict, a row into "Stationen in der Nähe" |
| **Mitteilungen** | the three switches, the summary hour, the permission hint |
| **App** | Jetzt aktualisieren, the update slot, the build lines, the attribution |

An all-caps `labelSmall` heading sits **above** each card, not between controls, so it belongs to
what follows rather than floating equidistant from both neighbours. Cards are spaced by 10 dp,
matching `HomeCardSpacing`, and `GlassCard` already pays 12 dp of its own padding on each side —
the trough arithmetic the home screen measured applies here unchanged.

**The stations row is the one addition that is not cosmetic.** "Stationen in der Nähe" is
currently reachable only from the station card four scrolls down the home screen. The Stationen
group is where somebody goes looking for it. Gated exactly as the home entrance is: a key, and a
place with a station. It navigates with a plain `navigate`, not `openTopLevel` — it is a detail
pushed onto the Settings back stack, and back returns to Settings, which is what
`NearbyStationsRoute` already does from Home.

**"Jetzt aktualisieren" stops being a full-width filled button in the middle of the page** and
becomes a row in the App card, with a refresh icon like every other row. A filled button is the
loudest thing a Material screen can contain, and it was shouting from the middle of a list of
preferences.

## 3 · Every row gets a leading icon

From `Icons.Rounded.*`, the family the navigation bar already uses (`Home`, `Map`, `Settings`).
`material-icons-extended` is already a dependency, so this adds no new one.

| row | icon |
|---|---|
| Ort | `Place` |
| Sprache | `Language` |
| Windeinheit | `Air` |
| Himmel animieren | `AutoAwesome` |
| Private Wetterstationen | `Thermostat` |
| Stationen in der Nähe | `Sensors` |
| Morgenübersicht | `WbSunny` |
| Regen zieht auf | `WaterDrop` |
| Unwettermeldungen | `Warning` |
| Jetzt aktualisieren | `Refresh` |
| Über | `Info` |

The icon column is what turns a list of sentences into a form that can be scanned, and it is the
single largest change to how the screen reads. Icons are decorative — `contentDescription = null`
— because every one of them sits beside a label that already says the same thing, and a screen
reader announcing "Ort-Symbol, Ort" is worse than silence.

## 4 · Wind goes inline

`WindUnit` is two options and sits on its label's row as a compact pair. `LanguageSetting` keeps a
full-width row of four: four options worth reading do not fit beside a label, and squeezing them
would hit `CompactLabel`'s ceiling at a 2x font scale, which is the exact failure that rule exists
for. Worth about 110 dp.

Both keep `icon = {}` — Material reserves about 24 dp for a tick in *every* segment whether shown
or not, and the fill already says which one is on.

## 5 · The file splits

`SettingsScreen.kt` is 407 lines and this adds to it. Four files, by responsibility:

- `SettingsScreen.kt` — the `@Composable` that takes the ViewModel, plus the notification
  permission plumbing, which belongs to this screen and nothing else.
- `SettingsContent.kt` — the page: the heading, the five groups, the order they come in.
- `SettingsRows.kt` — the primitives every group uses: `SettingRow` (icon, label, optional
  supporting line, trailing slot), `SwitchRow`, `NavRow`, `SegmentedRow`.
- `NotificationSettings.kt` — its own group, including the hour stepper and the permission hint.

## What must not change

**Every existing `testTag` survives verbatim**: `settings_screen`, `settings_place`,
`setting_amateur_stations`, `setting_wu_key`, `wu_key_verdict`, `notify_summary`, `notify_rain`,
`notify_warning`, `notify_summary_hour`, `notify_hour_up`, `notify_hour_down`,
`notify_permission_hint`, `notify_grant`, `about_build`, `refresh_now`. Thirteen instrumented
cases drive those, and a redesign that renamed them would silently stop testing what it claims
to. `SettingsContentTest` must pass **unchanged** — that is the gate on this whole spec.

And the behaviour each of those guards keeps its reason:

- The key field holds its own text and never reads back its own write. A 32-character key typed
  one character at a time arrived reordered and one short, three times out of three, when `value`
  came from `settings.wuApiKey`.
- The key field appears only while private stations are on, and the verdict only under the field.
- The summary hour wraps: 0 steps down to 23, because a reader stepping down from midnight means
  late evening.
- The permission hint waits until something has been switched on.
- The build lines are selectable, so an exact build can be copied into a bug report.
- The update row stays a slot, so `update/` remains removable in one piece — the permission it
  needs is restricted on the Play Store.

## Testing

`SettingsContentTest` unchanged, plus new instrumented cases:

- each of the five groups renders;
- the stations row appears with a key and a station, and is absent without a key;
- tapping it reports;
- the whole screen at **font scale 2,0**, which is where the inline wind pair could break, and
  where the navigation bar's `CompactLabel` ceiling was found.

Then the phone, at 1x and 2x, in all three languages, over a light sky and a dark one.

## What is deliberately not here

- **No new settings.** This is the same fourteen controls, arranged.
- **No sub-screens.** A short top-level list navigating into five detail pages was considered and
  refused: it is four new destinations and a tap between the reader and every setting, for
  fourteen controls that fit on two screens.
- **No pinned or collapsing top bar**, for the reason in section 1.
- **No change to the notification hour stepper.** `+`/`−` is tested, wraps correctly and works at
  every font scale; a time picker would be a new dialog for a setting that has 24 values.
