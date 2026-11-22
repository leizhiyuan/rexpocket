package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.BarKey
import me.leaver.rexpocket.core.KeyArrangement
import me.leaver.rexpocket.data.HostStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings › Terminal controls › Arrange keys as a person uses it: the page the app shows, in its
 * bottom sheet, on the app's view model. After each tap or drag the test reads the list on screen,
 * the key bar beside it, and what the store keeps, and opens the page again to see it there.
 * Runs on the JVM (Robolectric) and on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class ArrangeKeysTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var model: RexViewModel
    private var page by mutableStateOf<ControlsPage?>(ControlsPage.ArrangeKeys)

    @Before
    fun start() {
        compose.runOnUiThread { model = RexViewModel(freshApp()) }
        compose.setContent { ControlsOnModel(model, page, onPage = { page = it }) }
        compose.waitForIdle()
    }

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun name(key: BarKey) = when (key) {
        BarKey.Paste -> string(R.string.key_paste)
        BarKey.More -> string(R.string.key_more)
        BarKey.Escape -> string(R.string.key_escape)
        BarKey.CtrlC -> string(R.string.key_ctrl_c)
        BarKey.Attach -> string(R.string.upload_attach)
        BarKey.Arrows -> string(R.string.key_arrows)
        BarKey.Keyboard -> string(R.string.key_keyboard)
        else -> error("no name for $key here")
    }

    /** Where [key]'s row is in the list on screen. */
    private fun rowY(key: BarKey): Float =
        compose.onNodeWithTag("arrange-${key.pref}", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y

    /** The keys of the wide pill, top to bottom, as the list on screen shows them. */
    private fun listOnScreen(): List<BarKey> = BarKey.of(me.leaver.rexpocket.core.Pill.Keys).sortedBy { rowY(it) }

    private val inBar = hasAnyAncestor(hasTestTag(KEY_PILL_TAG))
    private fun barX(key: BarKey): Float =
        compose.onNode((hasContentDescription(name(key)) or hasText(name(key))) and inBar, useUnmergedTree = true)
            .fetchSemanticsNode().positionInRoot.x

    /** What a fresh read of the store says: what the app shows when it starts again. */
    private fun stored(): KeyArrangement = HostStore(compose.activity).keyArrangement()

    /** Closes the page and opens it again from Terminal controls, as a person goes back to it. */
    private fun reopen() {
        page = ControlsPage.Controls
        compose.waitForIdle()
        page = ControlsPage.ArrangeKeys
        compose.waitForIdle()
    }

    private fun tap(description: String) {
        compose.onNodeWithContentDescription(description).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun upMovesTheRowOnScreenAndTheKeyInTheBarAndIsKept() {
        assertTrue("Paste starts after Fn", rowY(BarKey.Paste) > rowY(BarKey.More))
        assertTrue(barX(BarKey.Paste) > barX(BarKey.More))

        tap(string(R.string.arrange_move_up, name(BarKey.Paste)))

        val expected = KeyArrangement().move(BarKey.Paste, -1)
        assertEquals("the list on screen", expected.keys, listOnScreen())
        assertTrue("the bar: Paste now before Fn", barX(BarKey.Paste) < barX(BarKey.More))
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
    fun theEyeHidesAndShowsOnScreenInTheBarAndInTheStore() {
        val escapeInBar = hasText(name(BarKey.Escape)) and inBar
        compose.onAllNodes(escapeInBar, useUnmergedTree = true).assertCountEquals(1)

        tap(string(R.string.arrange_hide, name(BarKey.Escape)))
        // The eye now offers to show it again, and says it is hidden.
        compose.onNodeWithContentDescription(string(R.string.arrange_show, name(BarKey.Escape)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.arrange_hidden)))
        compose.onAllNodes(escapeInBar, useUnmergedTree = true).assertCountEquals(0)
        assertTrue(BarKey.Escape in stored().hidden)
        reopen()
        compose.onNodeWithContentDescription(string(R.string.arrange_show, name(BarKey.Escape))).assertExists()

        tap(string(R.string.arrange_show, name(BarKey.Escape)))
        compose.onNodeWithContentDescription(string(R.string.arrange_hide, name(BarKey.Escape))).assertExists()
        compose.onAllNodes(escapeInBar, useUnmergedTree = true).assertCountEquals(1)
        assertTrue(BarKey.Escape !in stored().hidden)
    }

    @Test
    fun resetPutsTheListAndTheBarBack() {
        tap(string(R.string.arrange_move_up, name(BarKey.Paste)))
        tap(string(R.string.arrange_hide, name(BarKey.Escape)))
        compose.onNodeWithText(string(R.string.arrange_reset)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(KeyArrangement().keys, listOnScreen())
        compose.onAllNodes(hasText(name(BarKey.Escape)) and inBar, useUnmergedTree = true).assertCountEquals(1)
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
        assertTrue(barX(BarKey.Attach) < barX(BarKey.More))
        assertEquals(expected, stored())
    }

    @Test
    fun draggingDownMovesTheRowDown() {
        drag(BarKey.Escape, 1.3f)
        val expected = KeyArrangement().move(BarKey.Escape, 1)
        assertEquals(expected.keys, listOnScreen())
        assertEquals(expected, stored())
    }
}
