package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.ScriptedRex
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rex's custom actions with two hosts: each host's configuration file has its own, so the palette
 * on one host never lists the other's, even before the host on screen has answered
 * `client.keymap`. The whole app on its view model, on two ScriptedRex servers. JVM and emulator.
 */
@RunWith(AndroidJUnit4::class)
class RexActionsHostsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mac = RexOnDesk(sessions = 1, hostId = "mac-host", hostLabel = "mac")
    private val linux = RexOnDesk(sessions = 1, hostId = "linux-host", hostLabel = "linux")
    private var model: RexViewModel? = null

    private fun string(id: Int) = compose.activity.getString(id)

    private fun live(m: RexViewModel, desk: RexOnDesk): Boolean =
        m.lives.value[desk.host.id]?.state?.let { it.phase == Phase.Live && it.views.containsKey(desk.ids[0]) } == true

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnectAll() } }
        mac.close()
        linux.close()
    }

    private fun openTab(m: RexViewModel, desk: RexOnDesk) {
        val tab = desk.tabs(desk.ids[0]).first()
        compose.runOnUiThread { m.openTabOn(desk.host, desk.ids[0], tab) }
        compose.awaitThat { m.route.value == Route.Term && m.host?.id == desk.host.id && m.pocket.value.view?.focusedBlock() != null }
    }

    private fun openPalette() {
        compose.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.palette_title)).performClick()
        compose.waitForIdle()
    }

    private fun shows(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun thePaletteListsOnlyTheActionsOfTheHostOnScreen() {
        mac.rex.luaActions += ScriptedRex.ScriptedAction("pick_fruit", "Pick a Fruit", prompt = JSONObject().put("kind", "input").put("title", "Fruit"))
        val app = freshApp()
        val store = mac.saveFor(app)
        store.upsert(linux.host)
        store.setConnectedHosts(listOf(mac.host.id, linux.host.id))
        val m = launchedModel(compose, app)
        model = m
        compose.setContent { RexPocketApp(m) }
        compose.awaitThat { live(m, mac) && live(m, linux) }

        openTab(m, mac)
        openPalette()
        compose.awaitThat { shows("Pick a Fruit") }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.awaitThat { !shows("Pick a Fruit") }
        compose.runOnUiThread { m.showHome() }
        compose.awaitThat { m.route.value == Route.Home }

        // The linux host is slow to read its file: until it answers, its palette has none.
        linux.rex.hold("client.keymap")
        openTab(m, linux)
        openPalette()
        // The palette is up and has asked; Rex holds the answer (and the calls behind it).
        Thread.sleep(500)
        compose.waitForIdle()
        assertTrue("the mac's action is not offered on the linux host", !shows("Pick a Fruit"))
        linux.rex.release("client.keymap")
        compose.awaitThat { linux.rex.calls.any { it.startsWith("client.keymap") } }
        compose.waitForIdle()
        assertTrue(!shows("Pick a Fruit"))
    }
}
