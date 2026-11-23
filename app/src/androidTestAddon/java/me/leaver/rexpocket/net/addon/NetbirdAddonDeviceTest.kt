package me.leaver.rexpocket.net.addon

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.leaver.rexpocket.net.NetbirdConfig
import me.leaver.rexpocket.net.TailnetException
import me.leaver.rexpocket.netbird.api.NetbirdAddon
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * RexPocket and the Netbird add-on on one device: the add-on's APK is installed beside the app
 * (see .github/workflows/device-test.yml), signed with the same key. Offline: the add-on's node is
 * never asked to reach a management server, so it fails to start for want of a setup key, and that
 * failure has to come back through the binding.
 */
@RunWith(AndroidJUnit4::class)
class NetbirdAddonDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val client = AddonClient(ServiceAddonLink(context), AddonTexts.of(context))

    @Before
    fun theAddonIsInstalled() {
        assertTrue("install ${NetbirdAddon.APK_NAME} first", client.installed())
    }

    @After
    fun letGo() = client.stop()

    @Test
    fun theServiceIsGuardedByASignaturePermission() {
        val service = context.packageManager.getServiceInfo(
            ComponentName(NetbirdAddon.PACKAGE, "${NetbirdAddon.PACKAGE}.NodeService"),
            0,
        )
        assertEquals(NetbirdAddon.PERMISSION, service.permission)
        val permission = context.packageManager.getPermissionInfo(NetbirdAddon.PERMISSION, 0)
        val protection = permission.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        assertEquals(PermissionInfo.PROTECTION_SIGNATURE, protection)
        // This app is signed with the add-on's key; the shell, like any app signed otherwise, is not.
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(NetbirdAddon.PERMISSION))
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            context.checkPermission(NetbirdAddon.PERMISSION, -1, Process.SHELL_UID),
        )
    }

    @Test
    fun bindConfigureAndStateGoThroughTheBinding() {
        assertEquals(AddonClient.STOPPED, client.state())
        try {
            client.start("ci-addon", "", "")
            fail("a node that never joined must not start without a setup key")
        } catch (e: TailnetException) {
            assertTrue("message was: ${e.message}", e.message.orEmpty().contains("setup key"))
        }
        assertTrue(client.connected)
        assertEquals(AddonClient.STOPPED, client.state())
        try {
            client.dial("mac.netbird.cloud", 7297, 1_000)
            fail("dialed through a node that is not running")
        } catch (e: IOException) {
            assertTrue("message was: ${e.message}", e.message.orEmpty().contains("not running"))
        }
    }

    @Test
    fun theBoxReportsTheAddonsReason() {
        val box = NetbirdAddonBox(context, client)
        try {
            box.start(NetbirdConfig(setupKey = "", hostname = "ci-addon", managementUrl = ""))
            fail("started without a setup key")
        } catch (e: TailnetException) {
            assertTrue("message was: ${e.message}", e.message.orEmpty().contains("setup key"))
        }
        assertEquals(0, box.proxyPort)
    }

    @Test
    fun theAddonsProcessEndingIsNoticedAndItIsBoundAgain() {
        runCatching { client.start("ci-addon", "", "") }
        assertTrue(client.connected)
        val crashed = shell("am crash ${NetbirdAddon.PACKAGE}")
        assumeTrue("this Android has no am crash: $crashed", !crashed.contains("Unknown command"))
        assertTrue("the add-on's end was not noticed", waitFor { !client.connected })
        assertTrue("the add-on was not bound again", waitFor(30_000) { client.connected })
        assertEquals(AddonClient.STOPPED, client.state())
    }

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun shell(command: String): String {
        val out = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes().decodeToString() }
    }
}
