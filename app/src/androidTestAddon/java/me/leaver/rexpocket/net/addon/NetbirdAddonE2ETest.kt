package me.leaver.rexpocket.net.addon

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.leaver.rexpocket.core.DialedSocketFactory
import me.leaver.rexpocket.core.TailnetBackend
import me.leaver.rexpocket.net.NetbirdConfig
import me.leaver.rexpocket.net.TailnetException
import me.leaver.rexpocket.net.TailnetStatus
import me.leaver.rexpocket.netbird.api.NetbirdAddon
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

/**
 * The add-on's path against a real, self-hosted Netbird (tools/ci/netbird-e2e, the Netbird e2e
 * workflow's emulator job): RexPocket's NetbirdAddonBox, the add-on bound through its service, and
 * an SSH peer reached through the add-on's dial. The workflow passes the control plane as
 * instrumentation arguments; without them, as in device-test, every test is skipped.
 *
 * Each test expects one add-on build, which the workflow installs before it: the current add-on,
 * one built as revision 1 (`-Prexpocket.addonRevision=1`), or one of another base version
 * (`-Prexpocket.addonVersion=2`).
 */
@RunWith(AndroidJUnit4::class)
class NetbirdAddonE2ETest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val args = InstrumentationRegistry.getArguments()
    private val management = args.getString("netbirdManagement").orEmpty()
    private val setupKey = args.getString("netbirdSetupKey").orEmpty()
    private val peer = args.getString("netbirdPeer").orEmpty()
    private val peerIp = args.getString("netbirdPeerIp").orEmpty()

    private val client = AddonClient(ServiceAddonLink(context), AddonTexts.of(context))
    private val box by lazy { NetbirdAddonBox(context, client) }

    private val second = "${TailnetBackend.NetbirdAddon.storageKey}-e2esecond"

    @Before
    fun theControlPlaneIsGiven() {
        assumeTrue("no control plane given: this runs in the Netbird e2e workflow", management.isNotEmpty())
        assertTrue("install ${NetbirdAddon.APK_NAME} first", client.installed())
        val host = android.net.Uri.parse(management).host.orEmpty()
        // The emulator's DNS must name the control plane as the peers do.
        assertTrue("$host does not resolve on the emulator", runCatching { InetAddress.getByName(host) }.isSuccess)
    }

    @After
    fun letGo() {
        if (management.isNotEmpty()) runCatching { box.stop() }
    }

    private fun config(profile: String, key: String = "") =
        NetbirdConfig(setupKey = key, hostname = "ci-addon", managementUrl = management, profile = profile)

    /** Waits for the node to run and list the peer, and gives its own state. */
    private fun awaitPeer(timeoutMs: Long = 120_000): TailnetStatus {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = box.status.value
            if (s.peersKnown && box.knows(peer)) return s
            Thread.sleep(250)
        }
        fail("the peer $peer never showed; the state was ${box.status.value}")
        throw AssertionError()
    }

    /**
     * The node's registration, by its overlay IPv4 address. An IPv6 address joins it a moment
     * later, when the management server gets to it, so a list read twice could differ by that
     * alone; the v4 address is what names the peer the account registered.
     */
    private fun registration(): List<String> = awaitPeer().self!!.ips.filter { ':' !in it }

    /** Reads the SSH peer's banner through the add-on's dial, by name and by address. */
    private fun readsTheBanner() {
        for (host in listOf(peer, peerIp)) {
            var last: Exception? = null
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline) {
                try {
                    DialedSocketFactory(box.dialer).createSocket(host, 22).use { socket ->
                        socket.soTimeout = 10_000
                        val line = socket.getInputStream().bufferedReader().readLine().orEmpty()
                        assertTrue("$host said: $line", line.startsWith("SSH-2.0"))
                    }
                    last = null
                    break
                } catch (e: Exception) {
                    last = e
                    Thread.sleep(1_000)
                }
            }
            last?.let { throw AssertionError("no banner from $host through the add-on: ${it.message}", it) }
        }
    }

    @Test
    fun accountsEachKeepTheirRegistration() {
        assertEquals(NetbirdAddon.REVISION, client.revision())
        assertTrue(box.keepsAccounts.value)

        // The first account joins with the key, then runs again without it.
        box.start(config(TailnetBackend.NetbirdAddon.storageKey, setupKey))
        val first = registration()
        assertTrue("the node has an IPv4 address", first.isNotEmpty())
        readsTheBanner()
        box.stop()
        box.start(config(TailnetBackend.NetbirdAddon.storageKey))
        assertEquals("the first account's registration was kept", first, registration())

        // A second account has no registration until it joins, then one of its own.
        try {
            box.start(config(second))
            fail("a new account ran without its setup key")
        } catch (e: TailnetException) {
            assertTrue("message was: ${e.message}", e.message.orEmpty().contains("setup key"))
        }
        box.start(config(second, setupKey))
        val other = registration()
        assertNotEquals("the second account is a peer of its own", first, other)
        readsTheBanner()

        // Back on the first, without a key; then the second removed: its registration is gone.
        box.start(config(TailnetBackend.NetbirdAddon.storageKey))
        assertEquals(first, registration())
        client.forget(second)
        box.stop()
        try {
            box.start(config(second))
            fail("a removed account still had its registration")
        } catch (e: TailnetException) {
            assertTrue("message was: ${e.message}", e.message.orEmpty().contains("setup key"))
        }
    }

    @Test
    fun theAddonKilledComesBackWithItsNode() {
        box.start(config(TailnetBackend.NetbirdAddon.storageKey, setupKey))
        awaitPeer()
        readsTheBanner()
        val crashed = shell("am crash ${NetbirdAddon.PACKAGE}")
        assumeTrue("this Android has no am crash: $crashed", !crashed.contains("Unknown command"))
        assertTrue("the add-on's end was not noticed", waitFor(10_000) { !client.connected })
        // Bound again by Android, the node starts again on the same account, without the key.
        assertTrue("the add-on was not bound again", waitFor(60_000) { client.connected })
        awaitPeer()
        readsTheBanner()
    }

    @Test
    fun anAddonOfRevisionOneRunsTheFirstAccountOnly() {
        assertEquals(1, client.revision())
        assertFalse(box.keepsAccounts.value)
        box.start(config(TailnetBackend.NetbirdAddon.storageKey, setupKey))
        awaitPeer()
        readsTheBanner()
        try {
            box.start(config(second, setupKey))
            fail("ran a second account on an add-on without accounts")
        } catch (e: TailnetException) {
            assertEquals(context.getString(me.leaver.rexpocket.R.string.netbird_addon_accounts_update), e.message)
        }
        assertTrue(inspectAddon(context).olderThan(me.leaver.rexpocket.BuildConfig.VERSION_NAME))
    }

    @Test
    fun anAddonOfAnotherVersionIsRefusedWithAnUpdate() {
        try {
            box.start(config(TailnetBackend.NetbirdAddon.storageKey, setupKey))
            fail("used an add-on of another version")
        } catch (e: TailnetException) {
            assertEquals(
                context.getString(me.leaver.rexpocket.R.string.netbird_addon_version_newer, NetbirdAddon.VERSION + 1, NetbirdAddon.VERSION),
                e.message,
            )
        }
        assertFalse(client.connected)
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
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
