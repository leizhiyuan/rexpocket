package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.PaneProgress
import me.leaver.rexpocket.core.ProgressState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A program's progress bar (OSC 9;4) where the person looks: along the terminal's top bar for the
 * tab, under each pane's segment of the switcher, and on the tab's row on the home, read by TalkBack
 * as a progress bar with its words. The terminal on the app's real view model; the home on
 * ScriptedRex, its desk's program reporting.
 */
@RunWith(AndroidJUnit4::class)
class ProgressViewTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    @Before
    fun edgeToEdge() {
        compose.runOnUiThread { compose.activity.edgeToEdgeAsTheApp() }
    }

    private var desk: RexOnDesk? = null
    private var model: RexViewModel? = null

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnect() } }
        desk?.close()
    }

    @Test
    fun theTopBarShowsTheTabsBarAndEachPaneItsOwn() {
        val tab = tabOf(testPane("left"), testPane("right"))
        val state = liveState(tab).copy(
            progress = mapOf(
                "block:left" to PaneProgress(ProgressState.Normal, 42),
                "block:right" to PaneProgress(ProgressState.Paused, 50),
            ),
        )
        compose.setContent { TermOnModel(termModel(), state) }
        compose.waitForIdle()
        // The focused pane's bar is the tab's, along the top bar.
        val top = compose.onNode(hasTestTag(PROGRESS_TAG) and hasContentDescription(string(R.string.progress_normal, 42)))
        top.assertIsDisplayed()
        top.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.42f, 0f..1f)))
        // The other pane's segment says its own.
        val paused = string(R.string.progress_paused, 50)
        val segments = compose.onAllNodes(SemanticsMatcher("a pane segment with a bar") { node ->
            node.config.getOrNull(SemanticsProperties.StateDescription)?.contains(paused) == true
        }).fetchSemanticsNodes()
        assertEquals(1, segments.size)
    }

    @Test
    fun aBarWithNoPercentIsBusyAndAFailedOneSaysSo() {
        val state = liveState().copy(progress = mapOf("block:left" to PaneProgress(ProgressState.Indeterminate)))
        var shown by mutableStateOf(state)
        compose.setContent { TermOnModel(termModel(), shown) }
        compose.waitForIdle()
        compose.onNode(hasTestTag(PROGRESS_TAG) and hasContentDescription(string(R.string.progress_busy)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
        shown = state.copy(progress = mapOf("block:left" to PaneProgress(ProgressState.Error)))
        compose.waitForIdle()
        compose.onNode(hasTestTag(PROGRESS_TAG) and hasContentDescription(string(R.string.progress_error_plain))).assertIsDisplayed()
        shown = state.copy(progress = emptyMap())
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasTestTag(PROGRESS_TAG)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theHomeShowsATabsBarWhileItsProgramReportsOne() {
        val d = RexOnDesk(sessions = 1)
        desk = d
        val app = freshApp()
        d.saveFor(app, lastSession = d.ids[0])
        val m = launchedModel(compose, app)
        model = m
        compose.setContent { RexPocketApp(m) }
        compose.awaitLive(m, d.ids[0])
        compose.runOnUiThread { m.showHome() }
        compose.waitForIdle()
        val block = m.pocket.value.views.getValue(d.ids[0]).windows.first().blocks.first().id
        val words = string(R.string.progress_normal, 64)
        d.rex.progress(block, 1, 64)
        // The bar is in the tab's row, which TalkBack reads as one: its words join the row's.
        compose.awaitThat { compose.onAllNodes(hasTestTag(PROGRESS_TAG) and hasContentDescription(words), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasTestTag(PROGRESS_TAG), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasText(compose.activity.getString(R.string.tab_number, 1), substring = true) and hasContentDescription(words)).assertIsDisplayed()
        d.rex.progress(block, 0)
        compose.awaitThat { compose.onAllNodes(hasTestTag(PROGRESS_TAG), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }
}
