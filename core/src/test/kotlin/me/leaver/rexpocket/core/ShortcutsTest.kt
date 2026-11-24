package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which keys of a desk keyboard the terminal screen takes, and that the program keeps the rest. */
class ShortcutsTest {
    private fun of(key: ShortcutKey) = appShortcut(
        key.keyCode,
        ctrl = key.meta and META_CTRL_ON != 0,
        shift = key.meta and META_SHIFT_ON != 0,
        alt = key.meta and META_ALT_ON != 0,
        meta = key.meta and META_META_ON != 0,
    )

    @Test
    fun everyListedKeyDoesWhatItIsListedFor() {
        assertEquals(AppShortcut.entries.toSet(), SHORTCUT_KEYS.map { it.first }.toSet())
        for ((shortcut, keys) in SHORTCUT_KEYS) for (key in keys) assertEquals("$key", shortcut, of(key))
    }

    @Test
    fun theChordsTheTablePromises() {
        assertEquals(AppShortcut.Find, appShortcut(KeyCodes.F, ctrl = true, shift = true, alt = false, meta = false))
        assertEquals(AppShortcut.Help, appShortcut(KeyCodes.SLASH, ctrl = true, shift = true, alt = false, meta = false))
        assertEquals(AppShortcut.PreviousTab, appShortcut(KeyCodes.TAB, ctrl = true, shift = true, alt = false, meta = false))
        assertEquals(AppShortcut.FontBigger, appShortcut(KeyCodes.EQUALS, ctrl = true, shift = true, alt = false, meta = false))
        assertEquals(AppShortcut.FontBigger, appShortcut(KeyCodes.NUMPAD_ADD, ctrl = true, shift = false, alt = false, meta = false))
        assertEquals(AppShortcut.FontReset, appShortcut(KeyCodes.NUMPAD_0, ctrl = true, shift = false, alt = false, meta = false))
    }

    @Test
    fun theProgramKeepsItsKeys() {
        val letters = 29..54
        for (code in letters) {
            // Ctrl+letter is a control character; Alt+letter is ESC and the letter; both are the program's.
            assertNull("C-$code", appShortcut(code, ctrl = true, shift = false, alt = false, meta = false))
            assertNull("M-$code", appShortcut(code, ctrl = false, shift = false, alt = true, meta = false))
            assertNull("C-M-S-$code", appShortcut(code, ctrl = true, shift = true, alt = true, meta = false))
            assertNull("plain $code", appShortcut(code, ctrl = false, shift = false, alt = false, meta = false))
            assertNull("shifted $code", appShortcut(code, ctrl = false, shift = true, alt = false, meta = false))
        }
        // C-_ (undo in a shell) is Ctrl+Shift+- on a keyboard.
        assertNull(appShortcut(KeyCodes.MINUS, ctrl = true, shift = true, alt = false, meta = false))
        // The keys a desk keyboard has that a phone's does not, with Ctrl or Shift.
        val desk = listOf(92, 93, 122, 123, 124, 112, 111, 19, 20, 21, 22) + (131..142)
        for (code in desk) for (ctrl in listOf(false, true)) for (shift in listOf(false, true)) {
            assertNull("$code ctrl=$ctrl shift=$shift", appShortcut(code, ctrl, shift, alt = false, meta = false))
        }
        assertNull("Ctrl+Shift+Z", appShortcut(54, ctrl = true, shift = true, alt = false, meta = false))
        assertNull("Meta+Shift+F", appShortcut(KeyCodes.F, ctrl = false, shift = true, alt = false, meta = true))
        assertNull("Ctrl+Meta+F", appShortcut(KeyCodes.F, ctrl = true, shift = false, alt = false, meta = true))
    }

    @Test
    fun onlyTheFontRepeatsWhileHeld() {
        assertEquals(setOf(AppShortcut.FontBigger, AppShortcut.FontSmaller), AppShortcut.entries.filter { it.repeats }.toSet())
    }
}
