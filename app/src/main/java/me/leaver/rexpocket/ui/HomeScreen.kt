package me.leaver.rexpocket.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import me.leaver.rexpocket.alerts.Alerts
import me.leaver.rexpocket.core.AlertPrefs
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.leaver.rexpocket.BuildConfig
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.Appearance
import me.leaver.rexpocket.core.BlockInfo
import me.leaver.rexpocket.core.HostAddress
import me.leaver.rexpocket.core.HostAddressParser
import me.leaver.rexpocket.core.CtrlCKey
import me.leaver.rexpocket.core.KeyArrangement
import me.leaver.rexpocket.core.GestureSettings
import me.leaver.rexpocket.core.ListedSession
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.ProgramRecord
import me.leaver.rexpocket.core.SessionView
import me.leaver.rexpocket.core.TailnetBackend
import me.leaver.rexpocket.core.TailnetPath
import me.leaver.rexpocket.core.TailnetStage
import me.leaver.rexpocket.core.ConnectProgress
import me.leaver.rexpocket.core.Trouble
import me.leaver.rexpocket.core.UpdateChannel
import me.leaver.rexpocket.core.WindowInfo
import me.leaver.rexpocket.data.HostStore
import me.leaver.rexpocket.data.HomeFolds
import me.leaver.rexpocket.data.SavedHost
import me.leaver.rexpocket.data.TailnetSettings
import me.leaver.rexpocket.update.UpdateState
import me.leaver.rexpocket.diagnostics.Diagnostics
import me.leaver.rexpocket.net.TailnetStatus

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

/** A session and its tabs can be managed from the home only on the live host, once Rex sent its view. */
private fun manageable(live: Boolean, state: PocketState, row: HomeSession): Boolean =
    live && state.phase == Phase.Live && row.view != null

/** A pane that is working or waiting is what the eye filters on; its record is the pane's summary. */
private fun PocketState.activityOf(block: BlockInfo): ProgramRecord? =
    blockStatus[block.id]?.takeIf { block.id in busy }

@Composable
internal fun HomeScreen(
    hosts: List<SavedHost>,
    connected: SavedHost?,
    state: PocketState,
    /** True while the open pane stands beside this list, so the tab on the right carries a mark. */
    detail: Boolean = false,
    tailnet: TailnetSettings,
    tailnetStatus: TailnetStatus,
    tailnetError: TailnetFailure?,
    tailnetPath: TailnetPath?,
    /** Where the joined node is on its way to the network; see [TailnetStage]. */
    tailnetStage: TailnetStage = TailnetStage.Off,
    /** The steps of the connection to the open host while it is on its way. */
    connectProgress: ConnectProgress? = null,
    /** Opens the node's sign-in page. */
    onSignIn: (String) -> Unit = {},
    /** Starts the joined node again after it failed to start. */
    onTailnetRetry: () -> Unit = {},
    opening: Opening?,
    cachedSessions: (String) -> List<ListedSession>,
    /** Which hosts and sessions are folded: the view model's, so they outlive this screen. */
    folds: HomeFolds = HomeFolds(),
    onFoldHost: (hostId: String, folded: Boolean) -> Unit = { _, _ -> },
    onFoldSession: (hostId: String, sessionId: String, folded: Boolean) -> Unit = { _, _, _ -> },
    onConnect: (SavedHost) -> Unit,
    onOpenSession: (SavedHost, String) -> Unit,
    onOpenTab: (SavedHost, String, String) -> Unit,
    /** Opens one pane of a tab: an activity row opens the pane whose program reported. */
    onOpenPane: (SavedHost, String, String, String) -> Unit = { host, session, window, _ -> onOpenTab(host, session, window) },
    onNewSession: (SavedHost) -> Unit,
    onNewTab: (SavedHost, String) -> Unit,
    /** What the session and tab sheets do, on the connected host and without attaching. */
    onManage: (HomeAction) -> Unit = {},
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
    onProbePath: () -> Unit,
    onSave: (SavedHost) -> Unit,
    onDelete: (String) -> Unit,
    onTailnet: () -> Unit,
    alerts: Boolean,
    onAlerts: (Boolean) -> Unit,
    /** The Notifications page's switches: which kinds of alert are on. */
    alertPrefs: AlertPrefs = AlertPrefs(),
    onAlertPrefs: (AlertPrefs) -> Unit = {},
    keyArrangement: KeyArrangement,
    onKeyArrangement: (KeyArrangement) -> Unit,
    keyBarCtrlC: CtrlCKey,
    onKeyBarCtrlC: (CtrlCKey) -> Unit,
    gestures: GestureSettings,
    onGestures: (GestureSettings) -> Unit,
    /** True when the key bar is kept on screen with a keyboard attached, which is not the default. */
    keyBar: Boolean,
    onKeyBar: (Boolean) -> Unit,
    onFontSettings: () -> Unit,
    /** Builds the diagnostics report when the person asks for one, kept to [limit] characters. */
    onDiagnosticsReport: (limit: Int) -> String,
    /** The newer build the app found, if it found one, and the tap that acts on it. */
    update: UpdateState,
    onUpdate: () -> Unit,
    /** Which builds the updater offers, and the tap on the other one. */
    updateChannel: UpdateChannel,
    onUpdateChannel: (UpdateChannel) -> Unit,
    /** Whether every launch goes back to the last tab, not only one after Android killed the app. */
    reopenLastTab: Boolean = false,
    onReopenLastTab: (Boolean) -> Unit = {},
    /** Settings › Appearance: the app's theme and the terminal's colours. */
    appearance: Appearance = Appearance(),
    onAppearance: (Appearance) -> Unit = {},
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
    // What the person was looking at survives the window being rebuilt under it, which a rotation
    // or a resize in a free-form window does, and a trip to the terminal and back (RexApp keeps
    // this screen's saved state while it is away): the sheet that was open, the search on its
    // query, the eye, and where the list was scrolled to. The folds are the view model's.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = editingId?.let { id -> hosts.firstOrNull { it.id == id } }
    var creating by rememberSaveable { mutableStateOf(false) }
    // A device picked in the tailnet sheet: a new host starts from its name and Rex's port.
    var prefillLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var prefillAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var tailnetDetails by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var notifications by rememberSaveable { mutableStateOf(false) }
    var controlsPage by rememberSaveable { mutableStateOf<ControlsPage?>(null) }
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var recording by remember { mutableStateOf(Diagnostics.capture(context) != null) }
    var menuHostId by rememberSaveable { mutableStateOf<String?>(null) }
    var manage by remember { mutableStateOf<ManageTarget?>(null) }
    val menuHost = menuHostId?.let { id -> hosts.firstOrNull { it.id == id } }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var activeOnly by rememberSaveable { mutableStateOf(false) }
    val collapsedHosts = folds.hosts
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
        EmptyHome(
            tailnet = tailnet,
            tailnetStage = tailnetStage,
            tailnetStatus = tailnetStatus,
            onAdd = { address ->
                prefillAddress = address
                creating = true
            },
            onJoinTailnet = onTailnet,
            onTailnetDetails = { tailnetDetails = true },
            onSignIn = onSignIn,
            onTailnetRetry = onTailnetRetry,
        )
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
        // The eye shows the connected host's activity, as Rex's Activity does. A host the phone is
        // not connected to has none to show: only a live connection hears its programs.
        val liveHost = connected?.takeIf { state.phase == Phase.Live }
        val activity = if (activeOnly && liveHost != null) activityShown(state, query) else emptyList()
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
                    // The tailnet's line leads the list it carries, quiet once the phone is on it.
                    val tailnetHosts = hosts.count { it.viaTailscale }
                    if (!activeOnly && !searchOpen && (tailnet.enabled || tailnetHosts > 0)) {
                        item(key = "tailnet") {
                            TailnetStatusCard(
                                backend = tailnet.backend,
                                stage = tailnetStage,
                                status = tailnetStatus,
                                hostsNeedingTailnet = tailnetHosts,
                                onDetails = { tailnetDetails = true },
                                onSignIn = onSignIn,
                                onSettings = onTailnet,
                                onRetry = onTailnetRetry,
                                modifier = Modifier.padding(bottom = 12.dp),
                            )
                        }
                    }
                    if (activeOnly) {
                        if (activity.isEmpty() || liveHost == null) {
                            item(key = "activity-empty") { ActivityEmpty(live = liveHost != null, searching = query.isNotBlank()) }
                        } else {
                            activityItems(activity, state) { item ->
                                onOpenPane(liveHost, item.sessionId, item.window.id, item.block.id)
                            }
                        }
                    } else rows.forEachIndexed { index, (host, sessions) ->
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
                                        onFoldHost(host.id, hostExpanded)
                                    } else {
                                        onConnect(host)
                                    }
                                },
                                onMenu = { menuHostId = host.id },
                            )
                            if (live) {
                                HostStatus(
                                    state = state,
                                    path = tailnetPath,
                                    network = tailnetName(tailnet.backend),
                                    progress = connectProgress?.takeIf { it.host == host.host },
                                    stage = tailnetStage,
                                    authUrl = tailnetStatus.authUrl,
                                    onRetry = onRetry,
                                    onProbe = onProbePath,
                                    onSignIn = onSignIn,
                                    onDevices = { tailnetDetails = true },
                                    onTailnet = onTailnet,
                                )
                            }
                        }
                        if (hostExpanded) {
                            sessions.forEach { row ->
                                val sessionExpanded = HomeFolds.sessionKey(host.id, row.session.id) !in folds.sessions || searchOpen
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
                                                onFoldSession(host.id, row.session.id, sessionExpanded)
                                            }
                                        },
                                        onNewTab = { onNewTab(host, row.session.id) },
                                        onManage = if (manageable(live, state, row)) {
                                            { manage = ManageTarget.Session(row.session.id) }
                                        } else {
                                            null
                                        },
                                        moves = if (manageable(live, state, row)) {
                                            moveActions(state.sessions.indexOfFirst { it.id == row.session.id }, state.sessions.size) { to ->
                                                onManage(HomeAction.MoveSession(row.session.id, to))
                                            }
                                        } else {
                                            emptyList()
                                        },
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
                                                open = detail && live && state.activeSessionId == row.session.id,
                                                onOpen = { windowId -> onOpenTab(host, row.session.id, windowId) },
                                                onManage = if (manageable(live, state, row)) {
                                                    { windowId -> manage = ManageTarget.Tab(row.session.id, windowId) }
                                                } else {
                                                    null
                                                },
                                                onMove = if (manageable(live, state, row)) {
                                                    { windowId, to -> onManage(HomeAction.MoveTab(row.session.id, windowId, to)) }
                                                } else {
                                                    null
                                                },
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                    }
                                }
                            }
                        }
                    }
                    if (!activeOnly && rows.isEmpty()) {
                        item { NotFound() }
                    }
                    // The list is the only place a host is added from, so the way to add one is at
                    // the end of it - above it, there is nothing until a host exists.
                    if (!searchOpen && !activeOnly && hosts.isNotEmpty()) {
                        item(key = "add-host") {
                            Spacer(Modifier.height(12.dp))
                            AddHostRow(onClick = { creating = true })
                        }
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
        val done = {
            creating = false
            editingId = null
            prefillLabel = null
            prefillAddress = null
        }
        HostDialog(
            initial = editing,
            tailnetEnabled = tailnet.enabled,
            prefillLabel = prefillLabel,
            prefillAddress = prefillAddress,
            onDismiss = done,
            onDelete = editing?.let { host -> { onDelete(host.id); done() } },
            onSave = {
                onSave(it)
                done()
            },
            onJoinTailnet = {
                done()
                onTailnet()
            },
        )
    }

    if (tailnetDetails) {
        TailnetSheet(
            backend = tailnet.backend,
            stage = tailnetStage,
            status = tailnetStatus,
            hosts = hosts,
            openHost = connected,
            path = tailnetPath,
            onDismiss = { tailnetDetails = false },
            onSignIn = onSignIn,
            onSettings = onTailnet,
            onRetry = onTailnetRetry,
            onProbe = onProbePath,
            onPeer = { peer, saved ->
                if (saved != null) {
                    onConnect(saved)
                } else {
                    // Rex's own plaintext port: what `rex server start --extra-listen :7297` opens.
                    prefillLabel = peer.name
                    prefillAddress = "${peer.dnsName.trimEnd('.').ifBlank { peer.ips.firstOrNull().orEmpty() }}:${HostAddressParser.PLAIN_PORT}"
                    creating = true
                }
            },
        )
    }

    HomeManageSheet(manage, state, onDismiss = { manage = null }, onAction = onManage)

    menuHost?.let { host ->
        ActionSheet(onDismiss = { menuHostId = null }, title = host.label) {
            Group {
                SheetItem(stringResource(R.string.session_new), icon = R.drawable.ic_add) {
                    menuHostId = null
                    onNewSession(host)
                }
                SheetItem(stringResource(R.string.action_edit_host), icon = R.drawable.ic_edit) {
                    menuHostId = null
                    editingId = host.id
                }
                if (connected?.id == host.id) {
                    SheetItem(stringResource(R.string.action_disconnect), Colors.danger, icon = R.drawable.ic_disconnect) {
                        menuHostId = null
                        onDisconnect()
                    }
                }
            }
        }
    }

    if (settings) {
        SettingsSheet(
            tailnet = tailnet,
            tailnetStatus = tailnetStatus,
            tailnetError = tailnetError,
            tailnetStage = tailnetStage,
            onDismiss = { settings = false },
            onTailnet = {
                settings = false
                onTailnet()
            },
            alerts = alerts,
            onNotifications = {
                settings = false
                notifications = true
            },
            onControls = {
                settings = false
                controlsPage = ControlsPage.Controls
            },
            onFontSettings = onFontSettings,
            onAppearance = {
                settings = false
                appearanceOpen = true
            },
            reopenLastTab = reopenLastTab,
            onReopenLastTab = onReopenLastTab,
            recording = recording,
            onDiagnostics = {
                settings = false
                diagnostics = true
            },
            update = update,
            onUpdate = { onUpdate() },
            channel = updateChannel,
            onChannel = onUpdateChannel,
        )
    }

    if (notifications) {
        NotificationsSheet(
            onDismiss = { notifications = false },
            alerts = alerts,
            onAlerts = toggleAlerts,
            prefs = alertPrefs,
            onPrefs = onAlertPrefs,
        )
    }

    TerminalControlsPages(
        page = controlsPage,
        onPage = { controlsPage = it },
        gestures = gestures,
        onGestures = onGestures,
        keyBar = keyBar,
        onKeyBar = onKeyBar,
        keyBarCtrlC = keyBarCtrlC,
        onKeyBarCtrlC = onKeyBarCtrlC,
        keyArrangement = keyArrangement,
        onKeyArrangement = onKeyArrangement,
    )

    if (appearanceOpen) AppearanceSheet(appearance, onAppearance, onDismiss = { appearanceOpen = false })

    if (diagnostics) {
        DiagnosticsSheet(
            onDismiss = { diagnostics = false },
            report = onDiagnosticsReport,
            onRecordingChanged = { recording = it },
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

/**
 * The line under a connected host: the step its connection is on while it connects, why it is not
 * live - with the fix next to Retry when there is one: sign in, the device list, join - and how the
 * tailnet carries it once it is.
 */
@Composable
private fun HostStatus(
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
 * A session under its host, as the reference writes it: a quiet group label with its tab count, the
 * plus that makes a tab in it, and the fold. A blue dot says something in it is working.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SessionHeader(
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

/** The tabs of one session, on one card, hairlines between rows from the title's keyline. */
@Composable
private fun TabCard(
    row: HomeSession,
    state: PocketState,
    opening: Boolean,
    /** True for the session whose tab the pane beside the list is showing. */
    open: Boolean,
    onOpen: (String) -> Unit,
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
            val activity = window.blocks.firstNotNullOfOrNull { state.activityOf(it) }
            TabRow(
                title = row.view?.let { tabTitle(it, window, state) } ?: window.label,
                program = tabProgram(window, state),
                detail = detailFor(window, activity),
                busy = activity != null,
                opening = opening,
                open = open && window.id == state.view?.activeWindowId,
                onOpen = { onOpen(window.id) },
                onManage = onManage?.let { manage -> { manage(window.id) } },
                // Places in the session's whole order: the eye and the search may show only some tabs.
                moves = onMove?.let { move ->
                    val all = row.view?.windows.orEmpty()
                    moveActions(all.indexOfFirst { it.id == window.id }, all.size) { to -> move(window.id, to) }
                }.orEmpty(),
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
@OptIn(ExperimentalFoundationApi::class)
private fun TabRow(
    title: String,
    program: String?,
    detail: String,
    busy: Boolean,
    opening: Boolean,
    /** True for the tab the pane beside the list is showing. */
    open: Boolean,
    onOpen: () -> Unit,
    onManage: (() -> Unit)? = null,
    moves: List<CustomAccessibilityAction> = emptyList(),
) {
    val selected = stringResource(R.string.state_selected)
    val manageLabel = stringResource(R.string.manage_actions_for, title)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .background(if (open) Colors.keyOn else Color.Transparent)
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
        if (onManage != null) {
            RoundButton(R.drawable.ic_more, manageLabel, onManage, tint = Colors.muted, size = 48.dp, enabled = !opening)
        } else {
            Glyph(R.drawable.ic_chevron_right, Colors.muted, size = 24.dp)
        }
    }
}

/**
 * The end of the list: the way to add another computer, where the list runs out. It sits under the
 * hosts rather than in the settings, because adding one is a thing to do to this list.
 */
@Composable
private fun AddHostRow(onClick: () -> Unit) {
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
private fun NotFound() {
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
    tailnet: TailnetSettings,
    tailnetStatus: TailnetStatus,
    tailnetError: TailnetFailure?,
    tailnetStage: TailnetStage,
    onDismiss: () -> Unit,
    onTailnet: () -> Unit,
    alerts: Boolean,
    /** Opens Notifications: Background alerts and what they tell. */
    onNotifications: () -> Unit,
    /** Opens the terminal's controls: its gestures and its key bar. */
    onControls: () -> Unit,
    onFontSettings: () -> Unit,
    /** Opens Appearance: the app's theme and the terminal's colours. */
    onAppearance: () -> Unit,
    reopenLastTab: Boolean,
    onReopenLastTab: (Boolean) -> Unit,
    recording: Boolean,
    onDiagnostics: () -> Unit,
    update: UpdateState,
    onUpdate: () -> Unit,
    channel: UpdateChannel,
    onChannel: (UpdateChannel) -> Unit,
) {
    val context = LocalContext.current
    ActionSheet(onDismiss = onDismiss, title = stringResource(R.string.settings)) {
        // Four cards, each one answer to a different question: how the phone reaches a computer,
        // what the terminal looks and feels like, which build it installs, and what the app is. A
        // flat list of rows made every one of them equally far away; adding a host is not here at
        // all, because the list it belongs to has its own + button.
        Group(stringResource(R.string.settings_group_connection)) {
            SheetItem(
                label = stringResource(R.string.tailnet_title),
                icon = R.drawable.ic_tailnet,
                detail = if (tailnetStage is TailnetStage.Off) stringResource(R.string.tailscale_not_joined)
                else tailnetName(tailnet.backend) + " · " + tailnetStageText(tailnetStage, tailnet.backend),
                opens = true,
                onClick = onTailnet,
            )
            // Alerts are the connection kept up in the background, so they sit with the way in.
            SheetItem(
                label = stringResource(R.string.notifications_title),
                icon = R.drawable.ic_notifications,
                detail = stringResource(if (alerts) R.string.alerts_on else R.string.alerts_off),
                opens = true,
                onClick = onNotifications,
            )
        }
        Group(stringResource(R.string.settings_group_terminal)) {
            // Gestures and keys have a sheet of their own, so this one stays a list of places to go.
            SheetItem(stringResource(R.string.controls_title), icon = R.drawable.ic_touch_app, opens = true, onClick = onControls)
            SheetItem(stringResource(R.string.terminal_font_title), icon = R.drawable.ic_text_fields, opens = true, onClick = onFontSettings)
            // The app's light or dark, and the terminal's apart from it.
            SheetItem(stringResource(R.string.appearance_title), icon = R.drawable.ic_contrast, opens = true, onClick = onAppearance)
            // Off, a fresh start shows this list; Android killing the app on the terminal still
            // comes back to it, since that is the person's own place rather than a pick.
            ChoiceRow(
                label = stringResource(R.string.reopen_last_tab), options = ON_OFF, current = reopenLastTab,
                choice = ::reopenChoice, icon = R.drawable.ic_history, onPick = onReopenLastTab,
            )
        }
        Group(stringResource(R.string.settings_group_version)) {
            // The channel first: it is what the row under it looks on. Its picker says what each
            // channel offers.
            ChoiceRow(
                label = stringResource(R.string.update_channel), options = listOf(UpdateChannel.Stable, UpdateChannel.Preview),
                current = channel, choice = ::channelChoice, icon = R.drawable.ic_swap, onPick = onChannel,
            )
            SheetItem(
                label = stringResource(R.string.update_title),
                icon = R.drawable.ic_system_update,
                detail = updateDetail(update),
                keepOpen = true,
                onClick = onUpdate,
            )
        }
        // About the app itself: what it recorded, where its code is, and what it is built from.
        Group(stringResource(R.string.settings_group_about)) {
            SheetItem(
                label = stringResource(R.string.diagnostics),
                detail = stringResource(if (recording) R.string.diagnostics_state_on else R.string.diagnostics_state_off),
                icon = R.drawable.ic_bug_report,
                opens = true,
                onClick = onDiagnostics,
            )
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

/** The tags of the first-run screen's routes, for the screen tests. */
internal const val EMPTY_ROUTE_SSH_TAG = "empty-route-ssh"
internal const val EMPTY_ROUTE_TAILNET_TAG = "empty-route-tailnet"
internal const val EMPTY_ROUTE_LOCAL_TAG = "empty-route-local"
internal const val EMPTY_COMMAND_TAG = "empty-command"

/** What the local network route asks the computer to run: Rex with a plaintext port, as the README says. */
internal const val LOCAL_START_COMMAND = "rex server start --extra-listen :7297 --allow-anonymous"

/**
 * The first run: no hosts yet. It names Rex and offers the three ways to reach it, each a short card
 * with its one next step - SSH, which opens no port; the tailnet, whose card it becomes once the
 * phone has joined; the local network, with the command to copy.
 */
@Composable
private fun EmptyHome(
    tailnet: TailnetSettings,
    tailnetStage: TailnetStage,
    tailnetStatus: TailnetStatus,
    onAdd: (address: String?) -> Unit,
    onJoinTailnet: () -> Unit,
    onTailnetDetails: () -> Unit,
    onSignIn: (String) -> Unit,
    onTailnetRetry: () -> Unit,
) {
    val context = LocalContext.current
    val copied = stringResource(R.string.toast_command_copied)
    Column(
        Modifier.fillMaxSize().background(Colors.bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PAGE, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.home_no_hosts), color = Colors.ink, fontSize = TextStyles.display, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.home_no_hosts_body), color = Colors.muted, fontSize = TextStyles.subhead, lineHeight = labelLeading)
        Spacer(Modifier.height(4.dp))
        EmptyRoute(
            icon = R.drawable.ic_lock,
            title = stringResource(R.string.home_route_ssh),
            body = stringResource(R.string.home_route_ssh_body),
            tag = EMPTY_ROUTE_SSH_TAG,
        ) { RouteButton(stringResource(R.string.action_add_ssh_host)) { onAdd(SSH_PREFILL) } }
        if (tailnet.enabled && tailnetStage != TailnetStage.Off) {
            // Joined: the tailnet's own card, which says where the node is and what to do next.
            TailnetStatusCard(
                backend = tailnet.backend,
                stage = tailnetStage,
                status = tailnetStatus,
                hostsNeedingTailnet = 0,
                onDetails = onTailnetDetails,
                onSignIn = onSignIn,
                onSettings = onJoinTailnet,
                onRetry = onTailnetRetry,
            )
        } else {
            EmptyRoute(
                icon = R.drawable.ic_tailnet,
                title = stringResource(R.string.home_route_tailnet),
                body = stringResource(R.string.home_route_tailnet_body),
                tag = EMPTY_ROUTE_TAILNET_TAG,
            ) { RouteButton(stringResource(R.string.action_join_tailnet), onClick = onJoinTailnet) }
        }
        EmptyRoute(
            icon = R.drawable.ic_host,
            title = stringResource(R.string.home_route_local),
            body = stringResource(R.string.home_route_local_body),
            tag = EMPTY_ROUTE_LOCAL_TAG,
        ) {
            val copyLabel = stringResource(R.string.action_copy_command)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Colors.bg)
                    .clickable(onClickLabel = copyLabel, role = Role.Button) {
                        clipboardSet(context, LOCAL_START_COMMAND)
                        toast(context, copied)
                    }
                    .testTag(EMPTY_COMMAND_TAG)
                    .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    LOCAL_START_COMMAND,
                    color = Colors.ink,
                    fontSize = TextStyles.label,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = labelLeading,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Glyph(R.drawable.ic_copy, Colors.muted, size = 20.dp)
            }
            RouteButton(stringResource(R.string.action_add_host)) { onAdd(null) }
        }
    }
}

/** What the SSH route's Add host starts from: the scheme that makes the dialog ask for an account. */
private const val SSH_PREFILL = "ssh://"

@Composable
private fun EmptyRoute(icon: Int, title: String, body: String, tag: String, actions: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(Colors.surface)
            .testTag(tag).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(icon, Colors.muted, size = 20.dp)
            Spacer(Modifier.width(12.dp))
            Text(title, color = Colors.ink, fontSize = TextStyles.body, fontWeight = FontWeight.SemiBold)
        }
        Text(body, color = Colors.muted, fontSize = TextStyles.label, lineHeight = labelLeading)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            actions()
        }
    }
}

@Composable
private fun RouteButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, color = Colors.accent, fontWeight = FontWeight.SemiBold) }
}

/** The node's backend state in the app's own words; empty while nothing is running. */
@Composable
internal fun tailnetState(status: TailnetStatus): String = when (status.backendState) {
    "" -> ""
    "Running" -> stringResource(R.string.tailscale_state_running)
    "Starting" -> stringResource(R.string.tailscale_state_starting)
    "NeedsLogin" -> stringResource(R.string.tailscale_state_needs_login)
    "NeedsMachineAuth" -> stringResource(R.string.tailscale_state_needs_approval)
    "Stopped", "NoState" -> stringResource(R.string.tailscale_state_stopped)
    else -> status.text.ifBlank { status.backendState }
}

/** The network a backend's node is called in the app's own words. */
internal fun tailnetName(backend: TailnetBackend): String = backend.displayName

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TailnetDialog(
    initial: TailnetSettings,
    backends: List<TailnetBackend>,
    status: TailnetStatus,
    error: TailnetFailure?,
    onDismiss: () -> Unit,
    onJoin: (TailnetBackend, String, String, String) -> Unit,
    onLeave: () -> Unit,
) {
    val context = LocalContext.current
    var backend by remember { mutableStateOf(initial.backend) }
    var hostname by remember { mutableStateOf(initial.hostname) }
    var managementUrl by remember { mutableStateOf(initial.managementUrl) }
    var setupKey by remember { mutableStateOf("") }
    // The node's own state and login only describe the backend that is joined, not one picked here.
    val joinedHere = initial.enabled && backend == initial.backend
    val state = tailnetState(status)
    val netbird = backend == TailnetBackend.Netbird || backend == TailnetBackend.NetbirdAddon
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Colors.surface,
        title = { Text(stringResource(R.string.tailnet_title), color = Colors.ink) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // A choice only when this build has more than one backend.
                if (backends.size > 1) {
                    Text(stringResource(R.string.tailnet_backend_label), color = Colors.muted, fontSize = TextStyles.label)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (choice in backends) {
                            FilterChip(
                                selected = backend == choice,
                                onClick = { backend = choice },
                                label = { Text(tailnetName(choice)) },
                            )
                        }
                    }
                }
                Field(stringResource(R.string.tailscale_hostname), hostname) { hostname = it }
                if (netbird) {
                    Field(
                        stringResource(R.string.netbird_management_url),
                        managementUrl,
                        placeholder = stringResource(R.string.netbird_management_placeholder),
                    ) { managementUrl = it }
                    if (!joinedHere) {
                        Field(
                            stringResource(R.string.netbird_setup_key),
                            setupKey,
                            password = true,
                            placeholder = stringResource(R.string.netbird_setup_key_placeholder),
                        ) { setupKey = it }
                    }
                }
                if (joinedHere) {
                    Text(
                        listOfNotNull(
                            state.ifBlank { null },
                            status.networkName.ifBlank { null },
                        ).joinToString(" · ").ifBlank { stringResource(R.string.tailscale_state_starting) },
                        color = if (status.backendState == "Running") Colors.accent else Colors.amber,
                        fontSize = TextStyles.label,
                    )
                }
                error?.let { Text(tailnetFailureText(it), color = Colors.danger, fontSize = TextStyles.label) }
                if (joinedHere && status.authUrl.isNotBlank()) {
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
                me.leaver.rexpocket.net.addon.TailnetBackendNote(backend)
                if (netbird && !joinedHere) {
                    Text(stringResource(R.string.netbird_need_key), color = Colors.amber, fontSize = TextStyles.label, lineHeight = labelLeading)
                }
                Text(
                    stringResource(if (netbird) R.string.netbird_hint else R.string.tailscale_hint),
                    color = Colors.muted,
                    fontSize = TextStyles.label,
                    lineHeight = labelLeading,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onJoin(backend, hostname, managementUrl, setupKey) }) {
                Text(
                    stringResource(if (joinedHere) R.string.action_save else R.string.tailscale_join),
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
 * through the built-in tailnet node all follow from the address, and the line under the field
 * says what the app made of it - or, while there is nothing to make of it yet, what can be typed
 * there. `ssh://user@host` adds the account: Rex is then reached through that machine's SSH daemon,
 * with a password or a private key.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HostDialog(
    initial: SavedHost?,
    tailnetEnabled: Boolean,
    /** A new host's name and address to start from, such as a device picked in the tailnet sheet. */
    prefillLabel: String? = null,
    prefillAddress: String? = null,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (SavedHost) -> Unit,
    onJoinTailnet: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var label by rememberSaveable { mutableStateOf(initial?.label ?: prefillLabel.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var address by rememberSaveable {
        mutableStateOf(
            initial?.let { host ->
                if (host.viaSsh) {
                    "ssh://${host.host}:${host.port}"
                } else {
                    val scheme = if (host.plaintext) "ws" else "wss"
                    "$scheme://${host.host}:${host.port}"
                }
            } ?: prefillAddress.orEmpty(),
        )
    }
    var sshUser by rememberSaveable { mutableStateOf(initial?.sshUser.orEmpty()) }
    var password by remember { mutableStateOf(initial?.sshPassword.orEmpty()) }
    var privateKey by remember { mutableStateOf(initial?.sshPrivateKey.orEmpty()) }
    var passphrase by remember { mutableStateOf(initial?.sshPassphrase.orEmpty()) }
    var useKey by remember { mutableStateOf(!initial?.sshPrivateKey.isNullOrEmpty()) }
    val parsed = remember(address) { HostAddressParser.parse(address) }
    val ssh = parsed?.viaSsh == true
    // The account can be in the address or in its own field; the address wins when it names one.
    LaunchedEffect(parsed?.sshUser) {
        val fromAddress = parsed?.sshUser
        if (!fromAddress.isNullOrBlank() && fromAddress != sshUser) sshUser = fromAddress
    }
    val user = parsed?.sshUser?.takeIf { it.isNotBlank() } ?: sshUser.trim()
    val sameSshHost = initial != null && initial.viaSsh && parsed != null &&
        initial.host == parsed.host && initial.port == parsed.port && initial.sshUser == user
    val pinned = if (sameSshHost) initial.sshHostKey else ""
    val credentialReady = if (useKey) privateKey.isNotBlank() else password.isNotBlank()
    val identityReady = !ssh || (user.isNotBlank() && credentialReady)
    val canSave = parsed != null && identityReady

    fun savedHost(viaTailscale: Boolean): SavedHost? {
        val address = parsed ?: return null
        return asSavedHost(
            initial = initial,
            label = label,
            parsed = address,
            viaTailscale = viaTailscale,
            sshUser = user,
            sshPassword = if (!useKey) password else "",
            sshPrivateKey = if (useKey) privateKey else "",
            sshPassphrase = if (useKey) passphrase else "",
            sshHostKey = pinned,
        )
    }

    val passwordField = remember { FocusRequester() }
    val keyField = remember { FocusRequester() }

    /** Saves the host, from the button or Enter on the last field; nothing while it is not ready. */
    fun save() {
        val address = parsed ?: return
        if (!canSave) return
        savedHost(viaTailscale = address.viaTailscale)?.let(onSave)
    }

    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val key = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
            }
            if (key.isNullOrBlank()) {
                toast(context, context.getString(R.string.ssh_key_unreadable))
            } else {
                privateKey = key
                useKey = true
            }
        }
    }

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
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Field(
                    stringResource(R.string.field_name),
                    label,
                    placeholder = stringResource(R.string.field_name_optional),
                    imeAction = ImeAction.Next,
                ) { label = it }
                // Enter goes on to the next field, and on the last one saves, as the button does.
                Field(
                    stringResource(R.string.field_address),
                    address,
                    placeholder = stringResource(R.string.field_address_placeholder),
                    keyboardType = KeyboardType.Uri,
                    autoCorrect = false,
                    imeAction = if (ssh) ImeAction.Next else ImeAction.Done,
                    onDone = { save() },
                ) { address = it }
                if (parsed != null) {
                    Text(
                        listOfNotNull(
                            if (ssh && user.isNotBlank()) "$user@${parsed.host}:${parsed.port}" else "${parsed.host}:${parsed.port}",
                            stringResource(if (ssh) R.string.host_transport_ssh else if (parsed.plaintext) R.string.host_transport_plain else R.string.host_transport_tls),
                            stringResource(R.string.tailnet_title).takeIf { parsed.viaTailscale },
                        ).joinToString(" · "),
                        color = Colors.muted,
                        fontSize = TextStyles.label,
                    )
                } else {
                    // Nothing was typed yet, or what was typed is not an address: say what can be.
                    if (address.isNotBlank()) {
                        Text(stringResource(R.string.host_address_invalid), color = Colors.danger, fontSize = TextStyles.label)
                    }
                    Text(
                        stringResource(R.string.host_address_hint),
                        color = Colors.muted,
                        fontSize = TextStyles.label,
                        lineHeight = labelLeading,
                    )
                }
                if (ssh) {
                    Text(stringResource(R.string.ssh_address_hint), color = Colors.muted, fontSize = TextStyles.label)
                    Field(
                        stringResource(R.string.ssh_user),
                        sshUser,
                        keyboardType = KeyboardType.Ascii,
                        autoCorrect = false,
                        imeAction = ImeAction.Next,
                        // Past the password and key buttons, to the field that is shown.
                        next = if (useKey) keyField else passwordField,
                    ) { sshUser = it }
                    // Flowing, so large text puts the second choice under the first rather than squeezing both.
                    FlowRow {
                        TextButton(onClick = { useKey = false }) {
                            Text(
                                stringResource(R.string.ssh_auth_password),
                                color = if (!useKey) Colors.accent else Colors.muted,
                                fontWeight = if (!useKey) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                        TextButton(onClick = { useKey = true }) {
                            Text(
                                stringResource(R.string.ssh_auth_key),
                                color = if (useKey) Colors.accent else Colors.muted,
                                fontWeight = if (useKey) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                    if (useKey) {
                        Field(stringResource(R.string.ssh_private_key), privateKey, password = true, singleLine = false, minLines = 3, focusRequester = keyField) { privateKey = it }
                        FlowRow(verticalArrangement = Arrangement.Center) {
                            TextButton(onClick = { pickKey.launch(arrayOf("*/*")) }) {
                                Text(stringResource(R.string.ssh_key_file), color = Colors.accent)
                            }
                            if (privateKey.isNotBlank()) {
                                Text(stringResource(R.string.ssh_key_loaded), color = Colors.ok, fontSize = TextStyles.label, modifier = Modifier.align(Alignment.CenterVertically))
                            }
                        }
                        Field(stringResource(R.string.ssh_passphrase), passphrase, password = true, imeAction = ImeAction.Done, onDone = { save() }) { passphrase = it }
                    } else {
                        Field(
                            stringResource(R.string.ssh_password),
                            password,
                            password = true,
                            imeAction = ImeAction.Done,
                            onDone = { save() },
                            focusRequester = passwordField,
                        ) { password = it }
                    }
                    if (!identityReady) {
                        Text(
                            stringResource(if (user.isBlank()) R.string.ssh_user_required else R.string.ssh_identity_required),
                            color = Colors.amber,
                            fontSize = TextStyles.label,
                        )
                    }
                    if (pinned.isNotBlank()) {
                        Text(stringResource(R.string.ssh_host_key_trusted, pinned), color = Colors.muted, fontSize = TextStyles.label)
                    }
                }
                if (parsed != null && parsed.viaTailscale && !tailnetEnabled) {
                    Text(
                        stringResource(R.string.tailscale_need_join_host),
                        color = Colors.amber,
                        fontSize = TextStyles.label,
                        modifier = Modifier.clickable {
                            savedHost(viaTailscale = true)?.let(onSave)
                            onJoinTailnet()
                        },
                    )
                }
                if (parsed?.plaintext == true && !ssh) {
                    Text(stringResource(R.string.field_plaintext_warning), color = Colors.amber, fontSize = TextStyles.label)
                }
            }
        },
        confirmButton = {
            // Nothing to save until the address says where to connect and how to get in.
            TextButton(enabled = canSave, onClick = { save() }) { Text(stringResource(R.string.action_save), color = if (canSave) Colors.accent else Colors.muted) }
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
    sshUser: String = "",
    sshPassword: String = "",
    sshPrivateKey: String = "",
    sshPassphrase: String = "",
    sshHostKey: String = "",
) = SavedHost(
    id = initial?.id ?: HostStore.newId(),
    label = label.trim().ifBlank { parsed.host },
    host = parsed.host,
    port = parsed.port,
    plaintext = parsed.plaintext,
    viaTailscale = viaTailscale,
    sshUser = if (parsed.viaSsh) sshUser.trim() else "",
    sshPassword = if (parsed.viaSsh) sshPassword else "",
    sshPrivateKey = if (parsed.viaSsh) sshPrivateKey else "",
    sshPassphrase = if (parsed.viaSsh) sshPassphrase else "",
    sshHostKey = if (parsed.viaSsh) sshHostKey else "",
)
