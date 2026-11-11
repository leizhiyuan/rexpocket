package me.leaver.rexpocket.terminal

/**
 * One picture of the viewport, as libghostty-vt's render state reported it: every cell with its
 * resolved colours, flags and text. The words come from `nativeFrame` in native/ghostty/jni.c,
 * which documents their layout.
 */
internal class TerminalFrame(
    val columns: Int,
    val rows: Int,
    /** The cursor's cell, or -1 when it is hidden or scrolled out of view. */
    val cursorX: Int,
    val cursorY: Int,
    val cursorStyle: Int,
    val foreground: Int,
    val background: Int,
    /** The viewport's place in the scrollback, in rows. */
    val scrollTotal: Int,
    val scrollOffset: Int,
    val scrollVisible: Int,
    val inputFlags: Int,
    val flags: IntArray,
    val fg: IntArray,
    val bg: IntArray,
    private val widths: ByteArray,
    private val links: BooleanArray,
    private val textStart: IntArray,
    private val textLength: IntArray,
    private val text: IntArray,
) {
    val mouseTracking: Boolean get() = inputFlags and INPUT_MOUSE != 0
    val alternateScreen: Boolean get() = inputFlags and INPUT_ALT_SCREEN != 0
    val alternateScroll: Boolean get() = inputFlags and INPUT_ALT_SCROLL != 0

    /** Whether the viewport shows the newest output, rather than something scrolled back to. */
    val atBottom: Boolean get() = scrollOffset + scrollVisible >= scrollTotal

    fun index(column: Int, row: Int): Int = row * columns + column

    /** 1 for a cell, 2 for the head of a wide one, 0 for the tail that belongs to the cell before it. */
    fun width(index: Int): Int = widths[index].toInt()

    fun hasLink(index: Int): Boolean = links[index]

    fun isBlank(index: Int): Boolean = textLength[index] == 0 || (textLength[index] == 1 && text[textStart[index]] == ' '.code)

    /** The text of a cell as UTF-16, empty for a cell nothing was written to. */
    fun cellText(index: Int): String = String(text, textStart[index], textLength[index])

    /** The single code point of a plain cell, or -1 when it holds nothing or a grapheme cluster. */
    fun singleCodePoint(index: Int): Int = if (textLength[index] == 1) text[textStart[index]] else -1

    /** Whether the row carries on into the next one: a line the terminal wrapped, not one the program ended. */
    fun wrapped(row: Int): Boolean = columns > 0 && flags[index(0, row)] and FLAG_WRAPPED != 0

    /**
     * The text of columns [from]..[to] of a row, one space for each empty cell. The tail of a wide
     * character adds nothing; its head already did.
     */
    fun rowText(row: Int, from: Int = 0, to: Int = columns - 1): String {
        val out = StringBuilder()
        for (column in from.coerceAtLeast(0)..to.coerceAtMost(columns - 1)) {
            val i = index(column, row)
            if (width(i) == 0) continue
            if (textLength[i] == 0) out.append(' ') else out.append(cellText(i))
        }
        return out.toString()
    }

    companion object {
        const val FLAG_BOLD = 1 shl 0
        const val FLAG_ITALIC = 1 shl 1
        const val FLAG_FAINT = 1 shl 2
        const val FLAG_INVERSE = 1 shl 3
        const val FLAG_INVISIBLE = 1 shl 4
        const val FLAG_STRIKETHROUGH = 1 shl 5
        const val FLAG_UNDERLINE = 1 shl 6
        const val FLAG_FG_DEFAULT = 1 shl 7
        const val FLAG_BG_DEFAULT = 1 shl 8
        const val FLAG_WRAPPED = 1 shl 27

        const val INPUT_MOUSE = 1
        const val INPUT_ALT_SCREEN = 2
        const val INPUT_ALT_SCROLL = 4

        const val CURSOR_BAR = 0
        const val CURSOR_BLOCK = 1
        const val CURSOR_UNDERLINE = 2
        const val CURSOR_HOLLOW = 3

        private const val HEADER = 12
        private const val CELL_WORDS = 5

        /** Reads what `nativeFrame` returned; throws if the words do not describe a whole viewport. */
        fun parse(words: IntArray): TerminalFrame {
            require(words.size >= HEADER) { "frame header is ${words.size} words" }
            val columns = words[0]
            val rows = words[1]
            require(columns > 0 && rows > 0) { "frame is ${columns}x$rows" }
            val count = columns * rows
            val background = words[5]
            val foreground = words[4]
            val flags = IntArray(count) { FLAG_FG_DEFAULT or FLAG_BG_DEFAULT }
            val fg = IntArray(count) { foreground }
            val bg = IntArray(count) { background }
            val widths = ByteArray(count) { 1 }
            val links = BooleanArray(count)
            val start = IntArray(count)
            val length = IntArray(count)
            var text = IntArray(count)
            var used = 0
            var at = HEADER
            repeat(words[10]) {
                require(at + CELL_WORDS <= words.size) { "frame ends inside a cell" }
                val x = words[at] and 0xffff
                val y = words[at] ushr 16
                require(x < columns && y < rows) { "cell $x,$y is outside ${columns}x$rows" }
                val packed = words[at + 4]
                val glyphs = packed ushr 8
                require(at + CELL_WORDS + glyphs <= words.size) { "frame ends inside a cell's text" }
                val i = y * columns + x
                flags[i] = words[at + 1]
                fg[i] = words[at + 2]
                bg[i] = words[at + 3]
                widths[i] = (packed and 0xf).toByte()
                links[i] = packed and 0x10 != 0
                if (used + glyphs > text.size) text = text.copyOf(maxOf(text.size * 2, used + glyphs))
                System.arraycopy(words, at + CELL_WORDS, text, used, glyphs)
                start[i] = used
                length[i] = glyphs
                used += glyphs
                at += CELL_WORDS + glyphs
            }
            return TerminalFrame(
                columns = columns,
                rows = rows,
                cursorX = words[2],
                cursorY = words[3],
                cursorStyle = words[11],
                foreground = foreground,
                background = background,
                scrollTotal = words[6],
                scrollOffset = words[7],
                scrollVisible = words[8],
                inputFlags = words[9],
                flags = flags,
                fg = fg,
                bg = bg,
                widths = widths,
                links = links,
                textStart = start,
                textLength = length,
                text = text,
            )
        }
    }
}
