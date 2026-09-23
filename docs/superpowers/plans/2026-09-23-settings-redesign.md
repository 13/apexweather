# Settings redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the settings screen part of the same app as every other screen — white on `GlassCard`s over the shared sky, five grouped cards, an icon on every row — without changing a single setting or breaking a single existing test.

**Architecture:** `SettingsScreen.kt` splits four ways by responsibility. A small set of row primitives (`SettingRow`, `SwitchRow`, `NavRow`, `SegmentedRow`) in `SettingsRows.kt` is what every group is built from, so the groups describe content and nothing else. The page loses its opaque `Surface` and gains five `GlassCard`s.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), `material-icons-extended`, Compose UI tests (instrumented).

## Global Constraints

- **Every existing `testTag` survives verbatim**: `settings_screen`, `settings_place`, `setting_amateur_stations`, `setting_wu_key`, `wu_key_verdict`, `notify_summary`, `notify_rain`, `notify_warning`, `notify_summary_hour`, `notify_hour_up`, `notify_hour_down`, `notify_permission_hint`, `notify_grant`, `about_build`, `refresh_now`. `SettingsContentTest` must pass **unchanged** — that is the gate on this whole plan.
- Strings live in `values` (German, default), `values-it`, `values-en`; **every new key goes in all three**. An unescaped `'` in Italian fails aapt2.
- Nothing that runs on a device may assert a German string: CI's emulators are **en-US**.
- Nothing user-visible may name Dorf Tirol.
- Screens split into `XScreen` (ViewModel wiring) and `XContent(state, callbacks)`; UI tests drive `XContent` with hand-built states.
- Lint runs with `warningsAsErrors`: `./gradlew :app:lintDebug` must be clean, and an unused string is an error (`UnusedResources`).
- Labels in a control whose width is not its own go through `CompactLabel`; segmented buttons pass `icon = {}`.
- Device tests: `ANDROID_SERIAL=emulator-5554` (AVD `r8verify34`, API 34). **Never the phone `RZCXA1ZEXJE`** — a connected run uninstalls the app and deletes `station_history`.
- Card spacing is 10 dp, matching `HomeCardSpacing`; `GlassCard` already pays 12 dp of its own padding per side.
- JVM tests: `./gradlew :app:testDebugUnitTest`. Instrumented single class: `-Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`.

---

### Task 1: The row primitives

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/ui/settings/SettingsRows.kt`
- Test: `app/src/androidTest/kotlin/it/apexweather/ui/settings/SettingsRowsTest.kt`

**Interfaces:**
- Consumes: `it.apexweather.ui.common.CompactLabel`.
- Produces: `SettingsGroup(title: String, modifier, content: @Composable ColumnScope.() -> Unit)`; `SettingRow(icon: ImageVector, label: String, supporting: String? = null, modifier: Modifier = Modifier, trailing: @Composable () -> Unit)`; `SwitchRow(icon, label, supporting, checked, onChange, modifier)`; `NavRow(icon, label, value: String?, onClick, modifier)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/androidTest/kotlin/it/apexweather/ui/settings/SettingsRowsTest.kt`:

```kotlin
package it.apexweather.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Place
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import it.apexweather.ui.theme.ApexTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsRowsTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun aRowShowsItsLabelAndItsSupportingLine() {
        rule.setContent {
            ApexTheme {
                SettingRow(Icons.Rounded.Place, label = "Ort", supporting = "wo die Messung herkommt") {}
            }
        }
        rule.onNodeWithText("Ort").assertIsDisplayed()
        rule.onNodeWithText("wo die Messung herkommt").assertIsDisplayed()
    }

    /** A row without one must not leave a gap where a second line would have been. */
    @Test
    fun aRowWithNoSupportingLineShowsOnlyItsLabel() {
        rule.setContent {
            ApexTheme { SettingRow(Icons.Rounded.Place, label = "Ort", supporting = null) {} }
        }
        rule.onNodeWithText("Ort").assertIsDisplayed()
    }

    /**
     * The whole row is the target, not the switch alone. A 44 dp switch at the right edge is a
     * hard thing to hit with a thumb, and the label is the obvious thing to press.
     */
    @Test
    fun theWholeSwitchRowToggles() {
        var checked = false
        rule.setContent {
            ApexTheme {
                SwitchRow(
                    Icons.Rounded.Place, label = "Himmel animieren", supporting = null,
                    checked = checked, onChange = { checked = it },
                    modifier = Modifier.testTag("row"),
                )
            }
        }
        rule.onNodeWithText("Himmel animieren").performClick()
        assertTrue("pressing the label did not toggle the switch", checked)
    }

    @Test
    fun aNavRowShowsItsValueAndReports() {
        var tapped = false
        rule.setContent {
            ApexTheme {
                NavRow(
                    Icons.Rounded.Place, label = "Ort", value = "Meran",
                    onClick = { tapped = true }, modifier = Modifier.testTag("nav"),
                )
            }
        }
        rule.onNodeWithText("Meran").assertIsDisplayed()
        rule.onNodeWithTag("nav").performClick()
        assertTrue("the row did not report being pressed", tapped)
    }

    @Test
    fun aGroupShowsItsHeadingAboveItsContent() {
        rule.setContent {
            ApexTheme {
                SettingsGroup("ANZEIGE") { SettingRow(Icons.Rounded.Place, label = "Wind", supporting = null) {} }
            }
        }
        rule.onNodeWithText("ANZEIGE").assertIsDisplayed()
        rule.onNodeWithText("Wind").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.settings.SettingsRowsTest`
Expected: FAIL to compile — `Unresolved reference: SettingRow`.

- [ ] **Step 3: Write `SettingsRows.kt`**

```kotlin
package it.apexweather.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import it.apexweather.ui.common.GlassCard

/**
 * One group of settings: a heading, and a card holding the rows that belong to it.
 *
 * The heading sits **above** the card rather than between controls. It used to be a `labelSmall`
 * floating in a column spaced by a flat 16 dp, which put a heading exactly as far from its own
 * control as from the unrelated one before it — so nothing on the screen was grouped with anything.
 */
@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        GlassCard(Modifier.fillMaxWidth(), content = content)
    }
}

/** The height a row is held to, so a finger has something to hit and the column keeps a rhythm. */
private val RowMinHeight = 48.dp

/** The icon column's width, so every label in a card starts at the same x. */
private val IconColumn = 36.dp

/**
 * One setting: an icon, a label, an optional second line, and whatever operates it on the right.
 *
 * The icons are **decorative** (`contentDescription = null`). Every one sits beside a label that
 * already says the same thing, and a screen reader announcing "Ort-Symbol, Ort" is worse than
 * silence. What the icon column buys is a form that can be scanned instead of read.
 */
@Composable
fun SettingRow(
    icon: ImageVector,
    label: String,
    supporting: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    Row(
        // heightIn, never height: a fixed height clips a two-line row at a large font scale, which
        // is the same reason DayRowMinHeight is a floor rather than a size.
        modifier.fillMaxWidth().heightIn(min = RowMinHeight).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(IconColumn - 20.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            supporting?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
            }
        }
        trailing()
    }
}

/**
 * A setting that is on or off, where **the whole row toggles it**.
 *
 * A switch is about 52 dp of target at the right edge of a 360 dp row, and the label is the obvious
 * thing to press. The switch keeps its own semantics; the row's click is what makes the rest of it
 * live.
 */
@Composable
fun SwitchRow(
    icon: ImageVector,
    label: String,
    supporting: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    switchModifier: Modifier = Modifier,
) {
    SettingRow(
        icon = icon,
        label = label,
        supporting = supporting,
        modifier = modifier.clickable { onChange(!checked) },
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(),
            modifier = switchModifier,
        )
    }
}

/** A setting that lives somewhere else: its current value, and a chevron into it. */
@Composable
fun NavRow(
    icon: ImageVector,
    label: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
) {
    SettingRow(
        icon = icon,
        label = label,
        modifier = modifier.clickable(onClickLabel = onClickLabel, onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            value?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

Run: `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.settings.SettingsRowsTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/settings/SettingsRows.kt \
        app/src/androidTest/kotlin/it/apexweather/ui/settings/SettingsRowsTest.kt
git commit -m "feat: the rows a settings group is built from"
```

---

### Task 2: The notification group moves to its own file

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/ui/settings/NotificationSettings.kt`
- Modify: `app/src/main/kotlin/it/apexweather/ui/settings/SettingsScreen.kt` (remove `NotificationSettings` and `SwitchRow`)
- Modify: `app/src/main/res/values/strings.xml`, `values-it/strings.xml`, `values-en/strings.xml`

**Interfaces:**
- Consumes: `SettingsGroup`, `SwitchRow`, `SettingRow` (Task 1).
- Produces: `NotificationSettings(settings, allowed, onRequestPermission, onSummary, onSummaryHour, onRain, onWarnings)` — the same signature it has today, so `SettingsContent` calls it unchanged.

This task adds no strings: `setting_notifications` is already the heading, and the icons are decorative.

- [ ] **Step 1: Move the file**

Create `app/src/main/kotlin/it/apexweather/ui/settings/NotificationSettings.kt` with the existing `NotificationSettings` composable moved verbatim, then change its body to use the new primitives:

```kotlin
package it.apexweather.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.apexweather.R
import it.apexweather.data.AppSettings
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.LocalFormats

/**
 * The three notification switches.
 *
 * Android's permission is asked for only once something has been switched on: a settings screen
 * that demands permission before the reader has expressed any interest is the pattern this avoids.
 */
@Composable
fun NotificationSettings(
    settings: AppSettings,
    allowed: Boolean,
    onRequestPermission: () -> Unit,
    onSummary: (Boolean) -> Unit,
    onSummaryHour: (Int) -> Unit,
    onRain: (Boolean) -> Unit,
    onWarnings: (Boolean) -> Unit,
) {
    val formats = LocalFormats.current
    SettingsGroup(
        stringResource(R.string.setting_notifications),
        modifier = Modifier.testTag("notification_settings"),
    ) {
        SwitchRow(
            Icons.Rounded.WbSunny, stringResource(R.string.setting_notif_summary), null,
            settings.notifySummary, onSummary, switchModifier = Modifier.testTag("notify_summary"),
        )
        if (settings.notifySummary) {
            Row(
                Modifier.fillMaxWidth().padding(start = 36.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.setting_notif_summary_time, Format.hourOfDay(settings.notifySummaryHour, formats)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.testTag("notify_summary_hour"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Wrapping rather than clamping: 0 is the hour before 23, and a reader stepping
                    // down from midnight means late evening, not "stay at midnight".
                    FilledTonalIconButton(
                        onClick = { onSummaryHour((settings.notifySummaryHour + 23) % 24) },
                        modifier = Modifier.testTag("notify_hour_down"),
                    ) { Icon(Icons.Filled.Remove, contentDescription = null) }
                    FilledTonalIconButton(
                        onClick = { onSummaryHour((settings.notifySummaryHour + 1) % 24) },
                        modifier = Modifier.testTag("notify_hour_up"),
                    ) { Icon(Icons.Filled.Add, contentDescription = null) }
                }
            }
        }

        SwitchRow(
            Icons.Rounded.WaterDrop, stringResource(R.string.setting_notif_rain), null,
            settings.notifyRain, onRain, switchModifier = Modifier.testTag("notify_rain"),
        )
        SwitchRow(
            Icons.Rounded.Warning, stringResource(R.string.setting_notif_warning), null,
            settings.notifyWarnings, onWarnings, switchModifier = Modifier.testTag("notify_warning"),
        )

        // Only worth saying once something is switched on and Android is still in the way.
        if (settings.anyNotification && !allowed) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.setting_notif_permission),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.weight(1f).testTag("notify_permission_hint"),
                )
                Button(onClick = onRequestPermission, modifier = Modifier.testTag("notify_grant")) {
                    Text(stringResource(R.string.setting_notif_grant))
                }
            }
        }
    }
}
```

Delete `NotificationSettings` and the private `SwitchRow` from `SettingsScreen.kt`.

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the existing settings tests, unchanged**

Run: `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.settings.SettingsContentTest`
Expected: PASS. **The tags moved from the row to the switch**, so `assertIsOff()` and `performClick()` on `notify_summary` still address a switch — which is what those tests assert. If a test fails here, the tag went on the wrong node; put it back on the `Switch`, not the row.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/settings/NotificationSettings.kt \
        app/src/main/kotlin/it/apexweather/ui/settings/SettingsScreen.kt
git commit -m "refactor: the notification group is its own file, built from the new rows"
```

---

### Task 3: The page

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/ui/settings/SettingsContent.kt`
- Modify: `app/src/main/kotlin/it/apexweather/ui/settings/SettingsScreen.kt` (keeps only the wiring)
- Modify: `app/src/main/res/values/strings.xml`, `values-it/strings.xml`, `values-en/strings.xml`
- Test: `app/src/androidTest/kotlin/it/apexweather/ui/settings/SettingsContentTest.kt` (added to, never renamed)

**Interfaces:**
- Consumes: `SettingsGroup`, `SettingRow`, `SwitchRow`, `NavRow` (Task 1); `NotificationSettings` (Task 2).
- Produces: `SettingsContent(...)` with its existing parameters plus `onOpenStations: (() -> Unit)? = null`.

- [ ] **Step 1: Add the group headings**

`values/strings.xml`:

```xml
    <string name="settings_group_place">Ort und Sprache</string>
    <string name="settings_group_display">Anzeige</string>
    <string name="settings_group_stations">Stationen</string>
    <string name="settings_group_app">App</string>
```

`values-it/strings.xml`:

```xml
    <string name="settings_group_place">Luogo e lingua</string>
    <string name="settings_group_display">Visualizzazione</string>
    <string name="settings_group_stations">Stazioni</string>
    <string name="settings_group_app">App</string>
```

`values-en/strings.xml`:

```xml
    <string name="settings_group_place">Place and language</string>
    <string name="settings_group_display">Display</string>
    <string name="settings_group_stations">Stations</string>
    <string name="settings_group_app">App</string>
```

- [ ] **Step 2: Write the failing tests**

Append to `SettingsContentTest.kt` (do not touch what is there):

```kotlin
    /** Five groups, and the reader should be able to see which is which. */
    @Test
    fun theGroupHeadingsAreOnScreen() {
        show()
        listOf("group_place", "group_display", "group_stations", "notification_settings", "group_app")
            .forEach { rule.onNodeWithTag(it).performScrollTo().assertIsDisplayed() }
    }

    /**
     * The way to the stations screen, where somebody goes looking for it. It is otherwise reachable
     * only from the station card four scrolls down the home screen.
     */
    @Test
    fun theStationsRowIsOfferedWhenThereIsSomewhereToGo() {
        var opened = false
        show(AppSettings(amateurStations = true, wuApiKey = "k"), onOpenStations = { opened = true })
        rule.onNodeWithTag("settings_nearby_stations").performScrollTo().performClick()
        assertTrue("the stations row did not report", opened)
    }

    /** No key, no neighbourhood to list, so no row promising one. */
    @Test
    fun theStationsRowIsAbsentWithoutSomewhereToGo() {
        show(AppSettings(amateurStations = true, wuApiKey = "k"), onOpenStations = null)
        rule.onNodeWithTag("settings_nearby_stations").assertDoesNotExist()
    }

    /** Refreshing is a row in the App group now, not a filled button in the middle of the page. */
    @Test
    fun refreshingIsStillReachable() {
        var refreshed = false
        show(onRefresh = { refreshed = true })
        rule.onNodeWithTag("refresh_now").performScrollTo().performClick()
        assertTrue("the refresh row did not report", refreshed)
    }
```

And extend the `show` helper — additively, keeping every existing default:

```kotlin
    private fun show(
        settings: AppSettings = AppSettings(),
        notificationsAllowed: Boolean = true,
        onNotifySummary: (Boolean) -> Unit = {},
        onNotifySummaryHour: (Int) -> Unit = {},
        onWuApiKey: (String) -> Unit = {},
        onRefresh: () -> Unit = {},
        onOpenStations: (() -> Unit)? = {},
    ) = rule.setContent {
        ApexTheme {
            SettingsContent(
                settings = settings,
                onLanguage = {}, onWindUnit = {}, onAnimations = {}, onAmateurStations = {},
                onWuApiKey = onWuApiKey, onRefresh = onRefresh,
                onOpenStations = onOpenStations,
                notificationsAllowed = notificationsAllowed,
                onNotifySummary = onNotifySummary,
                onNotifySummaryHour = onNotifySummaryHour,
            )
        }
    }
```

- [ ] **Step 3: Run them and watch them fail**

Run: `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.settings.SettingsContentTest`
Expected: FAIL to compile — `No parameter with name 'onOpenStations'`.

- [ ] **Step 4: Write `SettingsContent.kt`**

Move `SettingsContent` out of `SettingsScreen.kt` into its own file, and rebuild its body as five groups. The shape:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    settings: AppSettings,
    onLanguage: (LanguageSetting) -> Unit,
    onWindUnit: (WindUnit) -> Unit,
    onAnimations: (Boolean) -> Unit,
    onAmateurStations: (Boolean) -> Unit,
    onWuApiKey: (String) -> Unit,
    onRefresh: () -> Unit,
    placeName: String = "",
    onOpenPlaces: () -> Unit = {},
    /** Null where there is nothing to open — no key, so no neighbourhood to list. */
    onOpenStations: (() -> Unit)? = null,
    notificationsAllowed: Boolean = true,
    onRequestNotifications: () -> Unit = {},
    onNotifySummary: (Boolean) -> Unit = {},
    onNotifySummaryHour: (Int) -> Unit = {},
    onNotifyRain: (Boolean) -> Unit = {},
    onNotifyWarnings: (Boolean) -> Unit = {},
    updateSection: @Composable () -> Unit = {},
) {
    // No Surface. Every other screen in this app is white text over the sky AppNavigation draws
    // once behind all of them, and this was the only one that painted an opaque slab over it — in
    // a colour that belongs to no part of the app and does not change with the weather. Safe
    // because GlassCard paints black: SkyContrast holds every palette to 4,5:1 against white and a
    // card can only darken what is behind it.
    Column(
        Modifier.fillMaxSize().testTag("settings_screen")
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.settings),
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
        )
        // … the five groups …
    }
}
```

The five groups, in order:

**Ort & Sprache** (`Modifier.testTag("group_place")`):

```kotlin
        SettingsGroup(stringResource(R.string.settings_group_place), Modifier.testTag("group_place")) {
            NavRow(
                Icons.Rounded.Place,
                label = stringResource(R.string.setting_place),
                value = placeName,
                onClick = onOpenPlaces,
                onClickLabel = stringResource(R.string.change_place),
                modifier = Modifier.testTag("settings_place"),
            )
            SettingRow(Icons.Rounded.Language, stringResource(R.string.setting_language)) {}
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                LanguageSetting.entries.forEachIndexed { i, l ->
                    // icon = {} for the reason given on the comparison screen's row: the reserved
                    // tick costs every segment about 24 dp, and the fill already says which is on.
                    SegmentedButton(
                        selected = settings.language == l,
                        onClick = { onLanguage(l) },
                        shape = SegmentedButtonDefaults.itemShape(i, LanguageSetting.entries.size),
                        icon = {},
                    ) {
                        // "System" beside three two-letter codes; see CompactLabel.
                        CompactLabel {
                            Text(
                                when (l) {
                                    LanguageSetting.SYSTEM -> stringResource(R.string.lang_system)
                                    LanguageSetting.DE -> "DE"
                                    LanguageSetting.IT -> "IT"
                                    LanguageSetting.EN -> "EN"
                                },
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
```

The language `SettingRow` with an empty trailing slot is the label; the segmented row goes under it at full width, because four options worth reading do not fit beside a label and squeezing them hits `CompactLabel`'s ceiling at a 2x font scale.

**Anzeige** (`group_display`):

```kotlin
        SettingsGroup(stringResource(R.string.settings_group_display), Modifier.testTag("group_display")) {
            SettingRow(Icons.Rounded.Air, stringResource(R.string.setting_wind)) {
                // Two options fit beside their label; four do not. Worth about 110 dp.
                SingleChoiceSegmentedButtonRow {
                    WindUnit.entries.forEachIndexed { i, u ->
                        SegmentedButton(
                            selected = settings.windUnit == u,
                            onClick = { onWindUnit(u) },
                            shape = SegmentedButtonDefaults.itemShape(i, WindUnit.entries.size),
                            icon = {},
                        ) { CompactLabel { Text(if (u == WindUnit.KMH) "km/h" else "m/s", maxLines = 1) } }
                    }
                }
            }
            SwitchRow(
                Icons.Rounded.AutoAwesome, stringResource(R.string.setting_animations), null,
                settings.animations, onAnimations,
            )
        }
```

**Stationen** (`group_stations`) holds the amateur switch with its note, the key field, the verdict and the stations row. The key field and the verdict are **moved verbatim** from the current file — the local `typed`/`sent` state, its comment, the `LaunchedEffect`, the `supportingText`, the `setting_wu_key` tag and the whole `when (settings.wuKeyVerdict)` block with its `wu_key_verdict` tag. Nothing in that block changes except the colours: `Color.White` where it read the theme's `onSurface`, and the verdict's own three colours stay as they are (`primary`, `onSurfaceVariant`, `error`), which read correctly on glass. The stations row:

```kotlin
            onOpenStations?.let { open ->
                NavRow(
                    Icons.Rounded.Sensors,
                    label = stringResource(R.string.stations_open),
                    value = null,
                    onClick = open,
                    modifier = Modifier.testTag("settings_nearby_stations"),
                )
            }
```

**Mitteilungen**: `NotificationSettings(...)` from Task 2, called with exactly the arguments it takes today. Its `SettingsGroup` keeps the tag it has always had, `notification_settings` — a node carries one tag, and the existing one is the one tests already use.

**App** (`group_app`):

```kotlin
        SettingsGroup(stringResource(R.string.settings_group_app), Modifier.testTag("group_app")) {
            SettingRow(
                Icons.Rounded.Refresh,
                stringResource(R.string.refresh_now),
                modifier = Modifier.clickable(onClick = onRefresh).testTag("refresh_now"),
            ) {}
            updateSection()
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.about, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
            // Selectable so the exact build can be copied into a bug report.
            SelectionContainer {
                Column(Modifier.testTag("about_build")) {
                    Text(
                        stringResource(R.string.about_build, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_TYPE),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    Text(
                        stringResource(R.string.about_commit, BuildConfig.GIT_HASH, BuildConfig.GIT_DATE),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
            Text(
                stringResource(R.string.attribution),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
```

`SettingsScreen.kt` keeps the ViewModel wiring, the permission plumbing and nothing else, and passes `onOpenStations` through.

- [ ] **Step 5: Run the whole settings suite**

Run: `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.settings.SettingsContentTest,it.apexweather.ui.settings.SettingsRowsTest`
Expected: PASS, every existing case included.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/settings/ \
        app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml app/src/main/res/values-en/strings.xml \
        app/src/androidTest/kotlin/it/apexweather/ui/settings/SettingsContentTest.kt
git commit -m "feat: settings is five groups over the sky, like every other screen"
```

---

### Task 4: The stations row is wired up

**Files:**
- Modify: `app/src/main/kotlin/it/apexweather/ui/navigation/AppNavigation.kt:277-290`

**Interfaces:**
- Consumes: `SettingsScreen(onOpenStations = …)` (Task 3).
- Produces: nothing later depends on.

- [ ] **Step 1: Pass the route through**

In the `SettingsRoute` composable, add:

```kotlin
                        // The same gate as the home screen's entrance, read from the same place:
                        // no key, no neighbourhood to list. A detail pushed onto the Settings back
                        // stack rather than a top-level destination, so back returns here.
                        onOpenStations = dropUnlessResumed { nav.navigate(NearbyStationsRoute) }
                            .takeIf { homeState.settings.wuApiKey != null },
```

`homeState` is already collected at the top of `AppNavigation` (`val homeState by homeVm.state.collectAsStateWithLifecycle()`) and `HomeUiState.settings` is what `HomeContent` reads for the same gate, so nothing new is collected.

- [ ] **Step 2: Compile and check the whole app builds**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Check navigation by hand on the emulator**

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/debug/ApexWeather-debug.apk
adb -s emulator-5554 shell am start -n it.apexweather/.MainActivity
```

Open Mehr, and confirm: with no key the stations row is absent; the back arrow on the stations screen returns to Settings, not to Heute.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/it/apexweather/ui/navigation/AppNavigation.kt
git commit -m "feat: the stations screen has an entrance where people look for it"
```

---

### Task 5: The gates, the phone, and the release

- [ ] **Step 1: Run every JVM gate**

```bash
./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintDebug
```

Expected: BUILD SUCCESSFUL. Lint's `UnusedResources` is an error, and this task is where a string left behind by the redesign shows up — check for `setting_notifications` and anything else the old layout used that the new one does not.

- [ ] **Step 2: Run the whole instrumented suite**

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

Expected: BUILD SUCCESSFUL. **Not on the phone.**

- [ ] **Step 3: Check it at a 2x font scale on the emulator**

```bash
adb -s emulator-5554 shell settings put system font_scale 2.0
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.settings.SettingsContentTest
adb -s emulator-5554 shell settings put system font_scale 1.0
```

Expected: PASS. This is where the inline wind pair breaks if it is going to, and it is how the navigation bar's `CompactLabel` ceiling was found.

- [ ] **Step 4: Build the release and smoke it**

```bash
./gradlew :app:assembleRelease && ./tools/release-smoke.sh emulator-5554
```

Expected: `release smoke: OK`.

- [ ] **Step 5: Verify on the phone by hand**

`adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/release/ApexWeather-release.apk` keeps the app's data. Then look at, and write down:

1. The screen over a daylight sky and over a night one — the cards read as cards, the text is legible.
2. Font scale 2,0: the wind pair does not wrap, the language segments do not clip, no row's two lines collide.
3. All three languages: the four group headings read correctly.
4. The stations row goes to the stations screen, and its back arrow returns to Settings.
5. Every control still does what it did: language switches, wind switches, the key field takes a typed key whole, the three notification switches, the hour wraps at midnight, refresh refreshes.

- [ ] **Step 6: Update `CLAUDE.md`**

Add to the conventions: that Settings is five `GlassCard` groups over the shared sky like every other screen and must stay that way; that the row primitives live in `SettingsRows.kt` and a new setting is a row rather than a fresh layout; that the icon column is decorative (`contentDescription = null`) because every icon sits beside a label saying the same thing; and that wind is inline while language is not, with the `CompactLabel` reason.

- [ ] **Step 7: Commit, merge, tag, push, verify the published APK**

```bash
git add CLAUDE.md && git commit -m "docs: the settings screen's shape"
git checkout main && git merge --no-ff <branch> -m "Merge: settings joins the rest of the app"
# bump apexVersionName / apexVersionCode in app/build.gradle.kts, then:
git commit -am "chore: v0.32.0"
git tag -a v0.32.0 -m "v0.32.0" && git push origin main && git push origin v0.32.0
```

Then download the published APK, install it, launch it, and confirm a non-empty `pidof it.apexweather` and `versionName=0.32.0`. A broken v0.29.0 shipped because this step was skipped. Note that `gh release view --json assets` can report *no assets* for several minutes after a successful release while the asset endpoint says `state=uploaded` — check `gh api repos/13/apexweather/releases/<id>/assets` or curl the download URL before concluding anything is wrong.
