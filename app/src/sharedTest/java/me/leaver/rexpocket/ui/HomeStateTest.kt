package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.data.HomeFolds
import me.leaver.rexpocket.data.HostStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the person set on the home screen is still there when they come back to it: a folded
 * session after a trip to a terminal, after a rotation and after a restart; the search they typed,
 * and a sheet they had open through a rotation.
 *
 * The whole app on its real view model and store, connected to ScriptedRex. Runs on the JVM
 * (Robolectric) and on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class HomeStateTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 2)
    private var model: RexViewModel? = null

    /** The session Rex attaches at connect, and another; the home list shows the tabs of both. */
    private val shown get() = desk.ids[0]
    private val other get() = desk.ids[1]

    private fun string(id: Int) = compose.activity.getString(id)

    private fun start(restoration: StateRestorationTester? = null): RexViewModel {
        val app = freshApp()
        desk.saveFor(app, lastSession = shown)
        val m = launchedModel(compose, app)
        model = m
        if (restoration != null) restoration.setContent { RexPocketApp(m) } else compose.setContent { RexPocketApp(m) }
        compose.awaitLive(m, shown)
        return m
    }

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnect() } }
        desk.close()
    }

    private fun label(m: RexViewModel, session: String) = m.pocket.value.sessions.first { it.id == session }.label

    private fun header(m: RexViewModel, session: String): SemanticsNodeInteraction =
        compose.onNode(hasText(label(m, session)) and hasClickAction())

    private val folded get() = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.state_folded))

    private fun fold(m: RexViewModel, session: String) {
        header(m, session).performClick()
        compose.waitForIdle()
        header(m, session).assert(folded)
    }

    /**
     * Opens a tab of [other] from the list - the only tabs showing, with [shown] folded or the search
     * on [other] - then goes back the way the person does.
     */
    private fun toTerminalAndBack(m: RexViewModel) {
        compose.onAllNodes(hasText("shell") and hasClickAction())[0].performClick()
        compose.awaitThat { m.route.value == Route.Term && m.pocket.value.activeSessionId == other }
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.awaitThat { m.route.value == Route.Home }
        compose.waitForIdle()
    }

    @Test
    fun aFoldedSessionIsStillFoldedAfterATabWasOpened() {
        val m = start()
        fold(m, shown)
        toTerminalAndBack(m)
        header(m, shown).assert(folded)
        header(m, other).assert(!folded)
    }

    @Test
    fun aFoldAndTheOpenSheetSurviveARotation() {
        val restoration = StateRestorationTester(compose)
        val m = start(restoration)
        fold(m, shown)
        compose.onNodeWithContentDescription(string(R.string.settings)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.settings)).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.settings)).assertIsDisplayed()
        header(m, shown).assert(folded)
    }

    @Test
    fun foldsAreKeptForTheNextStartAndUnfoldAgain() {
        val m = start()
        fold(m, shown)
        val key = HomeFolds.sessionKey(desk.host.id, shown)
        assertTrue(key in HostStore(compose.activity).folds().sessions)
        assertTrue(key in RexViewModel(compose.activity.application).folds.value.sessions)
        header(m, shown).performClick()
        compose.waitForIdle()
        header(m, shown).assert(!folded)
        assertFalse(key in HostStore(compose.activity).folds().sessions)
    }

    @Test
    fun theSearchIsStillThereAfterATabWasOpened() {
        val m = start()
        val query = label(m, other)
        compose.onNodeWithContentDescription(string(R.string.action_search)).performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput(query)
        compose.waitForIdle()
        toTerminalAndBack(m)
        compose.onNode(hasSetTextAction()).assert(hasText(query))
    }
}
