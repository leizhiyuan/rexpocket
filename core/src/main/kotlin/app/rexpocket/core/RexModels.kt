package app.rexpocket.core

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

    /** The most urgent program state among this tab's panes, for the tab's status dot. */
    fun status(byBlock: Map<String, ProgramRecord>): ProgramRecord? =
        summarizeStatus(blocks.mapNotNull { byBlock[it.id] })
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

    fun allBlocks(): List<BlockInfo> = windows.flatMap { it.blocks }
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
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optNullableString("session_id") ?: continue
            add(ListedSession(id, o.optNullableString("label") ?: shortId(id)))
        }
    }
}

fun parseProgramRecords(obj: JSONObject): List<ProgramRecord> {
    val arr = obj.optJSONArray("records") ?: return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
                ProgramRecord(
                    id = o.optNullableString("id") ?: "",
                    state = o.optNullableString("state") ?: "idle",
                    kind = o.optNullableString("kind"),
                    app = o.optNullableString("effective_app") ?: o.optNullableString("app"),
                    title = o.optNullableString("title"),
                    msg = o.optNullableString("msg"),
                    progress = if (o.has("progress") && !o.isNull("progress")) o.optInt("progress") else null,
                ),
            )
        }
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
