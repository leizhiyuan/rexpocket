package me.leaver.rexpocket.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import me.leaver.rexpocket.core.BarKey
import me.leaver.rexpocket.core.DeskKey
import me.leaver.rexpocket.core.KeyArrangement
import org.junit.Test

/**
 * How the key bar shares its width (keyBarWidths): the bar's width is the screen's less its 12 dp
 * margins; 9 keys is the default key pill (Esc, C-c, Tab, Return, Ctrl, Fn, Paste, Attach, `/`).
 */
class KeyBarLayoutTest {
    private fun bar(screen: Float, keys: Int = 9, restKeys: Int = 3) = keyBarWidths(screen - 24f, keys, restKeys)

    /** What the key pill shows of the key past its last whole one: its keys start after a 4 dp pad. */
    private fun KeyBarWidths.keyHint() = (keys - 4f) % 48f

    /** What the small pill shows of the full-screen key, past its pad, the stick and the keyboard key. */
    private fun KeyBarWidths.fullScreenShown() = rest - 100f

    @Test
    fun aLandscapePhoneShowsEveryKeyAndTheFullScreenKeyWhole() {
        val w = bar(891f)
        assertTrue(w.restWhole)
        assertEquals(152f, w.rest)
        assertTrue(w.keys >= 9 * 48f + 8f)
    }

    @Test
    fun aPortraitPhoneWithRoomShowsTheFullScreenKeyWholeAndAHalfKeyHint() {
        // 411 dp: the key pill shows 4 keys and 31 dp of the fifth.
        val w = bar(411f)
        assertTrue(w.restWhole)
        assertEquals(152f, w.rest)
        assertEquals(227f, w.keys)
        assertEquals(31f, w.keyHint())
        // 360 dp: 3 keys and half the fourth, the full-screen key still whole.
        val small = bar(360f)
        assertTrue(small.restWhole)
        assertEquals(176f, small.keys)
    }

    @Test
    fun whenTheKeyPillWouldShowNoHintTheFullScreenKeyGivesWay() {
        // 393 dp: whole, the key pill would show only 13 dp past its 4th key; it takes 7 dp more
        // and the full-screen key shows 45 dp of its 48.
        val w = bar(393f)
        assertFalse(w.restWhole)
        assertEquals(20f, w.keyHint())
        assertEquals(145f, w.rest)
        assertEquals(45f, w.fullScreenShown())
        // 426 dp: whole, the 5th key would show 46 dp, all but a sliver; the key pill takes a part
        // of the 6th instead, and the full-screen key shows 30 dp.
        val wide = bar(426f)
        assertFalse(wide.restWhole)
        assertEquals(20f, wide.keyHint())
        assertEquals(130f, wide.rest)
        assertEquals(30f, wide.fullScreenShown())
    }

    @Test
    fun atEveryWidthBothPillsShowTheirHintAndFillTheBar() {
        // Up to 40: the bar's own keys, and many added from Fn and the catalogue.
        for (keys in (8..40)) {
            var screen = 300f
            while (screen <= 1400f) {
                val w = bar(screen, keys)
                val room = screen - 24f - 8f
                assertEquals("$screen", room, w.keys + w.rest, 0.01f)
                val everything = keys * 48f + 8f + 152f <= room
                if (everything) {
                    assertTrue("$screen", w.restWhole)
                } else {
                    assertTrue("$screen: key hint ${w.keyHint()}", w.keyHint() in PEEK_MIN..PEEK_MAX)
                    val shown = w.fullScreenShown()
                    if (w.restWhole) {
                        assertEquals("$screen", 152f, w.rest)
                    } else {
                        assertTrue("$screen: full-screen hint $shown", shown > PEEK_MIN && shown < 52f)
                    }
                }
                screen += 0.5f
            }
        }
    }

    @Test
    fun aSmallPillOfFewerKeysStaysWholeAndTheKeyPillStillHints() {
        // The full-screen key hidden: the stick and the keyboard key, never scrolled away.
        val w = bar(393f, restKeys = 2)
        assertEquals(104f, w.rest)
        assertTrue(w.keyHint() in PEEK_MIN..PEEK_MAX)
        assertTrue(w.restWhole)
        // Only the keyboard key left there.
        val one = bar(411f, restKeys = 1)
        assertEquals(56f, one.rest)
        assertTrue(one.keyHint() in PEEK_MIN..PEEK_MAX)
    }

    @Test
    fun aKeyPillWithNoKeysLeavesTheSmallPillWhole() {
        val w = bar(360f, keys = 0)
        assertTrue(w.restWhole)
        assertEquals(152f, w.rest)
    }

    @Test
    fun fnsPanelByDefaultHasTheRowsItAlwaysHad() {
        val rows = panelRows(KeyArrangement().group(BarKey.FN_GROUP)!!.keys)
        assertEquals(listOf(6, 6, 6, 7), rows.map { it.keys.size })
        assertEquals(listOf(6, 6, 6, 7), rows.map { it.slots })
        assertEquals(BarKey.Desk(DeskKey.F1), rows[1].keys.first())
        assertEquals(BarKey.Desk(DeskKey.F7), rows[2].keys.first())
        assertEquals(MORE_SYMBOLS.toList(), rows[3].keys.map { if (it == BarKey.Slash) '/' else (it as BarKey.Typed).char })
    }

    @Test
    fun panelRowsFlowInTheOrderGiven() {
        val f = DeskKey.entries.map { BarKey.Desk(it) }
        // A wide key among symbols makes their row one of six; a short last row keeps the keys' width.
        val rows = panelRows(listOf(BarKey.Typed('q'), BarKey.Typed(':')) + f.take(5) + BarKey.Arrow("arrow_up"))
        assertEquals(listOf(6, 2), rows.map { it.keys.size })
        assertEquals(listOf(6, 6), rows.map { it.slots })
        val narrow = panelRows("abcdefghij".map { BarKey.Typed(it) })
        assertEquals(listOf(7, 3), narrow.map { it.keys.size })
        assertEquals(listOf(7, 7), narrow.map { it.slots })
        assertEquals(emptyList<PanelRow>(), panelRows(emptyList()))
    }
}
