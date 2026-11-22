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
    fun `the key bar goes with a keyboard on the desk, unless it was asked for`() {
        assertFalse(keyBarShown(hardwareKeyboard = true, always = false))
        assertTrue(keyBarShown(hardwareKeyboard = true, always = true))
        assertTrue(keyBarShown(hardwareKeyboard = false, always = false))
        assertTrue(keyBarShown(hardwareKeyboard = false, always = true))
    }
}
