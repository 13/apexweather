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
