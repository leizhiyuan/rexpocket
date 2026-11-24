package me.leaver.rexpocket.core

// The activity view: every tab with a program that told Rex what it is doing, grouped by that state
// the way Rex's own Activity groups tabs "by priority". Sources and what is inferred are in
// docs/design/rex-parity.md.

/**
 * The groups of the activity view, in the order they are shown: Rex's "Show Activity by Priority"
 * lists blocked, failed, done, working, idle. The states are the Program Status Protocol's
 * (`blocked`, `error`, `done`, `working`, `idle`). Idle is not shown: Rex keeps an idle tab in
 * Activity only until it has been seen, and whether a tab was seen is the desktop's own, not
 * something Rex tells another client.
 */
enum class ActivityGroup(val state: String) {
    /** Cannot go on until the person does something: a permission, a question, a login. */
    Waiting("blocked"),
    Failed("error"),
    Done("done"),
    Running("working"),
    ;

    /**
     * A failed or done tab has news until it is seen; a waiting one until it stops waiting, and a
     * running one never has any.
     */
    val needsSeeing: Boolean get() = this == Failed || this == Done

    companion object {
        fun of(state: String): ActivityGroup? = entries.firstOrNull { it.state == state }
    }
}

/**
 * One tab in the activity view: where it is, the pane whose report it shows and that report, and
 * when the phone saw that state begin ([since]; null when the state was already there when the
 * phone first read it and Rex stamped no time on it). [attention] is the whole tab: its most
 * pressing state, which colours its dot, whether anything in it works, and how many reports of
 * each kind it has.
 */
data class ActivityItem(
    val sessionId: String,
    val sessionLabel: String,
    val window: WindowInfo,
    val block: BlockInfo,
    val record: ProgramRecord,
    val since: Long?,
    val attention: TabAttention = TabAttention(block, record, ActivityGroup.of(record.state)!!),
) {
    val group: ActivityGroup get() = ActivityGroup.of(record.state)!!
}

data class ActivitySection(val group: ActivityGroup, val items: List<ActivityItem>)

/** Whether one report of a pane was seen; a done or failed one that was is no news. Per record. */
typealias SeenCheck = (blockId: String, record: ProgramRecord) -> Boolean

/** One report of one pane. */
data class PaneReport(val block: BlockInfo, val record: ProgramRecord)

/**
 * What a tab is doing, from every record of every pane in it (docs/design/home.md, "What a row
 * says"). A done or failed record that was seen no longer counts; the others of its pane still do,
 * so a pane whose root works while a child record is done and seen is running.
 *
 * [block], [record] and [group] are the most pressing report left, in Rex's Activity order
 * (Waiting before Failed before Done before Running): it colours the tab's dot and puts the tab in
 * Needs you when it is not Running. [running] is the tab's working report, if any - the focused
 * pane's first, else the first in pane order - and puts the tab in Running whatever else it has.
 * [counts] is how many reports of each group the tab has, seen ones left out.
 */
data class TabAttention(
    val block: BlockInfo,
    val record: ProgramRecord,
    val group: ActivityGroup,
    val running: PaneReport? = if (group == ActivityGroup.Running) PaneReport(block, record) else null,
    val counts: Map<ActivityGroup, Int> = mapOf(group to 1),
) {
    /** Something in the tab is at work: it is in Running. */
    val isRunning: Boolean get() = running != null

    /** It waits on the person, or failed or finished and that was not seen: it is in Needs you. */
    val needsYou: Boolean get() = group != ActivityGroup.Running

    /**
     * What else the tab has besides one report of group [shown], in Rex's order, for the words
     * after that report's own: each other group and how many reports of it, Running counted once.
     * "Running · 1 done", "Waiting for approval · running".
     */
    fun alsoBesides(shown: ActivityGroup): List<Pair<ActivityGroup, Int>> = ActivityGroup.entries.mapNotNull { g ->
        val n = (counts[g] ?: 0) - if (g == shown) 1 else 0
        when {
            n <= 0 -> null
            g == ActivityGroup.Running -> if (shown == ActivityGroup.Running) null else g to 1
            else -> g to n
        }
    }
}

/** Every record of a pane, root first, as Rex lists them; the summary alone where only it is known. */
fun paneRecords(state: PocketState, blockId: String): List<ProgramRecord> =
    state.records[blockId] ?: listOfNotNull(state.blockStatus[blockId])

fun tabAttention(
    window: WindowInfo,
    state: PocketState,
    seen: SeenCheck = { _, _ -> false },
): TabAttention? {
    var top: PaneReport? = null
    var topGroup: ActivityGroup? = null
    var running: PaneReport? = null
    val counts = LinkedHashMap<ActivityGroup, Int>()
    val focused = window.focusedBlock()?.id
    for (block in window.blocks) {
        for (record in paneRecords(state, block.id)) {
            val group = ActivityGroup.of(record.state) ?: continue
            if (group.needsSeeing && seen(block.id, record)) continue
            counts[group] = (counts[group] ?: 0) + 1
            if (topGroup == null || group.ordinal < topGroup.ordinal) {
                top = PaneReport(block, record)
                topGroup = group
            }
            val current = running
            if (group == ActivityGroup.Running && (current == null || (block.id == focused && current.block.id != focused))) {
                running = PaneReport(block, record)
            }
        }
    }
    val shown = top ?: return null
    return TabAttention(shown.block, shown.record, topGroup!!, running, counts)
}

/** Each tab of [state] that has a report, with its attention, in the order of the sessions and their tabs. */
private fun tabItems(state: PocketState, seen: SeenCheck): List<ActivityItem> {
    val items = ArrayList<ActivityItem>()
    val order = state.sessions.map { it.id } + state.views.keys.filter { id -> state.sessions.none { it.id == id } }
    for (sid in order) {
        val view = state.views[sid] ?: continue
        val label = state.sessions.firstOrNull { it.id == sid }?.label ?: view.label
        for (window in view.windows) {
            val pane = tabAttention(window, state, seen) ?: continue
            items += ActivityItem(sid, label, window, pane.block, pane.record, sinceOf(state, pane.block.id, pane.record), pane)
        }
    }
    return items
}

/**
 * When [record]'s state began: the pane's own time ([PocketState.statusSince]) when it is the
 * report that speaks for the pane, else Rex's stamp on the record.
 */
private fun sinceOf(state: PocketState, blockId: String, record: ProgramRecord): Long? {
    val summary = state.blockStatus[blockId]
    return if (summary == null || summary.id == record.id) state.statusSince[blockId] else record.updatedAt
}

/** Newest first, as the newest news; those with no known time after, in their own order. */
private fun List<ActivityItem>.newestFirst(): List<ActivityItem> = withIndex()
    .sortedWith(compareByDescending<IndexedValue<ActivityItem>> { it.value.since ?: Long.MIN_VALUE }.thenBy { it.index })
    .map { it.value }

/**
 * The activity view of [state]: each group that has a tab in it, in [ActivityGroup] order. A tab
 * stands where its most pressing report puts it ([tabAttention]), and shows that pane. Inside a
 * group the tab whose state the phone saw begin last comes first, as the newest news; tabs with no
 * known time follow in the order of the sessions and their tabs. With [seen], a done or failed
 * report that was seen is left out, as the home's Needs you leaves it.
 */
fun activitySections(
    state: PocketState,
    seen: SeenCheck = { _, _ -> false },
): List<ActivitySection> {
    val items = tabItems(state, seen)
    return ActivityGroup.entries.mapNotNull { group ->
        val inGroup = items.filter { it.group == group }.newestFirst()
        if (inGroup.isEmpty()) null else ActivitySection(group, inGroup)
    }
}

/**
 * The home's Needs you: the tabs that wait on the person, and those whose most pressing report is
 * a failure or a result not yet seen, in [activitySections]' groups. A tab here can be in
 * [runningItems] too.
 */
fun needsYouSections(
    state: PocketState,
    seen: SeenCheck = { _, _ -> false },
): List<ActivitySection> = activitySections(state, seen).filter { it.group != ActivityGroup.Running }

/**
 * The home's Running: every tab with a working report, whatever else it has - a tab whose agent
 * works while a child record is done, or that works in one pane and waits in another, is in Needs
 * you and here too. Each row shows its working report ([ActivityItem.record]); the tab's most
 * pressing state is in [ActivityItem.attention]. Newest first.
 */
fun runningItems(
    state: PocketState,
    seen: SeenCheck = { _, _ -> false },
): List<ActivityItem> = tabItems(state, seen).mapNotNull { item ->
    val run = item.attention.running ?: return@mapNotNull null
    item.copy(block = run.block, record = run.record, since = sinceOf(state, run.block.id, run.record))
}.newestFirst()

/**
 * What a seen mark is for: one report as the alerts tell it apart (its state, kind and message,
 * not its title, which programs animate), so a new report is news again. A child record's stamp
 * starts with its id, so seeing one record of a pane says nothing of the others; the root
 * record's is the stamp marks had before they were per record, so those marks still hold.
 */
fun seenStamp(record: ProgramRecord): String {
    val stamp = listOf(record.state, record.kind.orEmpty(), record.msg.orEmpty()).joinToString("\u0000")
    return if (record.id.isEmpty()) stamp else record.id + "\u0001" + stamp
}

/** Reports a seen mark is kept for: done and failed ones, which are news until seen, and idle ones. */
fun markable(record: ProgramRecord): Boolean = record.state == "done" || record.state == "error" || record.state == "idle"

/**
 * The mark of a pane whose [records] were all seen: the [seenStamp]s of those a mark is kept for
 * ([markable]), one per line - a record's text has no line breaks, the protocol refuses control
 * characters. Null when there is none.
 */
fun seenMark(records: Collection<ProgramRecord>): String? =
    records.filter(::markable).map(::seenStamp).sorted().takeIf { it.isNotEmpty() }?.joinToString(MARK_SEPARATOR)

/** Whether a pane's [mark] covers [record]: that report, of that record, was seen. */
fun markCovers(mark: String?, record: ProgramRecord): Boolean =
    mark != null && seenStamp(record) in mark.split(MARK_SEPARATOR)

/**
 * Two marks of one pane as one; with [records], kept to the stamps of those reports, so a mark
 * does not grow with reports that are gone. Null when nothing is left.
 */
fun mergeMarks(a: String?, b: String?, records: Collection<ProgramRecord>? = null): String? {
    val alive = records?.mapTo(HashSet(), ::seenStamp)
    return (a?.split(MARK_SEPARATOR).orEmpty() + b?.split(MARK_SEPARATOR).orEmpty())
        .filter { it.isNotEmpty() && (alive == null || it in alive) }
        .toSortedSet()
        .takeIf { it.isNotEmpty() }
        ?.joinToString(MARK_SEPARATOR)
}

private const val MARK_SEPARATOR = "\n"

/** How long ago [since] was, as Rex's sidebar writes it: `9m`, `3h`, `2d`; null under a minute. */
fun activityAge(since: Long, now: Long): String? {
    val seconds = ((now - since) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> null
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3600}h"
        else -> "${seconds / 86_400}d"
    }
}
