package me.leaver.rexpocket.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import me.leaver.rexpocket.alerts.Alerts
import me.leaver.rexpocket.core.AlertPrefs
import me.leaver.rexpocket.core.BackgroundHosts
import me.leaver.rexpocket.core.HostBackground
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
import androidx.compose.ui.semantics.heading
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

/** Where the credit for the interface goes; the README carries the long version. */
private const val SOURCE_URL = "https://github.com/leizhiyuan/rexpocket"

@Composable
internal fun HomeScreen(
    hosts: List<SavedHost>,
    /** The focused host: the one the terminal shows, or showed last. */
    connected: SavedHost?,
    /** Every connected host, live, by id; a host not in it is not connected. */
    lives: Map<String, HostLive>,
    /** True while the open pane stands beside this list, so the tab on the right carries a mark. */
    detail: Boolean = false,
    tailnet: TailnetSettings,
    tailnetStatus: TailnetStatus,
    tailnetError: TailnetFailure?,
    tailnetPath: TailnetPath?,
    /** Where the joined node is on its way to the network; see [TailnetStage]. */
    tailnetStage: TailnetStage = TailnetStage.Off,
    /** Opens the node's sign-in page. */
    onSignIn: (String) -> Unit = {},
    /** Starts the joined node again after it failed to start. */
    onTailnetRetry: () -> Unit = {},
    opening: Opening?,
    cachedSessions: (String) -> List<ListedSession>,
    /** Which hosts and sessions are folded: the view model's, so they outlive this screen. */
    folds: HomeFolds = HomeFolds(),
    /** Opens or folds a host's sessions: the person's choice, kept for that host from then on. */
    onHostOpen: (hostId: String, open: Boolean) -> Unit = { _, _ -> },
    onFoldSession: (hostId: String, sessionId: String, folded: Boolean) -> Unit = { _, _, _ -> },
    onFoldSessions: (hostId: String, sessionIds: List<String>, folded: Boolean) -> Unit = { _, _, _ -> },
    onConnect: (SavedHost) -> Unit,
    onOpenSession: (SavedHost, String) -> Unit,
    onOpenTab: (SavedHost, String, String) -> Unit,
    /** Opens one pane of a tab: an activity row opens the pane whose program reported. */
    onOpenPane: (SavedHost, String, String, String) -> Unit = { host, session, window, _ -> onOpenTab(host, session, window) },
    onNewSession: (SavedHost) -> Unit,
    onNewTab: (SavedHost, String) -> Unit,
    /** What the session and tab sheets do, on that host and without attaching. */
    onManage: (hostId: String, HomeAction) -> Unit = { _, _ -> },
    onDisconnect: (hostId: String) -> Unit,
    onRetry: (hostId: String) -> Unit,
    onProbePath: (hostId: String) -> Unit,
    onSave: (SavedHost) -> Unit,
    onDelete: (String) -> Unit,
    onTailnet: () -> Unit,
    alerts: Boolean,
    onAlerts: (Boolean) -> Unit,
    /** The Notifications page's switches: which kinds of alert are on. */
    alertPrefs: AlertPrefs = AlertPrefs(),
    onAlertPrefs: (AlertPrefs) -> Unit = {},
    /** Each host's background choices, on its page under Settings › Background and notifications. */
    background: BackgroundHosts = BackgroundHosts(),
    onHostBackground: (String, HostBackground) -> Unit = { _, _ -> },
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
    /** Opens Import hosts: a host list, or a connected Rex's hosts. */
    onImportHosts: () -> Unit = {},
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
    val sheets = rememberSaveable(saver = HomeSheets.Saver) { HomeSheets() }
    // Sessions of a host that is not live, opened on this screen to see they are there: their
    // tabs are not known, so they start folded, and the choice is not kept past the screen.
    var openCached by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var activeOnly by rememberSaveable { mutableStateOf(false) }
    val ops = HomeOps(
        connect = onConnect,
        retry = onRetry,
        cancel = onDisconnect,
        disconnect = onDisconnect,
        newSession = onNewSession,
        newTab = onNewTab,
        openSession = onOpenSession,
        openTab = onOpenTab,
        openPane = onOpenPane,
        manage = onManage,
        foldHost = onHostOpen,
        foldSession = { hostId, sessionId, folded ->
            val live = lives[hostId]?.state
            if (live != null && (live.phase == Phase.Live || live.views.containsKey(sessionId))) {
                onFoldSession(hostId, sessionId, folded)
            } else {
                val key = HomeFolds.sessionKey(hostId, sessionId)
                openCached = if (folded) openCached - key else openCached + key
            }
        },
        foldSessions = { hostId, ids, folded ->
            if (lives[hostId]?.state?.phase == Phase.Live) {
                onFoldSessions(hostId, ids, folded)
            } else {
                val keys = ids.map { HomeFolds.sessionKey(hostId, it) }.toSet()
                openCached = if (folded) openCached - keys else openCached + keys
            }
        },
        editHost = { editingId = it },
        copyAddress = { host ->
            clipboardSet(context, if (host.viaSsh) "${host.sshUser}@${host.host}:${host.port}" else "${host.host}:${host.port}")
            toast(context, context.getString(R.string.home_address_copied))
        },
        tailnetDetails = { tailnetDetails = true },
    )
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
            onImport = onImportHosts,
        )
    } else {
        val q = query.trim().lowercase()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // The eye shows every connected host's activity, as Rex's Activity does for its own. A host
        // the phone is not connected to has none to show: only a live connection hears its programs.
        val liveHosts = hosts.mapNotNull { host -> lives[host.id]?.takeIf { it.state.phase == Phase.Live } }
        val activity = if (activeOnly) {
            liveHosts.map { it to activityShown(it.state, query) }.filter { it.second.isNotEmpty() }
        } else {
            emptyList()
        }
        val tree = TreeEnv(
            hosts = hosts,
            lives = lives,
            folds = folds,
            openCached = openCached,
            query = q,
            searching = searchOpen,
            opening = opening,
            detailHostId = connected?.id?.takeIf { detail },
            cachedSessions = cachedSessions,
            ops = ops,
            sheets = sheets,
            network = tailnetName(tailnet.backend),
            stage = tailnetStage,
            authUrl = tailnetStatus.authUrl,
            onSignIn = onSignIn,
            onDevices = { tailnetDetails = true },
            onTailnet = onTailnet,
        )
        val treeRows = tree.rows()
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
                        if (activity.isEmpty()) {
                            item(key = "activity-empty") { ActivityEmpty(live = liveHosts.isNotEmpty(), searching = query.isNotBlank()) }
                        } else {
                            // With one host the groups are the host's; with more, each host's under its name.
                            activity.forEach { (live, sections) ->
                                if (liveHosts.size > 1) {
                                    item(key = "activity-host-${live.target.id}") { ActivityHostCaption(live.target.label) }
                                }
                                activityItems(sections, live.state, keyPrefix = live.target.id) { item ->
                                    onOpenPane(live.target, item.sessionId, item.window.id, item.block.id)
                                }
                            }
                        }
                    } else {
                        homeTree(tree, treeRows)
                    }
                    // The list is the only place a host is added from, so the way to add one is at
                    // the end of it - above it, there is nothing until a host exists.
                    if (!searchOpen && !activeOnly && hosts.isNotEmpty()) {
                        item(key = "add-host") {
                            Spacer(Modifier.height(12.dp))
                            AddHostRow(onClick = { creating = true })
                            ImportHostsRow(onClick = onImportHosts)
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
            onProbe = { connected?.let { onProbePath(it.id) } },
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

    HomeSheetsHost(sheets, hosts, lives, folds, cachedSessions, ops)

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
            onImportHosts = {
                settings = false
                onImportHosts()
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
            hosts = hosts,
            background = background,
            onHostBackground = onHostBackground,
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
    /** Import hosts, and export them. */
    onImportHosts: () -> Unit = {},
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
            // Many hosts at once, from a list or a connected Rex: the + under the list adds one.
            SheetItem(stringResource(R.string.import_hosts_title), icon = R.drawable.ic_host, opens = true, onClick = onImportHosts)
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
    onImport: () -> Unit = {},
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
        ) {
            RouteButton(stringResource(R.string.action_add_ssh_host)) { onAdd(SSH_PREFILL) }
            RouteButton(stringResource(R.string.import_hosts_title), onImport)
        }
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
                // An imported host's other addresses: for the person to see, and to copy into the address.
                if (!initial?.fallbacks.isNullOrEmpty()) {
                    Text(
                        stringResource(R.string.host_fallbacks, initial!!.fallbacks.joinToString(", ")),
                        color = Colors.muted,
                        fontSize = TextStyles.label,
                        lineHeight = labelLeading,
                    )
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
    fallbacks = initial?.fallbacks.orEmpty(),
)
