package me.leaver.rexpocket.terminal

import java.net.URI

/** Finding the web link under a cell: one the program set with OSC 8 comes from the terminal, any other from the text. */
internal object TerminalLinks {
    private val candidate = Regex("""(?i)(?<![\p{L}\p{N}_@/])(?:https?://|www\.)[^\s\p{Cc}<>"`“”]+""")

    /** The address if it is a plain web page, else null: nothing else is offered to open. */
    fun webUrl(value: String): String? {
        val normalized = if (value.startsWith("www.", ignoreCase = true)) "https://$value" else value
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val web = uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)
        return normalized.takeIf { web && !uri.host.isNullOrBlank() }
    }

    /**
     * The web address printed over the cell at [column],[row]. An address the terminal wrapped onto
     * the next row is one address; a stray full stop, comma or closing bracket after it is not part of it.
     */
    fun urlAt(frame: TerminalFrame, column: Int, row: Int): String? {
        if (column !in 0 until frame.columns || row !in 0 until frame.rows) return null
        var top = row
        while (top > 0 && frame.wrapped(top - 1)) top--
        var bottom = row
        while (bottom < frame.rows - 1 && frame.wrapped(bottom)) bottom++

        val text = StringBuilder()
        val rowOf = ArrayList<Int>()
        val from = ArrayList<Int>()
        val to = ArrayList<Int>()
        for (r in top..bottom) {
            for (c in 0 until frame.columns) {
                val i = frame.index(c, r)
                val width = frame.width(i)
                if (width == 0) continue
                val glyph = if (frame.isBlank(i)) " " else frame.cellText(i)
                text.append(glyph)
                repeat(glyph.length) {
                    rowOf.add(r)
                    from.add(c)
                    to.add(c + width)
                }
            }
        }
        val pressed = text.indices.firstOrNull { rowOf[it] == row && column >= from[it] && column < to[it] } ?: return null
        for (match in candidate.findAll(text)) {
            val value = trimTail(match.value)
            if (pressed in match.range.first until match.range.first + value.length) return webUrl(value)
        }
        return null
    }

    private fun trimTail(match: String): String {
        var value = match
        while (value.isNotEmpty()) {
            val last = value.last()
            val opener = when (last) {
                ')' -> '('
                ']' -> '['
                '}' -> '{'
                else -> null
            }
            val unbalanced = opener != null && value.count { it == last } > value.count { it == opener }
            if (last in ".,;!:'’" || unbalanced) value = value.dropLast(1) else break
        }
        return value
    }
}
