package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.ActivityGroup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The eye on the home screen shows what Rex's Activity shows: the connected host's tabs whose
 * program reported a status, grouped Waiting, Failed, Done, Running, each row with the program's
 * tile, the tab, its session and the program's message - not only what is running now. A row opens
 * the pane that reported. "Nothing" is said only when Rex has nothing.
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

    private fun string(id: Int) = compose.activity.getString(id)

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

    private fun tabs(m: RexViewModel, session: String) = m.pocket.value.views.getValue(session).windows

    private fun eye() {
        compose.onNodeWithContentDescription(string(R.string.action_show_active)).performClick()
        compose.waitForIdle()
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y

    @Test
    fun failedAndDoneTabsAreShownGroupedNotOnlyWhatRuns() {
        val m = start()
        val failedTab = tabs(m, shown)[1]
        val doneTab = tabs(m, other)[0]
        desk.rex.report(failedTab.blocks[0].id, "error", msg = "failed: prompt_failed: 401", app = "pi")
        desk.rex.report(doneTab.blocks[0].id, "done", app = "pi")
        compose.awaitThat { m.pocket.value.blockStatus.size == 2 }

        eye()

        compose.onNodeWithTag(activityGroupTag(ActivityGroup.Failed), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(activityGroupTag(ActivityGroup.Done), useUnmergedTree = true).assertIsDisplayed()
        // Failed above its row, then Done above its row: Rex's priority order.
        val order = listOf(
            top(activityGroupTag(ActivityGroup.Failed)),
            top(activityRowTag(failedTab.id)),
            top(activityGroupTag(ActivityGroup.Done)),
            top(activityRowTag(doneTab.id)),
        )
        assertEquals(order.sorted(), order)
        compose.onNodeWithText("failed: prompt_failed: 401").assertIsDisplayed()
        // Each row says which session its tab is in.
        val labels = m.pocket.value.sessions.associate { it.id to it.label }
        compose.onNode(hasTestTag(activityRowTag(doneTab.id)) and hasText(labels.getValue(other), substring = true)).assertExists()
        assertTrue(compose.onAllNodes(hasText(string(R.string.no_activity))).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasText(string(R.string.action_add_host))).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aRowOpensThePaneThatReported() {
        val m = start()
        val tab = tabs(m, other)[1]
        desk.rex.report(tab.blocks[0].id, "blocked", kind = "permission", msg = "Apply 3 to add?")
        compose.awaitThat { m.pocket.value.blockStatus.isNotEmpty() }
        eye()
        compose.onNodeWithTag(activityGroupTag(ActivityGroup.Waiting), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(activityRowTag(tab.id)).performClick()
        compose.awaitThat {
            val st = m.pocket.value
            m.route.value == Route.Term && st.activeSessionId == other && st.view?.focusedBlock()?.id == tab.blocks[0].id
        }
    }

    @Test
    fun nothingIsSaidOnlyWhenRexHasNothingAndATabLeavesWhenItsRecordGoes() {
        val m = start()
        eye()
        compose.onNodeWithText(string(R.string.no_activity)).assertIsDisplayed()
        val block = tabs(m, shown)[0].blocks[0].id
        desk.rex.report(block, "working", app = "cargo")
        compose.awaitThat { compose.onAllNodes(hasText(string(R.string.activity_running))).fetchSemanticsNodes().isNotEmpty() }
        desk.rex.report(block, null)
        compose.awaitThat { compose.onAllNodes(hasText(string(R.string.no_activity))).fetchSemanticsNodes().isNotEmpty() }
    }
}
