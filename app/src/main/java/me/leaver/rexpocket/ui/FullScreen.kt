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
 */
internal data class TermChrome(val fullScreen: Boolean = false) {
    /** The button, and the double tap, both do this. */
    fun toggled(): TermChrome = copy(fullScreen = !fullScreen)

    /** Back leaves full screen before it leaves the terminal; null when Back is not taken here. */
    fun back(): TermChrome? = if (fullScreen) copy(fullScreen = false) else null

    val topBar: Boolean get() = !fullScreen

    /** The key bar, which a keyboard on the desk may already have taken away. */
    fun keyBar(dockWanted: Boolean): Boolean = dockWanted && !fullScreen

    /** The phone's status and navigation bars, which a swipe from the edge shows for a moment. */
    val systemBars: Boolean get() = !fullScreen
}

/**
 * Whether the keyboard stays up after a double tap. The first tap of it has already raised the
 * keyboard - a tap never waits to see whether a second follows - so a keyboard that was down before
 * goes down again, and one that was already up stays up and keeps taking what is typed.
 */
internal fun keyboardStaysAfterDoubleTap(upBeforeFirstTap: Boolean): Boolean = upBeforeFirstTap

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
