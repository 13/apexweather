# Share Today's Weather as an Image — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A share button on the home screen and in the day sheet produces a picture of today's weather — place, the current temperature and condition, the day's high and low, when rain starts and how likely it is, and the day's hours as a strip — and hands it to Android's share sheet, so it can go into a chat the way a screenshot would, but legible and without the status bar.

**Architecture:** A pure `ShareCardStateBuilder` over the existing `HomeUiState`, a `ShareCard` composable that draws it at a fixed size, a preview sheet that shows the reader exactly what will be sent, and a capture step (`GraphicsLayer.toImageBitmap()`) that writes a PNG into the cache and shares it through a `FileProvider`. No new network, no new persistence, no new model work — every number on the card is one the home screen already has.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, `androidx.compose.ui.graphics.layer.GraphicsLayer`, `androidx.core.content.FileProvider`, JUnit4, Robolectric, Roborazzi, Compose UI tests.

---

## What the card says

```
┌──────────────────────────┐
│ Dorf Tirol   Mo 22. Sep  │
│                          │
│   18°        ⛅          │
│   Wolkig · 22° / 11°     │
│   Regen ab 19:45 · 40 %  │
│                          │
│ 12 13 14 15 16 17 18 19  │
│ 17 18 19 19 18 17 16 15  │
│  ▁  ▁  ▁  ▂  ▃  ▅  ▆  ▄  │
│                          │
│ Apex Wetter · 13 Modelle │
└──────────────────────────┘
```

Drawn on the place's own `SkyPalette`, so the shared picture looks like the screen it came from, and
held to the same contrast floor — `SkyContrast.darkenForWhiteText` already guarantees 4,5:1 for
white text on every palette, and the card is all white text on that gradient, so it inherits the
guarantee for free. Do not invent a second palette for it.

## Five decisions, taken while planning

**1. It shows a preview before it shares.** Tapping share opens a bottom sheet holding the rendered
card and one "Teilen" button. Two reasons, one of them technical. The reader is about to put this
in someone else's chat and should see it first; and `GraphicsLayer.toImageBitmap()` captures what
was actually composed and drawn, so capturing something that is on screen is correct by
construction, where capturing something composed off-screen is a layout problem waiting to be
discovered on somebody else's phone. It costs one tap and removes a class of bug.

**2. The card is drawn at `fontScale = 1f`, always.** It is a fixed-size image, not a screen. At the
2x font scale this app's layout rules are built for, the hour strip's eight columns would not fit
and the card would clip — which is not a layout bug to be solved but a category error: the reader's
text size is a setting about *their* screen and this image is going to somebody else's. Wrap the
card in `CompositionLocalProvider(LocalDensity provides Density(density = target, fontScale = 1f))`.
This is the single detail most likely to be forgotten and most likely to be reported later as
"the shared picture is broken on my phone".

**3. The footer carries the data credit, not just the app's name.** Sharing the image redistributes
the data, and GeoSphere Austria's is CC BY 4.0 — the same licence that put `map_attribution` at the
bottom of the map. `R.string.attribution` is too long for a card this size, so a short form
(`share_card_attribution`) names the province's weather service, GeoSphere (CC BY 4.0) and
Open-Meteo. This is a licence obligation, not decoration; it does not get dropped to make room.

**4. A moved station reading still says it was moved.** `state.heroAdjustmentC` is why the home
screen's hero carries a provenance line, and the rule in CLAUDE.md is flat: "A moved reading always
says so on screen." An image that leaves it out is the same reading with the caveat cut off. The
card carries it as a short line under the condition, in the same quiet grey the hero uses — and says
nothing when the adjustment rounds to zero, exactly as the hero does.

**5. The share icon goes at the end of the place row, not beside the place name.** CLAUDE.md's hero
note says the place name is deliberately the only affordance on that line. So the icon is
right-aligned at the far end of the row, outside the place button's tap target, as its own
`IconButton` — and its `testTag` goes **inside** its `clearAndSetSemantics` block, because that
clears the node's whole config and a tag further down the modifier chain is cleared with it. That is
the exact bug the pin star hit; it is written down and it still costs a release if it is repeated.

---

## Global Constraints

- Work on a branch; do not push, tag or merge.
- Run every shell script as `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c '…'`. Gradle in the foreground only, never two builds at once.
- Every new string key goes into `values/` (German), `values-it/` and `values-en/`.
- Nothing that runs on a device may assert a German string (CI emulators are en-US).
- Numbers and times go through `ui/common/Format.kt` with `LocalFormats.current`; times in `SouthTyrol.ZONE`. Never `Locale.ROOT`, never a number interpolated into a string.
- Screens split into `XScreen` (wiring) and `XContent(state, callbacks)`; UI tests drive the content with hand-built states.
- The preview sheet is a `ModalBottomSheet`; it scrolls, so **it needs a close cross** (CLAUDE.md).
- Any Compose test that renders `SkyBackground` or `MainActivity` must set `rule.mainClock.autoAdvance = false` and advance by hand.
- `./gradlew :app:lintDebug` runs with `warningsAsErrors` and must be clean.
- Commit per task.

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/kotlin/it/apexweather/ui/share/ShareCardState.kt` (create) | `ShareCardState`, `ShareHour`, and the pure `ShareCardStateBuilder.build(HomeUiState, …)`. |
| `app/src/main/kotlin/it/apexweather/ui/share/ShareCard.kt` (create) | The composable that draws the card at a fixed size. |
| `app/src/main/kotlin/it/apexweather/ui/share/ShareCapture.kt` (create) | `GraphicsLayer` → `ImageBitmap` → PNG in `cacheDir/share/` → `content://` Uri; pruning of old files. |
| `app/src/main/kotlin/it/apexweather/ui/share/ShareSheet.kt` (create) | The preview sheet: the card, the share button, the cross. |
| `app/src/main/kotlin/it/apexweather/ui/home/HomeScreen.kt` (modify) | Holds which share is open; hosts the sheet. |
| `app/src/main/kotlin/it/apexweather/ui/home/HomeSections.kt` (modify) | The share icon at the end of the hero's place row. |
| `app/src/main/kotlin/it/apexweather/ui/home/DayDetail.kt` (modify) | A share icon beside the sheet's cross. |
| `app/src/main/AndroidManifest.xml` (modify) | The `FileProvider`. |
| `app/src/main/res/xml/share_paths.xml` (create) | `<cache-path name="shares" path="share/"/>`. |
| `app/src/main/res/values*/strings.xml` (modify) | New keys in three languages. |
| `app/proguard-rules.pro` (verify, likely unchanged) | Confirm against a release build, do not add rules blind. |
| Tests | `ShareCardStateBuilderTest`, `ShareCardScreenshotTest` (Roborazzi), `ShareSheetTest`, `ShareCaptureTest`, `ShareProviderDeviceTest`. |

---

### Task 1: `ShareCardState` and its builder

**Files:** `app/src/main/kotlin/it/apexweather/ui/share/ShareCardState.kt` (create),
`app/src/test/kotlin/it/apexweather/ui/share/ShareCardStateBuilderTest.kt` (create)

Pure Kotlin over `HomeUiState`, the way `HomeStateBuilder` and `SourceDetailStateBuilder` already
are — so every rule below is testable on the JVM and none of it lives in a composable.

- [ ] `ShareCardState`: place name, the date it is about, hero temperature, condition, condition
      icon phase, high/low, `precipStart` + `precipProb`, `adjustmentC`, `sourceCount`, `palette`,
      and `hours: List<ShareHour>`.
- [ ] `ShareHour`: the hour, its temperature, its precipitation in mm (or cm — see below), the bar
      fraction, its condition and the phase to draw it in.
- [ ] `ShareCardStateBuilder.build(state: HomeUiState, date: LocalDate)`. For today it takes the
      hero values straight off `HomeUiState` — the hero has already been through `StationDownscale`,
      `StationFog`, `StationSun`, `StationDry` and `MeasuredRain`, and **re-deriving any of that here
      would put a second answer about the same hour on the reader's screen.** For a day from the day
      sheet it takes that day's `ConsensusDay` and `hoursByDate`, and carries no station reading at
      all, because a thermometer speaks for the hour it measured.
- [ ] **Eight hours, chosen by a stated rule, not by "the next eight".** For today: from the current
      hour forward within the day, and where fewer than eight remain (a share at 21:00), back-fill
      from earlier in the day so the strip is always eight columns wide and always about one day. For
      a future day: 06:00 to 21:00 at three-hour steps, six columns. Pin both in the test.
- [ ] The bar fraction comes from `PrecipScale`, unchanged — the same square root against a 10 mm
      full bar the home strip uses. A share that drew rain on a different scale from the screen it
      came from would be two pictures of one day.
- [ ] Snow follows `Format.showsSnow`/`Format.precip`, so a snowy hour reads in centimetres. This is
      the fourth place that rule has to be honoured and the notifications are the reason it is
      written down; do not re-implement the threshold here.
- [ ] `adjustmentC` is carried through verbatim and is null when the hero is not a moved reading.

**Tests** (`ShareCardStateBuilderTest`, JVM):
- [ ] Today's card takes the hero temperature, not the consensus hour, when the two differ.
- [ ] A place with no station produces a card with no adjustment line.
- [ ] A share at 21:00 still yields eight hours, and they are all the same local day.
- [ ] A snowy hour's value is centimetres, a rainy hour's is millimetres.
- [ ] A future day carries no station reading and its own high/low.
- [ ] An empty `HomeUiState` yields null rather than a card of dashes — there is nothing to share
      before the first fetch, and the button is disabled for that state (Task 4).

**Verify:** `./gradlew :app:testDebugUnitTest --tests '*ShareCardStateBuilderTest'`.

---

### Task 2: `ShareCard`, and its goldens

**Files:** `app/src/main/kotlin/it/apexweather/ui/share/ShareCard.kt` (create),
`app/src/test/kotlin/it/apexweather/ui/share/ShareCardScreenshotTest.kt` (create)

- [ ] `ShareCard(state: ShareCardState, modifier: Modifier)` — a `Column` on the palette's gradient,
      fixed at `ShareCardWidth = 360.dp` with height wrapping its content. Reuse `WeatherIcons` and
      `Format` throughout; tint the icons as the hero does.
- [ ] The footer is two lines: the app's name plus the model count, and `share_card_attribution`.
- [ ] No animation, no `SkyBackground`, no particles — a still picture of a moving sky is a still
      picture. The gradient is drawn directly from `state.palette`.

**Goldens** (Roborazzi, into `app/src/test/screenshots/`) — the repo's existing mechanism, and the
only thing that will notice a card that still *composes* but no longer *looks* right:
- [ ] `share_card_sunny.png`, `share_card_rain.png`, `share_card_snow.png`,
      `share_card_no_station.png`, `share_card_night.png`.
- [ ] Record them deliberately and **look at each PNG before committing it** (CLAUDE.md).

**Verify:** `./gradlew :app:recordRoborazziDebug` then `:app:verifyRoborazziDebug` clean.

---

### Task 3: Capture and the FileProvider

**Files:** `app/src/main/kotlin/it/apexweather/ui/share/ShareCapture.kt` (create),
`app/src/main/res/xml/share_paths.xml` (create), `app/src/main/AndroidManifest.xml` (modify),
`app/src/test/kotlin/it/apexweather/ui/share/ShareCaptureTest.kt` (create)

- [ ] `share_paths.xml`: `<cache-path name="shares" path="share/"/>` and nothing else. The provider
      must reach exactly one directory the app writes and nothing the app stores.
- [ ] Manifest: `androidx.core.content.FileProvider`, authority `${applicationId}.shares`,
      `android:exported="false"`, `android:grantUriPermissions="true"`.
- [ ] `captureToPng(layer: GraphicsLayer, context: Context): Uri` — `layer.toImageBitmap()`,
      `asAndroidBitmap().compress(PNG, 100, …)` into `cacheDir/share/apexweather-<epoch>.png`, then
      `FileProvider.getUriForFile`. **The bitmap write is IO**, so it is a `suspend` function on
      `Dispatchers.IO`; nothing in this app does file work on Main.
- [ ] Prune on every capture: delete files in `cacheDir/share/` older than an hour. The share sheet
      copies what it needs; the app should not accumulate pictures of last week's weather.
- [ ] The `Intent`: `ACTION_SEND`, type `image/png`, `EXTRA_STREAM` the Uri,
      `FLAG_GRANT_READ_URI_PERMISSION`, plus `EXTRA_TEXT` holding a one-line plain-text form (place,
      temperature, condition) so a target that takes only text still receives the answer rather than
      nothing. Wrapped in `Intent.createChooser`.

**Tests:**
- [ ] `ShareCaptureTest` (Robolectric): a bitmap round-trips to a file under `cacheDir/share/`;
      the pruning deletes an hour-old file and keeps a fresh one; the Uri carries the expected
      authority.

**Verify:** `./gradlew :app:testDebugUnitTest --tests '*ShareCaptureTest'`.

---

### Task 4: The preview sheet and the two entry points

**Files:** `app/src/main/kotlin/it/apexweather/ui/share/ShareSheet.kt` (create),
`HomeScreen.kt`, `HomeSections.kt`, `DayDetail.kt` (modify),
`app/src/test/kotlin/it/apexweather/ui/share/ShareSheetTest.kt` (create)

- [ ] `ShareSheetContent(state: ShareCardState, onShare: () -> Unit, onClose: () -> Unit)`: the card
      inside the fixed-density wrapper, a "Teilen" button, and the close cross.
- [ ] The capture layer: `rememberGraphicsLayer()` and
      `Modifier.drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }`
      on the card, so what is captured is byte-for-byte what the reader is looking at.
- [ ] `HomeContent` holds `var shareTarget by remember { mutableStateOf<LocalDate?>(null) }` and
      hosts one `ModalBottomSheet` for it, the way it already hosts the hour, day and warning sheets.
- [ ] Hero: an `IconButton` with `Icons.Filled.Share` at the **end** of the place row, outside the
      place button, `clearAndSetSemantics { contentDescription = …; testTag("share_today") }` — tag
      inside the block.
- [ ] Day sheet: the same icon beside the existing cross in `DayDetail`'s header row, tagged
      `share_day`. It shares that day, not today.
- [ ] Both buttons are disabled (drawn dim, not hidden) while `state.isEmpty` — "there is nothing to
      share yet" is a different message from "this cannot be shared".

**Tests** (`ShareSheetTest`, Compose, driving the content with a hand-built state — no real intent):
- [ ] The hero's share button reports its press and opens the sheet.
- [ ] The day sheet's share button carries the day it was opened from, not today.
- [ ] The sheet's button reports its press; the cross closes it.
- [ ] The share buttons are disabled on an empty state.
- [ ] The card inside the sheet is unchanged at a 2x font scale — the test sets the font scale and
      asserts the card's measured width is still `ShareCardWidth`. This is the guard for decision 2
      and the reason that decision is not just a comment.

**Verify:** `./gradlew :app:testDebugUnitTest --tests '*ShareSheetTest'`.

---

### Task 5: Strings

**Files:** `app/src/main/res/values*/strings.xml`

- [ ] `share`, `share_today`, `share_day`, `share_action`, `share_sheet_title`,
      `share_card_models` (`%1$d Modelle`), `share_card_attribution`, `share_text` (the plain-text
      fallback). All three languages. German spells the app **Apex Wetter** — see the sibling plan,
      `2026-09-22-german-app-name.md`; if that one has not landed, use `@string/app_name` on the card
      rather than a literal, which is right either way.

---

### Task 6: The release build, and the phone

The instrumented suite runs against debug, so R8 and the provider registration are only exercised
here. This is the same class of risk as the Glance layout lookup and jhdf's static initialiser —
both of which shipped broken once and were invisible in debug.

- [ ] `./gradlew :app:assembleRelease`, then `./tools/release-smoke.sh` **on an emulator**
      (it uninstalls, which deletes `station_history`).
- [ ] `ShareProviderDeviceTest` in `androidTest`: capture a card, resolve the Uri through
      `ContentResolver.openInputStream`, decode it, assert non-null and the expected width. This is
      what proves the manifest entry and `share_paths.xml` agree.
- [ ] On the phone (`RZCXA1ZEXJE`, debug build): share today into a chat app and into Google Photos.
      Look at the received picture. Check it at system font scale 2.0 — the card must be identical.
      Check it in German, Italian and English.
- [ ] `./gradlew :app:lintDebug` clean.

---

## Deliberately not built

- **No custom text, no cropping, no "add a note".** A share sheet that asks the reader to compose
  is a different feature; this one answers "what's the weather" in one tap and a confirm.
- **No map or radar in the image.** The radar is a loop and a still frame of it is the one mistake
  that map can make — "this happened" read as "this is expected". If it is ever wanted, it is its
  own card with its own timestamp, not a strip glued to this one.
- **No wallpaper or widget export.** Same rendering, different product.
