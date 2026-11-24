package me.leaver.rexpocket.ui

import android.app.Application
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.alerts.RexTile1
import me.leaver.rexpocket.alerts.TileStore
import me.leaver.rexpocket.core.Connectable
import me.leaver.rexpocket.core.LinkState
import me.leaver.rexpocket.core.TailnetBackend
import me.leaver.rexpocket.data.HostStore
import me.leaver.rexpocket.data.TailnetSettings
import me.leaver.rexpocket.net.TailnetEntry
import me.leaver.rexpocket.net.TailnetNodes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * A tile's tap in each state of a tailnet: Needs sign-in does not disconnect it (it opens the app's
 * sign-in), and Not connected tries it again. See docs/design/tailnet-control.md, "Tiles".
 */
@RunWith(AndroidJUnit4::class)
class TileTapTest {
    private val node = FakeTailnetNode()
    private lateinit var app: Application
    private lateinit var connections: Connections

    private fun joined() {
        app = freshApp()
        HostStore(app).setTailnet(TailnetSettings(TailnetBackend.Tailscale, enabled = true, hostname = "pixel-8"))
        connections = Connections.of(app, TailnetNodes(listOf(TailnetEntry(TailnetBackend.Tailscale, node))))
        TileStore(app).bind(1, Connectable.tailnet("tailscale"))
    }

    private fun await(ms: Long = 15_000, what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            if (System.currentTimeMillis() > until) throw AssertionError("still waiting after $ms ms: $what")
            Thread.sleep(10)
        }
    }

    @After
    fun stop() {
        runCatching { connections.closeWithScreen() }
    }

    @Test
    fun aTapOnATailnetThatNeedsASignInDoesNotDisconnectIt() {
        joined()
        node.state.value = TailnetSamples.needsLogin
        connections.control.begin()
        val item = Connectable.tailnet("tailscale")
        await(what = "the node asks for a sign-in") { connections.stateOf(item) == LinkState.NeedsSignIn }
        Robolectric.buildService(RexTile1::class.java).create().get().onClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(connections.control.isOff("tailscale"))
        assertEquals(0, node.stops)
        assertEquals(LinkState.NeedsSignIn, connections.stateOf(item))
    }

    @Test
    fun aTapOnATailnetThatFailedToStartTriesItAgain() {
        joined()
        node.state.value = TailnetSamples.connected()
        node.startError = "no network"
        connections.control.begin()
        val item = Connectable.tailnet("tailscale")
        await(what = "the start failed") { connections.stateOf(item) == LinkState.Failed }
        val starts = node.starts
        node.startError = null
        Robolectric.buildService(RexTile1::class.java).create().get().onClick()
        await(what = "the tile tried it again") { node.starts > starts && connections.stateOf(item) == LinkState.Connected }
        assertFalse(connections.control.isOff("tailscale"))
    }
}
