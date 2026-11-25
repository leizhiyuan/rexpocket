package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.LaunchTemplate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The home's flows at their edges, as a person meets them: a session ended at the desk while its
 * template sheet is open, and the composer reached from Reply keeping the host's Return after. The
 * whole app on its view model and store, on ScriptedRex. JVM and emulator, except where a test says.
 */
@RunWith(AndroidJUnit4::class)
class HomeEdgeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 2)
    private var model: RexViewModel? = null

    private val shown get() = desk.ids[0]
    private val other get() = desk.ids[1]

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun start(): RexViewModel {
        val app = freshApp()
        desk.saveFor(app, lastSession = shown)
        val m = launchedModel(compose, app)
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

    private fun sheetHas(text: String) = compose.onAllNodes(hasText(text) and inASheet).fetchSemanticsNodes().isNotEmpty()

    /**
     * The session a template's sheet was opened for ends at the desk before the template is picked.
     * The sheet then says the tab goes to a new session, and that is where it opens: the person
     * lands on the template's tab, not on an error about a session that is gone.
     */
    @Test
    fun aTemplatePickedAfterItsSessionEndedOpensInANewSession() {
        val m = start()
        val api = LaunchTemplate("t1", "api", "~/api", "claude")
        compose.runOnUiThread { m.setLaunchTemplates(desk.host.id, listOf(api)) }
        val label = m.pocket.value.sessions.first { it.id == other }.label
        compose.onNode(hasContentDescription(string(R.string.manage_actions_for, label)) and hasClickAction() and !inASheet).performClick()
        compose.waitForIdle()
        inSheet(string(R.string.template_new_tab)).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(sheetHas(string(R.string.template_in_session, label)))

        desk.end(other)
        compose.awaitThat { m.pocket.value.sessions.none { it.id == other } }
        compose.awaitThat { sheetHas(string(R.string.template_in_new_session, desk.host.label)) }
        val sessionsBefore = m.pocket.value.sessions.map { it.id }.toSet()
        inSheet("api").performClick()
        compose.awaitThat { m.route.value == Route.Term && m.pocket.value.activeSessionId !in sessionsBefore + other }
        val made = m.pocket.value.view!!
        assertEquals(listOf("api"), made.windows.map { it.label })
        assertEquals(
            "cd ~/'api' && claude; exec \"\${SHELL:-sh}\" -l",
            desk.rex.layouts.last().getJSONObject("options").getJSONArray("command").getString(2),
        )
        assertEquals("no problem is shown", null, m.pocket.value.problem)
    }

    private fun replyThenCompose() {
        compose.onNode(hasText(string(R.string.alerts_reply)) and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNode(hasText(string(R.string.home_reply_compose)) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    /**
     * Press Return after it is the person's choice per host. Turned off in the terminal's composer,
     * the composer that Reply's Compose opens on the home has it off too, and its Send pastes the
     * prompt without Return; turned on there, it is on in the terminal's next time. It also leads to
     * Edit quick replies. The emulator only: Reply's dialog has a text field, which never lets
     * Compose idle on Robolectric (HomeComposerTest has the composer itself on the JVM).
     */
    @Test
    fun replysComposerKeepsTheHostsReturnAfter() {
        assumeFalse(onRobolectric)
        val m = start()
        compose.runOnUiThread { m.drafts.setEnterAfter(desk.host.id, false) }
        val block = m.pocket.value.views.getValue(other).windows[0].blocks[0].id
        desk.rex.report(block, "blocked", kind = "question", msg = "What next?", app = "claude")
        compose.awaitThat { m.pocket.value.blockStatus[block]?.state == "blocked" }
        compose.onNodeWithTag(filterChipTag(HomeFilter.NeedsYou)).performClick()
        compose.waitForIdle()
        replyThenCompose()
        compose.onNodeWithTag(COMPOSER_ENTER_TAG).assertExists()
        compose.onNodeWithTag(COMPOSER_EDIT_REPLIES_TAG).assertExists()
        assertTrue(
            "Return after is off for this host, as chosen in the terminal",
            compose.onAllNodes(hasText(string(R.string.composer_enter_after_body))).fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onNodeWithTag(quickReplyChipTag(0)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(COMPOSER_SEND_TAG).performClick()
        val reply = m.quickReplies.value.items[0]
        compose.awaitThat { desk.rex.inputs.isNotEmpty() }
        Thread.sleep(300)
        assertEquals(listOf("paste $block $reply"), desk.rex.inputs.toList())

        // On again from the home's composer: the terminal's has it on too.
        replyThenCompose()
        compose.onNodeWithTag(COMPOSER_ENTER_TAG).performClick()
        compose.waitForIdle()
        assertTrue(m.drafts.enterAfter(desk.host.id))
    }

    /** The labels of the open sheet's items that can act now, top to bottom. */
    private fun enabledSheetItems(): List<String> =
        compose.onAllNodes(hasClickAction() and inASheet).fetchSemanticsNodes()
            .filter { SemanticsProperties.Disabled !in it.config }
            .sortedBy { it.boundsInRoot.top }
            .map { it.config[SemanticsProperties.Text].first().text }

    private fun talkBackOf(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }

    /**
     * A host that is not connected still lists the sessions it had, folded. A session's sheet then
     * offers Expand, as its row's TalkBack actions do, and Expand opens it; the host's sheet and
     * its TalkBack actions agree on Expand or Collapse too, also in a filtered list.
     */
    @Test
    fun aDisconnectedHostsSheetsMatchTheirRowsAndExpandOpensTheSession() {
        val m = start()
        val label = m.pocket.value.sessions.first { it.id == other }.label
        compose.runOnUiThread { m.disconnect(desk.host.id) }
        compose.awaitThat { m.lives.value[desk.host.id] == null }
        compose.waitForIdle()
        // Not connected, the host is folded: opened, its sessions show, each folded.
        compose.onNode(hasContentDescription(string(R.string.manage_actions_for, desk.host.label)) and hasClickAction() and !inASheet).performClick()
        compose.waitForIdle()
        inSheet(string(R.string.home_action_expand)).performScrollTo().performClick()
        compose.waitForIdle()
        val row = compose.onNode(hasText(label) and hasClickAction() and !inASheet)
        val rowActions = talkBackOf(row)
        assertTrue(rowActions.toString(), string(R.string.home_action_expand) in rowActions)
        compose.onNode(hasContentDescription(string(R.string.manage_actions_for, label)) and hasClickAction() and !inASheet).performClick()
        compose.waitForIdle()
        assertEquals(rowActions, enabledSheetItems())
        inSheet(string(R.string.home_action_expand)).performClick()
        compose.waitForIdle()
        compose.awaitThat { talkBackOf(compose.onNode(hasText(label) and hasClickAction() and !inASheet)).contains(string(R.string.home_action_collapse)) }

        // The host folded in the tree, then a filter that lists it: its header's TalkBack actions are its sheet's.
        compose.runOnUiThread { m.connect(desk.host) }
        compose.awaitLive(m, shown)
        val block = m.pocket.value.views.getValue(other).windows[0].blocks[0].id
        desk.rex.report(block, "blocked", kind = "question", msg = "What next?", app = "claude")
        compose.awaitThat { m.pocket.value.blockStatus[block]?.state == "blocked" }
        compose.onNode(hasContentDescription(string(R.string.manage_actions_for, desk.host.label)) and hasClickAction() and !inASheet).performClick()
        compose.waitForIdle()
        inSheet(string(R.string.home_action_collapse)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(filterChipTag(HomeFilter.NeedsYou)).performClick()
        compose.waitForIdle()
        val header = compose.onNode(hasContentDescription(string(R.string.manage_actions_for, desk.host.label)) and hasClickAction() and !inASheet)
        header.performClick()
        compose.waitForIdle()
        val sheet = enabledSheetItems()
        val hostRow = compose.onAllNodes(hasText(desk.host.label) and !inASheet).fetchSemanticsNodes()
            .first { SemanticsActions.CustomActions in it.config }.config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(sheet, hostRow)
    }
}
