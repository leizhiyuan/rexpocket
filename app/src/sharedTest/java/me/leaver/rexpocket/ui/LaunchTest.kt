package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
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
import org.junit.Assert.assertFalse
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
        val attaches = { desk.rex.calls.count { it == "session.attach ${desk.ids[1]}" } }
        val before = attaches()
        val m = launch(killedOnTerminal = true)
        compose.awaitThat { m.route.value == Route.Term }
        compose.waitForIdle()
        assertEquals(desk.ids[1], m.pocket.value.activeSessionId)
        assertEquals(Route.Term, m.route.value)
        // session.bootstrap asked for it and attached it, with its view: no second round trip.
        assertEquals("session.attach after the bootstrap that chose it", before, attaches())
        assertTrue(m.pocket.value.view?.focusedBlock() != null)
    }

    @Test
    fun aProgramsCopyInThePaneOnScreenGoesOnTheClipboard() {
        // OSC 52: Rex tells the clients with clipboard_written, and only the phone can put it on its clipboard.
        val m = launch(killedOnTerminal = true)
        compose.awaitThat { m.route.value == Route.Term && m.pocket.value.view?.focusedBlock() != null }
        compose.waitForIdle()
        val pane = m.pocket.value.view!!.focusedBlock()!!.id
        desk.rex.copy(pane, "copied by vim")
        // Read whether or not the system's copied overlay holds the focus: see clipboardInBackground.
        clipboardInBackground(compose.activity) {
            compose.awaitThat { compose.runOnUiThread<String?> { clipboard(compose.activity) } == "copied by vim" }
        }
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
    fun settingsRowsThatOpenAPageEndInAChevron() {
        val m = launch()
        compose.awaitLive(m, desk.ids[1])
        compose.onNodeWithContentDescription(string(R.string.settings)).performClick()
        compose.waitForIdle()
        for (opens in listOf(
            R.string.tailnet_title, R.string.notifications_title, R.string.controls_title, R.string.terminal_font_title, R.string.appearance_title,
            R.string.reopen_last_tab, R.string.update_channel, R.string.diagnostics,
        )) {
            assertTrue("${string(opens)} opens a page or a picker", compose.rowHasChevron(string(opens)))
        }
        for (acts in listOf(R.string.update_title, R.string.settings_source)) {
            assertFalse("${string(acts)} acts in place", compose.rowHasChevron(string(acts)))
        }
    }

    @Test
    fun notificationsHasASwitchForEachKindThatTheStoreKeeps() {
        val m = launch()
        compose.awaitLive(m, desk.ids[1])
        compose.onNodeWithContentDescription(string(R.string.settings)).performClick()
        compose.waitForIdle()
        compose.onNode(hasText(string(R.string.notifications_title)) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
        // Background alerts comes first; Android's own pages open from the checks. A switch's
        // On or Off is its toggle state, which TalkBack says once.
        compose.onNode(hasText(string(R.string.alerts_title)) and hasClickAction()).assert(isOff())
        assertTrue(compose.rowHasChevron(string(R.string.notifications_android)))
        assertFalse(compose.rowHasChevron(string(R.string.notifications_done)))
        val done = compose.onNode(hasText(string(R.string.notifications_done)) and hasClickAction()).performScrollTo()
        done.assert(isOn())
        done.performClick()
        compose.waitForIdle()
        compose.onNode(hasText(string(R.string.notifications_done)) and hasClickAction()).assert(isOff())
        // A bell off screen is opt-in.
        compose.onNode(hasText(string(R.string.notifications_bells)) and hasClickAction()).performScrollTo().assert(isOff())
        assertFalse(m.alertPrefs.value.done)
        assertFalse(HostStore(compose.activity).alertPrefs().done)
        assertTrue(HostStore(compose.activity).alertPrefs().waiting)
        // Every channel has its Android page here, failures and progress bars too.
        for (channel in listOf(R.string.alerts_channel_failed, R.string.alerts_channel_progress)) {
            compose.onNode(hasText(string(channel)) and hasClickAction()).performScrollTo()
            assertTrue(compose.rowHasChevron(string(channel)))
        }
        compose.assertStaysHome(m, ms = 500)
    }

    @Test
    fun theSettingRowTurnsReopenLastTabOnAndOff() {
        val m = launch()
        compose.awaitLive(m, desk.ids[1])
        compose.onNodeWithContentDescription(string(R.string.settings)).performClick()
        compose.waitForIdle()
        val row = compose.onNode(hasText(string(R.string.reopen_last_tab)) and hasClickAction()).performScrollTo()
        row.assert(hasText(string(R.string.gesture_off)))
        compose.pick(string(R.string.reopen_last_tab), string(R.string.gesture_on))
        compose.onNode(hasText(string(R.string.reopen_last_tab)) and hasClickAction())
            .assert(hasText(string(R.string.gesture_on)))
        assertTrue(m.reopenLastTab.value)
        assertTrue(HostStore(compose.activity).reopenLastTab())
        compose.assertStaysHome(m, ms = 500)
    }
}
