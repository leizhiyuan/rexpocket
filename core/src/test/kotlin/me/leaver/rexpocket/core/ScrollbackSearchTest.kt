package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Find in the scrollback, on rows as the terminal holds them. */
class ScrollbackSearchTest {
    /** Rows of [columns] cells; a row ending in `\` wraps into the next. Wide characters are given as `W` followed by `_`. */
    private fun rows(columns: Int, vararg lines: String): List<SearchRow> = lines.map { line ->
        val wrapped = line.endsWith("\\")
        val body = line.removeSuffix("\\")
        val cells = ArrayList<String>()
        var i = 0
        while (i < body.length) {
            when {
                body[i] == '_' -> cells += ""
                body[i] == 'W' -> cells += "你"
                else -> cells += body[i].toString()
            }
            i++
        }
        while (cells.size < columns) cells += " "
        SearchRow(cells, wrapped)
    }

    @Test
    fun everyPlaceOnEveryRowOldestFirst() {
        val r = rows(10, "make all", "ok  make", "done")
        assertEquals(
            listOf(SearchMatch(0, 0, 0, 4), SearchMatch(1, 4, 1, 8)),
            searchScrollback(r, "make"),
        )
    }

    @Test
    fun caseIsIgnoredUnlessAskedFor() {
        val r = rows(10, "Error here", "error")
        assertEquals(2, searchScrollback(r, "ERROR").size)
        assertEquals(listOf(SearchMatch(1, 0, 1, 5)), searchScrollback(r, "error", caseSensitive = true))
    }

    @Test
    fun aWordTheTerminalWrappedIsFoundAcrossTheRows() {
        val r = rows(5, "xxpan\\", "ic ok")
        assertEquals(listOf(SearchMatch(0, 2, 1, 2)), searchScrollback(r, "panic"))
        val m = searchScrollback(r, "panic").single()
        assertEquals(2 until 5, m.columnsOn(0, 5))
        assertEquals(0 until 2, m.columnsOn(1, 5))
        assertNull(m.columnsOn(2, 5))
    }

    @Test
    fun aLineTheProgramEndedDoesNotRunIntoTheNext() {
        val r = rows(6, "foo", "bar")
        assertEquals(emptyList<SearchMatch>(), searchScrollback(r, "obar"))
        assertEquals(emptyList<SearchMatch>(), searchScrollback(r, "foo "))
    }

    @Test
    fun aWideCharacterCoversBothItsColumns() {
        val r = rows(8, "aW_W_b")
        assertEquals(listOf(SearchMatch(0, 1, 0, 5)), searchScrollback(r, "你你"))
        assertEquals(listOf(SearchMatch(0, 3, 0, 6)), searchScrollback(r, "你b"))
    }

    @Test
    fun matchesDoNotOverlapAndStopAtTheLimit() {
        val r = rows(10, "aaaa")
        assertEquals(listOf(SearchMatch(0, 0, 0, 2), SearchMatch(0, 2, 0, 4)), searchScrollback(r, "aa"))
        val many = rows(4, *Array(50) { "ab" })
        assertEquals(10, searchScrollback(many, "a", limit = 10).size)
        assertEquals(emptyList<SearchMatch>(), searchScrollback(many, ""))
    }

    @Test
    fun theCurrentMatchStaysWhereItWasWhenTheMatchesChange() {
        val a = SearchMatch(1, 0, 1, 3)
        val b = SearchMatch(5, 2, 5, 5)
        val c = SearchMatch(9, 0, 9, 3)
        assertEquals(-1, nextCurrent(emptyList(), a))
        assertEquals("the newest, at the bottom, to start", 2, nextCurrent(listOf(a, b, c), null))
        assertEquals(1, nextCurrent(listOf(a, b, c), b))
        assertEquals("gone: the next one after it", 1, nextCurrent(listOf(a, b, c), SearchMatch(3, 0, 3, 3)))
        assertEquals("nothing after: the last", 1, nextCurrent(listOf(a, b), SearchMatch(20, 0, 20, 3)))
    }
}
