package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.Trouble
import me.leaver.rexpocket.data.HostStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the app shows when it starts and connects to the host it had open: the list, unless this is
 * plainly a continuation of the terminal - Android killed the app while it was on screen, or the
 * person asked for the last tab on every launch - and even then only while the person has not done
 * anything yet. A connection finishing never takes them out of a screen they opened.
 *
 * The whole app on its real view model and store, connected to ScriptedRex. Runs on the JVM
 * (Robolectric) and on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class LaunchTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 2)
    private var model: RexViewModel? = null

    private fun string(id: Int) = compose.activity.getString(id)

    private fun launch(killedOnTerminal: Boolean = false, reopenLastTab: Boolean = false): RexViewModel {
        val app = freshApp()
        // The second session was on the terminal: Rex's own pick at connect would be the first.
        val store = desk.saveFor(app, lastSession = desk.ids[1])
        store.setReopenLastTab(reopenLastTab)
        val m = launchedModel(compose, app, killedOnTerminal)
        model = m
        compose.setContent { RexPocketApp(m) }
        return m
    }

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnect() } }
        desk.close()
    }

    @Test
    fun aFreshStartConnectsAndStaysOnTheList() {
        val m = launch()
        compose.awaitLive(m, desk.ids[1])
        compose.assertStaysHome(m)
        compose.onNodeWithText("desk").assertIsDisplayed()
    }

    @Test
    fun settingsOpenedWhileConnectingStayOpenWhenTheConnectionIsUp() {
        // The strongest case for going back: the app was killed on the terminal. The person opens
        // Settings before the connection is up, and stays there.
        desk.rex.hold("session.bootstrap")
        val m = launch(killedOnTerminal = true)
        compose.waitForIdle()
        compose.onNodeWithContentDescription(string(R.string.settings)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.settings)).assertIsDisplayed()
        desk.rex.release("session.bootstrap")
        compose.awaitLive(m, desk.ids[1])
        compose.assertStaysHome(m)
        compose.onNodeWithText(string(R.string.settings)).assertIsDisplayed()
    }

    @Test
    fun killedOnTheTerminalComesBackToTheSameSession() {
        val m = launch(killedOnTerminal = true)
        compose.awaitThat { m.route.value == Route.Term }
        compose.waitForIdle()
        assertEquals(desk.ids[1], m.pocket.value.activeSessionId)
        assertEquals(Route.Term, m.route.value)
    }

    @Test
    fun aProgramsCopyInThePaneOnScreenGoesOnTheClipboard() {
        // OSC 52: Rex tells the clients with clipboard_written, and only the phone can put it on its clipboard.
        val m = launch(killedOnTerminal = true)
        compose.awaitThat { m.route.value == Route.Term && m.pocket.value.view?.focusedBlock() != null }
        compose.waitForIdle()
        val pane = m.pocket.value.view!!.focusedBlock()!!.id
        desk.rex.copy(pane, "copied by vim")
        compose.awaitThat { compose.runOnUiThread<String?> { clipboard(compose.activity) } == "copied by vim" }
    }

    @Test
    fun reopenLastTabBringsItBackOnAFreshStart() {
        val m = launch(reopenLastTab = true)
        compose.awaitThat { m.route.value == Route.Term }
        compose.waitForIdle()
        assertEquals(desk.ids[1], m.pocket.value.activeSessionId)
    }

    @Test
    fun retryOnTheListAfterAFailedConnectStaysOnTheList() {
        // A plain launch whose connection failed for good (here: a tailnet host with no tailnet
        // joined). The person fixes the host and taps Retry on the list: the connection comes up
        // and they stay on the list, as on any launch that is not a continuation.
        val app = freshApp()
        val store = desk.saveFor(app, lastSession = desk.ids[1])
        store.upsert(desk.host.copy(viaTailscale = true))
        val m = launchedModel(compose, app)
        model = m
        compose.setContent { RexPocketApp(m) }
        compose.awaitThat { m.pocket.value.problem?.trouble == Trouble.TailnetNotJoined }
        compose.runOnUiThread {
            m.saveHost(desk.host)
            m.retryNow()
        }
        compose.awaitLive(m, desk.ids[1])
        compose.assertStaysHome(m)
    }

    @Test
    fun theSettingRowTurnsReopenLastTabOnAndOff() {
        val m = launch()
        compose.awaitLive(m, desk.ids[1])
        compose.onNodeWithContentDescription(string(R.string.settings)).performClick()
        compose.waitForIdle()
        val row = compose.onNode(hasText(string(R.string.reopen_last_tab)) and hasClickAction()).performScrollTo()
        row.assert(hasText(string(R.string.reopen_last_tab_off)))
        row.performClick()
        compose.waitForIdle()
        compose.onNode(hasText(string(R.string.reopen_last_tab)) and hasClickAction())
            .assert(hasText(string(R.string.reopen_last_tab_on)))
        assertTrue(m.reopenLastTab.value)
        assertTrue(HostStore(compose.activity).reopenLastTab())
        compose.assertStaysHome(m, ms = 500)
    }
}
