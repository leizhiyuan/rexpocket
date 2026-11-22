package me.leaver.rexpocket.ui

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.flowOf
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.BlockInfo
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.SessionView
import me.leaver.rexpocket.core.TerminalFeed
import me.leaver.rexpocket.core.WindowInfo
import me.leaver.rexpocket.terminal.TerminalSession
import me.leaver.rexpocket.terminal.TerminalView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A key on the bar and Paste go to the program through Rex, not through the pane's terminal, so
 * the pane is told to follow them to the newest output the way typing does: someone who had
 * scrolled back sees what the key did. A real libghostty-vt pane, so on the emulator only.
 */
@RunWith(AndroidJUnit4::class)
class FollowInputTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var acts = 0

    private fun string(id: Int) = compose.activity.getString(id)
    private val inBar = hasAnyAncestor(hasTestTag(KEY_PILL_TAG))

    @Before
    fun show() {
        val view = SessionView(revision = 2, sessionId = SESSION, label = "work", activeWindowId = TAB.id, windows = listOf(TAB))
        val history = (1..300).joinToString("") { "line $it\r\n" }.toByteArray()
        compose.setContent {
            RexTheme {
                TermScreen(
                    state = PocketState(phase = Phase.Live, activeSessionId = SESSION, view = view),
                    fontScale = 1f,
                    terminalTypeface = Typeface.MONOSPACE,
                    onFont = {},
                    onBack = {},
                    onOpenSession = {},
                    watchPane = { flowOf(TerminalFeed.Snapshot(SNAPSHOT), TerminalFeed.Output(history)) },
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
        compose.waitUntil(10_000) { pane()?.frame()?.atBottom == true }
    }

    private fun pane(): TerminalSession? = compose.runOnIdle { find(compose.activity.window.decorView)?.session }

    private fun find(view: View): TerminalView? = when (view) {
        is TerminalView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
        else -> null
    }

    private fun scrollBack(): TerminalSession {
        val session = requireNotNull(pane())
        compose.runOnIdle { session.scroll(-20) }
        assertFalse("the pane was scrolled back", requireNotNull(session.frame()).atBottom)
        return session
    }

    @Test
    fun aKeyOnTheBarBringsThePaneToTheNewestOutput() {
        val session = scrollBack()
        compose.onNode(hasContentDescription(string(R.string.key_tab)) and hasClickAction() and inBar).performClick()
        compose.waitForIdle()
        assertEquals("the key went to Rex", 1, acts)
        assertTrue("the pane follows the key to the bottom", requireNotNull(session.frame()).atBottom)
    }

    @Test
    fun pasteBringsThePaneToTheNewestOutput() {
        compose.runOnIdle { clipboardSet(compose.activity, "echo hi") }
        val session = scrollBack()
        compose.onNode(hasContentDescription(string(R.string.key_paste)) and hasClickAction() and inBar).performClick()
        compose.waitForIdle()
        assertEquals("the paste went to Rex", 1, acts)
        assertTrue("the pane follows the paste to the bottom", requireNotNull(session.frame()).atBottom)
    }

    private companion object {
        const val SESSION = "session:test"
        val TAB = WindowInfo(
            id = "window:main",
            label = "main",
            focusedBlockId = "block:main",
            zoomedBlockId = null,
            blocks = listOf(BlockInfo("block:main", "main", null, null, null)),
        )

        /** The smallest snapshot there is, as ScriptedRex sends: the magic, a version, the end record. */
        val SNAPSHOT = "GHOSTSNP".toByteArray() + byteArrayOf(1, 0) + byteArrayOf(6, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}
