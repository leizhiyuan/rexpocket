package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The home's session and tab sheets: a long press or the ⋮ opens them, and what they do reaches Rex
 * without the phone attaching the session or taking a pane, so the desk's sizes stay. A close or an
 * end is confirmed first. The whole app on its real view model, connected to ScriptedRex, which
 * refuses what real Rex refuses to an unattached connection (RexShapeTest). On the JVM and the
 * emulator; the rename dialog's text field only on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class HomeManageTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 2)
    private var model: RexViewModel? = null

    /** What reached Rex before the tests acted: the desk attaches the sessions it made. */
    private var callsBefore = 0

    /** Attached at connect; the tests manage [other], which the phone never attaches. */
    private val shown get() = desk.ids[0]
    private val other get() = desk.ids[1]

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun start(): RexViewModel {
        val app = freshApp()
        desk.saveFor(app, lastSession = shown)
        val m = launchedModel(compose, app)
        model = m
        compose.setContent { RexPocketApp(m) }
        compose.awaitLive(m, other)
        callsBefore = desk.rex.calls.size
        return m
    }

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnect() } }
        desk.close()
    }

    private fun label(m: RexViewModel) = m.pocket.value.sessions.first { it.id == other }.label

    private fun view(m: RexViewModel) = m.pocket.value.views.getValue(other)

    /** Nothing the sheets did attached [other] or took one of its panes. */
    private fun assertNotAttached() {
        val panes = view(model!!).windows.flatMap { w -> w.blocks.map { it.id } }.toSet()
        val touched = desk.rex.calls.drop(callsBefore).filter { it == "session.attach $other" || (it.startsWith("session.own_block ") && it.substringAfter(' ') in panes) }
        assertTrue("the home attached or took a pane: $touched", touched.isEmpty())
        assertEquals(Route.Home, model!!.route.value)
    }

    private fun inSheet(text: String) = compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestorTagged())

    private fun hasAnyAncestorTagged() = androidx.compose.ui.test.hasAnyAncestor(hasTestTag(ACTION_SHEET_TAG))

    @Test
    fun aLongPressOnATabClosesItAfterAConfirmWithoutAttaching() {
        val m = start()
        val before = view(m).windows.size
        val tabTitle = string(R.string.tab_number, 2)
        // The second session's second tab; the first session's has the same title, and comes first.
        compose.onAllNodes(hasText(tabTitle) and hasClickAction())[1].performTouchInput { longClick() }
        compose.waitForIdle()
        inSheet(string(R.string.menu_rename_tab)).assertExists()
        inSheet(string(R.string.menu_close_tab)).performClick()
        compose.waitForIdle()
        // The close waits for its confirmation: nothing has gone yet.
        assertEquals(before, view(m).windows.size)
        compose.onNodeWithText(string(R.string.confirm_close_tab, tabTitle)).assertExists()
        compose.onNode(hasText(string(R.string.action_close)) and hasClickAction()).performClick()
        compose.awaitThat { view(m).windows.size == before - 1 }
        assertNotAttached()
    }

    @Test
    fun theSessionsOverflowMakesATabAndEndsTheSessionWithoutAttaching() {
        val m = start()
        val before = view(m).windows.size
        val more = string(R.string.manage_actions_for, label(m))
        compose.onNode(hasContentDescription(more) and hasClickAction()).performClick()
        compose.waitForIdle()
        inSheet(string(R.string.menu_new_tab)).performClick()
        compose.awaitThat { view(m).windows.size == before + 1 }
        // The new tab's pane has no owner: Rex gives it to an attached caller only.
        assertTrue(view(m).windows.last().blocks.all { it.owner == null })
        assertNotAttached()

        compose.onNode(hasContentDescription(more) and hasClickAction()).performClick()
        compose.waitForIdle()
        inSheet(string(R.string.menu_kill_session)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.confirm_kill_session, label(m))).assertExists()
        compose.onNode(hasText(string(R.string.action_end)) and hasClickAction()).performClick()
        compose.awaitThat { m.pocket.value.sessions.none { it.id == other } }
        assertEquals("the session on the phone stays", shown, m.pocket.value.activeSessionId)
        assertEquals(Route.Home, m.route.value)
        assertFalse(desk.rex.calls.drop(callsBefore).any { it == "session.attach $other" })
    }

    @Test
    fun renamingFromTheSheetsReachesRex() {
        assumeFalse("a text field in a dialog never idles on Robolectric", onRobolectric)
        val m = start()
        compose.onNode(hasContentDescription(string(R.string.manage_actions_for, label(m))) and hasClickAction()).performClick()
        compose.waitForIdle()
        inSheet(string(R.string.menu_rename_session)).performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextReplacement("renamed at home")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.awaitThat { view(m).label == "renamed at home" }

        val tab = view(m).windows.first()
        compose.onAllNodes(hasContentDescription(string(R.string.manage_actions_for, string(R.string.tab_number, 1))) and hasClickAction())[1].performClick()
        compose.waitForIdle()
        inSheet(string(R.string.menu_rename_tab)).performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextReplacement("logs")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.awaitThat { view(m).windows.first { it.id == tab.id }.label == "logs" }
        assertNotAttached()
    }
}
