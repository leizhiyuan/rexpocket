package me.leaver.rexpocket.core

import org.json.JSONArray

// The person's quick replies: short answers and prompts an agent is often given, sent in one tap
// from a notification's Reply, the terminal's key bar and the command palette, and put into the
// prompt composer. Edited in Settings › Quick replies. See docs/design/notifications.md.

/**
 * The quick replies in the person's order. Each is kept as typed, trimmed of the blank lines and
 * spaces around it; an empty one is never kept, nor the same text twice.
 */
data class QuickReplies(val items: List<String> = DEFAULTS) {
    val canAdd: Boolean get() = items.size < MAX_ITEMS

    /** [text] added at the end; unchanged when it is empty, already there, or the list is full. */
    fun add(text: String): QuickReplies {
        val clean = clean(text) ?: return this
        if (!canAdd || clean in items) return this
        return copy(items = items + clean)
    }

    /** The reply at [index] made [text]; an empty [text] deletes it, a duplicate leaves it as it was. */
    fun edit(index: Int, text: String): QuickReplies {
        if (index !in items.indices) return this
        val clean = clean(text) ?: return delete(index)
        if (items.withIndex().any { (i, it) -> i != index && it == clean }) return this
        return copy(items = items.toMutableList().apply { set(index, clean) })
    }

    fun delete(index: Int): QuickReplies =
        if (index !in items.indices) this else copy(items = items.toMutableList().apply { removeAt(index) })

    /** The reply at [index] moved [by] places (negative is up), kept in bounds. */
    fun move(index: Int, by: Int): QuickReplies {
        if (index !in items.indices) return this
        val list = items.toMutableList()
        val item = list.removeAt(index)
        list.add((index + by).coerceIn(0, list.size), item)
        return copy(items = list)
    }

    /**
     * The replies a notification's Reply offers as choices: Android sends a choice's text as it is
     * shown, so only the short one-line ones, the first [CHOICES] of them.
     */
    fun choices(): List<String> = items.filter { '\n' !in it && it.length <= CHOICE_LENGTH }.take(CHOICES)

    /** As stored: a JSON array of the replies, in order. */
    fun stored(): String = JSONArray(items).toString()

    companion object {
        /** What an agent is told most: agree, go on, and Claude Code's and Codex's `/compact`. */
        val DEFAULTS = listOf("yes", "continue", "/compact")

        /** A notification offers a few at most, and the key bar's panel stays short. */
        const val MAX_ITEMS = 20

        /** A quick reply may be a whole prompt; past this it belongs in the composer. */
        const val MAX_LENGTH = 2_000

        /** How many choices a notification's Reply shows, and how long each may be. */
        const val CHOICES = 5
        const val CHOICE_LENGTH = 40

        fun clean(text: String): String? = text.trim().take(MAX_LENGTH).trim().ifEmpty { null }

        /** The list as stored; the defaults when nothing was stored, or what was cannot be read. */
        fun fromStored(raw: String?): QuickReplies {
            if (raw == null) return QuickReplies()
            val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return QuickReplies()
            val items = (0 until arr.length()).mapNotNull { arr.optString(it, null)?.let(::clean) }.distinct().take(MAX_ITEMS)
            return QuickReplies(items)
        }

        /**
         * A quick reply's text on one short line, for a chip, a notification's choice and a list row:
         * the first line, cut to [max] characters with an ellipsis when there is more.
         */
        fun short(text: String, max: Int = 40): String {
            val line = text.lineSequence().first().trim()
            val more = line.length > max || text.trim().contains('\n')
            val cut = if (line.length > max) line.take(max - 1).trimEnd() else line
            return if (more) "$cut…" else cut
        }
    }
}
