package me.leaver.rexpocket.core

import java.time.Instant

// Status details: everything Rex reports about one tab's programs, what the phone made of it and
// which reports were seen - for finding out why a tab is, or is not, where the home puts it. The
// full form is shown and copied only when the person asks; the diagnostics report carries the
// redacted digest ([activityDigest]), without titles, messages or record ids.

/** One record of one pane as Status details lists it, with whether the phone's and a desk's marks cover it. */
data class RecordDetail(val record: ProgramRecord, val seenOnPhone: Boolean, val seenOnDesk: Boolean)

/** One pane: its place in the tab, what the phone summed its records up to, and every record. */
data class PaneDetail(
    val index: Int,
    val block: BlockInfo,
    val focused: Boolean,
    val program: String?,
    val summary: ProgramRecord?,
    val records: List<RecordDetail>,
)

/** One tab's Status details: what the home made of it ([attention], seen marks applied) and its panes. */
data class TabDetails(
    val window: WindowInfo,
    val attention: TabAttention?,
    val panes: List<PaneDetail>,
) {
    /** The tab's lists on the home: Needs you, Working (HomeFilter.Running), both, or neither. */
    fun lists(): String = listOfNotNull(
        "Needs you".takeIf { attention?.needsYou == true },
        "Working".takeIf { attention?.isRunning == true },
    ).ifEmpty { listOf("neither") }.joinToString(", ")

    /** What the phone shows for the tab, in short: "done (record task-1) · also working". */
    fun phoneShows(): String {
        val a = attention ?: return "nothing (no report that is news)"
        val also = a.alsoBesides(a.group).joinToString(", ") { (g, n) -> if (g == ActivityGroup.Running) "working" else "$n ${g.state}" }
        return buildString {
            append(a.group.state).append(" (pane ").append(a.block.label).append(", record ").append(recordName(a.record.id)).append(')')
            if (also.isNotEmpty()) append(" · also ").append(also)
        }
    }

    /** The lines of one pane, as the sheet shows them and Copy copies them. */
    fun paneLines(pane: PaneDetail, now: Long): List<String> = buildList {
        add("block ${pane.block.id}${if (pane.focused) " (focused)" else ""}")
        pane.program?.let { add("program: $it") }
        add("phone summary: ${pane.summary?.let { "${it.state} (record ${recordName(it.id)})" } ?: "none"}")
        if (pane.records.isEmpty()) add("records: none")
        for (detail in pane.records) {
            val r = detail.record
            add("record ${recordName(r.id)}")
            add("  parent: ${r.parent?.let(::recordName) ?: "-"}")
            add("  state: ${r.state}${r.kind?.let { " · kind: $it" }.orEmpty()}")
            add("  app: ${r.reportedApp ?: "-"} · effective_app: ${r.effectiveApp ?: "-"}")
            add("  owner: ${if (r.ownerPid == null && r.ownerName == null) "-" else "${r.ownerPid ?: "?"} ${r.ownerName ?: "?"}"}")
            r.title?.let { add("  title: $it") }
            r.msg?.let { add("  msg: $it") }
            r.progress?.let { add("  progress: $it%") }
            add("  updated_at: ${r.updatedAt?.let { "${Instant.ofEpochMilli(it)} (${activityAge(it, now) ?: "now"})" } ?: "-"}")
            add("  seen: phone ${yesNo(detail.seenOnPhone)} · desk ${yesNo(detail.seenOnDesk)}")
        }
    }

    /** The whole of it as plain text, for Copy: [title] is the tab's name as the home shows it. */
    fun text(title: String, now: Long): String = buildString {
        appendLine("Status details: $title")
        appendLine("tab ${window.id}")
        appendLine("phone shows: ${phoneShows()}")
        appendLine("home lists: ${lists()}")
        for (pane in panes) {
            appendLine()
            appendLine("Pane ${pane.index + 1} · ${pane.block.label}")
            for (line in paneLines(pane, now)) appendLine("  $line")
        }
    }.trimEnd()

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"
}

/** The root record is the one with no id; the details call it that. */
fun recordName(id: String): String = id.ifEmpty { "(root)" }

/**
 * The Status details of [windowId] in [state]: every record Rex reported for each of its panes,
 * the phone's summary, and whether the phone's mark ([phoneMark], by pane) and a desk's
 * ([PocketState.deskSeen]) cover each record. Null when the tab is not known.
 */
fun tabDetails(state: PocketState, windowId: String, phoneMark: (blockId: String) -> String?): TabDetails? {
    val window = state.views.values.flatMap { it.windows }.firstOrNull { it.id == windowId } ?: return null
    val focused = window.focusedBlock()?.id
    val seen: SeenCheck = { block, record -> markCovers(phoneMark(block), record) }
    val panes = window.blocks.mapIndexed { i, block ->
        val desk = state.deskSeen[block.id]
        val phone = phoneMark(block.id)
        PaneDetail(
            index = i,
            block = block,
            focused = block.id == focused,
            program = state.programs[block.id],
            summary = state.blockStatus[block.id],
            records = paneRecords(state, block.id).map { RecordDetail(it, markCovers(phone, it), markCovers(desk, it)) },
        )
    }
    return TabDetails(window, tabAttention(window, state, seen), panes)
}

/**
 * The diagnostics report's form of what programs reported on one host, one line per tab that has
 * a record: by place, not by name - "tab 2 pane 1" - each record's state, kind, app, owner name,
 * age and seen marks, what the phone made of the tab and the lists it is in. No titles, messages,
 * record ids or labels: those are the program's and the person's words (Diagnostics' rules).
 */
fun activityDigest(state: PocketState, phoneMark: (blockId: String) -> String?, now: Long): List<String> {
    val lines = ArrayList<String>()
    val order = state.sessions.map { it.id } + state.views.keys.filter { id -> state.sessions.none { it.id == id } }
    order.forEachIndexed { s, sid ->
        val view = state.views[sid] ?: return@forEachIndexed
        view.windows.forEachIndexed { t, window ->
            val details = tabDetails(state, window.id, phoneMark) ?: return@forEachIndexed
            val panes = details.panes.filter { it.records.isNotEmpty() }
            if (panes.isEmpty()) return@forEachIndexed
            val parts = panes.map { pane ->
                val records = pane.records.joinToString(", ") { d ->
                    val r = d.record
                    buildString {
                        append(if (r.id.isEmpty()) "root" else "child${r.id.count { it == '/' } + 1}").append(' ').append(r.state)
                        r.kind?.let { append('/').append(it) }
                        r.effectiveApp?.let { append(" app=").append(it) }
                        r.ownerName?.let { append(" owner=").append(it) }
                        r.updatedAt?.let { append(' ').append(activityAge(it, now) ?: "now") }
                        if (d.seenOnPhone) append(" seen-phone")
                        if (d.seenOnDesk) append(" seen-desk")
                    }
                }
                "pane ${pane.index + 1}${if (pane.focused) "*" else ""}: $records"
            }
            val shows = details.attention?.let { a -> a.group.state + (if (a.isRunning && a.group != ActivityGroup.Running) "+working" else "") } ?: "nothing"
            lines += "session ${s + 1} tab ${t + 1}: ${parts.joinToString(" | ")} -> $shows [${details.lists()}]"
        }
    }
    return lines
}
