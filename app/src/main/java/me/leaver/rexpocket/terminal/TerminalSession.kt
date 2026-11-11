package me.leaver.rexpocket.terminal

import android.os.Handler
import android.os.Looper

/**
 * One pane's terminal: a libghostty-vt emulator that Rex's output is fed into, and the bytes a
 * program is to read for what the user does to it.
 *
 * Output arrives on a worker thread and the view reads the screen on the main one, so every use of
 * the native handle takes [lock]. A session is closed once, by whoever replaces or drops it.
 */
internal class TerminalSession private constructor(private var handle: Long) {
    /** Where what the user types, and the size the pane was given, go: to Rex, not to a local process. */
    interface Transport {
        fun sendInput(data: ByteArray)
        fun sendResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int)
    }

    var transport: Transport? = null

    /** Called, on any thread, whenever the picture may have changed. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    /** Called on the main thread for what the view has selected, when something asks for it as text. */
    @Volatile
    var selection: (() -> String)? = null

    var theme: TerminalTheme? = null
        set(value) {
            field = value
            if (value != null) withHandle { h ->
                GhosttyVt.nativeSetColor(h, GhosttyVt.COLOR_FOREGROUND, value.foreground)
                GhosttyVt.nativeSetColor(h, GhosttyVt.COLOR_BACKGROUND, value.background)
                version++
            }
            onChanged?.invoke()
        }

    var columns = 0
        private set
    var rows = 0
        private set
    private var cellWidthPx = 1
    private var cellHeightPx = 1

    private val lock = Any()
    private var version = 0
    private var cached: TerminalFrame? = null
    private var cachedVersion = -1

    private val main = Handler(Looper.getMainLooper())
    private var pendingResize: Runnable? = null

    private inline fun <T> withHandle(block: (Long) -> T): T? = synchronized(lock) { if (handle == 0L) null else block(handle) }

    /** Feeds what the program printed. Safe from any thread. */
    fun feedOutput(bytes: ByteArray) {
        val result = withHandle { GhosttyVt.nativeWrite(it, bytes, 0, bytes.size).also { version++ } }
        // A refused write leaves the emulator where it was, and there is no better picture to show.
        if (result != null) onChanged?.invoke()
    }

    /** The viewport right now; the one before it if nothing has changed since. Null once closed. */
    fun frame(): TerminalFrame? = synchronized(lock) {
        if (handle == 0L) return null
        if (cachedVersion != version || cached == null) {
            val words = GhosttyVt.nativeFrame(handle) ?: return null
            cached = TerminalFrame.parse(words)
            cachedVersion = version
        }
        cached
    }

    /**
     * Gives the terminal a grid. The emulator takes it at once, so what is drawn next fits; Rex is
     * told after the size has held still for a moment, since a keyboard sliding in changes it on
     * every frame.
     */
    fun resize(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        if (columns <= 0 || rows <= 0) return
        val changed = columns != this.columns || rows != this.rows
        if (!changed && cellWidthPx == this.cellWidthPx && cellHeightPx == this.cellHeightPx) return
        this.columns = columns
        this.rows = rows
        this.cellWidthPx = cellWidthPx
        this.cellHeightPx = cellHeightPx
        withHandle { GhosttyVt.nativeResize(it, columns, rows, cellWidthPx, cellHeightPx).also { version++ } }
        onChanged?.invoke()
        pendingResize?.let(main::removeCallbacks)
        val send = Runnable {
            pendingResize = null
            transport?.sendResize(columns, rows, cellWidthPx * columns, cellHeightPx * rows)
        }
        pendingResize = send
        main.postDelayed(send, RESIZE_DEBOUNCE_MS)
    }

    fun scroll(lines: Int) {
        if (lines == 0) return
        withHandle { GhosttyVt.nativeScroll(it, lines).also { version++ } }
        onChanged?.invoke()
    }

    fun scrollToBottom() {
        withHandle { GhosttyVt.nativeScrollToBottom(it).also { version++ } }
        onChanged?.invoke()
    }

    /** Types text: sent as it is, and the pane follows it to the newest output. */
    fun sendText(text: CharSequence) {
        if (text.isEmpty()) return
        send(text.toString().toByteArray(Charsets.UTF_8))
    }

    /** Types bytes, such as an encoded key: the pane follows them to the newest output. */
    fun send(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        scrollToBottom()
        sendRaw(bytes)
    }

    /** Bytes that answer a gesture, such as a mouse report: the pane stays where it is. */
    fun sendRaw(bytes: ByteArray) {
        if (bytes.isNotEmpty()) transport?.sendInput(bytes)
    }

    /** What the program reads for a key, in the modes it has set; null if the terminal does not encode it. */
    fun encodeKey(keyCode: Int, repeat: Boolean, metaState: Int, text: String?): ByteArray? =
        withHandle { GhosttyVt.nativeKey(it, keyCode, repeat, metaState, text) }

    /** One wheel step over a cell, for a program that asked for the mouse; empty when it did not. */
    fun encodeWheel(down: Boolean, column: Int, row: Int, cellWidthPx: Int, cellHeightPx: Int): ByteArray =
        withHandle { GhosttyVt.nativeWheel(it, down, column, row, cellWidthPx, cellHeightPx) } ?: EMPTY

    fun encodeClick(column: Int, row: Int, cellWidthPx: Int, cellHeightPx: Int): ByteArray =
        withHandle { GhosttyVt.nativeClick(it, column, row, cellWidthPx, cellHeightPx) } ?: EMPTY

    /** The link a program attached to a cell with OSC 8, if any. */
    fun hyperlinkAt(column: Int, row: Int): String? =
        withHandle { GhosttyVt.nativeHyperlinkAt(it, column, row) }?.toString(Charsets.UTF_8)

    /** The text the view has selected, empty if nothing is. */
    fun selectionText(): String = selection?.invoke().orEmpty()

    fun close() {
        pendingResize?.let(main::removeCallbacks)
        pendingResize = null
        transport = null
        onChanged = null
        selection = null
        synchronized(lock) {
            if (handle != 0L) GhosttyVt.nativeFree(handle)
            handle = 0L
            cached = null
        }
    }

    companion object {
        private const val RESIZE_DEBOUNCE_MS = 80L
        private val EMPTY = ByteArray(0)

        /** An empty terminal, for a pane that has no snapshot to start from. */
        fun create(columns: Int, rows: Int): TerminalSession? =
            GhosttyVt.nativeCreate(columns, rows).takeIf { it != 0L }?.let(::TerminalSession)

        /** The terminal a `GHOSTSNP` snapshot describes, or null if libghostty could not read it. */
        fun restore(snapshot: ByteArray): TerminalSession? =
            GhosttyVt.nativeRestore(snapshot).takeIf { it != 0L }?.let(::TerminalSession)
    }
}
