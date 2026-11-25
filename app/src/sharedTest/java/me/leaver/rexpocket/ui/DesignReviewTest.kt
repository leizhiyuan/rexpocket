package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.ListedSession
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.SecurityPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The design review's fixes to Settings and the sheets (docs/design/design-review.md): each page
 * Settings opens goes back to it; Notifications puts what to be told about first, in Rex's words;
 * a switch row is a switch to TalkBack and a marked row is selected; Security's row names every
 * guard. The whole app on its view model, on ScriptedRex, but for the sheet rows. JVM and emulator.
 */
@RunWith(AndroidJUnit4::class)
class DesignReviewTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 1)
    private var model: RexViewModel? = null

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun start(): RexViewModel {
        val app = freshApp()
        desk.saveFor(app)
        val m = launchedModel(compose, app)
        model = m
        compose.setContent { RexPocketApp(m) }
        compose.waitForIdle()
        return m
    }

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnectAll() } }
        desk.close()
    }

    private val inASheet = hasAnyAncestor(hasTestTag(ACTION_SHEET_TAG))

    private fun row(id: Int) = compose.onNode(hasText(string(id)) and hasClickAction() and inASheet)

    private fun openSettings() {
        compose.onNodeWithContentDescription(string(R.string.settings)).performClick()
        compose.waitForIdle()
    }

    private fun settingsShown() = compose.onAllNodes(hasText(string(R.string.settings_group_connection))).fetchSemanticsNodes().isNotEmpty()

    /** Settings › a page › its back arrow: Settings again, for the next change, rather than the home. */
    @Test
    fun everyPageOpenedFromSettingsGoesBackToIt() {
        start()
        openSettings()
        for (page in listOf(R.string.notifications_title, R.string.appearance_title, R.string.security_title, R.string.quick_replies_title, R.string.diagnostics)) {
            row(page).performScrollTo().performClick()
            compose.waitForIdle()
            assertFalse("${string(page)} opened in Settings' place", settingsShown())
            compose.onNode(hasContentDescription(string(R.string.action_back)) and hasClickAction() and inASheet).performClick()
            compose.waitForIdle()
            assertTrue("Back on ${string(page)} goes to Settings", settingsShown())
        }
    }

    /** What to be told about is under Background alerts, above Android's checks; its words are Rex's. */
    @Test
    fun notificationsPutsWhatToBeToldAboutFirst() {
        start()
        openSettings()
        row(R.string.notifications_title).performScrollTo().performClick()
        compose.waitForIdle()
        fun top(id: Int) = row(id).fetchSemanticsNode().positionInRoot.y
        assertTrue("Tell me when comes before the checks", top(R.string.notifications_waiting) < top(R.string.bg_battery))
        assertTrue(top(R.string.alerts_title) < top(R.string.notifications_waiting))
        assertEquals("A program is blocked on me", string(R.string.notifications_waiting))
        assertEquals("Done", string(R.string.alerts_channel_done))
    }

    /** A switch row is said as a switch and its state, once; a tap turns it and the state follows. */
    @Test
    fun aSwitchRowIsASwitchToTalkBack() {
        val m = start()
        openSettings()
        row(R.string.notifications_title).performScrollTo().performClick()
        compose.waitForIdle()
        val switch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        val done = compose.onNode(hasText(string(R.string.notifications_done)) and hasClickAction() and inASheet)
        done.performScrollTo().assert(switch).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        done.performClick()
        compose.waitForIdle()
        done.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        assertFalse(m.alertPrefs.value.done)
        // A row that opens a page is a button, not a switch.
        compose.onNode(hasText(string(R.string.notifications_android)) and hasClickAction() and inASheet)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    /** The session switcher's current session is selected to TalkBack, not only checked by eye. */
    @Test
    fun theCurrentSessionIsSaidAsSelected() {
        val state = PocketState(sessions = listOf(ListedSession("a", "api"), ListedSession("b", "web")), activeSessionId = "b")
        compose.setContent { RexTheme { SessionSheet(state, onDismiss = {}, onOpen = {}, onCreate = {}) } }
        compose.waitForIdle()
        val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
        compose.onNode(hasText("web") and hasClickAction()).assert(selected)
        compose.onNode(hasText("api") and hasClickAction()).assert(!selected)
    }

    /** Security's row in Settings names each guard that is on: a screenshot block is not hidden by the lock. */
    @Test
    fun securitysRowNamesEveryGuard() {
        fun said(prefs: SecurityPrefs) = securityDetail(prefs).map { string(it) }
        assertEquals(listOf(string(R.string.security_detail_off)), said(SecurityPrefs()))
        assertEquals(listOf(string(R.string.security_detail_screenshots)), said(SecurityPrefs(blockScreenshots = true)))
        assertEquals(
            listOf(string(R.string.security_detail_locked), string(R.string.security_detail_screenshots)),
            said(SecurityPrefs(lock = true, blockScreenshots = true)),
        )
    }
}
