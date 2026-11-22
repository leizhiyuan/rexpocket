package me.leaver.rexpocket.core

// The key bar's keys, where each sits and in what order, as the person arranged them in Settings ›
// Terminal controls › Arrange keys, and the key groups - Fn and the panels like it. See
// docs/design/terminal-controls.md, 4.1.

/** The bar's two groups: the wide pill that scrolls, and the small one at the right. */
enum class Pill { Keys, Rest }

/** The headings the catalogue of keys is offered under, in the order they are shown. */
enum class KeyKind { Bar, Group, Rest, Desk, Arrow, Control, Symbol, LetterOrDigit }

/**
 * A key the bar can draw, on either pill or in a group's panel. Each has a stored name ([pref]) and
 * lives in one [pill]: the small pill holds only its own three keys, the thumb's fixed place, and
 * every other key may sit in the key pill. Every key but the group keys and the small pill's may
 * also sit in a group's panel ([inGroups]). The keyboard key cannot be removed: it is the one way to
 * bring the phone keyboard back once it is down.
 */
sealed class BarKey(val pref: String, val kind: KeyKind) {
    val pill: Pill get() = if (kind == KeyKind.Rest) Pill.Rest else Pill.Keys
    val removable: Boolean get() = this != Keyboard
    val inGroups: Boolean get() = kind != KeyKind.Group && kind != KeyKind.Rest

    /** True when the key's face is one narrow character, so a panel row takes seven of them. */
    val narrow: Boolean get() = this is Typed || this is Arrow || this == Slash

    data object Escape : BarKey("escape", KeyKind.Bar)
    data object CtrlC : BarKey("ctrl_c", KeyKind.Bar)
    data object Tab : BarKey("tab", KeyKind.Bar)
    data object Enter : BarKey("enter", KeyKind.Bar)
    data object Ctrl : BarKey("ctrl", KeyKind.Bar)
    data object Shift : BarKey("shift", KeyKind.Bar)
    data object Alt : BarKey("alt", KeyKind.Bar)
    data object Paste : BarKey("paste", KeyKind.Bar)
    data object Attach : BarKey("attach", KeyKind.Bar)
    data object Slash : BarKey("slash", KeyKind.Bar)
    data object Arrows : BarKey("arrows", KeyKind.Rest)
    data object Keyboard : BarKey("keyboard", KeyKind.Rest)
    data object FullScreen : BarKey("full_screen", KeyKind.Rest)

    /** The key that pins group [id]'s panel. Fn's keeps the name it had before groups. */
    data class Group(val id: String) : BarKey(if (id == FN_GROUP) "fn" else "group_$id", KeyKind.Group)

    /** Home, End, the page keys, Ins, Del, F1 to F12: sent as a desk keyboard sends them. */
    data class Desk(val key: DeskKey) : BarKey(deskPref(key), KeyKind.Desk)

    /** One arrow, [code] as Rex names it (`arrow_up`); held, it repeats as the arrow pad's do. */
    data class Arrow(val code: String) : BarKey(code, KeyKind.Arrow)

    /** C-[letter] in one tap: the chords behind a held `ctrl`. C-c is [CtrlC], with its own setting. */
    data class Chord(val letter: Char) : BarKey("ctrl_$letter", KeyKind.Control)

    /** A key that types [char]: space, a letter, a digit or a symbol. */
    data class Typed(val char: Char) : BarKey(
        "char_" + char.code.toString(16),
        if (char.isLetterOrDigit()) KeyKind.LetterOrDigit else KeyKind.Symbol,
    )

    override fun toString(): String = pref

    companion object {
        /** Fn's group id: the panel the bar has always had. */
        const val FN_GROUP = "fn"

        // Lazy, as a sealed class's companion is set up while its first object is: a list made
        // eagerly here could hold an object not yet made.
        val Fn: Group by lazy { Group(FN_GROUP) }

        val ARROW_CODES = listOf("arrow_up", "arrow_down", "arrow_left", "arrow_right")

        /** The keys a [Typed] may type: space, then the printable ASCII but capitals (Shift makes those) and `/`, the bar's own. */
        val TYPED_CHARS: List<Char> = listOf(' ') + ('!'..'~').filter { it != '/' && it !in 'A'..'Z' }

        /**
         * Every key there is but the group keys, under its heading: what the Add keys page offers.
         * Group keys are the arrangement's own (see [KeyArrangement.catalogue]).
         */
        val catalogue: List<BarKey> by lazy {
            listOf(Escape, CtrlC, Tab, Enter, Ctrl, Shift, Alt, Paste, Attach, Slash, Arrows, Keyboard, FullScreen) +
                DeskKey.entries.map { Desk(it) } +
                ARROW_CODES.map { Arrow(it) } +
                ('a'..'z').filter { it != 'c' }.map { Chord(it) } +
                TYPED_CHARS.filter { !it.isLetterOrDigit() }.map { Typed(it) } +
                TYPED_CHARS.filter { it.isLetterOrDigit() }.sortedBy { if (it.isDigit()) 1 else 0 }.map { Typed(it) }
        }

        private val byPref: Map<String, BarKey> by lazy { catalogue.associateBy { it.pref } }

        /** The key a stored name means, or null for one this build does not know. */
        fun fromPref(value: String): BarKey? {
            val v = value.trim()
            byPref[v]?.let { return it }
            if (v == "fn") return Fn
            if (v.startsWith("group_")) return v.removePrefix("group_").takeIf(::validGroupId)?.let(::Group)
            if (v.startsWith("char_")) {
                val c = v.removePrefix("char_").toIntOrNull(16)?.toChar() ?: return null
                return if (c == '/') Slash else byPref["char_" + c.code.toString(16)]
            }
            return null
        }

        /** A group id is short and plain, so it is safe in a stored list and a preference's name. */
        fun validGroupId(id: String): Boolean = id.isNotEmpty() && id.length <= 16 && id.all { it in 'a'..'z' || it in '0'..'9' }

        private fun deskPref(key: DeskKey): String = when (key) {
            DeskKey.PageUp -> "page_up"
            DeskKey.PageDown -> "page_down"
            else -> key.name.lowercase()
        }
    }
}

/** A named panel of keys, opened by its key ([BarKey.Group]) on the bar. */
data class KeyGroup(val id: String, val name: String, val keys: List<BarKey>) {
    val key: BarKey.Group get() = BarKey.Group(id)
}

/**
 * The groups that come ready to add, off by default but Fn. A preset is a template: the group made
 * from it is the person's own, and a later change here never reaches it.
 */
enum class KeyPreset(val id: String, val title: String, val keys: () -> List<BarKey>) {
    Fn(BarKey.FN_GROUP, "Fn", {
        DeskKey.entries.map { BarKey.Desk(it) } + "|~`\\/-_".map { if (it == '/') BarKey.Slash else BarKey.Typed(it) }
    }),
    Htop("htop", "htop", {
        deskKeys(DeskKey.F1, DeskKey.F10).map { BarKey.Desk(it) } + arrows() +
            listOf(BarKey.Desk(DeskKey.PageUp), BarKey.Desk(DeskKey.PageDown), BarKey.Typed(' '), BarKey.Slash, BarKey.Typed('q'))
    }),
    Vim("vim", "vim", {
        listOf(BarKey.Escape) + ":/?*~".map { if (it == '/') BarKey.Slash else BarKey.Typed(it) } +
            "durowv".map { BarKey.Chord(it) } + arrows()
    }),
    Tmux("tmux", "tmux", {
        listOf<BarKey>(BarKey.Chord('b')) + "cnpd%\"[z".map { BarKey.Typed(it) } + arrows()
    });

    fun group(): KeyGroup = KeyGroup(id, title, keys())

    companion object {
        fun byId(id: String): KeyPreset? = entries.firstOrNull { it.id == id }
    }
}

private fun deskKeys(from: DeskKey, to: DeskKey): List<DeskKey> = DeskKey.entries.subList(from.ordinal, to.ordinal + 1)

private fun arrows(): List<BarKey> = listOf("arrow_left", "arrow_down", "arrow_up", "arrow_right").map { BarKey.Arrow(it) }

/**
 * The keys of each pill in the person's order, and the key groups. Each pill holds a key at most
 * once and only keys that belong in it; the right pill always holds the keyboard key. A group key
 * on the pill always has its group. A key may be on the pill and in groups at once.
 */
data class KeyArrangement(
    val keys: List<BarKey> = DEFAULT_KEYS,
    val rest: List<BarKey> = DEFAULT_REST,
    val groups: List<KeyGroup> = listOf(KeyPreset.Fn.group()),
) {
    /** [pill]'s keys in order: what the settings list shows. */
    fun order(pill: Pill): List<BarKey> = if (pill == Pill.Keys) keys else rest

    /** [pill]'s keys the bar draws, in order: a group with no keys has no key, as it opens nothing. */
    fun shown(pill: Pill): List<BarKey> =
        if (pill == Pill.Rest) rest else keys.filter { it !is BarKey.Group || group(it.id)?.keys?.isNotEmpty() == true }

    fun group(id: String): KeyGroup? = groups.firstOrNull { it.id == id }

    /** Whether [key] is on its pill (drawn or not). */
    fun has(key: BarKey): Boolean = key in order(key.pill)

    /**
     * Whether a person can reach [key] on the bar: drawn on a pill, or in a group whose key is.
     * A modifier that cannot be reached is let go, so it never stays held with no key to free it.
     */
    fun isShown(key: BarKey): Boolean {
        val drawn = shown(Pill.Keys) + rest
        return key in drawn || groups.any { it.key in drawn && key in it.keys }
    }

    /** [key] put at the end of its pill, if it may go there and is not there yet. */
    fun add(key: BarKey): KeyArrangement {
        if (has(key) || (key is BarKey.Group && group(key.id) == null)) return this
        return withPill(key.pill, order(key.pill) + key)
    }

    /** [key] taken off its pill; the keyboard key always stays. */
    fun remove(key: BarKey): KeyArrangement =
        if (!key.removable || !has(key)) this else withPill(key.pill, order(key.pill) - key)

    /** [key] moved [by] places within its pill (negative is towards the start), kept in bounds. */
    fun move(key: BarKey, by: Int): KeyArrangement = moveTo(key, order(key.pill).indexOf(key) + by)

    /** [key] put at [index] of its pill's order, kept in bounds: where a drag lets it go. */
    fun moveTo(key: BarKey, index: Int): KeyArrangement {
        val list = order(key.pill).toMutableList()
        if (!list.remove(key)) return this
        list.add(index.coerceIn(0, list.size), key)
        return withPill(key.pill, list)
    }

    /** The groups as settings lists them: in their keys' order on the bar, then those off it. */
    fun groupsInOrder(): List<KeyGroup> {
        val at = keys.filterIsInstance<BarKey.Group>().map { it.id }
        return groups.sortedBy { at.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    val canAddGroup: Boolean get() = groups.size < MAX_GROUPS

    /** Group [preset] made, its key on the pill after the last group key; an existing one stays as it is. */
    fun addPreset(preset: KeyPreset): KeyArrangement =
        if (group(preset.id) != null || !canAddGroup) this else withGroup(preset.group())

    /** An empty group called [name] made, its key placed as a preset's; [newGroupId] is its id. */
    fun addGroup(name: String): KeyArrangement {
        val clean = cleanName(name) ?: return this
        if (!canAddGroup) return this
        return withGroup(KeyGroup(newGroupId(), clean, emptyList()))
    }

    /** The id the next [addGroup] gives: `c1`, `c2`, ..., the first one free. */
    fun newGroupId(): String = generateSequence(1) { it + 1 }.map { "c$it" }.first { id -> group(id) == null }

    fun renameGroup(id: String, name: String): KeyArrangement {
        val clean = cleanName(name) ?: return this
        return mapGroup(id) { it.copy(name = clean) }
    }

    /** Group [id] gone, and its key off the pill. */
    fun deleteGroup(id: String): KeyArrangement =
        copy(groups = groups.filter { it.id != id }, keys = keys - BarKey.Group(id))

    fun addToGroup(id: String, key: BarKey): KeyArrangement =
        if (!key.inGroups) this else mapGroup(id) { if (key in it.keys) it else it.copy(keys = it.keys + key) }

    fun removeFromGroup(id: String, key: BarKey): KeyArrangement = mapGroup(id) { it.copy(keys = it.keys - key) }

    fun moveInGroup(id: String, key: BarKey, by: Int): KeyArrangement {
        val at = group(id)?.keys?.indexOf(key) ?: return this
        return moveInGroupTo(id, key, at + by)
    }

    fun moveInGroupTo(id: String, key: BarKey, index: Int): KeyArrangement = mapGroup(id) { g ->
        val list = g.keys.toMutableList()
        if (!list.remove(key)) return@mapGroup g
        list.add(index.coerceIn(0, list.size), key)
        g.copy(keys = list)
    }

    /** Whether [key] may be added to the key pill ([group] null) or to [group]. */
    fun fits(key: BarKey, group: String?): Boolean =
        if (group == null) key.pill == Pill.Keys && (key !is BarKey.Group || group(key.id) != null) else key.inGroups

    /** The keys the Add keys page of the key pill ([group] null), the right pill or [group] offers. */
    fun catalogue(pill: Pill, group: String? = null): List<BarKey> = when {
        group != null -> BarKey.catalogue.filter { it.inGroups }
        pill == Pill.Rest -> BarKey.catalogue.filter { it.pill == Pill.Rest && it.removable }
        else -> BarKey.catalogue.filter { it.pill == Pill.Keys }.let { all ->
            // Groups' keys after the bar's own, as the headings come.
            val own = all.filter { it.kind == KeyKind.Bar }
            own + groupsInOrder().map { it.key } + (all - own.toSet())
        }
    }

    /** The order of both pills, as stored: the keys' names, the wide pill's first. */
    fun storedOrder(): String = (keys + rest).joinToString(",") { it.pref }

    /** The keys a bar had by default up to now that are not on their pill (what an older build calls hidden). */
    fun storedHidden(): String = BAR_KEYS_SO_FAR.filter { !has(it) || (it is BarKey.Group && group(it.id) == null) }
        .joinToString(",") { it.pref }

    fun storedGroups(): String = groups.joinToString(",") { it.id }

    fun storedGroupKeys(id: String): String = group(id)?.keys.orEmpty().joinToString(",") { it.pref }

    private fun withPill(pill: Pill, list: List<BarKey>) = if (pill == Pill.Keys) copy(keys = list) else copy(rest = list)

    private fun mapGroup(id: String, change: (KeyGroup) -> KeyGroup) =
        copy(groups = groups.map { if (it.id == id) change(it) else it })

    private fun withGroup(group: KeyGroup): KeyArrangement {
        val last = keys.indexOfLast { it is BarKey.Group }
        val list = keys.toMutableList().apply { add(if (last < 0) size else last + 1, group.key) }
        return copy(groups = groups + group, keys = list)
    }

    companion object {
        /** The most groups: each is a key on the bar and a row in settings. */
        const val MAX_GROUPS = 8

        /** The longest name a group takes: its key on the bar shows all of it. */
        const val MAX_NAME = 8

        val DEFAULT_KEYS: List<BarKey>
            get() = listOf(BarKey.Escape, BarKey.CtrlC, BarKey.Tab, BarKey.Enter, BarKey.Ctrl, BarKey.Fn, BarKey.Paste, BarKey.Attach, BarKey.Slash)
        val DEFAULT_REST: List<BarKey> get() = listOf(BarKey.Arrows, BarKey.Keyboard, BarKey.FullScreen)

        /**
         * Every key the bar has had by default, in its default place - Shift and Alt among them, as
         * they were hidden rather than absent. A key here that a stored order lacks and the stored
         * hidden list does not name came after that order was stored, and goes back in its place.
         */
        val BAR_KEYS_SO_FAR: List<BarKey>
            get() = DEFAULT_KEYS.take(5) + listOf(BarKey.Shift, BarKey.Alt) + DEFAULT_KEYS.drop(5) + DEFAULT_REST

        fun cleanName(name: String): String? = name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).trim().ifEmpty { null }

        private fun parse(list: String?): List<BarKey>? = list?.split(',')?.mapNotNull { BarKey.fromPref(it) }?.distinct()

        /**
         * The arrangement as stored (see the table in the design doc). With nothing stored yet
         * ([order] and [hidden] null), it is the bar the person had: Shift and Alt on it if
         * [legacyShift] and [legacyAlt] said so, and C-c off it if [legacyCtrlCHidden]. A stored
         * order keeps its known keys in their pill and drops unknown ones; a default key it lacks
         * that [hidden] does not name - one added since - goes back after the key before it by
         * default; a key [hidden] names is off its pill, as the last version's hidden keys and this
         * one's removed ones both are. With no [groups] stored there is Fn, as it always was.
         */
        fun fromStored(
            order: String?,
            hidden: String?,
            groups: String? = null,
            groupKeys: (String) -> String? = { null },
            groupName: (String) -> String? = { null },
            legacyShift: Boolean = false,
            legacyAlt: Boolean = false,
            legacyCtrlCHidden: Boolean = false,
        ): KeyArrangement {
            val groupList = if (groups == null) {
                listOf(KeyPreset.Fn.group())
            } else {
                groups.split(',').map { it.trim() }.filter(BarKey::validGroupId).distinct().take(MAX_GROUPS).map { id ->
                    val preset = KeyPreset.byId(id)
                    val keys = parse(groupKeys(id))?.filter { it.inGroups } ?: preset?.keys?.invoke().orEmpty()
                    KeyGroup(id, groupName(id)?.let(::cleanName) ?: preset?.title ?: id, keys)
                }
            }
            val ids = groupList.map { it.id }.toSet()
            val off: Set<BarKey> = parse(hidden)?.toSet() ?: buildSet {
                if (!legacyShift) add(BarKey.Shift)
                if (!legacyAlt) add(BarKey.Alt)
                if (legacyCtrlCHidden) add(BarKey.CtrlC)
            }
            val stored = parse(order)
            fun pill(pill: Pill): List<BarKey> {
                val defaults = BAR_KEYS_SO_FAR.filter { it.pill == pill }
                val list = (stored ?: defaults).filter { it.pill == pill }.toMutableList()
                for ((i, key) in defaults.withIndex()) {
                    if (key in list || (key in off && key.removable)) continue
                    val before = defaults.subList(0, i).lastOrNull { it in list }
                    list.add(if (before == null) 0 else list.indexOf(before) + 1, key)
                }
                list.removeAll { (it in off && it.removable) || (it is BarKey.Group && it.id !in ids) }
                return list
            }
            return KeyArrangement(pill(Pill.Keys), pill(Pill.Rest), groupList)
        }
    }
}
