package me.leaver.rexpocket.ui

import android.app.Application
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import me.leaver.rexpocket.BuildConfig
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import me.leaver.rexpocket.core.Endpoint
import me.leaver.rexpocket.core.AttentionLedger
import me.leaver.rexpocket.core.FileUpload
import me.leaver.rexpocket.core.UploadPhase
import me.leaver.rexpocket.R
import me.leaver.rexpocket.alerts.Alert
import me.leaver.rexpocket.alerts.AlertTarget
import me.leaver.rexpocket.alerts.Alerts
import me.leaver.rexpocket.core.ListedSession
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.PocketSession
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.Problem
import me.leaver.rexpocket.core.TailnetPath
import me.leaver.rexpocket.core.Trouble
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.Flow
import me.leaver.rexpocket.core.TerminalFeed
import me.leaver.rexpocket.core.toProblem
import me.leaver.rexpocket.core.RexClient
import me.leaver.rexpocket.data.HostStore
import me.leaver.rexpocket.data.FontFiles
import me.leaver.rexpocket.data.TerminalFontState
import me.leaver.rexpocket.data.downloadedTypeface
import me.leaver.rexpocket.data.SavedHost
import me.leaver.rexpocket.data.TailscaleSettings
import me.leaver.rexpocket.core.ReportedFact
import me.leaver.rexpocket.diagnostics.Diagnostics
import me.leaver.rexpocket.net.NetworkWatcher
import me.leaver.rexpocket.net.TailscaleBox
import me.leaver.rexpocket.net.TailscaleConfig
import me.leaver.rexpocket.net.TailscaleStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// The app state: hosts, the tailnet node, and the one Rex connection the screens share.

/** What the session list is waiting on: one of its rows, or a session still being created. */
sealed interface Opening {
    data class Existing(val id: String) : Opening
    data object New : Opening
}

/** One pane of the session the user opened, offered before the terminal appears. */
sealed interface Route {
    data object Home : Route
    data object Term : Route
}

class RexViewModel(app: Application) : AndroidViewModel(app) {
    private val store = HostStore(app)
    private val tailscaleBox = TailscaleBox(app)
    val hosts = MutableStateFlow(store.list())
    val tailscale = MutableStateFlow(store.tailscale())
    val tailscaleStatus: StateFlow<TailscaleStatus> = tailscaleBox.status

    /**
     * How the open tailnet host is reached right now: direct, through a peer relay, or through a
     * DERP server. Null for a host on the phone's own network, and while it has not been measured.
     */
    private val _tailnetPath = MutableStateFlow<TailnetPath?>(null)
    val tailnetPath: StateFlow<TailnetPath?> = _tailnetPath
    private var pathJob: Job? = null

    /** Measures the path to the open host; a no-op unless that host goes through the tailnet. */
    fun probeTailnetPath() {
        val target = host?.takeIf { it.viaTailscale } ?: return
        pathJob?.cancel()
        pathJob = viewModelScope.launch {
            val path = withContext(Dispatchers.IO) { tailscaleBox.probePath(target.host) }
            _tailnetPath.value = path
            Diagnostics.event(CONN_TAG, "tailnet path to ${target.host}: $path")
        }
    }
    val route = MutableStateFlow<Route>(Route.Home)
    private val _pocket = MutableStateFlow(PocketState())
    val pocket: StateFlow<PocketState> = _pocket
    val fontScale = MutableStateFlow(store.fontScale())
    private val fontFiles = FontFiles(File(app.filesDir, "terminal-fonts"))
    val terminalTypeface = MutableStateFlow(Typeface.MONOSPACE)
    internal val terminalFont = MutableStateFlow(TerminalFontState())

    internal fun selectTerminalFont(downloaded: Boolean) {
        if (terminalFont.value.initializing || terminalFont.value.downloading) return
        if (!downloaded) {
            terminalTypeface.value = Typeface.MONOSPACE
            store.setDownloadedFont(false)
            terminalFont.value = terminalFont.value.copy(selected = false, failed = false)
            return
        }
        terminalFont.value = terminalFont.value.copy(downloading = true, failed = false, progress = 0f)
        viewModelScope.launch {
            try {
                val face = withContext(Dispatchers.IO) {
                    val files = fontFiles.install(progress = { progress ->
                        terminalFont.value = terminalFont.value.copy(progress = progress)
                    })
                    downloadedTypeface(files)
                }
                terminalTypeface.value = face
                store.setDownloadedFont(true)
                terminalFont.value = terminalFont.value.copy(selected = true, available = true, downloading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                terminalFont.value = terminalFont.value.copy(downloading = false, failed = true)
            }
        }
    }

    /** The file going to the shown pane right now, if any: the terminal shows it in a pill. */
    private val _upload = MutableStateFlow<UploadStatus?>(null)
    val upload: StateFlow<UploadStatus?> = _upload
    private var uploadJob: Job? = null
    private var uploadFile: File? = null
    private var uploadSession: String? = null
    private var uploadBlock: String? = null

    /** Background alerts: on, the app stays connected while it is away and says when a program needs you. */
    val alerts: StateFlow<Boolean> = Alerts.enabled.also { it.value = store.alerts() }
    private val attention = AttentionLedger()
    var host: SavedHost? = null
        private set

    private var session: PocketSession? = null
    private var foreground = true
    private var sessionJob: Job? = null
    private var watch: Job? = null
    private var connectJob: Job? = null
    private var tailnetJob: Job? = null
    private val _tailnetError = MutableStateFlow<String?>(null)
    val tailnetError: StateFlow<String?> = _tailnetError

    /** The session being opened or created from the list, shown as busy until it is on screen. */
    private val _opening = MutableStateFlow<Opening?>(null)
    val opening: StateFlow<Opening?> = _opening

    /** Becomes the connection's session once it exists, so a tap before that can wait for it. */
    private var sessionReady = CompletableDeferred<PocketSession>()

    /** Wi-Fi to mobile data and back: redial at once instead of waiting for the old socket to die. */
    private val network = NetworkWatcher(app) {
        viewModelScope.launch { session?.reconnectNow() }
    }.also { it.start() }

    init {
        viewModelScope.launch {
            val face = withContext(Dispatchers.IO) {
                runCatching {
                    if (fontFiles.available()) downloadedTypeface(fontFiles.directory) else null
                }.getOrNull()
            }
            val selected = store.downloadedFont() && face != null
            if (selected) terminalTypeface.value = face
            store.setDownloadedFont(selected)
            terminalFont.value = TerminalFontState(
                selected = selected, available = face != null, initializing = false,
            )
        }
        // The watch notification's Stop turns alerts off from outside the app; the setting follows.
        viewModelScope.launch {
            Alerts.enabled.collect { on ->
                Diagnostics.event(CONN_TAG, "alerts ${if (on) "on" else "off"}")
                store.setAlerts(on)
                session?.setWatching(on)
                if (!on) Alerts.unwatch(app)
            }
        }
        // The report a bug report needs follows the connection: which phase it reached, and why it
        // stopped. Recorded here rather than at each call, so no path can forget to say so.
        viewModelScope.launch {
            pocket.map { it.phase to it.problem }.distinctUntilChanged().collect { (phase, problem) ->
                Diagnostics.event(CONN_TAG, "phase $phase" + (problem?.let { " · ${it.describe()}" } ?: ""))
            }
            // An action that failed while the connection was fine: the pane could not be renamed, a
            // file did not go - the sort of thing that is invisible in a report otherwise.
            pocket.mapNotNull { it.notice }.distinctUntilChanged().collect { notice ->
                Diagnostics.event(CONN_TAG, "action failed: ${notice.problem.describe()}")
            }
            upload.map { it?.let { file -> "${file.phase} ${file.sent}/${file.total}" } }
                .distinctUntilChanged()
                .collect { state -> Diagnostics.event(CONN_TAG, "upload ${state ?: "done"}") }
        }
    }

    /**
     * Turns background alerts on or off. Called from the settings screen, which is on screen: Android
     * only lets an app start its foreground service while the person can see it.
     */
    fun setAlerts(on: Boolean) {
        Alerts.enabled.value = on
        val open = host
        if (on && open != null) Alerts.watch(getApplication(), open.label)
    }

    override fun onCleared() {
        network.stop()
        Alerts.unwatch(getApplication())
        // The pane goes back to the desktop's size; viewModelScope is already cancelled here.
        disconnectQuiet()
        tailscaleBox.stopAsync()
        super.onCleared()
    }

    /**
     * Joins the tailnet under [hostname] (blank means the device's own name). The node starts here,
     * not on the first connect, so the app can hand out the login link while the user is looking
     * at the Tailscale row; a node that is already logged in just comes back up.
     */
    fun joinTailnet(hostname: String) {
        Diagnostics.event(CONN_TAG, "joining the tailnet as ${hostname.trim().ifBlank { "default" }}")
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
        Diagnostics.event(CONN_TAG, "leaving the tailnet")
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
        route.value = Route.Home
        val settings = store.tailscale()
        if (target.viaTailscale && !settings.enabled) {
            // The host is saved either way; without a joined node there is no route to it.
            Diagnostics.event(CONN_TAG, "${target.label}: the tailnet is not joined")
            _pocket.value = PocketState(
                phase = Phase.Failed,
                endpointLabel = "${target.host}:${target.port}",
                problem = Problem(Trouble.TailnetNotJoined, null),
            )
            return
        }
        val viaTailscale = target.viaTailscale
        Diagnostics.event(CONN_TAG, "connect ${target.host}:${target.port} tailnet=$viaTailscale plaintext=${target.plaintext} resume=$resume")
        // Draw the list the host had last time straight away; the live one replaces it below.
        val cached = if (resume) emptyList() else store.cachedSessions(target.id)
        _pocket.value = PocketState(
            phase = Phase.Connecting,
            endpointLabel = "${target.host}:${target.port}",
            sessions = cached,
        )
        if (Alerts.enabled.value) Alerts.watch(getApplication(), target.label)
        connectJob = viewModelScope.launch {
            // A host on the phone's own network is dialed directly, and the tailnet node stays as it
            // is: the home screen says the phone is in a tailnet, so that must not depend on which
            // host happens to be open.
            // The node has to be up before Rex is dialed, because the Rex connection itself is
            // what goes through it. Joining the tailnet takes a moment on the first connect.
            val socksPort = if (viaTailscale) {
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
                        problem = Problem(Trouble.Tailnet, e.message),
                    )
                    return@launch
                }
                // The node reports Running only once its WireGuard session is up; dialing before
                // that just gets a SOCKS failure, which looks like a broken tunnel.
                val settled = withTimeoutOrNull(NODE_READY_MS) {
                    tailscaleBox.status.first {
                        it.backendState in setOf("Running", "NeedsLogin", "NeedsMachineAuth", "Stopped")
                    }
                }
                // Running comes before the first netmap, and a MagicDNS name dialed before the
                // netmap lists that peer fails in the node's DNS: that was the SOCKS error the first
                // open showed for a moment. Waiting for the peer costs nothing once it is known.
                if (settled?.backendState == "Running" && !tailscaleBox.awaitPeer(target.host, PEER_READY_MS)) {
                    Diagnostics.event(CONN_TAG, "${target.host} is not in the netmap yet; dialing anyway")
                }
                port
            } else {
                0
            }
            val s = PocketSession(
                Endpoint(target.host, target.port, target.plaintext),
                clientFactory = { RexClient.forSocksPort(socksPort) },
                // Uploads reserve a file on their own revision-11 connection, over the same route.
                uploadClientFactory = { RexClient.forSocksPort(socksPort, FileUpload.API_REVISION) },
                cachedSessions = cached,
                // The terminal stream takes the same route as the control connection.
                socksPort = socksPort,
                trace = { if (BuildConfig.DEBUG) Log.i(CONN_TAG, it) },
            )
            session = s
            sessionReady.complete(s)
            s.setActive(foreground)
            s.setWatching(Alerts.enabled.value)
            sessionJob = s.start(viewModelScope, previousSession)
            watch = viewModelScope.launch { follow(s, target.id, if (resume) previousSession else null) }
        }
    }

    /**
     * Everything that follows the connection's state, one flow per job: the screen shows every
     * state, and the store only hears about what changed. Ends with the connection.
     */
    private suspend fun follow(s: PocketSession, hostId: String, resumeTo: String?) = coroutineScope {
        launch { s.state.collect { _pocket.value = it } }
        launch {
            s.state.mapNotNull { it.activeSessionId }.distinctUntilChanged()
                .collect { store.setLastSession(hostId, it) }
        }
        launch {
            s.state.map { it.sessions }.filter { it.isNotEmpty() }.distinctUntilChanged()
                .collect { store.setCachedSessions(hostId, it) }
        }
        // Only a pane that was on the terminal screen counts. Connecting already selects a session,
        // and its Rex focus must not replace the pane remembered for it.
        launch {
            combine(s.state, route) { st, r -> if (r == Route.Term) shownPane(st) else null }
                .filterNotNull().distinctUntilChanged()
                .collect { (sid, block) -> store.setLastPane(hostId, sid, block, s.state.value.sessions.map { it.id }) }
        }
        // A session that is no longer on Rex, such as after Rex restarted, leaves nothing to show:
        // back to the list rather than an empty terminal.
        launch {
            s.state.map { it.phase to it.activeSessionId }.distinctUntilChanged().collect { (phase, active) ->
                if (phase == Phase.Live && active == null && route.value == Route.Term) route.value = Route.Home
            }
        }
        // Measured once the connection is up, which is when Tailscale has had a chance to find a
        // direct path; earlier it would almost always report the DERP fallback it starts on.
        launch {
            s.state.first { it.phase == Phase.Live }
            delay(PATH_SETTLE_MS)
            probeTailnetPath()
        }
        if (resumeTo != null) {
            launch {
                s.state.first { it.phase == Phase.Live && it.sessions.isNotEmpty() }
                resumeInto(s, resumeTo)
            }
        }
        if (BuildConfig.DEBUG) launch { logConnection(s) }
        launch { raiseAlerts(s, hostId) }
    }

    /**
     * Turns the panes' status into alerts. Every change is noted, so a request already on screen is
     * not news later; only one that arrives while the app is away is raised. An alert goes once its
     * pane stops waiting, or once the person opens that pane.
     */
    private suspend fun raiseAlerts(s: PocketSession, hostId: String) {
        val app = getApplication<Application>()
        combine(s.state, route) { st, r -> st to r }.collect { (st, r) ->
            val changes = attention.observe(st.blockStatus)
            changes.settled.forEach { Alerts.settle(app, it) }
            if (r == Route.Term && foreground) shownPane(st)?.let { (_, block) -> Alerts.settle(app, block) }
            if (foreground || !Alerts.enabled.value) return@collect
            for (block in changes.raised) alertFor(app, st, hostId, block)?.let { Alerts.raise(app, it) }
        }
    }

    private fun alertFor(app: Application, st: PocketState, hostId: String, block: String): Alert? {
        val record = st.blockStatus[block] ?: return null
        val view = st.views.values.firstOrNull { v -> v.allBlocks().any { it.id == block } } ?: return null
        val info = view.allBlocks().first { it.id == block }
        val name = paneTitle(info, record, st.programs[block])
        val state = statusWordRes(record)?.let { app.getString(it) } ?: app.getString(R.string.alerts_failed)
        val text = record.msg?.takeIf { it.isNotBlank() }?.let { "$state: $it" } ?: state
        return Alert(AlertTarget(hostId, view.sessionId, block), "$name · ${view.label}", text, host?.label.orEmpty())
    }

    /** Set once an alert has opened a pane, so the start-up resume does not replace it. */
    private var openedFromAlert = false

    /** Opens the pane an alert names, connecting to its host first when another one is open. */
    fun openAlert(target: AlertTarget) {
        val saved = store.find(target.hostId) ?: return
        openedFromAlert = true
        if (host?.id != saved.id || session == null) connect(saved)
        enterSession(Opening.Existing(target.sessionId)) { openSession(target.sessionId, target.blockId) }
    }

    private fun shownPane(st: PocketState): Pair<String, String>? {
        val sid = st.activeSessionId ?: return null
        val block = st.view?.takeIf { it.sessionId == sid }?.focusedBlock()?.id ?: return null
        return sid to block
    }

    /** Connection diagnostics for `adb logcat -s RexPocketConn`: each phase change, and how long the first live state took. */
    private suspend fun logConnection(s: PocketSession) {
        val startedAt = SystemClock.elapsedRealtime()
        var timedLive = false
        s.state.map { it.phase to it.problem }.distinctUntilChanged().collect { (phase, problem) ->
            Log.i(CONN_TAG, "phase=$phase problem=${problem ?: "-"}")
            if (!timedLive && phase == Phase.Live) {
                timedLive = true
                Log.i(CONN_TAG, "first live after ${SystemClock.elapsedRealtime() - startedAt}ms")
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
    fun openSession(id: String) = enterSession(Opening.Existing(id)) { openSession(id, rememberedPane(id)) }

    /**
     * Opens a session on [target] from the home list, connecting to that host first when it is not
     * the one on screen. The row and the terminal both say *Opening* while it waits.
     */
    fun openSessionOn(target: SavedHost, id: String) {
        if (host?.id != target.id) connect(target)
        openSession(id)
    }

    /** Makes a session on [target], connecting to it first when it is not the host on screen. */
    fun createSessionOn(target: SavedHost) {
        if (host?.id != target.id) connect(target)
        createSession()
    }

    /** Opens one tab of [target]'s session, connecting to the host first when it is not on screen. */
    fun openTabOn(target: SavedHost, sessionId: String, windowId: String) {
        if (host?.id != target.id) connect(target)
        // One open on the chosen tab, rather than opening the session and then switching tabs: each
        // step is a round trip, and the second one used to flash the session's other tab first.
        enterSession(Opening.Existing(sessionId)) { openSession(sessionId, rememberedPane(sessionId), windowId) }
    }

    /** Adds a tab to [target]'s session and shows it; connects to the host first when needed. */
    fun newTabOn(target: SavedHost, sessionId: String) {
        if (host?.id != target.id) connect(target)
        enterSession(Opening.Existing(sessionId)) {
            openSession(sessionId, rememberedPane(sessionId))
            newWindow()
        }
    }

    /** The sessions this host had last time, for a host that is not the one on screen. */
    fun cachedSessions(hostId: String): List<ListedSession> = store.cachedSessions(hostId)

    /** Creates a shell session and shows it once Rex has made it, never the session before it. */
    fun createSession(label: String? = null) = enterSession(Opening.New) { createShell(label) }

    /**
     * Runs [open] once the connection is up, then shows the terminal. A tap on the list from the
     * cache, or while the tailnet is still coming up, waits for the connection instead of being
     * dropped; the terminal only appears for the session that was asked for.
     */
    private fun enterSession(what: Opening, open: suspend PocketSession.() -> Unit) {
        if (BuildConfig.DEBUG) Log.i(CONN_TAG, "open $what (busy=${_opening.value}, phase=${_pocket.value.phase})")
        if (_opening.value != null) return
        // A host that cannot be dialed at all (a tailnet host with no joined node) fails before a
        // connection exists; waiting for one would leave the row saying *Opening* forever.
        if (_pocket.value.phase == Phase.Failed) return
        val waiting = sessionReady
        _opening.value = what
        val tappedAt = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            try {
                val s = waiting.await()
                s.awaitReady()
                s.open()
                if (BuildConfig.DEBUG) Log.i(CONN_TAG, "opened $what after ${SystemClock.elapsedRealtime() - tappedAt}ms")
                if (session === s) route.value = Route.Term
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.i(CONN_TAG, "open $what failed: ${e.javaClass.simpleName}: ${e.message}")
                session?.note(e.toProblem())
            } finally {
                if (_opening.value == what) _opening.value = null
            }
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
        if (openedFromAlert || route.value != Route.Home || session != null) return
        val target = store.lastHost()?.let { store.find(it) } ?: return
        connect(target, resume = true)
    }

    /** The shown pane's bytes for as long as the screen collects them; see [PocketSession.watchPane]. */
    fun watchPane(blockId: String): Flow<TerminalFeed> = session?.watchPane(blockId) ?: emptyFlow()

    fun retryNow() {
        Diagnostics.event(CONN_TAG, "retry asked for")
        session?.retryNow()
    }

    fun dismissNotice(id: Long) {
        session?.dismissNotice(id)
    }

    /**
     * Polling a screen nobody sees only drains the battery. Coming back, the node is told to find its
     * paths again and the session checks its connection, because Android may have cut both while
     * the app was away.
     */
    fun setForeground(visible: Boolean) {
        val returning = visible && !foreground
        foreground = visible
        if (returning && host?.viaTailscale == true) viewModelScope.launch(Dispatchers.IO) { tailscaleBox.resetNetwork() }
        session?.setActive(visible)
    }

    /**
     * The report the diagnostics sheet hands out: the phone, the hosts it knows, what the
     * connection was doing, and the log of how it got there. The addresses and the names the person
     * gave their hosts are replaced on the way out, and tab titles and terminal contents never
     * enter it at all.
     */
    fun diagnosticReport(limit: Int = Int.MAX_VALUE): String {
        val state = pocket.value
        val open = host
        val facts = buildList {
            add(
                ReportedFact(
                    "connection",
                    buildString {
                        append(if (open == null) "idle" else if (open.viaTailscale) "tailnet" else "local")
                        append(" · ").append(state.phase)
                        if (state.resumed) append(" · resumed")
                        if (state.owning) append(" · owns the pane")
                    },
                ),
            )
            add(ReportedFact("route", route.value.toString().lowercase()))
            state.problem?.let { add(ReportedFact("problem", it.describe())) }
            state.notice?.let { add(ReportedFact("notice", it.problem.describe())) }
            add(ReportedFact("sessions", "${state.sessions.size} listed · ${state.views.size} open"))
            state.view?.let { session ->
                val tab = session.activeWindow()
                add(ReportedFact("tabs", "${session.windows.size} in the session · ${tab?.blocks?.size ?: 0} panes in the shown one"))
            }
            state.programs.values.toSortedSet().takeIf { it.isNotEmpty() }?.let {
                add(ReportedFact("programs", it.joinToString(", ")))
            }
            for (known in hosts.value) {
                add(
                    ReportedFact(
                        "host",
                        buildString {
                            append(known.label).append(" · ").append(known.host).append(':').append(known.port)
                            append(if (known.plaintext) " · plaintext" else " · tls")
                            append(if (known.viaTailscale) " · tailnet" else " · local")
                            append(if (known.id == open?.id) " · connected" else "")
                        },
                    ),
                )
            }
            val node = tailscale.value
            add(
                ReportedFact(
                    "tailnet",
                    when {
                        !node.enabled -> "off"
                        tailnetError.value != null -> "failed · ${tailnetError.value}"
                        else -> "${tailscaleStatus.value.backendState.ifBlank { "starting" }} · node ${node.hostname.ifBlank { "default" }}"
                    },
                ),
            )
            tailnetPath.value?.let { add(ReportedFact("tailnet path", it.toString())) }
            add(ReportedFact("alerts", if (alerts.value) "on" else "off"))
            add(ReportedFact("foreground", if (foreground) "yes" else "no"))
            add(ReportedFact("font", "${(fontScale.value * 100).toInt()}%"))
            upload.value?.let { file ->
                add(ReportedFact("upload", "${file.phase} ${file.sent}/${file.total}${file.error?.let { " · $it" }.orEmpty()}"))
            }
        }
        // What identifies the phone's networks, and nothing about the bug.
        val secrets = buildList {
            for (known in hosts.value) {
                add(known.label)
                add(known.host)
            }
            open?.let {
                add(it.label)
                add(it.host)
            }
            add(tailscale.value.hostname)
        }
        return Diagnostics.report(getApplication(), facts, secrets, limit)
    }

    fun disconnect() {
        Diagnostics.event(CONN_TAG, "disconnect")
        disconnectQuiet()
        Alerts.unwatch(getApplication())
        store.setLastHost(null)
        route.value = Route.Home
    }

    /** Back from the terminal: the host list stays connected so its live sessions are still there. */
    fun showHome() {
        route.value = Route.Home
    }

    fun setFont(scale: Float) {
        val next = scale.coerceIn(0.7f, 1.8f)
        fontScale.value = next
        store.setFontScale(next)
    }

    /**
     * Sends a file or image the picker returned to the pane shown when it was picked. The copy in
     * the cache is what is sent, so the picker's stream closing later cannot shorten the upload;
     * it is deleted once the pill is dismissed.
     */
    fun attachUpload(uri: Uri) {
        val s = session ?: return
        if (_upload.value?.running == true) return
        // The pane on screen now is the one the file is for, even if staging or the picker outlives it.
        val sessionId = _pocket.value.activeSessionId ?: return
        val blockId = _pocket.value.view?.focusedBlock()?.id ?: return
        val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
        uploadJob?.cancel()
        // A failure or an earlier sent file is done with; its staged copy goes too.
        clearUpload()
        _upload.value = UploadStatus(name, UploadPhase.Preparing)
        uploadJob = viewModelScope.launch {
            val staged = try {
                stageUpload(getApplication(), uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _upload.value = UploadStatus(name, UploadPhase.Failed, error = e.message ?: "Could not read this file")
                return@launch
            }
            uploadFile = staged.file
            runUpload(s, staged.file, staged.name, sessionId, blockId)
        }
    }

    /** Sends again after a failure that did not reach the half-close; never after an uncertain one. */
    fun retryUpload() {
        val s = session ?: return
        val file = uploadFile ?: return
        val name = _upload.value?.name ?: return
        if (_upload.value?.phase != UploadPhase.Failed) return
        if (_upload.value?.retryable != true) return
        val sessionId = uploadSession ?: return
        val blockId = uploadBlock ?: return
        uploadJob = viewModelScope.launch { runUpload(s, file, name, sessionId, blockId) }
    }

    /** Stops the upload. Past the half-close Rex may already have the file, so that is unsure. */
    fun cancelUpload() {
        val current = _upload.value ?: return
        if (!current.running) {
            clearUpload()
            return
        }
        uploadJob?.cancel()
        uploadJob = null
        if (current.phase == UploadPhase.Finishing) {
            _upload.value = current.copy(phase = UploadPhase.Uncertain)
        } else {
            clearUpload()
        }
    }

    fun dismissUpload() {
        if (_upload.value?.running == true) cancelUpload() else clearUpload()
    }

    private suspend fun runUpload(
        s: PocketSession,
        file: File,
        name: String,
        sessionId: String,
        blockId: String,
    ) {
        uploadSession = sessionId
        uploadBlock = blockId
        _upload.value = UploadStatus(name, UploadPhase.Preparing)
        try {
            s.upload(file, name, sessionId, blockId).collect { progress ->
                _upload.value = UploadStatus(name, progress.phase, progress.sent, progress.total, progress.error, retryable = true)
                if (progress.phase == UploadPhase.Done) {
                    delay(UPLOAD_DONE_MS)
                    if (_upload.value?.phase == UploadPhase.Done) clearUpload()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _upload.value = UploadStatus(name, UploadPhase.Failed, error = e.message ?: "Upload failed", retryable = uploadFile != null)
        }
    }

    private fun clearUpload() {
        val file = uploadFile
        uploadFile = null
        uploadJob = null
        uploadSession = null
        uploadBlock = null
        _upload.value = null
        if (file != null) teardown.launch(Dispatchers.IO) { file.delete() }
    }

    fun act(block: suspend PocketSession.() -> Unit) {
        val s = session ?: return
        viewModelScope.launch {
            try {
                s.block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                s.note(e.toProblem())
            }
        }
    }

    private fun disconnectQuiet() {
        connectJob?.cancel()
        connectJob = null
        pathJob?.cancel()
        _tailnetPath.value = null
        // A tap waiting for the old connection must not open anything on the next one.
        sessionReady.cancel()
        sessionReady = CompletableDeferred()
        _opening.value = null
        watch?.cancel()
        watch = null
        uploadJob?.cancel()
        clearUpload()
        val closing = session
        session = null
        sessionJob?.cancel()
        _pocket.value = PocketState()
        // The teardown waits for a disown round trip; leaving the screen must not wait with it, and it
        // must still run when the view model itself is going away, so it is not on viewModelScope.
        if (closing != null) teardown.launch { runCatching { closing.stop() } }
    }
}

/**
 * Runs a session's teardown after whatever started it is gone. The disown it waits for is one
 * round trip with a time limit, so nothing here outlives the process by more than that.
 */
private val teardown = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** How long after a connection goes live the path is measured: Tailscale needs a moment to go direct. */
private const val PATH_SETTLE_MS = 3_000L

/** How long a sent file stays on screen as green before the pill clears itself. */
private const val UPLOAD_DONE_MS = 1_500L

/** How long a connection waits for the tailnet node to come up before dialing anyway. */
internal const val NODE_READY_MS = 30_000L

/** How long a running node may take to list the host as a peer before it is dialed anyway. */
private const val PEER_READY_MS = 10_000L

/** What a failure says in the report: its kind, then whatever the exception carried. */
private fun Problem.describe(): String = trouble.toString() + (detail?.let { ": $it" } ?: "")

/** Connection diagnostics: `adb logcat -s RexPocketConn`. */
internal const val CONN_TAG = "RexPocketConn"
