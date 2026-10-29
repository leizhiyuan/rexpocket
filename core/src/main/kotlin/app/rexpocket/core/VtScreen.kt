package app.rexpocket.core

/**
 * Enough of a VT100/xterm screen to replay `com.superlogical.terminal.format`
 * (`format=vt`). Not a Ghostty snapshot decoder: Rex's data stream starts with
 * one, and the server documents `format` as the way to read the screen instead.
 */
class VtScreen(var cols: Int = 80, var rows: Int = 24) {
    data class Cell(
        var grapheme: String = " ",
        var wideTail: Boolean = false,
        var fg: Int = -1,
        var bg: Int = -1,
        var bold: Boolean = false,
        var underline: Boolean = false,
        var inverse: Boolean = false,
    ) {
        fun blank() {
            grapheme = " "
            wideTail = false
            fg = -1
            bg = -1
            bold = false
            underline = false
            inverse = false
        }

        fun copy(): Cell = Cell(grapheme, wideTail, fg, bg, bold, underline, inverse)
    }

    data class CellSnap(
        val ch: String,
        val fg: Int,
        val bg: Int,
        val bold: Boolean,
        val underline: Boolean,
        val inverse: Boolean,
        val wideTail: Boolean,
    )

    data class Snapshot(
        val cols: Int,
        val rows: Int,
        val cursorX: Int,
        val cursorY: Int,
        val cursorOn: Boolean,
        val defaultFg: Int,
        val defaultBg: Int,
        val cursorColor: Int,
        val scrollback: List<String>,
        val screen: List<List<CellSnap>>,
    ) {
        fun plainScreen(): String = screen.joinToString("\n") { row ->
            buildString {
                for (cell in row) {
                    if (!cell.wideTail) append(if (cell.ch.isEmpty()) " " else cell.ch)
                }
            }.trimEnd()
        }
    }

    var cursorX: Int = 0
    var cursorY: Int = 0
    var cursorOn: Boolean = true
    var defaultFg: Int = rgb(197, 200, 198)
    var defaultBg: Int = rgb(29, 31, 33)
    var cursorColor: Int = rgb(0, 255, 65)

    private val palette = IntArray(256) { xterm256(it) }
    private var grid: Array<Array<Cell>> = Array(rows) { blankRow() }
    private var altGrid: Array<Array<Cell>>? = null
    private var altCursor: Pair<Int, Int> = 0 to 0
    private var alt = false
    private val scrollback = ArrayDeque<Array<Cell>>()
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var wrapPending = false

    private var bold = false
    private var underline = false
    private var inverse = false
    private var fg = -1
    private var bg = -1
    private var saved: Saved? = null

    private data class Saved(val x: Int, val y: Int, val fg: Int, val bg: Int, val bold: Boolean, val underline: Boolean, val inverse: Boolean)

    fun feed(text: String) {
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '\u001b' -> {
                    if (i + 1 >= text.length) break
                    when (text[i + 1]) {
                        '[' -> i = csi(text, i + 2)
                        ']' -> i = osc(text, i + 2)
                        '_' -> i = skipSt(text, i + 2)
                        'c' -> { softReset(); i += 2 }
                        '7' -> { saveCursor(); i += 2 }
                        '8' -> { restoreCursor(); i += 2 }
                        'D' -> { lineFeed(); i += 2 }
                        'M' -> { reverseIndex(); i += 2 }
                        '(', ')' -> i = (i + 3).coerceAtMost(text.length)
                        else -> i += 2
                    }
                }
                ch == '\r' -> { cursorX = 0; wrapPending = false; i++ }
                ch == '\n' -> { lineFeed(); i++ }
                ch == '\b' -> { if (cursorX > 0) cursorX--; wrapPending = false; i++ }
                ch == '\t' -> { cursorX = ((cursorX / 8) + 1) * 8; if (cursorX >= cols) cursorX = cols - 1; wrapPending = false; i++ }
                ch == '\u0000' || ch == '\u0007' -> i++
                ch.isISOControl() -> i++
                else -> {
                    val cp = text.codePointAt(i)
                    val count = Character.charCount(cp)
                    putCodePoint(cp)
                    i += count
                }
            }
        }
    }

    fun snapshot(maxScrollback: Int = 300): Snapshot {
        val history = scrollback.takeLast(maxScrollback).map { row ->
            buildString {
                for (cell in row) if (!cell.wideTail) append(if (cell.grapheme.isEmpty()) " " else cell.grapheme)
            }.trimEnd()
        }
        val screen = grid.map { row ->
            row.map { c ->
                CellSnap(
                    ch = if (c.wideTail) "" else c.grapheme,
                    fg = c.fg,
                    bg = c.bg,
                    bold = c.bold,
                    underline = c.underline,
                    inverse = c.inverse,
                    wideTail = c.wideTail,
                )
            }
        }
        return Snapshot(cols, rows, cursorX, cursorY, cursorOn, defaultFg, defaultBg, cursorColor, history, screen)
    }

    fun rowText(row: Int): String {
        if (row !in grid.indices) return ""
        return buildString {
            for (cell in grid[row]) if (!cell.wideTail) append(if (cell.grapheme.isEmpty()) " " else cell.grapheme)
        }.trimEnd()
    }

    fun cellAt(row: Int, col: Int): Cell? = grid.getOrNull(row)?.getOrNull(col)

    private fun softReset() {
        grid = Array(rows) { blankRow() }
        altGrid = null
        alt = false
        cursorX = 0
        cursorY = 0
        cursorOn = true
        wrapPending = false
        scrollTop = 0
        scrollBottom = rows - 1
        scrollback.clear()
        resetStyle()
    }

    private fun resetStyle() {
        bold = false
        underline = false
        inverse = false
        fg = -1
        bg = -1
    }

    private fun blankRow(): Array<Cell> = Array(cols) { Cell() }

    private fun putCodePoint(cp: Int) {
        val width = charWidth(cp)
        if (width <= 0) return
        if (wrapPending || cursorX + width > cols) {
            cursorX = 0
            lineFeed()
            wrapPending = false
        }
        val cell = grid[cursorY][cursorX]
        cell.blank()
        cell.grapheme = String(Character.toChars(cp))
        cell.fg = fg
        cell.bg = bg
        cell.bold = bold
        cell.underline = underline
        cell.inverse = inverse
        if (width == 2 && cursorX + 1 < cols) {
            val tail = grid[cursorY][cursorX + 1]
            tail.blank()
            tail.wideTail = true
            tail.bg = bg
            tail.grapheme = ""
        }
        cursorX += width
        if (cursorX >= cols) {
            cursorX = cols - 1
            wrapPending = true
        }
    }

    private fun lineFeed() {
        wrapPending = false
        if (cursorY == scrollBottom) scrollUp(1) else if (cursorY < rows - 1) cursorY++
    }

    private fun reverseIndex() {
        if (cursorY == scrollTop) scrollDown(1) else if (cursorY > 0) cursorY--
    }

    private fun scrollUp(n: Int) {
        repeat(n) {
            if (scrollTop == 0 && !alt) {
                scrollback.addLast(grid[0].map { it.copy() }.toTypedArray())
                while (scrollback.size > 2000) scrollback.removeFirst()
            }
            for (r in scrollTop until scrollBottom) grid[r] = grid[r + 1]
            grid[scrollBottom] = blankRow()
        }
    }

    private fun scrollDown(n: Int) {
        repeat(n) {
            for (r in scrollBottom downTo scrollTop + 1) grid[r] = grid[r - 1]
            grid[scrollTop] = blankRow()
        }
    }

    private fun saveCursor() {
        saved = Saved(cursorX, cursorY, fg, bg, bold, underline, inverse)
    }

    private fun restoreCursor() {
        val s = saved ?: return
        cursorX = s.x.coerceIn(0, cols - 1)
        cursorY = s.y.coerceIn(0, rows - 1)
        fg = s.fg
        bg = s.bg
        bold = s.bold
        underline = s.underline
        inverse = s.inverse
        wrapPending = false
    }

    private fun csi(text: String, start: Int): Int {
        var i = start
        if (i >= text.length) return i
        var priv = false
        if (text[i] == '?') {
            priv = true
            i++
        }
        val params = ArrayList<Int>(4)
        var cur = -1
        var saw = false
        while (i < text.length) {
            val ch = text[i]
            when {
                ch in '0'..'9' -> {
                    if (cur < 0) cur = 0
                    cur = (cur * 10 + (ch - '0')).coerceAtMost(9999)
                    saw = true
                    i++
                }
                ch == ';' -> {
                    params.add(if (cur < 0) 0 else cur)
                    cur = -1
                    i++
                }
                ch in '\u0020'..'\u002F' -> i++
                else -> break
            }
        }
        if (saw || params.isNotEmpty()) params.add(if (cur < 0) 0 else cur)
        if (i >= text.length) return i
        val final = text[i]
        i++
        if (priv) decPrivate(final, params) else csiFinal(final, params)
        return i
    }

    private fun p(params: List<Int>, index: Int, default: Int): Int {
        val v = params.getOrNull(index) ?: return default
        return if (v == 0) default else v
    }

    private fun csiFinal(final: Char, params: List<Int>) {
        when (final) {
            'm' -> sgr(if (params.isEmpty()) listOf(0) else params)
            'H', 'f' -> {
                cursorY = (p(params, 0, 1) - 1).coerceIn(0, rows - 1)
                cursorX = (p(params, 1, 1) - 1).coerceIn(0, cols - 1)
                wrapPending = false
            }
            'A' -> { cursorY = (cursorY - p(params, 0, 1)).coerceAtLeast(0); wrapPending = false }
            'B' -> { cursorY = (cursorY + p(params, 0, 1)).coerceAtMost(rows - 1); wrapPending = false }
            'C' -> { cursorX = (cursorX + p(params, 0, 1)).coerceAtMost(cols - 1); wrapPending = false }
            'D' -> { cursorX = (cursorX - p(params, 0, 1)).coerceAtLeast(0); wrapPending = false }
            'G' -> { cursorX = (p(params, 0, 1) - 1).coerceIn(0, cols - 1); wrapPending = false }
            'd' -> { cursorY = (p(params, 0, 1) - 1).coerceIn(0, rows - 1); wrapPending = false }
            'J' -> eraseDisplay(params.firstOrNull() ?: 0)
            'K' -> eraseLine(params.firstOrNull() ?: 0)
            'L' -> insertLines(p(params, 0, 1))
            'M' -> deleteLines(p(params, 0, 1))
            '@' -> insertChars(p(params, 0, 1))
            'P' -> deleteChars(p(params, 0, 1))
            'X' -> eraseChars(p(params, 0, 1))
            'r' -> {
                val top = (p(params, 0, 1) - 1).coerceIn(0, rows - 1)
                val bot = (p(params, 1, rows) - 1).coerceIn(0, rows - 1)
                if (top < bot) {
                    scrollTop = top
                    scrollBottom = bot
                }
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            'h', 'l' -> {
                // SM/RM without private marker. Ignore unknown.
            }
        }
    }

    private fun decPrivate(final: Char, params: List<Int>) {
        val on = final == 'h'
        for (mode in params) {
            when (mode) {
                25 -> cursorOn = on
                47, 1047, 1049 -> if (on) enterAlt(mode == 1049) else leaveAlt(mode == 1049)
            }
        }
    }

    private fun enterAlt(clear: Boolean) {
        if (alt) return
        altGrid = grid
        altCursor = cursorX to cursorY
        grid = Array(rows) { blankRow() }
        alt = true
        if (clear) {
            cursorX = 0
            cursorY = 0
        }
        wrapPending = false
    }

    private fun leaveAlt(restore: Boolean) {
        if (!alt) return
        grid = altGrid ?: grid
        altGrid = null
        alt = false
        if (restore) {
            cursorX = altCursor.first
            cursorY = altCursor.second
        }
        wrapPending = false
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseLine(0)
                for (r in cursorY + 1 until rows) grid[r] = blankRow()
            }
            1 -> {
                eraseLine(1)
                for (r in 0 until cursorY) grid[r] = blankRow()
            }
            2, 3 -> {
                for (r in 0 until rows) grid[r] = blankRow()
                if (mode == 3) scrollback.clear()
            }
        }
    }

    private fun eraseLine(mode: Int) {
        val row = grid.getOrNull(cursorY) ?: return
        val range = when (mode) {
            1 -> 0..cursorX
            2 -> row.indices
            else -> cursorX until cols
        }
        for (c in range) row[c].blank()
    }

    private fun eraseChars(n: Int) {
        val row = grid.getOrNull(cursorY) ?: return
        for (c in cursorX until (cursorX + n).coerceAtMost(cols)) row[c].blank()
    }

    private fun insertLines(n: Int) {
        if (cursorY !in scrollTop..scrollBottom) return
        repeat(n.coerceAtMost(rows)) {
            for (r in scrollBottom downTo cursorY + 1) grid[r] = grid[r - 1]
            grid[cursorY] = blankRow()
        }
    }

    private fun deleteLines(n: Int) {
        if (cursorY !in scrollTop..scrollBottom) return
        repeat(n.coerceAtMost(rows)) {
            for (r in cursorY until scrollBottom) grid[r] = grid[r + 1]
            grid[scrollBottom] = blankRow()
        }
    }

    private fun insertChars(n: Int) {
        val row = grid.getOrNull(cursorY) ?: return
        val count = n.coerceAtMost(cols)
        for (c in cols - 1 downTo cursorX + count) row[c] = row[c - count]
        for (c in cursorX until (cursorX + count).coerceAtMost(cols)) row[c] = Cell()
    }

    private fun deleteChars(n: Int) {
        val row = grid.getOrNull(cursorY) ?: return
        val count = n.coerceAtMost(cols)
        for (c in cursorX until cols - count) row[c] = row[c + count]
        for (c in (cols - count).coerceAtLeast(cursorX) until cols) row[c] = Cell()
    }

    private fun sgr(params: List<Int>) {
        var i = 0
        while (i < params.size) {
            when (val p = params[i]) {
                0 -> resetStyle()
                1 -> bold = true
                22 -> bold = false
                4 -> underline = true
                24 -> underline = false
                7 -> inverse = true
                27 -> inverse = false
                in 30..37 -> fg = if (bold) palette[p - 30 + 8] else palette[p - 30]
                in 40..47 -> bg = palette[p - 40]
                in 90..97 -> fg = palette[p - 90 + 8]
                in 100..107 -> bg = palette[p - 100 + 8]
                39 -> fg = -1
                49 -> bg = -1
                38, 48 -> {
                    val color = readColor(params, i)
                    if (color != null) {
                        if (p == 38) fg = color.second else bg = color.second
                        i = color.first
                    }
                }
            }
            i++
        }
    }

    /** Returns the index of the last consumed param and the color. */
    private fun readColor(params: List<Int>, at: Int): Pair<Int, Int>? {
        val mode = params.getOrNull(at + 1) ?: return null
        return when (mode) {
            5 -> {
                val idx = params.getOrNull(at + 2) ?: return null
                (at + 2) to palette[idx.coerceIn(0, 255)]
            }
            2 -> {
                val r = params.getOrNull(at + 2) ?: return null
                val g = params.getOrNull(at + 3) ?: return null
                val b = params.getOrNull(at + 4) ?: return null
                (at + 4) to rgb(r, g, b)
            }
            else -> null
        }
    }

    private fun osc(text: String, start: Int): Int {
        val end = skipSt(text, start)
        val bodyEnd = when {
            end >= 2 && text[end - 1] == '\\' && text[end - 2] == '\u001b' -> end - 2
            end >= 1 && end <= text.length && text.getOrNull(end - 1) == '\u0007' -> end - 1
            else -> end
        }
        if (start < bodyEnd) handleOsc(text.substring(start, bodyEnd))
        return end
    }

    private fun skipSt(text: String, start: Int): Int {
        var i = start
        while (i < text.length) {
            if (text[i] == '\u0007') return i + 1
            if (text[i] == '\u001b' && i + 1 < text.length && text[i + 1] == '\\') return i + 2
            i++
        }
        return i
    }

    private fun handleOsc(body: String) {
        val parts = body.split(';')
        when (parts.firstOrNull()) {
            "4" -> {
                var i = 1
                while (i + 1 < parts.size) {
                    val idx = parts[i].toIntOrNull() ?: break
                    val color = parseColor(parts[i + 1]) ?: break
                    if (idx in palette.indices) palette[idx] = color
                    i += 2
                }
            }
            "10" -> parseColor(parts.getOrNull(1).orEmpty())?.let { defaultFg = it }
            "11" -> parseColor(parts.getOrNull(1).orEmpty())?.let { defaultBg = it }
            "12" -> parseColor(parts.getOrNull(1).orEmpty())?.let { cursorColor = it }
        }
    }

    companion object {
        fun rgb(r: Int, g: Int, b: Int): Int {
            fun b8(v: Int) = v.coerceIn(0, 255)
            return (0xFF shl 24) or (b8(r) shl 16) or (b8(g) shl 8) or b8(b)
        }

        fun parseColor(raw: String): Int? {
            val text = raw.trim()
            if (text.startsWith('#')) {
                val hex = text.drop(1)
                if (hex.length == 6) return hex.toIntOrNull(16)?.let { (0xFF shl 24) or it }
            }
            if (text.startsWith("rgb:")) {
                val comps = text.removePrefix("rgb:").split('/')
                if (comps.size == 3) {
                    fun comp(s: String): Int? {
                        val v = s.toIntOrNull(16) ?: return null
                        return if (s.length >= 4) v shr 8 else v
                    }
                    val r = comp(comps[0]) ?: return null
                    val g = comp(comps[1]) ?: return null
                    val b = comp(comps[2]) ?: return null
                    return rgb(r, g, b)
                }
            }
            return null
        }

        fun xterm256(index: Int): Int {
            val ansi = intArrayOf(
                rgb(29, 31, 33), rgb(204, 102, 102), rgb(181, 189, 104), rgb(240, 198, 116),
                rgb(129, 162, 190), rgb(178, 148, 187), rgb(138, 190, 183), rgb(197, 200, 198),
                rgb(102, 102, 102), rgb(213, 78, 83), rgb(185, 202, 74), rgb(231, 197, 71),
                rgb(122, 166, 218), rgb(195, 151, 216), rgb(112, 192, 177), rgb(234, 234, 234),
            )
            if (index < 16) return ansi[index]
            if (index >= 232) {
                val c = 8 + (index - 232) * 10
                return rgb(c, c, c)
            }
            val n = index - 16
            fun q(v: Int) = if (v == 0) 0 else 55 + v * 40
            return rgb(q(n / 36), q((n % 36) / 6), q(n % 6))
        }

        fun charWidth(cp: Int): Int {
            if (cp < 0x20 || cp in 0x7f..0x9f) return 0
            if (cp in 0x300..0x36f || cp in 0x1ab0..0x1aff || cp in 0x1dc0..0x1dff || cp in 0x20d0..0x20ff || cp in 0xfe20..0xfe2f) return 0
            if (cp in 0x1100..0x115f || cp == 0x2329 || cp == 0x232a || cp in 0x2e80..0xa4cf ||
                cp in 0xac00..0xd7a3 || cp in 0xf900..0xfaff || cp in 0xfe10..0xfe19 ||
                cp in 0xfe30..0xfe6f || cp in 0xff00..0xff60 || cp in 0xffe0..0xffe6 ||
                cp in 0x1f300..0x1f64f || cp in 0x1f900..0x1f9ff || cp in 0x20000..0x3fffd
            ) return 2
            return 1
        }
    }
}
