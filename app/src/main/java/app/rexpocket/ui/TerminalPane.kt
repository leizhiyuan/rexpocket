package app.rexpocket.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import io.github.sagernet.libghostty.GhosttyTerminalSession
import io.github.sagernet.libghostty.compose.rememberGhosttyTerminalState
import io.github.sagernet.libghostty.compose.GhosttyTerminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Terminal surface backed by libghostty-vt.
 *
 * Rex hands out a full `format=vt` replay of scrollback plus screen, so every update starts
 * from a RIS reset before the dump goes in. That keeps the emulator's scrollback in sync with
 * the server's instead of appending the same history again on each poll.
 */
@Composable
fun TerminalPane(
    vt: String?,
    vtVersion: Int,
    fontScale: Float,
    onFont: (Float) -> Unit,
    onInput: (ByteArray) -> Unit,
    onResize: (columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) -> Unit,
    modifier: Modifier = Modifier,
    onSession: (GhosttyTerminalSession?) -> Unit = {},
) {
    val context = LocalContext.current
    val termState = rememberGhosttyTerminalState()
    val lastFed = remember { java.util.concurrent.atomic.AtomicReference<String?>(null) }
    val session = remember {
        GhosttyTerminalSession(
            context,
            GhosttyTerminalSession.Options(
                columns = 80,
                rows = 24,
                maxScrollbackLines = 10_000,
            ),
        )
    }
    val currentInput = rememberUpdatedState(onInput)
    val input = remember { InputBatcher { bytes -> currentInput.value(bytes) } }
    val currentResize = rememberUpdatedState(onResize)

    DisposableEffect(session) {
        session.transport = object : GhosttyTerminalSession.Transport {
            override fun sendInput(data: ByteArray) {
                // A swipe over a full-screen TUI turns into one mouse-wheel report per row, and
                // each report used to be its own round trip. Batching them keeps the gesture
                // attached to the finger.
                input.post(data)
            }

            override fun sendResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) {
                currentResize.value(columns, rows, widthPixels, heightPixels)
            }

            override fun close() = Unit
        }
        session.systemClipboardWriteEnabled = false
        onSession(session)
        onDispose {
            input.flush()
            onSession(null)
            session.transport = null
            session.close()
        }
    }

    LaunchedEffect(vtVersion) {
        val dump = vt ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            val previous = lastFed.getAndSet(dump)
            session.feedOutput(payloadFor(dump, previous).toByteArray(Charsets.UTF_8))
        }
    }

    GhosttyTerminal(
        session = session,
        state = termState,
        // The view fills its whole canvas and draws rows scrolled above its top; Compose hosts
        // Android views without clipping, so unclipped it paints over the header and tab bar.
        modifier = modifier.clipToBounds(),
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
}

private const val RESET = "\u001bc"

/** Cursor placement and SGR reset that the server writes after the screen text. */
private val TRAILING_CONTROL = Regex("(?:\\u001b\\[[0-9;]*[HJm])+$")

private fun splitTrailingControl(dump: String): Pair<String, String> {
    val match = TRAILING_CONTROL.find(dump) ?: return dump to ""
    return dump.substring(0, match.range.first) to dump.substring(match.range.first)
}

/**
 * Rex sends the whole scrollback and screen on every poll. Replaying it from a RIS reset would
 * throw away the emulator's scroll position on every keystroke, so the common case - output
 * appended to the transcript - feeds just the new tail.
 */
private fun payloadFor(dump: String, previous: String?): String {
    val (body, tail) = splitTrailingControl(dump)
    val previousBody = previous?.let { splitTrailingControl(it).first }
    if (previousBody != null && previousBody.isNotEmpty() && body.length > previousBody.length && body.startsWith(previousBody)) {
        return body.substring(previousBody.length) + tail
    }
    return RESET + body + tail
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
        /** Long enough to collect one gesture frame, short enough to feel immediate. */
        const val WINDOW_MS = 50L
    }
}
