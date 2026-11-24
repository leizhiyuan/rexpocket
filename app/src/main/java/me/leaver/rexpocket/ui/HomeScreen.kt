package me.leaver.rexpocket.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import me.leaver.rexpocket.alerts.Alerts
import me.leaver.rexpocket.core.QuickReplies
import me.leaver.rexpocket.core.promptStamp
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalFocusManager
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
import me.leaver.rexpocket.core.Connectable
import me.leaver.rexpocket.core.ConnectableKind
import me.leaver.rexpocket.core.TailnetPolicy
import me.leaver.rexpocket.core.linkState
import me.leaver.rexpocket.core.TailnetPath
import me.leaver.rexpocket.core.TailnetStage
import me.leaver.rexpocket.core.ConnectProgress
import me.leaver.rexpocket.core.Trouble
import me.leaver.rexpocket.core.UpdateChannel
import me.leaver.rexpocket.core.WindowInfo
import me.leaver.rexpocket.data.HostStore
import me.leaver.rexpocket.data.HomeFolds
import me.leaver.rexpocket.data.HomeMarks
import me.leaver.rexpocket.core.markCovers
import me.leaver.rexpocket.core.ActivityGroup
import me.leaver.rexpocket.core.activityBySession
import androidx.compose.material3.SnackbarDuration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
    /** The joined tailnet as its chip, sheet and page show it; see docs/design/tailnet-control.md. */
    tailnetView: TailnetView = TailnetView(id = tailnet.backend.storageKey, name = tailnet.backend.displayName, backend = tailnet.backend, joined = tailnet.enabled),
    /** Connects the tailnet by hand, and the hosts given with it (a Reconnect). */
    onTailnetConnect: (List<SavedHost>) -> Unit = {},
    /** Disconnects the tailnet by hand; gives back the hosts that went with it. */
    onTailnetDisconnect: () -> List<SavedHost> = { emptyList() },
    onTailnetPolicy: (String, TailnetPolicy) -> Unit = { _, _ -> },
    /** A page or a confirm asked for from outside the home (a tile, a notification), once. */
    request: HomeRequest? = null,
    onRequestHandled: () -> Unit = {},
    opening: Opening?,
    cachedSessions: (String) -> List<ListedSession>,
    /** Which hosts and sessions are folded: the view model's, so they outlive this screen. */
    folds: HomeFolds = HomeFolds(),
    /** Opens or folds a host's sessions: the person's choice, kept for that host from then on. */
    onHostOpen: (hostId: String, open: Boolean) -> Unit = { _, _ -> },
    onFoldSession: (hostId: String, sessionId: String, folded: Boolean) -> Unit = { _, _, _ -> },
    onFoldSessions: (hostId: String, sessionIds: List<String>, folded: Boolean) -> Unit = { _, _, _ -> },
    /** Every fold at once: Collapse all and Expand all, and their Undo. */
    onSetFolds: (HomeFolds) -> Unit = {},
    /** Which list shows: every host's tree, Needs you or Running. */
    filter: HomeFilter = HomeFilter.All,
    onHomeFilter: (HomeFilter) -> Unit = {},
    /** The Activity view's arrangement and Include idle. */
    activityPrefs: ActivityPrefs = ActivityPrefs(),
    onActivityPrefs: (ActivityPrefs) -> Unit = {},
    /** Which done and failed panes were seen, by host and pane ([HomeMarks.key]). */
    seenMarks: Map<String, String> = emptyMap(),
    /** Marks panes seen; gives back the marks they had, for the Undo. */
    onMarkSeen: (hostId: String, blockIds: List<String>) -> Map<String, String?> = { _, _ -> emptyMap() },
    onRestoreSeen: (Map<String, String?>) -> Unit = {},
    /** A Needs you row's Reply and Interrupt, on the pane's host. */
    onReply: (hostId: String, sessionId: String, blockId: String, text: String) -> Unit = { _, _, _, _ -> },
    onInterrupt: (hostId: String, sessionId: String, blockId: String) -> Unit = { _, _, _ -> },
    /** A Needs you row's Approve or Deny, for the prompt [stamp] names (see [promptStamp]). */
    onAnswer: (hostId: String, sessionId: String, windowId: String, blockId: String, stamp: String, approve: Boolean) -> Unit = { _, _, _, _, _, _ -> },
    /** The composer's send from a Needs you row's Reply: the text, then Return when [enter]. */
    onSendPrompt: (hostId: String, sessionId: String, blockId: String, text: String, enter: Boolean) -> Unit = { h, s, b, t, _ -> onReply(h, s, b, t) },
    /** Settings › Quick replies, and Reply's and the composer's chips. */
    quickReplies: QuickReplies = QuickReplies(),
    onQuickReplies: (QuickReplies) -> Unit = {},
    /** The composer's drafts, by host and pane. */
    drafts: PromptDrafts = remember { PromptDrafts() },
    /** Moves a host in the phone's own order. */
    onMoveHost: (hostId: String, to: Int) -> Unit = { _, _ -> },
    /** Removes a host; gives back what Undo needs. */
    onRemoveHost: (hostId: String) -> RemovedHost? = { null },
    onRestoreHost: (RemovedHost) -> Unit = {},
    /** What the home says of its own accord: an action that failed, a session ended elsewhere. */
    notices: Flow<HomeNotice> = emptyFlow(),
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
    /** Settings › Backup: export and import every setting. */
    onBackup: () -> Unit = {},
    /** The host dialog's Test connection; null leaves it out. */
    hostTester: HostTester? = null,
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
    // A host's or a tailnet's page (Connectable.key), and whether Back goes to Notifications.
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var pageFromNotifications by rememberSaveable { mutableStateOf(false) }
    var confirmTailnetOff by rememberSaveable { mutableStateOf(false) }
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
    var homeMenu by rememberSaveable { mutableStateOf(false) }
    var replyTo by remember { mutableStateOf<ReplyTarget?>(null) }
    // Reply's Compose: the same pane, in the composer, with what Reply had so far.
    var composeTo by remember { mutableStateOf<ReplyTarget?>(null) }
    var quickRepliesOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val snackScope = rememberCoroutineScope()
    // One message at a time, the newest: an Undo belongs to what was just done.
    fun snack(message: String, action: String? = null, onAction: () -> Unit = {}) {
        snackScope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(message, action, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) onAction()
        }
    }
    // A tailnet's Disconnect, as a host's: at once with nothing on it, with a confirm naming the
    // hosts that go with it otherwise, and a snackbar with Reconnect that brings them back too.
    val tailnetOff: (confirmed: Boolean) -> Unit = { confirmed ->
        if (!confirmed && tailnetView.hosts.isNotEmpty()) {
            confirmTailnetOff = true
        } else {
            confirmTailnetOff = false
            val dropped = onTailnetDisconnect()
            snack(context.getString(R.string.home_disconnected, tailnetView.name), context.getString(R.string.action_reconnect)) { onTailnetConnect(dropped) }
        }
    }
    val tailnetOn: () -> Unit = { onTailnetConnect(emptyList()) }
    LaunchedEffect(request) {
        when (request) {
            is HomeRequest.Page -> {
                pageFromNotifications = false
                page = request.key
            }
            HomeRequest.ConfirmTailnetOff -> if (tailnetView.hosts.isNotEmpty()) confirmTailnetOff = true
            null -> return@LaunchedEffect
        }
        onRequestHandled()
    }
    val ops = HomeOps(
        connect = onConnect,
        retry = onRetry,
        cancel = onDisconnect,
        disconnect = { hostId ->
            onDisconnect(hostId)
            hosts.firstOrNull { it.id == hostId }?.let { host ->
                snack(context.getString(R.string.home_disconnected, host.label), context.getString(R.string.action_reconnect)) { onConnect(host) }
            }
        },
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
        page = { hostId ->
            pageFromNotifications = false
            page = Connectable.host(hostId).key
        },
        seen = { hostId, block, record -> markCovers(seenMarks[HomeMarks.key(hostId, block)], record) },
        markOf = { hostId, block -> seenMarks[HomeMarks.key(hostId, block)] },
        moveHost = onMoveHost,
        removeHost = { hostId ->
            onRemoveHost(hostId)?.let { removed ->
                snack(context.getString(R.string.home_removed_host, removed.host.label), context.getString(R.string.action_undo)) { onRestoreHost(removed) }
            }
        },
        markSeen = { hostId, blocks ->
            val before = onMarkSeen(hostId, blocks)
            snack(context.getString(R.string.home_marked_seen), context.getString(R.string.action_undo)) { onRestoreSeen(before) }
        },
    )
    var picking by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val closeSearch: () -> Unit = {
        searchOpen = false
        query = ""
        focusManager.clearFocus()
        keyboard?.hide()
    }
    // Back closes the search first, as the platform's search does.
    BackHandler(enabled = searchOpen || query.isNotEmpty(), onBack = closeSearch)
    // + makes a session where it plainly goes: the one host connected. Otherwise it asks where.
    val newSession: () -> Unit = {
        val connectedNow = hosts.filter { it.id in lives }
        if (connectedNow.size == 1) onNewSession(connectedNow.single()) else picking = true
    }

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
        val tree = TreeEnv(
            hosts = hosts,
            lives = lives,
            folds = folds,
            openCached = openCached,
            query = q,
            searching = q.isNotEmpty(),
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
        // The filters cover every connected host; a host the phone is not connected to has nothing
        // to list, since only a live connection hears its programs.
        val connectedHosts = hosts.filter { it.id in lives }
        fun seenOn(hostId: String): Seen = { block, record -> ops.seen(hostId, block, record) }
        val needsYou = connectedHosts.sumOf { h -> filterSections(lives.getValue(h.id).state, HomeFilter.NeedsYou, seenOn(h.id)).sumOf { it.items.size } }
        val running = connectedHosts.sumOf { h -> filterSections(lives.getValue(h.id).state, HomeFilter.Running, seenOn(h.id)).sumOf { it.items.size } }
        val filtered = if (filter == HomeFilter.All) {
            emptyMap()
        } else {
            connectedHosts.associate { h ->
                val st = lives.getValue(h.id).state
                h.id to searched(filterSections(st, filter, seenOn(h.id), activityPrefs), st, query)
            }
        }
        Column(Modifier.fillMaxSize().background(Colors.bg).statusBarsPadding().imePadding()) {
            // The tailnet's card while it is on its way or needs the person; a chip once it is on.
            val tailnetHosts = hosts.count { it.viaTailscale }
            val tailnetShown = tailnet.enabled || tailnetHosts > 0
            // Quiet too once disconnected by hand or waiting for a host: nothing is wrong then.
            val tailnetQuiet = tailnetShown && (tailnetStage is TailnetStage.Connected || tailnetStage is TailnetStage.Disconnected)
            Row(Modifier.fillMaxWidth().padding(start = PAGE, end = PAGE - 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChips(filter, needsYou, running, onHomeFilter, Modifier.weight(1f))
                if (tailnetQuiet) {
                    val network = tailnetName(tailnet.backend)
                    TailnetChip(
                        network = network,
                        said = network + " · " + tailnetStageText(tailnetStage, tailnet.backend),
                        healthy = tailnetStatus.health.isEmpty(),
                        on = tailnetStage is TailnetStage.Connected,
                        onClick = { tailnetDetails = true },
                    )
                }
                RoundButton(R.drawable.ic_more, stringResource(R.string.home_menu), { homeMenu = true }, tint = Colors.muted, size = 48.dp)
            }
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = PAGE,
                        end = PAGE,
                        top = 8.dp,
                        bottom = 16.dp,
                    ),
                ) {
                    // The tailnet's card leads the list it carries while it needs a look.
                    if (filter == HomeFilter.All && q.isEmpty() && tailnetShown && !tailnetQuiet) {
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
                    if (filter != HomeFilter.All) {
                        // What a row's buttons do, on its host: Reply, Interrupt, Approve and Deny
                        // where something may wait on the person (Needs you and Activity).
                        val answers = filter == HomeFilter.NeedsYou || filter == HomeFilter.Activity
                        fun opsFor(host: SavedHost): AttentionOps {
                            val ref = HostRef(host, lives[host.id])
                            return AttentionOps(
                                open = { item -> onOpenPane(host, item.sessionId, item.window.id, item.block.id) },
                                manage = { item -> sheets.sheet = ManageTarget.Tab(host.id, item.sessionId, item.window.id) },
                                actionsOf = { item -> tabActions(HostRef(host, lives[host.id]), item.sessionId, item.window.id, ops, sheets).talkBack() },
                                reply = if (answers) {
                                    { item -> replyTo = ReplyTarget(host.id, item.sessionId, item.block.id, paneTitle(item.block, item.record, ref.state.programs[item.block.id])) }
                                } else {
                                    null
                                },
                                interrupt = if (answers) {
                                    { item -> onInterrupt(host.id, item.sessionId, item.block.id) }
                                } else {
                                    null
                                },
                                answer = if (answers) {
                                    { item, approve -> onAnswer(host.id, item.sessionId, item.window.id, item.block.id, promptStamp(item.record), approve) }
                                } else {
                                    null
                                },
                            )
                        }
                        var shown = 0
                        if (filter == HomeFilter.Activity) {
                            item(key = "activity-controls") { ActivityControls(activityPrefs, onActivityPrefs) }
                        }
                        if (filter == HomeFilter.Activity && !activityPrefs.bySession) {
                            // By priority: one list across every connected host, group by group, as
                            // Rex's Activity; a row names its host when more than one is connected.
                            val named = connectedHosts.size > 1
                            for (group in ActivityGroup.entries) {
                                val rows = connectedHosts.flatMap { host ->
                                    val ops = opsFor(host)
                                    val st = lives.getValue(host.id).state
                                    filtered[host.id].orEmpty().filter { it.group == group }.flatMap { it.items }
                                        .map { HostedItem(it, st, ops, host.label.takeIf { named }) }
                                }
                                if (rows.isEmpty()) continue
                                shown++
                                item(key = "activity-group-${group.name}") {
                                    ActivityCaption("${activityGroupName(group)} · ${rows.size}", activityTint(group), Modifier.testTag(activityGroupTag(group)))
                                }
                                hostedItems("activity-rows-${group.name}", rows)
                            }
                        } else {
                            for (host in connectedHosts) {
                                val sections = filtered[host.id].orEmpty()
                                if (sections.isEmpty()) continue
                                val ref = HostRef(host, lives[host.id])
                                val first = shown++ == 0
                                item(key = "filter-host-${host.id}") {
                                    if (!first) Spacer(Modifier.height(12.dp))
                                    HostItem(tree, ref, treeRows[host.id].orEmpty(), expanded = true, foldable = false)
                                }
                                if (filter == HomeFilter.Activity) {
                                    // By session: each session's tabs, in Rex's order of its tabs.
                                    val inList = sections.flatMap { it.items }.mapTo(HashSet()) { it.window.id }
                                    val ops = opsFor(host)
                                    for (session in activityBySession(ref.state, seenOn(host.id), activityPrefs.idle(seenOn(host.id)))) {
                                        val rows = session.items.filter { it.window.id in inList }
                                        if (rows.isEmpty()) continue
                                        item(key = "activity-session-${host.id}-${session.sessionId}") {
                                            ActivityCaption(session.label, Colors.muted, Modifier.testTag(activitySessionTag(session.sessionId)))
                                        }
                                        hostedItems("activity-session-rows-${host.id}-${session.sessionId}", rows.map { HostedItem(it, ref.state, ops, null) })
                                    }
                                } else {
                                    attentionItems(sections, ref.state, keyPrefix = host.id, ops = opsFor(host))
                                }
                            }
                        }
                        if (shown == 0) {
                            item(key = "filter-empty") { ActivityEmpty(filter, live = connectedHosts.isNotEmpty(), searching = q.isNotEmpty()) }
                        }
                        // What the filter cannot see: the hosts that are not connected, each with its way in.
                        for (host in hosts.filter { it.id !in lives }) {
                            item(key = "filter-off-${host.id}") {
                                NoteRow(
                                    stringResource(R.string.home_not_connected_line, host.label),
                                    stringResource(R.string.action_connect) to { onConnect(host) },
                                    card = false,
                                )
                            }
                        }
                    } else {
                        homeTree(tree, treeRows)
                    }
                    // The list is the only place a host is added from, so the way to add one is at
                    // the end of it - above it, there is nothing until a host exists.
                    if (q.isEmpty() && filter == HomeFilter.All && hosts.isNotEmpty()) {
                        item(key = "add-host") {
                            Spacer(Modifier.height(12.dp))
                            AddHostRow(onClick = { creating = true })
                            ImportHostsRow(onClick = onImportHosts)
                        }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
            }
            HomeBottomBar(
                query = query,
                onQuery = { query = it },
                onFocus = { focused -> searchOpen = focused || query.isNotEmpty() },
                onSettings = { settings = true },
                onNew = newSession,
            )
        }
    }

    if (picking) {
        RowActionsSheet(stringResource(R.string.session_new), newSessionPicker(hosts, lives) { onNewSession(it) }) { picking = false }
    }

    if (homeMenu) {
        RowActionsSheet(
            null,
            listOf(
                ActionGroup(
                    listOf(
                        RowAction(stringResource(R.string.home_expand_all), R.drawable.ic_expand) {
                            val before = folds
                            onSetFolds(hosts.fold(folds) { f, h -> f.withHost(h.id, true) }.copy(sessions = emptySet()))
                            snack(context.getString(R.string.home_expanded_all), context.getString(R.string.action_undo)) { onSetFolds(before) }
                        },
                        RowAction(stringResource(R.string.home_collapse_all), R.drawable.ic_expand) {
                            val before = folds
                            onSetFolds(hosts.fold(folds) { f, h -> f.withHost(h.id, false) })
                            snack(context.getString(R.string.home_collapsed_all), context.getString(R.string.action_undo)) { onSetFolds(before) }
                        },
                    ),
                ),
                ActionGroup(
                    listOf(
                        RowAction(stringResource(R.string.action_add_host), R.drawable.ic_add) { creating = true },
                        RowAction(stringResource(R.string.import_hosts_title), R.drawable.ic_host) { onImportHosts() },
                        RowAction(stringResource(R.string.tailnet_title), R.drawable.ic_tailnet) { onTailnet() },
                    ),
                ),
                // The tailnet by hand, as a host's sheet has its host: Connect or Disconnect.
                ActionGroup(
                    listOfNotNull(
                        if (tailnetStage is TailnetStage.Disconnected) {
                            RowAction(stringResource(R.string.tailnet_connect_item, tailnetView.name), R.drawable.ic_tailnet, run = tailnetOn)
                        } else {
                            RowAction(stringResource(R.string.tailnet_disconnect_item, tailnetView.name), R.drawable.ic_disconnect, danger = true) { tailnetOff(false) }
                        }.takeIf { tailnet.enabled },
                    ),
                ),
            ),
        ) { homeMenu = false }
    }

    replyTo?.let { target ->
        ReplyDialog(
            title = stringResource(R.string.home_reply_title, target.title),
            onDismiss = { replyTo = null },
            quickReplies = quickReplies.items,
            onCompose = { text ->
                val key = PromptDrafts.key(target.hostId, target.blockId)
                if (text.isNotBlank()) drafts[key] = joinReply(drafts[key], text)
                replyTo = null
                composeTo = target
            },
        ) { text ->
            replyTo = null
            onReply(target.hostId, target.sessionId, target.blockId, text)
        }
    }

    composeTo?.let { target ->
        val key = PromptDrafts.key(target.hostId, target.blockId)
        PromptComposer(
            target = target.title,
            draft = drafts[key],
            onDraft = { drafts[key] = it },
            quickReplies = quickReplies.items,
            onSend = { text, enter -> onSendPrompt(target.hostId, target.sessionId, target.blockId, text, enter) },
            onDismiss = { composeTo = null },
        )
    }

    if (quickRepliesOpen) {
        QuickRepliesSheet(quickReplies, onQuickReplies, onDismiss = { quickRepliesOpen = false })
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
            // Remove host is in the host's sheet, with a confirm and an Undo.
            onDelete = null,
            onSave = {
                onSave(it)
                done()
            },
            onJoinTailnet = {
                done()
                onTailnet()
            },
            tester = hostTester,
        )
    }

    if (confirmTailnetOff) {
        TailnetDisconnectDialog(tailnetView, onDismiss = { confirmTailnetOff = false }) { tailnetOff(true) }
    }

    if (tailnetDetails) {
        TailnetSheet(
            backend = tailnet.backend,
            stage = tailnetStage,
            actions = connectionActions(
                tailnetStage.linkState(), ConnectableKind.Tailnet,
                onConnect = tailnetOn, onDisconnect = { tailnetOff(false) }, onRetry = onTailnetRetry,
            ).takeIf { tailnet.enabled }.orEmpty(),
            supporting = tailnetSupporting(tailnetView, tailnetStage),
            onPage = {
                pageFromNotifications = false
                page = Connectable.tailnet(tailnetView.id).key
            },
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

    HomeSheetsHost(sheets, hosts, lives, folds, cachedSessions, ops) { session, host ->
        snack(context.getString(R.string.home_ended_elsewhere, session, host))
    }
    LaunchedEffect(notices) {
        notices.collect { notice ->
            when (notice) {
                is HomeNotice.Ended -> snack(context.getString(R.string.home_ended_elsewhere, notice.session, notice.host))
                // Nothing was typed: the pane opens, so the person sees what it asks now. A toast,
                // as the home it would be a snackbar of goes as the terminal comes up.
                is HomeNotice.PromptChanged -> {
                    toast(context, context.getString(R.string.home_prompt_changed))
                    hosts.firstOrNull { it.id == notice.hostId }?.let { onOpenPane(it, notice.sessionId, notice.windowId, notice.blockId) }
                }
                is HomeNotice.NotAnswered -> snack(context.getString(R.string.home_answer_failed))
                is HomeNotice.Failed -> snack(
                    context.getString(failedText(notice.action), notice.detail.ifBlank { context.getString(R.string.status_failed) }),
                    context.getString(R.string.action_retry),
                ) { onManage(notice.hostId, notice.action) }
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
                // A joined tailnet opens its sheet: its state, Connect or Disconnect, its page.
                if (tailnet.enabled) tailnetDetails = true else onTailnet()
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
            quickReplies = quickRepliesDetail(quickReplies),
            onQuickReplies = {
                settings = false
                quickRepliesOpen = true
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
            onBackup = {
                settings = false
                onBackup()
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
            tailnets = listOf(tailnetView).filter { it.joined },
            onPage = {
                notifications = false
                pageFromNotifications = true
                page = it.key
            },
        )
    }

    val pageOf = Connectable.parse(page)
    val closePage = { page = null }
    val pageBack: (() -> Unit)? = if (pageFromNotifications) ({
        page = null
        notifications = true
    }) else null
    when (pageOf?.kind) {
        ConnectableKind.Host -> hosts.firstOrNull { it.id == pageOf.id }?.let { host ->
            val live = lives[host.id]
            HostPage(
                host = host,
                live = live,
                choices = background.of(host.id),
                defaults = alertPrefs,
                backgroundMode = alerts,
                onChoices = { onHostBackground(host.id, it) },
                onConnect = { onConnect(host) },
                onDisconnect = { ops.disconnect(host.id) },
                onRetry = { onRetry(host.id) },
                onBack = pageBack,
                onDismiss = closePage,
                tile = { TileGroup(Connectable.host(host.id), host.label.ifBlank { host.host }) },
            )
        } ?: LaunchedEffect(page) { page = null }
        ConnectableKind.Tailnet -> TailnetPage(
            view = tailnetView,
            stage = tailnetStage,
            backgroundMode = alerts,
            onPolicy = { onTailnetPolicy(tailnetView.id, it) },
            onConnect = tailnetOn,
            onDisconnect = { tailnetOff(false) },
            onRetry = onTailnetRetry,
            onBack = pageBack,
            onDismiss = closePage,
            tile = { TileGroup(Connectable.tailnet(tailnetView.id), tailnetView.name) },
        )
        null -> Unit
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
    /** Settings › Backup: every setting to one file, and back. */
    onBackup: () -> Unit = {},
    /** The Quick replies row's detail, and the row's tap. */
    quickReplies: String? = null,
    onQuickReplies: () -> Unit = {},
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
            // What agents are told most, in one tap from a notification, the key bar and the palette.
            SheetItem(stringResource(R.string.quick_replies_title), icon = R.drawable.ic_return, detail = quickReplies, opens = true, onClick = onQuickReplies)
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
        // Everything above, and the hosts, to one file for another phone, and back.
        Group(stringResource(R.string.settings_group_backup)) {
            SheetItem(stringResource(R.string.backup_title), icon = R.drawable.ic_backup, opens = true, onClick = onBackup)
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
    /** Test connection; null leaves the button out. */
    tester: HostTester? = null,
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
    // A host key the person trusted in Test connection, for this account, machine and port only:
    // Save keeps it as the pinned key. Nothing is written before Save.
    var trusted by remember(parsed?.host, parsed?.port, user) { mutableStateOf<String?>(null) }
    val pinned = trusted ?: if (sameSshHost) initial.sshHostKey else ""
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
                if (tester != null) {
                    val report by tester.report.collectAsState()
                    DisposableEffect(tester) { onDispose { tester.clear() } }
                    // A report is for what was typed when it ran: an edit since makes it stale.
                    val target = parsed?.let { savedHost(viaTailscale = it.viaTailscale)?.checkTarget() }
                    LaunchedEffect(target) { if (tester.tested != null && tester.tested != target) tester.clear() }
                    HostCheckPanel(
                        report = report,
                        running = report?.finished == false,
                        enabled = canSave && target != null,
                        pinned = (if (sameSshHost) initial?.sshHostKey else null)?.ifBlank { null },
                        trusted = trusted,
                        onRun = { target?.let(tester::start) },
                        onCancel = tester::cancel,
                        onTrust = { key ->
                            trusted = key
                            target?.let { tester.start(it.copy(hostKey = key)) }
                        },
                    )
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
