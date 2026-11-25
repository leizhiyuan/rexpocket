package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The prompt composer that Reply's Compose opens on the home, fed from the real view model's
 * drafts: Press Return after it is the host's, as the terminal's composer keeps it, both ways.
 * HomeEdgeTest drives the same through Reply on the emulator, where Reply's dialog can be typed in.
 */
@RunWith(AndroidJUnit4::class)
class HomeComposerTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = compose.activity.getString(id)

    private val target = ReplyTarget("build-box", "session:1", "block:claude", "claude")

    @Test
    fun returnAfterIsTheHostsChoice() {
        lateinit var model: RexViewModel
        compose.runOnUiThread { model = RexViewModel(freshApp()) }
        model.drafts.setEnterAfter("build-box", false)
        val sent = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            RexTheme {
                HomeComposer(target, model.drafts, quickReplies = listOf("continue"), onSend = { text, enter -> sent += text to enter }, onDismiss = {})
            }
        }
        compose.waitForIdle()
        assertTrue(
            "Return after is off on this host",
            compose.onAllNodes(hasText(string(R.string.composer_enter_after_body))).fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onNodeWithTag(quickReplyChipTag(0)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(COMPOSER_SEND_TAG).performClick()
        compose.waitForIdle()
        assertEquals(listOf("continue" to false), sent)
    }

    @Test
    fun turningItOnHereIsKeptForTheHost() {
        lateinit var model: RexViewModel
        compose.runOnUiThread { model = RexViewModel(freshApp()) }
        model.drafts.setEnterAfter("build-box", false)
        compose.setContent {
            RexTheme { HomeComposer(target, model.drafts, quickReplies = emptyList(), onSend = { _, _ -> }, onDismiss = {}) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(COMPOSER_ENTER_TAG).performClick()
        compose.waitForIdle()
        assertTrue(model.drafts.enterAfter("build-box"))
        assertFalse("another host keeps its own", !model.drafts.enterAfter("other-host"))
    }
}
