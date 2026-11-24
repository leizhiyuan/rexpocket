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
        versionMismatch = { addon, app -> "add-on $addon, app $app" },
    )

    /** The add-on's node: it records what it was asked. */
    private class FakeNode(val version: Int = NetbirdAddon.VERSION) : INetbirdNode {
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
        override fun asBinder(): IBinder? = null
    }

    /** The binding: it connects at once to [node], unless told otherwise. */
    private class FakeLink(var node: FakeNode?, var installed: Boolean = true) : AddonLink {
        var events: AddonLink.Events? = null
        var binds = 0
        var unbinds = 0
        var refuse: Exception? = null
        var connectAtOnce = true

        override fun installed() = installed

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

    private fun client(link: FakeLink, timeoutMs: Long = 2_000) =
        AddonClient(link, texts, connectTimeoutMs = timeoutMs, background = { it() })

    @Test
    fun startBindsChecksTheVersionAndStartsTheNode() {
        val node = FakeNode()
        val link = FakeLink(node)
        val client = client(link)
        var told = 0
        client.onStateChanged = { told++ }
        client.start("phone", "KEY", "https://nb.example")
        assertEquals(listOf("listener true", "start phone 'KEY' https://nb.example"), node.calls)
        assertEquals(node.state, client.state())
        node.told!!.stateChanged()
        assertTrue(told >= 2)
    }

    @Test
    fun aMissingAddonSaysSoAndIsNotBound() {
        val link = FakeLink(FakeNode(), installed = false)
        try {
            client(link).start("phone", "", "")
            fail("started without the add-on")
        } catch (e: TailnetException) {
            assertEquals("not installed", e.message)
        }
        assertEquals(0, link.binds)
    }

    @Test
    fun anAddonOfAnotherVersionIsNotUsed() {
        val node = FakeNode(version = NetbirdAddon.VERSION + 1)
        val client = client(FakeLink(node))
        try {
            client.start("phone", "", "")
            fail("started an add-on of another version")
        } catch (e: TailnetException) {
            assertEquals("add-on ${NetbirdAddon.VERSION + 1}, app ${NetbirdAddon.VERSION}", e.message)
        }
        assertTrue("nothing is asked of it", node.calls.isEmpty())
    }

    @Test
    fun anAddonSignedWithAnotherKeyIsNotAllowed() {
        val link = FakeLink(FakeNode()).apply { refuse = SecurityException("Not allowed to bind") }
        try {
            client(link).start("phone", "", "")
            fail("bound without the permission")
        } catch (e: TailnetException) {
            assertEquals("not allowed", e.message)
        }
    }

    @Test
    fun anAddonThatNeverAnswersTimesOut() {
        val link = FakeLink(FakeNode()).apply { connectAtOnce = false }
        try {
            client(link, timeoutMs = 100).start("phone", "", "")
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
        client.start("phone", "KEY", "")
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
        assertEquals(listOf("listener true", "start phone '' "), second.calls)
        assertEquals(second.state, client.state())
    }

    @Test
    fun aDeadBindingIsMadeAgain() {
        val first = FakeNode()
        val link = FakeLink(first)
        val client = client(link)
        client.start("phone", "", "")
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
        client.start("phone", "", "")
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
        client.start("phone", "", "")
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
        client.start("phone", "", "")
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
        assertEquals(TailnetBackend.NetbirdAddon, TailnetBackend.fromStorage("netbird-addon"))
    }
}
