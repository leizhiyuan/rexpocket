package me.leaver.rexpocket.core

// The order of the key bar's keys and which of them show, as the person arranged them in
// Settings › Terminal controls › Arrange keys. See docs/design/terminal-controls.md.

/** The bar's two groups: the wide pill that scrolls, and the small one at the right. */
enum class Pill { Keys, Rest }

/**
 * A key of the bar, in its default order. Each lives in one [pill]: the small pill is the thumb's
 * fixed place, and its width rule assumes its own few keys (see keyBarWidths in the app), so keys
 * are arranged within their pill, not moved between them. The keyboard key cannot be hidden: it is
 * the one way to bring the phone keyboard back once it is down.
 */
enum class BarKey(val pref: String, val pill: Pill = Pill.Keys, val hideable: Boolean = true) {
    Escape("escape"),
    CtrlC("ctrl_c"),
    Tab("tab"),
    Enter("enter"),
    Ctrl("ctrl"),
    Shift("shift"),
    Alt("alt"),
    More("fn"),
    Paste("paste"),
    Attach("attach"),
    Slash("slash"),
    Arrows("arrows", Pill.Rest),
    Keyboard("keyboard", Pill.Rest, hideable = false),
    FullScreen("full_screen", Pill.Rest);

    companion object {
        fun fromPref(value: String): BarKey? = entries.firstOrNull { it.pref == value }

        /** Every key of [pill], in its default order. */
        fun of(pill: Pill): List<BarKey> = entries.filter { it.pill == pill }
    }
}

/**
 * The keys of each pill in the person's order, and those they hid. Every key is in its pill's list
 * exactly once; [hidden] never holds the keyboard key.
 */
data class KeyArrangement(
    val keys: List<BarKey> = BarKey.of(Pill.Keys),
    val rest: List<BarKey> = BarKey.of(Pill.Rest),
    val hidden: Set<BarKey> = DEFAULT_HIDDEN,
) {
    /** [pill]'s keys in order, hidden ones included: what the settings list shows. */
    fun order(pill: Pill): List<BarKey> = if (pill == Pill.Keys) keys else rest

    /** [pill]'s keys that show on the bar, in order. */
    fun shown(pill: Pill): List<BarKey> = order(pill).filter { it !in hidden }

    fun isShown(key: BarKey): Boolean = key !in hidden

    /** [key] moved [by] places within its pill (negative is towards the start), kept in bounds. */
    fun move(key: BarKey, by: Int): KeyArrangement {
        val list = order(key.pill)
        return moveTo(key, list.indexOf(key) + by)
    }

    /** [key] put at [index] of its pill's order, kept in bounds: where a drag lets it go. */
    fun moveTo(key: BarKey, index: Int): KeyArrangement {
        val list = order(key.pill).toMutableList()
        if (!list.remove(key)) return this
        list.add(index.coerceIn(0, list.size), key)
        return if (key.pill == Pill.Keys) copy(keys = list) else copy(rest = list)
    }

    /** [key] shown or hidden; the keyboard key always stays. */
    fun show(key: BarKey, shown: Boolean): KeyArrangement = when {
        !key.hideable -> this
        shown -> copy(hidden = hidden - key)
        else -> copy(hidden = hidden + key)
    }

    /** The order of both pills, as stored: the keys' names, the wide pill's first. */
    fun storedOrder(): String = (keys + rest).joinToString(",") { it.pref }

    fun storedHidden(): String = BarKey.entries.filter { it in hidden }.joinToString(",") { it.pref }

    companion object {
        /** Shift and Alt were off the bar until asked for, as they still are by default. */
        val DEFAULT_HIDDEN = setOf(BarKey.Shift, BarKey.Alt)

        /**
         * The arrangement as stored. With nothing stored yet ([order] and [hidden] null), it is
         * the bar the person had: Shift and Alt shown if [legacyShift] and [legacyAlt] said so, and
         * C-c gone if [legacyCtrlCHidden]. A stored order keeps its known keys in their pill and
         * drops unknown ones; a key it lacks - one added since - goes back in its default place,
         * after the key that comes before it by default. A key new since [hidden] was stored shows.
         */
        fun fromStored(
            order: String?,
            hidden: String?,
            legacyShift: Boolean = false,
            legacyAlt: Boolean = false,
            legacyCtrlCHidden: Boolean = false,
        ): KeyArrangement {
            val stored = order?.split(',')?.mapNotNull { BarKey.fromPref(it.trim()) }?.distinct()
            fun pill(pill: Pill): List<BarKey> {
                val defaults = BarKey.of(pill)
                val list = stored?.filter { it.pill == pill }?.toMutableList() ?: return defaults
                for ((i, key) in defaults.withIndex()) {
                    if (key in list) continue
                    val before = defaults.subList(0, i).lastOrNull { it in list }
                    list.add(if (before == null) 0 else list.indexOf(before) + 1, key)
                }
                return list
            }
            val hiddenSet = if (hidden == null) {
                buildSet {
                    if (!legacyShift) add(BarKey.Shift)
                    if (!legacyAlt) add(BarKey.Alt)
                    if (legacyCtrlCHidden) add(BarKey.CtrlC)
                }
            } else {
                hidden.split(',').mapNotNull { BarKey.fromPref(it.trim()) }.filter { it.hideable }.toSet()
            }
            return KeyArrangement(pill(Pill.Keys), pill(Pill.Rest), hiddenSet)
        }
    }
}
