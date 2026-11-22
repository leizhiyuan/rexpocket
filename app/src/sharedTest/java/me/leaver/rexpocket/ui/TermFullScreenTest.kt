package me.leaver.rexpocket.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Full screen lays the screen out at once, in both directions: the pane takes the whole window as
 * soon as full screen is on, and the key bar is back above the navigation bar as soon as it is off,
 * whether or not the phone has told the app about its bars since.
 *
 * The bug: entering full screen left a band at the bottom (and some at the top) until the keyboard
 * was opened and closed. The screen padded itself by the bars' insets as Compose last heard them,
 * and Compose ignores the insets Android sends while a bar animation is being prepared, then takes
 * them at its end only if it had a duration; with animations off, or a bar that hides without one,
 * the old insets stayed until the next change, which the keyboard made. On the emulator this runs
 * with the real bars (edge to edge, animations off); on the JVM, where nothing sends insets, the
 * test sends a phone's and then nothing more, the case of the update that never came.
 */
@RunWith(AndroidJUnit4::class)
class TermFullScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = compose.activity.getString(id)

    private val content: View get() = compose.activity.findViewById<ViewGroup>(android.R.id.content)

    /** The navigation bar's height, shown or not. */
    private var navPx = 0
    private var statusPx = 0

    private fun show() {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        val model = termModel()
        compose.setContent { TermOnModel(model, liveState()) }
        compose.waitForIdle()
        if (onRobolectric) {
            statusPx = 72
            navPx = 144
            compose.runOnUiThread { sendBars(content, shown = true, statusPx = statusPx, navPx = navPx) }
            compose.waitForIdle()
        } else {
            val insets = ViewCompat.getRootWindowInsets(content)!!
            statusPx = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top
            navPx = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars()).bottom
        }
    }

    private fun enterFromMenu() {
        compose.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.action_full_screen)).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription(string(R.string.action_exit_full_screen)).assertCountEquals(1)
    }

    private fun assertNear(message: String, expected: Float, actual: Float) =
        assertTrue("$message: expected $expected, was $actual", abs(expected - actual) <= 1f)

    @Test
    fun enteringFullScreenGivesThePaneTheWholeWindowAtOnce() {
        show()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("the bars take room before full screen", compose.bounds(PANE_TAG).height < root.height - navPx)

        enterFromMenu()

        // Right after entering, before any keyboard or later insets: no band at either end.
        val pane = compose.bounds(PANE_TAG)
        val seen = describe()
        assertNear("pane top ($seen)", root.top, pane.top)
        assertNear("pane bottom ($seen)", root.bottom, pane.bottom)
    }

    /** What the screen and the window say, for a failure on a device to explain itself. */
    private fun describe(): String {
        val insets = ViewCompat.getRootWindowInsets(content)
        val types = mapOf(
            "status" to WindowInsetsCompat.Type.statusBars(),
            "nav" to WindowInsetsCompat.Type.navigationBars(),
            "ime" to WindowInsetsCompat.Type.ime(),
            "cutout" to WindowInsetsCompat.Type.displayCutout(),
        )
        val bars = types.entries.joinToString { (name, type) ->
            "$name=${insets?.getInsets(type)} visible=${insets?.isVisible(type)}"
        }
        val docks = compose.onAllNodes(androidx.compose.ui.test.hasTestTag(DOCK_TAG)).fetchSemanticsNodes().map { it.boundsInRoot }
        return "pane=${compose.bounds(PANE_TAG)} dock=$docks $bars"
    }

    @Test
    fun leavingFullScreenPutsTheKeyBarBackAboveTheNavigationBarAtOnce() {
        show()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val dockBefore = compose.bounds(DOCK_TAG)
        assertNear("the key bar sits on the navigation bar", root.bottom - navPx, dockBefore.bottom)

        enterFromMenu()
        // The phone says its bars went, and then says nothing when they come back.
        if (onRobolectric) {
            compose.runOnUiThread { sendBars(content, shown = false, statusPx = statusPx, navPx = navPx) }
            compose.waitForIdle()
        }
        compose.onNodeWithContentDescription(string(R.string.action_exit_full_screen)).performClick()
        compose.waitForIdle()

        assertEquals("the key bar is back", 1, compose.onAllNodesWithContentDescription(string(R.string.key_keyboard)).fetchSemanticsNodes().size)
        assertNear("the key bar sits on the navigation bar again", root.bottom - navPx, compose.bounds(DOCK_TAG).bottom)
        assertNear("the pane starts under the status bar and the top bar", dockBefore.top, compose.bounds(DOCK_TAG).top)
        assertTrue("the pane is under the status bar", compose.bounds(PANE_TAG).top >= root.top + statusPx)
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** Back in full screen brings the bars back and stays on the terminal; the next Back leaves it. */
    @Test
    fun backLeavesFullScreenFirstAndThenTheTerminal() {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        val model = termModel()
        compose.setContent { TermOnModel(model, liveState()) }
        compose.waitForIdle()
        enterFromMenu()
        compose.onAllNodesWithContentDescription(string(R.string.action_more)).assertCountEquals(0)

        back()
        assertEquals("the first Back stays on the terminal", Route.Term, model.route.value)
        compose.onAllNodesWithContentDescription(string(R.string.action_exit_full_screen)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(string(R.string.action_more)).assertCountEquals(1)
        compose.onAllNodesWithContentDescription(string(R.string.key_keyboard)).assertCountEquals(1)
        compose.onAllNodesWithTag(PANE_TAG).assertCountEquals(1)

        back()
        assertEquals("the second Back leaves the terminal", Route.Home, model.route.value)
        compose.onAllNodesWithTag(PANE_TAG).assertCountEquals(0)
    }
}
