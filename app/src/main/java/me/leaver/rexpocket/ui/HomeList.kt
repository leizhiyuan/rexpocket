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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
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
import me.leaver.rexpocket.core.PaneProgress
import me.leaver.rexpocket.core.tabProgress
import me.leaver.rexpocket.core.ConnectProgress
import me.leaver.rexpocket.core.ListedSession
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.ProgramRecord
import me.leaver.rexpocket.core.SessionView
import me.leaver.rexpocket.core.TabAttention
import me.leaver.rexpocket.core.TailnetPath
import me.leaver.rexpocket.core.TailnetStage
import me.leaver.rexpocket.core.Trouble
import me.leaver.rexpocket.core.WindowInfo
import me.leaver.rexpocket.core.tabAttention
import me.leaver.rexpocket.data.SavedHost
import me.leaver.rexpocket.data.HomeFolds
import me.leaver.rexpocket.core.Problem
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.LazyListScope

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

/** A pane's report was seen; a done or failed one that was is no news. */
internal typealias Seen = (blockId: String, record: ProgramRecord) -> Boolean

/** The most pressing state among [tabs] that is news, as the session's chip says it. */
internal fun sessionChip(tabs: List<WindowInfo>, state: PocketState, seen: Seen = { _, _ -> false }): ActivityGroup? =
    tabs.mapNotNull { tabAttention(it, state, seen)?.group }.minByOrNull { it.ordinal }

/**
 * How many tabs of a live host are in each state that is news, for its summary line, counted as
 * the home's filters count them: a tab by its most pressing state, and in running too when
 * anything in it works.
 */
internal fun hostCounts(state: PocketState, seen: Seen = { _, _ -> false }): Map<ActivityGroup, Int> {
    val counts = LinkedHashMap<ActivityGroup, Int>()
    for (window in state.views.values.flatMap { it.windows }) {
        val attention = tabAttention(window, state, seen) ?: continue
        if (attention.needsYou) counts[attention.group] = (counts[attention.group] ?: 0) + 1
        if (attention.isRunning) counts[ActivityGroup.Running] = (counts[ActivityGroup.Running] ?: 0) + 1
    }
    return counts
}

/**
 * The summary line of a connected host, its most pressing news first: "2 waiting · 1 failed ·
 * 4 sessions". While it is not live yet it says where its connection is instead.
 */
@Composable
internal fun hostSummary(state: PocketState, sessions: Int, seen: Seen = { _, _ -> false }): String {
    val parts = ArrayList<String>()
    if (state.phase == Phase.Live) {
        val counts = hostCounts(state, seen)
        for (group in ActivityGroup.entries) {
            val n = counts[group] ?: continue
            parts += stringResource(
                when (group) {
                    ActivityGroup.Waiting -> R.string.home_count_waiting
                    ActivityGroup.Failed -> R.string.home_count_failed
                    ActivityGroup.Done -> R.string.home_count_done
                    ActivityGroup.Running -> R.string.home_count_running
                    // Never counted: idle is the Activity view's only.
                    ActivityGroup.Idle -> continue
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

/** Where a host's connection is, as its row tells it and its action slot answers it. */
internal enum class HostCondition { NotConnected, Connecting, Live, Failed }

internal val HostRef.condition: HostCondition
    get() {
        val st = live?.state ?: return HostCondition.NotConnected
        return when {
            st.phase == Phase.Live -> HostCondition.Live
            st.phase == Phase.Failed || st.problem != null -> HostCondition.Failed
            else -> HostCondition.Connecting
        }
    }

/** The button that fixes a problem the phone can fix: sign in, the device list, join, the tailnet's settings. */
@Composable
internal fun problemFix(problem: Problem, authUrl: String, onSignIn: (String) -> Unit, onDevices: () -> Unit, onTailnet: () -> Unit): Pair<String, () -> Unit>? =
    when (problem.trouble) {
        Trouble.TailnetNeedsLogin ->
            if (authUrl.isNotBlank()) stringResource(R.string.action_sign_in) to { onSignIn(authUrl) }
            else stringResource(R.string.action_devices) to onDevices
        Trouble.TailnetPeerUnknown, Trouble.TailnetPeerOffline -> stringResource(R.string.action_devices) to onDevices
        Trouble.TailnetNotJoined -> stringResource(R.string.action_join) to onTailnet
        Trouble.Tailnet -> stringResource(R.string.action_tailnet_settings) to onTailnet
        else -> null
    }

/** A problem whose fix is the tailnet's, not Rex's: its row offers the fix where Retry would be. */
internal fun Problem.needsTailnet(): Boolean = trouble == Trouble.TailnetNeedsLogin || trouble == Trouble.TailnetNotJoined

/** A small text button in a row: Connect, Cancel, Retry, Open, a fix. At least 48 dp to touch. */
@Composable
internal fun RowButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        Text(label, color = if (enabled) Colors.accent else Colors.muted, fontWeight = FontWeight.SemiBold, fontSize = TextStyles.label)
    }
}

/**
 * One host: its mark, its name with the connection's dot, its summary - what its programs need, and
 * its sessions - and the button its state calls for: Connect, Cancel, Retry, or the tailnet's fix.
 * A tap folds it in any state, and never connects; a long press opens its sheet, as ⋮ does, and
 * TalkBack offers the sheet's items as its actions. A heading, so TalkBack can jump from host to host.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun HostHeader(
    host: SavedHost,
    live: Boolean,
    summary: String,
    phase: Phase?,
    expanded: Boolean,
    /** False where folds do not apply - the search, a filter - so the row shows no chevron and does not fold. */
    foldable: Boolean = true,
    onToggle: () -> Unit,
    /** The action slot's button: Connect, Cancel, Retry or a fix; null when the host needs none. */
    action: Pair<String, () -> Unit>? = null,
    onMenu: () -> Unit,
    actions: List<CustomAccessibilityAction> = emptyList(),
    /** The host's notifications are off: a crossed-out bell beside its name, which TalkBack reads. */
    silenced: Boolean = false,
) {
    val folded = stringResource(R.string.state_folded)
    val silencedWords = stringResource(R.string.alerts_muted_host)
    val clickLabel = stringResource(if (expanded) R.string.home_action_collapse else R.string.home_action_expand)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(Radius.card))
            .combinedClickable(
                enabled = foldable,
                onClickLabel = clickLabel,
                onLongClickLabel = stringResource(R.string.action_more),
                onLongClick = onMenu,
                onClick = onToggle,
            )
            .semantics {
                heading()
                if (foldable && !expanded) stateDescription = folded
                if (actions.isNotEmpty()) customActions = actions
            }
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
                if (silenced) {
                    Spacer(Modifier.width(6.dp))
                    Glyph(R.drawable.ic_notifications_off, Colors.muted, Modifier.semantics { contentDescription = silencedWords }, size = 16.dp)
                }
            }
            // Wrapping rather than cut: with large text the counts take a second line.
            Text(summary, color = Colors.muted, fontSize = TextStyles.label)
            // Under the line rather than beside it, so the line keeps its width with large text.
            if (action != null) RowButton(action.first, action.second, Modifier.offset(x = (-12).dp))
        }
        RoundButton(R.drawable.ic_more, stringResource(R.string.manage_actions_for, host.label), onMenu, tint = Colors.muted, size = 48.dp)
        if (foldable) FoldChevron(expanded)
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
 * The lines under a connected host: the step its connection is on while it connects; why it is not
 * live, with the fix as a button of its own (sign in, the device list, join) unless the row's action
 * slot has it; and how the tailnet carries it once it is. Nothing here acts on a plain tap: Retry is
 * the row's button, and measuring the path again is in the tailnet's sheet.
 */
@Composable
internal fun HostStatus(
    state: PocketState,
    problem: Problem?,
    path: TailnetPath?,
    network: String,
    progress: ConnectProgress?,
    stage: TailnetStage,
    /** The fix, when the action slot does not already offer it. */
    fix: Pair<String, () -> Unit>?,
) {
    if (problem == null && progress != null && state.phase != Phase.Live) {
        ConnectProgressLine(progress, stage, network, Modifier.padding(start = KEY_TEXT, end = 12.dp))
    }
    if (problem != null) {
        Row(Modifier.fillMaxWidth().padding(start = KEY_TEXT, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(problemText(problem, state.endpointLabel, network), color = Colors.danger, fontSize = TextStyles.label, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
            if (fix != null) RowButton(fix.first, fix.second)
        }
    }
    if (path != null) {
        val (text, color) = pathText(path, network)
        Text(text, color = color, fontSize = TextStyles.label, modifier = Modifier.padding(start = KEY_TEXT, end = 12.dp, top = 2.dp, bottom = 6.dp))
    }
}

/**
 * A line under a host or a session that says what is not there yet and offers the way to it:
 * "No sessions · New session", "Tabs load when opened · Open", "Connect mac to see tabs · Connect".
 */
@Composable
internal fun NoteRow(text: String, action: Pair<String, () -> Unit>? = null, start: Dp = KEY_MARK, card: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (action != null) 56.dp else 40.dp)
            .then(if (card) Modifier.clip(RoundedCornerShape(Radius.card)).background(Colors.surface) else Modifier)
            .padding(start = start, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Colors.muted, fontSize = if (card) TextStyles.subhead else TextStyles.label, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        if (action != null) RowButton(action.first, action.second)
    }
}

/**
 * A session under its host, as the reference writes it: a quiet group label, a chip of its most
 * pressing state - waiting, failed, done or running, in that state's colour and word - its tab
 * count, its ⋮ and the fold. A tap folds it, whether its tabs are loaded or not; a long press opens
 * its sheet, and TalkBack offers the sheet's items as its actions.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
internal fun SessionHeader(
    row: HomeSession,
    opening: Boolean,
    expanded: Boolean,
    /** False in the search, where every fold is open and the row shows no chevron. */
    foldable: Boolean = true,
    /** Greyed: a session from the list cached last time, while its host is not live. */
    cached: Boolean = false,
    onToggle: () -> Unit,
    onManage: () -> Unit,
    actions: List<CustomAccessibilityAction> = emptyList(),
) {
    val tabs = row.view?.windows?.size ?: 0
    val detail = when {
        opening -> stringResource(R.string.session_opening)
        row.view != null -> pluralStringResource(R.plurals.session_window_count, tabs, tabs)
        else -> null
    }
    val folded = stringResource(R.string.state_folded)
    val manageLabel = stringResource(R.string.manage_actions_for, row.session.label)
    val clickLabel = stringResource(if (expanded) R.string.home_action_collapse else R.string.home_action_expand)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(Radius.chip))
            .combinedClickable(
                enabled = foldable,
                onClickLabel = clickLabel,
                onLongClickLabel = manageLabel,
                onLongClick = onManage,
                onClick = onToggle,
            )
            // Said as the host's header says it, so a folded session is not only a turned chevron.
            // A fold shows before the session's tabs are known too - from the cached list while the
            // host connects, as after a launch or an update - rather than reading as open until then.
            .semantics {
                if (foldable && !expanded) stateDescription = folded
                if (actions.isNotEmpty()) customActions = actions
            }
            .padding(start = KEY_MARK),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Wrapping, so a long name and its chip keep their words with large text.
        FlowRow(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.Center) {
            Text(
                row.session.label,
                color = if (cached) Colors.muted.copy(alpha = 0.7f) else Colors.muted,
                fontSize = TextStyles.subhead,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(end = 8.dp).align(Alignment.CenterVertically),
            )
            row.chip?.let { StateChip(it, Modifier.padding(end = 8.dp).align(Alignment.CenterVertically)) }
            if (detail != null) {
                Text(detail, color = if (opening) Colors.accent else Colors.muted, fontSize = TextStyles.label, modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
        RoundButton(R.drawable.ic_more, manageLabel, onManage, tint = Colors.muted, size = 48.dp, enabled = !opening)
        if (foldable) FoldChevron(expanded)
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

/**
 * The tabs of one session, on one card, hairlines between rows from the title's keyline, and a
 * "+ New tab" row at the end of the card while the host is live.
 */
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
    /** Opens a tab's sheet: a long press, and its ⋮. */
    onManage: (String) -> Unit,
    /** Each tab's TalkBack actions: its sheet's enabled items. */
    actionsOf: @Composable (windowId: String) -> List<CustomAccessibilityAction>,
    /** Adds a tab; null where the session cannot take one now. */
    onNewTab: (() -> Unit)?,
    seen: Seen = { _, _ -> false },
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(Colors.surface)) {
        row.tabs.forEachIndexed { index, window ->
            if (index > 0) {
                Box(Modifier.padding(start = KEY_TEXT).fillMaxWidth().height(1.dp).background(Colors.line))
            }
            // What is news; failing that, a done or failed report that was seen, said quietly.
            val fresh = tabAttention(window, state, seen)
            val attention = fresh ?: tabAttention(window, state)
            val old = fresh == null && attention != null
            // A pane other than the one the tab shows is named, so the tap that opens it is there.
            val hidden = attention?.takeIf { !old && it.group != ActivityGroup.Running && it.block.id != window.focusedBlock()?.id }
            TabRow(
                seen = old,
                title = row.view?.let { tabTitle(it, window, state) } ?: window.label,
                program = tabProgram(window, state),
                panes = window.blocks.size,
                attention = attention,
                hiddenPane = hidden?.let { paneTitle(it.block, it.record, state.programs[it.block.id]) },
                opening = opening,
                open = open && window.id == state.view?.activeWindowId,
                onOpen = { onOpen(window.id) },
                onOpenPane = hidden?.let { h -> { onOpenPane(window.id, h.block.id) } },
                onManage = { onManage(window.id) },
                actions = actionsOf(window.id),
                progress = tabProgress(window, state.progress),
            )
        }
        if (onNewTab != null) {
            if (row.tabs.isNotEmpty()) Box(Modifier.padding(start = KEY_TEXT).fillMaxWidth().height(1.dp).background(Colors.line))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !opening, role = Role.Button, onClick = onNewTab)
                    .padding(start = KEY_MARK, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(TILE), contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_add, Colors.accent, size = 20.dp) }
                Spacer(Modifier.width(KEY_TEXT - KEY_MARK - TILE))
                Text(stringResource(R.string.menu_new_tab), color = Colors.accent, fontSize = TextStyles.label, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * What a tab is doing, in words: the state of its most pressing pane in that state's colour -
 * "waiting for approval", "failed", "done", "running" - then its panes. A tab no program reported
 * on says only how many panes it has.
 */
@Composable
internal fun tabDetail(panes: Int, attention: TabAttention?, seen: Boolean = false): AnnotatedString {
    val count = pluralStringResource(R.plurals.pane_count, panes, panes)
    val word = attention?.let { statusWord(it.record) }
    val also = if (attention != null && !seen) alsoWords(attention, attention.group) else emptyList()
    return buildAnnotatedString {
        if (word != null) {
            // A done or failed report that was seen keeps its word, quietly.
            val color = if (seen) Colors.muted else activityTint(attention.group)
            withStyle(SpanStyle(color = color, fontWeight = if (seen) FontWeight.Normal else FontWeight.Medium)) { append(word) }
            append(" · ")
            // What else the tab has: "waiting for approval · running", "running · 1 done".
            for (more in also) {
                append(more)
                append(" · ")
            }
        }
        append(count)
    }
}

/** The first line of what a program said, for the line under a waiting or failed tab. */
internal fun firstLine(text: String?): String? = text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()

/**
 * One tab: the tile of what runs in it - an agent's mark, or the terminal for a shell - dotted in
 * the colour of its state (amber waiting, red failed, blue done, green running), its name, what it
 * is doing in words, and for a waiting or failed tab the first line of what the program said. A tap
 * opens it; a long press and its ⋮ open its sheet. A tab whose hidden pane is the one that needs the
 * person names that pane on a line of its own, which opens that pane.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun TabRow(
    /** True for a done or failed tab whose report was seen: no dot, its word quiet. */
    seen: Boolean = false,
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
    onManage: () -> Unit,
    actions: List<CustomAccessibilityAction> = emptyList(),
    /** The tab's progress bar, under what it is doing. */
    progress: PaneProgress? = null,
) {
    val selected = stringResource(R.string.state_selected)
    val manageLabel = stringResource(R.string.manage_actions_for, title)
    val message = attention?.takeIf { !seen && (it.group == ActivityGroup.Waiting || it.group == ActivityGroup.Failed) }?.record?.msg?.let(::firstLine)
    Column(Modifier.fillMaxWidth().background(if (open) Colors.keyOn else Color.Transparent)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .combinedClickable(
                    enabled = !opening,
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.home_action_open),
                    onLongClickLabel = manageLabel,
                    onLongClick = onManage,
                    onClick = onOpen,
                )
                .semantics {
                    if (open) stateDescription = selected
                    if (actions.isNotEmpty()) customActions = actions
                }
                .padding(start = KEY_MARK, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                ProgramTile(program, size = TILE)
                if (attention != null && !seen) {
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
                Text(tabDetail(panes, attention, seen), color = Colors.muted, fontSize = TextStyles.label)
                if (progress != null) ProgressLine(progress, Modifier.padding(top = 6.dp, end = 8.dp))
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
            RoundButton(R.drawable.ic_more, manageLabel, onManage, tint = Colors.muted, size = 48.dp, enabled = !opening)
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
internal fun NotFound() {
    Text(
        stringResource(R.string.search_none),
        color = Colors.muted,
        fontSize = TextStyles.subhead,
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        textAlign = TextAlign.Center,
    )
}

/** Test tag of the home's search field, in its bar at the bottom. */
internal const val HOME_SEARCH_TAG = "home-search"

/**
 * The bar along the bottom, in reach of a thumb and just above the keyboard while it is up:
 * settings, the search field, and + for a new session.
 */
@Composable
internal fun HomeBottomBar(
    query: String,
    onQuery: (String) -> Unit,
    onFocus: (Boolean) -> Unit,
    onSettings: () -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().background(Colors.bg).navigationBarsPadding().padding(horizontal = PAGE - 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(R.drawable.ic_settings, stringResource(R.string.settings), onSettings, size = 48.dp, iconSize = 24.dp)
        Row(
            Modifier.weight(1f).padding(horizontal = 4.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(50)).background(Colors.surface)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(R.drawable.ic_search, Colors.muted, size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f).padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    // One word, so it fits beside the buttons at any text size.
                    Text(stringResource(R.string.action_search), color = Colors.muted, fontSize = TextStyles.body, maxLines = 1)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(color = Colors.ink, fontSize = TextStyles.body),
                    cursorBrush = SolidColor(Colors.accent),
                    modifier = Modifier.fillMaxWidth().testTag(HOME_SEARCH_TAG).onFocusChanged { onFocus(it.isFocused) },
                )
            }
            if (query.isNotEmpty()) {
                RoundButton(R.drawable.ic_close, stringResource(R.string.action_clear), { onQuery("") }, tint = Colors.muted, size = 40.dp)
            }
        }
        RoundButton(R.drawable.ic_add, stringResource(R.string.session_new), onNew, tint = Colors.accent, plate = Colors.keyOn, size = 48.dp, iconSize = 24.dp)
    }
}

/**
 * The tailnet, quiet: a chip beside the filters while the phone is on it, which opens its details.
 * Anything on its way or that needs the person - starting, signing in, a failure, not joined - is
 * the full card in the list instead.
 */
@Composable
internal fun TailnetChip(network: String, said: String, healthy: Boolean, on: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = said }
            .testTag(TAILNET_CARD_TAG).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Colors.surface).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Grey while disconnected, by hand or until a host needs it: quiet, not wrong.
            Dot(if (!on) Colors.muted else if (healthy) Colors.ok else Colors.amber, 8.dp)
            Spacer(Modifier.width(6.dp))
            Text(network, color = Colors.ink, fontSize = TextStyles.label, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/** The hosts a new session can go on, for the picker + opens when it is not plain which one. */
@Composable
internal fun newSessionPicker(hosts: List<SavedHost>, lives: Map<String, HostLive>, onPick: (SavedHost) -> Unit): List<ActionGroup> {
    val connectFirst = stringResource(R.string.home_connect_and_create)
    return listOf(
        ActionGroup(hosts.map { host -> RowAction(host.label, R.drawable.ic_host, detail = connectFirst.takeIf { host.id !in lives }) { onPick(host) } }),
    )
}

/** What the host tree needs to draw itself: the hosts, their connections, the folds, the search. */
internal class TreeEnv(
    val hosts: List<SavedHost>,
    val lives: Map<String, HostLive>,
    val folds: HomeFolds,
    /** Sessions of hosts that are not live opened on this screen: their tabs are not known, so they start folded. */
    val openCached: Set<String>,
    /** The search, lower-cased and trimmed; empty when there is none. */
    val query: String,
    /** True while the search is open: every fold is open, and no row folds. */
    val searching: Boolean,
    val opening: Opening?,
    /** The host whose pane stands beside the list, when one does: its tab carries a mark. */
    val detailHostId: String?,
    val cachedSessions: (String) -> List<ListedSession>,
    val ops: HomeOps,
    val sheets: HomeSheets,
    val network: String,
    val stage: TailnetStage,
    val authUrl: String,
    val onSignIn: (String) -> Unit,
    val onDevices: () -> Unit,
    val onTailnet: () -> Unit,
    /** Why a host's tailnet keeps it off: disconnected by hand, or on another account. */
    val parked: (SavedHost) -> HostParked? = { null },
    /** Whether a host's notifications are off (its page's Notifications from this host): its row says so. */
    val silenced: (hostId: String) -> Boolean = { false },
) {
    fun ref(host: SavedHost) = HostRef(host, lives[host.id])

    /** Whether a session's tabs show: its fold on a live host, this screen's choice on one that is not. */
    fun sessionOpen(ref: HostRef, sessionId: String): Boolean {
        if (searching) return true
        val key = HomeFolds.sessionKey(ref.host.id, sessionId)
        return if (ref.isLive || ref.state.views.containsKey(sessionId)) key !in folds.sessions else key in openCached
    }
}

/** Each host's rows, as the search narrows them, by host id; null for a host the search leaves nothing of. */
@Composable
internal fun TreeEnv.rows(): Map<String, List<HomeSession>?> = hosts.associate { it.id to rowsOf(ref(it)) }

@Composable
private fun TreeEnv.rowsOf(ref: HostRef): List<HomeSession>? {
    val state = ref.state
    val hostMatch = query.isEmpty() || ref.host.label.lowercase().contains(query) || ref.host.host.contains(query)
    val sessions = ref.sessionsShown(cachedSessions(ref.host.id))
    val rows = sessions.mapNotNull { session ->
        val view = state.views[session.id]
        val tabs = view?.windows.orEmpty()
        val labelMatch = hostMatch || session.label.lowercase().contains(query)
        val shownTabs = if (labelMatch || view == null) tabs else tabs.filter { tabTitle(view, it, state).lowercase().contains(query) }
        if (!labelMatch && shownTabs.isEmpty()) return@mapNotNull null
        HomeSession(session, view, shownTabs, sessionChip(tabs, state) { block, record -> ops.seen(ref.host.id, block, record) })
    }
    if (rows.isEmpty() && !hostMatch) return null
    return rows
}

/**
 * The hosts as a tree: each host's header, and while it is open its sessions, and under each open
 * session its tabs. A host's tap folds it in every state; connecting is its button. A host that is
 * not live shows the sessions it had last time, with one note saying so, rather than one per row.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun LazyListScope.homeTree(env: TreeEnv, rowsByHost: Map<String, List<HomeSession>?>) {
    var shown = 0
    for (host in env.hosts) {
        val ref = env.ref(host)
        val rows = rowsByHost[host.id] ?: continue
        val first = shown == 0
        shown++
        val state = ref.state
        val expanded = env.searching || env.folds.hostOpen(host.id, ref.connected)
        if (!first) item(key = "gap-${host.id}") { Spacer(Modifier.height(12.dp)) }
        // A host's header stays at the top while its sessions scroll under it: with many hosts, which
        // one a row belongs to is always in sight.
        stickyHeader(key = "host-${host.id}") {
            Column(Modifier.fillMaxWidth().background(Colors.bg)) { HostItem(env, ref, rows, expanded) }
        }
        if (!expanded) continue
        val condition = ref.condition
        val note = when {
            rows.isEmpty() && condition == HostCondition.Live && env.query.isEmpty() -> "none"
            rows.isEmpty() -> null
            condition == HostCondition.Connecting -> "loading"
            condition == HostCondition.NotConnected || (condition == HostCondition.Failed && state.views.isEmpty()) -> "cached"
            else -> null
        }
        if (note != null) {
            item(key = "note-${host.id}") {
                when (note) {
                    "none" -> NoteRow(
                        stringResource(R.string.home_no_sessions),
                        stringResource(R.string.session_new) to { env.ops.newSession(host) },
                    )
                    "loading" -> NoteRow(stringResource(R.string.home_loading), start = KEY_TEXT, card = false)
                    else -> NoteRow(stringResource(R.string.home_cached_note), start = KEY_TEXT, card = false)
                }
            }
        }
        for (row in rows) {
            val sessionExpanded = env.sessionOpen(ref, row.session.id)
            val openingThis = env.opening == Opening.Existing(row.session.id, host.id)
            item(key = "session-${host.id}-${row.session.id}") {
                val groups = sessionActions(ref, row.session, sessionExpanded, env.ops, env.sheets)
                SessionHeader(
                    row = row,
                    opening = openingThis,
                    expanded = sessionExpanded,
                    foldable = !env.searching,
                    cached = !ref.isLive && row.view == null,
                    onToggle = { env.ops.foldSession(host.id, row.session.id, sessionExpanded) },
                    onManage = { env.sheets.sheet = ManageTarget.Session(host.id, row.session.id) },
                    actions = groups.talkBack(),
                )
            }
            if (!sessionExpanded) continue
            item(key = "tabs-${host.id}-${row.session.id}") {
                SessionBody(env, ref, row, openingThis)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
    if (shown == 0) item(key = "not-found") { NotFound() }
}

/** A host's header and the lines under it, with the button its state calls for in the action slot. */
@Composable
internal fun HostItem(env: TreeEnv, ref: HostRef, rows: List<HomeSession>, expanded: Boolean, foldable: Boolean = !env.searching) {
    val host = ref.host
    val state = ref.state
    val live = ref.live
    val problem = rememberSettledProblem(state.phase, state.problem.takeIf { live != null && state.phase != Phase.Live })
    val fix = problem?.let { problemFix(it, env.authUrl, env.onSignIn, env.onDevices, env.onTailnet) }
    val slotFix = fix?.takeIf { problem.needsTailnet() }
    val action: Pair<String, () -> Unit>? = when (ref.condition) {
        HostCondition.NotConnected -> stringResource(R.string.action_connect) to { env.ops.connect(host) }
        HostCondition.Connecting -> stringResource(R.string.action_cancel) to { env.ops.cancel(host.id) }
        HostCondition.Failed -> slotFix ?: (stringResource(R.string.action_retry) to { env.ops.retry(host.id) })
        HostCondition.Live -> null
    }
    val sessions = ref.sessionsShown(env.cachedSessions(host.id))
    val summary = when {
        ref.isLive -> hostSummary(state, state.sessions.size) { block, record -> env.ops.seen(host.id, block, record) }
        problem != null && problem.needsTailnet() -> problemText(problem, state.endpointLabel, env.network)
        live != null -> hostSummary(state, sessions.size)
        else -> (env.parked(host)?.let { parkedText(it) } ?: stringResource(R.string.host_not_connected)) +
            if (sessions.isNotEmpty()) " · " + pluralStringResource(R.plurals.session_count, sessions.size, sessions.size) else ""
    }
    val groups = hostActions(ref, expanded, sessions, env.folds, env.ops, env.hosts.indexOfFirst { it.id == host.id }, env.hosts.size, env.sheets)
    // Connected, or trying, at one of the host's other addresses: which one.
    val other = otherAddressInUse(host, state).takeIf { live != null }
    HostHeader(
        host = host,
        live = live != null,
        summary = if (other == null) summary else summary + " · " + stringResource(if (ref.isLive) R.string.host_via_other else R.string.host_trying_other, other),
        phase = live?.state?.phase,
        expanded = expanded,
        foldable = foldable,
        onToggle = { env.ops.foldHost(host.id, !expanded) },
        action = action,
        onMenu = { env.sheets.sheet = ManageTarget.Host(host.id) },
        actions = groups.talkBack(),
        silenced = env.silenced(host.id),
    )
    if (live != null) {
        HostStatus(
            state = state,
            problem = problem?.takeIf { !it.needsTailnet() },
            path = live.path,
            network = env.network,
            progress = live.progress,
            stage = env.stage,
            fix = fix.takeIf { slotFix == null },
        )
    }
}

/**
 * What an open session shows: its tabs and "+ New tab" once Rex sent them; "Tabs load when opened"
 * with Open on a live host that has not yet; "Connect mac to see tabs" with Connect on one that is
 * not connected.
 */
@Composable
private fun SessionBody(env: TreeEnv, ref: HostRef, row: HomeSession, opening: Boolean) {
    val host = ref.host
    val state = ref.state
    val sid = row.session.id
    when {
        opening -> NoteRow(stringResource(R.string.session_opening))
        row.view != null -> {
            if (row.tabs.isEmpty() && env.searching) return
            TabCard(
                row = row,
                state = state,
                opening = opening,
                open = env.detailHostId == host.id && state.activeSessionId == sid,
                onOpen = { windowId -> env.ops.openTab(host, sid, windowId) },
                onOpenPane = { windowId, blockId -> env.ops.openPane(host, sid, windowId, blockId) },
                onManage = { windowId -> env.sheets.sheet = ManageTarget.Tab(host.id, sid, windowId) },
                actionsOf = { windowId -> tabActions(ref, sid, windowId, env.ops, env.sheets).talkBack() },
                onNewTab = if (manageable(ref.connected, state, row)) {
                    { env.ops.newTab(host, sid) }
                } else {
                    null
                },
                seen = { block, record -> env.ops.seen(host.id, block, record) },
            )
        }
        ref.isLive -> NoteRow(stringResource(R.string.home_tabs_load), stringResource(R.string.home_action_open) to { env.ops.openSession(host, sid) })
        ref.connected -> NoteRow(stringResource(R.string.home_loading))
        else -> NoteRow(stringResource(R.string.home_connect_to_see, host.label), stringResource(R.string.action_connect) to { env.ops.connect(host) })
    }
}

/** The address [state] uses when it is not [host]'s own, as the person reads it; null on its own address. */
internal fun otherAddressInUse(host: SavedHost, state: PocketState): String? =
    state.endpointLabel.takeIf { it.isNotBlank() && host.fallbacks.isNotEmpty() && it != host.endpoint().label() }
