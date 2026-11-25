package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.LaunchTemplate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every New tab on the home ends on the terminal showing the tab it made, not a tab the session
 * already had: the "+ New tab" under a session's tabs, the session sheet's New tab, each with its
 * Blank tab, and a template run from New tab and from the host's sheet. A phone found the sheet's New tab leaving the person
 * on the home, where opening the session then showed its old tab. The whole app on ScriptedRex.
 */
@RunWith(AndroidJUnit4::class)
class HomeNewTabTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 2)
    private var model: RexViewModel? = null

    private val shown get() = desk.ids[0]
    private val other get() = desk.ids[1]

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun start(onTerminal: Boolean = false): RexViewModel {
        val app = freshApp()
        desk.saveFor(app, lastSession = shown)
        val m = launchedModel(compose, app, killedOnTerminal = onTerminal)
        model = m
        compose.setContent { RexPocketApp(m) }
        compose.awaitLive(m, shown)
        compose.awaitThat { m.pocket.value.views.containsKey(other) }
        return m
    }

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnect() } }
        desk.close()
    }

    private val inASheet = hasAnyAncestor(hasTestTag(ACTION_SHEET_TAG))

    private fun inSheet(text: String) = compose.onNode(hasText(text) and hasClickAction() and inASheet)

    /** New tab's own sheet: Blank tab, the host's templates, Add template…. */
    private fun blank() {
        compose.waitForIdle()
        inSheet(string(R.string.new_tab_blank)).performClick()
    }

    private fun label(m: RexViewModel, session: String) = m.pocket.value.sessions.first { it.id == session }.label

    private fun sessionSheet(m: RexViewModel, session: String) {
        compose.onNode(hasContentDescription(string(R.string.manage_actions_for, label(m, session))) and hasClickAction() and !inASheet).performClick()
        compose.waitForIdle()
    }

    /**
     * Waits for the terminal on [session] and checks the pane it shows is in a tab that was not
     * there before ([before], Rex's tabs of that session when the person tapped).
     */
    private fun assertOnNewTab(m: RexViewModel, session: String, before: List<String>) {
        compose.awaitThat { desk.tabs(session).size == before.size + 1 }
        val made = desk.tabs(session).single { it !in before }
        try {
            compose.awaitThat(5_000) {
                val st = m.pocket.value
                m.route.value == Route.Term && st.activeSessionId == session && st.view?.activeWindowId == made
            }
        } catch (e: AssertionError) {
            val st = m.pocket.value
            throw AssertionError("not on the new tab $made: route=${m.route.value} session=${st.activeSessionId} tab=${st.view?.activeWindowId} (had $before)", e)
        }
        val pane = m.pocket.value.view!!.focusedBlock()!!.id
        assertTrue("the pane shown is not the new tab's", m.pocket.value.view!!.windows.first { it.id == made }.blocks.any { it.id == pane })
    }

    @Test
    fun theSessionSheetsNewTabShowsTheNewTab() {
        val m = start()
        val before = desk.tabs(other)
        sessionSheet(m, other)
        inSheet(string(R.string.menu_new_tab)).performClick()
        blank()
        assertOnNewTab(m, other, before)
    }

    @Test
    fun theSessionSheetsNewTabShowsTheNewTabOfTheSessionLastOpen() {
        val m = start()
        val before = desk.tabs(shown)
        sessionSheet(m, shown)
        inSheet(string(R.string.menu_new_tab)).performClick()
        blank()
        assertOnNewTab(m, shown, before)
    }

    @Test
    fun thePlusNewTabUnderASessionShowsTheNewTab() {
        val m = start()
        for (session in listOf(other, shown)) {
            val before = desk.tabs(session)
            // One "+ New tab" per session, in Rex's order.
            val index = m.pocket.value.sessions.indexOfFirst { it.id == session }
            compose.onAllNodes(hasText(string(R.string.menu_new_tab)) and hasClickAction() and !inASheet)[index].performScrollTo().performClick()
            blank()
            assertOnNewTab(m, session, before)
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.awaitThat { m.route.value == Route.Home }
        }
    }

    @Test
    fun aTemplateFromTheSessionSheetShowsItsNewTab() {
        val m = start()
        compose.runOnUiThread { m.setLaunchTemplates(desk.host.id, listOf(LaunchTemplate("t1", "api", "~/api", "claude"))) }
        val before = desk.tabs(other)
        sessionSheet(m, other)
        inSheet(string(R.string.menu_new_tab)).performScrollTo().performClick()
        compose.waitForIdle()
        inSheet("api").performClick()
        assertOnNewTab(m, other, before)
        assertEquals("api", m.pocket.value.view!!.activeWindow()!!.label)
    }

    @Test
    fun aTemplateFromTheHostSheetShowsItsNewTab() {
        val m = start()
        compose.runOnUiThread { m.setLaunchTemplates(desk.host.id, listOf(LaunchTemplate("t1", "api", "~/api", "claude"))) }
        val before = desk.tabs(shown)
        compose.onNode(hasContentDescription(string(R.string.manage_actions_for, desk.host.label)) and hasClickAction() and !inASheet).performClick()
        compose.waitForIdle()
        inSheet(string(R.string.template_new_tab)).performScrollTo().performClick()
        compose.waitForIdle()
        inSheet("api").performClick()
        assertOnNewTab(m, shown, before)
    }

    /** The terminal's ⋮ menu and its palette: each New tab moves the phone onto the tab it made. */
    @Test
    fun theTerminalsMenuAndPaletteShowTheNewTab() {
        val m = start(onTerminal = true)
        compose.awaitThat { m.route.value == Route.Term && m.pocket.value.view?.focusedBlock() != null }
        var before = desk.tabs(shown)
        compose.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        compose.waitForIdle()
        inSheet(string(R.string.menu_new_tab)).performScrollTo().performClick()
        blank()
        assertOnNewTab(m, shown, before)

        before = desk.tabs(shown)
        compose.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.palette_title)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(PALETTE_FIELD_TAG).performTextInput(string(R.string.menu_new_tab))
        compose.waitForIdle()
        compose.onAllNodes(hasText(string(R.string.menu_new_tab)) and hasClickAction() and !hasTestTag(PALETTE_FIELD_TAG))[0].performClick()
        assertOnNewTab(m, shown, before)
    }
}
