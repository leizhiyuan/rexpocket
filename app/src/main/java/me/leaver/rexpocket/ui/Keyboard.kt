package me.leaver.rexpocket.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
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
