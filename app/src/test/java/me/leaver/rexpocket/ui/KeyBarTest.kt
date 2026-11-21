package me.leaver.rexpocket.ui

import android.view.KeyEvent
import me.leaver.rexpocket.core.DeskKey
import me.leaver.rexpocket.core.META_ALT_ON
import me.leaver.rexpocket.core.META_CTRL_ON
import me.leaver.rexpocket.core.META_SHIFT_ON
import me.leaver.rexpocket.core.symbolPress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The Fn panel's keys go to the pane's terminal as Android key events, which the native shim maps
 * to libghostty's keys: the codes and flags the core writes down must be the platform's own.
 */
class KeyBarTest {
    @Test
    fun deskKeysAreAndroidsKeyCodes() {
        val expected = mapOf(
            DeskKey.Home to KeyEvent.KEYCODE_MOVE_HOME,
            DeskKey.End to KeyEvent.KEYCODE_MOVE_END,
            DeskKey.PageUp to KeyEvent.KEYCODE_PAGE_UP,
            DeskKey.PageDown to KeyEvent.KEYCODE_PAGE_DOWN,
            DeskKey.Insert to KeyEvent.KEYCODE_INSERT,
            DeskKey.Delete to KeyEvent.KEYCODE_FORWARD_DEL,
            DeskKey.F1 to KeyEvent.KEYCODE_F1,
            DeskKey.F12 to KeyEvent.KEYCODE_F12,
        )
        for ((key, code) in expected) assertEquals(key.name, code, key.keyCode)
    }

    @Test
    fun modifierFlagsAreAndroidsMetaState() {
        assertEquals(KeyEvent.META_SHIFT_ON, META_SHIFT_ON)
        assertEquals(KeyEvent.META_ALT_ON, META_ALT_ON)
        assertEquals(KeyEvent.META_CTRL_ON, META_CTRL_ON)
    }

    @Test
    fun everyPanelSymbolHasAKey() {
        for (symbol in MORE_SYMBOLS) assertNotNull("$symbol", symbolPress(symbol))
    }
}
