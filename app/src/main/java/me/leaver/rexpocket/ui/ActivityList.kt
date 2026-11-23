package me.leaver.rexpocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.ActivityGroup
import me.leaver.rexpocket.core.ActivityItem
import me.leaver.rexpocket.core.ActivitySection
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.activityAge
import me.leaver.rexpocket.core.activitySections

// The eye's view: each connected host's tabs with a program that reported its status, grouped by
// that status in Rex's priority order (Waiting, Failed, Done, Running), as Rex's Activity sidebar
// groups them. See docs/design/rex-parity.md for what comes from Rex and what does not.

/** The activity of [state], narrowed by [query] on a tab's title, its session and its message. */
@Composable
internal fun activityShown(state: PocketState, query: String): List<ActivitySection> {
    val sections = activitySections(state)
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

/** The caption over a group, as Rex writes it: Waiting, Failed, Done, Running. */
@Composable
internal fun activityGroupName(group: ActivityGroup): String = stringResource(
    when (group) {
        ActivityGroup.Waiting -> R.string.activity_waiting
        ActivityGroup.Failed -> R.string.activity_failed
        ActivityGroup.Done -> R.string.activity_done
        ActivityGroup.Running -> R.string.activity_running
    },
)

/** The dot on a row's tile: red for failed, blue for done, as Rex's sidebar; amber waiting, green running, as the app's tabs. */
internal fun activityTint(group: ActivityGroup): Color = when (group) {
    ActivityGroup.Waiting -> Colors.amber
    ActivityGroup.Failed -> Colors.danger
    ActivityGroup.Done -> Colors.accent
    ActivityGroup.Running -> Colors.ok
}

/** Test tag of one group's caption, by group name. */
internal fun activityGroupTag(group: ActivityGroup) = "activity-group-${group.name}"

/** Test tag of one activity row, by tab id. */
internal fun activityRowTag(windowId: String) = "activity-row-$windowId"

/** The rows of the activity view, a caption and a card per group. */
internal fun LazyListScope.activityItems(
    sections: List<ActivitySection>,
    state: PocketState,
    /** Each host's rows have their own keys: two hosts list the same groups. */
    keyPrefix: String = "",
    onOpen: (ActivityItem) -> Unit,
) {
    sections.forEachIndexed { index, section ->
        item(key = "activity-$keyPrefix-${section.group.name}") {
            Text(
                activityGroupName(section.group),
                color = Colors.muted,
                fontSize = TextStyles.subhead,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, top = if (index == 0) 8.dp else 20.dp, bottom = 8.dp)
                    .testTag(activityGroupTag(section.group))
                    .semantics { heading() },
            )
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(Colors.surface)) {
                section.items.forEachIndexed { i, item ->
                    if (i > 0) Box(Modifier.padding(start = 64.dp).fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(Colors.line))
                    ActivityRow(item, state, onOpen = { onOpen(item) })
                }
            }
        }
    }
}

/**
 * One tab: the tile of the program that reported, dotted with its state's colour; the tab's title;
 * its session and how long ago the phone saw the state begin; and the program's message, if it
 * gave one, in a box under them - Rex shows a failure's message that way.
 */
@Composable
private fun ActivityRow(item: ActivityItem, state: PocketState, onOpen: () -> Unit) {
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
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onOpen)
            .testTag(activityRowTag(item.window.id))
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 2.dp)) {
            ProgramTile(program, size = 36.dp)
            Dot(activityTint(item.group), 8.dp, Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp), ring = Colors.surface)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Colors.ink, fontSize = TextStyles.body, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val detail = listOfNotNull(item.sessionLabel, progress?.let { "$it%" }).joinToString(" · ")
                Text(detail, color = Colors.muted, fontSize = TextStyles.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (age != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(age, color = Colors.muted, fontSize = TextStyles.label, maxLines = 1)
                }
            }
            val message = item.record.msg?.takeIf { it.isNotBlank() }
            if (message != null) {
                Text(
                    message,
                    color = Colors.muted,
                    fontSize = TextStyles.label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Colors.key)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** What the eye shows when there is no activity to list: why, in one line. */
@Composable
internal fun ActivityEmpty(live: Boolean, searching: Boolean) {
    Text(
        stringResource(
            when {
                !live -> R.string.activity_offline
                searching -> R.string.search_none
                else -> R.string.no_activity
            },
        ),
        color = Colors.muted,
        fontSize = TextStyles.subhead,
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 16.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
