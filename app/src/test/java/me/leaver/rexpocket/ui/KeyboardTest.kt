package me.leaver.rexpocket.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two keyboards a terminal lives with. The pane is moved for the screen keyboard and never
 * resized for it - a program takes a resize as a window change and redraws everything - and a
 * keyboard on the desk sends its keys to the pane without the screen's bar in the way.
 */
class KeyboardTest {
    @Test
    fun `a closed keyboard moves nothing`() {
        assertEquals(0f, imeShift(imePx = 0, paneHeightPx = 1000), 0.01f)
    }

    @Test
    fun `the pane moves by the keyboard, so the bottom of the terminal stays above it`() {
        // What has to stay on screen is the last row of the grid, where a program draws the line
        // being typed into - not the cursor, which a full-screen program hides.
        assertEquals(400f, imeShift(imePx = 400, paneHeightPx = 1000), 0.01f)
    }

    @Test
    fun `a keyboard taller than the pane takes the whole pane`() {
        assertEquals(1000f, imeShift(imePx = 1400, paneHeightPx = 1000), 0.01f)
    }

    @Test
    fun `a pane that has not been laid out yet is left alone`() {
        assertEquals(0f, imeShift(imePx = 400, paneHeightPx = 0), 0.01f)
    }

    // A pane 1000 px tall with 50 px rows, and a keyboard that covers 400 px of it: eight rows.

    @Test
    fun `a new shell's prompt on the top row keeps the pane where it is`() {
        // The prompt is on row 0: sliding the pane would take it under the top bar, out of reach of
        // any scrolling, since it is the live screen and not history. What the keyboard covers is
        // the empty rows under it.
        assertEquals(0f, imeShift(imePx = 400, paneHeightPx = 1000, cursorTopPx = 0), 0.01f)
    }

    @Test
    fun `a cursor a few rows down lets the pane slide only as far as its row`() {
        assertEquals(150f, imeShift(imePx = 400, paneHeightPx = 1000, cursorTopPx = 150), 0.01f)
    }

    @Test
    fun `a cursor at the bottom slides the pane by the whole keyboard, as before`() {
        // A shell after some output, or an agent's composer: the bottom of the grid stays above
        // the keyboard, and the cursor is in it.
        assertEquals(400f, imeShift(imePx = 400, paneHeightPx = 1000, cursorTopPx = 950), 0.01f)
        assertEquals(400f, imeShift(imePx = 400, paneHeightPx = 1000, cursorTopPx = 400), 0.01f)
    }

    @Test
    fun `with no cursor shown the bottom of the grid is what stays in view`() {
        assertEquals(400f, imeShift(imePx = 400, paneHeightPx = 1000, cursorTopPx = null), 0.01f)
    }

    @Test
    fun `a short screen that ends above the keyboard is not moved`() {
        // The owner's report: seven prompts, rows 0-6, the cursor on the last. Sliding to the
        // cursor's row (300) put it on the top line and the six prompts above it out of view, for
        // empty rows the keyboard could cover. Whoever holds the pane's size, the screen is the same.
        assertEquals(0f, imeShift(imePx = 400, paneHeightPx = 1000, cursorTopPx = 300, drawnBottomPx = 350), 0.01f)
        assertEquals(0f, imeShift(imePx = 400, paneHeightPx = 1000, cursorTopPx = 0, drawnBottomPx = 50), 0.01f)
        assertEquals("right at the keyboard's edge", 0f, imeShift(imePx = 400, paneHeightPx = 1000, cursorTopPx = 550, drawnBottomPx = 600), 0.01f)
    }

    @Test
    fun `a short screen that reaches under the keyboard moves only as far as its last row needs`() {
        // The cursor on row 13 of 20: its bottom, 700, is 100 px under the keyboard's edge at 600.
        assertEquals(100f, imeShift(imePx = 400, paneHeightPx = 1000, cursorTopPx = 650, drawnBottomPx = 700), 0.01f)
        // A completion menu drawn under the cursor counts too: it is what is being chosen from.
        assertEquals(250f, imeShift(imePx = 400, paneHeightPx = 1000, cursorTopPx = 650, drawnBottomPx = 850), 0.01f)
        // Never past the cursor's own row, nor by more than the keyboard.
        assertEquals(150f, imeShift(imePx = 400, paneHeightPx = 1000, cursorTopPx = 150, drawnBottomPx = 900), 0.01f)
        assertEquals(400f, imeShift(imePx = 400, paneHeightPx = 1000, cursorTopPx = 900, drawnBottomPx = 1100), 0.01f)
    }

    @Test
    fun `with no cursor shown what is drawn does not matter`() {
        assertEquals(400f, imeShift(imePx = 400, paneHeightPx = 1000, cursorTopPx = null, drawnBottomPx = 350), 0.01f)
    }

    @Test
    fun `the cursor's row is in view whichever way the keyboard goes`() {
        // Opening, part open while it animates, open, closing and closed again: at every step the
        // row from the cursor's top to its bottom is inside what the keyboard leaves of the pane.
        val pane = 1000
        val row = 50
        for (cursor in listOf(0, 50, 300, 500, 950)) {
            for (ime in listOf(0, 100, 250, 400, 250, 100, 0)) {
                val shift = imeShift(imePx = ime, paneHeightPx = pane, cursorTopPx = cursor)
                val visibleTop = shift
                val visibleBottom = shift + (pane - ime)
                assertTrue("cursor $cursor, keyboard $ime: top", cursor >= visibleTop)
                assertTrue("cursor $cursor, keyboard $ime: bottom", cursor + row <= visibleBottom)
            }
            assertEquals("closed again, the pane is back", 0f, imeShift(imePx = 0, paneHeightPx = pane, cursorTopPx = cursor), 0.01f)
        }
    }

    @Test
    fun `the cursor's row is in view whichever way the keyboard goes, on a short screen too`() {
        val pane = 1000
        val row = 50
        for (cursor in listOf(0, 50, 300, 500, 650, 950)) {
            for (below in listOf(0, 1, 3)) {
                val drawn = minOf(pane - row, cursor + below * row) + row
                for (ime in listOf(0, 100, 250, 400, 250, 100, 0)) {
                    val shift = imeShift(imePx = ime, paneHeightPx = pane, cursorTopPx = cursor, drawnBottomPx = drawn)
                    assertTrue("cursor $cursor, keyboard $ime: top", cursor >= shift)
                    assertTrue("cursor $cursor, keyboard $ime: bottom", cursor + row <= shift + (pane - ime))
                    assertTrue("cursor $cursor, keyboard $ime: no more than needed", shift <= maxOf(0, drawn - (pane - ime)))
                }
            }
        }
    }

    @Test
    fun `the key bar goes with a keyboard on the desk, unless it was asked for`() {
        assertFalse(keyBarShown(hardwareKeyboard = true, always = false))
        assertTrue(keyBarShown(hardwareKeyboard = true, always = true))
        assertTrue(keyBarShown(hardwareKeyboard = false, always = false))
        assertTrue(keyBarShown(hardwareKeyboard = false, always = true))
    }
}
