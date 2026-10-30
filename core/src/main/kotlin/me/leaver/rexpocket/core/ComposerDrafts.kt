package me.leaver.rexpocket.core

/**
 * What the composer holds: a draft per pane, so switching panes never carries half a command
 * across, and one history of sent lines shared by all panes, walked with up and down like a shell.
 *
 * Not thread-safe: the UI owns it.
 */
class ComposerDrafts(private val historySize: Int = 100) {
    private val drafts = HashMap<String, String>()
    private val history = ArrayDeque<String>()

    /** How far back the pane is in [history], and what it was typing before it started walking. */
    private class Walk(val index: Int, val typed: String)
    private val walks = HashMap<String, Walk>()

    fun draft(pane: String): String = drafts[pane].orEmpty()

    /** The user typed: this is the draft now, and any walk through history ends here. */
    fun setDraft(pane: String, text: String) {
        if (drafts[pane] == text) return
        drafts[pane] = text
        walks.remove(pane)
    }

    /** Takes the draft to send it, and remembers it unless it is empty or the last line again. */
    fun send(pane: String): String {
        val text = draft(pane)
        drafts.remove(pane)
        walks.remove(pane)
        if (text.isNotBlank() && history.lastOrNull() != text) {
            history.addLast(text)
            while (history.size > historySize) history.removeFirst()
        }
        return text
    }

    /** The line sent before the one shown, now the draft; null when there is none. */
    fun older(pane: String): String? {
        val walk = walks[pane]
        val index = (walk?.index ?: history.size) - 1
        if (index < 0) return null
        return step(pane, index, walk?.typed ?: draft(pane))
    }

    /** The line sent after the one shown, or back to what was being typed; null when not walking. */
    fun newer(pane: String): String? {
        val walk = walks[pane] ?: return null
        val index = walk.index + 1
        if (index < history.size) return step(pane, index, walk.typed)
        walks.remove(pane)
        drafts[pane] = walk.typed
        return walk.typed
    }

    private fun step(pane: String, index: Int, typed: String): String {
        val line = history[index]
        walks[pane] = Walk(index, typed)
        drafts[pane] = line
        return line
    }
}
