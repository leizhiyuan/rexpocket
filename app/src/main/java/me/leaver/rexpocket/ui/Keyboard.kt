package me.leaver.rexpocket.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

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
 * What of the pane the keyboard covers; its bottom is 0 while it is closed. The screen already
 * keeps the navigation bar clear of the pane, and the keyboard's inset counts that bar too, so it
 * is taken off: the dock rises by the rest, and so must the pane, or a gap opens between them and a
 * row more is pushed out at the top. Read its bottom in layout ([imeSlide]), not in composition:
 * it changes on every frame of the keyboard's animation.
 */
@Composable
internal fun imeCover(): WindowInsets = WindowInsets.ime.exclude(WindowInsets.navigationBars)

/**
 * What the pane reports of itself for [imeShift]: its height, the top of its cursor's row and the
 * bottom of what it drew. They change as the program draws (the cursor's row on every line of a
 * `cat`), so they are state read only by [imeSlide]'s layout, never by the screen's composition.
 */
@Stable
internal class PaneSlide {
    var paneHeightPx by mutableIntStateOf(0)
    var cursorTopPx by mutableStateOf<Int?>(null)
    var drawnBottomPx by mutableStateOf<Int?>(null)
}

/**
 * Slides the pane up by [imeShift] of [ime] and [slide], reading both in the layout phase only, so
 * a keyboard opening or a cursor moving re-places the pane without recomposing the screen around it.
 */
internal fun Modifier.imeSlide(ime: WindowInsets, slide: PaneSlide): Modifier = offset {
    val shift = imeShift(ime.getBottom(this), slide.paneHeightPx, slide.cursorTopPx, slide.drawnBottomPx)
    IntOffset(0, -shift.roundToInt())
}

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
 * And the bottom of the grid only matters when something is drawn there. A short screen - a new
 * shell, a few prompts, the screen after a clear - ends at the cursor's row with nothing under it,
 * so the pane moves only as far as that last drawn row needs to clear the keyboard: not at all
 * while it is already above it. Sliding it by the cursor's row alone put the cursor on the top
 * line and the prompts above it out of view, for empty rows the keyboard could have covered.
 *
 * [cursorTopPx] is the top of the cursor's row in the pane, or null when no cursor shows.
 * [drawnBottomPx] is the bottom of the last drawn row, at or under the cursor's, or null when the
 * drawing reaches the last row of the grid (or a full-screen program owns it).
 */
internal fun imeShift(imePx: Int, paneHeightPx: Int, cursorTopPx: Int? = null, drawnBottomPx: Int? = null): Float {
    if (imePx <= 0 || paneHeightPx <= 0) return 0f
    val full = minOf(imePx, paneHeightPx)
    if (cursorTopPx == null) return full.toFloat()
    val toCursor = minOf(full, maxOf(0, cursorTopPx))
    if (drawnBottomPx == null) return toCursor.toFloat()
    val needed = drawnBottomPx - (paneHeightPx - imePx)
    return minOf(toCursor, maxOf(0, needed)).toFloat()
}
