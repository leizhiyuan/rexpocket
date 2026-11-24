package me.leaver.rexpocket.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

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
 * How far the pane slides up while the keyboard covers it. The terminal is not resized for the
 * keyboard - the program would take that as a window change and redraw everything - so the pane is
 * moved instead: only as far as needed to keep the lowest row with content above the keyboard,
 * capped at what the keyboard covers.
 *
 * Content is the lowest of the cursor row, visible Kitty images, and the last row with visible
 * cells. A fresh shell near the top of the pane stays put instead of sliding off the top of the
 * screen, while a full-screen program (`pi`, `htop`, Claude Code) that hides the terminal's cursor
 * and draws down to the bottom still shifts by the full keyboard height.
 *
 * The keyboard is measured from the window's bottom edge, and the pane's own bottom edge is
 * [bottomInsetPx] above it: only the part of the keyboard above that line covers the pane, and
 * moving the pane by more than that covers would hide rows off the top of the screen for nothing.
 *
 * What the pane has to clear is the lowest thing floating over it, which is the dock and not the
 * keyboard's top edge: the dock rises with the keyboard and sits on the last [bottomInsetPx] of the
 * window, so on the terminal screen the inset is the navigation bar alone and the dock's own height
 * is not part of it.
 */
internal fun imeShift(
    imePx: Int,
    paneHeightPx: Int,
    contentBottomRow: Int? = null,
    cellHeightPx: Float = 0f,
    bottomInsetPx: Int = 0,
): Float {
    if (imePx <= 0 || paneHeightPx <= 0) return 0f
    val coveredPx = (imePx - bottomInsetPx).coerceIn(0, paneHeightPx)
    if (coveredPx <= 0) return 0f
    val maxShift = coveredPx.toFloat()
    if (contentBottomRow == null || cellHeightPx <= 0f) return maxShift
    if (contentBottomRow < 0) return 0f
    val contentBottomPx = (contentBottomRow + 1) * cellHeightPx
    val visibleHeightPx = paneHeightPx - coveredPx
    return (contentBottomPx - visibleHeightPx).coerceIn(0f, maxShift)
}
