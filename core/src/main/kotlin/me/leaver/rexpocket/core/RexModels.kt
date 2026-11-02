package me.leaver.rexpocket.core

import org.json.JSONArray
import org.json.JSONObject

data class Endpoint(
    val host: String,
    val port: Int = 7297,
    val plaintext: Boolean = true,
) {
    fun httpBase(): String {
        val scheme = if (plaintext) "http" else "https"
        return "$scheme://${normalizedHost()}:$port"
    }

    fun ws(path: String): String {
        val scheme = if (plaintext) "ws" else "wss"
        return "$scheme://${normalizedHost()}:$port$path"
    }

    private fun normalizedHost(): String {
        var h = host.trim()
        for (prefix in listOf("http://", "https://", "ws://", "wss://")) {
            if (h.startsWith(prefix)) h = h.removePrefix(prefix)
        }
        h = h.trimEnd('/')
        val slash = h.indexOf('/')
        if (slash >= 0) h = h.substring(0, slash)
        if (h.startsWith('[') && h.contains(']')) return h.substring(0, h.indexOf(']') + 1)
        val colon = h.lastIndexOf(':')
        if (colon > 0 && h.substring(colon + 1).all { it.isDigit() }) return h.substring(0, colon)
        return h
    }
}

data class Welcome(
    val clientId: String,
    val reconnectToken: String?,
    val resumed: Boolean,
    val apiRevision: Int,
    val maxMessageBytes: Int,
)

data class ListedSession(val id: String, val label: String)

data class BlockInfo(
    val id: String,
    val label: String,
    val creator: String?,
    val flavor: String?,
    val owner: String?,
)

data class WindowInfo(
    val id: String,
    val label: String,
    val focusedBlockId: String?,
    val zoomedBlockId: String?,
    val blocks: List<BlockInfo>,
) {
    /** The pane this tab shows on the phone: its focused block, or its first one. */
    fun focusedBlock(): BlockInfo? = blocks.firstOrNull { it.id == focusedBlockId } ?: blocks.firstOrNull()

    fun focusedIndex(): Int = focusedBlock()?.let { blocks.indexOf(it) } ?: -1
}

data class SessionView(
    val revision: Long,
    val sessionId: String,
    val label: String,
    val activeWindowId: String?,
    val windows: List<WindowInfo>,
) {
    fun activeWindow(): WindowInfo? =
        windows.firstOrNull { it.id == activeWindowId } ?: windows.firstOrNull()

    fun focusedBlock(): BlockInfo? = activeWindow()?.focusedBlock()

    /**
     * The pane to show when this session is opened: the one the phone showed last time, if it
     * still exists, otherwise the one Rex has focused, which is where the desktop is. Asked for a
     * tab, [windowId], it is a pane of that tab: the remembered one if it is there, else the tab's
     * own focus. Choosing the pane up front lets one focus call switch the tab and the pane.
     */
    fun paneToShow(remembered: String?, windowId: String? = null): BlockInfo? {
        val tab = windowId?.let { id -> windows.firstOrNull { it.id == id } }
        if (tab != null) return remembered?.let { id -> tab.blocks.firstOrNull { it.id == id } } ?: tab.focusedBlock()
        return remembered?.let { id -> allBlocks().firstOrNull { it.id == id } } ?: focusedBlock()
    }

    fun allBlocks(): List<BlockInfo> = windows.flatMap { it.blocks }

    /**
     * This view as the phone shows it, on its own [place] - the pane it chose - rather than on the
     * tab and pane Rex has active, which is the desktop's: Rex keeps one active tab per session for
     * every client. Kept while the pane exists; once it closes, Rex's choice is used again.
     */
    fun placedAt(place: String?): SessionView {
        if (place == null) return this
        val window = windows.firstOrNull { w -> w.blocks.any { it.id == place } } ?: return this
        return copy(
            activeWindowId = window.id,
            windows = windows.map { if (it.id == window.id) it.copy(focusedBlockId = place) else it },
        )
    }
}

data class ProgramRecord(
    val id: String,
    val state: String,
    val kind: String?,
    val app: String?,
    val title: String?,
    val msg: String?,
    val progress: Int?,
)

fun summarizeStatus(records: List<ProgramRecord>): ProgramRecord? {
    if (records.isEmpty()) return null
    val rank = mapOf("blocked" to 0, "error" to 1, "working" to 2, "done" to 3, "idle" to 4)
    return records.minBy { rank[it.state] ?: 9 }
}

fun parseView(obj: JSONObject): SessionView {
    val windowsJson = obj.optJSONArray("windows") ?: JSONArray()
    val windows = buildList {
        for (i in 0 until windowsJson.length()) {
            val w = windowsJson.optJSONObject(i) ?: continue
            val blocks = ArrayList<BlockInfo>()
            val layers = w.optJSONArray("layers") ?: JSONArray()
            for (li in 0 until layers.length()) {
                val layer = layers.optJSONObject(li) ?: continue
                val arr = layer.optJSONArray("blocks") ?: JSONArray()
                for (bi in 0 until arr.length()) {
                    val b = arr.optJSONObject(bi) ?: continue
                    val id = b.optNullableString("block_id") ?: continue
                    blocks.add(
                        BlockInfo(
                            id = id,
                            label = b.optNullableString("label") ?: shortId(id),
                            creator = b.optNullableString("creator_name"),
                            flavor = b.optNullableString("flavor"),
                            owner = b.optNullableString("owner"),
                        ),
                    )
                }
            }
            val id = w.optNullableString("window_id") ?: continue
            add(
                WindowInfo(
                    id = id,
                    label = w.optNullableString("label") ?: shortId(id),
                    focusedBlockId = w.optNullableString("focused_block_id"),
                    zoomedBlockId = w.optNullableString("zoomed_block_id"),
                    blocks = blocks,
                ),
            )
        }
    }
    val sessionId = obj.optNullableString("session_id") ?: ""
    return SessionView(
        revision = obj.optLong("revision"),
        sessionId = sessionId,
        label = obj.optNullableString("label") ?: shortId(sessionId),
        activeWindowId = obj.optNullableString("active_window_id"),
        windows = windows,
    )
}

fun parseSessions(array: JSONArray?): List<ListedSession> {
    if (array == null) return emptyList()
    val seen = LinkedHashMap<String, ListedSession>()
    for (i in 0 until array.length()) {
        val o = array.optJSONObject(i) ?: continue
        val id = o.optNullableString("session_id") ?: continue
        // Rex can list a session twice while one is being moved; the list is drawn with the id as
        // its key, so a repeat is a crash rather than a repeated row.
        seen[id] = ListedSession(id, o.optNullableString("label") ?: shortId(id))
    }
    return seen.values.toList()
}

fun parseProgramRecords(obj: JSONObject): List<ProgramRecord> {
    val arr = obj.optJSONArray("records") ?: return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(parseProgramRecord(o, o.optNullableString("id") ?: ""))
        }
    }
}

private fun parseProgramRecord(o: JSONObject, id: String) = ProgramRecord(
    id = id,
    state = o.optNullableString("state") ?: "idle",
    kind = o.optNullableString("kind"),
    app = o.optNullableString("effective_app") ?: o.optNullableString("app"),
    title = o.optNullableString("title"),
    msg = o.optNullableString("msg"),
    progress = if (o.has("progress") && !o.isNull("progress")) o.optInt("progress") else null,
)

/**
 * A pane's status records, keyed by record id, after Rex's `program_status_changed` [event]: each
 * report replaces its record, and an event without one clears it.
 */
fun applyStatusEvent(records: Map<String, ProgramRecord>, event: JSONObject): Map<String, ProgramRecord> {
    val id = event.optNullableString("id") ?: ""
    val record = event.optJSONObject("record") ?: return records - id
    return records + (id to parseProgramRecord(record, id))
}

/**
 * The program in a terminal's foreground, from Rex's `process` answer or `process_changed` event.
 * The name it was started by comes first: an agent written for node runs as `node` but is started
 * as `pi`, and that is the name a person knows it by. A login shell's leading dash is dropped.
 */
fun foregroundProgram(process: JSONObject): String? {
    val fg = process.optJSONObject("foreground") ?: return null
    val started = fg.optNullableString("argv0")?.removePrefix("-")?.substringAfterLast('/')
    return started?.takeIf { it.isNotBlank() } ?: fg.optNullableString("name")?.takeIf { it.isNotBlank() }
}

/**
 * The title a program set, from Rex's `title` answer or `title_changed` event, or null when it is
 * not one to show. A shell writes the command it is about to run, or `user@host:dir`, into the
 * title; that is noise, and Rex says who set it, so only a title from a program other than a shell
 * is kept - `π - rexpocket` from pi, not `( exec -a claude … )` from zsh.
 */
fun programTitle(answer: JSONObject): String? {
    val title = answer.optNullableString("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val owner = answer.optJSONObject("owner")?.optNullableString("name")
    return if (programMark(owner) == ProgramMark.Shell) null else title
}

/** What a pane runs: the app a program said it is, else the foreground process. */
fun paneProgram(status: ProgramRecord?, foreground: String?): String? =
    status?.app?.takeIf { it.isNotBlank() } ?: foreground

/**
 * The marks the app draws for a pane. A program the app knows gets its own; a shell, or nothing at
 * all, gets the terminal prompt; anything else gets its initial on the tile.
 */
enum class ProgramMark { Shell, Pi, Claude, Codex, Gemini, Cursor, OpenCode, Copilot, Vim, Emacs, Docker, Node, Python, Git, Ssh, Top, Other }

private val SHELLS = setOf("sh", "bash", "zsh", "fish", "nu", "nushell", "dash", "ksh", "tcsh", "csh", "login", "shell")

/** The mark for a program name, matched by its leading word so `Claude Code` and `claude` agree. */
fun programMark(program: String?): ProgramMark {
    val name = program?.trim()?.removePrefix("-")?.substringAfterLast('/')?.lowercase()
        ?.substringBefore(' ')?.takeIf { it.isNotEmpty() } ?: return ProgramMark.Shell
    return when (name) {
        in SHELLS -> ProgramMark.Shell
        "pi" -> ProgramMark.Pi
        "claude" -> ProgramMark.Claude
        "codex" -> ProgramMark.Codex
        "gemini" -> ProgramMark.Gemini
        "cursor", "cursor-agent" -> ProgramMark.Cursor
        "opencode" -> ProgramMark.OpenCode
        "copilot", "gh-copilot" -> ProgramMark.Copilot
        "vim", "nvim", "vi", "hx", "helix" -> ProgramMark.Vim
        "emacs" -> ProgramMark.Emacs
        "docker", "podman" -> ProgramMark.Docker
        "node", "bun", "deno", "npm", "pnpm", "yarn" -> ProgramMark.Node
        "python", "python3", "ipython", "uv" -> ProgramMark.Python
        "git", "lazygit", "tig", "jj" -> ProgramMark.Git
        "ssh", "mosh", "et" -> ProgramMark.Ssh
        "top", "htop", "btop" -> ProgramMark.Top
        else -> ProgramMark.Other
    }
}

fun shortId(id: String): String {
    val tail = id.substringAfterLast(':').substringAfterLast('-')
    return if (tail.length >= 4) tail.takeLast(6) else id.takeLast(6)
}

fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key)
}

class RexCallException(val code: String, val status: Int, message: String) : Exception(message)
