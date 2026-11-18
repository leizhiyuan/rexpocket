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

    @Test
    fun `the key bar goes with a keyboard on the desk, unless it was asked for`() {
        assertFalse(keyBarShown(hardwareKeyboard = true, always = false))
        assertTrue(keyBarShown(hardwareKeyboard = true, always = true))
        assertTrue(keyBarShown(hardwareKeyboard = false, always = false))
        assertTrue(keyBarShown(hardwareKeyboard = false, always = true))
    }
}
