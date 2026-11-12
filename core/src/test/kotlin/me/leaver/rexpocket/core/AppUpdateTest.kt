package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {

    private fun feed(vararg releases: String): String = releases.joinToString(",", "[", "]")

    private fun release(
        tag: String,
        prerelease: Boolean = false,
        draft: Boolean = false,
        asset: String? = "RexPocket-$tag.apk",
    ): String {
        val assets = asset?.let {
            """[{"name":"$it","browser_download_url":"https://example.test/$it","size":42,"digest":"sha256:abc"}]"""
        } ?: "[]"
        return """{"tag_name":"$tag","prerelease":$prerelease,"draft":$draft,"assets":$assets}"""
    }

    @Test
    fun `versions compare as numbers, not as text`() {
        assertTrue(AppVersion.parse("0.0.16")!! < AppVersion.parse("0.1.0")!!)
        assertTrue(AppVersion.parse("0.9.0")!! < AppVersion.parse("0.10.0")!!)
        assertEquals(0, AppVersion.parse("v0.1.0")!!.compareTo(AppVersion.parse("0.1.0")!!))
        assertEquals(0, AppVersion.parse("0.1")!!.compareTo(AppVersion.parse("0.1.0")!!))
    }

    @Test
    fun `a pre-release comes before the release it leads to`() {
        assertTrue(AppVersion.parse("0.1.0-alpha1")!! < AppVersion.parse("0.1.0")!!)
        assertTrue(AppVersion.parse("0.1.0-alpha1")!! < AppVersion.parse("0.1.0-alpha2")!!)
        assertTrue(AppVersion.parse("0.1.0-alpha2")!! < AppVersion.parse("0.1.0-alpha10")!!)
        assertTrue(AppVersion.parse("0.1.0-alpha10")!! < AppVersion.parse("0.1.0-beta1")!!)
        assertTrue(AppVersion.parse("0.1.0-rc1")!! < AppVersion.parse("0.1.0")!!)
        assertTrue(AppVersion.parse("0.1.0-alpha")!! < AppVersion.parse("0.1.0-alpha.1")!!)
    }

    @Test
    fun `something that is not a version is not parsed`() {
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse("0.x.1"))
    }

    @Test
    fun `the feed drops drafts and tags that are not versions`() {
        val releases = AppRelease.parseFeed(
            feed(
                release("v1.0.0", draft = true),
                release("nightly"),
                release("v1.1.0", asset = null),
                release("v1.2.0"),
            ),
        )
        assertEquals(listOf("v1.1.0", "v1.2.0"), releases.map { it.tag })
        assertNull(releases.first().apk)
        assertEquals("https://example.test/RexPocket-v1.2.0.apk", releases.last().apk?.url)
        assertEquals("sha256:abc", releases.last().apk?.sha256)
    }

    @Test
    fun `a release without an APK is never offered`() {
        val releases = AppRelease.parseFeed(feed(release("v1.1.0", asset = null), release("v1.0.0")))
        assertEquals("v1.0.0", releases.newestFor(AppVersion.parse("0.9.0")!!)?.tag)
    }

    @Test
    fun `the running build is never offered itself`() {
        val releases = AppRelease.parseFeed(feed(release("v0.0.16"), release("v0.1.0-alpha1", prerelease = true)))
        assertNull(releases.newestFor(AppVersion.parse("0.0.16")!!))
        assertNull(releases.newestFor(AppVersion.parse("0.1.0-alpha1")!!))
        // An alpha does take the stable version that follows it.
        assertEquals("v0.0.16", releases.newestFor(AppVersion.parse("0.0.9")!!)?.tag)
    }

    @Test
    fun `a finished build does not get offered a pre-release`() {
        val releases = AppRelease.parseFeed(feed(release("v0.1.0-alpha1", prerelease = true)))
        assertNull(releases.newestFor(AppVersion.parse("0.0.16")!!))
        assertEquals("v0.1.0-alpha1", releases.newestFor(AppVersion.parse("0.0.9-alpha3")!!)?.tag)
    }

    @Test
    fun `the newest of several newer releases is the one offered`() {
        val releases = AppRelease.parseFeed(
            feed(
                release("v0.0.15"),
                release("v0.0.17"),
                release("v0.0.16"),
            ),
        )
        assertEquals("v0.0.17", releases.newestFor(AppVersion.parse("0.0.14")!!)?.tag)
    }
}
