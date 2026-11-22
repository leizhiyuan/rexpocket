package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.ConnectStep
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.TailnetStage
import me.leaver.rexpocket.core.Trouble
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the home screen says about the tailnet: the node's way from starting to connected, a sign-in
 * or a failed start loud with its fix, the network's devices, and why a host on it was not reached.
 *
 * The whole app on its real view model and store; only the node is a stand-in (its Go library loads
 * on a phone only), reporting what the real nodes report. A connection goes through a SOCKS5 proxy
 * on loopback, as the node's does. Runs on the JVM (Robolectric) and on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class TailnetStatusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = FakeTailnetNode()
    private var model: RexViewModel? = null
    private val closing = mutableListOf<AutoCloseable>()

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun launch(vararg hosts: me.leaver.rexpocket.data.SavedHost = arrayOf(TailnetSamples.macHost, TailnetSamples.nasHost), open: me.leaver.rexpocket.data.SavedHost? = null): RexViewModel {
        val m = tailnetModel(compose, node, hosts.toList(), open)
        model = m
        compose.setContent { RexPocketApp(m) }
        compose.waitForIdle()
        return m
    }

    private fun awaitProblem(m: RexViewModel, trouble: Trouble) {
        try {
            compose.awaitThat { m.pocket.value.problem?.trouble == trouble }
        } catch (e: AssertionError) {
            throw AssertionError("want $trouble, the connection is ${m.pocket.value.phase} with ${m.pocket.value.problem}, at ${m.connectProgress.value}", e)
        }
    }

    /** Waits for [text] on screen, and says what is there instead when it does not come. */
    private fun awaitText(text: String, substring: Boolean = false) {
        try {
            // A problem is shown once it has lasted a moment (rememberSettledProblem): the clock is moved on.
            compose.awaitThat(10_000) {
                compose.mainClock.advanceTimeBy(500)
                compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: AssertionError) {
            val shown = compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().flatMap { root ->
                generateSequence(listOf(root)) { level -> level.flatMap { it.children }.takeIf { it.isNotEmpty() } }.flatten()
                    .mapNotNull { it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString { t -> t.text } }
            }
            throw AssertionError("want \"$text\" on screen; shown: $shown", e)
        }
    }

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnect() } }
        closing.forEach { runCatching { it.close() } }
    }

    @Test
    fun aStartingNodeSaysWhichStepItIsOn() {
        val m = launch()
        compose.awaitThat { m.tailnetStage.value == TailnetStage.Starting }
        compose.onNodeWithText(string(R.string.tailnet_stage_starting, "Tailscale")).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.tailnet_step, 1, 3), substring = true).assertIsDisplayed()
        node.state.value = TailnetSamples.connecting
        compose.awaitThat { m.tailnetStage.value == TailnetStage.Connecting }
        compose.onNodeWithText(string(R.string.tailnet_stage_connecting, "Tailscale")).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.tailnet_step, 3, 3), substring = true).assertIsDisplayed()
    }

    @Test
    fun aNodeThatNeedsASignInOffersIt() {
        node.state.value = TailnetSamples.needsLogin
        val m = launch()
        compose.awaitThat { m.tailnetStage.value is TailnetStage.SigningIn }
        compose.onNodeWithText(string(R.string.tailnet_stage_signing_in, "Tailscale")).assertIsDisplayed()
        compose.onNode(hasText(string(R.string.action_sign_in)) and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText(string(R.string.action_copy_link)) and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.tailnet_step, 2, 3)).assertIsDisplayed()
    }

    @Test
    fun aConnectedPhoneIsOneQuietLine() {
        node.state.value = TailnetSamples.connected()
        launch()
        compose.awaitThat { model!!.tailnetStage.value is TailnetStage.Connected }
        compose.onNodeWithText("Tailscale · " + string(R.string.tailnet_stage_connected_on, "pixel-8", "example.com")).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.tailnet_devices_online, 3, 5)).assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasText(string(R.string.action_sign_in))).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theDetailsListTheDevicesWithTheRexHostsFirst() {
        node.state.value = TailnetSamples.connected()
        launch()
        compose.onNodeWithTag(TAILNET_CARD_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("100.101.102.103", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("me@example.com").performScrollTo().assertIsDisplayed()
        val rows = compose.onAllNodesWithTag(TAILNET_DEVICE_TAG).fetchSemanticsNodes()
        assertEquals(5, rows.size)
        // The saved hosts first, then online before offline; each says what it is.
        val names = rows.map { row -> row.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.first()?.text }
        assertEquals(listOf("mac", "nas", "build-box", "iphone", "desktop"), names)
        compose.onAllNodes(hasText(string(R.string.tailnet_peer_rex_host)), useUnmergedTree = true).fetchSemanticsNodes().let {
            assertEquals(2, it.size)
        }
        compose.onNodeWithText("macOS · " + string(R.string.tailnet_peer_online) + " · " + string(R.string.tailnet_link_direct), useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aDeviceThatIsNotAHostIsOfferedAsOne() {
        node.state.value = TailnetSamples.connected()
        launch()
        compose.onNodeWithTag(TAILNET_CARD_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("build-box", useUnmergedTree = true).performScrollTo()
        // The host dialog never lets Compose go idle under Robolectric (it did not before this test
        // either), so what it shows is checked on the emulator.
        assumeFalse("a text field in a dialog never idles on Robolectric", onRobolectric)
        compose.onNodeWithText("build-box", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.action_add_host)).assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("build-box.tail1234.ts.net:7297")).assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("build-box")).assertIsDisplayed()
    }

    @Test
    fun anOfflineDeviceIsWhyItsHostIsNotReached() {
        val socks = FakeSocks { _, _ -> null }.also(closing::add)
        node.socksPort = socks.port
        node.state.value = TailnetSamples.connected()
        val m = launch(open = TailnetSamples.nasHost)
        awaitProblem(m, Trouble.TailnetPeerOffline)
        awaitText("nas.tail1234.ts.net is offline on Tailscale, last seen", substring = true)
        compose.onNode(hasText(string(R.string.action_devices)) and hasClickAction()).assertIsDisplayed()
        assertTrue("the host was dialed by its name", socks.asked.any { it.startsWith("nas.tail1234.ts.net:") })
    }

    @Test
    fun aPortNobodyListensOnIsRexNotListening() {
        val socks = FakeSocks { _, _ -> null }.also(closing::add)
        node.socksPort = socks.port
        node.dialErrors["mac.tail1234.ts.net"] = "connect tcp 100.64.0.2:7297: connection was refused"
        node.state.value = TailnetSamples.connected()
        val m = launch(open = TailnetSamples.macHost)
        awaitProblem(m, Trouble.RexNotListening)
        awaitText(string(R.string.problem_rex_not_listening, "mac.tail1234.ts.net", "7297"))
    }

    @Test
    fun aNameTheTailnetDoesNotHaveIsSaid() {
        val socks = FakeSocks { _, _ -> null }.also(closing::add)
        node.socksPort = socks.port
        node.state.value = TailnetSamples.connected()
        val typo = TailnetSamples.macHost.copy(id = "typo", label = "mini", host = "mini.tail1234.ts.net")
        val m = launch(typo, open = typo)
        awaitProblem(m, Trouble.TailnetPeerUnknown)
        awaitText(string(R.string.problem_tailnet_peer_unknown, "Tailscale", "mini.tail1234.ts.net"))
    }

    @Test
    fun aHostOnTheTailnetConnectsStepByStep() {
        RexOnDesk(sessions = 1).use { desk ->
            // The node's proxy reaches the host only once a moment has passed, and Rex holds its
            // sessions back, so each step is on screen long enough to be seen.
            val socks = FakeSocks { _, _ ->
                Thread.sleep(300)
                InetSocketAddress("127.0.0.1", desk.rex.endpoint.port)
            }.also(closing::add)
            node.socksPort = socks.port
            node.state.value = TailnetSamples.connected()
            desk.rex.hold("session.bootstrap")
            val m = tailnetModel(compose, node, listOf(TailnetSamples.macHost), open = TailnetSamples.macHost)
            model = m
            compose.setContent { RexPocketApp(m) }
            compose.awaitThat { m.connectProgress.value?.step == ConnectStep.OpenRex }
            awaitText(string(R.string.connect_step_rex))
            // Each step before it, with how long it took: reaching the host took the proxy's 300 ms.
            val took = m.connectProgress.value!!.took
            assertEquals(listOf(ConnectStep.StartTailnet, ConnectStep.FindPeer, ConnectStep.Reach), took.map { it.first })
            assertTrue("reaching took ${took.last().second} ms", took.last().second >= 250)
            desk.rex.release("session.bootstrap")
            compose.awaitThat { m.pocket.value.phase == Phase.Live }
            // Once it is live, the steps are gone: the host's own line says it is online.
            compose.awaitThat { m.connectProgress.value == null }
            assertNull(m.pocket.value.problem)
        }
    }

    @Test
    fun aStartThatFailedOffersTryAgain() {
        node.startError = "tsnet: no state directory"
        val m = launch()
        compose.awaitThat { m.tailnetStage.value is TailnetStage.Failed }
        compose.onNodeWithText(string(R.string.tailnet_stage_failed, "Tailscale")).assertIsDisplayed()
        compose.onNodeWithText("tsnet: no state directory").assertIsDisplayed()
        val before = node.starts
        node.startError = null
        compose.onNode(hasText(string(R.string.action_try_again)) and hasClickAction()).performClick()
        compose.awaitThat { node.starts > before && m.tailnetStage.value == TailnetStage.Starting }
    }
}
