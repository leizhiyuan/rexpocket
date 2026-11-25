package me.leaver.rexpocket.ui.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Help's registry holds together: ids are unique, every section has entries, every spot is pointed
 * at by an entry or a tour step (or is a row that opens Help), and the tour runs by itself only on a
 * new install. That every entry's control is where it says is GuideRegistryTest's, on the screens.
 */
class GuideEntriesTest {
    @Test
    fun idsAreUnique() {
        val dup = GUIDE_ENTRIES.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue("ids used twice: $dup", dup.isEmpty())
    }

    @Test
    fun everySectionHasEntries() {
        val empty = GuideSection.entries.filter { s -> GUIDE_ENTRIES.none { it.section == s } }
        assertTrue("sections with no entry: $empty", empty.isEmpty())
    }

    @Test
    fun everySpotIsPointedAt() {
        val used = GUIDE_ENTRIES.map { it.spot } + TOUR_STEPS.map { it.spot } + GUIDE_ENTRY_POINTS
        val unused = GuideSpot.entries - used.toSet()
        assertTrue("spots nothing points at (drop the spot and its guideTarget, or add an entry): $unused", unused.isEmpty())
    }

    @Test
    fun everyEntryHasItsOwnStrings() {
        val strings = GUIDE_ENTRIES.flatMap { listOf(it.title, it.hint, it.body, it.keywords) }
        assertEquals("an entry shares a string with another", strings.size, strings.toSet().size)
    }

    @Test
    fun gesturesAndLongPressesAreListed() {
        val gestures = GUIDE_ENTRIES.filter { it.section == GuideSection.Gestures }.map { it.id }.toSet()
        for (id in listOf("pinch", "swipe_panes", "long_press_select", "double_tap", "hold_return", "hold_ctrl")) {
            assertTrue("$id is not among the gestures", id in gestures)
        }
    }

    @Test
    fun theTourRunsByItselfOnlyOnANewInstallOnceItHasAHost() {
        // The first start records what it found and runs nothing.
        assertEquals(false to INSTALL_NEW, GuidePrefs.tourDue(null, hostsNow = false, done = false))
        assertEquals(false to INSTALL_OLD, GuidePrefs.tourDue(null, hostsNow = true, done = false))
        // A new install: due once it starts with a host, until it ran.
        assertEquals(false to null, GuidePrefs.tourDue(INSTALL_NEW, hostsNow = false, done = false))
        assertEquals(true to null, GuidePrefs.tourDue(INSTALL_NEW, hostsNow = true, done = false))
        assertEquals(false to null, GuidePrefs.tourDue(INSTALL_NEW, hostsNow = true, done = true))
        // An install that had hosts when Help came: never by itself.
        assertEquals(false to null, GuidePrefs.tourDue(INSTALL_OLD, hostsNow = true, done = false))
    }
}
