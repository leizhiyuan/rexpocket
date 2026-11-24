package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.ClosedPane
import me.leaver.rexpocket.core.PaneExit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The closed note says why the pane went: its program exited, with its code, or someone closed it elsewhere. */
@RunWith(AndroidJUnit4::class)
class ClosedNoteTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(exit: PaneExit?) {
        val tab = tabOf(testPane("left"))
        compose.setContent {
            TermOnModel(termModel(), liveState(tab).copy(closed = ClosedPane(TEST_SESSION, "block:gone", tabClosed = true, exit = exit)))
        }
        compose.waitForIdle()
    }

    @Test
    fun theProgramExitedWithItsCode() {
        show(PaneExit(3, "exit status 3"))
        compose.onNodeWithText(compose.activity.getString(R.string.closed_body_exited, 3)).assertIsDisplayed()
    }

    @Test
    fun aSignalEndedIt() {
        show(PaneExit(-1, "signal: killed"))
        compose.onNodeWithText(compose.activity.getString(R.string.closed_body_ended, "signal: killed")).assertIsDisplayed()
    }

    @Test
    fun closedOnAnotherDevice() {
        show(null)
        compose.onNodeWithText(compose.activity.getString(R.string.closed_body_elsewhere)).assertIsDisplayed()
    }
}
