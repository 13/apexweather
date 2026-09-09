package it.apexweather.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Every state the update row can be in. The release page is carried wherever it is known, because
 * opening it by hand is the fallback that works even when the installer permission is refused.
 */
sealed interface UpdateUiState {
    /** The release page, where this state knows it. */
    val releaseUrl: String? get() = null

    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState

    data class Available(val version: AppVersion, val sizeBytes: Long, override val releaseUrl: String) : UpdateUiState

    data class Downloading(val version: AppVersion, val bytes: Long, val total: Long, override val releaseUrl: String) : UpdateUiState {
        /** Null while the size is unknown, so the bar can be indeterminate rather than wrong. */
        val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
    }

    /**
     * Downloaded and ready to hand to the installer. [digestVerified] is false only for a release
     * that published no checksum, which the row says rather than passing over in silence.
     */
    data class Ready(val version: AppVersion, val file: File, val digestVerified: Boolean, override val releaseUrl: String) : UpdateUiState

    /** Handed to the platform installer, which is now showing its own confirmation. */
    data object Installing : UpdateUiState

    /** The user has not granted permission to install packages, so nothing can be handed over yet. */
    data class NeedsPermission(override val releaseUrl: String) : UpdateUiState

    data class Failed(val failure: UpdateFailure, override val releaseUrl: String? = null) : UpdateUiState
    data class InstallFailed(val message: String?, override val releaseUrl: String?) : UpdateUiState
}

/**
 * Drives one manual update: check, download, install. There is no background check and no
 * notification; the only way into this is the button in the settings sheet.
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val repository: UpdateRepository,
    private val installer: ApkInstaller,
) : ViewModel() {

    private val mutableState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = mutableState.asStateFlow()

    /** What the last check found, kept so the download does not have to rebuild it from the UI. */
    private var available: UpdateCheck.Available? = null

    /** The downloaded file, kept so a cancelled install returns to the offer rather than to nothing. */
    private var ready: UpdateUiState.Ready? = null

    private var work: Job? = null

    init {
        viewModelScope.launch {
            InstallResultReceiver.results.collect { result ->
                mutableState.value = when (result) {
                    // The process is about to be replaced; there is nothing further to show.
                    InstallResult.Success -> UpdateUiState.Installing
                    InstallResult.Cancelled -> ready ?: UpdateUiState.Idle
                    is InstallResult.Failed -> UpdateUiState.InstallFailed(result.message, mutableState.value.releaseUrl)
                }
            }
        }
    }

    fun check() {
        work?.cancel()
        available = null
        ready = null
        mutableState.value = UpdateUiState.Checking
        work = viewModelScope.launch {
            mutableState.value = when (val result = repository.check()) {
                UpdateCheck.UpToDate -> UpdateUiState.UpToDate
                is UpdateCheck.Failed -> UpdateUiState.Failed(result.failure, result.releaseUrl)
                is UpdateCheck.Available -> {
                    available = result
                    UpdateUiState.Available(result.version, result.asset.size, result.releaseUrl)
                }
            }
        }
    }

    fun download() {
        val update = available ?: return
        work?.cancel()
        work = viewModelScope.launch {
            repository.download(update).collect { progress ->
                mutableState.value = when (progress) {
                    is DownloadProgress.Running ->
                        UpdateUiState.Downloading(update.version, progress.bytes, progress.total, update.releaseUrl)
                    is DownloadProgress.Done ->
                        UpdateUiState.Ready(update.version, progress.file, progress.digestVerified, update.releaseUrl)
                            .also { ready = it }
                    is DownloadProgress.Failed -> UpdateUiState.Failed(progress.failure, update.releaseUrl)
                }
            }
        }
    }

    /**
     * Hands the downloaded file to the platform. Android shows its own confirmation on top of
     * this; the row never claims the update happens on its own.
     */
    fun install() {
        val file = ready ?: return
        if (!installer.canInstall()) {
            mutableState.value = UpdateUiState.NeedsPermission(file.releaseUrl)
            return
        }
        mutableState.value = UpdateUiState.Installing
        runCatching { installer.install(file.file) }.onFailure {
            mutableState.value = UpdateUiState.InstallFailed(it.message, file.releaseUrl)
        }
    }

    /** Offered after a failed install, and after a trip to the system's install-apps screen. */
    fun retryInstall() {
        val file = ready ?: return
        mutableState.value = file
        install()
    }

    /**
     * Called when the app comes back to the foreground. Granting the permission happens in the
     * system's settings, so without this the row would still be asking for something the user has
     * already given, with no way forward but to open that screen again.
     */
    fun onResumed() {
        if (mutableState.value is UpdateUiState.NeedsPermission && installer.canInstall()) {
            mutableState.value = ready ?: UpdateUiState.Idle
        }
    }

    fun unknownSourcesIntent() = installer.unknownSourcesIntent()

    fun dismiss() {
        work?.cancel()
        mutableState.value = UpdateUiState.Idle
    }
}
