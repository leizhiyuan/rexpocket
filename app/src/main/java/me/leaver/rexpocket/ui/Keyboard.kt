package me.leaver.rexpocket.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

// The screen keyboard comes and goes over the terminal many times an hour. The pane is moved for it,
// never resized: a program takes a resize as a window change and redraws everything it has.

/** How tall the keyboard covering the window is right now, in pixels; 0 while it is closed. */
@Composable
internal fun imeHeight(): Int = WindowInsets.ime.getBottom(LocalDensity.current)

/**
 * How far the pane slides up when the keyboard covers it. The terminal is not resized for the
 * keyboard - the program would take that as a window change and redraw everything - so the pane is
 * moved instead: up by just enough that the cursor's row sits above the keyboard, never further
 * than the keyboard itself, and not at all while it is closed.
 */
internal fun imeShift(cursorBottomPx: Float, imePx: Int, paneHeightPx: Int): Float {
    if (imePx <= 0 || paneHeightPx <= 0 || cursorBottomPx <= 0f) return 0f
    return (cursorBottomPx + imePx - paneHeightPx).coerceIn(0f, imePx.toFloat())
}
