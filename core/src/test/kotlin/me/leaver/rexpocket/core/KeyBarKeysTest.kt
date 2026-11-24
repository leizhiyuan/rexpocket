package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyBarKeysTest {
    @Test
    fun plainSymbolsAreTheirOwnKey() {
        for ((symbol, code) in listOf('/' to "slash", '\\' to "backslash", '`' to "backquote", '-' to "minus")) {
            val event = KeyModifiers().event(symbolPress(symbol)!!)
            assertEquals(code, event.code)
            assertEquals("", event.mods)
            assertEquals(symbol.toString(), event.text)
            assertEquals(symbol.code, event.codepoint)
        }
    }

    @Test
    fun shiftedSymbolsAreTheirKeyWithShiftUsedForTheText() {
        for ((symbol, code) in listOf('|' to "backslash", '~' to "backquote", '_' to "minus")) {
            val event = KeyModifiers().event(symbolPress(symbol)!!)
            assertEquals(code, event.code)
            assertEquals("shift", event.mods)
            assertEquals("shift", event.consumedMods)
            assertEquals(symbol.toString(), event.text)
        }
    }

    @Test
    fun aHeldModifierReachesASymbol() {
        val alt = KeyModifiers().tap(Mod.Alt, 1_000)
        val event = alt.event(symbolPress('|')!!)
        assertEquals("shift+alt", event.mods)
        assertEquals("|", event.text)
    }

    @Test
    fun lettersAndUnknownSymbolsAreNotSymbolKeys() {
        assertNull(symbolPress(' '))
        assertNull(symbolPress('€'))
        assertEquals("key_a", keyPressFor("a")!!.code)
    }

    @Test
    fun heldModifiersBecomeAndroidMetaState() {
        assertEquals(0, KeyModifiers().metaState())
        val all = KeyModifiers().tap(Mod.Shift, 1_000).tap(Mod.Ctrl, 1_000).tap(Mod.Alt, 1_000)
        assertEquals(META_SHIFT_ON or META_ALT_ON or META_CTRL_ON, all.metaState())
        assertEquals(META_CTRL_ON, KeyModifiers().tap(Mod.Ctrl, 1_000).metaState())
        assertEquals(0, KeyModifiers().tap(Mod.Ctrl, 1_000).consume().metaState())
    }

    @Test
    fun functionKeysRunInOrder() {
        val f = DeskKey.entries.filter { it.name.startsWith("F") }
        assertEquals(12, f.size)
        f.forEachIndexed { i, key ->
            assertEquals("F${i + 1}", key.name)
            assertEquals(131 + i, key.keyCode)
        }
    }

    @Test
    fun ctrlCKeyStepsThroughItsChoicesAndDefaultsToATap() {
        assertEquals(CtrlCKey.Tap, CtrlCKey.fromPref(null))
        assertEquals(CtrlCKey.Tap, CtrlCKey.fromPref("nonsense"))
        for (choice in CtrlCKey.entries) assertEquals(choice, CtrlCKey.fromPref(choice.pref))
        assertEquals(CtrlCKey.Hold, CtrlCKey.Tap.next())
        assertEquals(CtrlCKey.Hidden, CtrlCKey.Hold.next())
        assertEquals(CtrlCKey.Tap, CtrlCKey.Hidden.next())
    }

    @Test
    fun aHeldCtrlCKeyStillStopsABusyProgramWithATap() {
        assertTrue(CtrlCKey.Tap.tapSends(busy = false))
        assertFalse(CtrlCKey.Hold.tapSends(busy = false))
        assertTrue(CtrlCKey.Hold.tapSends(busy = true))
    }
}
