package me.leaver.rexpocket.ui

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.BarKey
import me.leaver.rexpocket.core.ClosedPane
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.PocketSession
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.UploadPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The terminal's ⋮ menu as the person reads it, top to bottom: the key bar's phone actions with no
 * caption, then Pane, Tab and Session, each with its destructive row last. Which rows show follows
 * the screen - a pane on screen, who holds it, how many panes the tab has, the keyboard, an upload -
 * and never the key bar: Arrange keys and a desk keyboard leave the menu as it is.
 * See terminal-controls.md, 4.
 */
@RunWith(AndroidJUnit4::class)
class TermMenuTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = compose.activity.getString(id)

    /**
     * Edge to edge, as MainActivity is. Otherwise the window keeps its insets from Compose, which
     * then takes the keyboard for up and the row would read Hide keyboard on a phone with none.
     */
    @Before
    fun edgeToEdge() {
        compose.runOnUiThread { compose.activity.edgeToEdgeAsTheApp() }
    }

    private val acts = mutableListOf<suspend PocketSession.() -> Unit>()

    private fun open(state: PocketState, model: RexViewModel = termModel(), upload: UploadStatus? = null): List<SheetLine> {
        compose.setContent { TermOnModel(model, state, upload = upload, onAct = { acts += it }) }
        compose.waitForIdle()
        openMenu()
        return compose.sheetLines()
    }

    private fun openMenu() {
        compose.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        compose.waitForIdle()
    }

    private fun texts(lines: List<SheetLine>) = lines.map { it.text }

    /**
     * The keyboard row's label for the keyboard as the window has it now: on the JVM it is down
     * unless a test sends its insets; the emulator may have its own up.
     */
    private fun keyboardNow(): Int {
        val content = compose.activity.findViewById<ViewGroup>(android.R.id.content)
        val up = ViewCompat.getRootWindowInsets(content)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        return if (up) R.string.menu_hide_keyboard else R.string.menu_show_keyboard
    }

    /** The rows of a live pane held by the phone, alone in its tab. */
    private fun onePane(holder: Int = R.string.action_release, keyboard: Int = keyboardNow()) = listOf(
        string(R.string.key_paste),
        string(R.string.upload_attach),
        string(keyboard),
        string(R.string.action_full_screen),
        "# " + string(R.string.level_pane),
        string(holder),
        string(R.string.menu_split_right),
        string(R.string.menu_split_below),
        string(R.string.menu_rename_pane),
        "# " + string(R.string.level_tab),
        string(R.string.menu_new_tab),
        string(R.string.menu_rename_tab),
        string(R.string.menu_close_tab),
        "# " + string(R.string.level_session),
        string(R.string.header_sessions),
        string(R.string.menu_rename_session),
        string(R.string.menu_kill_session),
    )

    @Test
    fun aPaneHeldByThePhoneAloneInItsTab() {
        // One pane: closing it would close the tab, which Close tab already says.
        val lines = texts(open(liveState()))
        assertEquals(onePane(), lines)
    }

    @Test
    fun aSplitTabHandedBackOffersTakeOverAndClosePane() {
        val lines = open(liveState(tabOf(testPane("left"), testPane("right")), released = true))
        val expected = onePane(holder = R.string.action_take_over).toMutableList()
        expected.add(expected.indexOf(string(R.string.menu_rename_pane)) + 1, string(R.string.menu_close_pane))
        assertEquals(expected, texts(lines))
    }

    @Test
    fun releaseAndTakeOverDoWhatTheChipDoes() {
        open(liveState())
        compose.onNodeWithText(string(R.string.action_release)).performClick()
        compose.waitForIdle()
        assertEquals("Release went to the session", 1, acts.size)
    }

    @Test
    fun withoutALiveSessionThereIsNoHolderRow() {
        val lines = texts(open(liveState(phase = Phase.Reconnecting)))
        assertEquals(onePane() - string(R.string.action_release), lines)
    }

    @Test
    fun arrangeKeysAndADeskKeyboardLeaveTheMenuAsItIs() {
        // Every phone key taken off the bar but the keyboard key, which cannot be, and the bar
        // taken away by a desk keyboard where there is one.
        val model = termModel(keyBar = false)
        var arrangement = model.keyArrangement.value
        for (key in BarKey.catalogue) arrangement = arrangement.remove(key)
        model.setKeyArrangement(arrangement)
        assertFalse(model.keyArrangement.value.isShown(BarKey.Paste))
        val lines = texts(open(liveState(), model))
        assertEquals(onePane(), lines)
    }

    @Test
    fun sendAFileIsGreyedOutWhileAFileIsOnItsWayAndNothingMoves() {
        val model = termModel()
        var upload by mutableStateOf<UploadStatus?>(null)
        compose.setContent { TermOnModel(model, liveState(), upload = upload) }
        compose.waitForIdle()
        openMenu()
        val idle = compose.sheetLines()

        // A file starts on its way while the menu is open.
        upload = UploadStatus("notes.txt", UploadPhase.Sending, sent = 1, total = 4)
        compose.waitForIdle()
        val sending = compose.sheetLines()
        assertEquals(texts(idle), texts(sending))
        assertEquals(idle.map { it.top }, sending.map { it.top })
        val attach = sending.single { it.text == string(R.string.upload_attach) }
        assertFalse("Send a file waits for the file on its way", attach.enabled)
        assertEquals(listOf(string(R.string.upload_attach)), sending.filter { !it.enabled }.map { it.text })
    }

    @Test
    fun aClosedPaneLeavesOnlyWhatNeedsNoPane() {
        val state = liveState().copy(closed = ClosedPane(TEST_SESSION, "block:gone", tabClosed = false))
        assertEquals(
            listOf(
                string(R.string.action_full_screen),
                "# " + string(R.string.level_pane),
                string(R.string.menu_split_right),
                string(R.string.menu_split_below),
                "# " + string(R.string.level_tab),
                string(R.string.menu_new_tab),
                "# " + string(R.string.level_session),
                string(R.string.header_sessions),
                string(R.string.menu_rename_session),
                string(R.string.menu_kill_session),
            ),
            texts(open(state)),
        )
    }

    /** The keyboard row says what a tap does: hide the keyboard while it is up. */
    @Test
    fun theKeyboardRowFollowsTheKeyboard() {
        // The emulator shows no screen keyboard (it reports one on the desk); on the JVM the
        // keyboard's insets are sent as a phone's would be.
        assumeTrue(onRobolectric)
        val model = termModel()
        compose.setContent { TermOnModel(model, liveState()) }
        compose.waitForIdle()
        val content = compose.activity.findViewById<ViewGroup>(android.R.id.content)
        compose.runOnUiThread { sendBars(content, shown = true, statusPx = 72, navPx = 144, imePx = 800) }
        compose.waitForIdle()
        openMenu()
        assertEquals(onePane(keyboard = R.string.menu_hide_keyboard), texts(compose.sheetLines()))
    }
}
