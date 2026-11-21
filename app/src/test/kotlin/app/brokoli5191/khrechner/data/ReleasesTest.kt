package app.brokoli5191.khrechner.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {

    // --- GitHub's answer ----------------------------------------------------------------------

    @Test
    fun `a real answer yields version, apk, size, checksum and page`() {
        // Shape of GET /repos/Brokoli5191/kh-rechner/releases/latest as answered in November 2026, cut down.
        val body = """
            {"url":"https://api.github.com/repos/Brokoli5191/kh-rechner/releases/1","html_url":"https://github.com/Brokoli5191/kh-rechner/releases/tag/v1.3.0",
             "tag_name":"v1.3.0","name":"v1.3.0","draft":false,"prerelease":false,"published_at":"2026-11-21T15:11:55Z",
             "assets":[{"url":"https://api.github.com/repos/Brokoli5191/kh-rechner/releases/assets/2","name":"kh-rechner-1.3.0.apk",
                        "content_type":"application/vnd.android.package-archive","size":4402561,
                        "digest":"sha256:cd9bc07f454e03d4458f525b274944e2c43e6a66bef3725e78172558bbd8809e",
                        "browser_download_url":"https://github.com/Brokoli5191/kh-rechner/releases/download/v1.3.0/kh-rechner-1.3.0.apk"}],
             "body":"## What's Changed"}
        """.trimIndent()
        assertEquals(
            ReleaseLookup.Found(
                ReleaseInfo(
                    version = "1.3.0",
                    apkUrl = "https://github.com/Brokoli5191/kh-rechner/releases/download/v1.3.0/kh-rechner-1.3.0.apk",
                    apkSize = 4402561,
                    sha256 = "cd9bc07f454e03d4458f525b274944e2c43e6a66bef3725e78172558bbd8809e",
                    pageUrl = "https://github.com/Brokoli5191/kh-rechner/releases/tag/v1.3.0",
                ),
            ),
            GitHubReleaseParser.parse(body),
        )
    }

    private fun release(assets: String, tag: String = "v1.4.0") =
        """{"tag_name":"$tag","html_url":"https://github.com/x/y/releases/tag/$tag","assets":[$assets]}"""

    @Test
    fun `the apk is picked from among other files, and a missing checksum is no obstacle`() {
        val body = release(
            """{"name":"notes.txt","browser_download_url":"https://github.com/x/notes.txt"},
               {"name":"KH-Rechner-1.4.0.APK","size":"12","browser_download_url":"https://github.com/x/app.apk"}""",
        )
        val found = (GitHubReleaseParser.parse(body) as ReleaseLookup.Found).release
        assertEquals("https://github.com/x/app.apk", found.apkUrl)
        assertEquals(12L, found.apkSize)
        assertNull(found.sha256)
    }

    @Test
    fun `a release without an apk says so`() {
        assertEquals(ReleaseLookup.Failed(UpdateFailure.NO_APK), GitHubReleaseParser.parse(release("")))
        assertEquals(
            ReleaseLookup.Failed(UpdateFailure.NO_APK),
            GitHubReleaseParser.parse(release("""{"name":"app.apk","browser_download_url":"http://github.com/x/app.apk"}""")),
        )
    }

    @Test
    fun `a checksum in another format is ignored rather than trusted`() {
        val body = release("""{"name":"a.apk","digest":"md5:abc","browser_download_url":"https://github.com/x/a.apk"}""")
        assertNull((GitHubReleaseParser.parse(body) as ReleaseLookup.Found).release.sha256)
    }

    @Test
    fun `answers that are not a usable release are github's problem`() {
        val failed = ReleaseLookup.Failed(UpdateFailure.GITHUB_UNAVAILABLE)
        assertEquals(failed, GitHubReleaseParser.parse("<html>rate limited</html>"))
        assertEquals(failed, GitHubReleaseParser.parse("""{"message":"Not Found"}"""))
        assertEquals(failed, GitHubReleaseParser.parse(release("", tag = "nightly")))
        assertEquals(failed, GitHubReleaseParser.parse(release("", tag = "v1.4.0-rc1")))
        assertEquals(failed, GitHubReleaseParser.parse("""{"tag_name":"v1.4.0","assets":[]}"""))
    }

    // --- versions -----------------------------------------------------------------------------

    @Test
    fun `versions compare by number, not by text`() {
        assertTrue(AppVersion.isNewer("1.4.0", "1.3.0"))
        assertTrue(AppVersion.isNewer("1.10.0", "1.9.9"))
        assertTrue(AppVersion.isNewer("2.0.0", "1.99.99"))
        assertTrue(AppVersion.isNewer("1.3.1", "1.3"))
        assertTrue(AppVersion.isNewer("v1.4.0", "1.3.0"))
        assertFalse(AppVersion.isNewer("1.3.0", "1.3.0"))
        assertFalse(AppVersion.isNewer("1.3", "1.3.0"))
        assertFalse(AppVersion.isNewer("1.2.9", "1.3.0"))
        assertFalse(AppVersion.isNewer("1.4.0-rc1", "1.3.0"))
        assertFalse(AppVersion.isNewer("1.4.0", "dev"))
        assertNull(AppVersion.parse("1.2.3.4"))
        assertNull(AppVersion.parse(""))
    }

    // --- download -----------------------------------------------------------------------------

    private val apk = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    private fun copy(bytes: ByteArray = apk, size: Long? = apk.size.toLong(), sha: String? = apkSha, progress: MutableList<Int?> = mutableListOf()) =
        ByteArrayOutputStream().let { output ->
            ApkTransfer.copy(ByteArrayInputStream(bytes), output, size, sha) { progress += it } to output.toByteArray()
        }

    @Test
    fun `a complete download with the right checksum passes, with progress up to 100`() {
        val progress = mutableListOf<Int?>()
        val (ok, written) = copy(progress = progress)
        assertTrue(ok)
        assertArrayEquals(apk, written)
        assertEquals(0, progress.first())
        assertEquals(100, progress.last())
        assertEquals(progress.sortedBy { it }, progress)
    }

    @Test
    fun `a download that differs from what github listed fails`() {
        val tampered = apk.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        assertFalse(copy(bytes = tampered).first)
        assertFalse(copy(bytes = apk.copyOf(apk.size - 1)).first)
        assertFalse(copy(bytes = apk + byteArrayOf(0)).first)
        assertFalse(copy(sha = "0".repeat(64)).first)
    }

    @Test
    fun `without a size there is no percentage, and without a checksum only the size counts`() {
        val progress = mutableListOf<Int?>()
        assertTrue(copy(size = null, progress = progress).first)
        assertEquals(listOf<Int?>(null), progress)
        assertTrue(copy(sha = null).first)
    }
}
