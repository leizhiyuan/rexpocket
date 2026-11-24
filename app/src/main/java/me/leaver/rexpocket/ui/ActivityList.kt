package me.leaver.rexpocket.ui

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
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

/** Which of the home's lists shows: every host's tree, what needs the person, or what runs. */
enum class HomeFilter {
    All, NeedsYou, Running;

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
internal fun filterSections(state: PocketState, filter: HomeFilter, seen: (String, ProgramRecord) -> Boolean): List<ActivitySection> =
    when (filter) {
        HomeFilter.All -> activitySections(state, seen)
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
    },
)

/** A state's colour: amber waiting, red failed, blue done, green running. The word always goes with it. */
internal fun activityTint(group: ActivityGroup): Color = when (group) {
    ActivityGroup.Waiting -> Colors.amber
    ActivityGroup.Failed -> Colors.danger
    ActivityGroup.Done -> Colors.accent
    ActivityGroup.Running -> Colors.ok
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
internal fun FilterChips(filter: HomeFilter, needsYou: Int, running: Int, onFilter: (HomeFilter) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (choice in HomeFilter.entries) {
            val label = when (choice) {
                HomeFilter.All -> stringResource(R.string.filter_all)
                HomeFilter.NeedsYou -> stringResource(R.string.filter_needs_you, needsYou)
                HomeFilter.Running -> stringResource(R.string.filter_running, running)
            }
            FilterChip(
                selected = filter == choice,
                onClick = { onFilter(choice) },
                label = { Text(label, fontSize = TextStyles.label, fontWeight = FontWeight.Medium) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Colors.surface,
                    labelColor = Colors.ink,
                    selectedContainerColor = Colors.keyOn,
                    selectedLabelColor = Colors.accent,
                ),
                border = null,
                modifier = Modifier.heightIn(min = 48.dp).testTag(filterChipTag(choice)),
            )
        }
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
private fun AttentionRow(item: ActivityItem, state: PocketState, ops: AttentionOps) {
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
                val detail = listOfNotNull(item.sessionLabel, progress?.let { "$it%" }, age).joinToString(" · ")
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
        if (reply != null || interrupt != null || answer != null) {
            FlowRow(Modifier.fillMaxWidth().padding(start = 52.dp, end = 8.dp, bottom = 4.dp)) {
                if (answer != null) {
                    val approveFor = stringResource(R.string.home_approve_for, title)
                    val denyFor = stringResource(R.string.home_deny_for, title)
                    RowButton(stringResource(R.string.alerts_approve), { answer(item, true) }, Modifier.semantics { contentDescription = approveFor })
                    RowButton(stringResource(R.string.alerts_deny), { answer(item, false) }, Modifier.semantics { contentDescription = denyFor })
                }
                if (reply != null) RowButton(stringResource(R.string.alerts_reply), { reply(item) })
                if (interrupt != null) RowButton(stringResource(R.string.alerts_interrupt), { interrupt(item) })
            }
        }
    }
}

/** What a filter shows when nothing is in it: why, in one line. */
@Composable
internal fun ActivityEmpty(filter: HomeFilter, live: Boolean, searching: Boolean) {
    Text(
        stringResource(
            when {
                !live -> R.string.activity_offline
                searching -> R.string.search_none
                filter == HomeFilter.Running -> R.string.filter_running_none
                else -> R.string.filter_needs_you_none
            },
        ),
        color = Colors.muted,
        fontSize = TextStyles.subhead,
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 16.dp),
        textAlign = TextAlign.Center,
    )
}
