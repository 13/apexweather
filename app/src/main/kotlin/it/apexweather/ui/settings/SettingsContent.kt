package it.apexweather.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.apexweather.BuildConfig
import it.apexweather.R
import it.apexweather.data.AppSettings
import it.apexweather.data.LanguageSetting
import it.apexweather.data.WindUnit
import it.apexweather.data.WuKeyVerdict
import it.apexweather.domain.SouthTyrol
import it.apexweather.ui.common.CompactLabel
import it.apexweather.ui.common.MAX_FONT_SCALE
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.LocalFormats
import java.time.Instant

/**
 * The settings page: five groups over the same sky as every other screen.
 *
 * **There is no `Surface` here, and there must not be one.** `SkyBackground` is drawn once in
 * `AppNavigation`, behind every destination, and Heute, Karte, Vergleich, Bericht and the stations
 * screen are all white text on `GlassCard`s over it. This screen used to put an opaque
 * `Surface(colorScheme.surface)` over the whole thing — a slab in a colour that belongs to no part
 * of the app and does not change with the weather — so opening settings did not look like moving
 * to another page of the same app.
 *
 * Safe as well as consistent: `SkyContrast.darkenForWhiteText` holds every palette to 4,5:1 against
 * white, and `GlassCard` paints **black**, so a card can only darken a sky that has already been
 * held to the floor.
 *
 * No pinned top bar, for the same reason: no screen in this app has one, this is a bottom-bar
 * destination and needs no back arrow, and being the only screen with a collapsing bar would put
 * back the inconsistency the rest of this removes.
 */
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
    /** The chosen place, and the way to a different one. Empty until the catalogue has been read. */
    placeName: String = "",
    onOpenPlaces: () -> Unit = {},
    /** Null where there is nothing to open — no key, so no neighbourhood to list. */
    onOpenStations: (() -> Unit)? = null,
    /**
     * Notifications. Defaulted so a test, or any caller that does not care, can leave them out; the
     * switches then still render and simply lead nowhere.
     */
    notificationsAllowed: Boolean = true,
    onRequestNotifications: () -> Unit = {},
    onNotifySummary: (Boolean) -> Unit = {},
    onNotifySummaryHour: (Int) -> Unit = {},
    onNotifyRain: (Boolean) -> Unit = {},
    onNotifyWarnings: (Boolean) -> Unit = {},
    /**
     * The in-app update row, passed in as a slot so this file imports nothing from the update
     * package. That keeps the feature removable in one piece, which matters because the permission
     * it needs is restricted on the Play Store.
     */
    updateSection: @Composable () -> Unit = {},
    /** The diagnostics export, a slot for the same reason as [updateSection]. */
    diagnosticsSection: @Composable () -> Unit = {},
) {
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

        // First, because it is the setting that changes everything else on the screen.
        SettingsGroup(stringResource(R.string.settings_group_place), Modifier.testTag("group_place")) {
            NavRow(
                Icons.Rounded.Place,
                label = stringResource(R.string.setting_place),
                value = placeName,
                onClick = onOpenPlaces,
                onClickLabel = stringResource(R.string.change_place),
                modifier = Modifier.testTag("settings_place"),
            )
            SettingLabel(Icons.Rounded.Language, stringResource(R.string.setting_language))
            // Full width under its label rather than beside it: four options worth reading do not
            // fit next to a label, and squeezing them hits CompactLabel's ceiling at a 2x font
            // scale, which is the exact failure that rule exists for.
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

        SettingsGroup(stringResource(R.string.settings_group_display), Modifier.testTag("group_display")) {
            // Two options fit beside their label and four do not, so wind is inline where
            // language is not — worth about 110 dp. **Except at a large text size**: the label has
            // the row's leftover width, and at 2x on the phone "Windeinheit" broke mid-word into
            // "Windeinh / eit". No layout fixes that, the same way none fixes a fifth of 384 dp in
            // the navigation bar, so past CompactLabel's own ceiling the pair stacks under its
            // label exactly as the language row does.
            val windFitsBesideItsLabel = LocalDensity.current.fontScale <= MAX_FONT_SCALE
            val windSegments: @Composable () -> Unit = {
                SingleChoiceSegmentedButtonRow(if (windFitsBesideItsLabel) Modifier else Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
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
            if (windFitsBesideItsLabel) {
                SettingRow(Icons.Rounded.Air, stringResource(R.string.setting_wind)) { windSegments() }
            } else {
                SettingLabel(Icons.Rounded.Air, stringResource(R.string.setting_wind))
                windSegments()
            }
            SwitchRow(
                Icons.Rounded.AutoAwesome, stringResource(R.string.setting_animations), null,
                settings.animations, onAnimations,
            )
        }

        SettingsGroup(stringResource(R.string.settings_group_stations), Modifier.testTag("group_stations")) {
            // The label alone would not say what the switch costs. A private station is usually the
            // better thermometer — it is only in the catalogue at all because it beat the province's
            // on eight weeks of measured stability — but it is one nobody maintains, and the second
            // line is what lets a reader who can see out of the window make that call.
            SwitchRow(
                Icons.Rounded.Thermostat,
                stringResource(R.string.setting_amateur_stations),
                stringResource(R.string.setting_amateur_stations_note),
                settings.amateurStations,
                onAmateurStations,
                switchModifier = Modifier.testTag("setting_amateur_stations"),
            )

            // Only while the feature is on: a key field under a switch that is off is a question
            // about something that is not happening.
            if (settings.amateurStations) {
                // The field keeps its own text and never reads back what it just wrote. Driving
                // `value` from `settings.wuApiKey` meant every keystroke went out to DataStore and
                // came back a recomposition later, so the next character was committed against a
                // stale value and a stale cursor: measured on the phone on 2026-09-23, the same
                // 32-character key typed one character at a time (450 ms apart, which is not fast)
                // arrived as "b2dfb3ef31f40bcadf8ef31f30bc603" — reordered, one short, three times
                // out of three, while the place picker's search box, which holds its own state,
                // took the identical input exactly. A key is the one string in this app nobody can
                // proof-read, so a character out of place is silent and the app simply reads no
                // station. Pasting hid it, because a paste is a single commit.
                var typed by rememberSaveable { mutableStateOf(settings.wuApiKey.orEmpty()) }
                // A change this field did not make — the store's first emission, or a key cleared
                // somewhere else — is still adopted; an echo of our own write is not.
                var sent by rememberSaveable { mutableStateOf(typed) }
                LaunchedEffect(settings.wuApiKey) {
                    val stored = settings.wuApiKey.orEmpty()
                    if (stored != sent) {
                        typed = stored
                        sent = stored
                    }
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it; sent = it; onWuApiKey(it) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.setting_wu_key)) },
                    supportingText = { Text(stringResource(R.string.setting_wu_key_note)) },
                    // Shown as typed rather than masked. It is a quota key for a free weather API
                    // and not a password, and the one thing a reader does with it is paste it and
                    // check by eye that it arrived whole.
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("setting_wu_key"),
                )
                // What the app last saw happen to this key, rather than what the reader hopes.
                // Three failures and not one, because a refusal wants re-typing, an exhausted quota
                // wants waiting, and a dropped connection wants nothing at all.
                val verdict = when (settings.wuKeyVerdict) {
                    WuKeyVerdict.UNCHECKED -> null
                    WuKeyVerdict.CHECKING -> stringResource(R.string.wu_key_checking)
                    WuKeyVerdict.GOOD -> stringResource(
                        R.string.wu_key_good,
                        settings.wuKeyCheckedAtMs
                            ?.let { Format.time(Instant.ofEpochMilli(it), SouthTyrol.ZONE, LocalFormats.current) }
                            .orEmpty(),
                    )
                    WuKeyVerdict.REFUSED -> stringResource(R.string.wu_key_refused)
                    WuKeyVerdict.OVER_QUOTA -> stringResource(R.string.wu_key_over_quota)
                    WuKeyVerdict.OFFLINE -> stringResource(R.string.wu_key_offline)
                }
                verdict?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = when (settings.wuKeyVerdict) {
                            WuKeyVerdict.GOOD -> MaterialTheme.colorScheme.primary
                            WuKeyVerdict.CHECKING -> Color.White.copy(alpha = 0.7f)
                            else -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp).testTag("wu_key_verdict"),
                    )
                }
            }

            // The way to the neighbourhood, where somebody goes looking for it: it is otherwise
            // reachable only from the station card four scrolls down the home screen.
            onOpenStations?.let { open ->
                NavRow(
                    Icons.Rounded.Sensors,
                    label = stringResource(R.string.stations_open),
                    value = null,
                    onClick = open,
                    modifier = Modifier.testTag("settings_nearby_stations"),
                )
            }
        }

        NotificationSettings(
            settings = settings,
            allowed = notificationsAllowed,
            onRequestPermission = onRequestNotifications,
            onSummary = onNotifySummary,
            onSummaryHour = onNotifySummaryHour,
            onRain = onNotifyRain,
            onWarnings = onNotifyWarnings,
        )

        SettingsGroup(stringResource(R.string.settings_group_app), Modifier.testTag("group_app")) {
            // A row rather than the full-width filled button this used to be. A filled button is
            // the loudest thing a Material screen can hold, and it was shouting from the middle of
            // a list of preferences.
            SettingRow(
                Icons.Rounded.Refresh,
                stringResource(R.string.refresh_now),
                modifier = Modifier.clickable(onClick = onRefresh).testTag("refresh_now"),
            ) {}
            updateSection()
            diagnosticsSection()
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
    }
}
