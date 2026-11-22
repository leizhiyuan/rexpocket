package me.leaver.rexpocket.ui

import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.CtrlCKey
import me.leaver.rexpocket.core.DoubleTapAction
import me.leaver.rexpocket.core.LongPressAction
import me.leaver.rexpocket.core.TapAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The icon and words of each choice in Terminal controls: the icon is the action chosen, so it
 * changes with the setting, and one action looks the same on every gesture it can be put on.
 */
class ChoiceTest {
    @Test
    fun eachGestureShowsTheActionItIsSetTo() {
        assertEquals(Choice(R.string.gesture_open_keyboard, R.drawable.ic_keyboard), choice(TapAction.Keyboard))
        assertEquals(Choice(R.string.gesture_toggle_keyboard, R.drawable.ic_keyboard), choice(TapAction.Toggle))
        assertEquals(Choice(R.string.gesture_nothing, R.drawable.ic_block), choice(TapAction.Nothing))

        assertEquals(Choice(R.string.action_full_screen, R.drawable.ic_fullscreen), choice(DoubleTapAction.FullScreen))
        assertEquals(Choice(R.string.gesture_toggle_keyboard, R.drawable.ic_keyboard), choice(DoubleTapAction.Keyboard))
        assertEquals(Choice(R.string.gesture_nothing, R.drawable.ic_block), choice(DoubleTapAction.Nothing))

        assertEquals(Choice(R.string.gesture_select, R.drawable.ic_text_select), choice(LongPressAction.Select))
        assertEquals(Choice(R.string.action_full_screen, R.drawable.ic_fullscreen), choice(LongPressAction.FullScreen))
        assertEquals(Choice(R.string.gesture_toggle_keyboard, R.drawable.ic_keyboard), choice(LongPressAction.Keyboard))
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
        assertEquals(Choice(R.string.keybar_ctrl_c_tap, R.drawable.ic_touch_app), choice(CtrlCKey.Tap))
        assertEquals(Choice(R.string.keybar_ctrl_c_hold, R.drawable.ic_touch_long), choice(CtrlCKey.Hold))
        // Hidden is Arrange keys' business; the row shows it as the tap it steps from.
        assertEquals(choice(CtrlCKey.Tap), choice(CtrlCKey.Hidden))
    }
}
