package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyModifiersTest {
    @Test
    fun aTapArmsAModifierForOneKey() {
        val armed = KeyModifiers().tap(Mod.Ctrl, now = 1_000)
        assertEquals(Latch.Once, armed[Mod.Ctrl])
        assertEquals("ctrl", armed.mods())
        val used = armed.consume()
        assertEquals(Latch.Off, used[Mod.Ctrl])
        assertEquals("", used.mods())
    }

    @Test
    fun aSecondTapSoonAfterLocksAndALaterTapUnlocks() {
        val locked = KeyModifiers().tap(Mod.Shift, now = 1_000).tap(Mod.Shift, now = 1_200)
        assertEquals(Latch.Locked, locked[Mod.Shift])
        assertEquals(Latch.Locked, locked.consume()[Mod.Shift])
        assertEquals(Latch.Off, locked.tap(Mod.Shift, now = 5_000)[Mod.Shift])
    }

    @Test
    fun aSlowSecondTapDisarms() {
        val off = KeyModifiers().tap(Mod.Alt, now = 1_000).tap(Mod.Alt, now = 2_000)
        assertEquals(Latch.Off, off[Mod.Alt])
    }

    @Test
    fun modifiersAreJoinedInRexOrder() {
        val all = KeyModifiers().tap(Mod.Alt, now = 1).tap(Mod.Ctrl, now = 1).tap(Mod.Shift, now = 1)
        assertEquals("shift+ctrl+alt", all.mods())
        // A key that carries its own modifier, such as a C-r shortcut, adds to the armed ones once.
        assertEquals("ctrl+alt", KeyModifiers().tap(Mod.Alt, now = 1).mods("ctrl"))
        assertEquals("ctrl", KeyModifiers().tap(Mod.Ctrl, now = 1).mods("ctrl"))
    }

    @Test
    fun consumingKeepsLockedModifiers() {
        val mixed = KeyModifiers().tap(Mod.Ctrl, now = 1).tap(Mod.Ctrl, now = 2).tap(Mod.Shift, now = 3)
        assertEquals("ctrl", mixed.consume().mods())
    }

    @Test
    fun typedCharactersBecomePhysicalKeys() {
        assertEquals(KeyPress("key_r", 'r'.code, text = "r"), keyPressFor("r"))
        assertEquals(KeyPress("key_r", 'r'.code, shifted = true, text = "R"), keyPressFor("R"))
        assertEquals(KeyPress("digit_4", '4'.code, text = "4"), keyPressFor("4"))
        assertEquals(KeyPress("enter", '\r'.code), keyPressFor("\r"))
        assertEquals(KeyPress("space", ' '.code, text = " "), keyPressFor(" "))
        assertEquals(KeyPress("slash", '/'.code, text = "/"), keyPressFor("/"))
        assertEquals(KeyPress("backspace", 0x7f), keyPressFor("\u007f"))
    }

    // Rex makes a printable character only from the key's text: a letter sent without it, even with
    // shift held, reaches the program as nothing at all.
    @Test
    fun aPrintableKeyCarriesTheCharacterItMakes() {
        val none = KeyModifiers()
        assertEquals(KeyEvent("slash", "", "", "/", '/'.code), none.event(keyPressFor("/")!!))
        val shift = KeyModifiers().tap(Mod.Shift, now = 1)
        assertEquals(KeyEvent("key_x", "shift", "shift", "X", 'x'.code), shift.event(keyPressFor("x")!!))
        assertEquals(KeyEvent("digit_4", "shift", "shift", "$", '4'.code), shift.event(keyPressFor("4")!!))
        // A capital from the keyboard is shift on its own.
        assertEquals(KeyEvent("key_x", "shift", "shift", "X", 'x'.code), none.event(keyPressFor("X")!!))
    }

    @Test
    fun controlAndAltKeepTheUnshiftedCharacter() {
        val ctrl = KeyModifiers().tap(Mod.Ctrl, now = 1)
        assertEquals(KeyEvent("key_r", "ctrl", "", "r", 'r'.code), ctrl.event(keyPressFor("r")!!))
        val alt = KeyModifiers().tap(Mod.Alt, now = 1)
        assertEquals(KeyEvent("key_b", "alt", "", "b", 'b'.code), alt.event(keyPressFor("b")!!))
        // A chord from the ctrl grid brings its own ctrl.
        assertEquals(KeyEvent("key_c", "ctrl", "", "c", 'c'.code), KeyModifiers().event(keyPressFor("c")!!, own = "ctrl"))
    }

    @Test
    fun keysWithoutACharacterSendNoText() {
        val shift = KeyModifiers().tap(Mod.Shift, now = 1)
        assertEquals(KeyEvent("tab", "shift", "", "", 0), shift.event(KeyPress("tab", 0)))
        assertEquals(KeyEvent("enter", "shift", "", "", '\r'.code), shift.event(keyPressFor("\r")!!))
    }

    @Test
    fun textThatIsNotOneKeyStaysText() {
        assertNull(keyPressFor("ab"))
        assertNull(keyPressFor("?"))
        assertNull(keyPressFor("中"))
        assertNull(keyPressFor("\u001b[A"))
        assertNull(keyPressFor(""))
    }

    @Test
    fun onlyWebLinksAreOpened() {
        assertEquals(true, isWebLink("https://github.com/leizhiyuan/rexpocket/pull/1"))
        assertEquals(true, isWebLink("HTTP://example.com"))
        assertEquals(false, isWebLink("file:///etc/passwd"))
        assertEquals(false, isWebLink("javascript:alert(1)"))
        assertEquals(false, isWebLink("https://"))
        assertEquals(false, isWebLink("not a link"))
    }
}
