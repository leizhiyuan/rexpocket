package me.leaver.rexpocket.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.ActivityGroup
import me.leaver.rexpocket.core.ConnectProgress
import me.leaver.rexpocket.core.ListedSession
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.SessionView
import me.leaver.rexpocket.core.TabAttention
import me.leaver.rexpocket.core.TailnetPath
import me.leaver.rexpocket.core.TailnetStage
import me.leaver.rexpocket.core.Trouble
import me.leaver.rexpocket.core.WindowInfo
import me.leaver.rexpocket.core.tabAttention
import me.leaver.rexpocket.data.SavedHost

// The rows of the home list: the hosts, each with its sessions under it and their tabs under those.
// See docs/design/home.md for what each row says and does.
//
// Everything sits on two keylines, the way the reference lines up: [KEY_MARK] for the host's mark,
// a session's name and a tab's tile, [KEY_TEXT] for every title. Trailing controls sit in 48 dp
// slots flush with the right edge, so a menu, a fold and a row's chevron share one centre line.

/** The page margin: the cards and the floating controls stand this far from the screen's edge. */
internal val PAGE = 16.dp

/** The first keyline, inside the page margin: marks, session names, tiles. */
internal val KEY_MARK = 16.dp

/** The second keyline, inside the page margin: every title. */
internal val KEY_TEXT = 64.dp

internal val TILE = 36.dp

/** One session of a host, with the tabs the phone knows of; [view] is null until it is loaded. */
internal data class HomeSession(
    val session: ListedSession,
    val view: SessionView?,
    val tabs: List<WindowInfo>,
    /** The state of its most pressing tab, for its chip; null when no program in it reported one. */
    val chip: ActivityGroup?,
)

/** A session and its tabs can be managed from the home only on the live host, once Rex sent its view. */
internal fun manageable(live: Boolean, state: PocketState, row: HomeSession): Boolean =
    live && state.phase == Phase.Live && row.view != null

/** The most pressing state among [tabs], as the session's chip says it. */
internal fun sessionChip(tabs: List<WindowInfo>, state: PocketState): ActivityGroup? =
    tabs.mapNotNull { tabAttention(it, state)?.group }.minByOrNull { it.ordinal }

/** How many tabs of a live host are in each state, for its summary line. */
internal fun hostCounts(state: PocketState): Map<ActivityGroup, Int> =
    state.views.values.flatMap { it.windows }.mapNotNull { tabAttention(it, state)?.group }.groupingBy { it }.eachCount()

/**
 * The summary line of a connected host, its most pressing news first: "2 waiting · 1 failed ·
 * 4 sessions". While it is not live yet it says where its connection is instead.
 */
@Composable
internal fun hostSummary(state: PocketState, sessions: Int): String {
    val parts = ArrayList<String>()
    if (state.phase == Phase.Live) {
        val counts = hostCounts(state)
        for (group in ActivityGroup.entries) {
            val n = counts[group] ?: continue
            parts += stringResource(
                when (group) {
                    ActivityGroup.Waiting -> R.string.home_count_waiting
                    ActivityGroup.Failed -> R.string.home_count_failed
                    ActivityGroup.Done -> R.string.home_count_done
                    ActivityGroup.Running -> R.string.home_count_running
                },
                n,
            )
        }
    } else {
        parts += phaseText(state)
    }
    if (sessions > 0) parts += pluralStringResource(R.plurals.session_count, sessions, sessions)
    return parts.joinToString(" · ")
}

/** The fold's chevron: down while open, turned to point right while folded. */
@Composable
internal fun FoldChevron(expanded: Boolean) {
    val turn by animateFloatAsState(if (expanded) 0f else -90f, label = "fold")
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Glyph(R.drawable.ic_expand, Colors.muted, Modifier.rotate(turn))
    }
}

/**
 * One host: its mark, its name with the connection's dot, and its summary - what its programs need,
 * and its sessions. Tapping it folds its sessions, in any state; connecting is its own button.
 */
@Composable
internal fun HostHeader(
    host: SavedHost,
    live: Boolean,
    summary: String,
    phase: Phase?,
    expanded: Boolean,
    onToggle: () -> Unit,
    /** Connects a host that is not connected: the row's own button, never the row's tap. */
    onConnect: (() -> Unit)? = null,
    onMenu: () -> Unit,
) {
    val folded = stringResource(R.string.state_folded)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(Radius.card))
            .clickable(onClick = onToggle)
            .semantics { if (!expanded) stateDescription = folded }
            .padding(start = KEY_MARK),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(R.drawable.ic_host, if (live) Colors.ink else Colors.muted, size = 24.dp)
        Spacer(Modifier.width(KEY_TEXT - KEY_MARK - 24.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    host.label,
                    color = Colors.ink,
                    fontSize = TextStyles.title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (live) {
                    Spacer(Modifier.width(8.dp))
                    Dot(phaseTint(phase ?: Phase.Idle), 8.dp)
                }
            }
            // Wrapping rather than cut: with large text the counts take a second line.
            Text(summary, color = Colors.muted, fontSize = TextStyles.label)
            // Under the line rather than beside it, so the line keeps its width with large text.
            if (onConnect != null) {
                TextButton(
                    onClick = onConnect,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.action_connect), color = Colors.accent, fontWeight = FontWeight.SemiBold, fontSize = TextStyles.label)
                }
            }
        }
        RoundButton(R.drawable.ic_more, stringResource(R.string.action_more), onMenu, tint = Colors.muted, size = 48.dp)
    }
}

/** The caption over one host's activity, when the eye shows more than one host. */
@Composable
internal fun ActivityHostCaption(label: String) {
    Text(
        label,
        color = Colors.ink,
        fontSize = TextStyles.title,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp).semantics { heading() },
    )
}

/**
 * The line under a connected host: the step its connection is on while it connects, why it is not
 * live - with the fix next to Retry when there is one: sign in, the device list, join - and how the
 * tailnet carries it once it is.
 */
@Composable
internal fun HostStatus(
    state: PocketState,
    path: TailnetPath?,
    network: String,
    progress: ConnectProgress?,
    stage: TailnetStage,
    authUrl: String,
    onRetry: () -> Unit,
    onProbe: () -> Unit,
    onSignIn: (String) -> Unit,
    onDevices: () -> Unit,
    onTailnet: () -> Unit,
) {
    val problem = rememberSettledProblem(state.phase, state.problem.takeIf { state.phase != Phase.Live })
    if (problem == null && progress != null && state.phase != Phase.Live) {
        ConnectProgressLine(progress, stage, network, Modifier.padding(start = KEY_TEXT, end = 12.dp))
    }
    if (problem != null) {
        val fix: Pair<String, () -> Unit>? = when (problem.trouble) {
            Trouble.TailnetNeedsLogin ->
                if (authUrl.isNotBlank()) stringResource(R.string.action_sign_in) to { onSignIn(authUrl) }
                else stringResource(R.string.action_devices) to onDevices
            Trouble.TailnetPeerUnknown, Trouble.TailnetPeerOffline -> stringResource(R.string.action_devices) to onDevices
            Trouble.TailnetNotJoined -> stringResource(R.string.action_join) to onTailnet
            Trouble.Tailnet -> stringResource(R.string.action_tailnet_settings) to onTailnet
            else -> null
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(Radius.chip)).clickable(onClick = onRetry)
                .padding(start = KEY_TEXT, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(problemText(problem, state.endpointLabel, network), color = Colors.danger, fontSize = TextStyles.label, modifier = Modifier.weight(1f))
            if (fix != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    fix.first,
                    color = Colors.accent,
                    fontSize = TextStyles.label,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(Radius.chip)).clickable(role = Role.Button, onClick = fix.second)
                        .padding(horizontal = 6.dp, vertical = 10.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_retry_now), color = Colors.accent, fontSize = TextStyles.label, fontWeight = FontWeight.Medium)
        }
    }
    if (path != null) {
        val (text, color) = pathText(path, network)
        Text(
            text,
            color = color,
            fontSize = TextStyles.label,
            modifier = Modifier.padding(start = KEY_TEXT - 8.dp, bottom = 4.dp).clip(RoundedCornerShape(Radius.chip))
                .clickable(onClick = onProbe).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * A session under its host, as the reference writes it: a quiet group label, a chip of its most
 * pressing state - waiting, failed, done or running, in that state's colour and word - and its tab
 * count, the plus that makes a tab in it, and the fold.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
internal fun SessionHeader(
    row: HomeSession,
    live: Boolean,
    opening: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onNewTab: () -> Unit,
    /** Opens the session's sheet (rename, new tab, end); null where the session cannot be managed now. */
    onManage: (() -> Unit)? = null,
    /** Move up and Move down, for TalkBack's actions menu. */
    moves: List<CustomAccessibilityAction> = emptyList(),
) {
    val tabs = row.view?.windows?.size ?: 0
    val detail = when {
        opening -> stringResource(R.string.session_opening)
        row.view != null -> pluralStringResource(R.plurals.session_window_count, tabs, tabs)
        !live -> stringResource(R.string.session_cached)
        else -> null
    }
    val folded = stringResource(R.string.state_folded)
    val manageLabel = stringResource(R.string.manage_actions_for, row.session.label)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(Radius.chip))
            .combinedClickable(onLongClickLabel = onManage?.let { manageLabel }, onLongClick = onManage, onClick = onToggle)
            // Said as the host's header says it, so a folded session is not only a turned chevron.
            // A fold shows before the session's tabs are known too - from the cached list while the
            // host connects, as after a launch or an update - rather than reading as open until then.
            .semantics {
                if (!expanded) stateDescription = folded
                if (moves.isNotEmpty()) customActions = moves
            }
            .padding(start = KEY_MARK),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Wrapping, so a long name and its chip keep their words with large text.
        FlowRow(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.Center) {
            Text(
                row.session.label,
                color = Colors.muted,
                fontSize = TextStyles.subhead,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(end = 8.dp).align(Alignment.CenterVertically),
            )
            row.chip?.let { StateChip(it, Modifier.padding(end = 8.dp).align(Alignment.CenterVertically)) }
            if (detail != null) {
                Text(detail, color = if (opening) Colors.accent else Colors.muted, fontSize = TextStyles.label, modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
        if (onManage != null) {
            RoundButton(R.drawable.ic_more, manageLabel, onManage, tint = Colors.muted, size = 48.dp, enabled = !opening)
        }
        RoundButton(
            R.drawable.ic_add_circle,
            stringResource(R.string.menu_new_tab),
            onNewTab,
            tint = Colors.muted,
            size = 48.dp,
            enabled = !opening,
        )
        FoldChevron(expanded)
    }
}

/** A state as a word on a tinted pill: Waiting, Failed, Done, Running. The word carries it, not only the colour. */
@Composable
internal fun StateChip(group: ActivityGroup, modifier: Modifier = Modifier) {
    val tint = activityTint(group)
    Text(
        activityGroupName(group),
        color = tint,
        fontSize = TextStyles.label,
        fontWeight = FontWeight.Medium,
        modifier = modifier.clip(RoundedCornerShape(50)).background(tint.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** The tabs of one session, on one card, hairlines between rows from the title's keyline. */
@Composable
internal fun TabCard(
    row: HomeSession,
    state: PocketState,
    opening: Boolean,
    /** True for the session whose tab the pane beside the list is showing. */
    open: Boolean,
    onOpen: (String) -> Unit,
    /** Opens one pane of a tab: the line that names a pane needing the person, in a tab showing another. */
    onOpenPane: (windowId: String, blockId: String) -> Unit,
    /** Opens a tab's sheet (rename, close); null where the tabs cannot be managed now. */
    onManage: ((String) -> Unit)? = null,
    /** Moves a tab to a place in its session's order; null where the tabs cannot be managed now. */
    onMove: ((windowId: String, to: Int) -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(Colors.surface)) {
        row.tabs.forEachIndexed { index, window ->
            if (index > 0) {
                Box(Modifier.padding(start = KEY_TEXT).fillMaxWidth().height(1.dp).background(Colors.line))
            }
            val attention = tabAttention(window, state)
            // A pane other than the one the tab shows is named, so the tap that opens it is there.
            val hidden = attention?.takeIf { it.group != ActivityGroup.Running && it.block.id != window.focusedBlock()?.id }
            TabRow(
                title = row.view?.let { tabTitle(it, window, state) } ?: window.label,
                program = tabProgram(window, state),
                panes = window.blocks.size,
                attention = attention,
                hiddenPane = hidden?.let { paneTitle(it.block, it.record, state.programs[it.block.id]) },
                opening = opening,
                open = open && window.id == state.view?.activeWindowId,
                onOpen = { onOpen(window.id) },
                onOpenPane = hidden?.let { h -> { onOpenPane(window.id, h.block.id) } },
                onManage = onManage?.let { manage -> { manage(window.id) } },
                // Places in the session's whole order: a filter and the search may show only some tabs.
                moves = onMove?.let { move ->
                    val all = row.view?.windows.orEmpty()
                    moveActions(all.indexOfFirst { it.id == window.id }, all.size) { to -> move(window.id, to) }
                }.orEmpty(),
            )
        }
    }
}

/**
 * What a tab is doing, in words: the state of its most pressing pane in that state's colour -
 * "waiting for approval", "failed", "done", "running" - then its panes. A tab no program reported
 * on says only how many panes it has.
 */
@Composable
internal fun tabDetail(panes: Int, attention: TabAttention?): AnnotatedString {
    val count = pluralStringResource(R.plurals.pane_count, panes, panes)
    val word = attention?.let { statusWord(it.record) }
    return buildAnnotatedString {
        if (word != null) {
            withStyle(SpanStyle(color = activityTint(attention.group), fontWeight = FontWeight.Medium)) { append(word) }
            append(" · ")
        }
        append(count)
    }
}

/** The first line of what a program said, for the line under a waiting or failed tab. */
internal fun firstLine(text: String?): String? = text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()

/**
 * One tab: the tile of what runs in it - an agent's mark, or the terminal for a shell - dotted in
 * the colour of its state (amber waiting, red failed, blue done, green running), its name, what it
 * is doing in words, and for a waiting or failed tab the first line of what the program said. A tab
 * whose hidden pane is the one that needs the person names that pane on a line of its own, which
 * opens it.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun TabRow(
    title: String,
    program: String?,
    panes: Int,
    attention: TabAttention?,
    /** The name of the pane that needs the person, when the tab shows another one. */
    hiddenPane: String?,
    opening: Boolean,
    /** True for the tab the pane beside the list is showing. */
    open: Boolean,
    onOpen: () -> Unit,
    onOpenPane: (() -> Unit)? = null,
    onManage: (() -> Unit)? = null,
    moves: List<CustomAccessibilityAction> = emptyList(),
) {
    val selected = stringResource(R.string.state_selected)
    val manageLabel = stringResource(R.string.manage_actions_for, title)
    val message = attention?.takeIf { it.group == ActivityGroup.Waiting || it.group == ActivityGroup.Failed }?.record?.msg?.let(::firstLine)
    Column(Modifier.fillMaxWidth().background(if (open) Colors.keyOn else Color.Transparent)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .combinedClickable(
                    enabled = !opening,
                    role = Role.Button,
                    onLongClickLabel = onManage?.let { manageLabel },
                    onLongClick = onManage,
                    onClick = onOpen,
                )
                .semantics {
                    if (open) stateDescription = selected
                    if (moves.isNotEmpty()) customActions = moves
                }
                .padding(start = KEY_MARK, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                ProgramTile(program, size = TILE)
                if (attention != null) {
                    Dot(activityTint(attention.group), 8.dp, Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp), ring = Colors.surface)
                }
            }
            Spacer(Modifier.width(KEY_TEXT - KEY_MARK - TILE))
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(
                    title,
                    color = Colors.ink,
                    fontSize = TextStyles.body,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(tabDetail(panes, attention), color = Colors.muted, fontSize = TextStyles.label)
                if (message != null) {
                    Text(
                        message,
                        color = Colors.muted,
                        fontSize = TextStyles.label,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(Colors.key)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            if (onManage != null) {
                RoundButton(R.drawable.ic_more, manageLabel, onManage, tint = Colors.muted, size = 48.dp, enabled = !opening)
            } else {
                Glyph(R.drawable.ic_chevron_right, Colors.muted, size = 24.dp)
            }
        }
        if (hiddenPane != null && attention != null && onOpenPane != null) {
            val tint = activityTint(attention.group)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(enabled = !opening, role = Role.Button, onClick = onOpenPane)
                    .padding(start = KEY_TEXT, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(tint, 6.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.home_pane_line, hiddenPane, statusWord(attention.record).orEmpty()),
                    color = tint,
                    fontSize = TextStyles.label,
                    modifier = Modifier.weight(1f),
                )
                Glyph(R.drawable.ic_chevron_right, Colors.muted, size = 20.dp)
            }
        }
    }
}

/**
 * The end of the list: the way to add another computer, where the list runs out. It sits under the
 * hosts rather than in the settings, because adding one is a thing to do to this list.
 */
@Composable
internal fun AddHostRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(Radius.card)).background(Colors.surface)
            .clickable(role = Role.Button, onClick = onClick).padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(R.drawable.ic_add, Colors.accent, size = 24.dp)
        Spacer(Modifier.width(16.dp))
        Text(
            stringResource(R.string.action_add_host),
            color = Colors.accent,
            fontSize = TextStyles.subhead,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun EmptyTabs(text: String, canOpen: Boolean, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(Radius.card)).background(Colors.surface)
            .clickable(enabled = canOpen, onClick = onOpen).padding(start = KEY_MARK, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Colors.muted, fontSize = TextStyles.subhead, modifier = Modifier.weight(1f))
        if (canOpen) {
            Text(stringResource(R.string.session_tap_open), color = Colors.accent, fontSize = TextStyles.subhead, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
internal fun NotFound() {
    Text(
        stringResource(R.string.search_none),
        color = Colors.muted,
        fontSize = TextStyles.subhead,
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        textAlign = TextAlign.Center,
    )
}

/**
 * The controls of the reference, floating over the list: a pill with settings and the activity
 * filter at the bottom left, search on its own at the bottom right. Both are 56 dp, the height of
 * the platform's floating action button, and stand on the page margin.
 */
@Composable
internal fun FloatingControls(
    activeOnly: Boolean,
    onSettings: () -> Unit,
    onEye: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val on = stringResource(R.string.filter_on)
    val off = stringResource(R.string.filter_off)
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = PAGE, vertical = 16.dp)) {
        Row(
            Modifier.align(Alignment.BottomStart).floating(RoundedCornerShape(50)).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(R.drawable.ic_settings, stringResource(R.string.settings), onSettings, size = 48.dp, iconSize = 24.dp)
            Box {
                RoundButton(
                    if (activeOnly) R.drawable.ic_eye_filled else R.drawable.ic_eye,
                    stringResource(R.string.action_show_active),
                    onEye,
                    modifier = Modifier.semantics { stateDescription = if (activeOnly) on else off },
                    tint = if (activeOnly) Colors.accent else Colors.ink,
                    plate = if (activeOnly) Colors.keyOn else null,
                    size = 48.dp,
                    iconSize = 24.dp,
                )
            }
        }
        RoundButton(
            R.drawable.ic_search,
            stringResource(R.string.action_search),
            onSearch,
            modifier = Modifier.align(Alignment.BottomEnd).floating(),
            size = 56.dp,
            iconSize = 24.dp,
        )
    }
}

/** The platform's search bar: a 56 dp pill, back on the left, clear on the right. */
@Composable
internal fun SearchBar(
    query: String,
    focus: FocusRequester,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PAGE, vertical = 8.dp).height(56.dp)
            .clip(RoundedCornerShape(50)).background(Colors.surface).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(R.drawable.ic_back, stringResource(R.string.action_back), onClose, size = 48.dp)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(stringResource(R.string.search_hint), color = Colors.muted, fontSize = TextStyles.body, maxLines = 1)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = Colors.ink, fontSize = TextStyles.body),
                cursorBrush = SolidColor(Colors.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (query.isNotEmpty()) {
            RoundButton(R.drawable.ic_close, stringResource(R.string.action_clear), { onQuery("") }, tint = Colors.muted, size = 48.dp)
        }
    }
}
