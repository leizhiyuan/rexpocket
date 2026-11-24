package me.leaver.rexpocket.ui

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.AppShortcut
import me.leaver.rexpocket.core.PocketSession
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.SHORTCUT_KEYS
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
 * A keyboard on the desk, on the terminal screen: the screen's own keys (terminal-controls.md, "A
 * desk keyboard") reach it from the pane, the program keeps every other key, the help sheet lists
 * them and runs them, and Find opens, takes its keys and closes. TermScreen on the app's real view
 * model, as TermMenuTest drives it; the keys go to the pane as a keyboard's do.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TermShortcutsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = compose.activity.getString(id)

    @Before
    fun edgeToEdge() {
        compose.runOnUiThread { compose.activity.edgeToEdgeAsTheApp() }
    }

    private val acts = mutableListOf<suspend PocketSession.() -> Unit>()
    private lateinit var model: RexViewModel

    /** A live session with tabs build and logs. */
    private fun twoTabs(): PocketState {
        val build = tabOf(testPane("left"), id = "window:build", label = "build")
        val logs = tabOf(testPane("logs"), id = "window:logs", label = "logs")
        val work = SessionView(2, TEST_SESSION, "work", build.id, listOf(build, logs))
        return liveState(build).copy(view = work, views = mapOf(TEST_SESSION to work))
    }

    private fun show(state: PocketState = twoTabs()) {
        model = termModel()
        compose.setContent { TermOnModel(model, state, onAct = { acts += it }) }
        compose.waitForIdle()
    }

    private fun pane(): TerminalView = find(compose.activity.findViewById(android.R.id.content), TerminalView::class.java)
        ?: throw AssertionError("no terminal view on screen")

    private fun <T : View> find(view: View, type: Class<T>): T? {
        if (type.isInstance(view)) return type.cast(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), type)?.let { return it }
        return null
    }

    /** Presses [code] with [meta] on the pane as a desk keyboard does; true when the pane took it. */
    private fun press(code: Int, meta: Int, repeat: Int = 0): Boolean {
        val event = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, repeat, meta)
        var took = false
        compose.runOnUiThread { took = pane().onKeyDown(code, event) }
        compose.waitForIdle()
        return took
    }

    private val ctrlShift = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
    private val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON

    private fun findOpen() = compose.onAllNodes(hasTestTag(FIND_BAR_TAG)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun ctrlShiftFOpensFindWithItsFieldReadyAndEscapeClosesIt() {
        show()
        assertTrue(press(KeyEvent.KEYCODE_F, ctrlShift))
        compose.onNodeWithTag(FIND_BAR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(FIND_FIELD_TAG).assertIsFocused()
        compose.onNodeWithTag(FIND_FIELD_TAG).performTextInput("error")
        compose.waitForIdle()
        compose.onNodeWithTag(FIND_FIELD_TAG).performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()
        assertFalse(findOpen())
        assertTrue("nothing went to Rex", acts.isEmpty())
    }

    @Test
    fun theMenuAndThePaletteOpenFindToo() {
        show()
        compose.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.find_title)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(FIND_BAR_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.find_close)).performClick()
        compose.waitForIdle()
        assertFalse(findOpen())

        assertTrue(press(KeyEvent.KEYCODE_P, ctrlShift))
        compose.onNodeWithTag(PALETTE_FIELD_TAG).performTextInput("find")
        compose.waitForIdle()
        compose.onNodeWithTag(PALETTE_FIELD_TAG).performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        compose.onNodeWithTag(FIND_BAR_TAG).assertIsDisplayed()
    }

    @Test
    fun theCaseSwitchSaysWhereItIs() {
        show()
        press(KeyEvent.KEYCODE_F, ctrlShift)
        val case = compose.onNodeWithContentDescription(string(R.string.find_case))
        case.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        case.performClick()
        compose.waitForIdle()
        case.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        // With nothing found, there is nowhere to step to.
        compose.onNodeWithContentDescription(string(R.string.find_next)).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
    }

    @Test
    fun theOtherShortcutsStillWorkFromFindsField() {
        show()
        press(KeyEvent.KEYCODE_F, ctrlShift)
        compose.onNodeWithTag(FIND_FIELD_TAG).performKeyInput {
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.T) } }
        }
        compose.waitForIdle()
        assertEquals("Ctrl+Shift+T from the field made a tab", 1, acts.size)
    }

    @Test
    fun tabsComeAndGoFromTheKeyboard() {
        show()
        assertTrue(press(KeyEvent.KEYCODE_T, ctrlShift))
        assertEquals("a new tab", 1, acts.size)
        assertTrue(press(KeyEvent.KEYCODE_TAB, ctrl))
        assertEquals("the next tab", 2, acts.size)
        assertTrue(press(KeyEvent.KEYCODE_LEFT_BRACKET, ctrlShift))
        assertEquals("the previous tab", 3, acts.size)
        // A held key does not open tab after tab.
        assertTrue(press(KeyEvent.KEYCODE_T, ctrlShift, repeat = 1))
        assertEquals(3, acts.size)
        // Closing asks first, as the menu does.
        assertTrue(press(KeyEvent.KEYCODE_W, ctrlShift))
        compose.onNodeWithText(compose.activity.getString(R.string.confirm_close_tab, "build")).assertIsDisplayed()
        assertEquals(3, acts.size)
    }

    @Test
    fun ctrlShiftHGoesHome() {
        show()
        assertTrue(press(KeyEvent.KEYCODE_H, ctrlShift))
        assertEquals(Route.Home, model.route.value)
    }

    @Test
    fun theProgramKeepsItsKeys() {
        show()
        // Ctrl+C, Ctrl+Shift+- (C-_), Alt+F, Ctrl+PgUp, F5: none is the screen's. With no terminal
        // to type into here the pane takes nothing; what reaches the program is TerminalSessionTest's.
        for ((code, meta) in listOf(
            KeyEvent.KEYCODE_C to ctrl,
            KeyEvent.KEYCODE_MINUS to ctrlShift,
            KeyEvent.KEYCODE_F to (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON),
            KeyEvent.KEYCODE_PAGE_UP to ctrl,
            KeyEvent.KEYCODE_F5 to 0,
            KeyEvent.KEYCODE_ESCAPE to 0,
        )) {
            assertFalse("$code $meta", press(code, meta))
        }
        assertFalse(findOpen())
        assertTrue(acts.isEmpty())
        assertTrue(compose.onAllNodes(hasTestTag(ACTION_SHEET_TAG)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theHelpSheetListsTheKeysAndRunsThem() {
        show()
        assertTrue(press(KeyEvent.KEYCODE_SLASH, ctrlShift))
        compose.onNodeWithText(string(R.string.shortcuts_group)).assertIsDisplayed()
        val lines = compose.sheetLines().map { it.text }
        val find = lines.first { it.startsWith(string(R.string.find_title)) }
        assertTrue(find, find.contains("Ctrl+Shift+F"))
        assertTrue(find, find.contains("Meta+F"))
        for (shortcut in AppShortcut.entries - AppShortcut.Help) {
            assertTrue("$shortcut", lines.any { it.startsWith(string(shortcutLabel(shortcut))) })
        }
        compose.onNodeWithText(string(R.string.menu_new_tab)).performClick()
        compose.waitForIdle()
        assertEquals(1, acts.size)
    }

    @Test
    fun theMenuOpensTheHelpSheet() {
        show()
        compose.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.shortcuts_title)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.shortcuts_group)).assertIsDisplayed()
    }

    @Test
    fun androidsHelperGetsEveryKey() {
        val group = terminalShortcutGroup(compose.activity)
        assertEquals(string(R.string.shortcuts_title), group.label.toString())
        assertEquals(SHORTCUT_KEYS.sumOf { it.second.size }, group.items.size)
        val find = group.items.filter { it.label.toString() == string(R.string.find_title) }
        assertEquals(listOf(KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_F), find.map { it.keycode })
        assertEquals(listOf(KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON, KeyEvent.META_META_ON), find.map { it.modifiers })
    }
}
