package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A session whose every tab was closed at the desk. Rex keeps such a session, with no windows (seen
 * on Rex 0.1.0-preview.22: `session.view` answers with no `windows` at all). Opened on the phone, it
 * says so in calm words with a New tab button - not a blank pane where typing fails with "No
 * terminal in this window" - and shows no key bar, since there is nothing to type into.
 *
 * The whole app on its real view model and store, connected to ScriptedRex. Runs on the JVM
 * (Robolectric) and on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class EmptySessionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 1)
    private var model: RexViewModel? = null

    private fun string(id: Int) = compose.activity.getString(id)

    /** How tall the bottom stack is: the key bar, when there is one. */
    private fun dockHeight(): Float = compose.onNodeWithTag(DOCK_TAG).fetchSemanticsNode().boundsInRoot.height

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnect() } }
        desk.close()
    }

    @Test
    fun aSessionWithNoTabsSaysSoAndOffersANewTab() {
        val session = desk.ids[0]
        desk.closeEveryTab(session)
        val app = freshApp()
        desk.saveFor(app, lastSession = session)
        // The terminal was on screen when Android killed the app: it comes back on this session.
        val m = launchedModel(compose, app, killedOnTerminal = true)
        model = m
        compose.setContent { RexPocketApp(m) }
        compose.awaitThat { m.route.value == Route.Term && m.pocket.value.view?.sessionId == session }
        assertEquals(emptyList<Any>(), m.pocket.value.view!!.windows)

        compose.onNodeWithTag(EMPTY_SESSION_TAG).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.empty_session_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.empty_session_body)).assertIsDisplayed()
        // Nothing to type into, so no keys.
        assertEquals("the key bar is up over a session with no pane", 0f, dockHeight())
        assertNull(m.pocket.value.notice)

        compose.onNode(hasText(string(R.string.menu_new_tab)) and hasClickAction()).performClick()
        compose.awaitThat { m.pocket.value.view?.windows?.size == 1 }
        compose.onNodeWithTag(EMPTY_SESSION_TAG).assertDoesNotExist()
        compose.onNodeWithTag(PANE_TAG).assertIsDisplayed()
        assertNull("the new tab failed: ${m.pocket.value.notice}", m.pocket.value.notice)
    }
}
