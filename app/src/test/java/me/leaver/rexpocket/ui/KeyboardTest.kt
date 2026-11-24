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
    fun `the room under the pane is not part of what the keyboard covers`() {
        // The pane stops 250px above the window's bottom edge, so of a 400px keyboard only 150px
        // reach it: content ending at row 34 (700px) already fits in the 850px still visible.
        assertEquals(
            0f,
            imeShift(imePx = 400, paneHeightPx = 1000, contentBottomRow = 34, cellHeightPx = 20f, bottomInsetPx = 250),
            0.01f,
        )
    }

    @Test
    fun `content to the last row shifts by what the keyboard covers, not by its height`() {
        // A phone pane shaped like the one measured on a 1440x3200 device: 40 rows of 60px (a
        // 2400px grid) in a 2430px pane, a 1111px keyboard over the 75px navigation bar. The
        // keyboard covers 1036px of the pane, so 1394px stay visible and the last row needs a
        // shift of 1006px - not 1111px, which would leave rows behind the top bar for nothing.
        assertEquals(
            1006f,
            imeShift(imePx = 1111, paneHeightPx = 2430, contentBottomRow = 39, cellHeightPx = 60f, bottomInsetPx = 75),
            0.01f,
        )
    }

    @Test
    fun `content a few rows up shifts only by what is still covered`() {
        // The same pane with content to row 35: 2160px of content in the 1394px left visible.
        assertEquals(
            766f,
            imeShift(imePx = 1111, paneHeightPx = 2430, contentBottomRow = 35, cellHeightPx = 60f, bottomInsetPx = 75),
            0.01f,
        )
    }

    @Test
    fun `a keyboard that never reaches the pane moves nothing`() {
        // The pane ends above the keyboard's top edge: there is nothing to move out of its way.
        assertEquals(
            0f,
            imeShift(imePx = 400, paneHeightPx = 1000, contentBottomRow = 49, cellHeightPx = 20f, bottomInsetPx = 400),
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
