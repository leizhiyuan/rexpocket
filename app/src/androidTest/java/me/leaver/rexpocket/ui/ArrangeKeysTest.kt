package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.BarKey
import me.leaver.rexpocket.core.KeyArrangement
import me.leaver.rexpocket.core.KeyModifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Arrange keys on a device: a key moved or hidden in the list moves or goes in the bar beside it,
 * and Reset brings the bar back as it came.
 */
@RunWith(AndroidJUnit4::class)
class ArrangeKeysTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var arrangement by mutableStateOf(KeyArrangement())

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun show() {
        compose.setContent {
            RexTheme {
                // Wide enough for every key, so each one's place in the bar can be read.
                Box(Modifier.wrapContentWidth(unbounded = true).requiredWidth(891.dp)) {
                    KeyBar(
                        mods = KeyModifiers(),
                        busy = false,
                        imeUp = false,
                        onMod = {},
                        onKey = { _, _ -> },
                        onNewLine = {},
                        onPaste = {},
                        onAttach = {},
                        attachReady = true,
                        onKeyboard = {},
                        arrangement = arrangement,
                        onFullScreen = {},
                    )
                }
                ArrangeKeysSheet(arrangement = arrangement, onArrangement = { arrangement = it }, onDismiss = {})
            }
        }
        compose.waitForIdle()
    }

    private val inBar = hasAnyAncestor(hasTestTag(KEY_PILL_TAG))

    private fun barKeyX(matcher: SemanticsMatcher): Float =
        compose.onNode(matcher and inBar, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x

    @Test
    fun aKeyMovedInTheListMovesInTheBar() {
        show()
        val paste = hasContentDescription(string(R.string.key_paste))
        val more = hasContentDescription(string(R.string.key_more))
        assertTrue(barKeyX(paste) > barKeyX(more))
        compose.onNodeWithContentDescription(string(R.string.arrange_move_up, string(R.string.key_paste))).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue("Paste now before Fn", barKeyX(paste) < barKeyX(more))
        assertEquals(BarKey.Paste, arrangement.keys[arrangement.keys.indexOf(BarKey.More) - 1])
    }

    @Test
    fun aKeyHiddenInTheListLeavesTheBarAndResetBringsItBack() {
        show()
        val escape = hasText(string(R.string.key_escape)) and inBar
        compose.onAllNodes(escape, useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithContentDescription(string(R.string.arrange_hide, string(R.string.key_escape))).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onAllNodes(escape, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText(string(R.string.arrange_reset)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onAllNodes(escape, useUnmergedTree = true).assertCountEquals(1)
        assertEquals(KeyArrangement(), arrangement)
    }
}
