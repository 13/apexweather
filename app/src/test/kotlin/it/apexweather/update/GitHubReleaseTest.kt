package it.apexweather.update

import it.apexweather.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Decodes the recorded answer from api.github.com the way the weather mappers decode theirs. */
class GitHubReleaseTest {
    private fun decode(json: String) = Fixtures.json.decodeFromString(GitHubRelease.serializer(), json)

    private val release = decode(Fixtures.read("github_release.json"))

    @Test fun `reads the tag, the page and the one APK asset`() {
        assertEquals("v0.2.0", release.tagName)
        assertEquals("https://github.com/13/apexweather/releases/tag/v0.2.0", release.htmlUrl)
        val apk = checkNotNull(release.apk) { "the recorded release has an APK asset" }
        assertEquals("ApexWeather-0.2.0.apk", apk.name)
        assertEquals(5_493_047L, apk.size)
        assertEquals("https://github.com/13/apexweather/releases/download/v0.2.0/ApexWeather-0.2.0.apk", apk.downloadUrl)
    }

    /** The checksum is what lets the download be verified rather than merely trusted to TLS. */
    @Test fun `reads the sha-256 out of the digest field`() {
        assertEquals("25830736c37b606586fcb1e4a26da855a1fa754793fb873f7d5feff434935e16", release.apk!!.sha256)
    }

    @Test fun `an older release without a digest yields no checksum rather than a broken one`() {
        val asset = GitHubAsset(name = "a.apk", digest = null)
        assertNull(asset.sha256)
        assertNull(GitHubAsset(name = "a.apk", digest = "md5:abc").sha256)
    }

    /** A release published without an APK is an upstream mistake, not a crash here. */
    @Test fun `a release with no apk asset has none`() {
        assertNull(decode("""{"tag_name":"v9.9.9","html_url":"https://example.invalid","assets":[]}""").apk)
        assertNull(decode("""{"tag_name":"v9.9.9","assets":[{"name":"notes.txt","size":1}]}""").apk)
    }

    /** Unknown fields are the norm here: the real response carries about forty of them. */
    @Test fun `fields the app does not use are ignored`() {
        assertEquals("v1.0.0", decode("""{"tag_name":"v1.0.0","author":{"login":"someone"},"tarball_url":null}""").tagName)
    }
}
