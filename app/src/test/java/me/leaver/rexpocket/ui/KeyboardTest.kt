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
    fun `a prompt near the top does not slide off screen`() {
        // 50 rows at 20px = 1000px pane; keyboard covers the bottom 400px (rows 30..49).
        // Content ending at row 2 (60px) is already well above the keyboard.
        assertEquals(
            0f,
            imeShift(imePx = 400, paneHeightPx = 1000, contentBottomRow = 2, cellHeightPx = 20f),
            0.01f,
        )
    }

    @Test
    fun `content reaching the bottom shifts by the full keyboard height`() {
        // Row 49 ends at 1000px, so keeping it above the 400px keyboard takes the full 400px shift.
        assertEquals(
            400f,
            imeShift(imePx = 400, paneHeightPx = 1000, contentBottomRow = 49, cellHeightPx = 20f),
            0.01f,
        )
    }

    @Test
    fun `content partly covered by the keyboard shifts only as far as needed`() {
        // Row 34 ends at 700px; visible height above keyboard is 600px, so only a 100px shift is needed.
        assertEquals(
            100f,
            imeShift(imePx = 400, paneHeightPx = 1000, contentBottomRow = 34, cellHeightPx = 20f),
            0.01f,
        )
    }

    @Test
    fun `an empty viewport with no cursor does not shift`() {
        assertEquals(
            0f,
            imeShift(imePx = 400, paneHeightPx = 1000, contentBottomRow = -1, cellHeightPx = 20f),
            0.01f,
        )
    }

    @Test
    fun `the key bar goes with a keyboard on the desk, unless it was asked for`() {
        assertFalse(keyBarShown(hardwareKeyboard = true, always = false))
        assertTrue(keyBarShown(hardwareKeyboard = true, always = true))
        assertTrue(keyBarShown(hardwareKeyboard = false, always = false))
        assertTrue(keyBarShown(hardwareKeyboard = false, always = true))
    }
}
