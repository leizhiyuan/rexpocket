package me.leaver.rexpocket.net.addon

import me.leaver.rexpocket.core.AppRelease
import me.leaver.rexpocket.core.AppVersion
import me.leaver.rexpocket.netbird.api.NetbirdAddon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which release's add-on the app installs, and which APK of it, by name. */
class AddonUpdatesTest {
    private fun asset(name: String) = """{"name":"$name","browser_download_url":"https://example.com/$name","size":10}"""

    private fun release(tag: String, vararg assets: String) =
        """{"tag_name":"$tag","draft":false,"prerelease":false,"assets":[${assets.joinToString(",") { asset(it) }}]}"""

    private val feed = "[" + listOf(
        release("v0.1.4", "rexpocket.apk", NetbirdAddon.APK_NAME, "rexpocket-netbird.apk"),
        release("v0.1.3", "rexpocket.apk", "rexpocket-netbird.apk", NetbirdAddon.APK_NAME),
        release("v0.1.2", "rexpocket.apk"),
        release("v0.1.1", NetbirdAddon.APK_NAME, "rexpocket.apk"),
    ).joinToString(",") + "]"

    private val releases = AppRelease.parseFeed(feed) { it == NetbirdAddon.APK_NAME }

    @Test
    fun theAddonApkIsTakenByItsNameNotTheApps() {
        assertEquals(NetbirdAddon.APK_NAME, releases.first().apk?.name)
        assertNull("a release without the add-on has none", releases.single { it.tag == "v0.1.2" }.apk)
    }

    @Test
    fun theAppsOwnReleaseComesFirst() {
        assertEquals("v0.1.3", addonReleaseFor(releases, AppVersion.parse("0.1.3"))?.tag)
    }

    @Test
    fun withoutItTheNewestOlderOneThatCarriesTheAddon() {
        assertEquals("v0.1.1", addonReleaseFor(releases, AppVersion.parse("0.1.2"))?.tag)
        assertEquals("v0.1.4", addonReleaseFor(releases, AppVersion.parse("0.2.0"))?.tag)
    }

    @Test
    fun neverOneNewerThanTheApp() {
        assertNull(addonReleaseFor(releases, AppVersion.parse("0.1.0")))
    }

    @Test
    fun theAppUpdaterStillTakesTheAppNotTheAddon() {
        assertEquals("rexpocket.apk", AppRelease.parseFeed(feed, ::isUpdateAsset).first().apk?.name)
    }
}
