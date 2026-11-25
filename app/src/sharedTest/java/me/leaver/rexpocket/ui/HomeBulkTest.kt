package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.LaunchTemplate
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.data.HomeMarks
import me.leaver.rexpocket.data.SavedHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The home's broad actions and the ways out of a filtered list: Mark all as seen (a host's, and the
 * home's ⋮ for what the list shows) and a done row's Mark as seen, each with Undo; Approve, Deny,
 * Reply and Interrupt in the tab sheet and on a waiting tab in the tree; a tab sheet's way to its
 * host's; Connect all and Disconnect all with Reconnect; what an empty filter says while hosts are
 * not connected; the idle tabs Activity hides; a rename back to Rex's own name; and launch
 * templates. The whole app on its view model and store, on ScriptedRex. JVM and emulator.
 */
@RunWith(AndroidJUnit4::class)
class HomeBulkTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 2)
    private var model: RexViewModel? = null

    private val shown get() = desk.ids[0]
    private val other get() = desk.ids[1]

    private val away = SavedHost(id = "away-host", label = "build-box", host = "127.0.0.1", port = 9, plaintext = true)

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun start(withAway: Boolean = false): RexViewModel {
        val app = freshApp()
        val store = desk.saveFor(app, lastSession = shown)
        if (withAway) store.upsert(away)
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

    private fun tabs(m: RexViewModel, session: String) = m.pocket.value.views.getValue(session).windows

    private val inASheet = hasAnyAncestor(hasTestTag(ACTION_SHEET_TAG))

    private fun inSheet(text: String) = compose.onNode(hasText(text) and hasClickAction() and inASheet)

    private fun sheetHas(text: String) = compose.onAllNodes(hasText(text) and hasClickAction() and inASheet).fetchSemanticsNodes().isNotEmpty()

    private fun button(text: String) = hasText(text) and hasClickAction() and !inASheet

    private fun count(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().size

    private fun homeMenu() {
        compose.onNode(hasContentDescription(string(R.string.home_menu)) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun hostSheet() {
        compose.onNode(hasContentDescription(string(R.string.manage_actions_for, desk.host.label)) and hasClickAction() and !inASheet).performClick()
        compose.waitForIdle()
    }

    private fun chip(filter: HomeFilter) = compose.onNodeWithTag(filterChipTag(filter))

    /** Scrolls the home's list to [matcher]: a small screen shows only some of its rows. */
    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performScrollToNode(matcher)
        compose.waitForIdle()
    }

    private fun needsYou(m: RexViewModel) = me.leaver.rexpocket.core.needsYouSections(m.pocket.value) { block, record ->
        me.leaver.rexpocket.core.markCovers(m.seenMarks.value[HomeMarks.key(desk.host.id, block)], record)
    }.sumOf { it.items.size }

    /** A waiting tab in the tree has Needs you's buttons, and its sheet has them too, with the way to its host's sheet. */
    @Test
    fun aWaitingTabAnswersFromTheTreeAndItsSheetLeadsToItsHost() {
        val m = start()
        val claude = tabs(m, other)[0].blocks[0].id
        desk.rex.report(claude, "blocked", kind = "permission", msg = "Allow Bash: npm test?", app = "claude")
        compose.awaitThat { m.pocket.value.blockStatus[claude]?.state == "blocked" }
        compose.awaitThat { count(button(string(R.string.alerts_approve))) == 1 }
        assertEquals(1, count(button(string(R.string.alerts_deny))))
        assertEquals(1, count(button(string(R.string.alerts_reply))))
        assertEquals(1, count(button(string(R.string.alerts_interrupt))))
        compose.onNode(button(string(R.string.alerts_approve))).performClick()
        compose.awaitThat { desk.rex.inputs.toList() == listOf("key $claude +enter") }
        assertEquals("the tree answered without taking the pane", Route.Home, m.route.value)

        desk.rex.report(claude, "blocked", kind = "question", msg = "Which branch?", app = "claude")
        compose.awaitThat { m.pocket.value.blockStatus[claude]?.kind == "question" }
        val waitingRow = hasText("Which branch?", substring = true) and hasClickAction() and !inASheet
        fun openSheet() {
            scrollTo(waitingRow)
            compose.onNode(waitingRow).performTouchInput { longClick() }
            compose.waitForIdle()
        }
        openSheet()
        assertTrue(sheetHas(string(R.string.alerts_reply)))
        assertTrue(sheetHas(string(R.string.alerts_interrupt)))
        // Not an approval prompt: no Approve or Deny.
        assertTrue(!sheetHas(string(R.string.alerts_approve)))
        inSheet(string(R.string.alerts_interrupt)).performScrollTo().performClick()
        compose.awaitThat { desk.rex.inputs.any { it == "key $claude ctrl+key_c" } }

        openSheet()
        inSheet(string(R.string.home_host_sheet, desk.host.label)).performScrollTo().performClick()
        compose.awaitThat { sheetHas(string(R.string.action_edit_host)) }
    }

    /** A done row's own Mark as seen, a host's Mark all as seen and the home's, each undone. */
    @Test
    fun markAllAsSeenCoversWhatTheListShowsAndUndoes() {
        val m = start()
        val a = tabs(m, shown)[0]
        val b = tabs(m, other)[0]
        val c = tabs(m, other)[1]
        desk.rex.report(a.blocks[0].id, "done", app = "cargo")
        desk.rex.report(b.blocks[0].id, "error", msg = "exit 1", app = "make")
        desk.rex.report(c.blocks[0].id, "done", app = "pi")
        compose.awaitThat { needsYou(m) == 3 }

        chip(HomeFilter.NeedsYou).performClick()
        compose.waitForIdle()
        // The row's own button: that tab is seen, and Undo brings it back.
        // Each done or failed row has its own, said with the tab's name.
        val seenButton = button(string(R.string.home_mark_seen))
        assertEquals(3, count(seenButton))
        compose.onAllNodes(seenButton)[0].performClick()
        compose.awaitThat { needsYou(m) == 2 }
        compose.onNode(hasText(string(R.string.action_undo)) and hasClickAction()).performClick()
        compose.awaitThat { needsYou(m) == 3 }

        // The home's ⋮: everything the list shows, at once, with one Undo.
        homeMenu()
        inSheet(string(R.string.home_mark_all_seen)).performClick()
        compose.awaitThat { needsYou(m) == 0 }
        compose.onNode(hasText(string(R.string.action_undo)) and hasClickAction()).performClick()
        compose.awaitThat { needsYou(m) == 3 }

        // A host's sheet: every tab of that host, from the tree.
        chip(HomeFilter.All).performClick()
        compose.waitForIdle()
        hostSheet()
        inSheet(string(R.string.home_mark_all_seen)).performScrollTo().performClick()
        compose.awaitThat { needsYou(m) == 0 }
        hostSheet()
        assertTrue("nothing left to mark", !sheetHas(string(R.string.home_mark_all_seen)))
    }

    /** Connect all and Disconnect all, with Reconnect; and an empty filter that says what it cannot see. */
    @Test
    fun everyHostAtOnceAndAFilterThatSaysWhatItCannotSee() {
        val m = start(withAway = true)
        chip(HomeFilter.NeedsYou).performClick()
        compose.waitForIdle()
        val empty = string(R.string.filter_needs_you_none_connected) + " · " + string(R.string.home_not_connected_count, 1)
        compose.awaitThat { count(hasText(empty)) == 1 }
        val marked = compose.onNodeWithTag(filterChipTag(HomeFilter.NeedsYou)).fetchSemanticsNode().config
        assertEquals(string(R.string.home_counts_connected_only), marked.getOrElse(SemanticsProperties.StateDescription) { "" })

        homeMenu()
        inSheet(string(R.string.home_disconnect_all)).performClick()
        compose.awaitThat { desk.host.id !in m.lives.value }
        compose.onNode(hasText(string(R.string.action_reconnect)) and hasClickAction()).performClick()
        compose.awaitThat { m.lives.value[desk.host.id]?.state?.phase == Phase.Live }

        homeMenu()
        inSheet(string(R.string.home_connect_all)).performClick()
        compose.awaitThat { away.id in m.lives.value }
        homeMenu()
        assertTrue("everything is connected or on its way", !sheetHas(string(R.string.home_connect_all)))
        inSheet(string(R.string.home_disconnect_all)).performClick()
        compose.awaitThat { m.lives.value.isEmpty() }
    }

    /** Activity with Include idle off says how many seen idle tabs it hides, and Show turns it on. */
    @Test
    fun activitySaysHowManyIdleTabsItHides() {
        val m = start()
        val idle = tabs(m, shown)[1].blocks[0].id
        desk.rex.report(idle, "idle", app = "claude")
        compose.awaitThat { m.pocket.value.blockStatus[idle]?.state == "idle" }
        compose.runOnUiThread { m.markSeen(desk.host.id, listOf(idle)) }
        chip(HomeFilter.Activity).performClick()
        compose.waitForIdle()
        val note = compose.activity.resources.getQuantityString(R.plurals.activity_idle_hidden, 1, 1)
        compose.awaitThat { count(hasText(note)) == 1 }
        compose.onNode(button(string(R.string.action_show))).performClick()
        compose.awaitThat { m.activityPrefs.value.includeIdle }
        compose.awaitThat { count(hasText(note)) == 0 }
    }

    /** Rename's Automatic name gives a session and a tab back the names Rex chose. */
    @Test
    fun renameCanGiveBackRexsOwnName() {
        assumeFalse("a text field in a dialog never idles on Robolectric", onRobolectric)
        val m = start()
        val label = m.pocket.value.sessions.first { it.id == other }.label
        compose.onNode(hasContentDescription(string(R.string.manage_actions_for, label)) and hasClickAction()).performClick()
        compose.waitForIdle()
        inSheet(string(R.string.home_rename)).performClick()
        compose.waitForIdle()
        compose.onNode(hasText(string(R.string.rename_automatic)) and hasClickAction()).performClick()
        compose.awaitThat { m.pocket.value.views[other]?.label == "quiet-meadow" }
        assertTrue(desk.rex.calls.any { it.startsWith("session.set_label") })
    }

    private fun templateCommand(i: Int = -1): String {
        val layout = if (i < 0) desk.rex.layouts.last() else desk.rex.layouts[i]
        return layout.getJSONObject("options").getJSONArray("command").getString(2)
    }

    /** A template runs from the host's sheet into a new tab of the session shown, and its list is managed with Undo. */
    @Test
    fun aTemplateOpensItsTabAndItsListIsManaged() {
        val m = start()
        val api = LaunchTemplate("t1", "api", "~/api", "claude")
        val web = LaunchTemplate("t2", "web", "", "npm run dev")
        compose.runOnUiThread { m.setLaunchTemplates(desk.host.id, listOf(api, web)) }
        val before = tabs(m, shown).size
        hostSheet()
        inSheet(string(R.string.template_new_tab)).performScrollTo().performClick()
        compose.waitForIdle()
        inSheet("api").performClick()
        compose.awaitThat { m.route.value == Route.Term && tabs(m, shown).size == before + 1 }
        assertEquals("cd ~/'api' && claude; exec \"\${SHELL:-sh}\" -l", templateCommand())
        assertEquals("api", tabs(m, shown).last().label)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.awaitThat { m.route.value == Route.Home }

        // Edit templates: web moves up, then is deleted, and Undo brings it back in its place.
        hostSheet()
        inSheet(string(R.string.template_new_tab)).performScrollTo().performClick()
        compose.waitForIdle()
        inSheet(string(R.string.template_manage)).performClick()
        compose.waitForIdle()
        inSheet("web").performClick()
        compose.waitForIdle()
        inSheet(string(R.string.action_move_up)).performClick()
        compose.awaitThat { m.launchTemplates.value[desk.host.id] == listOf(web, api) }
        inSheet(string(R.string.template_delete)).performScrollTo().performClick()
        compose.awaitThat { m.launchTemplates.value[desk.host.id] == listOf(api) }
        compose.onNode(hasText(string(R.string.action_undo)) and hasClickAction() and !inASheet).performClick()
        compose.awaitThat { m.launchTemplates.value[desk.host.id] == listOf(web, api) }
        // Kept for the next start.
        assertEquals(listOf(web, api), RexViewModel(compose.activity.application).launchTemplates.value[desk.host.id])
    }

    /** A template typed into the dialog, run from a session's New tab. On the emulator: a dialog's text field never idles on Robolectric. */
    @Test
    fun aTemplateIsAddedInItsDialogAndRunFromASessionsSheet() {
        assumeFalse("a text field in a dialog never idles on Robolectric", onRobolectric)
        val m = start()
        val label = m.pocket.value.sessions.first { it.id == other }.label
        compose.onNode(hasContentDescription(string(R.string.manage_actions_for, label)) and hasClickAction()).performClick()
        compose.waitForIdle()
        inSheet(string(R.string.menu_new_tab)).performScrollTo().performClick()
        compose.waitForIdle()
        inSheet(string(R.string.template_add)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(TEMPLATE_NAME_TAG))).performTextInput("logs")
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(TEMPLATE_DIR_TAG))).performTextInput("/var/log")
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(TEMPLATE_COMMAND_TAG))).performTextInput("tail -f syslog")
        compose.onNode(hasText(string(R.string.action_save)) and hasClickAction()).performClick()
        compose.awaitThat { m.launchTemplates.value[desk.host.id]?.map { it.name } == listOf("logs") }
        val before = tabs(m, other).size
        inSheet("logs").performClick()
        compose.awaitThat { m.route.value == Route.Term && tabs(m, other).size == before + 1 }
        assertEquals("cd '/var/log' && tail -f syslog; exec \"\${SHELL:-sh}\" -l", templateCommand())
    }
}
