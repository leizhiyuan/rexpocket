package me.leaver.rexpocket.ui

import me.leaver.rexpocket.ui.guide.GuideSpot
import me.leaver.rexpocket.ui.guide.guideTarget
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.heading
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.approvalKeys
import me.leaver.rexpocket.core.ActivityGroup
import me.leaver.rexpocket.core.ActivityItem
import me.leaver.rexpocket.core.ActivitySection
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.ProgramRecord
import me.leaver.rexpocket.core.activityAge
import me.leaver.rexpocket.core.activitySections
import me.leaver.rexpocket.core.needsYouSections
import me.leaver.rexpocket.core.runningItems
import me.leaver.rexpocket.core.TabAttention

// The home's filters: Needs you - what waits on the person, and what failed or finished that they
// have not seen - and Running, each a flat list in Rex's priority order (Waiting, Failed, Done,
// Running) under each connected host's name. See docs/design/home.md and, for what comes from Rex,
// docs/design/rex-parity.md.

/**
 * Which of the home's lists shows: every host's tree, the Activity view (every tab a program
 * reported on, grouped as Rex's Activity groups it), what needs the person, or what runs.
 */
enum class HomeFilter {
    All, Activity, NeedsYou, Running;

    companion object {
        fun of(name: String?): HomeFilter = entries.firstOrNull { it.name == name } ?: All
    }
}

/**
 * The tabs [filter] lists of one host, most pressing first: for Needs you the waiting ones, and the
 * failed and done ones not yet [seen]; for Running every tab with anything at work, whatever else
 * it has - so a tab can be in both. Seen is per report: a seen done report of a pane that still
 * works leaves it running.
 */
internal fun filterSections(
    state: PocketState,
    filter: HomeFilter,
    seen: (String, ProgramRecord) -> Boolean,
    prefs: ActivityPrefs = ActivityPrefs(),
): List<ActivitySection> =
    when (filter) {
        HomeFilter.All -> activitySections(state, seen)
        HomeFilter.Activity -> activitySections(state, seen, prefs.idle(seen))
        HomeFilter.NeedsYou -> needsYouSections(state, seen)
        HomeFilter.Running -> runningItems(state, seen).takeIf { it.isNotEmpty() }?.let { listOf(ActivitySection(ActivityGroup.Running, it)) }.orEmpty()
    }

/**
 * The words for what else a tab has besides a report of group [shown], in Rex's order: "running",
 * "1 done", "2 waiting". A row says them after its own state.
 */
@Composable
internal fun alsoWords(attention: TabAttention, shown: ActivityGroup): List<String> = attention.alsoBesides(shown).map { (group, n) ->
    when (group) {
        ActivityGroup.Running -> stringResource(R.string.state_running)
        ActivityGroup.Waiting -> stringResource(R.string.home_count_waiting, n)
        ActivityGroup.Failed -> stringResource(R.string.home_count_failed, n)
        ActivityGroup.Done -> stringResource(R.string.home_count_done, n)
        ActivityGroup.Idle -> stringResource(R.string.state_idle)
    }
}

/**
 * The Activity view's two settings, as Rex's: "Show Activity by Session" (else by priority) and
 * "Include idle processes in activity" - off, an idle tab is listed until it was seen; on, always.
 * Kept, and in the settings backup.
 */
data class ActivityPrefs(val bySession: Boolean = false, val includeIdle: Boolean = false) {
    /** Which idle reports are left out: the seen ones, or none with Include idle. */
    fun idle(seen: (String, ProgramRecord) -> Boolean): (String, ProgramRecord) -> Boolean =
        if (includeIdle) { _, _ -> false } else seen
}

/** Test tags of the Activity view's controls. */
internal const val ACTIVITY_BY_PRIORITY_TAG = "activity-by-priority"
internal const val ACTIVITY_BY_SESSION_TAG = "activity-by-session"
internal const val ACTIVITY_IDLE_TAG = "activity-include-idle"

/** Test tag of an Activity group's or session's caption. */
internal fun activityGroupTag(group: ActivityGroup) = "activity-group-${group.name}"
internal fun activitySessionTag(sessionId: String) = "activity-session-$sessionId"

/**
 * The Activity view's controls, at the top of its list: By priority or By session, as two chips
 * that say which is on, and Include idle, a switch the whole row toggles. All 48 dp.
 */
@Composable
internal fun ActivityControls(prefs: ActivityPrefs, onPrefs: (ActivityPrefs) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((bySession, label, tag) in listOf(
                Triple(false, stringResource(R.string.activity_by_priority), ACTIVITY_BY_PRIORITY_TAG),
                Triple(true, stringResource(R.string.activity_by_session), ACTIVITY_BY_SESSION_TAG),
            )) {
                FilterChip(
                    selected = prefs.bySession == bySession,
                    onClick = { onPrefs(prefs.copy(bySession = bySession)) },
                    label = { Text(label, fontSize = TextStyles.label, fontWeight = FontWeight.Medium) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Colors.surface,
                        labelColor = Colors.ink,
                        selectedContainerColor = Colors.keyOn,
                        selectedLabelColor = Colors.accent,
                    ),
                    border = null,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(tag),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(Radius.card))
                .toggleable(value = prefs.includeIdle, role = Role.Switch) { onPrefs(prefs.copy(includeIdle = it)) }
                .testTag(ACTIVITY_IDLE_TAG)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.activity_include_idle), color = Colors.ink, fontSize = TextStyles.label, modifier = Modifier.weight(1f))
            Switch(checked = prefs.includeIdle, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = Colors.accent))
        }
    }
}

/** A caption over a group of the Activity view, or a session in it: a TalkBack heading. */
@Composable
internal fun ActivityCaption(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = color,
        fontSize = TextStyles.label,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, bottom = 6.dp).semantics { heading() },
    )
}

/** One row of the Activity view, from any connected host: the row, its host's state and its ops. */
internal class HostedItem(val item: ActivityItem, val state: PocketState, val ops: AttentionOps, val host: String?)

/** Rows of several hosts on one card, as the Activity view's priority groups have them. */
internal fun LazyListScope.hostedItems(key: String, items: List<HostedItem>) {
    if (items.isEmpty()) return
    item(key = key) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(Colors.surface)) {
            items.forEachIndexed { i, hosted ->
                if (i > 0) Box(Modifier.padding(start = 64.dp).fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(Colors.line))
                AttentionRow(hosted.item, hosted.state, hosted.ops, hosted.host)
            }
        }
    }
}

/** [sections] narrowed by [query] on a tab's title, its session and its message. */
@Composable
internal fun searched(sections: List<ActivitySection>, state: PocketState, query: String): List<ActivitySection> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return sections
    return sections.mapNotNull { section ->
        val items = section.items.filter { item ->
            val title = state.views[item.sessionId]?.let { tabTitle(it, item.window, state) } ?: item.window.label
            listOfNotNull(title, item.sessionLabel, item.record.msg, item.record.app).any { it.lowercase().contains(q) }
        }
        if (items.isEmpty()) null else section.copy(items = items)
    }
}

/** The caption a group is said by, as Rex writes it: Waiting, Failed, Done, Running. */
@Composable
internal fun activityGroupName(group: ActivityGroup): String = stringResource(
    when (group) {
        ActivityGroup.Waiting -> R.string.activity_waiting
        ActivityGroup.Failed -> R.string.activity_failed
        ActivityGroup.Done -> R.string.activity_done
        ActivityGroup.Running -> R.string.activity_running
        ActivityGroup.Idle -> R.string.activity_idle
    },
)

/** A state's colour: amber waiting, red failed, blue done, green running. The word always goes with it. */
internal fun activityTint(group: ActivityGroup): Color = when (group) {
    ActivityGroup.Waiting -> Colors.amber
    ActivityGroup.Failed -> Colors.danger
    ActivityGroup.Done -> Colors.accent
    ActivityGroup.Running -> Colors.ok
    ActivityGroup.Idle -> Colors.muted
}

/** Test tag of one filtered row, by tab id. */
internal fun activityRowTag(windowId: String) = "activity-row-$windowId"

/** Test tag of a filter chip. */
internal fun filterChipTag(filter: HomeFilter) = "filter-${filter.name}"

/**
 * The chips over the list: All, Needs you and Running, each with its count across every connected
 * host. The chip on is said as selected; its count is in its words, so TalkBack reads it.
 */
@Composable
internal fun FilterChips(
    filter: HomeFilter,
    needsYou: Int,
    running: Int,
    onFilter: (HomeFilter) -> Unit,
    modifier: Modifier = Modifier,
    /** Some hosts are not connected, so the counts are the connected hosts' only: the counted chips carry a hollow mark. */
    partial: Boolean = false,
) {
    val connectedOnly = stringResource(R.string.home_counts_connected_only)
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (choice in HomeFilter.entries) {
            val label = when (choice) {
                HomeFilter.All -> stringResource(R.string.filter_all)
                HomeFilter.Activity -> stringResource(R.string.filter_activity)
                HomeFilter.NeedsYou -> stringResource(R.string.filter_needs_you, needsYou)
                HomeFilter.Running -> stringResource(R.string.filter_running, running)
            }
            val marked = partial && choice != HomeFilter.All
            FilterChip(
                selected = filter == choice,
                onClick = { onFilter(choice) },
                label = { Text(label, fontSize = TextStyles.label, fontWeight = FontWeight.Medium) },
                trailingIcon = if (marked) {
                    { Box(Modifier.size(7.dp).border(1.5.dp, Colors.muted, androidx.compose.foundation.shape.CircleShape)) }
                } else {
                    null
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Colors.surface,
                    labelColor = Colors.ink,
                    selectedContainerColor = Colors.keyOn,
                    selectedLabelColor = Colors.accent,
                ),
                border = null,
                modifier = Modifier.heightIn(min = 48.dp).testTag(filterChipTag(choice))
                    .guideTarget(
                        when (choice) {
                            HomeFilter.NeedsYou -> GuideSpot.HomeNeedsYou
                            HomeFilter.Activity -> GuideSpot.HomeActivity
                            else -> null
                        },
                    )
                    .semantics { if (marked) stateDescription = connectedOnly },
            )
        }
    }
}

/**
 * How many idle tabs the Activity view leaves out because they were seen, across [states]: the
 * count of "N idle tabs hidden · Show", which turns Include idle on. Zero with Include idle on.
 */
internal fun hiddenIdle(states: List<Pair<PocketState, (String, ProgramRecord) -> Boolean>>, prefs: ActivityPrefs): Int {
    if (prefs.includeIdle) return 0
    return states.sumOf { (state, seen) ->
        val all = activitySections(state, seen, prefs.copy(includeIdle = true).idle(seen)).sumOf { it.items.size }
        val shown = activitySections(state, seen, prefs.idle(seen)).sumOf { it.items.size }
        (all - shown).coerceAtLeast(0)
    }
}

/** What a filtered row's buttons and sheet do; Reply and Interrupt are Needs you's only. */
internal class AttentionOps(
    val open: (ActivityItem) -> Unit,
    val manage: (ActivityItem) -> Unit,
    /** The row's TalkBack actions: its tab sheet's items. */
    val actionsOf: @Composable (ActivityItem) -> List<CustomAccessibilityAction>,
    val reply: ((ActivityItem) -> Unit)?,
    val interrupt: ((ActivityItem) -> Unit)?,
    /** Approve (true) or Deny an agent's approval prompt; Needs you's only. */
    val answer: ((ActivityItem, Boolean) -> Unit)? = null,
    /** Marks a done or failed row seen, so it leaves the list (with Undo); Needs you's and Activity's. */
    val dismiss: ((ActivityItem) -> Unit)? = null,
)

/** The rows of one host's filtered list: one card, in priority order, no folds. */
internal fun LazyListScope.attentionItems(
    sections: List<ActivitySection>,
    state: PocketState,
    keyPrefix: String,
    ops: AttentionOps,
) {
    val items = sections.flatMap { it.items }
    if (items.isEmpty()) return
    item(key = "attention-$keyPrefix") {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(Radius.card)).background(Colors.surface)) {
            items.forEachIndexed { i, item ->
                if (i > 0) Box(Modifier.padding(start = 64.dp).fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(Colors.line))
                AttentionRow(item, state, ops)
            }
        }
    }
}

/**
 * One tab: the tile of the program that reported, dotted with its state's colour; the tab's title;
 * its state in words, its session and how long ago the phone saw the state begin; the program's
 * message, if it gave one, in a box under them - Rex shows a failure's message that way - and, in
 * Needs you, Reply (never for a login: a password is typed into the pane) and Interrupt.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
internal fun AttentionRow(item: ActivityItem, state: PocketState, ops: AttentionOps, host: String? = null) {
    val view = state.views[item.sessionId]
    val title = view?.let { tabTitle(it, item.window, state) } ?: item.window.label
    val program = state.programs[item.block.id] ?: item.record.app
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(item.since) {
        while (item.since != null) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val age = item.since?.let { activityAge(it, now) ?: stringResource(R.string.activity_now) }
    val progress = item.record.progress?.takeIf { item.group == ActivityGroup.Running || item.group == ActivityGroup.Waiting }
    // The dot is the tab's most pressing state, in Rex's order; the words lead with the row's own.
    val tint = activityTint(item.attention.group)
    val ownTint = activityTint(item.group)
    val manageLabel = stringResource(R.string.manage_actions_for, title)
    val actions = ops.actionsOf(item)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .testTag(activityRowTag(item.window.id))
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.home_action_open),
                    onLongClickLabel = manageLabel,
                    onLongClick = { ops.manage(item) },
                    onClick = { ops.open(item) },
                )
                .semantics { if (actions.isNotEmpty()) customActions = actions }
                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.padding(top = 2.dp)) {
                ProgramTile(program, size = 36.dp)
                Dot(tint, 8.dp, Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp), ring = Colors.surface)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Colors.ink, fontSize = TextStyles.body, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val word = (listOf(statusWord(item.record) ?: activityGroupName(item.group)) + alsoWords(item.attention, item.group)).joinToString(" · ")
                val detail = listOfNotNull(item.sessionLabel, host, progress?.let { "$it%" }, age).joinToString(" · ")
                Text(word, color = ownTint, fontSize = TextStyles.label, fontWeight = FontWeight.Medium)
                Text(detail, color = Colors.muted, fontSize = TextStyles.label)
                val message = item.record.msg?.takeIf { it.isNotBlank() }
                if (message != null) {
                    Text(
                        message,
                        color = Colors.muted,
                        fontSize = TextStyles.label,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Colors.key)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            RoundButton(R.drawable.ic_more, manageLabel, { ops.manage(item) }, tint = Colors.muted, size = 48.dp)
        }
        val reply = ops.reply?.takeIf { item.group == ActivityGroup.Waiting && item.record.kind != "auth" }
        val interrupt = ops.interrupt?.takeIf { item.block.id in state.busy }
        // Approve and Deny for an agent's approval prompt the app knows the keys of; never a login.
        val answer = ops.answer?.takeIf { item.group == ActivityGroup.Waiting && approvalKeys(item.record) != null }
        // A finished or failed row is news until seen: one tap says so, as the tab sheet's Mark as seen.
        val dismiss = ops.dismiss?.takeIf { item.group == ActivityGroup.Done || item.group == ActivityGroup.Failed }
        if (reply != null || interrupt != null || answer != null || dismiss != null) {
            FlowRow(Modifier.fillMaxWidth().padding(start = 52.dp, end = 8.dp, bottom = 4.dp)) {
                if (answer != null) {
                    val approveFor = stringResource(R.string.home_approve_for, title)
                    val denyFor = stringResource(R.string.home_deny_for, title)
                    RowButton(stringResource(R.string.alerts_approve), { answer(item, true) }, Modifier.semantics { contentDescription = approveFor })
                    RowButton(stringResource(R.string.alerts_deny), { answer(item, false) }, Modifier.semantics { contentDescription = denyFor })
                }
                if (reply != null) RowButton(stringResource(R.string.alerts_reply), { reply(item) })
                if (interrupt != null) RowButton(stringResource(R.string.alerts_interrupt), { interrupt(item) })
                if (dismiss != null) {
                    val seenFor = stringResource(R.string.home_mark_seen_for, title)
                    RowButton(stringResource(R.string.home_mark_seen), { dismiss(item) }, Modifier.semantics { contentDescription = seenFor })
                }
            }
        }
    }
}

/**
 * What a filter shows when nothing is in it: why, in one line. The filters only hear connected
 * hosts, so with [away] hosts not connected it says the list is the connected hosts' and offers
 * Connect all, rather than "Nothing needs you" over hosts it cannot see.
 */
@Composable
internal fun ActivityEmpty(filter: HomeFilter, live: Boolean, searching: Boolean, away: Int = 0, onConnectAll: (() -> Unit)? = null) {
    val partial = live && away > 0
    val text = stringResource(
        when {
            !live -> R.string.activity_offline
            searching -> R.string.search_none
            filter == HomeFilter.Running -> if (partial) R.string.filter_running_none_connected else R.string.filter_running_none
            filter == HomeFilter.Activity -> if (partial) R.string.filter_activity_none_connected else R.string.filter_activity_none
            else -> if (partial) R.string.filter_needs_you_none_connected else R.string.filter_needs_you_none
        },
    )
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (away > 0 && live) text + " · " + stringResource(R.string.home_not_connected_count, away) else text,
            color = Colors.muted,
            fontSize = TextStyles.subhead,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        if (away > 0 && onConnectAll != null) RowButton(stringResource(R.string.home_connect_all), onConnectAll)
    }
}
