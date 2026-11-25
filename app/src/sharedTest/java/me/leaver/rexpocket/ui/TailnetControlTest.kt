package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.TailnetPolicies
import me.leaver.rexpocket.core.TailnetPolicy
import me.leaver.rexpocket.core.TailnetStage
import me.leaver.rexpocket.core.TailnetUse
import me.leaver.rexpocket.data.HostStore
import me.leaver.rexpocket.data.TailnetPolicyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress

/**
 * The tailnet by hand, as a host is: Disconnect from the home's menu confirms and names the hosts
 * that go with it, the snackbar's Reconnect brings both back, a tailnet disconnected stays off at
 * the next start, one on only when needed waits for its first host, and its page sets that.
 * The whole app on its view model, with the node a stand-in. Runs on the JVM and on the emulator.
 * See docs/design/tailnet-control.md.
 */
@RunWith(AndroidJUnit4::class)
class TailnetControlTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = FakeTailnetNode()
    private var model: RexViewModel? = null
    private val closing = mutableListOf<AutoCloseable>()

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnect() } }
        closing.forEach { runCatching { it.close() } }
    }

    private fun launch(open: me.leaver.rexpocket.data.SavedHost?, policies: TailnetPolicies? = null): RexViewModel {
        val m = tailnetModel(compose, node, listOf(TailnetSamples.macHost), open = open, policies = policies)
        model = m
        compose.setContent { RexPocketApp(m) }
        compose.waitForIdle()
        return m
    }

    private fun deskThroughTheNode(): RexOnDesk {
        val desk = RexOnDesk(sessions = 1).also(closing::add)
        val socks = FakeSocks { _, _ -> InetSocketAddress("127.0.0.1", desk.rex.endpoint.port) }.also(closing::add)
        node.socksPort = socks.port
        node.state.value = TailnetSamples.connected()
        return desk
    }

    /** The home's ⋮ Tailnet, as Settings' Tailnet: the joined tailnet's sheet, not the join screen. */
    @Test
    fun theHomeMenusTailnetOpensTheJoinedTailnetsSheet() {
        deskThroughTheNode()
        val m = launch(open = TailnetSamples.macHost)
        compose.awaitThat { m.tailnetStage.value is TailnetStage.Connected }
        compose.onNode(hasContentDescription(string(R.string.home_menu)) and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNode(hasText(string(R.string.tailnet_title)) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
        // The sheet is titled by the network, and offers its Disconnect.
        compose.onNode(hasText("Tailscale") and hasAnyAncestor(hasTestTag(ACTION_SHEET_TAG))).assertExists()
        compose.onNode(hasText(string(R.string.action_disconnect)) and hasClickAction() and hasAnyAncestor(hasTestTag(ACTION_SHEET_TAG))).assertExists()
    }

    @Test
    fun disconnectingTheTailnetConfirmsNamesItsHostsAndReconnectBringsThemBack() {
        deskThroughTheNode()
        val m = launch(open = TailnetSamples.macHost)
        compose.awaitThat { m.lives.value["mac-host"]?.state?.phase == Phase.Live }
        compose.awaitThat { m.tailnetStage.value is TailnetStage.Connected }
        compose.onNode(hasContentDescription(string(R.string.home_menu)) and hasClickAction()).performClick()
        compose.onNode(hasText(string(R.string.tailnet_disconnect_item, "Tailscale")) and hasClickAction()).performScrollTo().performClick()
        // The confirm names the host that goes with it.
        compose.onNode(hasText(string(R.string.tailnet_disconnect_title, "Tailscale")) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNode(hasText("mac", substring = true) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        val stops = node.stops
        compose.onNode(hasText(string(R.string.action_disconnect)) and hasAnyAncestor(isDialog())).performClick()
        compose.awaitThat { m.tailnetStage.value == TailnetStage.Disconnected(byYou = true) }
        compose.awaitThat { node.stops > stops && "mac-host" !in m.lives.value }
        // Quiet: the chip, grey, not a card.
        compose.onNodeWithTag(TAILNET_CARD_TAG).assertIsDisplayed()
        // Kept off for the next start, and the host is not connected again on its own.
        assertTrue(TailnetPolicyStore(compose.activity.application).read().isOff("tailscale"))
        assertEquals(emptyList<String>(), HostStore(compose.activity.application).connectedHosts())
        // The snackbar's Reconnect brings the tailnet and its host back.
        val starts = node.starts
        compose.onNode(hasText(string(R.string.action_reconnect)) and hasClickAction()).performClick()
        compose.awaitThat { node.starts > starts && m.tailnetStage.value is TailnetStage.Connected }
        compose.awaitThat { m.lives.value["mac-host"]?.state?.phase == Phase.Live }
        assertTrue(!TailnetPolicyStore(compose.activity.application).read().isOff("tailscale"))
    }

    @Test
    fun aTailnetDisconnectedByHandStaysOffAtTheNextStartUntilAHostIsConnected() {
        deskThroughTheNode()
        val m = launch(open = TailnetSamples.macHost, policies = TailnetPolicies().switched("tailscale", on = false))
        compose.awaitThat { m.tailnetStage.value == TailnetStage.Disconnected(byYou = true) }
        compose.waitForIdle()
        assertEquals("nothing started the node", 0, node.starts)
        assertTrue("the host on it was not connected", "mac-host" !in m.lives.value)
        // Connecting the host by hand turns its tailnet back on.
        compose.runOnUiThread { m.connect(TailnetSamples.macHost) }
        compose.awaitThat { m.lives.value["mac-host"]?.state?.phase == Phase.Live }
        assertTrue(node.starts > 0)
        assertTrue(!TailnetPolicyStore(compose.activity.application).read().isOff("tailscale"))
    }

    @Test
    fun onlyWhenNeededWaitsForItsFirstHost() {
        deskThroughTheNode()
        val needed = TailnetPolicies().with("tailscale", TailnetPolicy(use = TailnetUse.WhenNeeded))
        val m = launch(open = null, policies = needed)
        compose.awaitThat { m.tailnetStage.value == TailnetStage.Disconnected(byYou = false) }
        compose.onNodeWithText(string(R.string.tailnet_stage_standby, "Tailscale"), substring = true, useUnmergedTree = true)
        assertEquals(0, node.starts)
        compose.runOnUiThread { m.connect(TailnetSamples.macHost) }
        compose.awaitThat { m.lives.value["mac-host"]?.state?.phase == Phase.Live }
        assertEquals(1, node.starts)
        // The host goes: the tailnet lingers, its stop time on the view.
        compose.runOnUiThread { m.disconnect("mac-host") }
        compose.awaitThat { m.tailnetView.value.lingerUntil != null }
        assertTrue(m.tailnetView.value.running)
    }

    @Test
    fun theTailnetPageSetsWhenItRuns() {
        deskThroughTheNode()
        val m = launch(open = null)
        compose.awaitThat { m.tailnetStage.value is TailnetStage.Connected }
        compose.onNodeWithTag(TAILNET_CARD_TAG).performClick()
        compose.onNode(hasText(string(R.string.conn_settings)) and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText(string(R.string.tailnet_use)) and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText(string(R.string.tailnet_use_needed)) and hasAnyAncestor(isDialog())).performClick()
        compose.awaitThat { m.tailnetView.value.policy.use == TailnetUse.WhenNeeded }
        assertEquals(TailnetUse.WhenNeeded, TailnetPolicyStore(compose.activity.application).read().of("tailscale").use)
        // Nobody uses it: it lingers now, and the page says when it stops.
        compose.awaitThat { m.tailnetView.value.lingerUntil != null }
        compose.onNode(hasText(compose.activity.resources.getQuantityString(R.plurals.tailnet_stops_in, 5, 5))).assertIsDisplayed()
    }
}

private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onNodeWithText(text: String, substring: Boolean, useUnmergedTree: Boolean) =
    onNode(hasText(text, substring = substring), useUnmergedTree)
