package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.TailnetStage
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first run, with no hosts: Rex named, and the three ways to reach it, each with its own next
 * step. The whole app on its view model and store. Runs on the JVM (Robolectric) and on the emulator;
 * the steps that open a dialog with a text field only on the emulator, as such a dialog never lets
 * Compose idle on Robolectric.
 */
@RunWith(AndroidJUnit4::class)
class EmptyHomeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var model: RexViewModel? = null

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun launch(): RexViewModel {
        val m = launchedModel(compose, freshApp())
        model = m
        compose.setContent { RexPocketApp(m) }
        compose.waitForIdle()
        return m
    }

    private fun button(label: Int, route: String) =
        compose.onNode(hasText(string(label)) and hasClickAction() and hasAnyAncestor(hasTestTag(route))).performScrollTo()

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnect() } }
    }

    @Test
    fun itNamesRexAndOffersThreeRoutes() {
        launch()
        compose.onNodeWithText(string(R.string.home_no_hosts)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.home_no_hosts_body)).assertIsDisplayed()
        button(R.string.action_add_ssh_host, EMPTY_ROUTE_SSH_TAG).assertIsDisplayed()
        button(R.string.action_join_tailnet, EMPTY_ROUTE_TAILNET_TAG).assertIsDisplayed()
        button(R.string.action_add_host, EMPTY_ROUTE_LOCAL_TAG).assertIsDisplayed()
        compose.onNodeWithText(LOCAL_START_COMMAND, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theCommandIsCopiedWithATap() {
        launch()
        compose.onNodeWithTag(EMPTY_COMMAND_TAG).performScrollTo().performClick()
        // Android 13's copied overlay takes the focus a moment; the clipboard is read once it is back.
        compose.awaitThat(30_000) { compose.runOnUiThread<String?> { clipboard(compose.activity) } == LOCAL_START_COMMAND }
    }

    @Test
    fun aJoinedTailnetShowsItsOwnCard() {
        val node = FakeTailnetNode()
        node.state.value = TailnetSamples.needsLogin
        val m = tailnetModel(compose, node, hosts = emptyList())
        model = m
        compose.setContent { RexPocketApp(m, openUrl = {}) }
        compose.awaitThat { m.tailnetStage.value is TailnetStage.SigningIn }
        compose.onNodeWithTag(TAILNET_CARD_TAG).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(string(R.string.action_sign_in)) and hasClickAction()).assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasTestTag(EMPTY_ROUTE_TAILNET_TAG)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun joinTailnetOpensTheTailnetDialog() {
        assumeFalse("a text field in a dialog never idles on Robolectric", onRobolectric)
        launch()
        button(R.string.action_join_tailnet, EMPTY_ROUTE_TAILNET_TAG).performClick()
        compose.waitForIdle()
        compose.onNode(hasText(string(R.string.tailscale_join)) and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }

    @Test
    fun addSshHostStartsFromSsh() {
        assumeFalse("a text field in a dialog never idles on Robolectric", onRobolectric)
        launch()
        button(R.string.action_add_ssh_host, EMPTY_ROUTE_SSH_TAG).performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("ssh://") and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }
}
