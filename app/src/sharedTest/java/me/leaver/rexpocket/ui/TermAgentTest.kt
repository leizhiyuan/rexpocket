package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.BarKey
import me.leaver.rexpocket.core.PocketSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The terminal's ways to answer an agent: the key bar's Quick replies key and its panel, the
 * Compose key and the ⋮ menu's Compose prompt, the composer's draft kept for its pane, and the
 * palette's quick replies. The terminal screen on the app's view model, with the two keys added in
 * Arrange keys as a person adds them.
 */
@RunWith(AndroidJUnit4::class)
class TermAgentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val acts = mutableListOf<suspend PocketSession.() -> Unit>()
    private lateinit var model: RexViewModel

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    @Before
    fun start() {
        compose.runOnUiThread {
            compose.activity.edgeToEdgeAsTheApp()
            model = termModel()
            // At the front of the bar, where a person who uses them would put them.
            val added = model.keyArrangement.value.add(BarKey.Replies).add(BarKey.Compose)
            model.setKeyArrangement(added.moveTo(BarKey.Compose, 0).moveTo(BarKey.Replies, 0))
        }
        compose.setContent { androidx.compose.runtime.key(screen) { TermOnModel(model, liveState(), onAct = { acts += it }) } }
        compose.waitForIdle()
    }

    /** Which making of the screen is shown: a new one is the terminal screen made again, its own state gone. */
    private var screen by androidx.compose.runtime.mutableIntStateOf(0)

    private fun show() {
        screen++
        compose.waitForIdle()
    }

    private fun composerOpen() = compose.onAllNodes(hasTestTag(COMPOSER_FIELD_TAG)).fetchSemanticsNodes().isNotEmpty()

    private fun composerField() = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(COMPOSER_FIELD_TAG)))

    private fun fieldText(): String =
        composerField().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    @Test
    fun theQuickRepliesKeySendsOneAndPutsItsPanelAway() {
        compose.onNodeWithTag(REPLIES_KEY_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(REPLIES_PANEL_TAG).assertExists()
        val reply = compose.onNode(hasContentDescription(string(R.string.quick_reply_send, "continue")) and hasClickAction())
        reply.assertHeightIsAtLeast(48.dp)
        reply.performClick()
        compose.waitForIdle()
        assertEquals("the reply went to the pane", 1, acts.size)
        assertTrue(compose.onAllNodes(hasTestTag(REPLIES_PANEL_TAG)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theComposeKeyOpensTheComposerAndTheDraftStaysWithThePane() {
        compose.onNodeWithContentDescription(string(R.string.key_compose)).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(composerOpen())
        composerField().performTextInput("Look at the failing test")
        // A quick reply goes into the text, after what is there.
        compose.onNodeWithTag(quickReplyChipTag(1)).performClick()
        compose.waitForIdle()
        assertEquals("Look at the failing test continue", fieldText())
        assertTrue("nothing is sent before Send", acts.isEmpty())
        assertEquals("Look at the failing test continue", model.drafts[PromptDrafts.key(null, "block:left")])

        // The screen made again: the ⋮ menu's Compose prompt opens the same pane's draft.
        show()
        compose.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.composer_title)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("Look at the failing test continue", fieldText())

        compose.onNodeWithTag(COMPOSER_ENTER_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.composer_enter_after_body)).assertExists()
        compose.onNodeWithTag(COMPOSER_SEND_TAG).performClick()
        compose.waitForIdle()
        assertEquals("one paste went to the pane", 1, acts.size)
        assertEquals("a sent draft is gone", "", model.drafts[PromptDrafts.key(null, "block:left")])
        compose.waitUntil(5_000) { !composerOpen() }
    }

    @Test
    fun thePaletteListsTheQuickRepliesAndComposePrompt() {
        compose.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.palette_title)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(PALETTE_FIELD_TAG).performTextInput("compact")
        compose.waitForIdle()
        val rows = compose.onAllNodes(SemanticsMatcher("a palette row") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("palette-row-") == true })
            .fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].first().text }
        assertEquals(listOf("/compact"), rows)
        compose.onNode(hasText("/compact") and hasAnyAncestor(hasTestTag(ACTION_SHEET_TAG))).performClick()
        compose.waitForIdle()
        assertEquals(1, acts.size)
    }
}
