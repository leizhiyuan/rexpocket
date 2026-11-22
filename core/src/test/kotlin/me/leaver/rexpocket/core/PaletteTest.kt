package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** The command palette's ranking, as docs/design/command-palette.md states it. */
class PaletteTest {
    private fun e(label: String, group: String = "Pane", detail: String? = null) = PaletteEntry(label, group, detail, label)

    private val rows = listOf(
        e("Split right"),
        e("Split below"),
        e("Rename pane"),
        e("New tab", "Tab"),
        e("Close tab", "Tab"),
        e("logs", "Tab", "work · running"),
        e("pi > GRAPH: Organ", "Tab", "tau · failed"),
        e("work", "Session"),
        e("Paste", "Phone"),
    )

    private fun ranked(query: String) = rankPalette(rows, query).map { it.value }

    @Test
    fun nothingTypedKeepsEveryRowInItsOrder() {
        assertEquals(rows.map { it.value }, ranked(""))
        assertEquals(rows.map { it.value }, ranked("   "))
    }

    @Test
    fun aLabelThatStartsWithTheWordComesFirst() {
        // Paste starts with "pa", a word of Rename pane does, pi > GRAPH has p and a in order, and
        // the splits are in the Pane group.
        assertEquals(listOf("Paste", "Rename pane", "pi > GRAPH: Organ", "Split right", "Split below"), ranked("pa"))
    }

    @Test
    fun lettersInOrderMatchLast() {
        assertEquals(listOf("Split right"), ranked("sprt"))
    }

    @Test
    fun everyWordHasToMatchAndTheGroupOrDetailCounts() {
        assertEquals(listOf("New tab", "Close tab", "logs", "pi > GRAPH: Organ"), ranked("tab"))
        assertEquals(listOf("pi > GRAPH: Organ"), ranked("failed"))
        assertEquals(listOf("Close tab"), ranked("close tab"))
        assertEquals(emptyList<String>(), ranked("close zebra"))
    }

    @Test
    fun scoresAreThoseOfTheDesign() {
        assertEquals(100, paletteScore(e("Split right"), "split"))
        assertEquals(80, paletteScore(e("Split right"), "rig"))
        assertEquals(50, paletteScore(e("Split right"), "plit"))
        assertEquals(20, paletteScore(e("Split right"), "sr"))
        assertEquals(10, paletteScore(e("logs", "Tab", "work"), "wor"))
        assertEquals(null, paletteScore(e("logs", "Tab", "work"), "zz"))
        assertEquals(10, paletteScore(PaletteEntry("New pane on the right", "Pane", null, 0, "Split Pane Right pane.split.right"), "split"))
    }
}
