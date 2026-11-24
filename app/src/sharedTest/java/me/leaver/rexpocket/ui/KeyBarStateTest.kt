package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.core.KeyArrangement
import me.leaver.rexpocket.core.KeyPreset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the key bar keeps when the activity is made again, as on a rotation: the group panel that
 * was open stays open, and only that one. The bar is the app's, on the app's view model.
 */
@RunWith(AndroidJUnit4::class)
class KeyBarStateTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun panelKeys(id: String, text: String) =
        compose.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(groupPanelTag(id))), useUnmergedTree = true)
            .fetchSemanticsNodes().size

    @Test
    fun theOpenGroupPanelStaysOpenAcrossARotation() {
        lateinit var model: RexViewModel
        compose.runOnUiThread {
            model = RexViewModel(freshApp())
            model.setKeyArrangement(KeyArrangement().addPreset(KeyPreset.Htop))
        }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { ControlsOnModel(model, page = null, onPage = {}) }
        compose.waitForIdle()

        compose.onNodeWithTag(groupKeyTag("htop")).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, panelKeys("htop", "F10"))

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals("htop's panel is open again", 1, panelKeys("htop", "F10"))
        assertEquals("and Fn's is not", 0, panelKeys("fn", "F12"))

        // Put away, it stays away.
        compose.onNodeWithTag(groupKeyTag("htop")).performScrollTo().performClick()
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(0, panelKeys("htop", "F10"))
    }
}
