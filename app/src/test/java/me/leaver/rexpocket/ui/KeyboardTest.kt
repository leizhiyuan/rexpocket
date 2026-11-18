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
        assertEquals(0f, imeShift(cursorBottomPx = 900f, imePx = 0, paneHeightPx = 1000), 0.01f)
    }

    @Test
    fun `a cursor the keyboard does not reach leaves the pane where it is`() {
        // A 400 px keyboard over a 1000 px pane covers from 600 down: the row at 500 is not there.
        assertEquals(0f, imeShift(cursorBottomPx = 500f, imePx = 400, paneHeightPx = 1000), 0.01f)
    }

    @Test
    fun `the pane moves just far enough to bring the cursor above the keyboard`() {
        // The row ends at 900 and 300 of it would be under the keyboard.
        assertEquals(300f, imeShift(cursorBottomPx = 900f, imePx = 400, paneHeightPx = 1000), 0.01f)
    }

    @Test
    fun `the pane never moves further than the keyboard itself`() {
        // A cursor already off the bottom of the pane asks for more than the keyboard covers.
        assertEquals(400f, imeShift(cursorBottomPx = 1400f, imePx = 400, paneHeightPx = 1000), 0.01f)
    }

    @Test
    fun `a pane that has not been laid out yet is left alone`() {
        assertEquals(0f, imeShift(cursorBottomPx = 900f, imePx = 400, paneHeightPx = 0), 0.01f)
    }

    @Test
    fun `the key bar goes with a keyboard on the desk, unless it was asked for`() {
        assertFalse(keyBarShown(hardwareKeyboard = true, always = false))
        assertTrue(keyBarShown(hardwareKeyboard = true, always = true))
        assertTrue(keyBarShown(hardwareKeyboard = false, always = false))
        assertTrue(keyBarShown(hardwareKeyboard = false, always = true))
    }
}
