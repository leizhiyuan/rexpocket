package me.leaver.rexpocket.net.addon

import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import me.leaver.rexpocket.core.TailnetBackend
import me.leaver.rexpocket.net.TailnetException
import me.leaver.rexpocket.netbird.api.INetbirdNode
import me.leaver.rexpocket.netbird.api.INetbirdNodeListener
import me.leaver.rexpocket.netbird.api.NetbirdAddon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * RexPocket's side of the Netbird add-on, against a binding and an add-on played here: binding and
 * the version check, a missing or foreign add-on, and the add-on's process dying and coming back.
 */
class AddonClientTest {
    private val texts = AddonTexts(
        notInstalled = "not installed",
        notAllowed = "not allowed",
        noAnswer = "no answer",
        restarting = "restarting",
        addonOlder = { addon, app -> "add-on $addon older than app $app" },
        addonNewer = { addon, app -> "add-on $addon newer than app $app" },
        accountsNeedUpdate = "update for accounts",
    )

    /**
     * The add-on's node: it records what it was asked. An add-on of [revision] 1 answers a later
     * call with nothing, as Binder does for a call the add-on does not have: 0, and nothing done.
     */
    private class FakeNode(val version: Int = NetbirdAddon.VERSION, val revision: Int = NetbirdAddon.REVISION) : INetbirdNode {
        val calls = mutableListOf<String>()
        var told: INetbirdNodeListener? = null
        var state = """{"backendState":"Running","peers":[]}"""
        var dead = false
        var dialFailure: String? = null

        private fun call(name: String) {
            if (dead) throw RemoteException("dead")
            calls += name
        }

        override fun version(): Int = version.also { if (dead) throw RemoteException("dead") }
        override fun start(hostname: String, setupKey: String, managementUrl: String) = call("start $hostname '$setupKey' $managementUrl")
        override fun stop() = call("stop")
        override fun state(): String = state.also { call("state") }
        override fun ping(ip: String, timeoutMillis: Int): String = "{}".also { call("ping $ip") }
        override fun dialError(host: String): String = "".also { call("dialError $host") }
        override fun dial(host: String, port: Int, timeoutMillis: Int): ParcelFileDescriptor {
            call("dial $host:$port")
            dialFailure?.let { throw IllegalStateException(it) }
            throw UnsupportedOperationException("no descriptors on the JVM")
        }
        override fun setListener(listener: INetbirdNodeListener?) {
            call("listener ${listener != null}")
            told = listener
        }
        override fun keepRunning(background: Boolean) = call("keepRunning $background")
        var refuseForget: String? = null
        override fun revision(clientRevision: Int): Int {
            if (revision < 2) return 0
            call("revision $clientRevision")
            return revision
        }
        override fun startProfile(profile: String, hostname: String, setupKey: String, managementUrl: String) {
            if (revision < 2) return
            call("startProfile $profile $hostname '$setupKey' $managementUrl")
        }
        override fun forgetProfile(profile: String) {
            if (revision < 2) return
            call("forgetProfile $profile")
            refuseForget?.let { throw IllegalStateException(it) }
        }
        override fun asBinder(): IBinder? = null
    }

    /** The binding: it connects at once to [node], unless told otherwise. */
    private class FakeLink(var node: FakeNode?, var installed: Boolean = true, var trusted: Boolean = true) : AddonLink {
        var events: AddonLink.Events? = null
        var binds = 0
        var unbinds = 0
        var refuse: Exception? = null
        var connectAtOnce = true

        override fun inspect() = if (!installed) AddonPackage.MISSING
        else AddonPackage(installed = true, trusted = trusted, versionName = "0.1.3", revision = node?.revision ?: 1)

        override fun bind(events: AddonLink.Events): Boolean {
            refuse?.let { throw it }
            binds++
            this.events = events
            if (connectAtOnce) node?.let { events.connected(it) }
            return true
        }

        override fun unbind() {
            unbinds++
        }
    }

    private val forgets = ForgetQueue.InMemory()

    private fun client(link: FakeLink, timeoutMs: Long = 2_000) =
        AddonClient(link, texts, connectTimeoutMs = timeoutMs, background = { it() }, forgets = forgets)

    @Test
    fun startBindsChecksTheVersionAndStartsTheNode() {
        val node = FakeNode()
        val link = FakeLink(node)
        val client = client(link)
        var told = 0
        client.onStateChanged = { told++ }
        client.start("", "phone", "KEY", "https://nb.example")
        assertEquals(listOf("revision ${NetbirdAddon.REVISION}", "listener true", "start phone 'KEY' https://nb.example"), node.calls)
        assertEquals(node.state, client.state())
        node.told!!.stateChanged()
        assertTrue(told >= 2)
    }

    @Test
    fun aMissingAddonSaysSoAndIsNotBound() {
        val link = FakeLink(FakeNode(), installed = false)
        try {
            client(link).start("", "phone", "", "")
            fail("started without the add-on")
        } catch (e: TailnetException) {
            assertEquals("not installed", e.message)
        }
        assertEquals(0, link.binds)
    }

    @Test
    fun anAddonOfAnotherVersionIsNotUsedAndTheMessageSaysWhichToUpdate() {
        val newer = FakeNode(version = NetbirdAddon.VERSION + 1)
        try {
            client(FakeLink(newer)).start("", "phone", "", "")
            fail("started an add-on of another version")
        } catch (e: TailnetException) {
            assertEquals("add-on ${NetbirdAddon.VERSION + 1} newer than app ${NetbirdAddon.VERSION}", e.message)
        }
        assertTrue("nothing is asked of it", newer.calls.isEmpty())
        val older = FakeNode(version = NetbirdAddon.VERSION - 1)
        try {
            client(FakeLink(older)).start("", "phone", "", "")
            fail("started an add-on of another version")
        } catch (e: TailnetException) {
            assertEquals("add-on ${NetbirdAddon.VERSION - 1} older than app ${NetbirdAddon.VERSION}", e.message)
        }
    }

    @Test
    fun anAddonOfRevisionOneRunsTheFirstAccountAndNoOther() {
        val node = FakeNode(revision = 1)
        val client = client(FakeLink(node))
        client.start("", "phone", "KEY", "")
        assertEquals(1, client.revision())
        assertFalse(client.keepsAccounts())
        assertTrue(node.calls.contains("start phone 'KEY' "))
        try {
            client.start("netbird-addon-work", "phone", "KEY", "")
            fail("ran a second account on an add-on without accounts")
        } catch (e: TailnetException) {
            assertEquals("update for accounts", e.message)
        }
        assertFalse("the call it would take for nothing is never made", node.calls.any { it.startsWith("startProfile") })
    }

    @Test
    fun anAccountRunsOnItsOwnRegistrationAndComesBackOnIt() {
        val first = FakeNode()
        val link = FakeLink(first)
        val client = client(link)
        assertTrue(client.keepsAccounts())
        client.start("netbird-addon-work", "phone", "KEY", "")
        assertTrue(first.calls.contains("startProfile netbird-addon-work phone 'KEY' "))
        link.events!!.disconnected()
        val second = FakeNode()
        link.events!!.connected(second)
        assertTrue(second.calls.contains("startProfile netbird-addon-work phone '' "))
    }

    @Test
    fun aRemovedAccountsRegistrationIsDeletedByTheAddonAndTheBindingLetGo() {
        val node = FakeNode()
        val link = FakeLink(node)
        val client = client(link)
        client.forget("netbird-addon-work")
        assertEquals(listOf("revision ${NetbirdAddon.REVISION}", "listener true", "forgetProfile netbird-addon-work"), node.calls)
        assertTrue(forgets.all().isEmpty())
        assertEquals("bound for the removal only", 1, link.unbinds)
        // The first account is never removed.
        client.forget("")
        assertEquals(1, node.calls.count { it.startsWith("forgetProfile") })
    }

    @Test
    fun aRemovalWhileTheAddonIsAwayIsDoneOnceItIsBack() {
        val link = FakeLink(FakeNode(), installed = false)
        val client = client(link)
        client.forget("netbird-addon-work")
        assertEquals(setOf("netbird-addon-work"), forgets.all())
        link.installed = true
        val node = FakeNode()
        link.node = node
        client.start("", "phone", "", "")
        assertTrue(node.calls.contains("forgetProfile netbird-addon-work"))
        assertTrue(forgets.all().isEmpty())
    }

    @Test
    fun aRemovalTheAddonRefusesIsNotAskedAgain() {
        val node = FakeNode().apply { refuseForget = "the account is running" }
        val client = client(FakeLink(node))
        client.forget("netbird-addon-work")
        assertTrue(forgets.all().isEmpty())
    }

    @Test
    fun anAddonSignedWithAnotherKeyIsNeverBound() {
        val link = FakeLink(FakeNode(), trusted = false)
        try {
            client(link).start("", "phone", "KEY", "")
            fail("bound an add-on of another key")
        } catch (e: TailnetException) {
            assertEquals("not allowed", e.message)
        }
        assertEquals(0, link.binds)
        assertFalse(client(link).keepsAccounts())
    }

    @Test
    fun anOlderAddonWantsUpdating() {
        assertTrue(AddonPackage(installed = true, versionName = "0.1.3", revision = 1).olderThan("0.1.3"))
        assertTrue(AddonPackage(installed = true, versionName = "0.1.2", revision = NetbirdAddon.REVISION).olderThan("0.1.3"))
        assertFalse(AddonPackage(installed = true, versionName = "0.1.3", revision = NetbirdAddon.REVISION).olderThan("0.1.3"))
        assertFalse(AddonPackage(installed = true, versionName = "0.1.4", revision = NetbirdAddon.REVISION).olderThan("0.1.3"))
        // The first add-on was 1.0, revision 1: older by its revision, whatever its name says.
        assertTrue(AddonPackage(installed = true, versionName = "1.0", revision = 1).olderThan("0.1.3"))
        assertFalse(AddonPackage.MISSING.olderThan("0.1.3"))
        assertFalse(AddonPackage(installed = true, trusted = false, revision = 1).olderThan("0.1.3"))
    }

    @Test
    fun theFirstAccountIsTheAddonsFirst() {
        assertEquals(NetbirdAddon.FIRST_PROFILE, addonProfile(""))
        assertEquals(NetbirdAddon.FIRST_PROFILE, addonProfile(TailnetBackend.NetbirdAddon.storageKey))
        assertEquals("netbird-addon-work", addonProfile("netbird-addon-work"))
    }

    @Test
    fun anAddonSignedWithAnotherKeyIsNotAllowed() {
        val link = FakeLink(FakeNode()).apply { refuse = SecurityException("Not allowed to bind") }
        try {
            client(link).start("", "phone", "", "")
            fail("bound without the permission")
        } catch (e: TailnetException) {
            assertEquals("not allowed", e.message)
        }
    }

    @Test
    fun anAddonThatNeverAnswersTimesOut() {
        val link = FakeLink(FakeNode()).apply { connectAtOnce = false }
        try {
            client(link, timeoutMs = 100).start("", "phone", "", "")
            fail("started an add-on that never bound")
        } catch (e: TailnetException) {
            assertEquals("no answer", e.message)
        }
    }

    @Test
    fun whenTheAddonDiesTheStateSaysSoAndTheNodeStartsAgainOnItsReturn() {
        val first = FakeNode()
        val link = FakeLink(first)
        val client = client(link)
        client.start("", "phone", "KEY", "")
        first.dead = true
        link.events!!.disconnected()
        assertTrue(client.state().contains("restarting"))
        assertTrue(client.state().contains("\"Starting\""))
        try {
            client.dial("mac", 7297, 1000)
            fail("dialed through a dead add-on")
        } catch (e: IOException) {
            assertEquals("restarting", e.message)
        }

        // Android binds it again: the node starts with the same settings, without the setup key.
        val second = FakeNode()
        link.events!!.connected(second)
        assertEquals(listOf("revision ${NetbirdAddon.REVISION}", "listener true", "start phone '' "), second.calls)
        assertEquals(second.state, client.state())
    }

    @Test
    fun aDeadBindingIsMadeAgain() {
        val first = FakeNode()
        val link = FakeLink(first)
        val client = client(link)
        client.start("", "phone", "", "")
        val second = FakeNode()
        link.node = second
        link.events!!.died()
        assertEquals(1, link.unbinds)
        assertEquals(2, link.binds)
        assertTrue(second.calls.contains("start phone '' "))
    }

    @Test
    fun aStoppedNodeIsNotStartedAgain() {
        val first = FakeNode()
        val link = FakeLink(first)
        val client = client(link)
        client.start("", "phone", "", "")
        client.stop()
        assertTrue(first.calls.contains("stop"))
        assertEquals(1, link.unbinds)
        assertEquals(AddonClient.STOPPED, client.state())
        val second = FakeNode()
        link.events!!.connected(second)
        assertFalse(second.calls.any { it.startsWith("start") })
    }

    @Test
    fun aFailedDialCarriesTheNodesReason() {
        val node = FakeNode().apply { dialFailure = "no route to mac" }
        val client = client(FakeLink(node))
        client.start("", "phone", "", "")
        try {
            client.dial("mac", 7297, 1000)
            fail("dialed")
        } catch (e: IOException) {
            assertEquals("no route to mac", e.message)
        }
    }

    @Test
    fun theBackgroundWishReachesTheAddonNowAndAfterItComesBack() {
        val node = FakeNode()
        val link = FakeLink(node)
        val client = client(link)
        client.start("", "phone", "", "")
        client.keepInBackground(true)
        assertTrue(node.calls.contains("keepRunning true"))
        val again = FakeNode()
        link.events!!.disconnected()
        link.events!!.connected(again)
        assertTrue(again.calls.contains("keepRunning true"))
    }

    @Test
    fun aStateTheAddonDidNotReportIsValidJson() {
        assertEquals(
            """{"backendState":"Starting","health":["say \"why\"\u000a"],"peers":[]}""",
            AddonClient.stateJson("Starting", "say \"why\"\n"),
        )
    }

    @Test
    fun theAddonIsItsOwnBackend() {
        assertFalse(TailnetBackend.NetbirdAddon.signsIn)
        assertTrue("accounts, once the add-on has them", TailnetBackend.NetbirdAddon.accounts)
        assertEquals(TailnetBackend.NetbirdAddon, TailnetBackend.fromStorage("netbird-addon"))
    }
}
