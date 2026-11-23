package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.data.HostStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two hosts connected at once, each on its own ScriptedRex: both are live on the home with their
 * sessions, a tab opened on one leaves the other connected, Disconnect in a host's ⋮ sheet drops
 * only that host and leaves its one line with a Connect button, which - not the row's tap -
 * connects it again. The whole app on its real view model and store. See docs/design/multi-host.md.
 */
@RunWith(AndroidJUnit4::class)
class MultiHostTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mac = RexOnDesk(sessions = 1, hostId = "mac-host", hostLabel = "mac")
    private val linux = RexOnDesk(sessions = 1, hostId = "linux-host", hostLabel = "linux")
    private var model: RexViewModel? = null

    private fun string(id: Int) = compose.activity.getString(id)

    /** Both hosts saved and connected last time, the mac open last. */
    private fun start(): RexViewModel {
        val app = freshApp()
        val store = mac.saveFor(app)
        store.upsert(linux.host)
        store.setConnectedHosts(listOf(mac.host.id, linux.host.id))
        val m = launchedModel(compose, app)
        model = m
        compose.setContent { RexPocketApp(m) }
        compose.awaitThat { live(m, mac) && live(m, linux) }
        return m
    }

    private fun live(m: RexViewModel, desk: RexOnDesk): Boolean =
        m.lives.value[desk.host.id]?.state?.let { it.phase == Phase.Live && it.views.containsKey(desk.ids[0]) } == true

    private fun client(m: RexViewModel, desk: RexOnDesk) = m.lives.value.getValue(desk.host.id).state.clientId

    private fun label(m: RexViewModel, desk: RexOnDesk) =
        m.lives.value.getValue(desk.host.id).state.sessions.first { it.id == desk.ids[0] }.label

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnectAll() } }
        mac.close()
        linux.close()
    }

    @Test
    fun bothHostsAreLiveAndATabOnOneLeavesTheOtherConnected() {
        val m = start()
        // Each host's session is on the list, live.
        compose.onNodeWithText(label(m, mac)).assertExists()
        compose.onNodeWithText(label(m, linux)).assertExists()
        val macClient = client(m, mac)
        // A tab of the linux host's session: the terminal shows it, and the mac stays connected.
        val tab = linux.tabs(linux.ids[0]).first()
        compose.runOnUiThread { m.openTabOn(linux.host, linux.ids[0], tab) }
        compose.awaitThat { m.route.value == Route.Term && m.host?.id == linux.host.id && m.pocket.value.activeSessionId == linux.ids[0] }
        assertTrue(live(m, mac))
        assertEquals(macClient, client(m, mac))
        assertEquals(linux.host.id, HostStore(compose.activity).lastHost())
    }

    @Test
    fun disconnectDropsOnlyThatHostAndConnectIsItsOwnButton() {
        val m = start()
        val linuxClient = client(m, linux)
        val macSession = label(m, mac)
        // The mac's ⋮ sheet (the first host on the list): Disconnect.
        compose.onAllNodes(hasContentDescription(string(R.string.action_more)) and hasClickAction())[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.action_disconnect)).performClick()
        compose.awaitThat { mac.host.id !in m.lives.value }
        assertTrue(live(m, linux))
        assertEquals(linuxClient, client(m, linux))
        assertEquals(listOf(linux.host.id), HostStore(compose.activity).connectedHosts())
        // One line for the mac: not connected, the number of sessions it had, and Connect.
        compose.onAllNodesWithText(macSession).assertCountEquals(0)
        val line = string(R.string.host_not_connected) + " · " +
            compose.activity.resources.getQuantityString(R.plurals.session_count, 1, 1)
        compose.onNodeWithText(line).assertExists()
        val connect = compose.onNode(hasText(string(R.string.action_connect)) and hasClickAction())
        connect.assertExists()
        // The row's tap folds and unfolds it; it never connects.
        val row = compose.onNode(hasText("mac") and hasClickAction())
        row.performClick()
        compose.assertStaysHome(m, ms = 1_000)
        assertFalse(mac.host.id in m.lives.value)
        assertTrue(mac.host.id in m.folds.value.hosts)
        row.performClick()
        compose.waitForIdle()
        assertFalse(mac.host.id in m.lives.value)
        assertFalse(mac.host.id in m.folds.value.hosts)
        // Connect does, beside the linux host, which keeps its connection.
        connect.performClick()
        compose.awaitThat { live(m, mac) }
        compose.onNodeWithText(macSession).assertExists()
        assertEquals(linuxClient, client(m, linux))
        assertEquals(setOf(mac.host.id, linux.host.id), HostStore(compose.activity).connectedHosts().toSet())
    }
}
