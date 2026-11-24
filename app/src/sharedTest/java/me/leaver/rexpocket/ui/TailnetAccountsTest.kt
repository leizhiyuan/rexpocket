package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.TailnetStage
import me.leaver.rexpocket.data.HostStore
import me.leaver.rexpocket.data.TailnetProfileStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress

/**
 * Tailnet accounts: one added from the tailnet's page runs at once on a login of its own, a host
 * that names an account switches the tailnet to it when connected, and a removed account takes its
 * login and leaves its hosts following the one in use. See docs/design/tailnet-control.md, "Accounts".
 */
@RunWith(AndroidJUnit4::class)
class TailnetAccountsTest {
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

    private fun launch(): RexViewModel {
        val desk = RexOnDesk(sessions = 1).also(closing::add)
        val socks = FakeSocks { _, _ -> InetSocketAddress("127.0.0.1", desk.rex.endpoint.port) }.also(closing::add)
        node.socksPort = socks.port
        node.state.value = TailnetSamples.connected()
        val m = tailnetModel(compose, node, listOf(TailnetSamples.macHost), open = null)
        model = m
        compose.setContent { RexPocketApp(m) }
        compose.awaitThat { m.tailnetStage.value is TailnetStage.Connected }
        return m
    }

    @Test
    fun anAccountAddedOnThePageRunsAtOnceOnItsOwnLogin() {
        org.junit.Assume.assumeFalse("a text field in a dialog never idles on Robolectric", onRobolectric)
        val m = launch()
        assertEquals("tailscale", node.lastConfig?.profile)
        compose.onNodeWithTag(TAILNET_CARD_TAG).performClick()
        compose.onNode(hasText(string(R.string.conn_settings)) and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText(string(R.string.tailnet_account_add)) and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("Work")
        val stops = node.stops
        compose.onNode(hasText(string(R.string.tailnet_account_add_action)) and hasAnyAncestor(isDialog())).performClick()
        compose.awaitThat { m.tailnetView.value.accounts.size == 2 }
        val work = m.tailnetView.value.accounts.single { it.name == "Work" }
        assertEquals(work.id, m.tailnetView.value.account)
        compose.awaitThat { node.stops > stops && node.lastConfig?.profile == work.id }
        assertEquals("Tailscale · Work", m.tailnetView.value.name)
        assertEquals(work.id, TailnetProfileStore(compose.activity.application).read().activeOf(work.backend).id)
    }

    @Test
    fun aHostOnAnotherAccountSwitchesTheTailnetToItAndARemovedAccountLetsItFollow() {
        val m = launch()
        compose.runOnUiThread { m.addTailnetAccount("Work") }
        compose.awaitThat { m.tailnetView.value.accounts.size == 2 }
        val work = m.tailnetView.value.accounts.single { it.name == "Work" }
        // It runs at once, on a login of its own.
        compose.awaitThat { node.lastConfig?.profile == work.id && m.tailnetView.value.account == work.id }
        // mac is on the first account; the tailnet runs Work.
        val mac = TailnetSamples.macHost.copy(tailnetProfile = "tailscale")
        compose.runOnUiThread { m.saveHost(mac) }
        compose.runOnUiThread { m.connect(mac) }
        compose.awaitThat { m.tailnetView.value.account == "tailscale" && node.lastConfig?.profile == "tailscale" }
        compose.awaitThat { m.lives.value[mac.id]?.state?.phase == Phase.Live }
        // Work is not running now: it can go, and its login with it.
        compose.runOnUiThread { m.removeTailnetAccount(work.id) }
        assertEquals(1, m.tailnetView.value.accounts.size)
        // A host that named it follows the one in use.
        compose.runOnUiThread { m.saveHost(mac.copy(tailnetProfile = work.id)) }
        compose.runOnUiThread { m.addTailnetAccount("Home") }
        compose.awaitThat { m.tailnetView.value.accounts.size == 2 }
        val home = m.tailnetView.value.accounts.single { it.name == "Home" }
        compose.runOnUiThread { m.switchTailnetAccount("tailscale") }
        compose.runOnUiThread { m.removeTailnetAccount(home.id) }
        assertTrue(HostStore(compose.activity.application).list().none { it.tailnetProfile == home.id })
    }

    @Test
    fun aNodeThatCannotKeepAccountsShowsNoneUntilItCan() {
        val m = launch()
        compose.runOnUiThread { m.addTailnetAccount("Work") }
        compose.awaitThat { m.tailnetView.value.accounts.size == 2 }
        // The node can no longer keep them (its helper app went back to an older version): the
        // page shows no Accounts group; the accounts are kept for when it can again.
        node.accountsKept.value = false
        compose.awaitThat { m.tailnetView.value.accounts.isEmpty() }
        node.accountsKept.value = true
        compose.awaitThat { m.tailnetView.value.accounts.size == 2 }
    }
}
