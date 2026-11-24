package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.data.HomeFolds
import me.leaver.rexpocket.data.HostStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the person set on the home screen is still there when they come back to it: a folded
 * session after a trip to a terminal, after a rotation and after a restart; the search they typed,
 * and a sheet they had open through a rotation.
 *
 * The whole app on its real view model and store, connected to ScriptedRex. Runs on the JVM
 * (Robolectric) and on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class HomeStateTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 2)
    private var model: RexViewModel? = null

    /** The session Rex attaches at connect, and another; the home list shows the tabs of both. */
    private val shown get() = desk.ids[0]
    private val other get() = desk.ids[1]

    private fun string(id: Int) = compose.activity.getString(id)

    private fun start(restoration: StateRestorationTester? = null): RexViewModel {
        val app = freshApp()
        desk.saveFor(app, lastSession = shown)
        val m = launchedModel(compose, app)
        model = m
        if (restoration != null) restoration.setContent { RexPocketApp(m) } else compose.setContent { RexPocketApp(m) }
        compose.awaitLive(m, shown)
        return m
    }

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnect() } }
        desk.close()
    }

    private fun label(m: RexViewModel, session: String) = m.pocket.value.sessions.first { it.id == session }.label

    private fun header(m: RexViewModel, session: String): SemanticsNodeInteraction =
        compose.onNode(hasText(label(m, session)) and hasClickAction())

    private val folded get() = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.state_folded))

    private fun fold(m: RexViewModel, session: String) {
        header(m, session).performClick()
        compose.waitForIdle()
        header(m, session).assert(folded)
    }

    /**
     * Opens a tab of [other] from the list - the only tabs showing, with [shown] folded or the search
     * on [other] - then goes back the way the person does.
     */
    private fun toTerminalAndBack(m: RexViewModel) {
        // Rex names a new tab "Window" and its id; the list calls it by its place, as the app does.
        compose.onAllNodes(hasText(compose.activity.getString(R.string.tab_number, 1)) and hasClickAction())[0].performClick()
        compose.awaitThat { m.route.value == Route.Term && m.pocket.value.activeSessionId == other }
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.awaitThat { m.route.value == Route.Home }
        compose.waitForIdle()
    }

    @Test
    fun aFoldedSessionIsStillFoldedAfterATabWasOpened() {
        val m = start()
        fold(m, shown)
        toTerminalAndBack(m)
        header(m, shown).assert(folded)
        header(m, other).assert(!folded)
    }

    @Test
    fun aFoldAndTheOpenSheetSurviveARotation() {
        val restoration = StateRestorationTester(compose)
        val m = start(restoration)
        fold(m, shown)
        compose.onNodeWithContentDescription(string(R.string.settings)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.settings)).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.settings)).assertIsDisplayed()
        header(m, shown).assert(folded)
    }

    @Test
    fun foldsAreKeptForTheNextStartAndUnfoldAgain() {
        val m = start()
        fold(m, shown)
        val key = HomeFolds.sessionKey(desk.host.id, shown)
        assertTrue(key in HostStore(compose.activity).folds().sessions)
        assertTrue(key in RexViewModel(compose.activity.application).folds.value.sessions)
        header(m, shown).performClick()
        compose.waitForIdle()
        header(m, shown).assert(!folded)
        assertFalse(key in HostStore(compose.activity).folds().sessions)
    }

    private fun top(m: RexViewModel, session: String) = header(m, session).fetchSemanticsNode().boundsInRoot.top

    /**
     * A fold is the session's, by its id: it stays through a move at the desk (the row moves on
     * screen, folded), a rename, and the phone connecting again after the desk moved it again.
     */
    @Test
    fun aFoldFollowsTheSessionThroughMovesARenameAndAReconnect() {
        val m = start()
        compose.awaitThat { other in m.pocket.value.views }
        fold(m, other)
        val key = HomeFolds.sessionKey(desk.host.id, other)

        desk.moveSession(other, before = shown)
        compose.awaitThat { m.pocket.value.sessions.map { it.id } == listOf(other, shown) }
        header(m, other).assert(folded)
        header(m, shown).assert(!folded)
        assertTrue("the moved session is not above the other on screen", top(m, other) < top(m, shown))

        desk.rename(other, "moved and renamed")
        compose.awaitThat { label(m, other) == "moved and renamed" }
        header(m, other).assert(folded)

        // Away while the desk moves it again: the list is drawn from the one cached in the old
        // order until Rex answers with the new one.
        compose.runOnUiThread { m.disconnect() }
        desk.moveSession(other, before = null)
        desk.rex.hold("session.bootstrap")
        compose.runOnUiThread { m.connect(desk.host) }
        // Its tabs are not known yet, and the row still says it is folded, as it was left: this is
        // what a launch shows, an update's included, until Rex answers.
        compose.awaitThat { desk.rex.calls.any { it.startsWith("session.bootstrap") } && m.pocket.value.phase != Phase.Live }
        header(m, other).assert(folded)
        desk.rex.release("session.bootstrap")
        compose.awaitThat { m.pocket.value.let { it.phase == Phase.Live && it.sessions.map { s -> s.id } == listOf(shown, other) } }
        compose.awaitThat { other in m.pocket.value.views }
        header(m, other).assert(folded)
        assertTrue(top(m, shown) < top(m, other))
        assertTrue(key in HostStore(compose.activity).folds().sessions)
        assertTrue(key in RexViewModel(compose.activity.application).folds.value.sessions)
    }

    /**
     * Each tab says what its program is doing in words, a waiting one with the first line of its
     * question; the session's chip and the host's summary count them; and a pane the tab does not
     * show that failed is named on a line of its own, which opens that pane.
     */
    @Test
    fun theListSaysEachTabsStateAndNamesAHiddenPane() {
        val m = start()
        compose.awaitThat { other in m.pocket.value.views }
        val tabs = m.pocket.value.views.getValue(other).windows
        val hidden = desk.split(other, tabs[1].blocks[0].id)
        compose.awaitThat { m.pocket.value.views.getValue(other).windows[1].blocks.size == 2 }
        desk.rex.report(tabs[0].blocks[0].id, "blocked", kind = "permission", msg = "Allow edit of src/app.ts?\nsecond line", app = "claude")
        desk.rex.report(hidden, "error", msg = "3 tests failed", app = "pi")
        compose.awaitThat { m.pocket.value.blockStatus.size == 2 }
        compose.waitForIdle()
        val res = compose.activity.resources
        val sessions = res.getQuantityString(R.plurals.session_count, 2, 2)
        val summary = compose.activity.getString(R.string.home_count_waiting, 1) + " · " +
            compose.activity.getString(R.string.home_count_failed, 1) + " · " + sessions
        compose.onNodeWithText(summary).assertExists()
        compose.onNodeWithText(string(R.string.state_waiting_approval) + " · " + res.getQuantityString(R.plurals.pane_count, 1, 1)).assertExists()
        compose.onNodeWithText("Allow edit of src/app.ts?").assertExists()
        // The session's chip: its most pressing state, in a word.
        compose.onNodeWithText(string(R.string.activity_waiting)).assertExists()
        val line = compose.activity.getString(R.string.home_pane_line, "pi", string(R.string.state_failed))
        compose.onNode(hasText(line) and hasClickAction()).performClick()
        compose.awaitThat { m.route.value == Route.Term && m.pocket.value.view?.focusedBlock()?.id == hidden }
    }

    /**
     * A session whose tabs Rex has not sent yet folds on a tap like any other, and opens only from
     * its own Open button.
     */
    @Test
    fun aSessionWithoutItsTabsFoldsOnATapAndOpensFromItsButton() {
        desk.rex.hold("session.view")
        val m = start()
        compose.onNodeWithText(string(R.string.home_tabs_load)).assertExists()
        header(m, other).performClick()
        compose.waitForIdle()
        header(m, other).assert(folded)
        assertTrue(HomeFolds.sessionKey(desk.host.id, other) in m.folds.value.sessions)
        compose.assertStaysHome(m, ms = 500)
        header(m, other).performClick()
        compose.waitForIdle()
        header(m, other).assert(!folded)
        compose.onNode(hasText(string(R.string.home_action_open)) and hasClickAction()).performClick()
        desk.rex.release("session.view")
        compose.awaitThat { m.route.value == Route.Term && m.pocket.value.activeSessionId == other }
    }

    /** Collapse all from the home's ⋮, and its Undo, which puts back exactly the folds there were. */
    @Test
    fun collapseAllIsUndoneToTheFoldsBefore() {
        val m = start()
        compose.awaitThat { other in m.pocket.value.views }
        fold(m, shown)
        val before = m.folds.value
        compose.onNodeWithContentDescription(string(R.string.home_menu)).performClick()
        compose.waitForIdle()
        compose.onNode(hasText(string(R.string.home_collapse_all)) and hasClickAction()).performClick()
        compose.awaitThat { desk.host.id in m.folds.value.hosts }
        compose.awaitThat { compose.onAllNodes(hasText(label(m, other))).fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasText(string(R.string.action_undo)) and hasClickAction()).performClick()
        compose.awaitThat { m.folds.value == before }
        header(m, shown).assert(folded)
        header(m, other).assert(!folded)
    }

    @Test
    fun theSearchIsStillThereAfterATabWasOpened() {
        val m = start()
        val query = label(m, other)
        // The search is in the bar at the bottom, always there.
        compose.onNodeWithTag(HOME_SEARCH_TAG).performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput(query)
        compose.waitForIdle()
        toTerminalAndBack(m)
        compose.onNode(hasSetTextAction()).assert(hasText(query))
    }
}
