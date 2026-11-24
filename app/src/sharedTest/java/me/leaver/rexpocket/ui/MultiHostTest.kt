package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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

    /**
     * A host whose own address is down (a closed port) and whose other address is the linux Rex:
     * it connects there, its row says through which address, and that address is remembered.
     */
    @Test
    fun aHostWhoseOwnAddressIsDownConnectsAtItsOtherAddress() {
        val closed = java.net.ServerSocket(0).use { it.localPort }
        val other = "ws://127.0.0.1:${linux.rex.endpoint.port}"
        val app = freshApp()
        val store = HostStore(app)
        store.upsert(linux.host.copy(port = closed, fallbacks = listOf(other)))
        store.setConnectedHosts(listOf(linux.host.id))
        app.getSharedPreferences("rexpocket", android.content.Context.MODE_PRIVATE).edit()
            .putLong("update.checkedAt", System.currentTimeMillis()).commit()
        val m = launchedModel(compose, app)
        model = m
        compose.setContent { RexPocketApp(m) }
        compose.awaitThat { live(m, linux) }
        assertEquals("127.0.0.1:${linux.rex.endpoint.port}", m.lives.value.getValue(linux.host.id).state.endpointLabel)
        compose.awaitThat { compose.onAllNodes(hasText(compose.activity.getString(R.string.host_via_other, "127.0.0.1:${linux.rex.endpoint.port}"), substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(other, store.lastRoute(linux.host.id)?.first)
    }

    @Test
    fun disconnectDropsOnlyThatHostAndConnectIsItsOwnButton() {
        val m = start()
        val linuxClient = client(m, linux)
        val macSession = label(m, mac)
        // The mac's ⋮ sheet (the first host on the list): Disconnect.
        compose.onNode(hasContentDescription(compose.activity.getString(R.string.manage_actions_for, "mac")) and hasClickAction()).performClick()
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
        // The row's tap unfolds it - its cached list, said once to be the last connection's - and
        // folds it again; it never connects.
        val row = compose.onNode(hasText("mac") and hasClickAction())
        row.performClick()
        compose.assertStaysHome(m, ms = 1_000)
        assertFalse(mac.host.id in m.lives.value)
        assertTrue(mac.host.id in m.folds.value.openHosts)
        compose.onNodeWithText(macSession).assertExists()
        compose.onNodeWithText(string(R.string.home_cached_note)).assertExists()
        row.performClick()
        compose.waitForIdle()
        assertFalse(mac.host.id in m.lives.value)
        assertTrue(mac.host.id in m.folds.value.hosts)
        compose.onAllNodesWithText(macSession).assertCountEquals(0)
        // Connect does, beside the linux host, which keeps its connection. The person's fold wins
        // in every state: the host stays folded until it is opened.
        connect.performClick()
        compose.awaitThat { live(m, mac) }
        compose.onAllNodesWithText(macSession).assertCountEquals(0)
        row.performClick()
        compose.waitForIdle()
        compose.onNodeWithText(macSession).assertExists()
        assertEquals(linuxClient, client(m, linux))
        assertEquals(setOf(mac.host.id, linux.host.id), HostStore(compose.activity).connectedHosts().toSet())
    }

    /**
     * A host whose connection fails says why under its name and offers Retry as its button; its
     * tap folds it, as any host's does, and asks nothing of Rex. The other host is untouched.
     */
    @Test
    fun aHostThatFailsOffersRetryAndItsTapOnlyFolds() {
        linux.rex.refuse("session.bootstrap", "the server is busy")
        val app = freshApp()
        val store = mac.saveFor(app)
        store.upsert(linux.host)
        store.setConnectedHosts(listOf(mac.host.id, linux.host.id))
        val m = launchedModel(compose, app)
        model = m
        compose.setContent { RexPocketApp(m) }
        compose.awaitThat { live(m, mac) && m.lives.value[linux.host.id]?.state?.problem != null }
        val retry = compose.onNode(hasText(string(R.string.action_retry)) and hasClickAction())
        compose.awaitThat { retry.fetchSemanticsNodeOrNull() != null }
        compose.onNode(hasText("linux") and hasClickAction()).performClick()
        compose.waitForIdle()
        assertTrue(linux.host.id in m.folds.value.hosts)
        compose.assertStaysHome(m, ms = 500)
        assertTrue(live(m, mac))
        retry.assertExists()
    }

    /**
     * The bottom bar's +: with two hosts connected it asks which, and makes the session there; with
     * one it makes it there at once; a host that is not connected is offered as Connect & create.
     */
    @Test
    fun thePlusMakesASessionWhereItPlainlyGoesOrAsks() {
        val m = start()
        val plus = compose.onNode(hasContentDescription(string(R.string.session_new)) and hasClickAction())
        plus.performClick()
        compose.waitForIdle()
        inSheet("linux").performClick()
        compose.awaitThat { m.route.value == Route.Term && m.host?.id == linux.host.id && m.pocket.value.sessions.size == 2 }
        assertEquals(1, m.lives.value.getValue(mac.host.id).state.sessions.size)

        compose.runOnUiThread { m.showHome() }
        compose.runOnUiThread { m.disconnect(linux.host.id) }
        compose.awaitThat { m.route.value == Route.Home && linux.host.id !in m.lives.value }
        plus.performClick()
        compose.awaitThat { m.route.value == Route.Term && m.host?.id == mac.host.id && m.pocket.value.sessions.size == 2 }

        compose.runOnUiThread { m.showHome() }
        compose.runOnUiThread { m.disconnect(mac.host.id) }
        compose.awaitThat { m.lives.value.isEmpty() }
        plus.performClick()
        compose.waitForIdle()
        assertEquals(2, compose.onAllNodes(hasText(string(R.string.home_connect_and_create))).fetchSemanticsNodes().size)
    }

    private fun hostTop(label: String) = compose.onNode(hasText(label) and hasClickAction()).fetchSemanticsNode().boundsInRoot.top

    /**
     * Hosts in the phone's own order: Move down in a host's sheet moves it on screen and in the
     * store. Remove host confirms, disconnects and removes it; Undo puts it back in its place,
     * not connected, while the other host keeps its connection throughout.
     */
    @Test
    fun aHostMovesInThePhonesOrderAndIsRemovedWithUndo() {
        val m = start()
        val linuxClient = client(m, linux)
        assertTrue(hostTop("mac") < hostTop("linux"))
        compose.onNode(hasContentDescription(compose.activity.getString(R.string.manage_actions_for, "mac")) and hasClickAction()).performClick()
        compose.waitForIdle()
        inSheet(string(R.string.action_move_down)).performClick()
        compose.awaitThat { HostStore(compose.activity).list().map { it.id } == listOf(linux.host.id, mac.host.id) }
        compose.waitForIdle()
        assertTrue("the moved host is not below the other on screen", hostTop("linux") < hostTop("mac"))
        inSheet(string(R.string.action_move_up)).assertIsEnabled()
        inSheet(string(R.string.action_move_down)).assertIsNotEnabled()

        inSheet(string(R.string.home_remove_host)).performClick()
        compose.waitForIdle()
        compose.onNode(hasText(string(R.string.home_remove)) and hasClickAction()).performClick()
        compose.awaitThat { mac.host.id !in m.lives.value && m.hosts.value.none { it.id == mac.host.id } }
        assertEquals(linuxClient, client(m, linux))
        compose.onNode(hasText(string(R.string.action_undo)) and hasClickAction()).performClick()
        compose.awaitThat { m.hosts.value.map { it.id } == listOf(linux.host.id, mac.host.id) }
        compose.assertStaysHome(m, ms = 500)
        assertFalse("Undo connected the host again", mac.host.id in m.lives.value)
        assertEquals(listOf(linux.host.id), HostStore(compose.activity).connectedHosts())
        compose.onNodeWithText("mac").assertExists()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.fetchSemanticsNodeOrNull() =
        runCatching { fetchSemanticsNode() }.getOrNull()

    private fun inSheet(text: String) =
        compose.onNode(hasText(text) and hasClickAction() and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(ACTION_SHEET_TAG)))

    /**
     * A host's sheet follows its connection while it is open: Disconnect, once the host is
     * dropped, becomes Connect in its place, and New session, which needs the host, stays greyed
     * out saying so. The same sheet, items and order, is the host row's TalkBack actions.
     */
    @Test
    fun theHostSheetFollowsItsConnectionAndIsTheRowsTalkBackActions() {
        val m = start()
        val header = compose.onNode(hasText("mac") and hasClickAction())
        val actions = header.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].map { it.label }
        assertEquals(
            listOf(
                string(R.string.action_disconnect), string(R.string.session_new), string(R.string.home_action_collapse),
                string(R.string.home_collapse_sessions), string(R.string.action_move_down), string(R.string.action_edit_host), string(R.string.conn_settings),
                string(R.string.home_copy_address), string(R.string.home_remove_host),
            ),
            actions,
        )
        compose.onNode(hasContentDescription(compose.activity.getString(R.string.manage_actions_for, "mac")) and hasClickAction()).performClick()
        compose.waitForIdle()
        inSheet(string(R.string.action_disconnect)).assertExists()
        inSheet(string(R.string.session_new)).assertIsEnabled()
        compose.runOnUiThread { m.disconnect(mac.host.id) }
        compose.awaitThat { mac.host.id !in m.lives.value }
        inSheet(string(R.string.action_connect)).assertExists()
        assertTrue(compose.onAllNodes(hasText(string(R.string.action_disconnect)) and hasClickAction()).fetchSemanticsNodes().isEmpty())
        inSheet(string(R.string.session_new)).assertIsNotEnabled()
        compose.onNodeWithText(string(R.string.home_needs_connect)).assertExists()
    }
}
