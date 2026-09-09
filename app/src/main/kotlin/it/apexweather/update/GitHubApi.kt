package it.apexweather.update

import it.apexweather.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * api.github.com — the releases of this app's own repository.
 *
 * The endpoint is public and needs no token. "latest" already excludes drafts and pre-releases,
 * so there is nothing to filter here. Unauthenticated callers get 60 requests an hour per address,
 * which a button pressed by hand cannot approach.
 */
interface GitHubApi {
    @GET("repos/{repo}/releases/latest")
    suspend fun latestRelease(@Path("repo", encoded = true) repo: String = BuildConfig.UPDATE_REPO): GitHubRelease

    companion object { const val BASE_URL = "https://api.github.com/" }
}

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val name: String? = null,
    val body: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
) {
    /**
     * The APK to install, or null for a release that carries none. A release without one is a
     * mistake upstream rather than a crash here, so the caller reports it and offers the page.
     */
    val apk: GitHubAsset? get() = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
}

@Serializable
data class GitHubAsset(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String = "",
    /**
     * "sha256:<hex>" on releases published since GitHub started publishing it, absent on older
     * ones. Where it exists the download is verified against it rather than merely trusted to TLS.
     */
    val digest: String? = null,
) {
    /** The hex part of [digest] when it is a SHA-256, else null. */
    val sha256: String? get() = digest?.removePrefix("sha256:")?.takeIf { it.length == 64 && it != digest }
}
