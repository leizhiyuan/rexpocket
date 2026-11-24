package me.leaver.rexpocket.core

// What TalkBack reads of the terminal: the screen as lines, and the new ones read out as they come.
// The view (TerminalView's accessibility helper) feeds these from libghostty's frame; the rules are
// here so they are tested on the JVM.

/** A line of the screen as a screen reader reads it: the rows it spans and its words. */
data class ScreenLine(val firstRow: Int, val lastRow: Int, val text: String)

/**
 * The screen's [rows] as the lines the program wrote: a row the terminal [wrapped] is joined with
 * the next, as one sentence a reader can follow. Trailing spaces go, and so do blank lines, so
 * swiping from line to line never stops on empty rows.
 */
fun screenLines(rows: List<String>, wrapped: List<Boolean>): List<ScreenLine> {
    val out = ArrayList<ScreenLine>()
    var first = 0
    val text = StringBuilder()
    for (row in rows.indices) {
        text.append(rows[row])
        if (wrapped.getOrElse(row) { false } && row < rows.lastIndex) continue
        val line = text.toString().trimEnd()
        if (line.isNotBlank()) out += ScreenLine(first, row, line)
        text.setLength(0)
        first = row + 1
    }
    return out
}

/**
 * Says what a program prints while the person listens on the pane: only lines that were not on the
 * screen before, at most once every [intervalMs], and at most [maxChars] at a time, so a busy
 * program does not drown the reader. The line the cursor is on is left out while it is still being
 * written: that is the prompt or what is being typed, which the keyboard already echoes.
 */
class OutputAnnouncer(private val intervalMs: Long = 1500, private val maxChars: Int = 400) {
    private var seen: List<String>? = null
    private val pending = ArrayList<String>()
    private var spokeAt = Long.MIN_VALUE / 2

    /** Whether it is listening: from [start] to [stop]. */
    val listening: Boolean get() = seen != null

    /** Starts listening with [lines] on screen: what is already there is not new. */
    fun start(lines: List<String>) {
        seen = lines
        pending.clear()
    }

    /** Stops listening, and forgets what was waiting to be said. */
    fun stop() {
        seen = null
        pending.clear()
    }

    /** The screen now shows [lines], the cursor on [cursorLine] (an index into them, or -1). */
    fun changed(lines: List<String>, cursorLine: Int) {
        val before = seen ?: return
        if (lines == before) return
        val left = HashMap<String, Int>()
        for (line in before) left[line] = (left[line] ?: 0) + 1
        lines.forEachIndexed { i, line ->
            val count = left[line] ?: 0
            if (count > 0) left[line] = count - 1 else if (i != cursorLine) pending += line
        }
        seen = lines
    }

    /** When what is waiting may be said, or null when nothing is. */
    fun dueAt(): Long? = if (pending.isEmpty()) null else spokeAt + intervalMs

    /** What to say at [now], if anything is waiting and the last was long enough ago. */
    fun take(now: Long): String? {
        if (pending.isEmpty() || now < spokeAt + intervalMs) return null
        val out = StringBuilder()
        for (line in pending) {
            if (out.isNotEmpty() && out.length + 1 + line.length > maxChars) break
            if (out.isNotEmpty()) out.append('\n')
            out.append(if (line.length > maxChars) line.take(maxChars) else line)
        }
        pending.clear()
        spokeAt = now
        return out.toString()
    }
}
