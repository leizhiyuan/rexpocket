package me.leaver.rexpocket.ui

import android.content.Context
import android.util.Log
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.leaver.rexpocket.BuildConfig
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.TerminalFeed
import io.github.sagernet.libghostty.GhosttyTerminalSession
import io.github.sagernet.libghostty.compose.GhosttyTerminal
import io.github.sagernet.libghostty.compose.rememberGhosttyTerminalState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Terminal surface backed by libghostty-vt.
 *
 * Rex streams a pane: a `GHOSTSNP` snapshot of it first, then its output as it comes. The snapshot
 * becomes the emulator itself, so the pane arrives complete - scrollback, colours, cursor and the
 * escape sequence the program was in the middle of - and everything after it is fed in order.
 */
@Composable
fun TerminalPane(
    blockId: String,
    feeds: (String) -> Flow<TerminalFeed>,
    fontScale: Float,
    onFont: (Float) -> Unit,
    onInput: (ByteArray) -> Unit,
    onResize: (columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) -> Unit,
    modifier: Modifier = Modifier,
    onSession: (GhosttyTerminalSession?) -> Unit = {},
    onError: (Exception) -> Unit = {},
) {
    val context = LocalContext.current
    val termState = rememberGhosttyTerminalState()
    // Replaced when Rex sends a new snapshot: a reconnect, or a pane whose stream started over.
    var session by remember(blockId) { mutableStateOf<GhosttyTerminalSession?>(null) }
    // Collected again from the top when the user asks for the picture again.
    var attempt by remember(blockId) { mutableStateOf(0) }
    val currentInput = rememberUpdatedState(onInput)
    val currentResize = rememberUpdatedState(onResize)
    val input = remember { InputBatcher { bytes -> currentInput.value(bytes) } }

    LaunchedEffect(blockId, attempt) {
        // How long a picture takes to arrive is the number that says whether the tunnel under it is
        // good enough, so it is logged with the snapshot's size.
        val askedAt = SystemClock.elapsedRealtime()
        // The stream reports its own failures; anything that still escapes must not take the app down.
        try {
            feeds(blockId).collect { feed ->
                when (feed) {
                    is TerminalFeed.Snapshot -> {
                        if (BuildConfig.DEBUG) {
                            val waited = SystemClock.elapsedRealtime() - askedAt
                            Log.i(CONN_TAG, "pane $blockId: ${feed.bytes.size} bytes after ${waited}ms")
                        }
                        val restored = withContext(Dispatchers.Default) {
                            GhosttyTerminalSession.restore(context, feed.bytes)
                        }
                        if (restored == null) {
                            if (BuildConfig.DEBUG) Log.w(CONN_TAG, "pane $blockId: the snapshot did not restore")
                            return@collect
                        }
                        bind(restored, input, currentResize)
                        // The old emulator goes only after the new one is ready, so nothing flashes.
                        val previous = session
                        session = restored
                        onSession(restored)
                        withContext(Dispatchers.Default) { previous?.close() }
                    }
                    is TerminalFeed.Output -> {
                        // One reader, in order: a second coroutine would interleave the bytes.
                        withContext(Dispatchers.Default) { session?.feedOutput(feed.bytes) }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        }
    }

    val theme = remember { terminalTheme() }
    Box(modifier) {
        GhosttyTerminal(
            session = session,
            state = termState,
            // A restored snapshot carries the desktop's default colours; the app's own are applied here,
            // so a pane never flips from the app's paper to a white one after a reconnect.
            theme = theme,
            // The view fills its whole canvas and draws rows scrolled above its top; Compose hosts
            // Android views without clipping, so unclipped it paints over the header and tab bar.
            modifier = Modifier.fillMaxSize().clipToBounds(),
            fontSizeSp = 12f * fontScale,
            // The composer owns the IME; the view must not grab it on attach.
            focusOnAttach = false,
            onFontSizeChanged = { onFont(it / 12f) },
            configure = { view ->
                // The app has its own composer and toolbar: the view renders and handles selection,
                // while every keystroke goes through the composer so focus never moves away from it.
                view.showImeOnTap = false
                view.isFocusable = false
                view.isFocusableInTouchMode = false
                view.urlDetectionEnabled = false
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
            session?.let { s ->
                s.transport = null
                s.close()
            }
            session = null
        }
    }
}

/** Sends what the emulator produces, and reports its grid, through the screen's callbacks. */
private fun bind(
    session: GhosttyTerminalSession,
    input: InputBatcher,
    resize: androidx.compose.runtime.State<(Int, Int, Int, Int) -> Unit>,
) {
    session.transport = object : GhosttyTerminalSession.Transport {
        override fun sendInput(data: ByteArray) {
            // A swipe over a full-screen TUI turns into one mouse-wheel report per row, and each
            // report used to be its own round trip. Batching keeps the gesture attached to the finger.
            input.post(data)
        }

        override fun sendResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) {
            resize.value(columns, rows, widthPixels, heightPixels)
        }

        override fun close() = Unit
    }
    session.systemClipboardWriteEnabled = false
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
