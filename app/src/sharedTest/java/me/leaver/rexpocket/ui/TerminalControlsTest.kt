package me.leaver.rexpocket.ui

import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings › Terminal controls as a person uses it: each row is tapped on the page the app shows, on
 * the app's view model, an option picked in the picker it opens, and the row must then say the new
 * value, the store must keep it, and the page opened again must still say it. Runs on the JVM
 * (Robolectric) and on the emulator.
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

    private fun pick(label: Int, option: Int) = compose.pick(string(label), string(option))

    private fun assertRowSays(label: Int, detail: String) {
        row(label).assert(hasText(detail))
    }

    /** The row's icon is [icon]: the action it is set to, not a picture of the gesture. */
    private fun assertRowShows(label: Int, icon: Int) {
        row(label).assert(SemanticsMatcher.expectValue(Drawable, icon))
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
        pick(R.string.gesture_tap, R.string.gesture_toggle_keyboard)
        assertRowSays(R.string.gesture_tap, string(R.string.gesture_toggle_keyboard))

        assertRowSays(R.string.gesture_double_tap, string(R.string.action_full_screen))
        pick(R.string.gesture_double_tap, R.string.gesture_toggle_keyboard)
        assertRowSays(R.string.gesture_double_tap, string(R.string.gesture_toggle_keyboard))

        assertRowSays(R.string.gesture_long_press, string(R.string.gesture_select))
        pick(R.string.gesture_long_press, R.string.action_full_screen)
        assertRowSays(R.string.gesture_long_press, string(R.string.action_full_screen))

        assertRowSays(R.string.gesture_wait, string(R.string.gesture_off))
        pick(R.string.gesture_wait, R.string.gesture_on)
        assertRowSays(R.string.gesture_wait, string(R.string.gesture_on))
        // The line under the label follows: what a tap does now.
        assertRowSays(R.string.gesture_wait, string(R.string.gesture_wait_on))

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
    fun eachRowsIconIsTheActionItIsSetTo() {
        assertRowShows(R.string.gesture_tap, R.drawable.ic_keyboard)
        assertRowShows(R.string.gesture_double_tap, R.drawable.ic_fullscreen)
        assertRowShows(R.string.gesture_long_press, R.drawable.ic_text_select)
        assertRowShows(R.string.keybar_ctrl_c, R.drawable.ic_touch_app)

        pick(R.string.gesture_tap, R.string.gesture_nothing)
        assertRowSays(R.string.gesture_tap, string(R.string.gesture_nothing))
        assertRowShows(R.string.gesture_tap, R.drawable.ic_block)
        pick(R.string.gesture_double_tap, R.string.gesture_toggle_keyboard)
        assertRowShows(R.string.gesture_double_tap, R.drawable.ic_keyboard)
        pick(R.string.gesture_double_tap, R.string.gesture_nothing)
        assertRowShows(R.string.gesture_double_tap, R.drawable.ic_block)
        pick(R.string.gesture_long_press, R.string.action_full_screen)
        assertRowShows(R.string.gesture_long_press, R.drawable.ic_fullscreen)
        pick(R.string.keybar_ctrl_c, R.string.keybar_ctrl_c_hold)
        assertRowShows(R.string.keybar_ctrl_c, R.drawable.ic_touch_long)

        // The times are times, not more gears.
        assertRowShows(R.string.gesture_wait, R.drawable.ic_hourglass)
        assertRowShows(R.string.gesture_double_tap_time, R.drawable.ic_timer)
        assertRowShows(R.string.gesture_long_press_time, R.drawable.ic_timer)

        reopen()
        assertRowShows(R.string.gesture_tap, R.drawable.ic_block)
        assertRowShows(R.string.keybar_ctrl_c, R.drawable.ic_touch_long)
    }

    @Test
    fun everyRowOpensAPickerOrAPageSoEveryRowEndsInAChevron() {
        for (opens in listOf(
            R.string.gesture_tap, R.string.gesture_double_tap, R.string.gesture_long_press, R.string.gesture_wait,
            R.string.gesture_double_tap_time, R.string.gesture_long_press_time,
            R.string.keybar_title, R.string.arrange_title, R.string.keybar_ctrl_c,
        )) {
            row(opens)
            assertTrue("${string(opens)} opens a picker or a page", compose.rowHasChevron(string(opens)))
        }
    }

    @Test
    fun aPickerListsEveryChoiceWithItsIconAndWhatItDoesTheCurrentOneSelected() {
        tapRow(R.string.gesture_tap)
        val options = compose.onAllNodes(isSelectable() and hasAnyAncestor(hasTestTag(PICKER_TAG)))
        options.assertCountEquals(TapAction.entries.size)
        for (action in TapAction.entries) {
            val c = choice(action)
            val option = compose.pickerOption(string(c.label))
            option.assert(hasText(string(c.description)))
            option.assert(SemanticsMatcher.expectValue(Drawable, c.icon))
            if (action == TapAction.Keyboard) option.assertIsSelected() else option.assertIsNotSelected()
        }
        // Cancel leaves the setting as it was.
        compose.onNode(hasText(string(R.string.action_cancel)) and hasClickAction())
            .performClick()
        compose.waitForIdle()
        compose.onNode(hasTestTag(PICKER_TAG)).assertDoesNotExist()
        assertRowSays(R.string.gesture_tap, string(R.string.gesture_open_keyboard))
        assertEquals(TapAction.Keyboard, stored().tap)
    }

    @Test
    fun aTimePickedOnTheSliderShowsOnItsRow() {
        val phone = ViewConfiguration.getLongPressTimeout()
        assertRowSays(R.string.gesture_long_press_time, string(R.string.gesture_time_system, phone))
        tapRow(R.string.gesture_long_press_time)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))[0]
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
        pick(R.string.keybar_title, R.string.keybar_always)
        assertRowSays(R.string.keybar_title, string(R.string.keybar_always))
        assertEquals(true, HostStore(compose.activity).keyBarAlways())

        assertRowSays(R.string.keybar_ctrl_c, string(R.string.keybar_ctrl_c_tap))
        pick(R.string.keybar_ctrl_c, R.string.keybar_ctrl_c_hold)
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
