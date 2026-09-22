# Sharing Three Days and Seven — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The share preview offers **Heute · 3 Tage · 7 Tage**. Today is the card that already exists; the other two replace the hour strip with day rows, and carry the same agreement dot the day list uses so a thinning consensus travels with the picture.

**Architecture:** One new enum on the existing `ShareCardState`, day rows built by the existing pure `ShareCardStateBuilder`, a segmented control in `ShareSheetContent`, and the choice remembered in `AppSettings`. No new network, no new model work, no new provider.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, DataStore, JUnit4, Robolectric, Roborazzi, Compose UI tests.

Builds on `docs/superpowers/plans/2026-09-22-share-today-as-an-image.md` (shipped in v0.27.0).

---

## What the two new cards say

```
        3 Tage                            7 Tage
┌──────────────────────────┐   ┌──────────────────────────┐
│ Dorf Tirol   Di. 22. Sep │   │ Dorf Tirol   Di. 22. Sep │
│   15°        ☀           │   │   15°        ☀           │
│   Klar · 23° / 13°       │   │   Klar · 23° / 13°       │
│   Regen ab 19:45 · 40 %  │   │   Regen ab 19:45 · 40 %  │
│                          │   │                          │
│ Heute ☀      13°▬▬▬23° ● │   │ Heute ☀      13°▬▬▬23° ● │
│ Mi.   ☀      10°▬▬ 20° ● │   │ Mi.   ☀      10°▬▬ 20° ● │
│ Do.   ☁ 2mm  11°▬▬ 22° ● │   │ Do.   ☁ 2mm  11°▬▬ 22° ● │
│                          │   │ Fr.   ⛅     9°▬▬ 21° ● │
│ Apex Wetter · 13 Modelle │   │ Sa.   ☁ 2%   8°▬▬▬22° ● │
│ Daten: …                 │   │ So.   🌧 6mm  9°▬▬ 18° ● │
└──────────────────────────┘   │ Mo.   ⛅    10°▬▬ 20° ● │
                               │                          │
                               │ Apex Wetter · 13 Modelle │
                               │ Daten: …                 │
                               └──────────────────────────┘
```

## Five decisions, taken while planning

**1. The day cards drop the hour strip; they do not stack both.** "What is this afternoon like" and
"what is the week like" are different questions, and a card that answers both is two cards stapled
together — taller than a chat preview shows, with the important half pushed below the crop. Today
keeps its hours; three and seven days keep their rows. The hero stays on all three, because the
first line of any of them is still *where* and *what now*.

**2. The rows carry the agreement dot, and that is the whole reason seven days is defensible.**
`ApexWidget`'s tall size stops at five days on the stated ground that "past about day five only the
globals reach … and a widget has neither the room for that badge nor anywhere to put the
explanation". A shared picture has the first half of that problem — nobody can tap it — but not the
second: a card has room for a line of text, which a 4x5 widget does not. So the dot comes across
verbatim (`agreementColor`, and `SingleModelColor` where a day is down to one model), and where any
shown day has `sourceCount == 1` the card carries `daily_tail_note` / `daily_tail_note_ensemble`
underneath, exactly as the day list does.

At seven days that note should never fire — GFS reaches 336 h, GEM 243 h and UKMO 171 h, so day
seven still has five models — **but it is built from the data rather than from that reasoning**, so
a reach that shortens upstream cannot turn the card into a quiet single-model forecast.

**3. Seven, not fourteen, and the reason is not room.** The day list runs fourteen because the
reader can tap a day and be told what the badge means. Past day ten `sourceCount` reaches one and
the picture would be one model's opinion wearing a consensus's clothes, with a sentence under it
that the recipient cannot act on. Seven is where the answer is still several independent models.
If fourteen is ever wanted it is a different card with a different caveat, not a fourth chip.

**4. The range is remembered, as a single value.** `AppSettings.shareRange`, written when the reader
picks. This is **not** the case `compare_hidden_sources` warns about — that one stores an
*exclusion* set because storing the visible set froze the list when an eleventh model appeared. A
single enum has no such failure; an unknown stored value falls back to `TODAY`.

**5. The selector is only on the hero's share.** Opening the preview from the day sheet means the
reader has already picked a day, and offering "7 Tage" there would answer a question they did not
ask. That share keeps its single-day card and shows no chips.

## Best practice this follows, stated so it can be argued with

- **The important half is at the top.** Chat clients crop previews; place, temperature and condition
  survive a crop, and the rows are what the reader opens the image for.
- **The picture stays under about 1:1.8.** Seven rows at the row height below put the card near
  360 x 470 dp, which is 1:1.3 — comfortably inside what previews show whole. This is a constraint
  to check on the goldens, not a number to trust.
- **Nothing new is invented to say "less certain".** The dot, its colours, the grey single-model
  variant and the two tail sentences all exist and all mean something the reader has already seen on
  the home screen. A shared image is the worst place to introduce private vocabulary.
- **The row is not a touch target here**, so `DayRowMinHeight`'s 44 dp does not apply; the rows are
  sized by their content. That constant is about a finger, and there are no fingers in a PNG.

---

## Global Constraints

- Work on a branch; do not push, tag or merge.
- Run every shell script as `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c '…'`. Gradle in the foreground only.
- Every new string key goes into `values/` (German — the app is **Apex Wetter**), `values-it/` and `values-en/`.
- Chip labels are literals (`3 Tage`, `7 Tage`), **not** `%1$d Tage`: lint's `PluralsCandidate` is an error here under `warningsAsErrors`, and a fixed number has no plural to get right.
- The segmented buttons pass `icon = {}` and their labels go through `CompactLabel` (1.2 ceiling) — Material reserves ~24 dp for a tick in every segment, and "7 Tage" must not wrap inside its own segment at a 2x font scale.
- The card composes at `fontScale = 1f`; `ShareSheetTest` already asserts its size does not move, and that must keep passing for the new ranges.
- Nothing that runs on a device may assert a German string (CI emulators are en-US).
- Device tests on an **emulator**, never the phone: the run uninstalls and that deletes `station_history`.
- `./gradlew :app:lintDebug` runs with `warningsAsErrors` and must be clean.
- Commit per task.

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/kotlin/it/apexweather/ui/share/ShareCardState.kt` (modify) | `ShareRange`, `ShareDay`, `days` on the state, `ShareCardStateBuilder.range(...)`. |
| `app/src/main/kotlin/it/apexweather/ui/share/ShareCard.kt` (modify) | `DayRows`, the tail note, and the strip/rows switch. |
| `app/src/main/kotlin/it/apexweather/ui/share/ShareSheet.kt` (modify) | The three chips, on the hero's share only. |
| `app/src/main/kotlin/it/apexweather/ui/home/HomeScreen.kt` (modify) | Holds the chosen range; writes it back to settings. |
| `app/src/main/kotlin/it/apexweather/ui/home/HomeSections.kt` (modify) | `agreementColor` and the single-model grey made `internal` for reuse. |
| `app/src/main/kotlin/it/apexweather/data/SettingsRepository.kt` (modify) | `shareRange` + `setShareRange`. |
| `app/src/main/res/values*/strings.xml` (modify) | `share_range_today`, `share_range_3`, `share_range_7`. |
| Tests | `ShareCardStateBuilderTest` (+6), `ShareCardScreenshotTest` (+3), `ShareSheetTest` (+3), `SettingsRepositoryTest` (+1). |

---

### Task 1: The range, and the days that travel

**Files:** `ShareCardState.kt`, `ShareCardStateBuilderTest.kt`

- [ ] `enum class ShareRange { TODAY, THREE_DAYS, SEVEN_DAYS }` with a `days` count.
- [ ] `ShareDay`: date, condition, min/max, `precipMm`, `snowCm`, `precipProb`, `agreement`,
      `sourceCount`, `ensembleBacked` (`ensembleHalfWidthC != null`). Everything the dot and the row
      need, and nothing else — the card must not reach back into `ConsensusDay`.
- [ ] `ShareCardState` gains `range: ShareRange` and `days: List<ShareDay>`; `hours` stays and is
      empty for the two day ranges.
- [ ] `ShareCardStateBuilder.today(state, locale, range)` — the same hero, and then either the hour
      strip (`TODAY`) or `range.days` day rows starting at today.
- [ ] Where the forecast does not reach the whole range it carries what it has rather than padding:
      a six-day card on a short forecast is honest, a seventh empty row is not.
- [ ] `ShareCardStateBuilder.day(...)` is untouched — the day sheet still shares one day.

**Tests:**
- [ ] `THREE_DAYS` carries three days, the first being today; `SEVEN_DAYS` carries seven.
- [ ] The day ranges carry no hours, and `TODAY` carries no days.
- [ ] Each row's agreement and source count come from its own `ConsensusDay`, not the hero's.
- [ ] A forecast that reaches four days yields four rows for a seven-day request, not seven.
- [ ] The hero is identical across all three ranges — same temperature, same adjustment line. A
      reader who switches chips must not see the current temperature change.
- [ ] An empty state yields null for every range.

---

### Task 2: The rows on the card

**Files:** `ShareCard.kt`, `HomeSections.kt`, `ShareCardScreenshotTest.kt`

- [ ] Make `agreementColor` and the single-model grey reusable (`internal`) rather than copying the
      colours. Two tables of the same three colours is how the day list and the card would drift.
- [ ] `DayRows(state)`: weekday (today as `R.string.today`), icon, amount over chance, min, a
      `RangeBar` normalised across the shown days, max, dot. The same order as the day list, because
      a reader who knows one should not have to learn the other.
- [ ] The tail note under the rows when any shown day has `sourceCount == 1`, choosing between
      `daily_tail_note` and `daily_tail_note_ensemble` exactly as `DailySection` does.
- [ ] The footer's model count stays the *hero's* day count; the per-day variation is what the dots
      are for, and a footer averaging thirteen models across a week would be a number about nothing.

**Goldens** (record, then **look at each PNG**):
- [ ] `share_card_3d.png`, `share_card_7d.png`, and `share_card_7d_thin.png` — the last built with a
      tail of single-model days, so the grey dot and the note are pinned rather than assumed
      unreachable.
- [ ] Assert in the test that the seven-day card's height/width ratio is under 1.8, which is the
      preview-crop constraint written down as a check rather than a hope.

---

### Task 3: The chips

**Files:** `ShareSheet.kt`, `HomeScreen.kt`, `SettingsRepository.kt`, `strings.xml`, `ShareSheetTest.kt`, `SettingsRepositoryTest.kt`

- [ ] A `SingleChoiceSegmentedButtonRow` above the card: Heute · 3 Tage · 7 Tage, `icon = {}`,
      labels through `CompactLabel`.
- [ ] Shown only when the preview was opened from the hero. The day sheet's share has no chips.
- [ ] `AppSettings.shareRange` remembers the choice; an unknown stored value falls back to `TODAY`.
- [ ] Switching chips rebuilds the card **from the same `HomeUiState` snapshot**, so the hero cannot
      change under the reader mid-choice.

**Tests:**
- [ ] The chips are on screen for the hero's share and absent for the day sheet's.
- [ ] Picking "7 Tage" redraws the card with seven rows and writes the setting.
- [ ] The card's measured width is unchanged at a 2x font scale for all three ranges — extending the
      guard that already exists rather than adding a second one.
- [ ] `SettingsRepositoryTest`: the range round-trips and an unknown value falls back.

---

### Task 4: Verify

- [ ] `./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintDebug` clean.
- [ ] `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest` — the whole suite.
- [ ] `./gradlew :app:assembleRelease` then `./tools/release-smoke.sh` on the emulator.
- [ ] On the phone: share all three ranges into a chat app and **look at what the preview crops**,
      which is the one thing no test can tell you. Check at font scale 2.0 and in all three
      languages.
- [ ] Check a place late in the week where the dots differ, so the colours are seen carrying real
      variation rather than a row of identical green.

---

## Deliberately not in this plan

- **Fourteen days.** Past day ten the consensus is one model and the picture would need a caveat the
  recipient cannot act on. A different card if ever wanted, not a fourth chip.
- **An hourly card for a future day.** The day sheet shares that day already; hours for Thursday is
  a fourth layout for a question nobody has asked yet.
- **Custom ranges.** Two extra chips answer the two questions people actually have — the next few
  days, and the week. A date picker on a share sheet is a different product.
