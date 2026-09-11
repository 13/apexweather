package it.apexweather.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import it.apexweather.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Why a check or a download gave up. The UI turns these into sentences; they carry no text. */
enum class UpdateFailure {
    /** The request never completed: no network, a timeout, or GitHub answering with an error. */
    NETWORK,

    /** The latest release carries no APK asset. */
    NO_APK,

    /** Either this build's version name or the release tag could not be read as three numbers. */
    UNREADABLE_VERSION,

    /** The downloaded file does not match the checksum the release published. */
    DIGEST_MISMATCH,

    /** The file could not be written to the cache directory. */
    STORAGE,
}

sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck

    data class Available(
        val version: AppVersion,
        val asset: GitHubAsset,
        val releaseUrl: String,
    ) : UpdateCheck

    /** [releaseUrl] is present whenever the release itself was read, so the page can still be offered. */
    data class Failed(val failure: UpdateFailure, val releaseUrl: String? = null) : UpdateCheck
}

sealed interface DownloadProgress {
    data class Running(val bytes: Long, val total: Long) : DownloadProgress

    /**
     * [digestVerified] is false only when the release published no checksum, which the UI says out
     * loud. A published checksum that does not match never reaches here; it fails instead.
     */
    data class Done(val file: File, val digestVerified: Boolean) : DownloadProgress

    data class Failed(val failure: UpdateFailure) : DownloadProgress
}

/**
 * Asks GitHub what the newest release is and fetches its APK.
 *
 * Nothing in the weather code calls this. The package exists on its own so that it, the
 * REQUEST_INSTALL_PACKAGES line in the manifest and the one call in AppNavigation are all that
 * would have to go if the app were ever published on the Play Store, where that permission is
 * restricted.
 */
@Singleton
class UpdateRepository @Inject constructor(
    private val api: GitHubApi,
    @UpdateDownloads private val http: OkHttpClient,
    @ApplicationContext private val context: Context,
) {
    private val cacheDir: File get() = File(context.cacheDir, DIR_NAME)

    /**
     * What the newest release is, compared against [currentVersion]. Clears anything left in the
     * download cache first: a half-written file from a previous attempt must never be installed.
     */
    suspend fun check(currentVersion: String = BuildConfig.VERSION_NAME): UpdateCheck = withContext(Dispatchers.IO) {
        clearCache()
        val here = AppVersion.parse(currentVersion) ?: return@withContext UpdateCheck.Failed(UpdateFailure.UNREADABLE_VERSION)
        val release = runCatching { api.latestRelease() }.getOrElse {
            currentCoroutineContext().ensureActive()
            return@withContext UpdateCheck.Failed(UpdateFailure.NETWORK)
        }
        val there = AppVersion.parse(release.tagName)
            ?: return@withContext UpdateCheck.Failed(UpdateFailure.UNREADABLE_VERSION, release.htmlUrl)
        if (there <= here) return@withContext UpdateCheck.UpToDate
        val apk = release.apk ?: return@withContext UpdateCheck.Failed(UpdateFailure.NO_APK, release.htmlUrl)
        UpdateCheck.Available(there, apk, release.htmlUrl)
    }

    /**
     * Streams the APK into the cache directory, reporting progress against the asset's declared
     * size and hashing as it goes. A file that is shorter than the release says it is, or that does
     * not match its checksum, is deleted rather than offered.
     */
    fun download(update: UpdateCheck.Available): Flow<DownloadProgress> = flow {
        val target = File(cacheDir, update.asset.name)
        if (!cacheDir.isDirectory && !cacheDir.mkdirs()) {
            emit(DownloadProgress.Failed(UpdateFailure.STORAGE))
            return@flow
        }
        val sha = MessageDigest.getInstance("SHA-256")
        val total = update.asset.size
        emit(DownloadProgress.Running(0, total))

        val response = runCatching { http.newCall(Request.Builder().url(update.asset.downloadUrl).build()).execute() }
            .getOrElse {
                currentCoroutineContext().ensureActive()
                emit(DownloadProgress.Failed(UpdateFailure.NETWORK))
                return@flow
            }
        response.use {
            val body = it.body
            if (!it.isSuccessful) {
                emit(DownloadProgress.Failed(UpdateFailure.NETWORK))
                return@flow
            }
            val written = runCatching {
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var done = 0L
                        var lastReported = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            sha.update(buffer, 0, read)
                            done += read
                            // Emitting per buffer would be thousands of updates for one progress bar.
                            if (done - lastReported >= PROGRESS_STEP_BYTES) {
                                lastReported = done
                                emit(DownloadProgress.Running(done, total))
                            }
                        }
                        done
                    }
                }
            }.getOrElse { error ->
                currentCoroutineContext().ensureActive()
                target.delete()
                emit(DownloadProgress.Failed(if (error is java.io.IOException && error !is java.io.FileNotFoundException) UpdateFailure.NETWORK else UpdateFailure.STORAGE))
                return@flow
            }
            emit(DownloadProgress.Running(written, total))

            // A connection dropped mid-stream produces a short file, not an error, and a short APK
            // is exactly what must never reach PackageInstaller. The checksum below catches it —
            // but only when the release published one, and `digestVerified` exists precisely
            // because sometimes it does not.
            //
            // `total > 0` because the asset's size is a defaulted field: a release that declares
            // none must still be installable, and treating "no declared length" as "zero bytes
            // expected" would fail every download instead of the truncated ones.
            if (total > 0 && written != total) {
                target.delete()
                emit(DownloadProgress.Failed(UpdateFailure.NETWORK))
                return@flow
            }
        }

        val expected = update.asset.sha256
        if (expected != null && !expected.equals(sha.digest().toHex(), ignoreCase = true)) {
            target.delete()
            emit(DownloadProgress.Failed(UpdateFailure.DIGEST_MISMATCH))
            return@flow
        }
        emit(DownloadProgress.Done(target, digestVerified = expected != null))
    }.flowOn(Dispatchers.IO)

    /** Drops anything left from an earlier attempt, so a stale APK can never be installed. */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val DIR_NAME = "updates"
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_STEP_BYTES = 128 * 1024L
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
