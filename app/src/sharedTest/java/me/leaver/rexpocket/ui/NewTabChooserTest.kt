package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * Launch templates where tabs are made, so a person finds them (they were only "New tab from
 * template", in the host's and the session's sheets): every New tab offers Blank tab, the host's
 * templates and Add template…, on the home and in the terminal's ⋮ menu; the palette lists each
 * template; and with none yet, the sheet says what one is, with an example. Whole app on
 * ScriptedRex, JVM and emulator.
 */
@RunWith(AndroidJUnit4::class)
class NewTabChooserTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 1)
    private var model: RexViewModel? = null

    private val sid get() = desk.ids[0]

    private val api = LaunchTemplate("t1", "api", "~/api", "claude")

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun start(onTerminal: Boolean = false, templates: List<LaunchTemplate> = listOf(api)): RexViewModel {
        val app = freshApp()
        desk.saveFor(app, lastSession = sid)
        val m = launchedModel(compose, app, killedOnTerminal = onTerminal)
        model = m
        compose.runOnUiThread { m.setLaunchTemplates(desk.host.id, templates) }
        compose.setContent { RexPocketApp(m) }
        compose.awaitLive(m, sid)
        return m
    }

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnect() } }
        desk.close()
    }

    private val inASheet = hasAnyAncestor(hasTestTag(ACTION_SHEET_TAG))

    private fun inSheet(text: String) = compose.onNode(hasText(text) and hasClickAction() and inASheet)

    private fun sheetHas(text: String, substring: Boolean = false) =
        compose.onAllNodes(hasText(text, substring = substring) and inASheet).fetchSemanticsNodes().isNotEmpty()

    private fun assertOffersTheChoices() {
        assertTrue("no Blank tab", sheetHas(string(R.string.new_tab_blank)))
        assertTrue("no template", sheetHas("api"))
        assertTrue("no Add template", sheetHas(string(R.string.template_add)))
    }

    @Test
    fun theSessionSheetsNewTabOffersBlankTheTemplatesAndAdd() {
        val m = start()
        val label = m.pocket.value.sessions.first { it.id == sid }.label
        compose.onNode(hasContentDescription(string(R.string.manage_actions_for, label)) and hasClickAction() and !inASheet).performClick()
        compose.waitForIdle()
        inSheet(string(R.string.menu_new_tab)).performClick()
        compose.waitForIdle()
        assertOffersTheChoices()
        val before = desk.tabs(sid).size
        inSheet("api").performScrollTo().performClick()
        compose.awaitThat { m.route.value == Route.Term && desk.tabs(sid).size == before + 1 }
        assertEquals("api", m.pocket.value.view!!.activeWindow()!!.label)
    }

    @Test
    fun thePlusNewTabOffersThemTooAndBlankMakesAShell() {
        val m = start()
        compose.onNode(hasText(string(R.string.menu_new_tab)) and hasClickAction() and !inASheet).performScrollTo().performClick()
        compose.waitForIdle()
        assertOffersTheChoices()
        val before = desk.tabs(sid)
        inSheet(string(R.string.new_tab_blank)).performClick()
        compose.awaitThat { m.route.value == Route.Term && desk.tabs(sid).size == before.size + 1 }
        assertEquals("a blank tab is a shell", "com.superlogical.terminal.shell", desk.rex.layouts.last().optString("flavor"))
    }

    /** With no template yet the sheet says what one is, with an example, beside Add template…. */
    @Test
    fun noTemplatesYetSaysWhatOneIsWithAnExample() {
        start(templates = emptyList())
        compose.onNode(hasText(string(R.string.menu_new_tab)) and hasClickAction() and !inASheet).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(sheetHas(string(R.string.new_tab_blank)))
        assertTrue(sheetHas(string(R.string.template_add)))
        assertTrue("no example", sheetHas("cd ~/proj && claude", substring = true))
    }

    @Test
    fun theTerminalsMenuOffersThemAndThePaletteListsEachTemplate() {
        val m = start(onTerminal = true)
        compose.awaitThat { m.route.value == Route.Term && m.pocket.value.view?.focusedBlock() != null }
        compose.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        compose.waitForIdle()
        inSheet(string(R.string.menu_new_tab)).performScrollTo().performClick()
        compose.waitForIdle()
        assertOffersTheChoices()
        var before = desk.tabs(sid).size
        inSheet("api").performScrollTo().performClick()
        compose.awaitThat { desk.tabs(sid).size == before + 1 && m.pocket.value.view?.activeWindow()?.label == "api" }

        before = desk.tabs(sid).size
        compose.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.palette_title)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(PALETTE_FIELD_TAG).performTextInput("api")
        compose.waitForIdle()
        val entry = string(R.string.palette_new_tab_template, "api")
        compose.onAllNodes(hasText(entry) and hasClickAction() and !hasTestTag(PALETTE_FIELD_TAG))[0].performClick()
        compose.awaitThat { desk.tabs(sid).size == before + 1 }
    }
}
