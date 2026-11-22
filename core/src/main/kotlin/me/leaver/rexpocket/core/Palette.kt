package me.leaver.rexpocket.core

// The command palette's search: which rows a query keeps, and in what order. The rules are in
// docs/design/command-palette.md, "Ranking".

/**
 * One row of the palette: what it says, what it is (its group), a detail shown after the group, and
 * what it does. [keywords] are matched but not shown: Rex's own name for the action, such as
 * `Split Pane Right pane.split.right`, so a person who knows Rex's palette finds the phone's row.
 */
data class PaletteEntry<T>(
    val label: String,
    val group: String,
    val detail: String? = null,
    val value: T,
    val keywords: String? = null,
)

/**
 * How well [word] (lower case) matches [entry], or null when it does not: the label starting with
 * it beats a word of the label starting with it, which beats the label containing it, which beats
 * its letters in order in the label; the group, detail or keywords containing it come last.
 */
fun paletteScore(entry: PaletteEntry<*>, word: String): Int? {
    val label = entry.label.lowercase()
    return when {
        label.startsWith(word) -> 100
        label.split(' ', '-', '_', '/', '.', ':').any { it.startsWith(word) } -> 80
        label.contains(word) -> 50
        inOrder(label, word) -> 20
        listOfNotNull(entry.group, entry.detail, entry.keywords).any { it.lowercase().contains(word) } -> 10
        else -> null
    }
}

private fun inOrder(text: String, letters: String): Boolean {
    var at = 0
    for (c in letters) {
        at = text.indexOf(c, at)
        if (at < 0) return false
        at++
    }
    return true
}

/**
 * The rows [query] keeps, best first. Every word of the query has to match; a row's score is the
 * sum of its words' scores, and rows that score the same keep the order they were given in. With
 * nothing typed every row is kept, in that order.
 */
fun <T> rankPalette(entries: List<PaletteEntry<T>>, query: String): List<PaletteEntry<T>> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return entries
    return entries.withIndex()
        .mapNotNull { (index, entry) ->
            var total = 0
            for (word in words) total += paletteScore(entry, word) ?: return@mapNotNull null
            Triple(entry, total, index)
        }
        .sortedWith(compareByDescending<Triple<PaletteEntry<T>, Int, Int>> { it.second }.thenBy { it.third })
        .map { it.first }
}
