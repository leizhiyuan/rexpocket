package me.leaver.rexpocket.core

import me.leaver.rexpocket.core.BarKey.Alt
import me.leaver.rexpocket.core.BarKey.Arrows
import me.leaver.rexpocket.core.BarKey.Attach
import me.leaver.rexpocket.core.BarKey.Ctrl
import me.leaver.rexpocket.core.BarKey.CtrlC
import me.leaver.rexpocket.core.BarKey.Enter
import me.leaver.rexpocket.core.BarKey.Escape
import me.leaver.rexpocket.core.BarKey.FullScreen
import me.leaver.rexpocket.core.BarKey.Keyboard
import me.leaver.rexpocket.core.BarKey.More
import me.leaver.rexpocket.core.BarKey.Paste
import me.leaver.rexpocket.core.BarKey.Shift
import me.leaver.rexpocket.core.BarKey.Slash
import me.leaver.rexpocket.core.BarKey.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyArrangementTest {
    private val default = KeyArrangement()

    @Test
    fun theDefaultIsTodaysBar() {
        assertEquals(listOf(Escape, CtrlC, Tab, Enter, Ctrl, More, Paste, Attach, Slash), default.shown(Pill.Keys))
        assertEquals(listOf(Arrows, Keyboard, FullScreen), default.shown(Pill.Rest))
        assertEquals(setOf(Shift, Alt), default.hidden)
    }

    @Test
    fun keysMoveWithinTheirPillAndStayInBounds() {
        val moved = default.move(Paste, -7)
        assertEquals(listOf(Escape, Paste, CtrlC, Tab, Enter, Ctrl, Shift, Alt, More, Attach, Slash), moved.keys)
        assertEquals(Paste, default.move(Paste, -100).keys.first())
        assertEquals(Escape, default.move(Escape, 100).keys.last())
        assertEquals(listOf(Keyboard, Arrows, FullScreen), default.move(Arrows, 1).rest)
        assertEquals(listOf(FullScreen, Arrows, Keyboard), default.moveTo(FullScreen, 0).rest)
        // A move never takes a key out of its pill.
        assertEquals(default.keys, default.move(Arrows, -5).keys)
    }

    @Test
    fun keysHideAndShowButTheKeyboardKeyStays() {
        val a = default.show(Escape, false).show(Shift, true)
        assertFalse(a.isShown(Escape))
        assertTrue(Shift in a.shown(Pill.Keys))
        assertEquals(default, default.show(Keyboard, false))
        // Everything that can go, gone: the keyboard key is left.
        val bare = BarKey.entries.fold(default) { s, k -> s.show(k, false) }
        assertEquals(emptyList<BarKey>(), bare.shown(Pill.Keys))
        assertEquals(listOf(Keyboard), bare.shown(Pill.Rest))
    }

    @Test
    fun storedArrangementsReadBack() {
        val a = default.move(Slash, -10).move(FullScreen, -2).show(Tab, false).show(Alt, true)
        assertEquals(a, KeyArrangement.fromStored(a.storedOrder(), a.storedHidden()))
        // Stored with nothing hidden: Shift and Alt stay shown, though the legacy keys say no.
        val none = BarKey.entries.fold(default) { s, k -> s.show(k, true) }
        assertEquals(none, KeyArrangement.fromStored(none.storedOrder(), none.storedHidden()))
    }

    @Test
    fun withNothingStoredTheOldSettingsDecideWhatShows() {
        assertEquals(default, KeyArrangement.fromStored(null, null))
        val old = KeyArrangement.fromStored(null, null, legacyShift = true, legacyAlt = false, legacyCtrlCHidden = true)
        assertEquals(setOf(Alt, CtrlC), old.hidden)
        assertEquals(default.keys, old.keys)
        assertEquals(listOf(Escape, Tab, Enter, Ctrl, Shift, More, Paste, Attach, Slash), old.shown(Pill.Keys))
    }

    @Test
    fun unknownKeysAreDroppedAndMissingOnesComeBackInTheirPlace() {
        // A key from a later build, a duplicate, a key stored in the wrong place; Ctrl, Attach and
        // the full-screen key missing, as from an older build.
        val a = KeyArrangement.fromStored("slash,escape,warp,escape,ctrl_c,tab,keyboard,enter,shift,alt,fn,paste,arrows", "warp,keyboard,tab")
        assertEquals(listOf(Slash, Escape, CtrlC, Tab, Enter, Ctrl, Shift, Alt, More, Paste, Attach), a.keys)
        // The full-screen key goes back after the key before it by default: the keyboard key.
        assertEquals(listOf(Keyboard, FullScreen, Arrows), a.rest)
        assertEquals(setOf(Tab), a.hidden)
        // A first key missing goes first.
        assertEquals(Escape, KeyArrangement.fromStored("tab,ctrl_c", "").keys.first())
    }

    @Test
    fun resetIsTheDefault() {
        val a = default.move(Ctrl, -4).show(Escape, false)
        assertEquals(default, KeyArrangement())
        assertTrue(a != KeyArrangement())
    }
}
