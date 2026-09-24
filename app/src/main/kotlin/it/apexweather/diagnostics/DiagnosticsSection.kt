package it.apexweather.diagnostics

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import it.apexweather.R
import it.apexweather.data.SettingsRepository
import it.apexweather.domain.SouthTyrol
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.LocalFormats
import it.apexweather.ui.settings.SettingRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _summary = MutableStateFlow(DiagnosticsSummary(0, null))
    val summary: StateFlow<DiagnosticsSummary> = _summary

    fun reload() {
        viewModelScope.launch {
            _summary.value = withContext(Dispatchers.IO) { DiagnosticsSummary.of(AppLog.directory(context)) }
        }
    }

    /**
     * Zips the directory and hands the chooser to [open]. The settings go in with the key replaced
     * by whether there is one: "is a key set" is a diagnostic fact, the key itself is not.
     */
    fun export(subject: String, title: String, open: (android.content.Intent) -> Unit) {
        viewModelScope.launch {
            val current = settings.settings.first()
            val described = current.copy(wuApiKey = current.wuApiKey?.let { "(set)" }).toString()
            val intent = runCatching {
                withContext(Dispatchers.IO) {
                    DiagnosticsExport.chooser(DiagnosticsExport.write(context, described, Instant.now()), subject, title)
                }
            }.onFailure { AppLog.e(TAG, "export failed", it) }.getOrNull() ?: return@launch
            AppLog.i(TAG, "exported")
            open(intent)
        }
    }

    private companion object {
        const val TAG = "Diagnostics"
    }
}

/**
 * The settings row that sends the diagnostics, passed into the App group as a slot the way the
 * updater is, so the settings screen imports nothing from here.
 *
 * Under the label, how many failures there are to send and since when — and nothing at all when
 * there are none, because "0 Abstürze" is a line about something that is not happening.
 */
@Composable
fun DiagnosticsSection(viewModel: DiagnosticsViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.reload() }
    val context = LocalContext.current
    val formats = LocalFormats.current
    val subject = stringResource(R.string.diagnostics_subject)
    val title = stringResource(R.string.diagnostics_share)
    val since = summary.since
    SettingRow(
        Icons.Rounded.BugReport,
        stringResource(R.string.diagnostics_share),
        modifier = Modifier
            .clickable { viewModel.export(subject, title) { context.startActivity(it) } }
            .testTag("diagnostics_share"),
        supporting = if (summary.failures > 0 && since != null) pluralStringResource(
            R.plurals.diagnostics_failures, summary.failures, summary.failures,
            Format.dayMonth(since.atZone(SouthTyrol.ZONE).toLocalDate(), formats),
        ) else null,
    ) {}
}
