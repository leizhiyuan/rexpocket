package me.leaver.rexpocket.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity

// The two keyboards a terminal has to live with: the screen's, which comes and goes over the pane,
// and a real one on the desk, which needs the screen's bar out of the way.

/**
 * True while a keyboard that is not the screen's is attached: a tablet's cover, a keyboard over
 * Bluetooth or USB, a window on a desk. The activity handles `keyboard` and `keyboardHidden`
 * itself, so the configuration changes when one is connected or taken away rather than the app
 * being restarted around it.
 */
@Composable
internal fun rememberHardwareKeyboard(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.keyboard == Configuration.KEYBOARD_QWERTY &&
        configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
}

/**
 * Whether the key bar is drawn. It is the screen keyboard's bar - the keys a phone's keyboard has
 * no room for - so with a keyboard on the desk it is in the way and the pane gets the space, unless
 * someone asked to keep it.
 */
internal fun keyBarShown(hardwareKeyboard: Boolean, always: Boolean): Boolean = always || !hardwareKeyboard

/**
 * How much of the pane the keyboard covers right now, in pixels; 0 while it is closed. The screen
 * already keeps the navigation bar clear of the pane, and the keyboard's inset counts that bar too,
 * so it is taken off: the dock rises by the rest, and so must the pane, or a gap opens between them
 * and a row more is pushed out at the top.
 */
@Composable
internal fun imeCover(): Int =
    WindowInsets.ime.exclude(WindowInsets.navigationBars).getBottom(LocalDensity.current)

/**
 * How far the pane slides up while the keyboard covers it. The terminal is not resized for the
 * keyboard - the program would take that as a window change and redraw everything - so the pane is
 * moved instead: up by what the keyboard covers, which puts the bottom of the terminal - the
 * prompt, the composer, the status line - back where it was, above the dock.
 *
 * What has to stay visible is the bottom of the grid, as long as that keeps the cursor on screen: a
 * full-screen program draws its own cursor and hides the terminal's (`pi`, `htop`, Claude Code all
 * do), and the rows it draws below the cursor are part of what is being typed into. But a new
 * shell, or any program whose cursor is near the top, would have the row being typed into slid
 * out under the top bar, where no scrolling reaches it - it is the live screen, not history. So
 * the pane never slides past the top of the cursor's row: it moves less, the cursor stays in view,
 * and what the keyboard then covers is the rows under the cursor, which such a screen has yet to fill.
 *
 * [cursorTopPx] is the top of the cursor's row in the pane, or null when no cursor shows.
 */
internal fun imeShift(imePx: Int, paneHeightPx: Int, cursorTopPx: Int? = null): Float {
    if (imePx <= 0 || paneHeightPx <= 0) return 0f
    val full = minOf(imePx, paneHeightPx)
    return (if (cursorTopPx == null) full else minOf(full, maxOf(0, cursorTopPx))).toFloat()
}
