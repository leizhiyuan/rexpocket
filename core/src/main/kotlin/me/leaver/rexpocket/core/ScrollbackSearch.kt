package me.leaver.rexpocket.core

// Find in the pane's scrollback: the rows the terminal holds, searched as the lines a program
// wrote. See docs/design/terminal-controls.md, "Find".

/**
 * One row of the terminal, cell by cell: each column's text, "" for the second half of a wide
 * character (its first half holds it) and " " for an empty cell. [wrapped] when the line carries on
 * into the next row, as the terminal wrapped it rather than the program ending it.
 */
class SearchRow(val cells: List<String>, val wrapped: Boolean)

/**
 * A match, from [row] and [column] to [endRow] and [endColumn] (the column after its last cell).
 * Rows count from the oldest one searched; a match across a wrapped line spans rows.
 */
data class SearchMatch(val row: Int, val column: Int, val endRow: Int, val endColumn: Int) {
    /** The columns of [r] this match covers, as start until end, or null when it misses that row. */
    fun columnsOn(r: Int, columns: Int): IntRange? {
        if (r < row || r > endRow) return null
        val from = if (r == row) column else 0
        val to = if (r == endRow) endColumn else columns
        return if (to > from) from until to else null
    }
}

/**
 * Every place [query] is in [rows], oldest first, at most [limit]. Lines the terminal wrapped are
 * searched whole, so a word broken at the edge is found; the spaces that pad a line the program
 * ended are not text and match nothing. Matches do not overlap. Case is ignored unless
 * [caseSensitive].
 */
fun searchScrollback(rows: List<SearchRow>, query: String, caseSensitive: Boolean = false, limit: Int = MAX_MATCHES): List<SearchMatch> {
    if (query.isEmpty() || rows.isEmpty()) return emptyList()
    val out = ArrayList<SearchMatch>()
    val text = StringBuilder()
    // Where each char of the line came from: its row and column, packed.
    val rowAt = ArrayList<Int>()
    val columnAt = ArrayList<Int>()
    var start = 0
    while (start < rows.size && out.size < limit) {
        text.setLength(0)
        rowAt.clear()
        columnAt.clear()
        var end = start
        while (true) {
            val row = rows[end]
            val last = if (row.wrapped) row.cells.size else lastText(row)
            for (column in 0 until last) {
                val cell = row.cells[column]
                for (c in cell) {
                    text.append(c)
                    rowAt.add(end)
                    columnAt.add(column)
                }
            }
            if (!row.wrapped || end == rows.lastIndex) break
            end++
        }
        val line = text.toString()
        var from = 0
        while (out.size < limit) {
            val at = line.indexOf(query, from, ignoreCase = !caseSensitive)
            if (at < 0) break
            val lastChar = at + query.length - 1
            val lastRow = rowAt[lastChar]
            val lastColumn = columnAt[lastChar]
            // The match ends after the whole of its last cell: a wide character is two columns.
            val width = rows[lastRow].cells.let { cells -> if (lastColumn + 1 < cells.size && cells[lastColumn + 1].isEmpty()) 2 else 1 }
            out += SearchMatch(rowAt[at], columnAt[at], lastRow, lastColumn + width)
            from = at + query.length
        }
        start = end + 1
    }
    return out
}

/** The columns up to the last that holds text: the rest is padding. */
private fun lastText(row: SearchRow): Int {
    var last = row.cells.size
    while (last > 0 && row.cells[last - 1].isBlank()) last--
    return last
}

/** The most matches one search keeps; a count past it says "1000+". */
const val MAX_MATCHES = 1000

/**
 * Which match is current after the matches changed (new output, a new query): the one at the same
 * place as [previous], else the first at or after it, else the last; or, with nothing before, the
 * newest - the bottom of the pane, where the person is.
 */
fun nextCurrent(matches: List<SearchMatch>, previous: SearchMatch?): Int {
    if (matches.isEmpty()) return -1
    if (previous == null) return matches.lastIndex
    matches.indexOf(previous).takeIf { it >= 0 }?.let { return it }
    val after = matches.indexOfFirst { it.row > previous.row || (it.row == previous.row && it.column >= previous.column) }
    return if (after >= 0) after else matches.lastIndex
}
