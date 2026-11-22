package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.BarKey
import me.leaver.rexpocket.core.DeskKey
import me.leaver.rexpocket.terminal.TerminalSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A key put on the bar through Arrange keys sends what it sends in the Fn panel: the page and the
 * bar the app shows, on its view model, and the bar's desk keys encoded by a real libghostty-vt
 * terminal as the terminal screen sends them ([deskKeyBytes]). On the emulator only: the JVM has
 * no libghostty.
 */
@RunWith(AndroidJUnit4::class)
class KeyBarBytesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var model: RexViewModel
    private lateinit var session: TerminalSession
    private var page by mutableStateOf<ControlsPage?>(ControlsPage.ArrangeKeys)
    private val sent = StringBuilder()

    @Before
    fun start() {
        compose.runOnUiThread {
            model = RexViewModel(freshApp())
            session = requireNotNull(TerminalSession.create(80, 24)) { "libghostty-vt could not make a terminal" }
            session.transport = object : TerminalSession.Transport {
                override fun sendInput(data: ByteArray) {
                    sent.append(String(data, Charsets.UTF_8))
                }

                override fun sendResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) = Unit
            }
        }
        compose.setContent {
            ControlsOnModel(model, page, onPage = { page = it }, onDeskKey = { key, mods ->
                deskKeyBytes(session, key, mods)?.let(session::send)
            })
        }
        compose.waitForIdle()
    }

    @After
    fun close() = compose.runOnUiThread { session.close() }

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private val inBar = hasAnyAncestor(hasTestTag(KEY_PILL_TAG))

    @Test
    fun f10OnTheBarSendsF10AndCtrlF10() {
        compose.onNode(hasText(string(R.string.arrange_add_keys)) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chip-${BarKey.Desk(DeskKey.F10).pref}").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        compose.waitForIdle()
        page = null
        compose.waitForIdle()

        val f10 = compose.onNode(hasText("F10") and inBar, useUnmergedTree = true)
        f10.performClick()
        compose.waitForIdle()
        assertEquals("\u001b[21~", sent.toString())

        sent.clear()
        compose.onNode(hasContentDescription(string(R.string.key_ctrl)) and inBar).performClick()
        f10.performClick()
        compose.waitForIdle()
        assertEquals("\u001b[21;5~", sent.toString())
        // The Fn panel's own F10 still sends the same.
        sent.clear()
        compose.onNodeWithTag(MORE_KEYS_TAG).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("F10", useUnmergedTree = true).fetchSemanticsNodes().size.let { assertEquals(2, it) }
        compose.onNode(hasText("F10") and hasAnyAncestor(hasTestTag(MORE_PANEL_TAG)), useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("\u001b[21~", sent.toString())
    }
}
