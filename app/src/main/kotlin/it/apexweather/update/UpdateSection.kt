package it.apexweather.update

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.apexweather.BuildConfig
import it.apexweather.R
import java.util.Locale

/**
 * The update row in the settings sheet: one button, and whatever the last press produced.
 *
 * The settings sheet takes this as a slot and imports nothing from this package, so removing the
 * feature means deleting this directory, the REQUEST_INSTALL_PACKAGES line and its receiver in the
 * manifest, and the single call in AppNavigation.
 */
@Composable
fun UpdateSection(modifier: Modifier = Modifier, viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The install permission is granted in the system's settings, so the row has to look again
    // once the app is back in front of the user.
    LifecycleResumeEffect(viewModel) {
        viewModel.onResumed()
        onPauseOrDispose {}
    }
    UpdateRow(
        state = state,
        onCheck = viewModel::check,
        onDownload = viewModel::download,
        onInstall = viewModel::install,
        onGrantPermission = { context.startActivity(viewModel.unknownSourcesIntent()) },
        onRetryInstall = viewModel::retryInstall,
        onOpenReleasePage = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) },
        modifier = modifier,
    )
}

/** The row itself, with every decision hoisted out, so its states can be driven from a test. */
@Composable
fun UpdateRow(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onGrantPermission: () -> Unit,
    onRetryInstall: () -> Unit,
    onOpenReleasePage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag("update_row"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val status = statusText(state)
        if (status != null) {
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().testTag("update_status"),
            )
        }

        if (state is UpdateUiState.Downloading) {
            val fraction = (state as UpdateUiState.Downloading).fraction
            if (fraction == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("update_progress"))
            } else {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().testTag("update_progress"))
            }
        }

        val action = actionFor(state)
        if (action != null) {
            OutlinedButton(
                onClick = {
                    when (action.second) {
                        Action.CHECK -> onCheck()
                        Action.DOWNLOAD -> onDownload()
                        Action.INSTALL -> onInstall()
                        Action.GRANT -> onGrantPermission()
                        Action.RETRY_INSTALL -> onRetryInstall()
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("update_action"),
            ) { Text(stringResource(action.first)) }
        }

        // Always available once a release is known: it works even where the installer permission
        // is refused, and it is the only path left if the download keeps failing.
        state.releaseUrl?.let { url ->
            TextButton(
                onClick = { onOpenReleasePage(url) },
                modifier = Modifier.fillMaxWidth().testTag("update_release_page"),
            ) { Text(stringResource(R.string.update_open_release)) }
        }
    }
}

private enum class Action { CHECK, DOWNLOAD, INSTALL, GRANT, RETRY_INSTALL }

@Composable
private fun statusText(state: UpdateUiState): String? = when (state) {
    UpdateUiState.Idle -> null
    UpdateUiState.Checking -> stringResource(R.string.update_checking)
    UpdateUiState.UpToDate -> stringResource(R.string.update_up_to_date, BuildConfig.VERSION_NAME)
    is UpdateUiState.Available -> stringResource(R.string.update_available, state.version.toString(), megabytes(state.sizeBytes))
    is UpdateUiState.Downloading -> stringResource(R.string.update_downloading, state.version.toString())
    is UpdateUiState.Ready ->
        if (state.digestVerified) stringResource(R.string.update_ready, state.version.toString())
        else stringResource(R.string.update_ready_unverified, state.version.toString())
    UpdateUiState.Installing -> stringResource(R.string.update_installing)
    is UpdateUiState.NeedsPermission -> stringResource(R.string.update_needs_permission)
    is UpdateUiState.Failed -> stringResource(
        when (state.failure) {
            UpdateFailure.NETWORK -> R.string.update_failed_network
            UpdateFailure.NO_APK -> R.string.update_failed_no_apk
            UpdateFailure.UNREADABLE_VERSION -> R.string.update_failed_version
            UpdateFailure.DIGEST_MISMATCH -> R.string.update_failed_digest
            UpdateFailure.STORAGE -> R.string.update_failed_storage
        }
    )
    is UpdateUiState.InstallFailed ->
        state.message?.let { stringResource(R.string.update_install_failed, it) }
            ?: stringResource(R.string.update_install_failed_unknown)
}

private fun actionFor(state: UpdateUiState): Pair<Int, Action>? = when (state) {
    UpdateUiState.Idle, UpdateUiState.UpToDate -> R.string.update_check to Action.CHECK
    UpdateUiState.Checking -> null
    is UpdateUiState.Available -> R.string.update_download to Action.DOWNLOAD
    is UpdateUiState.Downloading -> null
    is UpdateUiState.Ready -> R.string.update_install to Action.INSTALL
    UpdateUiState.Installing -> null
    is UpdateUiState.NeedsPermission -> R.string.update_grant_permission to Action.GRANT
    is UpdateUiState.Failed -> R.string.update_check to Action.CHECK
    is UpdateUiState.InstallFailed -> R.string.update_retry to Action.RETRY_INSTALL
}

/** One decimal is enough to tell a five-megabyte download from a fifty-megabyte one. */
private fun megabytes(bytes: Long): String =
    String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
