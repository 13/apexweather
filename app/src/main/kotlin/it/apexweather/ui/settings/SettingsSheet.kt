package it.apexweather.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: AppSettings,
    onLanguage: (LanguageSetting) -> Unit,
    onWindUnit: (WindUnit) -> Unit,
    onAnimations: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // The sheet is short: open it fully so the refresh button and the attribution are never under the navigation bar.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("settings_sheet"),
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium)

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

            Button(onClick = { onRefresh(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.refresh_now)) }

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
