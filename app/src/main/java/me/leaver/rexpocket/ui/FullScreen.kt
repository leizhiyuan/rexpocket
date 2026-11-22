package me.leaver.rexpocket.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

// Full screen on the terminal: the pane takes the room of the top bar, the key bar and the phone's
// own bars. It is a choice for this screen, not a setting: leaving the terminal and coming back
// shows the bars again, so nobody opens a pane later and wonders where the controls went.

/**
 * What the terminal screen shows around the pane. Full screen hides the top bar, the key bar and
 * the phone's status and navigation bars together; one small button stays on screen to bring them
 * back, so there is always a way out that needs no gesture to be known.
 *
 * A [short] window - a phone on its side - has no height to spare. Its pane switcher goes up into
 * the top bar's row, where there is width, instead of floating over the pane; and while the
 * keyboard is up ([imeUp]) the top bar goes too, so the key bar keeps its full height above the
 * keyboard. A small menu button in the pane's corner stands in for the bar until the keyboard goes.
 */
internal data class TermChrome(val fullScreen: Boolean = false, val short: Boolean = false, val imeUp: Boolean = false) {
    /** The menu item, the pill's key, and a gesture set to it all do this. */
    fun toggled(): TermChrome = copy(fullScreen = !fullScreen)

    /** Back leaves full screen before it leaves the terminal; null when Back is not taken here. */
    fun back(): TermChrome? = if (fullScreen) copy(fullScreen = false) else null

    val topBar: Boolean get() = !fullScreen && !(short && imeUp)

    /** The menu in the pane's corner, while a short window has given the top bar to the keyboard. */
    val cornerMenu: Boolean get() = !fullScreen && short && imeUp

    /** Where the pane switcher goes, when a tab has more than one pane: under the pane, or in the top bar. */
    val panesBelow: Boolean get() = !fullScreen && !short
    val panesInTopBar: Boolean get() = topBar && short

    /** The key bar, which a keyboard on the desk may already have taken away. */
    fun keyBar(dockWanted: Boolean): Boolean = dockWanted && !fullScreen

    /** The phone's status and navigation bars, which a swipe from the edge shows for a moment. */
    val systemBars: Boolean get() = !fullScreen
}

/** Below this height a window is short: the key bar, the top bar and the keyboard do not all fit. */
internal const val SHORT_WINDOW_DP = 480

/**
 * Hides the phone's status and navigation bars while [shown] is false, the way a video player does:
 * a swipe from the edge brings them over the screen for a moment and they go again by themselves.
 * Leaving the screen, or full screen, gives them back. The screen keyboard is not one of them, so
 * typing goes on as before.
 */
@Composable
internal fun SystemBars(shown: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, shown) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (!shown && controller != null) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (!shown) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
