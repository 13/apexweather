package it.apexweather.update

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class UpdateRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val payload = ByteArray(300_000) { (it % 251).toByte() }
    private val payloadSha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    /** Answers every request with [payload], so nothing here touches the network. */
    private fun httpServing(body: ByteArray? = payload, code: Int = 200): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            if (body == null) throw IOException("no route to host")
            Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("ok")
                .body(body.toResponseBody("application/vnd.android.package-archive".toMediaType()))
                .build()
        }.build()

    private class FakeGitHub(private val answer: () -> GitHubRelease) : GitHubApi {
        override suspend fun latestRelease(repo: String): GitHubRelease = answer()
    }

    private fun release(tag: String, digest: String? = "sha256:x", withApk: Boolean = true) = GitHubRelease(
        tagName = tag,
        htmlUrl = "https://github.com/13/apexweather/releases/tag/$tag",
        assets = if (withApk) listOf(GitHubAsset("ApexWeather.apk", payload.size.toLong(), "https://example.invalid/a.apk", digest)) else emptyList(),
    )

    private fun repository(api: GitHubApi, http: OkHttpClient = httpServing()) = UpdateRepository(api, http, context)

    private fun cacheFiles(): List<File> = File(context.cacheDir, "updates").listFiles()?.toList().orEmpty()

    @Test fun `a newer tag is offered with its asset and its page`() = runTest {
        val result = repository(FakeGitHub { release("v0.3.0") }).check("0.2.0")
        val available = result as UpdateCheck.Available
        assertEquals(AppVersion(0, 3, 0), available.version)
        assertEquals(payload.size.toLong(), available.asset.size)
        assertEquals("https://github.com/13/apexweather/releases/tag/v0.3.0", available.releaseUrl)
    }

    @Test fun `the same or an older tag is up to date`() = runTest {
        assertEquals(UpdateCheck.UpToDate, repository(FakeGitHub { release("v0.2.0") }).check("0.2.0"))
        assertEquals(UpdateCheck.UpToDate, repository(FakeGitHub { release("v0.1.9") }).check("0.2.0"))
    }

    /** Never "up to date": an unreadable tag means the app does not know, and must say so. */
    @Test fun `an unreadable tag is a failure, not an all clear`() = runTest {
        val result = repository(FakeGitHub { release("nightly") }).check("0.2.0") as UpdateCheck.Failed
        assertEquals(UpdateFailure.UNREADABLE_VERSION, result.failure)
        assertEquals("https://github.com/13/apexweather/releases/tag/nightly", result.releaseUrl)
    }

    @Test fun `a release with no apk fails but still offers its page`() = runTest {
        val result = repository(FakeGitHub { release("v0.3.0", withApk = false) }).check("0.2.0") as UpdateCheck.Failed
        assertEquals(UpdateFailure.NO_APK, result.failure)
        assertEquals("https://github.com/13/apexweather/releases/tag/v0.3.0", result.releaseUrl)
    }

    @Test fun `an unreachable GitHub is reported as a network failure`() = runTest {
        val result = repository(FakeGitHub { throw IOException("offline") }).check("0.2.0") as UpdateCheck.Failed
        assertEquals(UpdateFailure.NETWORK, result.failure)
    }

    @Test fun `a download that matches the published checksum is kept and marked verified`() = runTest {
        val repository = repository(FakeGitHub { release("v0.3.0", "sha256:$payloadSha") })
        val available = repository.check("0.2.0") as UpdateCheck.Available
        val progress = repository.download(available).toList()

        val done = progress.last() as DownloadProgress.Done
        assertTrue(done.digestVerified)
        assertArrayEquals(payload, done.file.readBytes())
        assertTrue("progress should be reported while the file streams", progress.count { it is DownloadProgress.Running } > 1)
    }

    /** The reason the checksum is fetched at all: a file that does not match must not be installed. */
    @Test fun `a download that does not match the checksum fails and the file is deleted`() = runTest {
        val wrong = "0".repeat(64)
        val repository = repository(FakeGitHub { release("v0.3.0", "sha256:$wrong") })
        val available = repository.check("0.2.0") as UpdateCheck.Available
        val progress = repository.download(available).toList()

        assertEquals(DownloadProgress.Failed(UpdateFailure.DIGEST_MISMATCH), progress.last())
        assertTrue("the rejected file must not be left behind: ${cacheFiles()}", cacheFiles().isEmpty())
    }

    /** Older releases predate the digest field. The file is still offered, but not called verified. */
    @Test fun `a release without a checksum downloads unverified rather than silently trusted`() = runTest {
        val repository = repository(FakeGitHub { release("v0.3.0", digest = null) })
        val available = repository.check("0.2.0") as UpdateCheck.Available
        val done = repository.download(available).toList().last() as DownloadProgress.Done
        assertFalse(done.digestVerified)
    }

    @Test fun `a download that cannot reach the server fails`() = runTest {
        val repository = repository(FakeGitHub { release("v0.3.0", "sha256:$payloadSha") }, httpServing(body = null))
        val available = repository.check("0.2.0") as UpdateCheck.Available
        assertEquals(DownloadProgress.Failed(UpdateFailure.NETWORK), repository.download(available).toList().last())
    }

    /** A half-written file from a previous attempt must never survive into the next check. */
    @Test fun `checking clears whatever the last attempt left behind`() = runTest {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        File(dir, "stale.apk").writeBytes(byteArrayOf(1, 2, 3))
        repository(FakeGitHub { release("v0.2.0") }).check("0.2.0")
        assertTrue(cacheFiles().isEmpty())
    }
}
