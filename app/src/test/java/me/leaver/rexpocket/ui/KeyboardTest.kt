package me.leaver.rexpocket.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The screen keyboard. The pane is moved for it and never resized for it - a program takes a resize
 * as a window change and redraws everything - so what it slides by has to be exactly enough.
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
}
