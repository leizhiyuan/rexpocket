package me.leaver.rexpocket.ui

import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.BlockInfo
import me.leaver.rexpocket.core.ClosedPane
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.SessionView
import me.leaver.rexpocket.core.WindowInfo
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A pane closed elsewhere, on a device: the terminal screen says so in the pane's place, with no
 * key bar to type into a pane nobody chose, and offers the tab that is left and the way back.
 */
@RunWith(AndroidJUnit4::class)
class ClosedPaneTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var acts = 0
    private var backs = 0

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun show(closed: ClosedPane?, windows: List<WindowInfo> = listOf(BUILD)) {
        val view = SessionView(revision = 2, sessionId = SESSION, label = "work", activeWindowId = windows.firstOrNull()?.id, windows = windows)
        compose.setContent {
            RexTheme {
                TermScreen(
                    state = PocketState(phase = Phase.Live, activeSessionId = SESSION, view = view, closed = closed),
                    fontScale = 1f,
                    terminalTypeface = Typeface.MONOSPACE,
                    onBack = { backs++ },
                    onOpenSession = {},
                    watchPane = { emptyFlow() },
                    onRetry = {},
                    onDismissNotice = {},
                    onAct = { acts++ },
                    upload = null,
                    onAttach = {},
                    onCancelUpload = {},
                    onDismissUpload = {},
                    onRetryUpload = {},
                    keyBarWithKeyboard = true,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aClosedTabIsANoteNotAnError() {
        show(ClosedPane(SESSION, "block:gone", tabClosed = true))
        compose.onAllNodesWithText(string(R.string.closed_tab_title)).assertCountEquals(1)
        compose.onAllNodesWithText(string(R.string.pane_no_picture)).assertCountEquals(0)
        compose.onAllNodesWithText(string(R.string.pane_reading)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(string(R.string.key_keyboard)).assertCountEquals(0)

        compose.onNodeWithText(string(R.string.closed_show, "build")).performClick()
        compose.waitForIdle()
        assertEquals("showing the tab that is left goes through the session", 1, acts)

        compose.onNodeWithText(string(R.string.closed_sessions)).performClick()
        compose.waitForIdle()
        assertEquals(1, backs)
    }

    @Test
    fun aSessionWithNoTabLeftOffersANewOne() {
        show(ClosedPane(SESSION, "block:gone", tabClosed = true), windows = emptyList())
        compose.onNodeWithText(string(R.string.menu_new_tab)).performClick()
        compose.waitForIdle()
        assertEquals(1, acts)
    }

    @Test
    fun aPaneThatIsOpenShowsTheTerminal() {
        show(closed = null)
        compose.onAllNodesWithText(string(R.string.closed_tab_title)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(string(R.string.key_keyboard)).assertCountEquals(1)
    }

    private companion object {
        const val SESSION = "session:test"
        val BUILD = WindowInfo(
            id = "window:build",
            label = "build",
            focusedBlockId = "block:left",
            zoomedBlockId = null,
            blocks = listOf(BlockInfo("block:left", "left", null, null, null)),
        )
    }
}
