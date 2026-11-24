package me.leaver.rexpocket.ui

import androidx.compose.ui.text.input.TextFieldValue
import me.leaver.rexpocket.core.SearchMatch
import me.leaver.rexpocket.core.SearchRow
import me.leaver.rexpocket.terminal.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Find keeps between searches: the match it is on, as output comes and the query changes. */
class FindModelTest {
    private fun rows(vararg lines: String) = lines.map { line -> SearchRow(line.padEnd(10).map { it.toString() }, wrapped = false) }

    private fun scrollback(first: Int, vararg lines: String) = TerminalSession.Scrollback(rows(*lines), first, first + lines.size)

    private fun model(query: String) = FindModel().apply { this.query = TextFieldValue(query) }

    @Test
    fun aNewSearchStartsOnTheNewestMatchAndStepsRoundTheEnds() {
        val find = model("err")
        find.take(scrollback(0, "err one", "ok", "err two", "err three"))
        assertEquals(3, find.matches.size)
        assertEquals(2, find.current)
        assertEquals(3, find.currentRow)
        find.step(forward = true)
        assertEquals("past the newest is the oldest", 0, find.current)
        find.step(forward = false)
        assertEquals(2, find.current)
        find.step(forward = false)
        assertEquals(1, find.current)
        assertEquals(2, find.currentRow)
    }

    @Test
    fun outputThatPushesTheOldestRowsOutKeepsFindOnItsMatch() {
        val find = model("err")
        find.take(scrollback(0, "err one", "ok", "err two", "err three"))
        find.step(forward = false)
        assertEquals(2, find.currentRow)
        // Two rows went out of the top and two came in at the bottom: the same match, two rows up.
        find.take(scrollback(2, "err two", "err three", "err four", "x"))
        assertEquals(SearchMatch(0, 0, 0, 3), find.matches[find.current])
        assertEquals(2, find.currentRow)
    }

    @Test
    fun noQueryOrNoTerminalFindsNothing() {
        val find = model("")
        find.take(scrollback(0, "err"))
        assertTrue(find.matches.isEmpty())
        assertNull(find.currentRow)
        val other = model("err")
        other.take(null)
        assertTrue(other.matches.isEmpty())
        assertTrue("a search ran, and found nothing", other.searched)
    }

    @Test
    fun caseCountsOnlyWhenAskedFor() {
        val find = model("Err")
        find.take(scrollback(0, "err", "Err"))
        assertEquals(2, find.matches.size)
        find.caseSensitive = true
        find.take(scrollback(0, "err", "Err"))
        assertEquals(1, find.matches.size)
        assertEquals(1, find.currentRow)
    }
}
