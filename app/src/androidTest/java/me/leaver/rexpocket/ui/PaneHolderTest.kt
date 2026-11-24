package me.leaver.rexpocket.ui

import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.BlockInfo
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.SessionView
import me.leaver.rexpocket.core.WindowInfo
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The holder chip on a device. A pane that has just come on screen - after its tab closed and
 * another was shown - is being taken by the phone, as every pane is on sight; the chip says so
 * instead of asking for a Take over nothing needs. Only a pane handed back to the desk asks for it.
 */
@RunWith(AndroidJUnit4::class)
class PaneHolderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = compose.activity.getString(id)

    private fun show(owning: Boolean, released: Boolean) {
        val view = SessionView(revision = 2, sessionId = SESSION, label = "work", activeWindowId = TAB.id, windows = listOf(TAB))
        compose.setContent {
            RexTheme {
                TermScreen(
                    state = PocketState(phase = Phase.Live, activeSessionId = SESSION, view = view, owning = owning, released = released),
                    fontScale = 1f,
                    terminalTypeface = Typeface.MONOSPACE,
                    onBack = {},
                    onOpenSession = {},
                    watchPane = { emptyFlow() },
                    onRetry = {},
                    onDismissNotice = {},
                    onAct = {},
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
    fun aPaneBeingTakenIsThePhonesNotATakeOver() {
        show(owning = false, released = false)
        compose.onAllNodesWithText(string(R.string.holder_phone)).assertCountEquals(1)
        compose.onAllNodesWithText(string(R.string.action_take_over)).assertCountEquals(0)
    }

    @Test
    fun aPaneHandedBackAsksForTakeOver() {
        show(owning = false, released = true)
        compose.onAllNodesWithText(string(R.string.action_take_over)).assertCountEquals(1)
        compose.onAllNodesWithText(string(R.string.holder_phone)).assertCountEquals(0)
    }

    private companion object {
        const val SESSION = "session:test"
        val TAB = WindowInfo(
            id = "window:build",
            label = "build",
            focusedBlockId = "block:left",
            zoomedBlockId = null,
            blocks = listOf(BlockInfo("block:left", "left", null, null, null)),
        )
    }
}
