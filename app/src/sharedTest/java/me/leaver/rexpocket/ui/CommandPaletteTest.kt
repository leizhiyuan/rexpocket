package me.leaver.rexpocket.ui

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.ListedSession
import me.leaver.rexpocket.core.PocketSession
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.SessionView
import me.leaver.rexpocket.terminal.TerminalView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The command palette over the terminal screen, as docs/design/command-palette.md has it: opened
 * from the ⋮ menu and from Ctrl+Shift+P on the pane, typing narrows and orders the rows on screen,
 * the arrows and Return run a row, a tab of another session opens that session on it, and a
 * destructive row asks first. TermScreen on the app's real view model, as TermMenuTest drives it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CommandPaletteTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = compose.activity.getString(id)

    @Before
    fun edgeToEdge() {
        compose.runOnUiThread { compose.activity.edgeToEdgeAsTheApp() }
    }

    private val acts = mutableListOf<suspend PocketSession.() -> Unit>()
    private val openedTabs = mutableListOf<Pair<String, String>>()

    /** A live session "work" with tabs build and logs, and another session "tau" with its tab tau-watch. */
    private fun twoSessions(): PocketState {
        val build = tabOf(testPane("left"), testPane("right"), id = "window:build", label = "build")
        val logs = tabOf(testPane("logs"), id = "window:logs", label = "logs")
        val watch = tabOf(testPane("watch"), id = "window:watch", label = "tau-watch")
        val work = SessionView(2, TEST_SESSION, "work", build.id, listOf(build, logs))
        val tau = SessionView(5, "session:tau", "tau", watch.id, listOf(watch))
        return liveState(build).copy(
            sessions = listOf(ListedSession(TEST_SESSION, "work"), ListedSession("session:tau", "tau")),
            view = work,
            views = mapOf(TEST_SESSION to work, "session:tau" to tau),
        )
    }

    private fun show(state: PocketState = twoSessions()) {
        val model = termModel()
        compose.setContent {
            TermOnModel(model, state, onAct = { acts += it }, onOpenTab = { s, w -> openedTabs += s to w })
        }
        compose.waitForIdle()
    }

    private fun openFromMenu() {
        compose.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.palette_title)).performClick()
        compose.waitForIdle()
    }

    /** The labels of the rows the palette shows, top to bottom. */
    private fun rows(): List<String> = compose.onAllNodes(SemanticsMatcher("a palette row") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("palette-row-") == true })
        .fetchSemanticsNodes()
        .sortedBy { it.positionInRoot.y }
        .map { node -> node.config[SemanticsProperties.Text].first().text }

    private fun selectedRow(): String? = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        .fetchSemanticsNodes().firstOrNull()?.config?.get(SemanticsProperties.Text)?.first()?.text

    private fun type(text: String) {
        compose.onNodeWithTag(PALETTE_FIELD_TAG).performTextInput(text)
        compose.waitForIdle()
    }

    private fun keys(vararg keys: Long) {
        compose.onNodeWithTag(PALETTE_FIELD_TAG).performKeyInput { keys.forEach { pressKey(Key(it)) } }
        compose.waitForIdle()
    }

    private fun paletteOpen() = compose.onAllNodes(hasTestTag(PALETTE_FIELD_TAG)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theMenuOpensItWithTheActionsFirstThenWhereToGo() {
        show()
        openFromMenu()
        compose.onNodeWithTag(PALETTE_FIELD_TAG).assertIsDisplayed()
        val all = rows()
        assertEquals(string(R.string.action_release), all.first())
        // The tabs of the session on screen, then of the other session, then the sessions.
        val places = all.dropWhile { it != "build" }
        assertEquals(listOf("build", "logs", "tau-watch", "work", "tau"), places)
    }

    @Test
    fun typingNarrowsAndOrdersTheRows() {
        show()
        openFromMenu()
        // Rex calls them Split Pane Right and Split Pane Down; the phone's names are found by either.
        type("split")
        assertEquals(listOf(string(R.string.menu_split_right), string(R.string.menu_split_below)), rows())
        type(" bel")
        assertEquals(listOf(string(R.string.menu_split_below)), rows())
    }

    @Test
    fun clearAndResetAreRexsOwnPaneActions() {
        show()
        openFromMenu()
        // Reset is found by Rex's own words for it too, which name clearing.
        type("clear")
        assertEquals(listOf(string(R.string.palette_clear_terminal), string(R.string.palette_reset_terminal)), rows())
        compose.onNodeWithText(string(R.string.palette_reset_terminal)).performClick()
        compose.waitForIdle()
        assertEquals("the row goes to the pane", 1, acts.size)
        assertFalse(paletteOpen())
    }

    @Test
    fun theArrowsAndReturnRunTheSelectedRow() {
        show()
        openFromMenu()
        type("close")
        // End session is Rex's Close Session, so it is found too, after the rows whose name says it.
        assertEquals(
            listOf(string(R.string.menu_close_pane), string(R.string.menu_close_tab), string(R.string.menu_kill_session)),
            rows(),
        )
        assertEquals(string(R.string.menu_close_pane), selectedRow())
        keys(Key.DirectionDown.keyCode)
        assertEquals(string(R.string.menu_close_tab), selectedRow())
        keys(Key.Enter.keyCode)
        assertTrue("the palette closed", !paletteOpen())
        compose.onNodeWithText(compose.activity.getString(R.string.confirm_close_tab, "build")).assertIsDisplayed()
    }

    @Test
    fun escapeClosesIt() {
        show()
        openFromMenu()
        type("tab")
        keys(Key.Escape.keyCode)
        assertTrue(!paletteOpen())
        assertTrue(acts.isEmpty())
    }

    @Test
    fun aTabOfAnotherSessionOpensThatSessionOnIt() {
        show()
        openFromMenu()
        type("tau-w")
        assertEquals(listOf("tau-watch"), rows())
        keys(Key.Enter.keyCode)
        assertEquals(listOf("session:tau" to "window:watch"), openedTabs)
    }

    @Test
    fun aTabOfThisSessionIsShownWithoutLeavingIt() {
        show()
        openFromMenu()
        type("logs")
        keys(Key.Enter.keyCode)
        assertEquals(1, acts.size)
        assertTrue(openedTabs.isEmpty())
    }

    @Test
    fun closingATabAsksFirst() {
        show()
        openFromMenu()
        type("close tab")
        keys(Key.Enter.keyCode)
        compose.onNodeWithText(compose.activity.getString(R.string.confirm_close_tab, "build")).assertIsDisplayed()
        assertTrue("nothing closed before the answer", acts.isEmpty())
    }

    @Test
    fun ctrlShiftPOnThePaneOpensItAndTheProgramNeverSeesIt() {
        show()
        val pane = find(compose.activity.findViewById(android.R.id.content), TerminalView::class.java)
            ?: throw AssertionError("no terminal view on screen")
        val chord = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_P, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        var took = false
        compose.runOnUiThread { took = pane.onKeyDown(KeyEvent.KEYCODE_P, chord) }
        compose.waitForIdle()
        assertTrue("the pane gave the chord to the screen", took)
        compose.onNodeWithTag(PALETTE_FIELD_TAG).assertIsDisplayed()
        // Ctrl+P alone is the program's.
        assertTrue(!opensPalette(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_P, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)))
        assertTrue(opensPalette(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_P, 0, KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON or KeyEvent.META_SHIFT_ON)))
    }

    private fun <T : View> find(view: View, type: Class<T>): T? {
        if (type.isInstance(view)) return type.cast(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), type)?.let { return it }
        return null
    }
}
