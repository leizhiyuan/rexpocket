package me.leaver.rexpocket.core

import me.leaver.rexpocket.core.BarKey.Alt
import me.leaver.rexpocket.core.BarKey.Arrows
import me.leaver.rexpocket.core.BarKey.Attach
import me.leaver.rexpocket.core.BarKey.Ctrl
import me.leaver.rexpocket.core.BarKey.CtrlC
import me.leaver.rexpocket.core.BarKey.Enter
import me.leaver.rexpocket.core.BarKey.Escape
import me.leaver.rexpocket.core.BarKey.FullScreen
import me.leaver.rexpocket.core.BarKey.InsertPath
import me.leaver.rexpocket.core.BarKey.Keyboard
import me.leaver.rexpocket.core.BarKey.Paste
import me.leaver.rexpocket.core.BarKey.Shift
import me.leaver.rexpocket.core.BarKey.Slash
import me.leaver.rexpocket.core.BarKey.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyArrangementTest {
    private val default = KeyArrangement()
    private val fn = BarKey.Fn
    private val f10 = BarKey.Desk(DeskKey.F10)
    private val home = BarKey.Desk(DeskKey.Home)

    /** A stored arrangement read back, as the store reads it. */
    private fun KeyArrangement.roundTrip(): KeyArrangement {
        val keys = groups.associate { it.id to storedGroupKeys(it.id) }
        val names = groups.associate { it.id to it.name }
        return KeyArrangement.fromStored(storedOrder(), storedHidden(), storedGroups(), keys::get, names::get)
    }

    @Test
    fun theDefaultIsTodaysBarAndTodaysFnPanel() {
        assertEquals(listOf(Escape, CtrlC, Tab, Enter, Ctrl, fn, Paste, Attach, InsertPath, Slash), default.shown(Pill.Keys))
        assertEquals(listOf(Arrows, Keyboard, FullScreen), default.shown(Pill.Rest))
        val panel = default.group(BarKey.FN_GROUP)!!
        assertEquals("Fn", panel.name)
        assertEquals(
            DeskKey.entries.map { BarKey.Desk(it) } + listOf('|', '~', '`', '\\').map { BarKey.Typed(it) } + Slash +
                listOf('-', '_').map { BarKey.Typed(it) },
            panel.keys,
        )
        assertFalse(default.isShown(Shift))
        assertFalse(default.isShown(Alt))
    }

    @Test
    fun everyKeyHasAStoredNameThatReadsBack() {
        val all = BarKey.catalogue + listOf(fn, BarKey.Group("c1"), BarKey.Group("htop"))
        assertEquals("names are unique", all.size, all.map { it.pref }.toSet().size)
        for (key in all) assertEquals(key.pref, key, BarKey.fromPref(key.pref))
        // The names the last version stored mean the same keys.
        assertEquals(fn, BarKey.fromPref("fn"))
        assertEquals(CtrlC, BarKey.fromPref("ctrl_c"))
        assertEquals(BarKey.Chord('d'), BarKey.fromPref("ctrl_d"))
        assertEquals(Slash, BarKey.fromPref("char_2f"))
        assertNull(BarKey.fromPref("char_41")) // a capital: Shift makes it
        assertNull(BarKey.fromPref("group_Bad,id"))
        assertNull(BarKey.fromPref("warp"))
        assertTrue(BarKey.catalogue.none { it == BarKey.Chord('c') })
    }

    @Test
    fun anFnKeyAddedToThePillGoesAtTheEndAndCanBePlacedAnywhereAndRemoved() {
        val added = default.add(f10)
        assertEquals(default.keys + f10, added.keys)
        assertEquals("Fn keeps its own", default.groups, added.groups)
        assertEquals(added, added.add(f10))
        val placed = added.moveTo(f10, 2)
        assertEquals(listOf(Escape, CtrlC, f10, Tab), placed.keys.take(4))
        assertEquals(listOf(Escape, CtrlC, Tab, f10), placed.move(f10, 1).keys.take(4))
        assertEquals(f10, placed.move(f10, -100).keys.first())
        assertEquals(f10, placed.move(f10, 100).keys.last())
        assertEquals(default.keys, placed.remove(f10).keys)
        assertTrue(placed.isShown(f10))
    }

    @Test
    fun keysGoOnlyWhereTheyBelong() {
        // The right pill's keys stay in it, and nothing else goes there; group keys stay off panels.
        assertEquals(default, default.add(Arrows))
        assertEquals(listOf(Keyboard, Arrows, FullScreen), default.move(Arrows, 1).rest)
        assertEquals(default.keys, default.move(Arrows, -5).keys)
        assertEquals(default, default.addToGroup(BarKey.FN_GROUP, fn))
        assertEquals(default, default.addToGroup(BarKey.FN_GROUP, Keyboard))
        assertEquals(default, default.add(BarKey.Group("nope")))
        assertFalse(default.fits(Arrows, null))
        assertTrue(default.fits(f10, null))
        assertFalse(default.fits(fn, BarKey.FN_GROUP))
        assertTrue(default.catalogue(Pill.Rest).none { it == Keyboard })
        assertTrue(default.catalogue(Pill.Keys, BarKey.FN_GROUP).none { it is BarKey.Group || it.pill == Pill.Rest })
        assertEquals(listOf(Arrows, FullScreen), default.catalogue(Pill.Rest))
    }

    @Test
    fun keysComeOffButTheKeyboardKeyStays() {
        assertEquals(default, default.remove(Keyboard))
        val bare = (default.keys + default.rest).fold(default) { s, k -> s.remove(k) }
        assertEquals(emptyList<BarKey>(), bare.shown(Pill.Keys))
        assertEquals(listOf(Keyboard), bare.shown(Pill.Rest))
        assertEquals(listOf(Keyboard), bare.roundTrip().rest)
    }

    @Test
    fun theFnPanelIsArrangedLikeAPill() {
        val g = BarKey.FN_GROUP
        val a = default.removeFromGroup(g, BarKey.Desk(DeskKey.F1)).moveInGroup(g, f10, -100).addToGroup(g, Escape)
        val keys = a.group(g)!!.keys
        assertEquals(f10, keys.first())
        assertEquals(Escape, keys.last())
        assertFalse(BarKey.Desk(DeskKey.F1) in keys)
        assertEquals(a, a.addToGroup(g, Escape))
        assertEquals(2, a.moveInGroupTo(g, Escape, 2).group(g)!!.keys.indexOf(Escape))
        // A key in a group whose key is on the bar is reachable; take the group's key off and it is not.
        assertTrue(a.isShown(Escape) && a.isShown(home))
        assertFalse(a.remove(fn).isShown(home))
        assertFalse(a.remove(Escape).remove(fn).isShown(Escape))
        // Shift on the panel can be reached, so it is not let go.
        assertTrue(default.addToGroup(g, Shift).isShown(Shift))
    }

    @Test
    fun anEmptyGroupDrawsNoKey() {
        val empty = DeskKey.entries.map { BarKey.Desk(it) }.fold(default) { s, k -> s.removeFromGroup(BarKey.FN_GROUP, k) }
            .let { s -> s.group(BarKey.FN_GROUP)!!.keys.fold(s) { t, k -> t.removeFromGroup(BarKey.FN_GROUP, k) } }
        assertTrue(empty.has(fn))
        assertFalse(fn in empty.shown(Pill.Keys))
        assertTrue(fn in empty.addToGroup(BarKey.FN_GROUP, home).shown(Pill.Keys))
    }

    @Test
    fun groupsAreMadeFromPresetsOrFromNothingRenamedAndDeleted() {
        val htop = default.addPreset(KeyPreset.Htop)
        val key = BarKey.Group("htop")
        assertEquals("after the last group key", listOf(Escape, CtrlC, Tab, Enter, Ctrl, fn, key, Paste), htop.keys.take(8))
        assertEquals(KeyPreset.Htop.keys(), htop.group("htop")!!.keys)
        assertTrue(BarKey.Typed('q') in htop.group("htop")!!.keys && BarKey.Typed(' ') in htop.group("htop")!!.keys)
        assertEquals("a preset made twice is one group", htop, htop.addPreset(KeyPreset.Htop))

        val git = htop.addGroup("  Git   stuff and more ")
        val made = git.group("c1")!!
        assertEquals("Git stuf", made.name)
        assertEquals(emptyList<BarKey>(), made.keys)
        assertEquals(BarKey.Group("c1"), git.keys[7])
        assertEquals(git, git.addGroup("   "))
        assertEquals("c2", git.newGroupId())

        val renamed = git.renameGroup("htop", "top")
        assertEquals("top", renamed.group("htop")!!.name)
        assertEquals(renamed, renamed.renameGroup("htop", " "))

        val gone = renamed.deleteGroup("htop")
        assertNull(gone.group("htop"))
        assertFalse(key in gone.keys)
        // Listed in the bar's order, those off the bar after.
        assertEquals(listOf("fn", "htop", "c1"), git.groupsInOrder().map { it.id })
        assertEquals(listOf("htop", "c1", "fn"), git.remove(fn).groupsInOrder().map { it.id })
        // A deleted Fn comes back from its preset with today's keys.
        assertEquals(default.group("fn"), default.deleteGroup("fn").addPreset(KeyPreset.Fn).group("fn"))
    }

    @Test
    fun thereAreAtMostEightGroups() {
        var a = default
        repeat(20) { a = a.addGroup("g$it") }
        assertEquals(KeyArrangement.MAX_GROUPS, a.groups.size)
        assertFalse(a.canAddGroup)
        assertEquals(a, a.addPreset(KeyPreset.Vim))
        assertEquals(KeyArrangement.MAX_GROUPS, a.roundTrip().groups.size)
    }

    @Test
    fun everyArrangementReadsBack() {
        val a = default.add(f10).moveTo(f10, 1).remove(Tab).add(Alt).move(FullScreen, -2)
            .addPreset(KeyPreset.Vim).addGroup("日本語キーグループ").addToGroup("c1", BarKey.Chord('z'))
            .renameGroup("fn", "More").removeFromGroup("fn", home)
        assertEquals(a, a.roundTrip())
        assertEquals(default, default.roundTrip())
    }

    @Test
    fun withNothingStoredTheOldSettingsDecideWhatShows() {
        assertEquals(default, KeyArrangement.fromStored(null, null))
        val old = KeyArrangement.fromStored(null, null, legacyShift = true, legacyAlt = false, legacyCtrlCHidden = true)
        assertEquals(listOf(Escape, Tab, Enter, Ctrl, Shift, fn, Paste, Attach, InsertPath, Slash), old.keys)
        assertEquals(default.groups, old.groups)
    }

    @Test
    fun theLastVersionsArrangementsGiveTheSameBar() {
        // Stored by the last version: every one of its 14 keys in the order, the hidden ones named.
        val stored = KeyArrangement.fromStored(
            "slash,escape,ctrl_c,tab,enter,ctrl,shift,alt,fn,paste,attach,full_screen,arrows,keyboard",
            "tab,alt",
        )
        // Insert path came after that version: it goes in once, after Attach, as a key added since does.
        assertEquals(listOf(Slash, Escape, CtrlC, Enter, Ctrl, Shift, fn, Paste, Attach, InsertPath), stored.shown(Pill.Keys))
        assertEquals(listOf(FullScreen, Arrows, Keyboard), stored.rest)
        assertEquals("Fn as it was", default.groups, stored.groups)
        // Fn hidden then: off the bar, its panel kept.
        val noFn = KeyArrangement.fromStored(default.storedOrder() + ",shift,alt", "shift,alt,fn")
        assertFalse(noFn.has(fn))
        assertEquals(default.groups, noFn.groups)
        // Nothing hidden: Shift and Alt on, though the old switches said no.
        assertTrue(KeyArrangement.fromStored(BarKey.catalogue.take(13).joinToString(",") { it.pref } + ",fn", "").has(Shift))
    }

    /**
     * Insert path is a key of its own, after Attach. A bar stored before it existed gains it once,
     * after Attach or the key before it there; taken off, it is stored as off and stays off.
     */
    @Test
    fun insertPathReachesEveryBarOnceAndStaysOffWhenTakenOff() {
        val before = "escape,ctrl_c,tab,enter,ctrl,shift,alt,fn,paste,attach,slash,arrows,keyboard,full_screen"
        assertEquals(default, KeyArrangement.fromStored(before, "shift,alt"))
        // Customised: Attach off, keys moved. It goes after the key before it by default that is there.
        val moved = KeyArrangement.fromStored("slash,paste,escape,enter,arrows,keyboard", "ctrl_c,tab,ctrl,shift,alt,fn,attach,full_screen")
        assertEquals(listOf(Slash, Paste, InsertPath, Escape, Enter), moved.keys)
        val off = default.remove(InsertPath)
        assertTrue("insert_path" in off.storedHidden().split(','))
        assertFalse(off.roundTrip().has(InsertPath))
        assertTrue(InsertPath in default.catalogue(Pill.Keys))
        assertTrue("it may sit in a group's panel", InsertPath in default.catalogue(Pill.Keys, BarKey.FN_GROUP))
    }

    @Test
    fun anOlderBuildReadsTheBarItKnows() {
        // What the last version's reader makes of this version's stored keys: it drops the names it
        // does not know and puts back any of its 14 that neither list names. Each of them is named.
        val a = default.add(f10).remove(Escape).addPreset(KeyPreset.Htop)
        val order = a.storedOrder().split(',')
        val hidden = a.storedHidden().split(',')
        for (key in KeyArrangement.BAR_KEYS_SO_FAR) assertTrue(key.pref, key.pref in order || key.pref in hidden)
        assertFalse("escape" in order)
        assertTrue("escape" in hidden)
    }

    @Test
    fun unknownKeysAreDroppedAndMissingOnesComeBackInTheirPlace() {
        // A key from a later build, a duplicate, a key stored in the wrong pill; Ctrl, Attach and the
        // full-screen key missing, as from an older build.
        val a = KeyArrangement.fromStored("slash,escape,warp,escape,ctrl_c,tab,keyboard,enter,shift,alt,fn,paste,arrows", "warp,keyboard,tab")
        assertEquals(listOf(Slash, Escape, CtrlC, Enter, Ctrl, Shift, Alt, fn, Paste, Attach, InsertPath), a.keys)
        assertEquals("the keyboard key cannot be hidden", listOf(Keyboard, FullScreen, Arrows), a.rest)
        assertEquals(Escape, KeyArrangement.fromStored("tab,ctrl_c", "").keys.first())
        // A group key whose group is gone, a group from a later build's ids, keys not allowed in a group.
        val b = KeyArrangement.fromStored(
            "escape,group_gone,fn,group_c1", "", "fn,c1,BAD,c1",
            groupKeys = mapOf("fn" to "f10,fn,keyboard,warp,f10", "c1" to null)::get,
            groupName = mapOf("c1" to "   ")::get,
        )
        assertEquals(listOf("fn", "c1"), b.groups.map { it.id })
        assertEquals(listOf(f10), b.group("fn")!!.keys)
        assertEquals("c1", b.group("c1")!!.name)
        assertFalse(BarKey.Group("gone") in b.keys)
        assertTrue(BarKey.Group("c1") in b.keys)
        // Groups stored without Fn: its key goes.
        assertFalse(KeyArrangement.fromStored(default.storedOrder(), "", "htop").has(fn))
        // A preset stored without its keys has the preset's.
        assertEquals(KeyPreset.Tmux.keys(), KeyArrangement.fromStored(null, null, "tmux").group("tmux")!!.keys)
    }

    @Test
    fun resetIsTheDefault() {
        val a = default.move(Ctrl, -4).remove(Escape).addPreset(KeyPreset.Tmux)
        assertEquals(default, KeyArrangement())
        assertTrue(a != KeyArrangement())
    }

    @Test
    fun presetsHoldOnlyKeysAGroupTakes() {
        for (p in KeyPreset.entries) {
            val keys = p.keys()
            assertTrue(p.name, keys.all { it.inGroups && BarKey.fromPref(it.pref) == it })
            assertEquals(p.name, keys.size, keys.toSet().size)
        }
    }
}
