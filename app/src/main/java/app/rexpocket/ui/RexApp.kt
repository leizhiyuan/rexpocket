package app.rexpocket.ui

import android.app.Application
import android.util.Log
import app.rexpocket.BuildConfig
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.toArgb
import android.widget.EditText
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.text.TextWatcher
import android.text.InputType
import android.text.Editable
import android.os.SystemClock
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.input.pointer.PointerEventType
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.rexpocket.core.BlockInfo
import app.rexpocket.core.Endpoint
import app.rexpocket.core.HostAddress
import app.rexpocket.core.HostAddressParser
import app.rexpocket.core.Phase
import app.rexpocket.core.PocketSession
import app.rexpocket.core.PocketState
import app.rexpocket.core.ProgramRecord
import app.rexpocket.core.RexClient
import app.rexpocket.core.SessionView
import app.rexpocket.core.WindowInfo
import app.rexpocket.data.HostStore
import app.rexpocket.R
import app.rexpocket.data.SavedHost
import app.rexpocket.data.TailscaleSettings
import app.rexpocket.net.TailscaleBox
import app.rexpocket.net.TailscaleConfig
import app.rexpocket.net.TailscaleStatus
import io.github.sagernet.libghostty.GhosttyTerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF10120F)
private val Card = Color(0xFF1C1F1A)
private val Ink = Color(0xFFE7E9E4)
private val Muted = Color(0xFF8E9488)
private val Accent = Color(0xFF00FF41)
private val Amber = Color(0xFFF0C674)
private val Danger = Color(0xFFFF5D5D)
private val Line = Color(0xFF2C3129)

/** One pane of the session the user opened, offered before the terminal appears. */
sealed interface Route {
    data object Home : Route
    data object Picker : Route
    data object Term : Route
}

class RexViewModel(app: Application) : AndroidViewModel(app) {
    private val store = HostStore(app)
    private val tailscaleBox = TailscaleBox(app)
    val hosts = MutableStateFlow(store.list())
    val tailscale = MutableStateFlow(store.tailscale())
    val tailscaleStatus: StateFlow<TailscaleStatus> = tailscaleBox.status
    val route = MutableStateFlow<Route>(Route.Home)
    private val _pocket = MutableStateFlow(PocketState())
    val pocket: StateFlow<PocketState> = _pocket
    val fontScale = MutableStateFlow(store.fontScale())
    var host: SavedHost? = null
        private set

    private var session: PocketSession? = null
    private var sessionJob: Job? = null
    private var watch: Job? = null
    private var connectJob: Job? = null
    private var tailnetJob: Job? = null
    private val _tailnetError = MutableStateFlow<String?>(null)
    val tailnetError: StateFlow<String?> = _tailnetError

    override fun onCleared() {
        tailscaleBox.stopAsync()
        super.onCleared()
    }

    /**
     * Joins the tailnet under [hostname] (blank means the device's own name). The node starts here,
     * not on the first connect, so the app can hand out the login link while the user is looking
     * at the Tailscale row; a node that is already logged in just comes back up.
     */
    fun joinTailnet(hostname: String) {
        store.setTailscale(TailscaleSettings(enabled = true, hostname = hostname.trim()))
        val next = store.tailscale()
        val changed = next != tailscale.value
        tailscale.value = next
        if (changed) tailscaleBox.stopAsync()
        startTailnet(next)
    }

    /** Brings the node up for [settings]; the login it already has makes this a plain restart. */
    private fun startTailnet(settings: TailscaleSettings) {
        tailnetJob?.cancel()
        _tailnetError.value = null
        tailnetJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { tailscaleBox.start(TailscaleConfig(authKey = "", hostname = settings.hostname)) }
                    .onFailure { _tailnetError.value = it.message ?: "Tailscale could not start" }
            }
        }
    }

    /** Leaves the tailnet: the node stops and its login stays in the app for the next join. */
    fun leaveTailnet() {
        // The name is kept: rejoining later should not ask for it again.
        store.setTailscale(TailscaleSettings(enabled = false, hostname = tailscale.value.hostname))
        tailscale.value = store.tailscale()
        tailnetJob?.cancel()
        tailscaleBox.stopAsync()
    }

    fun saveHost(host: SavedHost) {
        store.upsert(host)
        hosts.value = store.list()
    }

    fun deleteHost(id: String) {
        store.delete(id)
        hosts.value = store.list()
    }

    /**
     * Connects and, when [resume] is set, goes back to the session and pane the app was showing
     * before the process died. A plain connect stops at the session list.
     */
    fun connect(target: SavedHost, resume: Boolean = false) {
        disconnectQuiet()
        host = target
        store.setLastHost(target.id)
        val previousSession = store.lastSession(target.id)
        route.value = Route.Picker
        val settings = store.tailscale()
        if (target.viaTailscale && !settings.enabled) {
            // The host is saved either way; without a joined node there is no route to it.
            _pocket.value = PocketState(
                phase = Phase.Failed,
                endpointLabel = "${target.host}:${target.port}",
                error = getApplication<Application>().getString(R.string.error_join_tailnet_first),
            )
            return
        }
        val viaTailscale = target.viaTailscale
        if (BuildConfig.DEBUG) {
            Log.i(CONN_TAG, "connect ${target.host}:${target.port} tailnet=$viaTailscale plaintext=${target.plaintext}")
        }
        connectJob = viewModelScope.launch {
            // A host on the phone's own network is dialed directly, and the tailnet node stays as it
            // is: the home screen says the phone is in a tailnet, so that must not depend on which
            // host happens to be open.
            // The node has to be up before Rex is dialed, because the Rex connection itself is
            // what goes through it. Joining the tailnet takes a moment on the first connect.
            val socksPort = if (viaTailscale) {
                _pocket.value = PocketState(
                    phase = Phase.Connecting,
                    endpointLabel = "${target.host}:${target.port}",
                )
                val port = try {
                    withContext(Dispatchers.IO) {
                        tailscaleBox.start(TailscaleConfig(authKey = "", hostname = settings.hostname))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _pocket.value = PocketState(
                        phase = Phase.Failed,
                        endpointLabel = "${target.host}:${target.port}",
                        error = e.message,
                    )
                    return@launch
                }
                // The node reports Running only once its WireGuard session is up; dialing before
                // that just gets a SOCKS failure, which looks like a broken tunnel.
                withTimeoutOrNull(NODE_READY_MS) {
                    tailscaleBox.status.first {
                        it.backendState in setOf("Running", "NeedsLogin", "NeedsMachineAuth", "Stopped")
                    }
                }
                port
            } else {
                0
            }
            val s = PocketSession(Endpoint(target.host, target.port, target.plaintext)) {
                RexClient.forSocksPort(socksPort)
            }
            session = s
            sessionJob = s.start(viewModelScope, previousSession)
            var saved: Pair<String, String>? = null
            var resumed = false
            watch = viewModelScope.launch {
                var lastPhase: Phase? = null
                var lastError: String? = null
                s.state.collect { st ->
                    if (BuildConfig.DEBUG && (st.phase != lastPhase || st.error != lastError)) {
                        Log.i(CONN_TAG, "phase=${st.phase} error=${st.error ?: "-"}")
                        lastPhase = st.phase
                        lastError = st.error
                    }
                    _pocket.value = st
                    if (st.activeSessionId != null) store.setLastSession(target.id, st.activeSessionId)
                    // Only a pane that was on the terminal screen counts. Connecting already selects
                    // a session, and its Rex focus must not replace the pane remembered for it.
                    val sid = st.activeSessionId
                    val shown = st.view?.takeIf { it.sessionId == sid }?.focusedBlock()?.id
                    if (route.value == Route.Term && sid != null && shown != null && saved != sid to shown) {
                        saved = sid to shown
                        store.setLastPane(target.id, sid, shown, st.sessions.map { it.id })
                    }
                    if (!resume || resumed || st.phase != Phase.Live || st.sessions.isEmpty()) return@collect
                    resumed = true
                    resumeInto(s, previousSession)
                }
            }
        }
    }

    /** Reopens the remembered session on its remembered pane; stays on the list if it is gone. */
    private suspend fun resumeInto(s: PocketSession, sessionId: String?) {
        val target = sessionId?.takeIf { id -> s.state.value.sessions.any { it.id == id } } ?: return
        try {
            s.openSession(target, rememberedPane(target))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        route.value = Route.Term
    }

    private fun rememberedPane(sessionId: String): String? = host?.let { store.lastPane(it.id, sessionId) }

    /**
     * Opens a session on the pane the phone showed there last time, or, the first time and when
     * that pane is gone, on the one Rex has focused, which is where the desktop is. Only that one
     * pane is drawn; the tab bar and the pane strip move between the others.
     */
    fun openSession(id: String) {
        val s = session ?: return
        viewModelScope.launch {
            try {
                s.openSession(id, rememberedPane(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                s.note(e.message ?: getApplication<Application>().getString(R.string.error_operation_failed))
                return@launch
            }
            route.value = Route.Term
        }
    }

    /**
     * After the process is killed in the background, reopen the host and session that were on
     * screen. The Rex server keeps the session, so this is a reconnect rather than a new one.
     */
    fun restore() {
        // A phone that has joined a tailnet keeps the node up: the Tailscale row is the honest
        // answer to "am I in the tailnet", and a host on a tailnet can then be dialed at once.
        if (tailscale.value.enabled) startTailnet(tailscale.value)
        if (route.value != Route.Home || session != null) return
        val target = store.lastHost()?.let { store.find(it) } ?: return
        connect(target, resume = true)
    }

    fun disconnect() {
        disconnectQuiet()
        store.setLastHost(null)
        route.value = Route.Home
    }

    fun showPicker() {
        route.value = Route.Picker
    }

    fun showTerm() {
        // Nothing to draw while the node or the connection is still missing.
        if (session != null) route.value = Route.Term
    }

    fun setFont(scale: Float) {
        val next = scale.coerceIn(0.7f, 1.8f)
        fontScale.value = next
        store.setFontScale(next)
    }

    fun act(block: suspend PocketSession.() -> Unit) {
        val s = session ?: return
        viewModelScope.launch {
            try {
                s.block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                s.note(e.message ?: getApplication<Application>().getString(R.string.error_operation_failed))
            }
        }
    }

    private fun disconnectQuiet() {
        connectJob?.cancel()
        connectJob = null
        watch?.cancel()
        session?.stop()
        sessionJob?.cancel()
        session = null
        watch = null
        _pocket.value = PocketState()
    }
}

@Composable
fun RexPocketApp(model: RexViewModel = viewModel()) {
    val route by model.route.collectAsState()
    val hosts by model.hosts.collectAsState()
    val pocket by model.pocket.collectAsState()
    val font by model.fontScale.collectAsState()
    val tailscale by model.tailscale.collectAsState()
    val tailscaleStatus by model.tailscaleStatus.collectAsState()
    val tailnetError by model.tailnetError.collectAsState()
    val context = LocalContext.current
    var tailscaleOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.restore() }
    // A node that still needs a login can only say so through the link it prints, so take the
    // user straight to it instead of leaving a URL to copy.
    val authUrl = tailscaleStatus.authUrl
    LaunchedEffect(authUrl) {
        if (authUrl.isBlank()) return@LaunchedEffect
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))) }
    }
    Box(Modifier.fillMaxSize().background(Bg)) {
        when (route) {
            Route.Home -> HomeScreen(
                hosts = hosts,
                tailscale = tailscale,
                tailscaleStatus = tailscaleStatus,
                onSave = model::saveHost,
                onDelete = model::deleteHost,
                onOpen = model::connect,
                onTailscale = { tailscaleOpen = true },
            )
            Route.Picker -> PickerScreen(
                hostLabel = model.host?.label ?: "Rex",
                state = pocket,
                tailscaleStatus = tailscaleStatus,
                tailscaleEnabled = tailscale.enabled,
                onTailscale = { tailscaleOpen = true },
                onBack = model::disconnect,
                onOpen = model::openSession,
                onCreate = { model.act { createShell(it) }; model.showTerm() },
            )
            Route.Term -> TermScreen(
                state = pocket,
                fontScale = font,
                onFont = model::setFont,
                onBack = model::showPicker,
                onOpenSession = model::openSession,
                onAct = model::act,
            )
        }
    }
    if (tailscaleOpen) {
        TailscaleDialog(
            initial = tailscale,
            status = tailscaleStatus,
            error = tailnetError,
            onDismiss = { tailscaleOpen = false },
            onJoin = { name ->
                model.joinTailnet(name)
                tailscaleOpen = false
            },
            onLeave = {
                model.leaveTailnet()
                tailscaleOpen = false
            },
        )
    }
}

@Composable
private fun HomeScreen(
    hosts: List<SavedHost>,
    tailscale: TailscaleSettings,
    tailscaleStatus: TailscaleStatus,
    onSave: (SavedHost) -> Unit,
    onDelete: (String) -> Unit,
    onOpen: (SavedHost) -> Unit,
    onTailscale: () -> Unit,
) {
    var editing by remember { mutableStateOf<SavedHost?>(null) }
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(28.dp))
        Text("REXPOCKET", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.home_title), color = Ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.home_subtitle),
            color = Muted,
            fontSize = 15.sp,
            lineHeight = 21.sp,
        )
        Spacer(Modifier.height(22.dp))
        TailscaleRow(tailscale, tailscaleStatus, onOpen = onTailscale)
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.home_hosts), color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        if (hosts.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp),
            ) {
                Text(stringResource(R.string.home_no_hosts), color = Ink, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.home_no_hosts_body),
                    color = Muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                items(hosts, key = { it.id }) { host ->
                    EntryCard(
                        title = host.label,
                        detail = listOfNotNull(
                            "${host.host}:${host.port}",
                            stringResource(
                                if (host.plaintext) R.string.host_transport_plain else R.string.host_transport_tls,
                            ),
                            stringResource(R.string.tailscale_title).takeIf { host.viaTailscale },
                        ).joinToString(" · "),
                        trailing = stringResource(R.string.action_edit),
                        onOpen = { onOpen(host) },
                        onTrailing = { editing = host },
                    )
                }
            }
        }
        if (hosts.isEmpty()) Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.action_add_host),
            color = Bg,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 18.dp)
                .clip(RoundedCornerShape(14.dp)).background(Accent).clickable { creating = true }
                .padding(vertical = 14.dp),
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
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
}

/**
 * The tailnet row: joining is a thing you do on purpose, so the row says whether the phone is in a
 * tailnet and under which name, and tapping it asks for that name once.
 */
@Composable
private fun TailscaleRow(settings: TailscaleSettings, status: TailscaleStatus, onOpen: () -> Unit) {
    val state = tailnetState(status)
    val joined = settings.enabled && (status.backendState == "Running")
    val detail = when {
        !settings.enabled -> stringResource(R.string.tailscale_not_joined)
        state.isNotEmpty() -> state
        // Joined, but the node is not up right now: say so instead of pretending to start.
        else -> stringResource(R.string.tailscale_state_stopped)
    }
    val color = when {
        !settings.enabled -> Muted
        joined -> Accent
        else -> Amber
    }
    // A strip, not a card: this is the state of the phone's own node, not one of the hosts below.
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .border(1.dp, Line, RoundedCornerShape(10.dp))
            .clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (joined) Accent else if (settings.enabled) Amber else Muted))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.tailscale_title), color = Muted, fontSize = 12.sp, letterSpacing = 0.5.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            settings.hostname.ifBlank { status.networkName }.ifBlank { stringResource(R.string.tailscale_no_name) },
            color = if (joined) Ink else Muted,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(detail, color = color, fontSize = 12.sp)
    }
}

/**
 * One row of a list, whether it lists hosts or sessions: the whole card opens the thing, and the
 * label on the right only becomes its own target when it does something else, such as editing.
 */
@Composable
private fun EntryCard(
    title: String,
    detail: String,
    trailing: String,
    onOpen: () -> Unit,
    onTrailing: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            // A tailnet address is long; two lines keep it readable instead of cut in the middle.
            Text(detail, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            trailing,
            color = Accent,
            fontSize = 13.sp,
            modifier = (if (onTrailing != null) Modifier.clickable(onClick = onTrailing) else Modifier)
                .padding(horizontal = 6.dp, vertical = 6.dp),
        )
    }
}

/** Tailscale's backend state in the app's own words; empty while nothing is running. */
@Composable
private fun tailnetState(status: TailscaleStatus): String = when (status.backendState) {
    "" -> ""
    "Running" -> stringResource(R.string.tailscale_state_running)
    "Starting" -> stringResource(R.string.tailscale_state_starting)
    "NeedsLogin" -> stringResource(R.string.tailscale_state_needs_login)
    "NeedsMachineAuth" -> stringResource(R.string.tailscale_state_needs_approval)
    "Stopped", "NoState" -> stringResource(R.string.tailscale_state_stopped)
    else -> status.text.ifBlank { status.backendState }
}

@Composable
private fun TailscaleDialog(
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
        containerColor = Card,
        title = { Text(stringResource(R.string.tailscale_title), color = Ink) },
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
                        color = if (status.backendState == "Running") Accent else Amber,
                        fontSize = 13.sp,
                    )
                }
                error?.let { Text(it, color = Danger, fontSize = 12.sp) }
                if (status.authUrl.isNotBlank()) {
                    Text(
                        status.authUrl,
                        color = Accent,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable {
                            clipboardSet(context, status.authUrl)
                            toast(context, context.getString(R.string.tailscale_login_copied))
                        },
                    )
                    Text(stringResource(R.string.tailscale_login_hint), color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                }
                Text(stringResource(R.string.tailscale_hint), color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { onJoin(hostname) }) {
                Text(
                    stringResource(if (initial.enabled) R.string.action_save else R.string.tailscale_join),
                    color = Accent,
                )
            }
        },
        dismissButton = {
            Row {
                if (initial.enabled) {
                    TextButton(onClick = onLeave) { Text(stringResource(R.string.tailscale_leave), color = Danger) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Muted) }
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
private fun HostDialog(
    initial: SavedHost?,
    tailscaleEnabled: Boolean,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (SavedHost) -> Unit,
    onJoinTailnet: () -> Unit,
) {
    val context = LocalContext.current
    var label by remember { mutableStateOf(initial?.label ?: "") }
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
        containerColor = Card,
        title = {
            Text(
                if (initial == null) stringResource(R.string.action_add_host) else stringResource(R.string.action_edit_host),
                color = Ink,
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
                        color = Muted,
                        fontSize = 12.sp,
                    )
                } else if (address.isNotBlank()) {
                    Text(stringResource(R.string.host_address_invalid), color = Danger, fontSize = 12.sp)
                }
                val tailnetTarget = parsed
                if (tailnetTarget != null && tailnetTarget.viaTailscale && !tailscaleEnabled) {
                    Text(
                        stringResource(R.string.tailscale_need_join_host),
                        color = Amber,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            onSave(asSavedHost(initial, label, tailnetTarget, viaTailscale = true))
                            onJoinTailnet()
                        },
                    )
                }
                if (parsed?.plaintext == true) {
                    Text(stringResource(R.string.field_plaintext_warning), color = Amber, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val target = parsed ?: return@TextButton
                onSave(asSavedHost(initial, label, target, viaTailscale = target.viaTailscale))
            }) { Text(stringResource(R.string.action_save), color = Accent) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), color = Danger) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Muted) }
            }
        },
    )
}

@Composable
private fun Field(hint: String, value: String, autoFocus: Boolean = false, onChange: (String) -> Unit) {
    // With autoFocus the field opens focused with its text selected, so a rename can be typed over.
    var field by remember { mutableStateOf(TextFieldValue(value, if (autoFocus) TextRange(0, value.length) else TextRange(value.length))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    if (autoFocus) {
        LaunchedEffect(Unit) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    // Written outside composition: setting state while composing makes the field lose a tap's focus.
    LaunchedEffect(value) {
        if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    }
    Column {
        Text(hint, color = Muted, fontSize = 12.sp)
        BasicTextField(
            value = field,
            onValueChange = {
                field = it
                onChange(it.text)
            },
            textStyle = TextStyle(color = Ink, fontSize = 16.sp),
            cursorBrush = SolidColor(Accent),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).border(1.dp, Line, RoundedCornerShape(8.dp)).padding(10.dp)
                .focusRequester(focus),
        )
    }
}

private fun asSavedHost(
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

@Composable
private fun PickerScreen(
    hostLabel: String,
    state: PocketState,
    tailscaleStatus: TailscaleStatus,
    tailscaleEnabled: Boolean,
    onTailscale: () -> Unit,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onCreate: (String?) -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Accent, fontSize = 28.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 8.dp))
            Column(Modifier.weight(1f)) {
                Text(hostLabel, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(phaseText(state), color = if (state.phase == Phase.Live) Accent else Amber, fontSize = 13.sp)
            }
        }
        state.error?.let {
            Text(it, color = Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
        // The two reasons this list can stay empty, and for both of them the fix is one tap away.
        if (!tailscaleEnabled) {
            Text(
                stringResource(R.string.tailscale_need_join),
                color = Amber,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp).clickable(onClick = onTailscale),
            )
        } else if (tailscaleStatus.authUrl.isNotBlank()) {
            Text(
                stringResource(R.string.tailscale_login_banner),
                color = Amber,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp).clickable {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(tailscaleStatus.authUrl),
                            ),
                        )
                    }
                },
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.sessions), color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        if (state.sessions.isEmpty()) {
            Text(
                stringResource(
                    if (state.phase == Phase.Live) R.string.session_none_live else R.string.session_loading,
                ),
                color = Muted,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f, fill = false)) {
                items(state.sessions, key = { it.id }) { session ->
                    val windows = state.views[session.id]?.windows?.size
                    EntryCard(
                        title = session.label,
                        detail = if (windows == null) {
                            stringResource(R.string.session_tap_to_enter)
                        } else {
                            stringResource(R.string.session_window_count, windows)
                        },
                        trailing = stringResource(R.string.session_enter),
                        onOpen = { onOpen(session.id) },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.session_new),
            color = Bg,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 18.dp)
                .clip(RoundedCornerShape(14.dp)).background(Accent)
                .clickable { onCreate(null) }
                .padding(vertical = 14.dp),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Something long-pressed or picked from the menu that needs a rename or a confirmation. */
private data class Rename(val kind: Level, val id: String, val current: String)

private data class Confirm(val kind: Level, val id: String, val name: String)

private enum class Level { Session, Tab, Pane }

/** The bottom sheet open over the terminal. */
private sealed interface Sheet {
    data object Sessions : Sheet
    data object Actions : Sheet
    data class TabActions(val windowId: String) : Sheet
    data class PaneActions(val blockId: String) : Sheet
}

@Composable
private fun TermScreen(
    state: PocketState,
    fontScale: Float,
    onFont: (Float) -> Unit,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    onAct: (suspend PocketSession.() -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var draft by remember { mutableStateOf("") }
    var ctrl by remember { mutableStateOf(false) }
    var ctrlLock by remember { mutableStateOf(false) }
    var lastCtrl by remember { mutableLongStateOf(0L) }
    var dpad by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Rename?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var termSession by remember { mutableStateOf<GhosttyTerminalSession?>(null) }
    val view = state.view
    val tab = view?.activeWindow()
    val focused = view?.focusedBlock()
    val status = focused?.let { state.blockStatus[it.id] }

    // An open sheet handles Back itself and closes.
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderButton("‹") { onBack() }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(view?.label ?: phaseText(state), color = Ink, fontSize = 16.sp, maxLines = 1)
                Text(subtitle(state, status), color = Muted, fontSize = 11.sp, maxLines = 1)
            }
            if (status?.state == "working" || status?.state == "blocked") {
                HeaderButton(stringResource(R.string.header_stop), Amber) { onAct { sendKey("key_c", mods = "ctrl", codepoint = 99) } }
            }
            HeaderButton(stringResource(R.string.header_sessions)) { sheet = Sheet.Sessions }
            HeaderButton("···") { sheet = Sheet.Actions }
        }
        if (view != null) {
            TabBar(
                view = view,
                status = state.blockStatus,
                onSelect = { w -> if (w.id != view.activeWindowId) onAct { focusWindow(w.id) } },
                onLongPress = { w -> sheet = Sheet.TabActions(w.id) },
                onNew = { onAct { newWindow() } },
            )
            if (tab != null && tab.blocks.size > 1) {
                PaneStrip(
                    tab = tab,
                    status = state.blockStatus,
                    onSelect = { b -> if (b.id != focused?.id) onAct { focusBlock(b.id) } },
                    onLongPress = { b -> sheet = Sheet.PaneActions(b.id) },
                    onNew = { onAct { split(vertical = false) } },
                )
            }
        }
        state.error?.let {
            Text(it, color = Danger, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        }
        // One emulator per shown pane, fed only with the dump read from that pane, so switching
        // never flashes the previous pane's screen or mixes two panes' scrollback.
        val shown = focused?.id
        key(shown) {
            TerminalPane(
                vt = state.vt.takeIf { shown != null && state.vtBlockId == shown },
                vtVersion = state.vtVersion,
                fontScale = fontScale,
                onFont = onFont,
                onInput = { bytes -> onAct { writeBytes(bytes) } },
                onResize = { cols, rows, cw, ch -> onAct { setSize(cols, rows, cw, ch) } },
                onSession = { termSession = it },
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp),
            )
        }
        if (dpad) Dpad(onAct)
        Composer(
            draft = draft,
            onDraft = { draft = it },
            onSend = {
                val text = draft
                draft = ""
                // An empty composer sends the Enter key alone, which is how a full-screen program
                // such as pi confirms the item its menu has selected.
                onAct { submitLine(text) }
            },
            onSendEnter = { onAct { sendKey("enter") } },
        )
        Toolbar(
            ctrl = ctrl || ctrlLock,
            locked = ctrlLock,
            dpad = dpad,
            onCtrl = {
                val now = SystemClock.uptimeMillis()
                if (now - lastCtrl < 320) {
                    ctrlLock = !ctrlLock
                    ctrl = ctrlLock
                } else if (ctrlLock) {
                    ctrlLock = false
                    ctrl = false
                } else {
                    ctrl = !ctrl
                }
                lastCtrl = now
            },
            onKey = { code, mods, cp ->
                val useMods = if ((ctrl || ctrlLock) && mods.isEmpty()) "ctrl" else mods
                onAct { sendKey(code, mods = useMods, codepoint = cp) }
                if (!ctrlLock) ctrl = false
            },
            onPaste = {
                val text = clipboard(context)
                if (text.isNullOrEmpty()) toast(context, context.getString(R.string.toast_clipboard_empty)) else onAct { paste(text) }
            },
            onCopy = {
                val text = termSession?.selectionText().orEmpty()
                if (text.isBlank()) {
                    toast(context, context.getString(R.string.toast_select_text))
                } else {
                    clipboardSet(context, text)
                    toast(context, context.getString(R.string.toast_copied))
                }
            },
            onDpad = { dpad = !dpad },
        )
    }

    when (val open = sheet) {
        null -> Unit
        Sheet.Sessions -> SessionSheet(
            state = state,
            onDismiss = { sheet = null },
            onOpen = { id -> if (id != state.activeSessionId) onOpenSession(id) },
            onCreate = { onAct { createShell(null) } },
        )
        Sheet.Actions -> ActionSheet(onDismiss = { sheet = null }) {
            Group(stringResource(R.string.level_pane)) {
                SheetItem(stringResource(R.string.menu_split_right)) { onAct { split(vertical = false) } }
                SheetItem(stringResource(R.string.menu_split_below)) { onAct { split(vertical = true) } }
                if (focused != null) {
                    SheetItem(stringResource(R.string.menu_rename_pane)) { rename = Rename(Level.Pane, focused.id, focused.label) }
                    SheetItem(stringResource(R.string.menu_close_pane), Danger) {
                        confirm = Confirm(Level.Pane, focused.id, focused.label)
                    }
                }
            }
            Group(stringResource(R.string.level_tab)) {
                SheetItem(stringResource(R.string.menu_new_tab)) { onAct { newWindow() } }
                if (view != null && tab != null) {
                    val title = tabTitle(view, tab)
                    SheetItem(stringResource(R.string.menu_rename_tab)) { rename = Rename(Level.Tab, tab.id, tab.label) }
                    SheetItem(stringResource(R.string.menu_close_tab), Danger) { confirm = Confirm(Level.Tab, tab.id, title) }
                }
            }
            if (view != null) {
                Group(stringResource(R.string.level_session)) {
                    SheetItem(stringResource(R.string.menu_rename_session)) {
                        rename = Rename(Level.Session, view.sessionId, view.label)
                    }
                    SheetItem(stringResource(R.string.menu_kill_session), Danger) {
                        confirm = Confirm(Level.Session, view.sessionId, view.label)
                    }
                }
            }
        }
        is Sheet.TabActions -> {
            // The tab may close on the desktop while its sheet is open; then the sheet goes too.
            val window = view?.windows?.firstOrNull { it.id == open.windowId }
            if (view != null && window != null) {
                val title = tabTitle(view, window)
                ActionSheet(onDismiss = { sheet = null }, title = title) {
                    Group {
                        SheetItem(stringResource(R.string.menu_rename_tab)) { rename = Rename(Level.Tab, window.id, window.label) }
                        SheetItem(stringResource(R.string.menu_close_tab), Danger) { confirm = Confirm(Level.Tab, window.id, title) }
                    }
                }
            }
        }
        is Sheet.PaneActions -> {
            val block = tab?.blocks?.firstOrNull { it.id == open.blockId }
            if (tab != null && block != null) {
                val number = tab.blocks.indexOf(block) + 1
                ActionSheet(onDismiss = { sheet = null }, title = "$number  ${paneTitle(block, state.blockStatus[block.id])}") {
                    Group {
                        SheetItem(stringResource(R.string.menu_rename_pane)) { rename = Rename(Level.Pane, block.id, block.label) }
                        SheetItem(stringResource(R.string.menu_close_pane), Danger) { confirm = Confirm(Level.Pane, block.id, block.label) }
                    }
                }
            }
        }
    }

    rename?.let { target ->
        var text by remember(target) { mutableStateOf(target.current) }
        AlertDialog(
            onDismissRequest = { rename = null },
            containerColor = Card,
            title = {
                Text(
                    when (target.kind) {
                        Level.Tab -> stringResource(R.string.menu_rename_tab)
                        Level.Pane -> stringResource(R.string.menu_rename_pane)
                        Level.Session -> stringResource(R.string.menu_rename_session)
                    },
                    color = Ink,
                )
            },
            text = { Field(stringResource(R.string.field_name), text, autoFocus = true) { text = it } },
            confirmButton = {
                TextButton(onClick = {
                    rename = null
                    val label = text.trim()
                    if (label.isEmpty() || label == target.current) return@TextButton
                    onAct {
                        when (target.kind) {
                            Level.Tab -> renameWindow(label, target.id)
                            Level.Pane -> renameBlock(label, target.id)
                            Level.Session -> renameSession(label)
                        }
                    }
                }) { Text(stringResource(R.string.action_save), color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { rename = null }) { Text(stringResource(R.string.action_cancel), color = Muted) }
            },
        )
    }

    confirm?.let { target ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Card,
            title = {
                Text(
                    when (target.kind) {
                        Level.Tab -> stringResource(R.string.confirm_close_tab, target.name)
                        Level.Pane -> stringResource(R.string.confirm_close_pane, target.name)
                        Level.Session -> stringResource(R.string.confirm_kill_session, target.name)
                    },
                    color = Ink,
                )
            },
            text = {
                Text(
                    when (target.kind) {
                        Level.Tab -> stringResource(R.string.confirm_close_tab_body)
                        Level.Pane -> stringResource(R.string.confirm_close_pane_body)
                        Level.Session -> stringResource(R.string.confirm_kill_session_body)
                    },
                    color = Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    onAct {
                        when (target.kind) {
                            Level.Tab -> closeWindow(target.id)
                            Level.Pane -> closeBlock(target.id)
                            Level.Session -> destroySession()
                        }
                    }
                }) { Text(stringResource(R.string.action_close), color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel), color = Muted) }
            },
        )
    }
}

/**
 * Rex's windows are the tabs of a session. Every tab is a chip here; the shown one is filled.
 * A tab holding several panes carries their count, and its dot is the most urgent pane state.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabBar(
    view: SessionView,
    status: Map<String, ProgramRecord>,
    onSelect: (WindowInfo) -> Unit,
    onLongPress: (WindowInfo) -> Unit,
    onNew: () -> Unit,
) {
    val list = rememberLazyListState()
    val activeIndex = view.windows.indexOfFirst { it.id == view.activeWindow()?.id }
    // Keep the tab in use in the middle of the row: a row of chips that always starts at the left
    // edge makes a session with ten tabs impossible to read.
    LaunchedEffect(activeIndex, view.windows.size) { list.centerOn(activeIndex) }
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScrollRow(list, Modifier.weight(1f)) {
            items(view.windows, key = { it.id }) { window ->
                val on = window.id == view.activeWindow()?.id
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp))
                        .background(if (on) Accent else Card)
                        .combinedClickable(onClick = { onSelect(window) }, onLongClick = { onLongPress(window) })
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(window.status(status), on)
                    Text(
                        tabTitle(view, window),
                        color = if (on) Bg else Ink,
                        fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                    if (window.blocks.size > 1) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${window.blocks.size}",
                            color = if (on) Accent else Muted,
                            fontSize = 11.sp,
                            modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(if (on) Bg else Line)
                                .padding(horizontal = 6.dp),
                        )
                    }
                }
            }
        }
        // The add chip stays outside the scroll, so it is in the same place however many tabs there are.
        Text(
            "+",
            color = Accent,
            fontSize = 16.sp,
            modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(10.dp)).background(Card)
                .clickable(onClick = onNew).padding(horizontal = 14.dp, vertical = 4.dp),
        )
    }
}

/**
 * The panes of the shown tab. The phone draws one pane at a time, so a split on the desktop
 * becomes a row of numbered chips here, and only the chosen one is on screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaneStrip(
    tab: WindowInfo,
    status: Map<String, ProgramRecord>,
    onSelect: (BlockInfo) -> Unit,
    onLongPress: (BlockInfo) -> Unit,
    onNew: () -> Unit,
) {
    val list = rememberLazyListState()
    val current = tab.focusedBlock()?.id
    val index = tab.focusedIndex()
    LaunchedEffect(tab.id, index, tab.blocks.size) { list.centerOn(index) }
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScrollRow(list, Modifier.weight(1f)) {
            items(tab.blocks, key = { it.id }) { block ->
                val on = block.id == current
                val record = status[block.id]
                Row(
                    Modifier.clip(RoundedCornerShape(99.dp))
                        .border(1.dp, if (on) Accent else Line, RoundedCornerShape(99.dp))
                        .combinedClickable(onClick = { onSelect(block) }, onLongClick = { onLongPress(block) })
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(record, false)
                    Text(
                        "${tab.blocks.indexOf(block) + 1}",
                        color = if (on) Accent else Muted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        paneTitle(block, record),
                        color = if (on) Accent else Ink,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp),
                    )
                }
            }
        }
        Text(
            "+",
            color = Accent,
            fontSize = 14.sp,
            modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(99.dp))
                .border(1.dp, Line, RoundedCornerShape(99.dp)).clickable(onClick = onNew)
                .padding(horizontal = 12.dp, vertical = 2.dp),
        )
    }
}

/**
 * A row of chips that fades out where it continues, so a chip cut by the edge reads as "there is
 * more" instead of as a broken layout. The add chip next to it stays put.
 */
@Composable
private fun ScrollRow(state: LazyListState, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    val moreBefore by remember { derivedStateOf { state.canScrollBackward } }
    val moreAfter by remember { derivedStateOf { state.canScrollForward } }
    Box(modifier) {
        LazyRow(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (moreBefore) {
            Box(
                Modifier.align(Alignment.CenterStart).width(18.dp).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Bg, Color.Transparent))),
            )
        }
        if (moreAfter) {
            Box(
                Modifier.align(Alignment.CenterEnd).width(18.dp).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, Bg))),
            )
        }
    }
}

/** Scrolls [index] to the middle of the row when it is already on screen, else to the left edge. */
private suspend fun LazyListState.centerOn(index: Int) {
    if (index < 0) return
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    val offset = if (item != null) item.offset - (info.viewportSize.width - item.size) / 2 else 0
    animateScrollToItem(index, offset)
}

/** A dot only for states worth a glance; idle panes stay quiet. */
@Composable
private fun StatusDot(status: ProgramRecord?, onAccent: Boolean) {
    if (status == null || status.state == "idle") return
    Box(
        Modifier.padding(end = 6.dp).size(7.dp).clip(CircleShape)
            .background(if (onAccent) Bg else statusColor(status)),
    )
}

/**
 * Every session on this Rex, nothing more. Picking one opens it on the pane the phone showed
 * there last time; the tab bar and pane strip take over from there.
 */
@Composable
private fun SessionSheet(
    state: PocketState,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
) {
    ActionSheet(onDismiss = onDismiss, title = stringResource(R.string.sessions)) {
        Group {
            state.sessions.forEach { session ->
                val active = session.id == state.activeSessionId
                val tabs = state.views[session.id]?.windows?.size
                SheetItem(
                    label = session.label,
                    color = if (active) Accent else Ink,
                    detail = tabs?.let { stringResource(R.string.session_window_count, it) },
                    marked = active,
                ) { onOpen(session.id) }
            }
        }
        Group { SheetItem("+  " + stringResource(R.string.session_new), Accent, onClick = onCreate) }
    }
}

/**
 * Lets a sheet row close its sheet. The row's action runs once the sheet has gone: a dialog it opens
 * would otherwise ask for the keyboard while the sheet's window still holds it, and lose it when that
 * window is removed.
 */
private class SheetScope(val close: (() -> Unit) -> Unit)

/**
 * A bottom sheet in the app's colours: rows grouped on cards over the dark background, the same
 * look as the host and session lists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionSheet(onDismiss: () -> Unit, title: String? = null, content: @Composable SheetScope.() -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val rows = SheetScope { action ->
        scope.launch { state.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        containerColor = Bg,
        contentColor = Ink,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Line))
        },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
        ) {
            if (title != null) {
                Text(
                    title,
                    color = Ink,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                )
            }
            rows.content()
        }
    }
}

/** A card of rows, with an optional caption above it. */
@Composable
private fun SheetScope.Group(label: String? = null, content: @Composable SheetScope.() -> Unit) {
    if (label != null) {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp))
    } else {
        Spacer(Modifier.height(10.dp))
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card)) { content() }
}

@Composable
private fun SheetScope.SheetItem(
    label: String,
    color: Color = Ink,
    detail: String? = null,
    marked: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { close(onClick) }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (marked) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Accent))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            label,
            color = color,
            fontSize = 16.sp,
            fontWeight = if (marked) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (detail != null) Text(detail, color = Muted, fontSize = 13.sp)
    }
}

/** Rex names an unlabeled window "Window " plus an id tail; the tab's position reads better. */
@Composable
private fun tabTitle(view: SessionView, window: WindowInfo): String {
    val number = view.windows.indexOf(window) + 1
    return if (GENERATED_WINDOW_LABEL.matches(window.label)) stringResource(R.string.tab_number, number) else window.label
}

/** How long a connection waits for the tailnet node to come up before dialing anyway. */
private const val NODE_READY_MS = 30_000L

/** Connection diagnostics: `adb logcat -s RexPocketConn`. */
private const val CONN_TAG = "RexPocketConn"

private val GENERATED_WINDOW_LABEL = Regex("Window [0-9a-f]{6,}")

/** A pane left at the default "shell" label is named after the program running in it. */
private fun paneTitle(block: BlockInfo, status: ProgramRecord?): String {
    val app = status?.app?.takeIf { it.isNotBlank() }
    return if (block.label == "shell" && app != null) app else block.label
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onSendEnter: () -> Unit,
) {
    var field by remember { mutableStateOf<EditText?>(null) }
    // The field outlives recomposition, so its callbacks read the current send through a state.
    val send = remember { mutableStateOf(onSend) }
    send.value = onSend

    fun showIme() {
        val edit = field ?: return
        edit.requestFocus()
        edit.post { edit.context.getSystemService(InputMethodManager::class.java)?.showSoftInput(edit, 0) }
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AndroidView(
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Card),
            factory = { ctx ->
                EditText(ctx).apply {
                    background = null
                    setTextColor(Ink.toArgb())
                    setHintTextColor(Muted.toArgb())
                    hint = ctx.getString(R.string.composer_hint)
                    textSize = 16f
                    setPadding(28, 18, 28, 18)
                    inputType = InputType.TYPE_CLASS_TEXT
                    // One line, so the keyboard offers its send key instead of a newline.
                    imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    maxLines = 1
                    isSingleLine = true
                    setOnEditorActionListener { _, _, _ ->
                        send.value()
                        true
                    }
                    // Some IMEs send the Enter key itself rather than the editor action.
                    setOnKeyListener { _, code, event ->
                        if (code == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                            send.value()
                            true
                        } else {
                            false
                        }
                    }
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            // A committed newline would otherwise ride along with the text; Enter
                            // sends the line through the listener above instead.
                            val text = s?.toString().orEmpty().replace("\n", "")
                            if (text != s?.toString()) setText(text) else onDraft(text)
                        }
                    })
                    setOnFocusChangeListener { _, focused ->
                        // An EditText reports focus before the input connection is ready.
                        if (focused) post { context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0) }
                    }
                    // Focus does not change when the field is already focused, which is the case
                    // after the keyboard was dismissed with Back, so taps ask for the IME too.
                    setOnClickListener {
                        requestFocus()
                        post { context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0) }
                    }
                    field = this
                }
            },
            update = { view ->
                if (view.text.toString() != draft) {
                    view.setText(draft)
                    view.setSelection(draft.length)
                }
            },
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.composer_send),
            color = Accent,
            fontSize = 16.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Card)
                .combinedClickable(
                    onClick = { onSend(); showIme() },
                    onLongClick = { onSendEnter(); showIme() },
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun Toolbar(
    ctrl: Boolean,
    locked: Boolean,
    dpad: Boolean,
    onCtrl: () -> Unit,
    onKey: (code: String, mods: String, codepoint: Int) -> Unit,
    onPaste: () -> Unit,
    onCopy: () -> Unit,
    onDpad: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Tool(stringResource(R.string.key_escape)) { onKey("escape", "", 0) }
        Tool(stringResource(if (locked) R.string.key_ctrl_lock else R.string.key_ctrl), if (ctrl) Accent else Ink) { onCtrl() }
        Tool(stringResource(R.string.key_tab)) { onKey("tab", "", 0) }
        // A slash command typed into pi opens its command menu, and the first Enter only accepts
        // the highlighted entry. This key sends the Enter that runs it.
        Tool(stringResource(R.string.key_enter)) { onKey("enter", "", 0) }
        Tool("/") { onKey("slash", "", '/'.code) }
        Tool(stringResource(R.string.key_up)) { onKey("arrow_up", "", 0) }
        Tool(stringResource(R.string.key_down)) { onKey("arrow_down", "", 0) }
        Tool(stringResource(R.string.key_ctrl_c), Amber) { onKey("key_c", "ctrl", 99) }
        Tool(stringResource(R.string.key_ctrl_d)) { onKey("key_d", "ctrl", 100) }
        Tool(stringResource(R.string.key_ctrl_z)) { onKey("key_z", "ctrl", 122) }
        Tool(stringResource(R.string.key_ctrl_l)) { onKey("key_l", "ctrl", 108) }
        Tool(stringResource(R.string.key_paste)) { onPaste() }
        Tool(stringResource(R.string.key_copy)) { onCopy() }
        Tool(stringResource(if (dpad) R.string.key_dpad_on else R.string.key_dpad)) { onDpad() }
    }
}

@Composable
private fun Dpad(onAct: (suspend PocketSession.() -> Unit) -> Unit) {
    fun key(code: String) = onAct { sendKey(code) }
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row {
            Tool(stringResource(R.string.key_backspace)) { key("backspace") }
            Tool(stringResource(R.string.key_up)) { key("arrow_up") }
            Tool(stringResource(R.string.header_stop), Amber) { onAct { sendKey("key_c", mods = "ctrl", codepoint = 99) } }
        }
        Row {
            Tool(stringResource(R.string.key_left)) { key("arrow_left") }
            Tool(stringResource(R.string.key_down)) { key("arrow_down") }
            Tool(stringResource(R.string.key_right)) { key("arrow_right") }
        }
    }
}

@Composable
private fun Tool(label: String, color: Color = Ink, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontSize = 14.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Card).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun HeaderButton(label: String, color: Color = Accent, onClick: () -> Unit) {
    Text(label, color = color, modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp), fontSize = 16.sp)
}

@Composable
private fun subtitle(state: PocketState, status: ProgramRecord?): String {
    // The tab bar and pane strip already name the tab and pane; the subtitle says what runs there.
    val bits = listOfNotNull(
        status?.let { statusLabel(it) },
        state.pwd?.let { path -> path.substringAfterLast('/').ifBlank { path } },
    )
    return bits.joinToString(" · ").ifBlank { phaseText(state) }
}

@Composable
private fun phaseText(state: PocketState): String = when (state.phase) {
    Phase.Idle -> stringResource(R.string.status_idle)
    Phase.Connecting -> stringResource(R.string.status_connecting, state.endpointLabel)
    Phase.Live -> stringResource(if (state.resumed) R.string.status_resumed else R.string.status_live)
    Phase.Reconnecting -> stringResource(R.string.status_reconnecting)
    Phase.Failed -> stringResource(R.string.status_failed)
}

@Composable
private fun statusLabel(status: ProgramRecord): String {
    val name = status.app ?: status.title
    val base = when (status.state) {
        "working" -> stringResource(R.string.state_running)
        "blocked" -> when (status.kind) {
            "permission" -> stringResource(R.string.state_waiting_approval)
            "question" -> stringResource(R.string.state_waiting_answer)
            "auth" -> stringResource(R.string.state_waiting_login)
            else -> stringResource(R.string.state_waiting_you)
        }
        "done" -> stringResource(R.string.state_done)
        "error" -> stringResource(R.string.state_failed)
        else -> stringResource(R.string.state_idle)
    }
    val msg = status.msg?.take(42)
    return listOfNotNull(name, base, msg).joinToString(" ")
}

private fun statusColor(status: ProgramRecord?): Color = when (status?.state) {
    "working" -> Accent
    "blocked" -> Amber
    "done" -> Color(0xFF7DDE92)
    "error" -> Danger
    else -> Muted
}

private fun clipboard(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java) ?: return null
    return clip.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

private fun clipboardSet(context: Context, text: String) {
    val clip = context.getSystemService(ClipboardManager::class.java) ?: return
    clip.setPrimaryClip(ClipData.newPlainText("rex", text))
}

private fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
