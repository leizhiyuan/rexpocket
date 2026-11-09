package me.leaver.rexpocket.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import me.leaver.rexpocket.alerts.Alerts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.leaver.rexpocket.BuildConfig
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.BlockInfo
import me.leaver.rexpocket.core.HostAddress
import me.leaver.rexpocket.core.HostAddressParser
import me.leaver.rexpocket.core.ListedSession
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.ProgramRecord
import me.leaver.rexpocket.core.SessionView
import me.leaver.rexpocket.core.TailnetPath
import me.leaver.rexpocket.core.WindowInfo
import me.leaver.rexpocket.data.HostStore
import me.leaver.rexpocket.data.SavedHost
import me.leaver.rexpocket.data.TailscaleSettings
import me.leaver.rexpocket.net.TailscaleStatus

// The hosts, each with its sessions under it and their tabs under those: one list to search, one
// place to connect from.
//
// Everything sits on two keylines, the way the reference lines up: [KEY_MARK] for the host's mark,
// a session's name and a tab's tile, [KEY_TEXT] for every title. Trailing controls sit in 48 dp
// slots flush with the right edge, so a menu, a fold and a row's chevron share one centre line.

/** Where the credit for the interface goes; the README carries the long version. */
private const val SOURCE_URL = "https://github.com/leizhiyuan/rexpocket"

/** The page margin: the cards and the floating controls stand this far from the screen's edge. */
private val PAGE = 16.dp

/** The first keyline, inside the page margin: marks, session names, tiles. */
private val KEY_MARK = 16.dp

/** The second keyline, inside the page margin: every title. */
private val KEY_TEXT = 64.dp

private val TILE = 36.dp

/** One session of a host, with the tabs the phone knows of; [view] is null until it is loaded. */
private data class HomeSession(
    val session: ListedSession,
    val view: SessionView?,
    val tabs: List<WindowInfo>,
    val activity: Boolean,
)

/** A pane that is working or waiting is what the eye filters on. */
private fun PocketState.activityOf(block: BlockInfo): ProgramRecord? =
    blockStatus[block.id]?.takeIf { it.state == "working" || it.state == "blocked" }

@Composable
internal fun HomeScreen(
    hosts: List<SavedHost>,
    connected: SavedHost?,
    state: PocketState,
    tailscale: TailscaleSettings,
    tailscaleStatus: TailscaleStatus,
    tailnetError: String?,
    tailnetPath: TailnetPath?,
    opening: Opening?,
    cachedSessions: (String) -> List<ListedSession>,
    onConnect: (SavedHost) -> Unit,
    onOpenSession: (SavedHost, String) -> Unit,
    onOpenTab: (SavedHost, String, String) -> Unit,
    onNewSession: (SavedHost) -> Unit,
    onNewTab: (SavedHost, String) -> Unit,
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
    onProbePath: () -> Unit,
    onSave: (SavedHost) -> Unit,
    onDelete: (String) -> Unit,
    onTailscale: () -> Unit,
    alerts: Boolean,
    onAlerts: (Boolean) -> Unit,
    onFontSettings: () -> Unit,
) {
    val context = LocalContext.current
    // Android 13 asks for notifications at run time; alerts come on once the person allows them.
    // The request lives here, not in the settings sheet: the sheet is gone before the answer comes.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onAlerts(true) else toast(context, context.getString(R.string.alerts_blocked))
    }
    val toggleAlerts = {
        when {
            alerts -> onAlerts(false)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            !Alerts.allowed(context) -> toast(context, context.getString(R.string.alerts_blocked))
            else -> onAlerts(true)
        }
    }
    var editing by remember { mutableStateOf<SavedHost?>(null) }
    var creating by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var menuHost by remember { mutableStateOf<SavedHost?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var activeOnly by remember { mutableStateOf(false) }
    var collapsedHosts by remember { mutableStateOf(emptySet<String>()) }
    var collapsedSessions by remember { mutableStateOf(emptySet<String>()) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val closeSearch: () -> Unit = {
        searchOpen = false
        query = ""
        keyboard?.hide()
    }

    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    // Back closes the search first, as the platform's search does.
    BackHandler(enabled = searchOpen, onBack = closeSearch)

    if (hosts.isEmpty()) {
        EmptyHome(onAdd = { creating = true })
    } else {
        val q = query.trim().lowercase()
        val rows = hosts.mapNotNull { host ->
            val live = connected?.id == host.id
            val allSessions = if (live && state.phase == Phase.Live) state.sessions else cachedSessions(host.id)
            val hostMatch = q.isEmpty() || host.label.lowercase().contains(q) || host.host.contains(q)
            val sessions = allSessions.mapNotNull { session ->
                val view = if (live) state.views[session.id] else null
                val tabs = view?.windows.orEmpty()
                val sessionActivity = tabs.any { window -> window.blocks.any { state.activityOf(it) != null } }
                if (activeOnly && !sessionActivity) return@mapNotNull null
                val labelMatch = hostMatch || session.label.lowercase().contains(q)
                // The eye keeps the tabs that are doing something; search then narrows by name.
                val activeTabs = if (activeOnly) tabs.filter { window -> window.blocks.any { state.activityOf(it) != null } } else tabs
                val shownTabs = if (labelMatch || view == null) activeTabs else activeTabs.filter { tabTitle(view, it, state).lowercase().contains(q) }
                if (!labelMatch && shownTabs.isEmpty()) return@mapNotNull null
                HomeSession(session, view, shownTabs, sessionActivity)
            }
            if (sessions.isEmpty() && (activeOnly || !hostMatch)) return@mapNotNull null
            host to sessions
        }
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(Modifier.fillMaxSize().background(Colors.bg).statusBarsPadding().imePadding()) {
            if (searchOpen) {
                SearchBar(query = query, focus = focus, onQuery = { query = it }, onClose = closeSearch)
            }
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = PAGE,
                        end = PAGE,
                        top = if (searchOpen) 0.dp else 8.dp,
                        // Clear of the floating controls, so the last tab can be scrolled above them.
                        bottom = if (searchOpen) 16.dp else navBottom + 104.dp,
                    ),
                ) {
                    rows.forEachIndexed { index, (host, sessions) ->
                        val live = connected?.id == host.id
                        val hostExpanded = host.id !in collapsedHosts || searchOpen
                        item(key = "host-${host.id}") {
                            if (index > 0) Spacer(Modifier.height(12.dp))
                            HostHeader(
                                host = host,
                                live = live,
                                status = if (live) phaseText(state) else null,
                                phase = if (live) state.phase else null,
                                sessionCount = sessions.size,
                                expanded = hostExpanded,
                                onToggle = {
                                    if (live) {
                                        collapsedHosts = if (hostExpanded) collapsedHosts + host.id else collapsedHosts - host.id
                                    } else {
                                        onConnect(host)
                                    }
                                },
                                onMenu = { menuHost = host },
                            )
                            if (live) HostStatus(state, tailnetPath, onRetry, onProbePath)
                        }
                        if (hostExpanded) {
                            sessions.forEach { row ->
                                val sessionExpanded = row.session.id !in collapsedSessions || searchOpen
                                val openingThis = opening == Opening.Existing(row.session.id)
                                item(key = "session-${row.session.id}") {
                                    SessionHeader(
                                        row = row,
                                        live = live,
                                        opening = openingThis,
                                        expanded = sessionExpanded,
                                        onToggle = {
                                            if (row.view == null) {
                                                onOpenSession(host, row.session.id)
                                            } else {
                                                collapsedSessions = if (sessionExpanded) collapsedSessions + row.session.id else collapsedSessions - row.session.id
                                            }
                                        },
                                        onNewTab = { onNewTab(host, row.session.id) },
                                    )
                                }
                                if (sessionExpanded) {
                                    item(key = "tabs-${row.session.id}") {
                                        if (row.tabs.isEmpty()) {
                                            EmptyTabs(
                                                text = when {
                                                    openingThis -> stringResource(R.string.session_opening)
                                                    !live -> stringResource(R.string.session_cached)
                                                    row.view == null -> stringResource(R.string.session_open_to_load)
                                                    else -> stringResource(R.string.no_tabs)
                                                },
                                                canOpen = row.view == null && !openingThis,
                                                onOpen = { onOpenSession(host, row.session.id) },
                                            )
                                        } else {
                                            TabCard(
                                                row = row,
                                                state = state,
                                                opening = openingThis,
                                                onOpen = { windowId -> onOpenTab(host, row.session.id, windowId) },
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                    }
                                }
                            }
                        }
                    }
                    if (rows.isEmpty()) {
                        item { NotFound(activeOnly = activeOnly) }
                    }
                }
                if (!searchOpen) {
                    FloatingControls(
                        activeOnly = activeOnly,
                        onSettings = { settings = true },
                        onEye = { activeOnly = !activeOnly },
                        onSearch = { searchOpen = true },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }

    if (creating || editing != null) {
        HostDialog(
            initial = editing,
            tailscaleEnabled = tailscale.enabled,
            onDismiss = { creating = false; editing = null },
            onDelete = editing?.let { host -> { onDelete(host.id); creating = false; editing = null } },
            onSave = {
                onSave(it)
                creating = false
                editing = null
            },
            onJoinTailnet = {
                creating = false
                editing = null
                onTailscale()
            },
        )
    }

    menuHost?.let { host ->
        ActionSheet(onDismiss = { menuHost = null }, title = host.label) {
            Group {
                SheetItem(stringResource(R.string.session_new), icon = R.drawable.ic_add) {
                    menuHost = null
                    onNewSession(host)
                }
                SheetItem(stringResource(R.string.action_edit_host), icon = R.drawable.ic_edit) {
                    menuHost = null
                    editing = host
                }
                if (connected?.id == host.id) {
                    SheetItem(stringResource(R.string.action_disconnect), Colors.danger, icon = R.drawable.ic_disconnect) {
                        menuHost = null
                        onDisconnect()
                    }
                }
            }
        }
    }

    if (settings) {
        SettingsSheet(
            tailscale = tailscale,
            tailscaleStatus = tailscaleStatus,
            tailnetError = tailnetError,
            onDismiss = { settings = false },
            onTailscale = {
                settings = false
                onTailscale()
            },
            onAddHost = {
                settings = false
                creating = true
            },
            alerts = alerts,
            onAlerts = toggleAlerts,
            onFontSettings = onFontSettings,
        )
    }
}

/** The fold's chevron: down while open, turned to point right while folded. */
@Composable
private fun FoldChevron(expanded: Boolean) {
    val turn by animateFloatAsState(if (expanded) 0f else -90f, label = "fold")
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Glyph(R.drawable.ic_expand, Colors.muted, Modifier.rotate(turn))
    }
}

/**
 * One host: its mark, its name with the connection's dot, and what it is doing. Tapping it folds
 * its sessions, or connects it when it is not the one open; the menu edits it.
 */
@Composable
private fun HostHeader(
    host: SavedHost,
    live: Boolean,
    status: String?,
    phase: Phase?,
    sessionCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onMenu: () -> Unit,
) {
    val folded = stringResource(R.string.state_folded)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(Radius.card))
            .clickable(onClick = onToggle)
            .semantics { if (live && !expanded) stateDescription = folded }
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
            Text(
                (if (live) status.orEmpty() else stringResource(R.string.host_not_connected)) +
                    if (sessionCount > 0) " · " + pluralStringResource(R.plurals.session_count, sessionCount, sessionCount) else "",
                color = Colors.muted,
                fontSize = TextStyles.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RoundButton(R.drawable.ic_more, stringResource(R.string.action_more), onMenu, tint = Colors.muted, size = 48.dp)
    }
}

/** The line under a connected host: why it is not live, and how the tailnet carries it. */
@Composable
private fun HostStatus(state: PocketState, path: TailnetPath?, onRetry: () -> Unit, onProbe: () -> Unit) {
    val problem = rememberSettledProblem(state.phase, state.problem.takeIf { state.phase != Phase.Live })
    if (problem != null) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(Radius.chip)).clickable(onClick = onRetry)
                .padding(start = KEY_TEXT, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(problemText(problem, state.endpointLabel), color = Colors.danger, fontSize = TextStyles.label, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.action_retry_now), color = Colors.accent, fontSize = TextStyles.label, fontWeight = FontWeight.Medium)
        }
    }
    if (path != null) {
        val ms = path.latencyMs?.let { " · ${it.toInt()} ms" }.orEmpty()
        val (text, color) = when (path) {
            is TailnetPath.Direct -> stringResource(R.string.tailnet_path_direct) + ms to Colors.ok
            is TailnetPath.PeerRelay -> stringResource(R.string.tailnet_path_peer_relay) + ms to Colors.amber
            is TailnetPath.Derp ->
                stringResource(R.string.tailnet_path_derp, path.regionCode.ifBlank { path.regionId.toString() }) + ms to Colors.amber
            is TailnetPath.Unreachable -> stringResource(R.string.tailnet_path_unreachable, path.reason) to Colors.danger
        }
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
 * A session under its host, as the reference writes it: a quiet group label with its tab count, the
 * plus that makes a tab in it, and the fold. A blue dot says something in it is working.
 */
@Composable
private fun SessionHeader(
    row: HomeSession,
    live: Boolean,
    opening: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onNewTab: () -> Unit,
) {
    val tabs = row.view?.windows?.size ?: 0
    val detail = when {
        opening -> stringResource(R.string.session_opening)
        row.view != null -> pluralStringResource(R.plurals.session_window_count, tabs, tabs)
        !live -> stringResource(R.string.session_cached)
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(Radius.chip)).clickable(onClick = onToggle)
            .padding(start = KEY_MARK),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.session.label,
                color = Colors.muted,
                fontSize = TextStyles.subhead,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (row.activity) {
                Spacer(Modifier.width(8.dp))
                Dot(Colors.accent, 6.dp)
            }
            if (detail != null) {
                Spacer(Modifier.width(8.dp))
                Text(detail, color = if (opening) Colors.accent else Colors.muted, fontSize = TextStyles.label, maxLines = 1)
            }
        }
        RoundButton(
            R.drawable.ic_add_circle,
            stringResource(R.string.menu_new_tab),
            onNewTab,
            tint = Colors.muted,
            size = 48.dp,
            enabled = !opening,
        )
        FoldChevron(expanded || row.view == null)
    }
}

/** The tabs of one session, on one card, hairlines between rows from the title's keyline. */
@Composable
private fun TabCard(
    row: HomeSession,
    state: PocketState,
    opening: Boolean,
    onOpen: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(Colors.surface)) {
        row.tabs.forEachIndexed { index, window ->
            if (index > 0) {
                Box(Modifier.padding(start = KEY_TEXT).fillMaxWidth().height(1.dp).background(Colors.line))
            }
            val activity = window.blocks.firstNotNullOfOrNull { state.activityOf(it) }
            TabRow(
                title = row.view?.let { tabTitle(it, window, state) } ?: window.label,
                program = tabProgram(window, state),
                detail = detailFor(window, activity),
                busy = activity != null,
                opening = opening,
                onOpen = { onOpen(window.id) },
            )
        }
    }
}

@Composable
private fun detailFor(window: WindowInfo, activity: ProgramRecord?): String {
    val panes = pluralStringResource(R.plurals.pane_count, window.blocks.size, window.blocks.size)
    val word = activity?.let { statusWord(it) }
    return if (word != null) "$panes · $word" else panes
}

/**
 * One tab: the tile of what runs in it - an agent's mark, or the terminal for a shell - badged blue
 * while a pane in it works, its name, its panes, a chevron.
 */
@Composable
private fun TabRow(
    title: String,
    program: String?,
    detail: String,
    busy: Boolean,
    opening: Boolean,
    onOpen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = !opening, role = Role.Button, onClick = onOpen)
            .padding(start = KEY_MARK, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            ProgramTile(program, size = TILE)
            if (busy) Dot(Colors.accent, 8.dp, Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp), ring = Colors.surface)
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
            Text(detail, color = if (busy) Colors.accent else Colors.muted, fontSize = TextStyles.label, maxLines = 1)
        }
        Glyph(R.drawable.ic_chevron_right, Colors.muted, size = 24.dp)
    }
}

@Composable
private fun EmptyTabs(text: String, canOpen: Boolean, onOpen: () -> Unit) {
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
private fun NotFound(activeOnly: Boolean) {
    Text(
        stringResource(if (activeOnly) R.string.no_activity else R.string.search_none),
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
private fun FloatingControls(
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
private fun SearchBar(
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

@Composable
private fun SettingsSheet(
    tailscale: TailscaleSettings,
    tailscaleStatus: TailscaleStatus,
    tailnetError: String?,
    onDismiss: () -> Unit,
    onTailscale: () -> Unit,
    onAddHost: () -> Unit,
    alerts: Boolean,
    onAlerts: () -> Unit,
    onFontSettings: () -> Unit,
) {
    val context = LocalContext.current
    val joined = tailscale.enabled && tailscaleStatus.backendState == "Running"
    ActionSheet(onDismiss = onDismiss, title = stringResource(R.string.settings)) {
        Group {
            SheetItem(
                label = stringResource(R.string.tailscale_title),
                icon = R.drawable.ic_tailnet,
                detail = when {
                    tailnetError != null -> stringResource(R.string.tailscale_state_failed)
                    joined -> stringResource(R.string.tailscale_state_running)
                    tailscale.enabled -> tailnetState(tailscaleStatus)
                    else -> stringResource(R.string.tailscale_not_joined)
                },
                onClick = onTailscale,
            )
        }
        Group {
            SheetItem(
                label = stringResource(R.string.alerts_title),
                icon = R.drawable.ic_notifications,
                detail = stringResource(if (alerts) R.string.alerts_on else R.string.alerts_off),
                onClick = onAlerts,
            )
            SheetItem(stringResource(R.string.action_add_host), icon = R.drawable.ic_add, onClick = onAddHost)
            SheetItem(stringResource(R.string.terminal_font_title), icon = R.drawable.ic_settings, onClick = onFontSettings)
            SheetItem(stringResource(R.string.settings_source), icon = R.drawable.ic_open_in_new) { openLink(context, SOURCE_URL) }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Text(
                "RexPocket ${BuildConfig.VERSION_NAME}",
                color = Colors.muted,
                fontSize = TextStyles.label,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_credits),
                color = Colors.muted,
                fontSize = TextStyles.label,
                lineHeight = labelLeading,
            )
        }
    }
}

@Composable
private fun EmptyHome(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Colors.bg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Glyph(R.drawable.ic_host, Colors.muted, size = 48.dp)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.home_no_hosts), color = Colors.ink, fontSize = TextStyles.display, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.home_no_hosts_body),
            color = Colors.muted,
            fontSize = TextStyles.label,
            fontFamily = FontFamily.Monospace,
            lineHeight = labelLeading,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        PrimaryButton(stringResource(R.string.action_add_host), onClick = onAdd)
    }
}

/** Tailscale's backend state in the app's own words; empty while nothing is running. */
@Composable
internal fun tailnetState(status: TailscaleStatus): String = when (status.backendState) {
    "" -> ""
    "Running" -> stringResource(R.string.tailscale_state_running)
    "Starting" -> stringResource(R.string.tailscale_state_starting)
    "NeedsLogin" -> stringResource(R.string.tailscale_state_needs_login)
    "NeedsMachineAuth" -> stringResource(R.string.tailscale_state_needs_approval)
    "Stopped", "NoState" -> stringResource(R.string.tailscale_state_stopped)
    else -> status.text.ifBlank { status.backendState }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TailscaleDialog(
    initial: TailscaleSettings,
    status: TailscaleStatus,
    error: String?,
    onDismiss: () -> Unit,
    onJoin: (String) -> Unit,
    onLeave: () -> Unit,
) {
    val context = LocalContext.current
    var hostname by remember { mutableStateOf(initial.hostname) }
    val state = tailnetState(status)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Colors.surface,
        title = { Text(stringResource(R.string.tailscale_title), color = Colors.ink) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Field(stringResource(R.string.tailscale_hostname), hostname) { hostname = it }
                if (initial.enabled) {
                    Text(
                        listOfNotNull(
                            state.ifBlank { null },
                            status.networkName.ifBlank { null },
                        ).joinToString(" · ").ifBlank { stringResource(R.string.tailscale_state_starting) },
                        color = if (status.backendState == "Running") Colors.accent else Colors.amber,
                        fontSize = TextStyles.label,
                    )
                }
                error?.let { Text(it, color = Colors.danger, fontSize = TextStyles.label) }
                if (status.authUrl.isNotBlank()) {
                    Text(
                        status.authUrl,
                        color = Colors.accent,
                        fontSize = TextStyles.label,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.combinedClickable(
                            onClick = { openLink(context, status.authUrl) },
                            onLongClick = {
                                clipboardSet(context, status.authUrl)
                                toast(context, context.getString(R.string.tailscale_login_copied))
                            },
                        ),
                    )
                    Text(stringResource(R.string.tailscale_login_hint), color = Colors.muted, fontSize = TextStyles.label, lineHeight = labelLeading)
                }
                Text(stringResource(R.string.tailscale_hint), color = Colors.muted, fontSize = TextStyles.label, lineHeight = labelLeading)
            }
        },
        confirmButton = {
            TextButton(onClick = { onJoin(hostname) }) {
                Text(
                    stringResource(if (initial.enabled) R.string.action_save else R.string.tailscale_join),
                    color = Colors.accent,
                )
            }
        },
        dismissButton = {
            Row {
                if (initial.enabled) {
                    TextButton(onClick = onLeave) { Text(stringResource(R.string.tailscale_leave), color = Colors.danger) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Colors.muted) }
            }
        },
    )
}

/**
 * Adding a host is a name and an address. The port, the scheme and whether the address has to go
 * through the built-in Tailscale node all follow from the address, and the line under the field
 * says what the app made of it.
 */
@Composable
internal fun HostDialog(
    initial: SavedHost?,
    tailscaleEnabled: Boolean,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (SavedHost) -> Unit,
    onJoinTailnet: () -> Unit,
) {
    val context = LocalContext.current
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    var address by remember {
        mutableStateOf(
            initial?.let { host ->
                val scheme = if (host.plaintext) "ws" else "wss"
                "$scheme://${host.host}:${host.port}"
            } ?: "",
        )
    }
    val parsed = remember(address) { HostAddressParser.parse(address) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Colors.surface,
        title = {
            Text(
                if (initial == null) stringResource(R.string.action_add_host) else stringResource(R.string.action_edit_host),
                color = Colors.ink,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(stringResource(R.string.field_name), label) { label = it }
                Field(stringResource(R.string.field_address), address) { address = it }
                if (parsed != null) {
                    Text(
                        listOfNotNull(
                            "${parsed.host}:${parsed.port}",
                            stringResource(if (parsed.plaintext) R.string.host_transport_plain else R.string.host_transport_tls),
                            stringResource(R.string.tailscale_title).takeIf { parsed.viaTailscale },
                        ).joinToString(" · "),
                        color = Colors.muted,
                        fontSize = TextStyles.label,
                    )
                } else if (address.isNotBlank()) {
                    Text(stringResource(R.string.host_address_invalid), color = Colors.danger, fontSize = TextStyles.label)
                }
                val tailnetTarget = parsed
                if (tailnetTarget != null && tailnetTarget.viaTailscale && !tailscaleEnabled) {
                    Text(
                        stringResource(R.string.tailscale_need_join_host),
                        color = Colors.amber,
                        fontSize = TextStyles.label,
                        modifier = Modifier.clickable {
                            onSave(asSavedHost(initial, label, tailnetTarget, viaTailscale = true))
                            onJoinTailnet()
                        },
                    )
                }
                if (parsed?.plaintext == true) {
                    Text(stringResource(R.string.field_plaintext_warning), color = Colors.amber, fontSize = TextStyles.label)
                }
            }
        },
        confirmButton = {
            // Nothing to save until the address says where to connect.
            TextButton(
                enabled = parsed != null,
                onClick = { parsed?.let { onSave(asSavedHost(initial, label, it, viaTailscale = it.viaTailscale)) } },
            ) { Text(stringResource(R.string.action_save), color = if (parsed != null) Colors.accent else Colors.muted) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.action_delete), color = Colors.danger) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Colors.muted) }
            }
        },
    )
    if (confirmDelete && onDelete != null && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Colors.surface,
            title = { Text(stringResource(R.string.confirm_delete_host, initial.label), color = Colors.ink) },
            text = { Text(stringResource(R.string.confirm_delete_host_body), color = Colors.muted) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(R.string.action_delete), color = Colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel), color = Colors.muted) }
            },
        )
    }
}

internal fun asSavedHost(
    initial: SavedHost?,
    label: String,
    parsed: HostAddress,
    viaTailscale: Boolean,
) = SavedHost(
    id = initial?.id ?: HostStore.newId(),
    label = label.trim().ifBlank { parsed.host },
    host = parsed.host,
    port = parsed.port,
    plaintext = parsed.plaintext,
    viaTailscale = viaTailscale,
)
