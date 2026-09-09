package it.apexweather.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.provider.Settings
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands an APK to the platform installer.
 *
 * minSdk is 31, so only the session API exists here and no FileProvider is needed. Android always
 * shows its own confirmation before installing: setRequireUserAction(false) applies to the update
 * owner of a package, which a sideloaded app is not. Nothing in the UI may promise a silent
 * update.
 *
 * The signature check is the platform's, and it already passes: every build signs with the
 * committed debug keystore, which is why a downloaded release installs over a local build.
 */
@Singleton
class ApkInstaller @Inject constructor(@ApplicationContext private val context: Context) {

    /** Whether the user has granted this app the right to install packages. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system screen where that right is granted, aimed at this app. */
    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Writes [file] into a new install session and commits it. The outcome arrives at
     * [InstallResultReceiver], including the request to show the system's confirmation.
     */
    fun install(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite(WRITE_NAME, 0, file.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            session.commit(statusSender(sessionId))
        }
    }

    private fun statusSender(sessionId: Int): IntentSender {
        val intent = Intent(context, InstallResultReceiver::class.java).setAction(InstallResultReceiver.ACTION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }

    private companion object { const val WRITE_NAME = "apexweather.apk" }
}
