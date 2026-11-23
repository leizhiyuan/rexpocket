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
 * phone first read it - a record carries no time, so how old it is cannot be known then).
 */
data class ActivityItem(
    val sessionId: String,
    val sessionLabel: String,
    val window: WindowInfo,
    val block: BlockInfo,
    val record: ProgramRecord,
    val since: Long?,
) {
    val group: ActivityGroup get() = ActivityGroup.of(record.state)!!
}

data class ActivitySection(val group: ActivityGroup, val items: List<ActivityItem>)

/**
 * A tab's most pressing pane, the one the activity view shows it by, with that pane's report and its
 * group: Waiting before Failed before Done before Running. A done or failed report that [seen] says
 * was seen no longer counts, so a pane running beside it speaks for the tab.
 */
data class TabAttention(val block: BlockInfo, val record: ProgramRecord, val group: ActivityGroup)

fun tabAttention(
    window: WindowInfo,
    state: PocketState,
    seen: (blockId: String, record: ProgramRecord) -> Boolean = { _, _ -> false },
): TabAttention? = window.blocks
    .mapNotNull { block ->
        val record = state.blockStatus[block.id] ?: return@mapNotNull null
        val group = ActivityGroup.of(record.state) ?: return@mapNotNull null
        if (group.needsSeeing && seen(block.id, record)) null else TabAttention(block, record, group)
    }
    .minByOrNull { it.group.ordinal }

/**
 * The activity view of [state]: each group that has a tab in it, in [ActivityGroup] order. A tab
 * stands where its most pressing pane puts it ([tabAttention]), and shows that pane. Inside a group
 * the tab whose state the phone saw begin last comes first, as the newest news; tabs with no known
 * time follow in the order of the sessions and their tabs. With [seen], a done or failed pane that
 * was seen is left out, as the home's Needs you leaves it.
 */
fun activitySections(
    state: PocketState,
    seen: (blockId: String, record: ProgramRecord) -> Boolean = { _, _ -> false },
): List<ActivitySection> {
    val items = ArrayList<ActivityItem>()
    val order = state.sessions.map { it.id } + state.views.keys.filter { id -> state.sessions.none { it.id == id } }
    for (sid in order) {
        val view = state.views[sid] ?: continue
        val label = state.sessions.firstOrNull { it.id == sid }?.label ?: view.label
        for (window in view.windows) {
            val pane = tabAttention(window, state, seen) ?: continue
            items += ActivityItem(sid, label, window, pane.block, pane.record, state.statusSince[pane.block.id])
        }
    }
    return ActivityGroup.entries.mapNotNull { group ->
        val inGroup = items.withIndex()
            .filter { it.value.group == group }
            .sortedWith(compareByDescending<IndexedValue<ActivityItem>> { it.value.since ?: Long.MIN_VALUE }.thenBy { it.index })
            .map { it.value }
        if (inGroup.isEmpty()) null else ActivitySection(group, inGroup)
    }
}

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
