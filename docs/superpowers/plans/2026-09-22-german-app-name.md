# "Apex Wetter" on a German Phone — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A phone in German calls the app **Apex Wetter** — on the launcher, in the task switcher, in the widget picker and in every sentence inside the app that names it. Italian and English keep **Apex Weather**.

**Architecture:** Resources only. `values/` is already the German default (CLAUDE.md, "Strings live in `values` (German, default)"), so the change is four German strings plus `app_name`, and one test that pins all three locales. No Kotlin, no manifest, no Gradle.

**Tech Stack:** Android resources, JUnit4 + Robolectric (for the per-locale resource test).

---

## The decision, and what it costs

`android:label="@string/app_name"` resolves against `values/` on a German phone and against
`values-en/` or `values-it/` elsewhere. So changing `values/strings.xml` is the whole feature — with
two consequences worth stating before the work starts rather than discovering after.

**1. `values/` is also the fallback for every locale that is not de, it or en.** `locales_config.xml`
lists only those three, but a phone set to French resolves an unlisted locale to the default
resources, so it would now read "Apex Wetter". That is correct for this app rather than a bug: the
default language of a South Tyrol app is German, and the project already ships German prose to that
same fallback (`empty_body`, `about`, the whole file). The alternative — a new `values-de/` holding
only `app_name`, with `values/` carrying English — inverts the convention CLAUDE.md states, splits
the German strings across two directories, and buys a French reader one English word in an otherwise
German app. Not worth it.

**2. The launcher label does not follow the in-app language switch.** `SettingsScreen` writes a
per-app locale through `AppCompatDelegate.setApplicationLocales` (`AppNavigation.kt:272`), and that
changes resources *inside the process*. The home-screen label is resolved by the launcher, from the
system locale. So a phone in English with the in-app language set to DE reads "Apex Wetter"
everywhere inside the app and "Apex Weather" under the icon. That is Android's behaviour for
per-app languages, not something this app can fix, and it is the honest outcome: the icon's label
belongs to the phone's language. Say it in CLAUDE.md so the next person does not chase it.

**3. Three literals stay in English on purpose.** `base { archivesName = "ApexWeather" }`, the
`ApexWeather-<version>.apk` asset name and `release.yml`'s `--title 'Apex Weather …'` are file names
and a release title, not user-facing app chrome. Do not touch them. The README title likewise.

---

## Global Constraints

- Work on a branch; do not push, tag or merge.
- Run every shell script as `env -i HOME=$HOME PATH=$PATH TERM=dumb bash --noprofile --norc -c '…'`. Gradle in the foreground only.
- Every string key exists in all three of `values/`, `values-it/`, `values-en/` (CLAUDE.md).
- Nothing that runs on a device may assert a German string — CI emulators are en-US. The test below is a JVM/Robolectric test that resolves each locale's resources explicitly, which is allowed and is the point.
- `./gradlew :app:lintDebug` runs with `warningsAsErrors` and must be clean.
- Commit per task.

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/res/values/strings.xml` (modify) | `app_name` → `Apex Wetter`; the four German sentences that name the app. |
| `app/src/main/res/values-it/strings.xml` (unchanged) | Stays `Apex Weather`. |
| `app/src/main/res/values-en/strings.xml` (unchanged) | Stays `Apex Weather`. |
| `app/src/test/kotlin/it/apexweather/AppNameTest.kt` (create) | Pins the name per locale and that no German string names the app in English. |
| `CLAUDE.md` (modify) | One bullet under Conventions: the name is German, and why the launcher label can disagree with the in-app language. |

---

### Task 1: The German name, everywhere German prose names the app

**Files:** `app/src/main/res/values/strings.xml`

Five strings. Four of them are prose that already spells the app's name out, and leaving them would
have the app call itself two different things inside one language — which is exactly the failure
CLAUDE.md records for the snow unit (a title and a body disagreeing about the same hour).

- [ ] `app_name` (line 3): `Apex Weather` → `Apex Wetter`
- [ ] `empty_body` (line 32): `Apex Weather braucht einmal Internet…` → `Apex Wetter braucht…`
- [ ] `about` (line 113): `Apex Weather %1$s · Südtirol · …` → `Apex Wetter %1$s · Südtirol · …`
- [ ] `update_needs_permission` (line 161): `Android muss Apex Weather erlauben…` → `…muss Apex Wetter erlauben…`
- [ ] `setting_notif_permission` (line 249): `Android muss Apex Weather Mitteilungen erlauben` → `…Apex Wetter Mitteilungen erlauben`

Do **not** touch `values-it/` or `values-en/`. Their four matching strings are correct as they are.

**Verify:** `grep -n "Apex Weather" app/src/main/res/values/strings.xml` returns nothing;
the same grep on `values-it/` and `values-en/` still returns four lines each.

---

### Task 2: A test that pins the name per locale

**Files:** `app/src/test/kotlin/it/apexweather/AppNameTest.kt` (create)

A Robolectric test that builds a `Context` per locale and asserts `R.string.app_name`. Three
assertions and one sweep:

- [ ] German (`de`) resolves to `Apex Wetter`.
- [ ] Italian (`it`) and English (`en`) resolve to `Apex Weather`.
- [ ] An unlisted locale (`fr`) falls back to the default and therefore to `Apex Wetter` — asserted
      so the fallback is a decision on record rather than a surprise.
- [ ] The German resources contain no string whose value holds the literal `Apex Weather`: read
      `values/strings.xml` off the test classpath and fail on a match. This is the guard that
      catches the *next* German string someone writes with the English name in it, which is the
      actual failure mode here — a single `app_name` is easy to get right once and easy to drift
      from afterwards.

Use `Locale.forLanguageTag` + `createConfigurationContext`, not `Locale.setDefault`, so the test does
not leak a locale into the rest of the JVM suite.

**Verify:** `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.AppNameTest'` passes; flipping
one string back fails it.

---

### Task 3: Write it down

**Files:** `CLAUDE.md`

- [ ] Under Conventions, beside the existing "Strings live in `values` (German, default)" bullet, add
      that the app's own name is one of those strings: **Apex Wetter** in German, **Apex Weather** in
      Italian and English, and that `archivesName`, the release asset name and the release title are
      file names and stay English. Include the launcher-label limitation above in one sentence — the
      home-screen label follows the *system* locale, not the in-app language switch, so the two can
      legitimately disagree.

---

### Task 4: Verify on the phone

Not optional; this is a feature whose whole output is a label drawn by other processes.

- [ ] `./gradlew :app:assembleDebug` and install on `RZCXA1ZEXJE`.
- [ ] System language German: the launcher icon reads **Apex Wetter**; the task switcher reads it;
      long-press the home screen → Widgets and the app's section reads it.
- [ ] Settings → Über: the `about` line reads "Apex Wetter …".
- [ ] System language English: all of the above read **Apex Weather**.
- [ ] System English, in-app language DE: the app's inside is German and says "Apex Wetter"; the
      launcher still says "Apex Weather". Confirm that, so the limitation is observed rather than
      assumed.
- [ ] `./gradlew :app:lintDebug` clean.

---

## Out of scope, noticed while planning

`about` reads "Konsens aus 10 Wettermodellen" in all three languages, where CLAUDE.md documents
**thirteen** sources. The string is stale by three models. Not part of this change; worth a
one-line commit of its own.
