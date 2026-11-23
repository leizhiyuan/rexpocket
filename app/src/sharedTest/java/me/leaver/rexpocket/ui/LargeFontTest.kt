package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.net.TailnetStatus
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screens with the system font 1.3 and 1.5 times larger on a 360 dp phone: no text may be cut
 * off - ellipsized, or past the lines it may take - where a person needs to read it. Each Text's own layout
 * says so. Runs on the JVM (Robolectric) and on the emulator; the host dialog only on the emulator,
 * as a text field in a dialog never lets Compose idle on Robolectric. ScreenshotsTest takes the
 * pictures (large-*).
 */
@RunWith(AndroidJUnit4::class)
class LargeFontTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** [content] on a 360 dp wide phone with the font [scale] times larger. */
    private fun show(scale: Float, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                Box(Modifier.width(360.dp).fillMaxHeight()) { content() }
            }
        }
        compose.waitForIdle()
    }

    /** Every text on screen, in every window, that is ellipsized or overflows its box. */
    private fun cutOff(): List<String> {
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return roots.flatMap(::walk).mapNotNull { node ->
            val layout = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return@mapNotNull null
            val results = mutableListOf<TextLayoutResult>()
            if (layout(results) != true) return@mapNotNull null
            val result = results.firstOrNull() ?: return@mapNotNull null
            val ellipsized = (0 until result.lineCount).any { result.isLineEllipsized(it) }
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                ?: result.layoutInput.text.text
            if (ellipsized || result.multiParagraph.didExceedMaxLines) text else null
        }
    }

    /** [except]: names that come from Rex, which a row may shorten to keep to one line. */
    private fun assertNothingCutOff(except: (String) -> Boolean = { false }) =
        assertEquals("cut off", emptyList<String>(), cutOff().filterNot(except))

    private fun controls(scale: Float, page: ControlsPage) {
        val model = RexViewModel(freshApp())
        show(scale) { ControlsOnModel(model, page, onPage = {}, bar = false) }
        assertNothingCutOff()
    }

    @Test fun controls13() = controls(1.3f, ControlsPage.Controls)
    @Test fun controls15() = controls(1.5f, ControlsPage.Controls)
    @Test fun arrangeKeys13() = controls(1.3f, ControlsPage.ArrangeKeys)
    @Test fun arrangeKeys15() = controls(1.5f, ControlsPage.ArrangeKeys)

    private fun home(scale: Float, status: TailnetStatus, joined: Boolean = true, hosts: List<me.leaver.rexpocket.data.SavedHost>? = null) {
        val node = FakeTailnetNode()
        node.state.value = status
        val model = tailnetModel(compose, node, hosts ?: listOf(TailnetSamples.macHost, TailnetSamples.nasHost), joined = joined)
        try {
            show(scale) { RexPocketApp(model, openUrl = {}) }
            compose.awaitThat { model.tailnetStage.value != me.leaver.rexpocket.core.TailnetStage.Off || !joined }
            assertNothingCutOff()
        } finally {
            compose.runOnUiThread { model.disconnect() }
        }
    }

    @Test fun homeSignIn15() = home(1.5f, TailnetSamples.needsLogin)
    @Test fun homeConnected15() = home(1.5f, TailnetSamples.connected())
    @Test fun homeConnected13() = home(1.3f, TailnetSamples.connected())
    @Test fun homeNotJoined15() = home(1.5f, TailnetSamples.starting, joined = false)
    @Test fun firstRun15() = home(1.5f, TailnetSamples.starting, joined = false, hosts = emptyList())
    @Test fun firstRunSignIn15() = home(1.5f, TailnetSamples.needsLogin, hosts = emptyList())

    private fun settings(scale: Float) {
        RexOnDesk(sessions = 1).use { desk ->
            val app = freshApp()
            desk.saveFor(app, lastSession = desk.ids[0])
            val model = launchedModel(compose, app)
            try {
                show(scale) { RexPocketApp(model) }
                compose.awaitLive(model, desk.ids[0])
                compose.onNodeWithContentDescription(compose.activity.getString(R.string.settings)).performClick()
                compose.waitForIdle()
                assertNothingCutOff(except = { it.startsWith("rexpocket-scenario-") })
            } finally {
                compose.runOnUiThread { model.disconnect() }
            }
        }
    }

    @Test fun settings13() = settings(1.3f)
    @Test fun settings15() = settings(1.5f)

    private fun hostDialog(scale: Float, address: String) {
        assumeFalse("a text field in a dialog never idles on Robolectric", onRobolectric)
        show(scale) {
            RexTheme {
                HostDialog(initial = null, tailnetEnabled = true, prefillAddress = address, onDismiss = {}, onDelete = null, onSave = {}, onJoinTailnet = {})
            }
        }
        assertNothingCutOff()
    }

    @Test fun hostDialogSsh13() = hostDialog(1.3f, "ssh://")
    @Test fun hostDialogSsh15() = hostDialog(1.5f, "ssh://")
    @Test fun hostDialogTailnet15() = hostDialog(1.5f, "mac.tail1234.ts.net")
}
