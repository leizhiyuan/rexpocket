package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.KeyModifiers
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The key bar laid out on a device at a phone's widths: the full-screen key whole where there is
 * room, and in part where there is not, and the key pill showing part of a key past its edge either
 * way, the hint that more follow.
 */
@RunWith(AndroidJUnit4::class)
class KeyBarLayoutInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The bar as wide as a screen of [screenDp], whatever the emulator's own width. */
    private fun show(screenDp: Int) {
        compose.setContent {
            RexTheme {
                Box(Modifier.wrapContentWidth(unbounded = true).requiredWidth(screenDp.dp)) {
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
                        onFullScreen = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private val Float.px get() = this * compose.activity.resources.displayMetrics.density

    /** Where a node is laid out, unclipped: a scrolled-off key, or a bar wider than the screen. */
    private fun SemanticsNode.rect() =
        Rect(positionInRoot.x, positionInRoot.y, positionInRoot.x + size.width, positionInRoot.y + size.height)

    private fun SemanticsNodeInteraction.rect() = fetchSemanticsNode().rect()

    private fun pill(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).rect()

    private fun fullScreenKey() = compose
        .onNodeWithContentDescription(compose.activity.getString(R.string.action_full_screen), useUnmergedTree = true)
        .rect()

    private fun keyPillKeys() = compose
        .onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(KEY_PILL_TAG)), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .map { it.rect() }

    /** True when [key] crosses [edge] with at least most of [PEEK_MIN] of it inside. */
    private fun crosses(key: Rect, edge: Float) = key.left < edge - (PEEK_MIN - 2f).px && key.right > edge + 1f.px

    private fun keyPillHints(): Boolean {
        val edge = pill(KEY_PILL_TAG).right
        return keyPillKeys().any { crosses(it, edge) }
    }

    @Test
    fun aRoomyPortraitPhoneShowsTheFullScreenKeyWholeAndTheKeyPillHints() {
        show(411)
        val rest = pill(REST_PILL_TAG)
        val key = fullScreenKey()
        assertTrue("full-screen key $key in pill $rest", key.right <= rest.right + 1f)
        assertTrue("the key pill shows part of a further key", keyPillHints())
    }

    @Test
    fun aTighterPortraitPhoneHintsInBothPills() {
        show(426)
        val rest = pill(REST_PILL_TAG)
        val key = fullScreenKey()
        assertTrue("full-screen key $key crosses pill $rest", crosses(key, rest.right))
        assertTrue("the key pill shows part of a further key", keyPillHints())
    }

    @Test
    fun landscapeShowsEveryKeyWhole() {
        show(891)
        val rest = pill(REST_PILL_TAG)
        assertTrue(fullScreenKey().right <= rest.right + 1f)
        val keys = pill(KEY_PILL_TAG)
        val last = keyPillKeys().maxOf { it.right }
        assertTrue("last key ends at $last in pill ${keys.right}", last <= keys.right + 1f)
    }
}
