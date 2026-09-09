package it.apexweather.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** What the platform installer said about a committed session. */
sealed interface InstallResult {
    data object Success : InstallResult
    data object Cancelled : InstallResult
    data class Failed(val message: String?) : InstallResult
}

/**
 * Receives the outcome of an install session.
 *
 * The system instantiates this, so results travel back to the view model through a shared flow
 * rather than an injected dependency. Its buffer means a result is never dropped for want of a
 * collector at that instant.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            // Android always asks the user itself. This is that request, not a failure.
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The typed overload is API 33; minSdk here is 31.
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    emit(InstallResult.Failed(null))
                } else {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> emit(InstallResult.Success)
            PackageInstaller.STATUS_FAILURE_ABORTED -> emit(InstallResult.Cancelled)
            else -> emit(InstallResult.Failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"))
        }
    }

    private fun emit(result: InstallResult) {
        mutableResults.tryEmit(result)
    }

    companion object {
        const val ACTION = "it.apexweather.INSTALL_RESULT"

        private val mutableResults = MutableSharedFlow<InstallResult>(extraBufferCapacity = 4)

        /** Every outcome the platform has reported since the process started. */
        val results: SharedFlow<InstallResult> = mutableResults
    }
}
