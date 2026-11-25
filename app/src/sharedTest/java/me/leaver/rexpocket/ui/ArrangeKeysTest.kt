package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.BarKey
import me.leaver.rexpocket.core.DeskKey
import me.leaver.rexpocket.core.KeyArrangement
import me.leaver.rexpocket.core.KeyEvent
import me.leaver.rexpocket.core.KeyModifiers
import me.leaver.rexpocket.core.KeyPreset
import me.leaver.rexpocket.core.Latch
import me.leaver.rexpocket.core.Mod
import me.leaver.rexpocket.data.HostStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings › Terminal controls › Arrange keys as a person uses it: the page the app shows, in its
 * bottom sheet, on the app's view model, with its pages for adding keys and for key groups. After
 * each tap or drag the test reads the list on screen, the key bar beside it (and the panels it
 * opens, and what its keys send), and what the store keeps. Runs on the JVM (Robolectric) and on
 * the emulator.
 */
@RunWith(AndroidJUnit4::class)
class ArrangeKeysTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var model: RexViewModel
    private var page by mutableStateOf<ControlsPage?>(ControlsPage.ArrangeKeys)
    private val sent = mutableListOf<KeyEvent>()
    private val desk = mutableListOf<Pair<DeskKey, KeyModifiers>>()

    private val f10 = BarKey.Desk(DeskKey.F10)
    private val home = BarKey.Desk(DeskKey.Home)

    @Before
    fun start() {
        compose.runOnUiThread { model = RexViewModel(freshApp()) }
        compose.setContent {
            ControlsOnModel(model, page, onPage = { page = it }, onKey = { sent += it }, onDeskKey = { k, m -> desk += k to m })
        }
        compose.waitForIdle()
    }

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    /** A key's name in the lists, as the page writes it. */
    private fun name(key: BarKey) = when (key) {
        BarKey.Paste -> string(R.string.key_paste)
        BarKey.Escape -> string(R.string.key_escape)
        BarKey.CtrlC -> string(R.string.key_ctrl_c)
        BarKey.Attach -> string(R.string.upload_attach)
        BarKey.Arrows -> string(R.string.key_arrows)
        BarKey.Keyboard -> string(R.string.key_keyboard)
        BarKey.Fn -> "Fn"
        home -> string(R.string.key_home)
        is BarKey.Desk -> key.key.name
        else -> error("no name for $key here")
    }

    /** Where [key]'s row is in the list on screen. */
    private fun rowY(key: BarKey): Float =
        compose.onNodeWithTag("arrange-${key.pref}", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y

    private fun rowShown(key: BarKey): Boolean =
        compose.onAllNodes(hasTestTag("arrange-${key.pref}"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** The rows of [keys] on screen, top to bottom. */
    private fun listOnScreen(keys: List<BarKey> = model.keyArrangement.value.keys): List<BarKey> = keys.filter(::rowShown).sortedBy(::rowY)

    private val inBar = hasAnyAncestor(hasTestTag(KEY_PILL_TAG))
    private fun barNode(key: BarKey): SemanticsNodeInteraction = when (key) {
        is BarKey.Group -> compose.onNodeWithTag(groupKeyTag(key.id))
        else -> compose.onNode((hasContentDescription(name(key)) or hasText(name(key))) and inBar, useUnmergedTree = true)
    }
    private fun barX(key: BarKey): Float = barNode(key).fetchSemanticsNode().positionInRoot.x
    private fun inBarCount(text: String) = compose.onAllNodes(hasText(text) and inBar, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun inPanel(id: String, text: String) =
        compose.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(groupPanelTag(id))), useUnmergedTree = true).fetchSemanticsNodes()

    /** What a fresh read of the store says: what the app shows when it starts again. */
    private fun stored(): KeyArrangement = HostStore(compose.activity).keyArrangement()

    /** Closes the page and opens it again from Terminal controls, as a person goes back to it. */
    private fun reopen() {
        page = ControlsPage.Controls
        compose.waitForIdle()
        page = ControlsPage.ArrangeKeys
        compose.waitForIdle()
    }

    /** The sheet put away, so the bar under it is what the person taps, as on the terminal screen. */
    private fun closeSettings() {
        page = null
        compose.waitForIdle()
    }

    private fun tap(description: String) {
        compose.onNodeWithContentDescription(description).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun tapText(text: String) {
        compose.onNode(hasText(text) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun back() = tap(string(R.string.action_back))

    /** The row [row] tapped, and [text] typed as the name its page asks for, then saved. */
    private fun name(row: String, text: String) {
        tapText(row)
        compose.onNode(hasSetTextAction()).performTextReplacement(text)
        compose.waitForIdle()
        tapText(string(R.string.action_save))
    }

    /** A chip of Add keys, for [key]. */
    private fun chip(key: BarKey) = compose.onNodeWithTag("chip-${key.pref}")

    private fun openAddKeys(index: Int = 0) {
        compose.onAllNodes(hasText(string(R.string.arrange_add_keys)) and hasClickAction())[index].performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun openGroup(name: String) {
        // The group's row under Key groups: the key pill's row of its key has the same name but no
        // tap, and the bar's key for it is not in the sheet.
        compose.onNode(hasText(name) and hasClickAction() and !inBar).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun upMovesTheRowOnScreenAndTheKeyInTheBarAndIsKept() {
        assertTrue("Paste starts after Fn", rowY(BarKey.Paste) > rowY(BarKey.Fn))
        assertTrue(barX(BarKey.Paste) > barX(BarKey.Fn))

        tap(string(R.string.arrange_move_up, name(BarKey.Paste)))

        val expected = KeyArrangement().move(BarKey.Paste, -1)
        assertEquals("the list on screen", expected.keys, listOnScreen())
        assertTrue("the bar: Paste now before Fn", barX(BarKey.Paste) < barX(BarKey.Fn))
        assertEquals(expected, model.keyArrangement.value)
        assertEquals(expected, stored())
        reopen()
        assertEquals("the list once opened again", expected.keys, listOnScreen())
    }

    @Test
    fun downMovesTheRowOnScreenTwiceInARow() {
        tap(string(R.string.arrange_move_down, name(BarKey.Escape)))
        tap(string(R.string.arrange_move_down, name(BarKey.Escape)))

        val expected = KeyArrangement().move(BarKey.Escape, 2)
        assertEquals(expected.keys, listOnScreen())
        assertTrue(barX(BarKey.Escape) > barX(BarKey.CtrlC))
        assertEquals(expected, stored())
    }

    @Test
    fun theRightPillsRowsMoveToo() {
        tap(string(R.string.arrange_move_up, name(BarKey.Keyboard)))
        assertTrue(rowY(BarKey.Keyboard) < rowY(BarKey.Arrows))
        assertEquals(listOf(BarKey.Keyboard, BarKey.Arrows, BarKey.FullScreen), stored().rest)
    }

    @Test
    fun removeTakesAKeyOffTheListAndTheBarAndAddKeysBringsItBack() {
        assertEquals(1, inBarCount(name(BarKey.Escape)))

        tap(string(R.string.arrange_remove, name(BarKey.Escape)))
        assertFalse("the row is gone", rowShown(BarKey.Escape))
        assertEquals("and the key", 0, inBarCount(name(BarKey.Escape)))
        assertFalse(stored().has(BarKey.Escape))

        openAddKeys()
        chip(BarKey.Escape).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.arrange_chip_not_added)))
        chip(BarKey.Escape).performScrollTo().performClick()
        compose.waitForIdle()
        chip(BarKey.Escape).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.arrange_chip_added)))
        back()
        assertEquals("back at the end", BarKey.Escape, listOnScreen().last())
        assertEquals(1, inBarCount(name(BarKey.Escape)))
        assertEquals(BarKey.Escape, stored().keys.last())
    }

    /** The user's case: F10 from Fn on the bar, through the page, sending F10 with the row's Ctrl. */
    @Test
    fun f10AddedThroughThePageIsInTheBarAndSendsF10WithTheRowsModifiers() {
        assertEquals(0, inBarCount("F10"))
        openAddKeys()
        chip(f10).performScrollTo().performClick()
        compose.waitForIdle()
        back()
        assertTrue("its row in the list", rowShown(f10))
        assertEquals(f10, listOnScreen().last())
        assertEquals("in the key bar", 1, inBarCount("F10"))
        assertTrue("Fn keeps its F10", f10 in stored().group(BarKey.FN_GROUP)!!.keys)
        assertTrue(stored().has(f10))

        // Moved to the front with its up button, past the ten keys of the bar, as a person would.
        repeat(10) { tap(string(R.string.arrange_move_up, "F10")) }
        assertEquals(f10, listOnScreen().first())
        assertTrue(barX(f10) < barX(BarKey.Escape))

        closeSettings()
        barNode(f10).performClick()
        compose.waitForIdle()
        assertEquals(listOf(DeskKey.F10 to KeyModifiers()), desk)

        // Ctrl tapped on the bar, then F10: the terminal gets C-F10, and Ctrl is used up.
        compose.onNode(hasContentDescription(string(R.string.key_ctrl)) and inBar).performClick()
        compose.waitForIdle()
        barNode(f10).performClick()
        compose.waitForIdle()
        assertEquals(DeskKey.F10, desk.last().first)
        assertEquals(Latch.Once, desk.last().second[Mod.Ctrl])
        barNode(f10).performClick()
        compose.waitForIdle()
        assertEquals(Latch.Off, desk.last().second[Mod.Ctrl])
    }

    @Test
    fun aKeyMovesFromTheFnPanelToTheBar() {
        // Home onto the bar from the pill's Add keys, then off Fn from its own page: a move, in two steps.
        openAddKeys()
        chip(home).performScrollTo().performClick()
        compose.waitForIdle()
        back()
        openGroup("Fn")
        // Home, and `/` as by default, are on the pill too.
        compose.onAllNodes(hasText(string(R.string.arrange_also_on_pill))).assertCountEquals(2)
        tap(string(R.string.arrange_remove, name(home)))
        assertFalse(rowShown(home))
        compose.onAllNodes(hasText(string(R.string.arrange_also_on_pill))).assertCountEquals(1)

        val kept = stored()
        assertTrue(kept.has(home))
        assertFalse(home in kept.group(BarKey.FN_GROUP)!!.keys)

        closeSettings()
        assertEquals(1, inBarCount(name(home)))
        compose.onNodeWithTag(MORE_KEYS_TAG).performClick()
        compose.waitForIdle()
        assertTrue("End still in Fn", inPanel(BarKey.FN_GROUP, string(R.string.key_end)).isNotEmpty())
        assertTrue("Home no longer", inPanel(BarKey.FN_GROUP, name(home)).isEmpty())
    }

    @Test
    fun theFnPanelsKeysAreArrangedOnItsPageAndThePanelFollows() {
        openGroup("Fn")
        tap(string(R.string.arrange_remove, "F1"))
        tap(string(R.string.arrange_move_up, string(R.string.key_end)))
        val expected = KeyArrangement().removeFromGroup("fn", BarKey.Desk(DeskKey.F1)).moveInGroup("fn", BarKey.Desk(DeskKey.End), -1)
        assertEquals(expected.group("fn")!!.keys, listOnScreen(KeyArrangement().group("fn")!!.keys))
        assertEquals(expected, stored())

        closeSettings()
        compose.onNodeWithTag(MORE_KEYS_TAG).performClick()
        compose.waitForIdle()
        assertTrue(inPanel("fn", "F1").isEmpty())
        val end = inPanel("fn", string(R.string.key_end)).single().positionInRoot.x
        val start = inPanel("fn", name(home)).single().positionInRoot.x
        assertTrue("End now before Home", end < start)
    }

    @Test
    fun aGroupFromAPresetGetsItsKeyOnTheBarAndItsPanelOpens() {
        tapText(string(R.string.arrange_new_group))
        tapText("htop")
        // The new group's page, then back to the list, where it sits after Fn.
        compose.onNodeWithText(string(R.string.arrange_group_panel)).assertExists()
        back()
        assertEquals(listOf(BarKey.Fn, BarKey.Group("htop")), listOnScreen().filterIsInstance<BarKey.Group>())
        assertNotNull(stored().group("htop"))
        assertEquals(KeyPreset.Htop.keys(), stored().group("htop")!!.keys)

        closeSettings()
        assertTrue(barX(BarKey.Group("htop")) > barX(BarKey.Fn))
        compose.onNodeWithTag(groupKeyTag("htop")).performClick()
        compose.waitForIdle()
        assertTrue(inPanel("htop", "F10").isNotEmpty())
        inPanel("htop", "q").single()
        // Its q goes to Rex as the q key; one panel at a time.
        compose.onNode(hasText("q") and hasAnyAncestor(hasTestTag(groupPanelTag("htop"))), useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("key_q", sent.last().code)
        compose.onNodeWithTag(MORE_KEYS_TAG).performClick()
        compose.waitForIdle()
        assertTrue(inPanel("htop", "F10").isEmpty())
        assertTrue(inPanel("fn", "F10").isNotEmpty())
    }

    @Test
    fun anEmptyGroupHasNoKeyUntilItHasKeys() {
        tapText(string(R.string.arrange_new_group))
        name(string(R.string.arrange_empty_group), "Git")
        compose.onNodeWithText(string(R.string.arrange_group_empty)).assertExists()
        assertEquals("Git", stored().group("c1")!!.name)
        compose.onAllNodes(hasTestTag(groupKeyTag("c1"))).assertCountEquals(0)

        openAddKeys()
        chip(BarKey.Chord('z')).performScrollTo().performClick()
        compose.waitForIdle()
        back()
        compose.onNodeWithTag("arrange-ctrl_z", useUnmergedTree = true).assertExists()
        closeSettings()
        compose.onAllNodes(hasTestTag(groupKeyTag("c1"))).assertCountEquals(1)
        compose.onNodeWithTag(groupKeyTag("c1")).performClick()
        compose.waitForIdle()
        compose.onNode(hasText("C-z") and hasAnyAncestor(hasTestTag(groupPanelTag("c1"))), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals("key_z", sent.last().code)
        assertEquals("ctrl", sent.last().mods)
    }

    @Test
    fun aGroupIsRenamedTakenOffTheBarAndDeleted() {
        compose.runOnUiThread { model.setKeyArrangement(KeyArrangement().addPreset(KeyPreset.Vim)) }
        compose.waitForIdle()
        openGroup("vim")
        name(string(R.string.arrange_rename), "x")
        assertEquals("x", stored().group("vim")!!.name)

        tapText(string(R.string.arrange_on_bar))
        assertFalse(stored().has(BarKey.Group("vim")))
        compose.onAllNodes(hasTestTag(groupKeyTag("vim"))).assertCountEquals(0)
        tapText(string(R.string.arrange_on_bar))
        compose.onAllNodes(hasTestTag(groupKeyTag("vim"))).assertCountEquals(1)

        tapText(string(R.string.arrange_delete))
        compose.onNodeWithText(string(R.string.action_delete)).performClick()
        compose.waitForIdle()
        assertNull(stored().group("vim"))
        compose.onAllNodes(hasTestTag(groupKeyTag("vim"))).assertCountEquals(0)
        compose.onNodeWithText(string(R.string.arrange_group_groups)).assertExists()
    }

    @Test
    fun theKeyboardKeySaysWhyItCannotBeRemoved() {
        val why = string(R.string.arrange_always)
        compose.onNodeWithText(why).performScrollTo().assertIsDisplayed()
        val lock = compose.onNodeWithContentDescription(string(R.string.arrange_cannot_remove, name(BarKey.Keyboard), why))
        lock.assertExists()
        compose.onAllNodes(hasContentDescription(string(R.string.arrange_remove, name(BarKey.Keyboard)))).assertCountEquals(0)
        lock.performScrollTo()
        // The tooltip goes again after a moment: the clock is held to see it.
        compose.mainClock.autoAdvance = false
        lock.performClick()
        compose.mainClock.advanceTimeBy(300)
        // The row's line, and now the tooltip.
        compose.onAllNodes(hasText(why)).assertCountEquals(2)
        compose.mainClock.autoAdvance = true
        assertTrue(stored().has(BarKey.Keyboard))
    }

    @Test
    fun newGroupAtTheLimitSaysWhy() {
        compose.runOnUiThread {
            model.setKeyArrangement((1 until KeyArrangement.MAX_GROUPS).fold(KeyArrangement()) { a, i -> a.addGroup("g$i") })
        }
        compose.waitForIdle()
        val reason = string(R.string.arrange_new_group_limit, KeyArrangement.MAX_GROUPS)
        compose.onNodeWithText(reason, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(string(R.string.arrange_new_group)) and hasText(reason)).assertIsNotEnabled()
        compose.onAllNodes(hasText(string(R.string.arrange_new_group)) and hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun resetPutsTheListAndTheBarBack() {
        tap(string(R.string.arrange_move_up, name(BarKey.Paste)))
        tap(string(R.string.arrange_remove, name(BarKey.Escape)))
        compose.onNodeWithText(string(R.string.arrange_reset)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(KeyArrangement().keys, listOnScreen())
        assertEquals(1, inBarCount(name(BarKey.Escape)))
        assertEquals(KeyArrangement(), stored())
    }

    @Test
    fun resetAsksFirstWhenGroupsWouldGo() {
        compose.runOnUiThread { model.setKeyArrangement(KeyArrangement().addPreset(KeyPreset.Tmux)) }
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.arrange_reset)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.arrange_reset_title)).assertExists()
        assertNotNull("nothing yet", stored().group("tmux"))
        compose.onNodeWithText(string(R.string.arrange_reset_confirm)).performClick()
        compose.waitForIdle()
        assertEquals(KeyArrangement(), stored())
    }

    /** A finger on [key]'s handle, moved [rows] places (up when negative) in small steps, then lifted. */
    private fun drag(key: BarKey, rows: Float) {
        val handle = compose.onNodeWithTag("arrange-handle-${key.pref}", useUnmergedTree = true)
        handle.performScrollTo()
        compose.waitForIdle()
        val place = compose.density.density * (56 + 1)
        handle.performTouchInput {
            down(center)
            val steps = 24
            repeat(steps) { moveBy(Offset(0f, rows * place / steps)) }
            up()
        }
        compose.waitForIdle()
    }

    @Test
    fun draggingTheHandleMovesTheRow() {
        // Attach, two places up, past Paste and Fn; the slop the drag starts with is under a third of a row.
        drag(BarKey.Attach, -2.3f)
        val expected = KeyArrangement().move(BarKey.Attach, -2)
        assertEquals("the list on screen", expected.keys, listOnScreen())
        assertTrue(barX(BarKey.Attach) < barX(BarKey.Fn))
        assertEquals(expected, stored())
    }

    @Test
    fun draggingDownMovesTheRowDown() {
        drag(BarKey.Escape, 1.3f)
        val expected = KeyArrangement().move(BarKey.Escape, 1)
        assertEquals(expected.keys, listOnScreen())
        assertEquals(expected, stored())
    }

    @Test
    fun draggingAKeyInTheFnPanelsListMovesItInThePanel() {
        openGroup("Fn")
        drag(BarKey.Desk(DeskKey.PageUp), -2.3f)
        val expected = KeyArrangement().moveInGroup("fn", BarKey.Desk(DeskKey.PageUp), -2)
        assertEquals(expected.group("fn")!!.keys, listOnScreen(KeyArrangement().group("fn")!!.keys))
        assertEquals(expected, stored())
    }
}
