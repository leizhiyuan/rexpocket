package me.leaver.rexpocket.terminal

/** A selection over one frozen frame. Coordinates are cells, never UTF-16 offsets. */
internal class TerminalSelection(val frame: TerminalFrame, column: Int, row: Int) {
    data class Point(val column: Int, val row: Int) : Comparable<Point> {
        override fun compareTo(other: Point): Int = compareValuesBy(this, other, Point::row, Point::column)
    }

    var anchor = point(column, row)
        private set
    var extent = anchor
        private set

    val first: Point get() = minOf(anchor, extent)
    val last: Point get() = maxOf(anchor, extent)

    /** A cell inside the frame, moved to the head of a wide character if it landed on its tail. */
    private fun point(column: Int, row: Int): Point {
        val r = row.coerceIn(0, frame.rows - 1)
        var c = column.coerceIn(0, frame.columns - 1)
        if (c > 0 && frame.width(frame.index(c, r)) == 0) c--
        return Point(c, r)
    }

    /** Whether a cell holds nothing. The tail of a wide character holds no text of its own, but is not empty. */
    private fun blank(column: Int, row: Int): Boolean {
        val i = frame.index(column, row)
        return frame.width(i) != 0 && frame.isBlank(i)
    }

    /** Selects the run of non-blank cells around the anchor; a blank cell selects itself only. */
    fun word() {
        val origin = anchor
        if (blank(origin.column, origin.row)) return
        var left = origin.column
        var right = origin.column
        while (left > 0 && !blank(left - 1, origin.row)) left--
        while (right < frame.columns - 1 && !blank(right + 1, origin.row)) right++
        anchor = point(left, origin.row)
        extent = point(right, origin.row)
    }

    fun move(column: Int, row: Int, moveAnchor: Boolean = false) {
        if (moveAnchor) anchor = point(column, row) else extent = point(column, row)
    }

    fun all() {
        anchor = Point(0, 0)
        extent = point(frame.columns - 1, frame.rows - 1)
    }

    /** The column just after the cell [point] names, which is where a selection that ends on it ends. */
    fun endColumn(point: Point): Int = point.column + maxOf(1, frame.width(frame.index(point.column, point.row)))

    /** The selected text: rows the terminal wrapped run together, the others end in a line break. */
    fun text(): String {
        val out = StringBuilder()
        val from = first
        val to = last
        for (row in from.row..to.row) {
            val start = if (row == from.row) from.column else 0
            val end = if (row == to.row) endColumn(to) - 1 else frame.columns - 1
            val joinsNext = row < to.row && frame.wrapped(row)
            var line = frame.rowText(row, start, end)
            if (end >= frame.columns - 1 && !joinsNext) line = line.trimEnd()
            out.append(line)
            if (row < to.row && !joinsNext) out.append('\n')
        }
        return out.toString().trimEnd('\n')
    }
}
