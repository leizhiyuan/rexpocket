package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.data.QuickReplyStore
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings › Quick replies as HomeScreen shows it - the same sheet, fed from the real view model and
 * writing to the real store: the list in order on screen, add, edit, move (buttons and TalkBack's
 * actions) and delete, each checked on screen, in the store, and on a fresh look.
 */
@RunWith(AndroidJUnit4::class)
class QuickRepliesScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var model: RexViewModel

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    @Before
    fun start() {
        compose.runOnUiThread { model = RexViewModel(freshApp()) }
        compose.setContent { QuickRepliesOnModel(model) }
        compose.waitForIdle()
    }

    /** The rows' texts, top to bottom, as they are on screen. */
    private fun rowsOnScreen(): List<String> =
        compose.onAllNodes(SemanticsMatcher("a quick reply's row") { node -> node.config.getOrNull(SemanticsProperties.TestTag)?.matches(Regex("quick-reply-[0-9]+")) == true })
            .fetchSemanticsNodes()
            .sortedBy { it.positionInRoot.y }
            .map { it.config[SemanticsProperties.Text].first().text }

    private fun stored() = QuickReplyStore(compose.activity).get().items

    private fun field() = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(QUICK_REPLY_FIELD_TAG)))

    private fun tap(text: String) {
        compose.onNode(hasText(text) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    @Test
    fun theDefaultsInTheirOrder() {
        assertEquals(listOf("yes", "continue", "/compact"), rowsOnScreen())
    }

    @Test
    fun addEditAndDelete() {
        tap(string(R.string.quick_replies_add))
        field().performTextInput("lgtm, merge it")
        tap(string(R.string.action_save))
        assertEquals(listOf("yes", "continue", "/compact", "lgtm, merge it"), rowsOnScreen())
        assertEquals(listOf("yes", "continue", "/compact", "lgtm, merge it"), stored())

        tap("continue")
        field().performTextReplacement("go on")
        tap(string(R.string.action_save))
        assertEquals(listOf("yes", "go on", "/compact", "lgtm, merge it"), rowsOnScreen())

        tap("yes")
        tap(string(R.string.action_delete))
        assertEquals(listOf("go on", "/compact", "lgtm, merge it"), rowsOnScreen())
        assertEquals(listOf("go on", "/compact", "lgtm, merge it"), stored())
        assertEquals(stored(), RexViewModel(compose.activity.application).quickReplies.value.items)
    }

    @Test
    fun moveUpAndDownWithTheButtonsAndWithTalkBack() {
        val down = compose.onNode(hasContentDescription(string(R.string.arrange_move_down, "yes")) and hasClickAction())
        down.assertHeightIsAtLeast(48.dp)
        down.performClick()
        compose.waitForIdle()
        assertEquals(listOf("continue", "yes", "/compact"), rowsOnScreen())
        assertEquals(listOf("continue", "yes", "/compact"), stored())

        // TalkBack: the row's own actions, Move up and Move down, as the buttons beside it.
        val up = string(R.string.arrange_move_up, "/compact")
        val row = compose.onNode(hasText("/compact") and hasClickAction())
        val action = row.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == up }
        compose.runOnUiThread { action.action() }
        compose.waitForIdle()
        assertEquals(listOf("continue", "/compact", "yes"), rowsOnScreen())
        assertEquals(listOf("continue", "/compact", "yes"), stored())
    }
}

/** Settings › Quick replies as HomeScreen opens it: the sheet, fed from [model], its changes to it. */
@Composable
internal fun QuickRepliesOnModel(model: RexViewModel, onDismiss: () -> Unit = {}) {
    val replies by model.quickReplies.collectAsState()
    RexTheme { QuickRepliesSheet(replies, model::setQuickReplies, onDismiss) }
}
