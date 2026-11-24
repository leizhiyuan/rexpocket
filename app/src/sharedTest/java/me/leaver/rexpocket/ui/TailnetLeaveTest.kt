package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.TailnetBackend
import me.leaver.rexpocket.core.TailnetProfile
import me.leaver.rexpocket.core.TailnetStage
import me.leaver.rexpocket.data.TailnetProfileStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress

/**
 * Leaving a tailnet and its accounts, as the person would hit them: Leave confirms and names the
 * hosts that go, can forget the sign-in, and the snackbar's Rejoin brings both back; the first
 * account can be removed once another runs, its login with it; connecting a host on another
 * account says what it switched and Undo switches back. See docs/design/tailnet-control.md.
 */
@RunWith(AndroidJUnit4::class)
class TailnetLeaveTest {
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

    private fun launch(open: me.leaver.rexpocket.data.SavedHost? = TailnetSamples.macHost): RexViewModel {
        val desk = RexOnDesk(sessions = 1).also(closing::add)
        val socks = FakeSocks { _, _ -> InetSocketAddress("127.0.0.1", desk.rex.endpoint.port) }.also(closing::add)
        node.socksPort = socks.port
        node.state.value = TailnetSamples.connected()
        val m = tailnetModel(compose, node, listOf(TailnetSamples.macHost), open = open)
        model = m
        compose.setContent { RexPocketApp(m) }
        compose.awaitThat { m.tailnetStage.value is TailnetStage.Connected }
        return m
    }

    @Test
    fun theLeaveConfirmNamesItsHostsAndCanForgetTheSignIn() {
        val asked = mutableListOf<Boolean>()
        val view = TailnetView(id = "tailscale", name = "Tailscale", joined = true, hosts = listOf(TailnetSamples.macHost))
        compose.setContent { RexTheme { LeaveTailnetDialog(view, onDismiss = {}) { asked += it } } }
        compose.onNode(hasText(string(R.string.tailnet_leave_title, "Tailscale")) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNode(hasText("mac", substring = true) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithTag(LEAVE_FORGET_TAG).performClick()
        compose.onNode(hasText(string(R.string.tailnet_leave_and_forget)) and hasClickAction()).performClick()
        assertEquals(listOf(true), asked)
    }

    @Test
    fun leavingDisconnectsItsHostsForgetsTheSignInAndRejoinBringsThemBack() {
        val m = launch()
        compose.awaitThat { m.lives.value["mac-host"]?.state?.phase == Phase.Live }
        compose.runOnUiThread { m.leaveTailnet(forgetSignIn = true) }
        compose.awaitThat { "mac-host" !in m.lives.value && !m.tailnet.value.enabled }
        // The login of the account in use goes once the node is stopped.
        compose.awaitThat { node.forgotten.toList() == listOf("tailscale") }
        compose.onNode(hasText(string(R.string.tailnet_left_forgot, "Tailscale"))).assertIsDisplayed()
        val starts = node.starts
        compose.onNode(hasText(string(R.string.tailnet_rejoin)) and hasClickAction()).performClick()
        compose.awaitThat { m.tailnet.value.enabled && node.starts > starts }
        compose.awaitThat { m.lives.value["mac-host"]?.state?.phase == Phase.Live }
    }

    @Test
    fun leavingWithoutForgettingKeepsTheSignIn() {
        val m = launch(open = null)
        compose.runOnUiThread { m.leaveTailnet() }
        compose.awaitThat { !m.tailnet.value.enabled }
        compose.onNode(hasText(string(R.string.tailnet_left, "Tailscale"))).assertIsDisplayed()
        compose.waitForIdle()
        assertTrue(node.forgotten.isEmpty())
    }

    @Test
    fun theFirstAccountIsRemovedOnceAnotherRunsAndItsLoginWithIt() {
        val m = launch(open = null)
        compose.runOnUiThread { m.addTailnetAccount("Work") }
        compose.awaitThat { m.tailnetView.value.accounts.size == 2 }
        val work = m.tailnetView.value.accounts.single { it.name == "Work" }
        compose.awaitThat { m.tailnetView.value.account == work.id }
        compose.runOnUiThread { m.removeTailnetAccount("tailscale") }
        compose.awaitThat { m.tailnetView.value.accounts.map { it.id } == listOf(work.id) }
        compose.awaitThat { "tailscale" in node.forgotten }
        // Kept so, for the next start.
        val stored = TailnetProfileStore(compose.activity.application).read()
        assertEquals(listOf(work.id), stored.of(TailnetBackend.Tailscale).map { it.id })
        assertEquals("Tailscale", m.tailnetView.value.name)
    }

    @Test
    fun connectingAHostOnAnotherAccountSaysWhatItSwitchedAndUndoSwitchesBack() {
        val m = launch(open = null)
        compose.runOnUiThread { m.addTailnetAccount("Work") }
        compose.awaitThat { m.tailnetView.value.accounts.size == 2 }
        val work: TailnetProfile = m.tailnetView.value.accounts.single { it.name == "Work" }
        compose.awaitThat { m.tailnetView.value.account == work.id }
        // mac is on Work, and connected; a second host names the first account.
        val mac = TailnetSamples.macHost.copy(tailnetProfile = work.id)
        val other = TailnetSamples.macHost.copy(id = "mac-2", label = "mac two", tailnetProfile = "tailscale")
        compose.runOnUiThread {
            m.saveHost(mac)
            m.saveHost(other)
            m.connect(mac)
        }
        compose.awaitThat { m.lives.value[mac.id]?.state?.phase == Phase.Live }
        compose.runOnUiThread { m.connect(other) }
        compose.awaitThat { m.tailnetView.value.account == "tailscale" }
        val said = string(R.string.tailnet_switched_to, "Tailscale", "Tailscale") + " · " +
            compose.activity.resources.getQuantityString(R.plurals.tailnet_switched_hosts, 1, 1)
        compose.onNode(hasText(said)).assertIsDisplayed()
        assertFalse(mac.id in m.lives.value)
        compose.onNode(hasText(string(R.string.action_undo)) and hasClickAction()).performClick()
        compose.awaitThat { m.tailnetView.value.account == work.id }
        compose.awaitThat { m.lives.value[mac.id]?.state?.phase == Phase.Live }
    }

    @Test
    fun theTailnetPageSignsInRestartsAndOpensTheTailnetSettings() {
        val did = mutableListOf<String>()
        val view = TailnetView(id = "tailscale", name = "Tailscale", joined = true, running = true)
        compose.setContent {
            RexTheme {
                TailnetPage(
                    view = view,
                    stage = TailnetStage.SigningIn("rexpocket-test://login/1"),
                    backgroundMode = false,
                    onPolicy = {},
                    onConnect = {},
                    onDisconnect = {},
                    onRetry = {},
                    onBack = null,
                    onDismiss = {},
                    onSignIn = { did += "sign in $it" },
                    onRestart = { did += "restart" },
                    onSettings = { did += "settings" },
                )
            }
        }
        compose.onNodeWithTag(TAILNET_SIGN_IN_TAG).performScrollTo().performClick()
        compose.onNode(hasText(string(R.string.tailnet_restart_item)) and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText(string(R.string.action_tailnet_settings)) and hasClickAction()).performScrollTo().performClick()
        compose.awaitThat { did.size == 3 }
        assertEquals(listOf("sign in rexpocket-test://login/1", "restart", "settings"), did)
    }

    @Test
    fun aHostItsTailnetKeepsOffSaysWhyAndClearsIt() {
        val off = TailnetView(id = "tailscale", name = "Tailscale", joined = true, off = true)
        assertEquals(HostParked.TailnetOff("Tailscale"), hostParked(TailnetSamples.macHost, off))
        val accounts = listOf(TailnetProfile("tailscale", TailnetBackend.Tailscale, "Tailscale"), TailnetProfile("tailscale-w", TailnetBackend.Tailscale, "Work"))
        val running = TailnetView(id = "tailscale", name = "Tailscale", joined = true, accounts = accounts, account = "tailscale")
        assertEquals(HostParked.OtherAccount("Work"), hostParked(TailnetSamples.macHost.copy(tailnetProfile = "tailscale-w"), running))
        assertEquals(null, hostParked(TailnetSamples.macHost, running))
        var cleared = 0
        compose.setContent {
            RexTheme {
                HostPage(
                    host = TailnetSamples.macHost,
                    live = null,
                    choices = me.leaver.rexpocket.core.HostBackground(),
                    defaults = me.leaver.rexpocket.core.AlertPrefs(),
                    backgroundMode = true,
                    onChoices = {},
                    onConnect = {},
                    onDisconnect = {},
                    onRetry = {},
                    onBack = null,
                    onDismiss = {},
                    parked = HostParked.TailnetOff("Tailscale"),
                    onUnpark = { cleared++ },
                )
            }
        }
        compose.onNode(hasText(string(R.string.host_parked_tailnet_off, "Tailscale"), substring = true), useUnmergedTree = true).assertExists()
        compose.onNode(hasText(string(R.string.host_parked_connect_tailnet, "Tailscale")) and hasAnyAncestor(hasTestTag(ACTION_SHEET_TAG))).performScrollTo().performClick()
        assertEquals(1, cleared)
    }
}
