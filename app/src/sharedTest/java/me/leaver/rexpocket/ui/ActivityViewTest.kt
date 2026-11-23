package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.Desk
import me.leaver.rexpocket.data.HomeMarks
import me.leaver.rexpocket.data.HostStore
import me.leaver.rexpocket.data.SavedHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The home's Needs you and Running filters: what waits on the person, then what failed and what is
 * done that they have not seen, in Rex's priority order; and what runs. A done tab stays until it is
 * seen - opened on the phone, marked as seen (with Undo), or looked at on a desk app - and a waiting
 * one offers Reply (never for a login) and Interrupt. Hosts that are not connected are named under
 * the list with Connect. The chips count it all, and the choice is kept.
 *
 * The whole app on its real view model and store, on ScriptedRex, whose programs report statuses as
 * OSC 7501 programs do. Runs on the JVM (Robolectric) and on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class ActivityViewTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 2)
    private var model: RexViewModel? = null

    private val shown get() = desk.ids[0]
    private val other get() = desk.ids[1]

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    /** A host saved and never connected, for the line under the filters. */
    private val away = SavedHost(id = "away-host", label = "build-box", host = "127.0.0.1", port = 9, plaintext = true)

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

    private fun chip(filter: HomeFilter) = compose.onNodeWithTag(filterChipTag(filter))

    private fun needsYou() {
        chip(HomeFilter.NeedsYou).performClick()
        compose.waitForIdle()
    }

    private fun row(windowId: String) = compose.onNodeWithTag(activityRowTag(windowId), useUnmergedTree = true)

    private fun rowGone(windowId: String) = compose.onAllNodes(hasTestTag(activityRowTag(windowId)), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()

    private fun top(windowId: String) = row(windowId).fetchSemanticsNode().positionInRoot.y

    private fun inSheet(text: String) = compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(hasTestTag(ACTION_SHEET_TAG)))

    @Test
    fun needsYouListsWaitingThenFailedThenDoneAndRunningIsItsOwn() {
        val m = start()
        val failedTab = tabs(m, shown)[1]
        val runningTab = tabs(m, shown)[0]
        val doneTab = tabs(m, other)[0]
        val waitingTab = tabs(m, other)[1]
        desk.rex.report(failedTab.blocks[0].id, "error", msg = "failed: prompt_failed: 401", app = "pi")
        desk.rex.report(doneTab.blocks[0].id, "done", app = "pi")
        desk.rex.report(waitingTab.blocks[0].id, "blocked", kind = "permission", msg = "Apply 3 to add?", app = "terraform")
        desk.rex.report(runningTab.blocks[0].id, "working", app = "cargo")
        compose.awaitThat { m.pocket.value.blockStatus.size == 4 }
        compose.waitForIdle()
        chip(HomeFilter.NeedsYou).assert(hasText(string(R.string.filter_needs_you, 3)))
        chip(HomeFilter.Running).assert(hasText(string(R.string.filter_running, 1)))

        needsYou()
        val order = listOf(top(waitingTab.id), top(failedTab.id), top(doneTab.id))
        assertEquals("Rex's priority: waiting, failed, done", order.sorted(), order)
        assertTrue("a running tab is not in Needs you", rowGone(runningTab.id))
        compose.onNodeWithText("failed: prompt_failed: 401").assertIsDisplayed()
        // Each row says its state in words, and which session its tab is in.
        val labels = m.pocket.value.sessions.associate { it.id to it.label }
        compose.onNode(hasTestTag(activityRowTag(doneTab.id)) and hasText(labels.getValue(other), substring = true)).assertExists()
        compose.onNodeWithText(string(R.string.state_waiting_approval)).assertExists()
        assertTrue(compose.onAllNodes(hasText(string(R.string.action_add_host))).fetchSemanticsNodes().isEmpty())
        // Terraform asks for approval too, but its keys are its own: no Approve or Deny, Reply stays.
        assertTrue(compose.onAllNodes(hasText(string(R.string.alerts_approve)) and hasClickAction()).fetchSemanticsNodes().isEmpty())
        assertEquals(1, compose.onAllNodes(hasText(string(R.string.alerts_reply)) and hasClickAction()).fetchSemanticsNodes().size)

        chip(HomeFilter.Running).performClick()
        compose.waitForIdle()
        row(runningTab.id).assertExists()
        assertTrue(rowGone(waitingTab.id))
        // The choice is kept for the next start.
        assertEquals(HomeFilter.Running, RexViewModel(compose.activity.application).homeFilter.value)
    }

    @Test
    fun aRowOpensThePaneThatReportedAndADoneOneIsThenSeen() {
        val m = start()
        val tab = tabs(m, other)[1]
        desk.rex.report(tab.blocks[0].id, "done", msg = "built", app = "cargo")
        compose.awaitThat { m.pocket.value.blockStatus.isNotEmpty() }
        needsYou()
        row(tab.id).performClick()
        compose.awaitThat {
            val st = m.pocket.value
            m.route.value == Route.Term && st.activeSessionId == other && st.view?.focusedBlock()?.id == tab.blocks[0].id
        }
        compose.awaitThat { HomeMarks.key(desk.host.id, tab.blocks[0].id) in m.seenMarks.value }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.awaitThat { m.route.value == Route.Home }
        compose.awaitThat { rowGone(tab.id) }
        chip(HomeFilter.NeedsYou).assert(hasText(string(R.string.filter_needs_you, 0)))
    }

    @Test
    fun aDoneTabStaysUntilMarkedAsSeenWhichUndoTakesBack() {
        val m = start()
        val tab = tabs(m, other)[0]
        desk.rex.report(tab.blocks[0].id, "done", msg = "Upgraded 12 packages", app = "brew")
        compose.awaitThat { m.pocket.value.blockStatus.isNotEmpty() }
        needsYou()
        row(tab.id).assertExists()
        // The tab's sheet, from a long press on its row, as anywhere on the home.
        compose.onNode(hasTestTag(activityRowTag(tab.id)) and hasClickAction()).performTouchInput { longClick() }
        compose.waitForIdle()
        inSheet(string(R.string.home_mark_seen)).performClick()
        compose.awaitThat { rowGone(tab.id) }
        compose.onNodeWithText(string(R.string.home_marked_seen)).assertExists()
        compose.onNode(hasText(string(R.string.action_undo)) and hasClickAction()).performClick()
        compose.awaitThat { !rowGone(tab.id) }
        // A new report is news again, whatever was seen before.
        compose.onNode(hasTestTag(activityRowTag(tab.id)) and hasClickAction()).performTouchInput { longClick() }
        compose.waitForIdle()
        inSheet(string(R.string.home_mark_seen)).performClick()
        compose.awaitThat { rowGone(tab.id) }
        desk.rex.report(tab.blocks[0].id, "done", msg = "Upgraded 13 packages", app = "brew")
        compose.awaitThat { !rowGone(tab.id) }
    }

    /** Seen on the desk is seen on the phone: Rex.app taking the done pane, or showing it. */
    @Test
    fun aDoneTabADeskAppLooksAtLeavesNeedsYou() {
        val m = start()
        val (tab, pane) = tabs(m, other)[0].let { it.id to it.blocks[0].id }
        desk.rex.report(pane, "done", app = "cargo")
        compose.awaitThat { m.pocket.value.blockStatus.isNotEmpty() }
        needsYou()
        row(tab).assertExists()
        Desk(desk.rex.endpoint, platform = "macos").use { mac ->
            runBlocking {
                mac.connect()
                mac.focus(other, tab)
                mac.take(other, pane)
            }
            compose.awaitThat { rowGone(tab) }
        }
        assertTrue(HomeMarks.key(desk.host.id, pane) in m.seenMarks.value)
    }

    @Test
    fun aWaitingQuestionOffersReplyAndInterruptAndALoginNoReply() {
        val m = start(withAway = true)
        val question = tabs(m, other)[0].blocks[0].id
        val login = tabs(m, other)[1].blocks[0].id
        desk.rex.report(question, "blocked", kind = "question", msg = "Which branch?", app = "claude")
        desk.rex.report(login, "blocked", kind = "auth", msg = "Password", app = "ssh")
        compose.awaitThat { m.pocket.value.blockStatus.size == 2 }
        needsYou()
        val reply = hasText(string(R.string.alerts_reply)) and hasClickAction()
        val interrupt = hasText(string(R.string.alerts_interrupt)) and hasClickAction()
        assertEquals("Reply for the question only, never for a login", 1, compose.onAllNodes(reply).fetchSemanticsNodes().size)
        assertEquals(2, compose.onAllNodes(interrupt).fetchSemanticsNodes().size)
        // The host that is not connected is said, with its way in.
        compose.onNodeWithText(string(R.string.home_not_connected_line, away.label)).assertExists()
        compose.onAllNodes(interrupt)[0].performClick()
        compose.awaitThat { desk.rex.inputs.any { it == "key $question ctrl+key_c" || it == "key $login ctrl+key_c" } }
        assertTrue("the home took no pane to interrupt", m.route.value == Route.Home)
    }

    private val approveButton get() = hasText(string(R.string.alerts_approve)) and hasClickAction()
    private val denyButton get() = hasText(string(R.string.alerts_deny)) and hasClickAction()

    /** An agent's approval prompt: Approve types its key for yes, Deny its key for no, from the list. */
    @Test
    fun approveAndDenyAnswerAnAgentsPromptFromTheList() {
        val m = start()
        val claude = tabs(m, other)[0].blocks[0].id
        val codex = tabs(m, other)[1].blocks[0].id
        desk.rex.report(claude, "blocked", kind = "permission", msg = "Allow Bash: npm test?", app = "claude")
        compose.awaitThat { m.pocket.value.blockStatus[claude]?.state == "blocked" }
        needsYou()
        compose.awaitThat { compose.onAllNodes(approveButton).fetchSemanticsNodes().size == 1 }
        assertEquals(1, compose.onAllNodes(denyButton).fetchSemanticsNodes().size)
        compose.onNode(approveButton).performClick()
        compose.awaitThat { desk.rex.inputs.toList() == listOf("key $claude +enter") }
        assertTrue("the home took no pane to answer", m.route.value == Route.Home)

        desk.rex.report(claude, null)
        desk.rex.report(codex, "blocked", kind = "permission", msg = "Run cargo test?", app = "codex")
        compose.awaitThat { m.pocket.value.blockStatus[codex]?.state == "blocked" && claude !in m.pocket.value.blockStatus }
        compose.awaitThat { compose.onAllNodes(denyButton).fetchSemanticsNodes().size == 1 }
        compose.onNode(denyButton).performClick()
        compose.awaitThat { desk.rex.inputs.toList() == listOf("key $claude +enter", "key $codex +escape") }
    }

    /**
     * The prompt changed after the row showed it, and the phone did not hear (a lost event): Approve
     * reads it again, types nothing, says so (a toast) and opens the pane to show what it asks now.
     */
    @Test
    fun approveForAPromptThatChangedOpensThePaneAndTypesNothing() {
        val m = start()
        val window = tabs(m, other)[0]
        val block = window.blocks[0].id
        desk.rex.report(block, "blocked", kind = "permission", msg = "Allow Bash: ls?", app = "claude")
        compose.awaitThat { m.pocket.value.blockStatus[block]?.state == "blocked" }
        needsYou()
        compose.awaitThat { compose.onAllNodes(approveButton).fetchSemanticsNodes().size == 1 }
        Thread.sleep(5)
        desk.rex.report(block, "blocked", kind = "permission", msg = "Allow Bash: rm -rf /?", app = "claude", quiet = true)
        compose.onNode(approveButton).performClick()
        compose.awaitThat { m.route.value == Route.Term && m.pocket.value.view?.focusedBlock()?.id == block }
        assertTrue("nothing was typed: ${desk.rex.inputs}", desk.rex.inputs.isEmpty())
    }

    /**
     * Reply's Compose takes the pane to the composer: a longer prompt, sent in one paste, then
     * Return. The emulator only: a text field in a dialog never lets Compose idle on Robolectric.
     */
    @Test
    fun replysComposeSendsALongerPromptInOnePaste() {
        org.junit.Assume.assumeFalse(onRobolectric)
        val m = start()
        val block = tabs(m, other)[0].blocks[0].id
        desk.rex.report(block, "blocked", kind = "question", msg = "What next?", app = "claude")
        compose.awaitThat { m.pocket.value.blockStatus[block]?.state == "blocked" }
        needsYou()
        compose.onNode(hasText(string(R.string.alerts_reply)) and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNode(hasText(string(R.string.home_reply_compose)) and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction() and hasAnyAncestor(hasTestTag(COMPOSER_FIELD_TAG)))
            .performTextInput("Fix the failing test.\nThen commit.")
        compose.onNodeWithTag(COMPOSER_SEND_TAG).performClick()
        compose.awaitThat { desk.rex.inputs.toList() == listOf("paste $block Fix the failing test.\nThen commit.", "key $block +enter") }
    }

    @Test
    fun nothingIsSaidOnlyWhenNothingNeedsYouAndATabLeavesWhenItsRecordGoes() {
        val m = start()
        needsYou()
        compose.onNodeWithText(string(R.string.filter_needs_you_none)).assertIsDisplayed()
        val block = tabs(m, shown)[0].blocks[0].id
        desk.rex.report(block, "blocked", kind = "permission", msg = "Run it?")
        compose.awaitThat { compose.onAllNodes(hasText("Run it?")).fetchSemanticsNodes().isNotEmpty() }
        desk.rex.report(block, null)
        compose.awaitThat { compose.onAllNodes(hasText(string(R.string.filter_needs_you_none))).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(HomeFilter.NeedsYou, HostStore(compose.activity).let { RexViewModel(compose.activity.application).homeFilter.value })
    }
}
