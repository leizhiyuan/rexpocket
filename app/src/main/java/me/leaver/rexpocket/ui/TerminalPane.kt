package me.leaver.rexpocket.ui

import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.DoubleTapAction
import me.leaver.rexpocket.core.GestureSettings
import me.leaver.rexpocket.core.LongPressAction
import me.leaver.rexpocket.core.TerminalFeed
import me.leaver.rexpocket.core.keyboardAfterDoubleTap
import me.leaver.rexpocket.core.keyboardAfterTap
import me.leaver.rexpocket.diagnostics.Diagnostics
import me.leaver.rexpocket.terminal.TerminalSession
import me.leaver.rexpocket.terminal.TerminalView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Terminal surface backed by the official libghostty-vt: [TerminalSession] holds the emulator and
 * [TerminalView] draws it.
 *
 * Rex streams a pane: a `GHOSTSNP` snapshot of it first, then its output as it comes. The snapshot
 * becomes the emulator itself, so the pane arrives complete - scrollback, colours, cursor and the
 * escape sequence the program was in the middle of - and everything after it is fed in order.
 */
@Composable
internal fun TerminalPane(
    blockId: String,
    feeds: (String) -> Flow<TerminalFeed>,
    fontScale: Float,
    terminalTypeface: android.graphics.Typeface,
    onFont: (Float) -> Unit,
    onInput: (ByteArray) -> Unit,
    onResize: (columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Offered what the keyboard typed before it is sent; true when the screen sent it itself, as a
     * key with the key row's modifiers. It calls the second argument first, which sends what is
     * still batched, so the key keeps its place among what was typed before it.
     */
    onTyped: (ByteArray, () -> Unit) -> Boolean = { _, _ -> false },
    /** A link the user long-pressed in the pane: a hyperlink the program set, or a URL in its text. */
    onLink: (String) -> Unit = {},
    /** Paste and Share in the menu over a selection. */
    onPaste: () -> Unit = {},
    onShare: (String) -> Unit = {},
    onSession: (TerminalSession?) -> Unit = {},
    /** The pane's editor, which is what raises and drops the keyboard for it. */
    onKeyboard: (TerminalImeView?) -> Unit = {},
    /** The top of the cursor's row in the pane, in pixels, or null while it shows none; see TerminalView.onCursorRow. */
    onCursorTop: (Int?) -> Unit = {},
    /** True while a keyboard other than the screen's is attached: the pane keeps the focus then. */
    hardwareKeyboard: Boolean = false,
    /** A horizontal swipe across the pane: forward means towards the next pane. */
    onSwipe: ((forward: Boolean) -> Unit)? = null,
    /** What a tap, a double tap and a long press on the pane do, and their times. */
    gestures: GestureSettings = GestureSettings(),
    /** True in full screen, where there is no keyboard key: a tap that opens the keyboard closes it too. */
    fullScreen: Boolean = false,
    /** Goes in or out of full screen, for a gesture set to do that. */
    onFullScreen: () -> Unit = {},
    onError: (Exception) -> Unit = {},
    /** A key from a keyboard, offered before the terminal gets it; true when the screen took it. */
    onShortcut: (android.view.KeyEvent) -> Boolean = { false },
) {
    val context = LocalContext.current
    // Replaced when Rex sends a new snapshot: a reconnect, or a pane whose stream started over.
    var session by remember(blockId) { mutableStateOf<TerminalSession?>(null) }
    // Collected again from the top when the user asks for the picture again.
    var attempt by remember(blockId) { mutableStateOf(0) }
    val currentInput = rememberUpdatedState(onInput)
    val currentTyped = rememberUpdatedState(onTyped)
    val currentLink = rememberUpdatedState(onLink)
    val currentPaste = rememberUpdatedState(onPaste)
    val currentShare = rememberUpdatedState(onShare)
    val currentResize = rememberUpdatedState(onResize)
    val currentSwipe = rememberUpdatedState(onSwipe)
    val currentKeyboard = rememberUpdatedState(onKeyboard)
    val currentFont = rememberUpdatedState(onFont)
    val currentCursor = rememberUpdatedState(onCursorTop)
    val currentShortcut = rememberUpdatedState(onShortcut)
    val input = remember { InputBatcher { bytes -> currentInput.value(bytes) } }

    LaunchedEffect(blockId, attempt) {
        // How long a picture takes to arrive is the number that says whether the tunnel under it is
        // good enough, so it is logged with the snapshot's size.
        val askedAt = SystemClock.elapsedRealtime()
        val stream = StreamMeter(blockId)
        // The stream reports its own failures; anything that still escapes must not take the app down.
        try {
            feeds(blockId).collect { feed ->
                when (feed) {
                    is TerminalFeed.Snapshot -> {
                        Diagnostics.event(CONN_TAG, "pane $blockId: ${feed.bytes.size} bytes after ${SystemClock.elapsedRealtime() - askedAt}ms")
                        val restored = withContext(Dispatchers.Default) {
                            TerminalSession.restore(feed.bytes)
                        }
                        if (restored == null) {
                            Diagnostics.event(CONN_TAG, "pane $blockId: the snapshot did not restore")
                            return@collect
                        }
                        bind(restored, input, currentTyped, currentResize)
                        // The old emulator goes only after the new one is ready, so nothing flashes.
                        val previous = session
                        session = restored
                        onSession(restored)
                        withContext(Dispatchers.Default) { previous?.close() }
                    }
                    is TerminalFeed.Output -> {
                        // One reader, in order: a second coroutine would interleave the bytes.
                        withContext(Dispatchers.Default) { session?.feedOutput(feed.bytes) }
                        stream.add(feed.bytes.size)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        } catch (e: LinkageError) {
            // libghostty is not there to draw: a JVM test on a computer that did not build it, or a
            // broken install. The pane stays blank rather than taking the app down with it.
            Diagnostics.event(CONN_TAG, "pane $blockId: no emulator to draw it: ${e.message}")
        }
    }

    val theme = remember(Colors.isDark) { terminalTheme() }
    // The keyboard types into this, not into the pane: see TerminalImeView for why the pane cannot
    // hold it itself. It sits under the terminal, so every gesture still lands on the pane.
    val keyboard = remember(context) { TerminalImeView(context) }
    val pane = remember(context) {
        TerminalView(context).also { view ->
            view.onFontSizeChanged = { currentFont.value(it / 12f) }
            // A long press on a link offers it instead of selecting its text: agents print the pull
            // requests and sign-in pages they want you to open.
            view.onLink = { currentLink.value(it) }
            view.onPaste = { currentPaste.value() }
            view.onShare = { currentShare.value(it) }
            // A horizontal swipe moves between the panes of a split, which is what the dots under
            // the card promise. Vertical drags stay the pane's own: history, or the program's wheel.
            view.onSwipe = { forward -> currentSwipe.value?.invoke(forward) }
            // The screen keeps the cursor's row in view while the keyboard is up.
            view.onCursorRow = { currentCursor.value(it) }
            view.onShortcut = { currentShortcut.value(it) }
        }
    }
    // With a keyboard on the desk the pane holds the focus itself, so its keys reach the program
    // without the screen keyboard having to be raised first. The screen keyboard's turn comes from
    // a tap on the pane, which still moves the focus to its own view.
    LaunchedEffect(hardwareKeyboard) {
        if (hardwareKeyboard && !keyboard.hasFocus()) pane.requestFocus()
    }
    // A tap on the pane is a tap on its text: by default it raises the keyboard, which the pane
    // never asks for itself - it is no text editor, and its input connection would answer with no
    // text type at all. What each gesture does is the person's choice; see terminal-controls.md.
    //
    // Unless the person asked to wait, a tap acts at once even when a double tap is listened for, so
    // the first tap of a double tap may have moved the keyboard by the time the second lands; the
    // double tap then sets it from where it was before that first tap.
    val taps = remember { TapMemory() }
    val currentFullScreen = rememberUpdatedState(onFullScreen)
    val currentGestures = rememberUpdatedState(gestures)
    val inFullScreen = rememberUpdatedState(fullScreen)
    val viewConfig = remember(context) { android.view.ViewConfiguration.get(context) }
    SideEffect {
        keyboard.pane = pane
        pane.gestureTiming = gestures.timing(
            systemDoubleTapMs = android.view.ViewConfiguration.getDoubleTapTimeout(),
            systemLongPressMs = android.view.ViewConfiguration.getLongPressTimeout(),
            touchSlop = viewConfig.scaledTouchSlop.toFloat(),
            doubleTapSlop = viewConfig.scaledDoubleTapSlop.toFloat(),
        )
        pane.onTap = {
            val up = keyboard.imeUp()
            taps.keyboardUpBeforeTap = up
            keyboardAfterTap(currentGestures.value.tap, up, inFullScreen.value)?.let { keyboard.setIme(it) }
        }
        pane.onDoubleTap = if (gestures.doubleTap == DoubleTapAction.Nothing) null else {
            { firstTapActed ->
                val action = currentGestures.value.doubleTap
                val before = if (firstTapActed) taps.keyboardUpBeforeTap else keyboard.imeUp()
                keyboard.setIme(keyboardAfterDoubleTap(before, action))
                if (action == DoubleTapAction.FullScreen) currentFullScreen.value()
            }
        }
        pane.onLongPress = when (gestures.longPress) {
            LongPressAction.Select -> null
            LongPressAction.FullScreen -> { { currentFullScreen.value() } }
            LongPressAction.Keyboard -> { { keyboard.setIme(!keyboard.imeUp()) } }
        }
    }
    // Published before the terminal view exists, so the key row can raise the keyboard at once.
    LaunchedEffect(keyboard) { currentKeyboard.value(keyboard) }
    Box(modifier) {
        AndroidView(factory = { keyboard }, modifier = Modifier.fillMaxSize())
        AndroidView(
            factory = { pane },
            // The view fills its whole canvas; Compose hosts Android views without clipping.
            modifier = Modifier.fillMaxSize().clipToBounds(),
            update = { view ->
                // A restored snapshot carries the desktop's default colours; the app's own are applied
                // here, so a pane never flips from the app's paper to a white one after a reconnect.
                view.theme = theme
                view.typeface = terminalTypeface
                view.fontSizeSp = 12f * fontScale
                view.hardwareKeys = hardwareKeyboard
                view.session = session
            },
        )
        // Until the first snapshot there is nothing to draw; a black rectangle with no words looks
        // like a broken app, so it says what it is waiting for and offers to ask again.
        if (session == null) WaitingForPane(blockId, onRetry = { attempt++ }, Modifier.fillMaxSize())
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            input.flush()
            onSession(null)
            onKeyboard(null)
            session?.let { s ->
                s.transport = null
                s.close()
            }
            session = null
        }
    }
}

/** What the pane remembers from one tap to the next; plain fields, since nothing draws from them. */
private class TapMemory {
    /** Whether the keyboard was up before the last tap moved it. */
    var keyboardUpBeforeTap = false
}

/** Sends what the emulator produces, and reports its grid, through the screen's callbacks. */
private fun bind(
    session: TerminalSession,
    input: InputBatcher,
    typed: androidx.compose.runtime.State<(ByteArray, () -> Unit) -> Boolean>,
    resize: androidx.compose.runtime.State<(Int, Int, Int, Int) -> Unit>,
) {
    session.transport = object : TerminalSession.Transport {
        override fun sendInput(data: ByteArray) {
            if (typed.value(data, input::flush)) return
            // A swipe over a full-screen TUI turns into one mouse-wheel report per row, and each
            // report used to be its own round trip. Batching keeps the gesture attached to the finger.
            input.post(data)
        }

        override fun sendResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) {
            Diagnostics.event(CONN_TAG, "resize to ${columns}x$rows")
            resize.value(columns, rows, widthPixels, heightPixels)
        }
    }
}

/** What a pane shows before Rex has sent its first snapshot: a line of state, and a way to ask again. */
@Composable
private fun WaitingForPane(blockId: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    var waited by remember(blockId) { mutableStateOf(false) }
    LaunchedEffect(blockId) {
        delay(PANE_WAIT_MS)
        waited = true
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(if (waited) R.string.pane_no_picture else R.string.pane_reading),
                color = Colors.muted,
                fontSize = TextStyles.label,
            )
            if (waited) {
                Spacer(Modifier.height(Space.s))
                Text(
                    stringResource(R.string.action_retry_now),
                    color = Colors.accent,
                    fontSize = TextStyles.label,
                    modifier = Modifier.clip(RoundedCornerShape(Radius.chip))
                        .clickable(onClick = onRetry)
                        .padding(horizontal = Space.m, vertical = Space.s),
                )
            }
        }
    }
}

/**
 * How long a pane may wait for its snapshot before it offers to try again. A pane with a long
 * scrollback arrives as several megabytes, which takes a while over a tunnel, so this waits long
 * enough not to call a slow picture a missing one.
 */
private const val PANE_WAIT_MS = 12_000L

/**
 * One line a second about what a pane's stream carried, not one per chunk: a busy program sends
 * dozens a second, and the shape - bursts, and the gaps between them - is what a report about a
 * stalled or slow pane needs.
 */
private class StreamMeter(private val blockId: String) {
    private var windowStart = 0L
    private var chunks = 0
    private var bytes = 0L

    fun add(size: Int) {
        val now = SystemClock.elapsedRealtime()
        if (windowStart == 0L) windowStart = now
        chunks++
        bytes += size
        if (now - windowStart < WINDOW_MS) return
        Diagnostics.event(CONN_TAG, "pane $blockId: $chunks chunks, ${bytes / 1024} KB in ${now - windowStart}ms")
        windowStart = now
        chunks = 0
        bytes = 0
    }

    private companion object {
        const val WINDOW_MS = 1_000L
    }
}

/**
 * Coalesces terminal input produced in quick succession (wheel reports from a swipe, fast
 * typing) into one write, so a gesture does not turn into dozens of serialized round trips.
 */
private class InputBatcher(private val send: (ByteArray) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val buffer = ByteArrayOutputStream()
    private val flushTask = Runnable { flush() }

    fun post(data: ByteArray) {
        buffer.write(data, 0, data.size)
        if (!pending) {
            pending = true
            handler.postDelayed(flushTask, WINDOW_MS)
        }
    }

    fun flush() {
        handler.removeCallbacks(flushTask)
        pending = false
        if (buffer.size() == 0) return
        val bytes = buffer.toByteArray()
        buffer.reset()
        send(bytes)
    }

    private var pending = false

    private companion object {
        /**
         * One display frame: enough to gather the reports a frame of finger movement produces.
         * Writes are fire-and-forget, so a longer wait only delays the scrolled frame.
         */
        const val WINDOW_MS = 16L
    }
}

/** Raises the keyboard for the pane, or puts it away. */
private fun TerminalImeView.setIme(up: Boolean) {
    if (up) showIme() else hideIme()
}
