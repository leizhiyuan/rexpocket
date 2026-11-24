package me.leaver.rexpocket.terminal

import android.os.Handler
import android.os.Looper
import me.leaver.rexpocket.core.SearchMatch
import me.leaver.rexpocket.core.SearchRow

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

    /** Called, on the thread that fed it, after the program's output has changed the screen; Find follows it. */
    @Volatile
    var onOutput: (() -> Unit)? = null

    /**
     * Find's matches, drawn over the text, and the one it is on; rows count from the oldest one
     * [scrollbackRows] read, as its [Scrollback.firstRow] says. Null while Find is closed.
     */
    @Volatile
    var highlights: Highlights? = null
        set(value) {
            field = value
            onChanged?.invoke()
        }

    /** What Find has found, for the view to draw: rows from the top of the scrollback. */
    class Highlights(val matches: List<SearchMatch>, val current: Int, val firstRow: Int)

    /** The terminal's rows as text, oldest first, from [firstRow] of the scrollback: what Find searches. */
    class Scrollback(val rows: List<SearchRow>, val firstRow: Int, val totalRows: Int)

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
        if (result != null) {
            onChanged?.invoke()
            onOutput?.invoke()
        }
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

    /**
     * The screen and its scrollback as rows of text, the newest [maxRows] of them, for Find. The
     * terminal hands out its viewport only, so the viewport is walked from the top a screen at a
     * time and put back where it was; it all happens under the lock, so no output lands in between
     * and nothing is drawn half-way. Null once closed.
     */
    fun scrollbackRows(maxRows: Int = MAX_SEARCH_ROWS): Scrollback? = synchronized(lock) {
        val h = handle
        if (h == 0L) return null
        val start = GhosttyVt.nativeFrame(h)?.let(TerminalFrame::parse) ?: return null
        val total = start.scrollTotal
        val first = (total - maxRows).coerceAtLeast(0)
        val rows = ArrayList<SearchRow>(minOf(total, maxRows))
        try {
            GhosttyVt.nativeScroll(h, -total)
            GhosttyVt.nativeScroll(h, first)
            var guard = 0
            while (guard++ < total + 2) {
                val frame = GhosttyVt.nativeFrame(h)?.let(TerminalFrame::parse) ?: break
                for (r in 0 until frame.rows) {
                    val at = frame.scrollOffset + r
                    if (at == first + rows.size && at < total) rows += searchRow(frame, r)
                }
                if (frame.atBottom || first + rows.size >= total) break
                val before = frame.scrollOffset
                GhosttyVt.nativeScroll(h, frame.rows)
                val after = GhosttyVt.nativeFrame(h)?.let(TerminalFrame::parse)?.scrollOffset ?: break
                if (after == before) break
            }
        } finally {
            if (start.atBottom) {
                GhosttyVt.nativeScrollToBottom(h)
            } else {
                GhosttyVt.nativeScroll(h, -total)
                GhosttyVt.nativeScroll(h, start.scrollOffset)
            }
            version++
        }
        Scrollback(rows, first, total)
    }

    private fun searchRow(frame: TerminalFrame, row: Int): SearchRow {
        val cells = ArrayList<String>(frame.columns)
        for (column in 0 until frame.columns) {
            val i = frame.index(column, row)
            cells += when {
                frame.width(i) == 0 -> ""
                frame.isBlank(i) -> " "
                else -> frame.cellText(i)
            }
        }
        return SearchRow(cells, frame.wrapped(row))
    }

    /** Scrolls so that [row] of the scrollback (0 the oldest) is in view, a third of the way down. */
    fun scrollToRow(row: Int) {
        withHandle { h ->
            val frame = GhosttyVt.nativeFrame(h)?.let(TerminalFrame::parse) ?: return@withHandle
            if (row >= frame.scrollOffset && row < frame.scrollOffset + frame.scrollVisible) return@withHandle
            val target = (row - frame.scrollVisible / 3).coerceAtLeast(0)
            GhosttyVt.nativeScroll(h, target - frame.scrollOffset)
            version++
        }
        onChanged?.invoke()
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

    /** The pixels of a stored Kitty image, or null when there is none or they have not all arrived. */
    fun imagePixels(imageId: Int): ImagePixels? {
        val words = withHandle { GhosttyVt.nativeImage(it, imageId) } ?: return null
        if (words.size < 2) return null
        val width = words[0]
        val height = words[1]
        if (width <= 0 || height <= 0 || words.size != 2 + width * height) return null
        return ImagePixels(width, height, words.copyOfRange(2, words.size))
    }

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
        onOutput = null
        selection = null
        highlights = null
        synchronized(lock) {
            if (handle != 0L) GhosttyVt.nativeFree(handle)
            handle = 0L
            cached = null
        }
    }

    companion object {
        private const val RESIZE_DEBOUNCE_MS = 80L

        /** The most rows Find reads: the newest of a longer scrollback are searched, and Find says so. */
        const val MAX_SEARCH_ROWS = 50_000
        private val EMPTY = ByteArray(0)

        /** An empty terminal, for a pane that has no snapshot to start from. */
        fun create(columns: Int, rows: Int): TerminalSession? =
            GhosttyVt.nativeCreate(columns, rows).takeIf { it != 0L }?.let(::TerminalSession)

        /** The terminal a `GHOSTSNP` snapshot describes, or null if libghostty could not read it. */
        fun restore(snapshot: ByteArray): TerminalSession? =
            GhosttyVt.nativeRestore(snapshot).takeIf { it != 0L }?.let(::TerminalSession)
    }
}
