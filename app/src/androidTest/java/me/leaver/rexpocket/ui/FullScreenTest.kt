package me.leaver.rexpocket.ui

import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.PocketState
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full screen on the terminal screen, on a device: the button and a double tap on the pane hide the
 * top bar and the key bar together, and the corner button, a second double tap or Back bring them back.
 */
@RunWith(AndroidJUnit4::class)
class FullScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var backs = 0
    private var doubleTap by mutableStateOf(true)

    private fun string(id: Int) = compose.activity.getString(id)

    private fun show() {
        compose.setContent {
            RexTheme {
                TermScreen(
                    state = PocketState(),
                    fontScale = 1f,
                    terminalTypeface = Typeface.MONOSPACE,
                    onFont = {},
                    onBack = { backs++ },
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
                    doubleTapFullScreen = doubleTap,
                    // The emulator reports a keyboard on the desk, which would take the key bar away.
                    keyBarWithKeyboard = true,
                )
            }
        }
    }

    private fun assertBars(shown: Boolean) {
        compose.waitForIdle()
        val bars = if (shown) 1 else 0
        // The top bar's menu, the key bar's keyboard key, and the way out of full screen.
        compose.onAllNodesWithContentDescription(string(R.string.action_more)).assertCountEquals(bars)
        compose.onAllNodesWithContentDescription(string(R.string.action_full_screen)).assertCountEquals(bars)
        compose.onAllNodesWithContentDescription(string(R.string.key_keyboard)).assertCountEquals(bars)
        compose.onAllNodesWithContentDescription(string(R.string.action_exit_full_screen)).assertCountEquals(1 - bars)
    }

    /** A double tap in the upper part of the pane, clear of the bars and of the line it waits with. */
    private fun doubleTapPane() {
        compose.onRoot().performTouchInput { doubleClick(androidx.compose.ui.geometry.Offset(centerX, height * 0.35f)) }
    }

    @Test
    fun theButtonHidesBothBarsAndTheCornerButtonBringsThemBack() {
        show()
        assertBars(shown = true)

        compose.onNodeWithContentDescription(string(R.string.action_full_screen)).performClick()
        assertBars(shown = false)

        compose.onNodeWithContentDescription(string(R.string.action_exit_full_screen)).performClick()
        assertBars(shown = true)
    }

    @Test
    fun backLeavesFullScreenBeforeItLeavesTheTerminal() {
        show()
        compose.onNodeWithContentDescription(string(R.string.action_full_screen)).performClick()
        assertBars(shown = false)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertBars(shown = true)
        assertEquals("the first Back only brought the bars back", 0, backs)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, backs)
    }

    @Test
    fun aDoubleTapOnThePaneGoesInAndOutOfFullScreen() {
        show()
        doubleTapPane()
        assertBars(shown = false)

        doubleTapPane()
        assertBars(shown = true)
    }

    @Test
    fun withTheSettingOffADoubleTapLeavesTheBarsAndTheButtonStillWorks() {
        doubleTap = false
        show()
        doubleTapPane()
        assertBars(shown = true)

        compose.onNodeWithContentDescription(string(R.string.action_full_screen)).performClick()
        assertBars(shown = false)
    }
}
