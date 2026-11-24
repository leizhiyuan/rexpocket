package me.leaver.rexpocket.ui

import kotlin.math.floor
import me.leaver.rexpocket.core.BarKey

// How the key bar's width is shared between its two pills. Pure, in dp, so it is tested on the JVM.

/** The two pills' widths, in dp, and whether the small pill shows all its keys whole. */
internal data class KeyBarWidths(val keys: Float, val rest: Float, val restWhole: Boolean)

/**
 * Shares [width] dp (the bar's, inside its margins) between the wide key pill of [keys] keys and
 * the small pill of [restKeys]: by default the arrow stick, the keyboard key and the full-screen
 * key, in the person's order, some perhaps hidden. Each key is [key] dp, each pill has [pad] dp
 * inside both ends, and [gap] dp lies between them.
 *
 * 1. Everything fits: the small pill shows all its keys whole, and the key pill takes the rest.
 *    Nothing scrolls. (Landscape, a tablet.)
 * 2. Otherwise the key pill scrolls, so the last key it shows must show only in part, the hint that
 *    more follow: [PEEK_MIN] to [PEEK_MAX] of it, under the pill's fade. The small pill stays whole
 *    beside it, and if the hint falls in that band its last key (the full-screen key, by default)
 *    shows whole.
 * 3. When it does not, a small pill of three keys gives up just enough of its last key for the key
 *    pill's hint to be [PEEK_SET] - a sliver more of the same key, or a sliver of the next. That key
 *    then shows in part, its own hint, and the bands are chosen so that is never under [PEEK_MIN] of
 *    it. A small pill of one or two keys stays whole, keeping its keys in reach without a swipe; the
 *    key pill shrinks to that hint instead, and the gap takes what it gave up.
 *
 * Only the width and the set of keys decide it, never what is typed or whether the keyboard is up,
 * so the bar does not move while the person types; it changes on a rotation or a settings change.
 */
internal fun keyBarWidths(
    width: Float,
    keys: Int,
    restKeys: Int = 3,
    key: Float = 48f,
    pad: Float = 4f,
    gap: Float = 8f,
): KeyBarWidths {
    val room = (width - gap).coerceAtLeast(0f)
    val restFull = restKeys * key + 2 * pad
    // 1. All of it fits.
    if (keys * key + 2 * pad + restFull <= room) return KeyBarWidths(room - restFull, restFull, true)

    // 2. The small pill whole: what the key pill shows past its last whole key. A pill's keys scroll
    // with its padding, so at rest only the leading pad is in the way; the keys run on to the edge.
    val beside = (room - restFull - pad).coerceAtLeast(0f)
    val peek = beside - floor(beside / key) * key
    // 3. What the key pill needs to make its hint PEEK_SET: more of the same key, or of the next.
    val give = when {
        peek < PEEK_MIN -> PEEK_SET - peek
        peek > PEEK_MAX -> key + PEEK_SET - peek
        else -> 0f
    }
    if (restKeys < 3) {
        val back = when {
            peek < PEEK_MIN -> peek + key - PEEK_SET
            peek > PEEK_MAX -> peek - PEEK_SET
            else -> 0f
        }
        return KeyBarWidths((room - restFull - back).coerceAtLeast(0f), restFull, true)
    }
    val rest = restFull - give
    return KeyBarWidths((room - rest).coerceAtLeast(0f), rest, give == 0f)
}

/** The least of a key shown as a hint, either pill's: less reads as a gap, not as a key. */
internal const val PEEK_MIN = 18f

/** The most of the key pill's last key shown as a hint: more and it reads as a whole key. */
internal const val PEEK_MAX = 38f

/**
 * The hint the key pill is given when one has to be made. With [PEEK_MIN] and [PEEK_MAX] it leaves
 * the small pill's last key more than 18 dp of its 48 (plus the pill's 4 dp pad, which scrolls with
 * it): 52 - (20 - peek) below the band, 52 - (68 - peek) above it, so 22 to 50 dp.
 */
internal const val PEEK_SET = 20f

/** A row of a group's panel: its keys, and how many slots it is cut into, so keys keep one width. */
internal data class PanelRow(val keys: List<BarKey>, val slots: Int)

/**
 * A group's keys in rows, in their order: six to a row, or seven while every key of the row is one
 * narrow character. Fn's keys by default come out as the panel always was: Home to Del, F1 to F6,
 * F7 to F12, and its seven symbols.
 */
internal fun panelRows(keys: List<BarKey>): List<PanelRow> {
    fun slots(row: List<BarKey>) = if (row.all { it.narrow }) PANEL_NARROW else PANEL_WIDE
    val rows = mutableListOf<List<BarKey>>()
    var row = emptyList<BarKey>()
    for (key in keys) {
        val next = row + key
        if (next.size <= slots(next)) {
            row = next
        } else {
            rows += row
            row = listOf(key)
        }
    }
    if (row.isNotEmpty()) rows += row
    return rows.map { PanelRow(it, slots(it)) }
}

private const val PANEL_WIDE = 6
private const val PANEL_NARROW = 7
