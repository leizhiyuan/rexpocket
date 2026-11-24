package me.leaver.rexpocket.ui

import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.CtrlCKey
import me.leaver.rexpocket.core.DoubleTapAction
import me.leaver.rexpocket.core.LongPressAction
import me.leaver.rexpocket.core.TapAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import me.leaver.rexpocket.core.AppTheme
import me.leaver.rexpocket.core.TerminalColors
import me.leaver.rexpocket.core.TextSize
import me.leaver.rexpocket.core.UpdateChannel
import org.junit.Test

/**
 * The icon and words of each choice in Terminal controls: the icon is the action chosen, so it
 * changes with the setting, and one action looks the same on every gesture it can be put on.
 */
class ChoiceTest {
    /** What a row shows for a choice: its words and its icon. */
    private fun looks(c: Choice) = c.label to c.icon

    @Test
    fun everyChoiceSaysWhatItDoes() {
        val all = TapAction.entries.map(::choice) + DoubleTapAction.entries.map(::choice) +
            LongPressAction.entries.map(::choice) + CtrlCKey.entries.map(::choice) +
            ON_OFF.map(::waitChoice) + ON_OFF.map(::keyBarChoice) + ON_OFF.map(::reopenChoice) +
            UpdateChannel.entries.map(::channelChoice) + AppTheme.entries.map(::choice) + TerminalColors.entries.map(::choice) +
            TextSize.entries.map(::choice)
        for (c in all) assertNotEquals("description of $c", 0, c.description)
        // Within one setting, no two choices read alike in its picker.
        for (setting in listOf(AppTheme.entries.map(::choice), TerminalColors.entries.map(::choice), TextSize.entries.map(::choice), TapAction.entries.map(::choice))) {
            assertEquals(setting.size, setting.map { it.description }.distinct().size)
        }
    }

    @Test
    fun eachGestureShowsTheActionItIsSetTo() {
        assertEquals(R.string.gesture_open_keyboard to R.drawable.ic_keyboard, looks(choice(TapAction.Keyboard)))
        assertEquals(R.string.gesture_toggle_keyboard to R.drawable.ic_keyboard, looks(choice(TapAction.Toggle)))
        assertEquals(R.string.gesture_nothing to R.drawable.ic_block, looks(choice(TapAction.Nothing)))

        assertEquals(R.string.action_full_screen to R.drawable.ic_fullscreen, looks(choice(DoubleTapAction.FullScreen)))
        assertEquals(R.string.gesture_toggle_keyboard to R.drawable.ic_keyboard, looks(choice(DoubleTapAction.Keyboard)))
        assertEquals(R.string.gesture_nothing to R.drawable.ic_block, looks(choice(DoubleTapAction.Nothing)))

        assertEquals(R.string.gesture_select to R.drawable.ic_text_select, looks(choice(LongPressAction.Select)))
        assertEquals(R.string.action_full_screen to R.drawable.ic_fullscreen, looks(choice(LongPressAction.FullScreen)))
        assertEquals(R.string.gesture_toggle_keyboard to R.drawable.ic_keyboard, looks(choice(LongPressAction.Keyboard)))
    }

    @Test
    fun anActionLooksTheSameOnEveryGesture() {
        // By label: every choice that says the same thing has the same icon, whichever row it is on.
        val all = TapAction.entries.map(::choice) + DoubleTapAction.entries.map(::choice) + LongPressAction.entries.map(::choice)
        for ((label, choices) in all.groupBy { it.label }) {
            assertEquals("icons for ${choices.first()}", 1, choices.map { it.icon }.distinct().size)
            assertNotEquals(0, label)
        }
    }

    @Test
    fun aGestureRowsIconChangesWhenItsSettingDoes() {
        // Unlike the static icons before: no gesture row shows one icon for all its choices.
        assertNotEquals(1, TapAction.entries.map { choice(it).icon }.distinct().size)
        assertEquals(DoubleTapAction.entries.size, DoubleTapAction.entries.map { choice(it).icon }.distinct().size)
        assertEquals(LongPressAction.entries.size, LongPressAction.entries.map { choice(it).icon }.distinct().size)
    }

    @Test
    fun theCtrlCKeyIsATapOrAHold() {
        assertEquals(R.string.keybar_ctrl_c_tap to R.drawable.ic_touch_app, looks(choice(CtrlCKey.Tap)))
        assertEquals(R.string.keybar_ctrl_c_hold to R.drawable.ic_touch_long, looks(choice(CtrlCKey.Hold)))
        // Hidden is Arrange keys' business; the row shows it as the tap it steps from.
        assertEquals(choice(CtrlCKey.Tap), choice(CtrlCKey.Hidden))
    }
}
