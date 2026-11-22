package me.leaver.rexpocket.ui

import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.CtrlCKey
import me.leaver.rexpocket.core.DoubleTapAction
import me.leaver.rexpocket.core.GestureSettings
import me.leaver.rexpocket.core.LongPressAction
import me.leaver.rexpocket.core.TapAction
import me.leaver.rexpocket.data.HostStore
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings › Terminal controls as a person uses it: each row is tapped on the page the app shows, on
 * the app's view model, and the row must then say the new value, the store must keep it, and the
 * page opened again must still say it. Runs on the JVM (Robolectric) and on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class TerminalControlsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var model: RexViewModel
    private var page by mutableStateOf<ControlsPage?>(ControlsPage.Controls)

    @Before
    fun start() {
        compose.runOnUiThread { model = RexViewModel(freshApp()) }
        compose.setContent { ControlsOnModel(model, page, onPage = { page = it }, bar = false) }
        compose.waitForIdle()
    }

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    /** The row called [label]: its label, its detail and what it does, as one node. */
    private fun row(label: Int): SemanticsNodeInteraction =
        compose.onNode(hasText(string(label)) and hasClickAction()).performScrollTo()

    private fun tapRow(label: Int) {
        row(label).performClick()
        compose.waitForIdle()
    }

    private fun assertRowSays(label: Int, detail: String) {
        row(label).assert(hasText(detail))
    }

    private fun stored(): GestureSettings = HostStore(compose.activity).gestures()

    private fun reopen() {
        page = null
        compose.waitForIdle()
        page = ControlsPage.Controls
        compose.waitForIdle()
    }

    @Test
    fun eachGestureRowSaysWhatItWasJustSetTo() {
        assertRowSays(R.string.gesture_tap, string(R.string.gesture_open_keyboard))
        tapRow(R.string.gesture_tap)
        assertRowSays(R.string.gesture_tap, string(R.string.gesture_toggle_keyboard))

        assertRowSays(R.string.gesture_double_tap, string(R.string.action_full_screen))
        tapRow(R.string.gesture_double_tap)
        assertRowSays(R.string.gesture_double_tap, string(R.string.gesture_toggle_keyboard))

        assertRowSays(R.string.gesture_long_press, string(R.string.gesture_select))
        tapRow(R.string.gesture_long_press)
        assertRowSays(R.string.gesture_long_press, string(R.string.action_full_screen))

        assertRowSays(R.string.gesture_wait, string(R.string.gesture_off))
        tapRow(R.string.gesture_wait)
        assertRowSays(R.string.gesture_wait, string(R.string.gesture_on))

        val expected = GestureSettings(
            tap = TapAction.Toggle,
            doubleTap = DoubleTapAction.Keyboard,
            longPress = LongPressAction.FullScreen,
            waitForDoubleTap = true,
        )
        assertEquals(expected, model.gestures.value)
        assertEquals(expected, stored())
        reopen()
        assertRowSays(R.string.gesture_tap, string(R.string.gesture_toggle_keyboard))
        assertRowSays(R.string.gesture_wait, string(R.string.gesture_on))
    }

    @Test
    fun aRowStepsRoundAllItsChoices() {
        tapRow(R.string.gesture_tap)
        tapRow(R.string.gesture_tap)
        assertRowSays(R.string.gesture_tap, string(R.string.gesture_nothing))
        tapRow(R.string.gesture_tap)
        assertRowSays(R.string.gesture_tap, string(R.string.gesture_open_keyboard))
        assertEquals(TapAction.Keyboard, stored().tap)
    }

    @Test
    fun aTimePickedOnTheSliderShowsOnItsRow() {
        val phone = ViewConfiguration.getLongPressTimeout()
        assertRowSays(R.string.gesture_long_press_time, string(R.string.gesture_time_system, phone))
        tapRow(R.string.gesture_long_press_time)
        compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))[0]
            .performSemanticsAction(SemanticsActions.SetProgress) { it(700f) }
        compose.onNodeWithText(string(R.string.action_save)).performClick()
        compose.waitForIdle()
        assertRowSays(R.string.gesture_long_press_time, string(R.string.gesture_time_ms, 700))
        assertEquals(700, stored().longPressMs)

        tapRow(R.string.gesture_long_press_time)
        compose.onNodeWithText(string(R.string.gesture_time_use_system)).performClick()
        compose.waitForIdle()
        assertRowSays(R.string.gesture_long_press_time, string(R.string.gesture_time_system, phone))
        assertEquals(0, stored().longPressMs)
    }

    @Test
    fun theKeyBarRowsSayWhatTheyWereJustSetTo() {
        assertRowSays(R.string.keybar_title, string(R.string.keybar_auto))
        tapRow(R.string.keybar_title)
        assertRowSays(R.string.keybar_title, string(R.string.keybar_always))
        assertEquals(true, HostStore(compose.activity).keyBarAlways())

        assertRowSays(R.string.keybar_ctrl_c, string(R.string.keybar_ctrl_c_tap))
        tapRow(R.string.keybar_ctrl_c)
        assertRowSays(R.string.keybar_ctrl_c, string(R.string.keybar_ctrl_c_hold))
        assertEquals(CtrlCKey.Hold, HostStore(compose.activity).keyBarCtrlC())

        reopen()
        assertRowSays(R.string.keybar_title, string(R.string.keybar_always))
        assertRowSays(R.string.keybar_ctrl_c, string(R.string.keybar_ctrl_c_hold))
    }

    @Test
    fun arrangeKeysOpensFromItsRow() {
        tapRow(R.string.arrange_title)
        assertEquals(ControlsPage.ArrangeKeys, page)
        compose.onNodeWithText(string(R.string.arrange_reset)).performScrollTo().assertExists()
    }
}
