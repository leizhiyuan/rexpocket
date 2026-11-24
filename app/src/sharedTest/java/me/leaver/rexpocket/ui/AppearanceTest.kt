package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.AppTheme
import me.leaver.rexpocket.core.Appearance
import me.leaver.rexpocket.core.TerminalColors
import me.leaver.rexpocket.core.TextSize
import me.leaver.rexpocket.data.HostStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings › Appearance as HomeScreen shows it, on the app's view model, under the RexTheme that
 * RexApp gives the same value: a pick shows on its row, in the colours in use at once (no new
 * activity), in the terminal's default colours, and in the store. Runs on the JVM and the emulator.
 */
@RunWith(AndroidJUnit4::class)
class AppearanceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var model: RexViewModel
    private var open by mutableStateOf(true)

    @Before
    fun start() {
        compose.runOnUiThread { model = RexViewModel(freshApp()) }
        compose.setContent { AppearanceOnModel(model, open) }
        compose.waitForIdle()
    }

    private fun string(id: Int) = compose.activity.getString(id)

    private fun assertRowSays(label: Int, detail: Int, icon: Int) {
        compose.onNode(hasText(string(label)) and hasClickAction()).performScrollTo()
            .assert(hasText(string(detail)))
            .assert(SemanticsMatcher.expectValue(Drawable, icon))
    }

    @Test
    fun theyStartAsThePhoneAndTheApp() {
        assertRowSays(R.string.appearance_app, R.string.appearance_system, R.drawable.ic_contrast)
        assertRowSays(R.string.appearance_terminal, R.string.appearance_match_app, R.drawable.ic_contrast)
        assertTrue(compose.rowHasChevron(string(R.string.appearance_app)))
        assertTrue(compose.rowHasChevron(string(R.string.appearance_terminal)))
    }

    @Test
    fun aDarkTerminalInALightAppShowsAtOnceAndIsKept() {
        compose.pick(string(R.string.appearance_app), string(R.string.appearance_light))
        compose.pick(string(R.string.appearance_terminal), string(R.string.appearance_always_dark))
        assertRowSays(R.string.appearance_app, R.string.appearance_light, R.drawable.ic_light_mode)
        assertRowSays(R.string.appearance_terminal, R.string.appearance_always_dark, R.drawable.ic_dark_mode)

        assertFalse("the app is light", Colors.isDark)
        assertTrue("the pane is dark", Colors.paneDark)
        // The terminal's paper is the night page, its ink the night ink: not black on white.
        val theme = terminalTheme()
        assertEquals(0xFF0A0A0B.toInt(), theme.background)
        assertEquals(0xFFF2F2F7.toInt(), theme.foreground)

        val kept = Appearance(AppTheme.Light, TerminalColors.Dark)
        assertEquals(kept, model.appearance.value)
        assertEquals(kept, HostStore(compose.activity).appearance())
        open = false
        compose.waitForIdle()
        open = true
        compose.waitForIdle()
        assertRowSays(R.string.appearance_terminal, R.string.appearance_always_dark, R.drawable.ic_dark_mode)
    }

    @Test
    fun aDarkAppCanKeepALightTerminal() {
        compose.pick(string(R.string.appearance_app), string(R.string.appearance_dark))
        assertTrue(Colors.isDark)
        assertTrue("the terminal matches the app until told otherwise", Colors.paneDark)
        compose.pick(string(R.string.appearance_terminal), string(R.string.appearance_always_light))
        assertTrue(Colors.isDark)
        assertFalse(Colors.paneDark)
        assertEquals(0xFFFFFFFF.toInt(), terminalTheme().background)
    }

    /** How tall the words [text] are drawn: in the sheet, or in the open picker with [inPicker]. */
    private fun heightOf(text: String, inPicker: Boolean = false): Int {
        val matcher = if (inPicker) hasText(text) and hasAnyAncestor(hasTestTag(PICKER_TAG)) else hasText(text)
        return compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().first().size.height
    }

    private fun pickerHeightOf(text: String): Int {
        compose.onNode(hasText(string(R.string.appearance_text_size)) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
        return heightOf(text, inPicker = true).also {
            compose.onNode(hasText(string(R.string.action_cancel)) and hasClickAction()).performClick()
            compose.waitForIdle()
        }
    }

    @Test
    fun theTextSizeShowsAtOnceInTheSheetAndItsPickerAndIsKept() {
        assertRowSays(R.string.appearance_text_size, R.string.appearance_system, R.drawable.ic_text_fields)
        // Under its label, the row says the terminal is not part of it.
        compose.onNode(hasText(string(R.string.appearance_text_size_hint))).assertExists()
        val title = heightOf(string(R.string.appearance_title))
        val option = pickerHeightOf(string(R.string.text_size_85))

        compose.pick(string(R.string.appearance_text_size), string(R.string.text_size_150))
        assertRowSays(R.string.appearance_text_size, R.string.text_size_150, R.drawable.ic_text_fields)
        // The sheet and the dialog are windows of their own; both draw the new size at once.
        assertTrue("sheet title $title -> ${heightOf(string(R.string.appearance_title))}", heightOf(string(R.string.appearance_title)) > title * 1.25)
        assertTrue("picker option $option", pickerHeightOf(string(R.string.text_size_85)) > option * 1.25)

        val kept = Appearance(text = TextSize.Largest)
        assertEquals(kept, model.appearance.value)
        assertEquals(kept, HostStore(compose.activity).appearance())
        assertEquals("the terminal keeps its own size", 1f, model.fontScale.value)

        compose.pick(string(R.string.appearance_text_size), string(R.string.appearance_system))
        assertEquals(title, heightOf(string(R.string.appearance_title)))
    }
}

/** Appearance as HomeScreen opens it, in the theme RexApp gives the app from the same value. */
@Composable
internal fun AppearanceOnModel(model: RexViewModel, open: Boolean) {
    val appearance by model.appearance.collectAsState()
    RexTheme(appearance.app, appearance.terminal, appearance.text) {
        if (open) AppearanceSheet(appearance, model::setAppearance, onDismiss = {})
    }
}
