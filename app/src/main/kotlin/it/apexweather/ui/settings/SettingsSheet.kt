package it.apexweather.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.apexweather.BuildConfig
import it.apexweather.R
import it.apexweather.data.AppSettings
import it.apexweather.data.LanguageSetting
import it.apexweather.data.WindUnit
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.LocalFormats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: AppSettings,
    onLanguage: (LanguageSetting) -> Unit,
    onWindUnit: (WindUnit) -> Unit,
    onAnimations: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    /** The chosen place, and the way to a different one. Empty until the catalogue has been read. */
    placeName: String = "",
    onOpenPlaces: () -> Unit = {},
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
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Open fully: the sheet is long enough now that a half-height one would start folded.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("settings_sheet"),
    ) {
        // The sheet is no longer short: language, wind, animations, three notification switches and
        // their hour, the update row and the build lines run past a phone screen in landscape or at a
        // large font scale, and ModalBottomSheet scrolls nothing by itself.
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium)

            // First, because it is the setting that changes everything else on the screen.
            Text(stringResource(R.string.setting_place), style = MaterialTheme.typography.labelSmall)
            Row(
                Modifier.fillMaxWidth()
                    .clickable(onClickLabel = stringResource(R.string.change_place), onClick = onOpenPlaces)
                    .padding(vertical = 8.dp)
                    .testTag("settings_place"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(placeName, style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.Filled.ChevronRight, contentDescription = null)
            }

            Text(stringResource(R.string.setting_language), style = MaterialTheme.typography.labelSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                LanguageSetting.entries.forEachIndexed { i, l ->
                    SegmentedButton(selected = settings.language == l, onClick = { onLanguage(l) }, shape = SegmentedButtonDefaults.itemShape(i, LanguageSetting.entries.size)) {
                        Text(when (l) { LanguageSetting.SYSTEM -> stringResource(R.string.lang_system); LanguageSetting.DE -> "DE"; LanguageSetting.IT -> "IT"; LanguageSetting.EN -> "EN" })
                    }
                }
            }

            Text(stringResource(R.string.setting_wind), style = MaterialTheme.typography.labelSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                WindUnit.entries.forEachIndexed { i, u ->
                    SegmentedButton(selected = settings.windUnit == u, onClick = { onWindUnit(u) }, shape = SegmentedButtonDefaults.itemShape(i, WindUnit.entries.size)) {
                        Text(if (u == WindUnit.KMH) "km/h" else "m/s")
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.setting_animations), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = settings.animations, onCheckedChange = onAnimations)
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

            Button(onClick = { onRefresh(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.refresh_now)) }

            updateSection()

            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.about, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
            // Selectable so the exact build can be copied into a bug report.
            SelectionContainer {
                Column(Modifier.testTag("about_build")) {
                    Text(
                        stringResource(R.string.about_build, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_TYPE),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        stringResource(R.string.about_commit, BuildConfig.GIT_HASH, BuildConfig.GIT_DATE),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(stringResource(R.string.attribution), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * The three notification switches.
 *
 * Android's permission is asked for only once something has been switched on: a settings sheet that
 * demands permission before the reader has expressed any interest is the pattern this avoids.
 */
@Composable
private fun NotificationSettings(
    settings: AppSettings,
    allowed: Boolean,
    onRequestPermission: () -> Unit,
    onSummary: (Boolean) -> Unit,
    onSummaryHour: (Int) -> Unit,
    onRain: (Boolean) -> Unit,
    onWarnings: (Boolean) -> Unit,
) {
    val formats = LocalFormats.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("notification_settings")) {
        Text(stringResource(R.string.setting_notifications), style = MaterialTheme.typography.labelSmall)

        SwitchRow(stringResource(R.string.setting_notif_summary), settings.notifySummary, "notify_summary", onSummary)
        if (settings.notifySummary) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.setting_notif_summary_time, Format.hourOfDay(settings.notifySummaryHour, formats)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("notify_summary_hour"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Wrapping rather than clamping: 0 is the hour before 23, and a reader stepping
                    // down from midnight means late evening, not "stay at midnight".
                    FilledTonalIconButton(onClick = { onSummaryHour((settings.notifySummaryHour + 23) % 24) }, modifier = Modifier.testTag("notify_hour_down")) {
                        Icon(Icons.Filled.Remove, contentDescription = null)
                    }
                    FilledTonalIconButton(onClick = { onSummaryHour((settings.notifySummaryHour + 1) % 24) }, modifier = Modifier.testTag("notify_hour_up")) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                    }
                }
            }
        }

        SwitchRow(stringResource(R.string.setting_notif_rain), settings.notifyRain, "notify_rain", onRain)
        SwitchRow(stringResource(R.string.setting_notif_warning), settings.notifyWarnings, "notify_warning", onWarnings)

        // Only worth saying once something is switched on and Android is still in the way.
        if (settings.anyNotification && !allowed) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.setting_notif_permission),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f).testTag("notify_permission_hint"),
                )
                Button(onClick = onRequestPermission, modifier = Modifier.testTag("notify_grant")) {
                    Text(stringResource(R.string.setting_notif_grant))
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}
