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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import me.leaver.rexpocket.core.Appearance
import me.leaver.rexpocket.core.GestureSettings
import me.leaver.rexpocket.core.Endpoint
import me.leaver.rexpocket.core.AlertKind
import me.leaver.rexpocket.core.AlertMove
import me.leaver.rexpocket.core.AlertPlanner
import me.leaver.rexpocket.core.AlertPrefs
import me.leaver.rexpocket.core.BellMove
import me.leaver.rexpocket.core.PaneSignal
import me.leaver.rexpocket.core.plainText
import me.leaver.rexpocket.core.FileUpload
import me.leaver.rexpocket.core.UploadPhase
import me.leaver.rexpocket.R
import me.leaver.rexpocket.alerts.Alert
import me.leaver.rexpocket.alerts.AlertAction
import me.leaver.rexpocket.alerts.AlertTarget
import me.leaver.rexpocket.alerts.Alerts
import me.leaver.rexpocket.core.CtrlCKey
import me.leaver.rexpocket.core.KeyArrangement
import me.leaver.rexpocket.core.ListedSession
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.PocketSession
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.Problem
import me.leaver.rexpocket.core.SshCredentials
import me.leaver.rexpocket.core.SshSettings
import me.leaver.rexpocket.core.SshTunnel
import me.leaver.rexpocket.core.TailnetBackend
import me.leaver.rexpocket.core.TailnetPath
import me.leaver.rexpocket.core.Trouble
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.Flow
import me.leaver.rexpocket.core.TerminalFeed
import me.leaver.rexpocket.core.UpdateChannel
import me.leaver.rexpocket.core.toProblem
import me.leaver.rexpocket.core.RexClient
import me.leaver.rexpocket.data.HostStore
import me.leaver.rexpocket.data.FontFiles
import me.leaver.rexpocket.data.FontCatalog
import me.leaver.rexpocket.data.SYSTEM_FONT
import me.leaver.rexpocket.data.TerminalFontState
import me.leaver.rexpocket.data.downloadedTypeface
import me.leaver.rexpocket.data.SavedHost
import me.leaver.rexpocket.data.HomeFolds
import me.leaver.rexpocket.data.TailnetSettings
import me.leaver.rexpocket.core.ReportedFact
import me.leaver.rexpocket.diagnostics.Diagnostics
import me.leaver.rexpocket.net.NetworkWatcher
import me.leaver.rexpocket.net.TailnetNodes
import me.leaver.rexpocket.core.ConnectProgress
import me.leaver.rexpocket.core.ConnectStep
import me.leaver.rexpocket.core.TailnetStage
import me.leaver.rexpocket.core.explainTailnetProblem
import me.leaver.rexpocket.core.tailnetStage
import me.leaver.rexpocket.net.NetbirdConfig
import me.leaver.rexpocket.net.TailnetConfig
import me.leaver.rexpocket.net.TailnetNode
import me.leaver.rexpocket.net.TailnetStatus
import me.leaver.rexpocket.net.TailscaleConfig
import me.leaver.rexpocket.update.InstallResult
import me.leaver.rexpocket.update.UpdateState
import me.leaver.rexpocket.update.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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

/**
 * [saved] is the state Android keeps for the activity when it kills the process in the background:
 * here only whether the terminal was on screen, which is what makes a reopen at launch a
 * continuation rather than a surprise.
 */
class RexViewModel @JvmOverloads constructor(
    app: Application,
    private val saved: SavedStateHandle = SavedStateHandle(),
    /** The tailnet nodes; a test gives its own, since the real ones need the Go library. */
    private val nodes: TailnetNodes = TailnetNodes.of(app),
) : AndroidViewModel(app) {
    private val store = HostStore(app)

    /** True when this process was started again after Android killed it while the terminal was on screen. */
    private val terminalWasOpen = saved.get<Boolean>(KEY_TERMINAL_OPEN) == true
    private val updater = Updater(app)

    /** The newer build the app found, from the check through to the downloaded APK. */
    val update: StateFlow<UpdateState> = updater.state

    /** Which builds the updater offers: finished ones only, or the newest published as well. */
    val updateChannel: StateFlow<UpdateChannel> = updater.channel
    private var updateJob: Job? = null
    val hosts = MutableStateFlow(store.list())
    val tailnet = MutableStateFlow(store.tailnet())

    /** The backends this build can run; see [TailnetNodes.of]. */
    val tailnetBackends: List<TailnetBackend> by lazy { nodes.available }

    /** The status of the backend that is selected, so the home screen follows the choice. */
    val tailnetStatus: StateFlow<TailnetStatus> = combine(
        tailnet,
        combine(nodes.registered.map { backend -> nodes[backend].status.map { backend to it } }) { it.toMap() },
    ) { settings, statuses ->
        statuses[settings.backend] ?: statuses.getValue(TailnetBackend.Tailscale)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TailnetStatus())

    /**
     * Where the joined node is on its way to the network, as the home screen tells it: starting,
     * signing in, connecting, connected as a device - or why it is not. See [tailnetStage].
     */
    val tailnetStage: StateFlow<TailnetStage> by lazy {
        combine(tailnet, tailnetStatus, tailnetError) { settings, status, error ->
            tailnetStage(
                joined = settings.enabled,
                backendState = status.backendState,
                authUrl = status.authUrl,
                device = status.self?.name.orEmpty(),
                network = status.networkName,
                // Blank when the node said nothing: the screen then words it (tailnet_start_failed).
                error = error?.let { it.detail.orEmpty() },
            )
        }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, TailnetStage.Off)
    }

    /**
     * How far the connection to the open host has come, step by step, while it is on its way;
     * null once it is live, and for a host that is not being connected.
     */
    private val _connectProgress = MutableStateFlow<ConnectProgress?>(null)
    val connectProgress: StateFlow<ConnectProgress?> = _connectProgress

    /** Moves the connection to [host] on to [step]; a connection that is not on its way stays as it is. */
    private fun progress(host: String, step: ConnectStep) {
        while (true) {
            val current = _connectProgress.value ?: return
            if (current.host != host) return
            val next = current.next(step, System.currentTimeMillis())
            if (next === current) return
            if (_connectProgress.compareAndSet(current, next)) {
                Diagnostics.event(CONN_TAG, "connect step $step after ${current.step} ${next.took.last().second}ms")
                return
            }
        }
    }

    /**
     * The connection's state with its problem told from what the tailnet node knows, for a host on
     * the tailnet: an offline device, a name the tailnet does not have, a port nothing listens on, a
     * node that needs a sign-in. See [explainTailnetProblem].
     */
    private fun explained(st: PocketState, target: SavedHost): PocketState {
        val problem = st.problem ?: return st
        if (!target.viaTailscale || st.phase == Phase.Live) return st
        val status = tailnetStatus.value
        val dial = runCatching { nodeFor(tailnet.value.backend).dialError(target.host) }.getOrNull()
        val better = explainTailnetProblem(problem, target.host, target.port, status.backendState, status.peers, status.peersKnown, dial)
        return if (better == problem) st else st.copy(problem = better)
    }

    /** The node of [backend]: only one of them runs at a time. */
    private fun nodeFor(backend: TailnetBackend): TailnetNode = nodes[backend]

    /** The node of the selected backend. */
    private val activeNode: TailnetNode get() = nodeFor(tailnet.value.backend)

    /** The credentials and settings a backend's node starts with. */
    private fun configFor(settings: TailnetSettings, setupKey: String = ""): TailnetConfig = when (settings.backend) {
        TailnetBackend.Tailscale -> TailscaleConfig(authKey = "", hostname = settings.hostname)
        TailnetBackend.Netbird, TailnetBackend.NetbirdAddon -> NetbirdConfig(
            setupKey = setupKey,
            hostname = settings.hostname,
            managementUrl = settings.managementUrl,
        )
    }

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
            val path = withContext(Dispatchers.IO) { activeNode.probePath(target.host) }
            _tailnetPath.value = path
            Diagnostics.event(CONN_TAG, "tailnet path to ${target.host}: $path")
        }
    }
    val route = MutableStateFlow<Route>(Route.Home)

    /**
     * Set while a connection may still take the person to the pane they had open: the launch after
     * the process was killed on the terminal, or a reconnect asked for. Anything the person does in
     * the meantime - a tap, a key, another host - ends it, so finishing the connection never pulls
     * them out of the screen they went to.
     */
    private var resumePending = false

    /**
     * Whether the connection now open (or failed) was asked to go back to the terminal. A retry or
     * a confirmed host key connects again with the same wish, or with the terminal when it is on
     * screen: tapping Retry on the list is not a continuation, and must not jump into a tab.
     */
    private var connectedToResume = false

    private fun resumeAgain(): Boolean = connectedToResume || route.value == Route.Term

    /** Whether the launch reopens the last tab even after a fresh start. Off by default. */
    val reopenLastTab = MutableStateFlow(store.reopenLastTab())

    fun setReopenLastTab(on: Boolean) {
        store.setReopenLastTab(on)
        reopenLastTab.value = on
    }

    /** Settings › Appearance; RexTheme reads it, so a change shows at once, with no new activity. */
    val appearance = MutableStateFlow(store.appearance())

    fun setAppearance(value: Appearance) {
        store.setAppearance(value)
        appearance.value = value
    }

    /**
     * The person touched the screen or pressed a key: whatever they do next is their own, so a
     * resume still on its way is dropped. RexApp calls this for every touch and key.
     */
    fun userActed() {
        if (resumePending) Diagnostics.event(CONN_TAG, "resume dropped: the person moved first")
        resumePending = false
    }

    /** The folds of the home list, kept across going to a terminal, rotation and restarts. */
    val folds = MutableStateFlow(store.folds())

    fun setHostFolded(hostId: String, folded: Boolean) {
        val current = folds.value
        val next = current.copy(hosts = if (folded) current.hosts + hostId else current.hosts - hostId)
        store.setFolds(next)
        folds.value = next
    }

    fun setSessionFolded(hostId: String, sessionId: String, folded: Boolean) {
        val current = folds.value
        val key = HomeFolds.sessionKey(hostId, sessionId)
        val next = current.copy(sessions = if (folded) current.sessions + key else current.sessions - key)
        store.setFolds(next)
        folds.value = next
    }

    /** Drops the folds of [hostId]'s sessions that are not in [alive], so gone sessions do not pile up. */
    private fun pruneFolds(hostId: String, alive: Collection<String>) {
        val current = folds.value
        val next = current.keepSessions(hostId, alive)
        if (next == current) return
        store.setFolds(next)
        folds.value = next
    }
    private val _pocket = MutableStateFlow(PocketState())
    val pocket: StateFlow<PocketState> = _pocket
    val fontScale = MutableStateFlow(store.fontScale())
    private val fontRoot = File(app.filesDir, "terminal-fonts")
    private val fontFiles = FontCatalog.fonts.associate { it.id to FontFiles(fontRoot, it) }
    private val fontFaces = mutableMapOf<String, Typeface>()
    val terminalTypeface = MutableStateFlow(Typeface.MONOSPACE)
    internal val terminalFont = MutableStateFlow(TerminalFontState())

    internal fun selectTerminalFont(id: String) {
        if (terminalFont.value.initializing || terminalFont.value.downloading != null) return
        if (id == SYSTEM_FONT) {
            terminalTypeface.value = Typeface.MONOSPACE
            store.setTerminalFont(SYSTEM_FONT)
            terminalFont.value = terminalFont.value.copy(selected = SYSTEM_FONT, failed = null)
            return
        }
        val font = FontCatalog.find(id) ?: return
        terminalFont.value = terminalFont.value.copy(downloading = id, failed = null, progress = 0f)
        viewModelScope.launch {
            try {
                val face = fontFaces[id] ?: withContext(Dispatchers.IO) {
                    val files = fontFiles.getValue(id).install(progress = { progress ->
                        terminalFont.value = terminalFont.value.copy(progress = progress)
                    })
                    downloadedTypeface(files, font)
                }
                fontFaces[id] = face
                terminalTypeface.value = face
                store.setTerminalFont(id)
                terminalFont.value = terminalFont.value.copy(
                    selected = id, available = terminalFont.value.available + id, downloading = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                terminalFont.value = terminalFont.value.copy(downloading = null, failed = id)
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

    /** True when the key bar is kept on screen with a keyboard attached, which is not the default. */
    val keyBar = MutableStateFlow(store.keyBarAlways())
    /** The key bar's keys in the person's order, and which show. */
    val keyArrangement = MutableStateFlow(store.keyArrangement())
    val keyBarCtrlC = MutableStateFlow(store.keyBarCtrlC())
    /** What the terminal's tap, double tap and long press do, and their times. */
    val gestures = MutableStateFlow(store.gestures())
    /** Which kinds of alert are on: the Notifications page's switches. */
    val alertPrefs = MutableStateFlow(store.alertPrefs())
    private val planner = AlertPlanner()

    /** This model's answer to a notification's Reply and Interrupt, while it holds the connection. */
    private val alertActions: suspend (AlertTarget, AlertAction) -> Boolean = { target, action -> onAlertAction(target, action) }
    var host: SavedHost? = null
        private set

    private var session: PocketSession? = null
    private var sshTunnel: SshTunnel? = null
    private var foreground = true
    private var sessionJob: Job? = null
    private var watch: Job? = null
    private var connectJob: Job? = null
    private var tailnetJob: Job? = null
    private val _tailnetError = MutableStateFlow<TailnetFailure?>(null)
    val tailnetError: StateFlow<TailnetFailure?> = _tailnetError

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
        // Kept for Android to hand back if it kills the process: see [terminalWasOpen].
        viewModelScope.launch { route.collect { saved[KEY_TERMINAL_OPEN] = it == Route.Term } }
        viewModelScope.launch {
            val saved = store.terminalFont()
            val (available, face) = withContext(Dispatchers.IO) {
                val available = fontFiles.filterValues { it.available() }.keys.toSet()
                val font = FontCatalog.find(saved)
                val face = if (saved in available && font != null) runCatching {
                    downloadedTypeface(fontFiles.getValue(saved).directory, font)
                }.getOrNull() else null
                available to face
            }
            val selected = if (face != null) saved else SYSTEM_FONT
            if (face != null) {
                fontFaces[saved] = face
                terminalTypeface.value = face
            }
            store.setTerminalFont(selected)
            terminalFont.value = TerminalFontState(
                selected = selected, available = available, initializing = false,
                failed = saved.takeIf { it != SYSTEM_FONT && face == null },
            )
        }
        Alerts.actions = alertActions
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
    fun setKeyBar(always: Boolean) {
        keyBar.value = always
        store.setKeyBarAlways(always)
    }

    fun setKeyArrangement(next: KeyArrangement) {
        store.setKeyArrangement(next)
        keyArrangement.value = next
    }

    fun setKeyBarCtrlC(choice: CtrlCKey) {
        store.setKeyBarCtrlC(choice)
        keyBarCtrlC.value = choice
    }

    fun setGestures(next: GestureSettings) {
        store.setGestures(next)
        gestures.value = next
    }

    fun setAlertPrefs(next: AlertPrefs) {
        store.setAlertPrefs(next)
        alertPrefs.value = next
    }

    fun setAlerts(on: Boolean) {
        Alerts.enabled.value = on
        val open = host
        if (on && open != null) Alerts.watch(getApplication(), open.label)
    }

    override fun onCleared() {
        network.stop()
        if (Alerts.actions === alertActions) Alerts.actions = null
        Alerts.unwatch(getApplication())
        // The pane goes back to the desktop's size; viewModelScope is already cancelled here.
        disconnectQuiet()
        nodes.all.forEach { it.stopAsync() }
        super.onCleared()
    }

    /**
     * Joins the tailnet of [backend] under [hostname] (blank means the device's own name). The node
     * starts here, not on the first connect, so the app can hand out the login link while the user is
     * looking at the Tailnet row; a node that is already logged in just comes back up. A Netbird node
     * that has not joined yet needs its [setupKey]; the key is used once and never stored.
     */
    fun joinTailnet(backend: TailnetBackend, hostname: String, managementUrl: String = "", setupKey: String = "") {
        Diagnostics.event(CONN_TAG, "joining the ${backend.storageKey} tailnet as ${hostname.trim().ifBlank { "default" }}")
        val previous = tailnet.value
        store.setTailnet(TailnetSettings(backend, enabled = true, hostname = hostname.trim(), managementUrl = managementUrl.trim()))
        val next = store.tailnet()
        tailnet.value = next
        // Only one node runs: the one that was joined before gives way.
        if (previous.enabled && previous.backend != backend) nodeFor(previous.backend).stopAsync()
        startTailnet(next, setupKey)
    }

    /** Brings the selected node up for [settings]; the login it already has makes this a plain restart. */
    private fun startTailnet(settings: TailnetSettings, setupKey: String = "") {
        tailnetJob?.cancel()
        _tailnetError.value = null
        tailnetJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { nodeFor(settings.backend).start(configFor(settings, setupKey)) }
                    .onFailure { _tailnetError.value = TailnetFailure(it.message) }
            }
        }
    }

    /** Starts the joined node again after its start failed: the home screen's Try again. */
    fun restartTailnet() {
        val current = tailnet.value
        if (!current.enabled) return
        Diagnostics.event(CONN_TAG, "restarting the tailnet")
        startTailnet(current)
    }

    /** Leaves the tailnet: the node stops and its login stays in the app for the next join. */
    fun leaveTailnet() {
        Diagnostics.event(CONN_TAG, "leaving the tailnet")
        val current = tailnet.value
        // The name is kept: rejoining later should not ask for it again.
        store.setTailnet(current.copy(enabled = false))
        tailnet.value = store.tailnet()
        tailnetJob?.cancel()
        nodeFor(current.backend).stopAsync()
    }

    fun saveHost(host: SavedHost) {
        store.upsert(host)
        hosts.value = store.list()
    }

    fun deleteHost(id: String) {
        store.delete(id)
        hosts.value = store.list()
        val current = folds.value
        val next = current.keepSessions(id, emptyList()).copy(hosts = current.hosts - id)
        store.setFolds(next)
        folds.value = next
    }

    /**
     * Remembers the SSH host key the person confirmed and connects again. An unknown key and a
     * changed one take the same path: the dialog says which it was before this is called.
     */
    fun trustSshHostKey(fingerprint: String) {
        val current = host ?: return
        if (!current.viaSsh || fingerprint.isBlank()) return
        val updated = current.copy(sshHostKey = fingerprint)
        saveHost(updated)
        host = updated
        Diagnostics.event(CONN_TAG, "ssh host key confirmed for ${current.host}")
        connect(updated, resume = resumeAgain())
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
        resumePending = resume
        connectedToResume = resume
        val settings = store.tailnet()
        val endpoint = target.endpoint()
        _connectProgress.value = null
        if (target.viaTailscale && !settings.enabled) {
            // The host is saved either way; without a joined node there is no route to it.
            Diagnostics.event(CONN_TAG, "${target.label}: the tailnet is not joined")
            _pocket.value = PocketState(
                phase = Phase.Failed,
                endpointLabel = endpoint.label(),
                problem = Problem(Trouble.TailnetNotJoined, null),
            )
            return
        }
        val viaTailscale = target.viaTailscale
        Diagnostics.event(CONN_TAG, "connect ${endpoint.label()} tailnet=$viaTailscale ssh=${target.viaSsh} plaintext=${target.plaintext} resume=$resume")
        // Draw the list the host had last time straight away; the live one replaces it below.
        val cached = if (resume) emptyList() else store.cachedSessions(target.id)
        _pocket.value = PocketState(
            phase = Phase.Connecting,
            endpointLabel = endpoint.label(),
            sessions = cached,
        )
        if (Alerts.enabled.value) Alerts.watch(getApplication(), target.label)
        _connectProgress.value = ConnectProgress(
            host = target.host,
            viaTailnet = viaTailscale,
            step = if (viaTailscale) ConnectStep.StartTailnet else ConnectStep.Reach,
            startedAt = System.currentTimeMillis(),
        )
        connectJob = viewModelScope.launch {
            // A host on the phone's own network is dialed directly, and the tailnet node stays as it
            // is: the home screen says the phone is in a tailnet, so that must not depend on which
            // host happens to be open.
            // The node has to be up before Rex is dialed, because the Rex connection itself is
            // what goes through it. Joining the tailnet takes a moment on the first connect.
            val node = nodeFor(settings.backend)
            val socksPort = if (viaTailscale) {
                val port = try {
                    withContext(Dispatchers.IO) { node.start(configFor(settings)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _pocket.value = PocketState(
                        phase = Phase.Failed,
                        endpointLabel = endpoint.label(),
                        problem = Problem(Trouble.Tailnet, e.message),
                    )
                    return@launch
                }
                // The node reports Running only once its WireGuard session is up; dialing before
                // that just gets a SOCKS failure, which looks like a broken tunnel.
                val settled = withTimeoutOrNull(NODE_READY_MS) {
                    node.status.first {
                        it.backendState in setOf("Running", "NeedsLogin", "NeedsMachineAuth", "Stopped")
                    }
                }
                // Running comes before the first netmap, and a MagicDNS name dialed before the
                // netmap lists that peer fails in the node's DNS: that was the SOCKS error the first
                // open showed for a moment. Waiting for the peer costs nothing once it is known.
                if (settled?.backendState == "Running") {
                    progress(target.host, ConnectStep.FindPeer)
                    // A node that has its device list and no such name is not going to learn it in
                    // the next ten seconds: dial soon, and let the failure say the name is unknown.
                    val wait = if (node.status.value.peersKnown) PEER_LISTED_MS else PEER_READY_MS
                    if (!node.awaitPeer(target.host, wait)) {
                        Diagnostics.event(CONN_TAG, "${target.host} is not in the netmap yet; dialing anyway")
                    }
                }
                progress(target.host, ConnectStep.Reach)
                port
            } else {
                0
            }
            // A node in another app (the Netbird add-on) has no proxy: it dials for the connection.
            val dialer = if (viaTailscale) node.dialer else null
            val trace = { line: String -> if (BuildConfig.DEBUG) Log.i(CONN_TAG, line) }
            // The SSH tunnel is per host and outlives a reconnect attempt: only the channels under it
            // come and go. Every Rex connection of this host goes through the same route.
            val ssh = if (target.viaSsh) {
                SshTunnel(
                    SshSettings(
                        host = target.host,
                        port = target.port,
                        user = target.sshUser,
                        credentials = SshCredentials(
                            password = target.sshPassword.takeIf { it.isNotEmpty() },
                            privateKey = target.sshPrivateKey.takeIf { it.isNotEmpty() },
                            passphrase = target.sshPassphrase.takeIf { it.isNotEmpty() },
                        ),
                        hostKey = target.sshHostKey.takeIf { it.isNotEmpty() },
                        socksPort = socksPort,
                        dialer = dialer,
                    ),
                    trace,
                ).also { sshTunnel = it }
            } else {
                null
            }
            val httpFactory: () -> OkHttpClient = { ssh?.http() ?: RexClient.defaultHttp(socksPort, dialer) }
            val s = PocketSession(
                endpoint,
                httpFactory = httpFactory,
                // The SSH client also carries the transport's own reason for a failure OkHttp hides.
                clientFactory = { if (ssh != null) RexClient.forSsh(ssh) else RexClient(httpFactory()) },
                // Uploads reserve a file on their own revision-11 connection, over the same route.
                uploadClientFactory = {
                    if (ssh != null) RexClient.forSsh(ssh, FileUpload.API_REVISION)
                    else RexClient(httpFactory(), apiRevision = FileUpload.API_REVISION)
                },
                cachedSessions = cached,
                trace = trace,
            )
            session = s
            sessionReady.complete(s)
            s.setActive(foreground)
            s.setWatching(Alerts.enabled.value)
            sessionJob = s.start(viewModelScope, previousSession)
            watch = viewModelScope.launch { follow(s, target, if (resume) previousSession else null) }
        }
    }

    /**
     * Everything that follows the connection's state, one flow per job: the screen shows every
     * state, and the store only hears about what changed. Ends with the connection.
     */
    private suspend fun follow(s: PocketSession, target: SavedHost, resumeTo: String?) = coroutineScope {
        val hostId = target.id
        // The node's view of the host can change while the connection waits to retry - it signs in,
        // the device comes online - so the problem is told again on either.
        launch {
            combine(s.state, tailnetStatus) { st, _ -> st }.collect { st ->
                _pocket.value = explained(st, target)
                // Rex answered the connection: the host was reached, and what is left is its sessions.
                // (OkHttp's own socket events cannot tell: a WebSocket call drops its event listener.)
                if (st.clientId != null && st.phase != Phase.Live) progress(target.host, ConnectStep.OpenRex)
                if (st.phase == Phase.Live) {
                    _connectProgress.value?.let { p ->
                        Diagnostics.event(CONN_TAG, "connected in ${System.currentTimeMillis() - p.startedAt}ms: " + p.took.joinToString { "${it.first} ${it.second}ms" })
                    }
                    _connectProgress.value = null
                }
            }
        }
        // The session a resume goes back to is the one that was on the terminal: connecting selects
        // one on its own (the remembered one, or Rex's first), and that one was never on screen.
        launch {
            combine(s.state, route) { st, r -> if (r == Route.Term) st.activeSessionId else null }
                .filterNotNull().distinctUntilChanged()
                .collect { store.setLastSession(hostId, it) }
        }
        launch {
            s.state.map { it.sessions }.filter { it.isNotEmpty() }.distinctUntilChanged()
                .collect { sessions ->
                    store.setCachedSessions(hostId, sessions)
                    if (s.state.value.phase == Phase.Live) pruneFolds(hostId, sessions.map { it.id })
                }
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
        launch { s.signals.collect { onSignal(s, hostId, it) } }
    }

    /**
     * Turns the panes' status into alerts. Every change is noted, so a request already on screen is
     * not news later; only one that arrives while the app is away is raised. An alert goes once its
     * pane stops waiting, or once the person opens that pane. See docs/design/notifications.md.
     */
    private suspend fun raiseAlerts(s: PocketSession, hostId: String) {
        val app = getApplication<Application>()
        combine(s.state, route, alertPrefs) { st, r, p -> Triple(st, r, p) }.collect { (st, r, prefs) ->
            // Away and watching: with alerts off, nothing keeps the connection up to tell anyway.
            val away = !foreground && Alerts.enabled.value
            for (move in planner.status(st.blockStatus, away, prefs)) {
                when (move) {
                    is AlertMove.Cancel -> Alerts.settle(app, move.block)
                    is AlertMove.Post -> alertFor(app, st, hostId, move)?.let { Alerts.raise(app, it) }
                }
            }
            if (r == Route.Term && foreground) shownPane(st)?.let { (_, block) -> seen(app, block) }
        }
    }

    /** The person is looking at [block]: whatever its notification said, they can see it now. */
    private fun seen(app: Application, block: String) {
        planner.seen(block)
        Alerts.settle(app, block)
    }

    /** A pane's bell or desktop notification: a buzz on screen, a notification while away. */
    private fun onSignal(s: PocketSession, hostId: String, signal: PaneSignal) {
        val app = getApplication<Application>()
        val st = s.state.value
        val prefs = alertPrefs.value
        val away = !foreground && Alerts.enabled.value
        when (signal) {
            is PaneSignal.Bell -> {
                val onScreen = foreground && route.value == Route.Term && shownPane(st)?.second == signal.blockId
                when (planner.bell(signal.blockId, onScreen, away, prefs)) {
                    BellMove.Vibrate -> Alerts.buzz(app)
                    BellMove.Notify -> {
                        val text = app.getString(R.string.alerts_bell)
                        alertFor(app, st, hostId, AlertMove.Post(signal.blockId, AlertKind.Message, loud = true, bell = true), text)?.let { Alerts.raise(app, it) }
                    }
                    BellMove.Nothing -> Unit
                }
            }
            is PaneSignal.Note -> {
                val move = planner.message(signal.blockId, away, prefs) as? AlertMove.Post ?: return
                val title = plainText(signal.title, max = 80)
                val body = plainText(signal.body) ?: title ?: return
                val text = if (title != null && title != body) "$title: $body" else body
                alertFor(app, st, hostId, move, text)?.let { Alerts.raise(app, it) }
            }
        }
    }

    /**
     * The notification for [move]: titled by the pane and tab, which are the app's own words, so a
     * program cannot pass itself off as another pane's (Program Status Protocol, Security). Its
     * own words - the status's message, a notification's text - are the body, as plain text.
     */
    private fun alertFor(app: Application, st: PocketState, hostId: String, move: AlertMove.Post, message: String? = null): Alert? {
        val block = move.block
        val record = st.blockStatus[block]
        val view = st.views.values.firstOrNull { v -> v.allBlocks().any { it.id == block } } ?: return null
        val info = view.allBlocks().first { it.id == block }
        val name = paneTitle(info, record, st.programs[block])
        val text = message ?: run {
            record ?: return null
            val state = statusWordRes(record)?.let { app.getString(it) } ?: app.getString(R.string.alerts_failed)
            plainText(record.msg)?.let { "$state: $it" } ?: state
        }
        val session = st.sessions.firstOrNull { it.id == view.sessionId }?.label ?: view.label
        val busy = block in st.busy
        return Alert(
            target = AlertTarget(hostId, view.sessionId, block),
            title = "$name · ${view.label}",
            text = text,
            host = host?.label.orEmpty(),
            kind = move.kind,
            loud = move.loud,
            group = session,
            // A credential is typed into the app, never into a notification that shows what was sent.
            reply = when (move.kind) {
                AlertKind.Waiting -> record?.kind != "auth"
                AlertKind.Done, AlertKind.Message -> record?.takeIf { it.state == "blocked" }?.kind != "auth"
                AlertKind.Failed -> false
            },
            interrupt = busy,
        )
    }

    /** A notification's Reply or Interrupt, sent on the connection to the host it came from. */
    private suspend fun onAlertAction(target: AlertTarget, action: AlertAction): Boolean {
        val s = session ?: return false
        if (host?.id != target.hostId) return false
        // Android may have cut the connection while the app was away; it redials, so give it a moment.
        withTimeoutOrNull(ACTION_CONNECT_MS) { s.state.first { it.phase == Phase.Live } } ?: return false
        val sent = when (action) {
            is AlertAction.Reply -> s.replyTo(target.sessionId, target.blockId, action.text)
            AlertAction.Interrupt -> s.interrupt(target.sessionId, target.blockId)
        }
        if (sent) {
            Diagnostics.event(CONN_TAG, "alert ${if (action is AlertAction.Reply) "reply" else "interrupt"} sent")
            planner.seen(target.blockId)
        }
        return sent
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

    /**
     * Reopens the remembered session on its remembered pane; stays on the list if it is gone, and
     * stays wherever the person went if they did anything before the connection was up.
     */
    private suspend fun resumeInto(s: PocketSession, sessionId: String?) {
        if (!resumePending) return
        val target = sessionId?.takeIf { id -> s.state.value.sessions.any { it.id == id } }
        if (target == null) {
            resumePending = false
            return
        }
        try {
            s.openSession(target, rememberedPane(target))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            resumePending = false
            return
        }
        // Opening took a round trip, and the person may have moved in it.
        if (resumePending && route.value == Route.Home && _opening.value == null) route.value = Route.Term
        resumePending = false
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

    /**
     * Opens [blockId] in its tab [windowId] of [target]'s session: what an activity row does, so
     * the pane whose program reported is the one on screen, not the tab's other pane.
     */
    fun openPaneOn(target: SavedHost, sessionId: String, windowId: String, blockId: String) {
        if (host?.id != target.id) connect(target)
        enterSession(Opening.Existing(sessionId)) { openSession(sessionId, blockId, windowId) }
    }

    /** Opens a tab of the session on screen, or of another session of this host. */
    fun openTab(sessionId: String, windowId: String) {
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
     * At launch, connects to the host that was open last, so its sessions are live on the list.
     * The terminal comes back only as a continuation: after Android killed the process while the
     * terminal was on screen, or on every launch with [reopenLastTab]; either way only while the
     * person has not done anything yet ([userActed]). The Rex server keeps the session, so this is
     * a reconnect rather than a new one.
     */
    fun restore() {
        // A phone that has joined a tailnet keeps the node up: the Tailscale row is the honest
        // answer to "am I in the tailnet", and a host on a tailnet can then be dialed at once.
        if (tailnet.value.enabled) startTailnet(tailnet.value)
        if (openedFromAlert || openedFromShare || route.value != Route.Home || session != null) return
        val target = store.lastHost()?.let { store.find(it) } ?: return
        val resume = terminalWasOpen || reopenLastTab.value
        Diagnostics.event(CONN_TAG, "launch: ${if (resume) "back to the last tab" else "the list"} (killed on the terminal: $terminalWasOpen)")
        connect(target, resume = resume)
    }

    /**
     * Looks for a newer build and downloads it. Starting the app only asks when the last check is
     * old; the settings row asks every time it is tapped.
     */
    fun checkForUpdates(force: Boolean = false) {
        viewModelScope.launch { updater.check(force) }
    }

    /** Downloads the release the check offered; nothing is fetched before this is called. */
    fun downloadUpdate() {
        updateJob = viewModelScope.launch { updater.download() }
    }

    /**
     * Takes the other update channel from now on. Whatever the last check offered belongs to the
     * channel being left, so a download of it is stopped and the next check runs at once.
     */
    fun setUpdateChannel(channel: UpdateChannel) {
        updateJob?.cancel()
        updater.setChannel(channel)
        viewModelScope.launch { updater.check(force = true) }
    }

    /** Opens the system installer on the downloaded APK; see [Updater.install]. */
    fun installUpdate(): InstallResult = updater.install()

    /** The shown pane's bytes for as long as the screen collects them; see [PocketSession.watchPane]. */
    fun watchPane(blockId: String): Flow<TerminalFeed> = session?.watchPane(blockId) ?: emptyFlow()

    /** What the program in the shown pane copies with OSC 52; see [PocketSession.copies]. */
    fun paneCopies(blockId: String): Flow<String> =
        session?.copies?.filter { it.blockId == blockId }?.map { it.text } ?: emptyFlow()

    fun retryNow() {
        Diagnostics.event(CONN_TAG, "retry asked for")
        val open = host
        val finished = sessionJob?.isCompleted ?: true
        // A connection stopped by a wrong key or an unconfirmed host key has no loop left to wake.
        // The retry starts a new one from the host as it is saved now, after an edit.
        if (open != null && finished) {
            connect(hosts.value.firstOrNull { it.id == open.id } ?: open, resume = resumeAgain())
            return
        }
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
        if (returning && host?.viaTailscale == true) viewModelScope.launch(Dispatchers.IO) { activeNode.resetNetwork() }
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
                        append(
                            when {
                                open == null -> "idle"
                                open.viaSsh -> "ssh"
                                open.viaTailscale -> "tailnet"
                                else -> "local"
                            },
                        )
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
                            append(known.label).append(" · ")
                            if (known.viaSsh) append("ssh ").append(known.sshUser).append('@')
                            append(known.host).append(':').append(known.port)
                            if (!known.viaSsh) append(if (known.plaintext) " · plaintext" else " · tls")
                            append(if (known.viaTailscale) " · tailnet" else " · local")
                            append(if (known.id == open?.id) " · connected" else "")
                        },
                    ),
                )
            }
            val node = tailnet.value
            add(
                ReportedFact(
                    "tailnet",
                    when {
                        !node.enabled -> "off"
                        tailnetError.value != null -> "failed · ${tailnetError.value?.detail}"
                        else -> "${node.backend.storageKey} · ${tailnetStatus.value.backendState.ifBlank { "starting" }} · node ${node.hostname.ifBlank { "default" }}"
                    },
                ),
            )
            tailnetStatus.value.takeIf { node.enabled }?.let { st ->
                val online = st.peers.count { it.online == true }
                add(ReportedFact("tailnet stage", tailnetStage.value.javaClass.simpleName))
                add(ReportedFact("tailnet devices", "${st.peers.size}" + if (st.peers.any { it.online != null }) " · $online online" else ""))
                if (st.health.isNotEmpty()) add(ReportedFact("tailnet health", st.health.joinToString(" | ")))
            }
            connectProgress.value?.let { p -> add(ReportedFact("connect step", "${p.step} · ${p.took.joinToString { "${it.first} ${it.second}ms" }}")) }
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
                if (known.sshUser.isNotBlank()) add(known.sshUser)
            }
            open?.let {
                add(it.label)
                add(it.host)
                if (it.sshUser.isNotBlank()) add(it.sshUser)
            }
            add(tailnet.value.hostname)
            tailnet.value.managementUrl.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        return Diagnostics.report(getApplication(), facts, secrets, limit)
    }

    fun disconnect() {
        Diagnostics.event(CONN_TAG, "disconnect")
        disconnectQuiet()
        Alerts.unwatch(getApplication())
        store.setLastHost(null)
        // The next tap on the host connects again; a host that is only on screen is not "on".
        host = null
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
        // The pane on screen now is the one the file is for, even if staging or the picker outlives it.
        val sessionId = _pocket.value.activeSessionId ?: return
        val blockId = _pocket.value.view?.focusedBlock()?.id ?: return
        val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
        startUploads(listOf(uri to name), sessionId, blockId)
    }

    /**
     * Sends [files] to one pane, one after another, through the pill. The first that fails stays on
     * the pill, and the ones after it are not sent.
     */
    private fun startUploads(files: List<Pair<Uri, String>>, sessionId: String, blockId: String) {
        val s = session ?: return
        if (_upload.value?.running == true || files.isEmpty()) return
        uploadJob?.cancel()
        // A failure or an earlier sent file is done with; its staged copy goes too.
        clearUpload()
        _upload.value = UploadStatus(files.first().second, UploadPhase.Preparing)
        uploadJob = viewModelScope.launch {
            for ((uri, name) in files) {
                // A sent file's pill clears itself, and with it this job's handle: Cancel still reaches it.
                uploadJob = coroutineContext[Job]
                _upload.value = UploadStatus(name, UploadPhase.Preparing)
                val staged = try {
                    stageUpload(getApplication(), uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e !is UploadStageException) Diagnostics.event(CONN_TAG, "upload: could not read the file: ${e.javaClass.simpleName}: ${e.message}")
                    _upload.value = UploadStatus(name, UploadPhase.Failed, error = (e as? UploadStageException)?.error ?: UploadError.Unreadable)
                    return@launch
                }
                uploadFile = staged.file
                runUpload(s, staged.file, staged.name, sessionId, blockId)
                if (_upload.value != null) return@launch
            }
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
                _upload.value = UploadStatus(name, progress.phase, progress.sent, progress.total, progress.error?.let(UploadError::Said), retryable = true)
                if (progress.phase == UploadPhase.Done) {
                    delay(UPLOAD_DONE_MS)
                    if (_upload.value?.phase == UploadPhase.Done) clearUpload()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _upload.value = UploadStatus(
                name,
                UploadPhase.Failed,
                error = e.message?.let(UploadError::Said) ?: UploadError.Failed,
                retryable = uploadFile != null,
            )
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

    /** The home's session and tab sheets: on the connected host, without attaching the session. */
    fun manage(action: HomeAction) {
        userActed()
        act {
            when (action) {
                is HomeAction.RenameSession -> renameSession(action.label, action.sessionId)
                is HomeAction.NewTab -> newWindowIn(action.sessionId)
                is HomeAction.EndSession -> destroySession(action.sessionId)
                is HomeAction.RenameTab -> renameWindow(action.label, action.windowId, action.sessionId)
                is HomeAction.CloseTab -> closeWindow(action.windowId, action.sessionId)
            }
        }
    }

    private val _share = MutableStateFlow<ShareOffer?>(null)

    /** What another app shared, waiting for the person to send it to a pane or cancel. */
    val share: StateFlow<ShareOffer?> = _share

    /**
     * Holds what another app shared until it is sent with a tap: nothing goes to a pane on its own.
     * It is offered for the pane the phone showed last, on the host it showed it on, which is
     * connected now if it is not already; without one, the picker is open from the start.
     */
    fun offerShare(request: ShareRequest) {
        userActed()
        openedFromShare = true
        val hostId = store.lastHost()
        val saved = hostId?.let { store.find(it) } ?: hosts.value.singleOrNull()
        val sessionId = saved?.let { store.lastSession(it.id) }
        val blockId = if (saved != null && sessionId != null) store.lastPane(saved.id, sessionId) else null
        val target = if (saved != null && sessionId != null && blockId != null) ShareTarget(saved.id, sessionId, blockId) else null
        _share.value = ShareOffer(request, target, choosing = target == null)
        if (saved != null && (host?.id != saved.id || session == null)) connect(saved)
    }

    /** Opens or closes the pane picker of the shared item's sheet. */
    fun chooseShareTarget(choosing: Boolean) {
        _share.value = _share.value?.copy(choosing = choosing)
    }

    /** The picker's choice; a pane of another host connects to that host first. */
    fun shareTo(target: ShareTarget) {
        val offer = _share.value ?: return
        _share.value = offer.copy(target = target, choosing = false)
        val saved = store.find(target.hostId) ?: return
        if (host?.id != saved.id || session == null) connect(saved)
    }

    /** The picker's host row: connects to it, so its sessions can be listed. */
    fun shareConnect(target: SavedHost) {
        if (host?.id != target.id || session == null) connect(target)
    }

    fun cancelShare() {
        _share.value = null
    }

    /**
     * Sends what was shared to its pane, on the person's tap: files through the upload pill, text
     * as a paste (never followed by Enter), then shows the pane.
     */
    fun sendShare() {
        val offer = _share.value ?: return
        val target = offer.target ?: return
        if (host?.id != target.hostId || _pocket.value.phase != Phase.Live) return
        if (offer.request.files.any { (it.size ?: 0) > FileUpload.MAX_BYTES }) return
        _share.value = null
        val text = offer.request.text
        enterSession(Opening.Existing(target.sessionId)) {
            openSession(target.sessionId, target.blockId)
            if (!text.isNullOrEmpty()) paste(text, target.sessionId, target.blockId)
        }
        startUploads(offer.request.files.map { it.uri to it.name }, target.sessionId, target.blockId)
    }

    /** Set once something shared came in, so the start-up resume does not jump past its sheet. */
    private var openedFromShare = false

    private fun disconnectQuiet() {
        connectJob?.cancel()
        connectJob = null
        pathJob?.cancel()
        _tailnetPath.value = null
        _connectProgress.value = null
        // A tap waiting for the old connection must not open anything on the next one.
        sessionReady.cancel()
        sessionReady = CompletableDeferred()
        _opening.value = null
        watch?.cancel()
        watch = null
        uploadJob?.cancel()
        clearUpload()
        val closing = session
        val tunnel = sshTunnel
        session = null
        sshTunnel = null
        sessionJob?.cancel()
        _pocket.value = PocketState()
        // The teardown waits for a disown round trip; leaving the screen must not wait with it, and it
        // must still run when the view model itself is going away, so it is not on viewModelScope.
        // The SSH session closes after that round trip, which may still need it.
        if (closing != null) {
            teardown.launch {
                runCatching { closing.stop() }
                tunnel?.close()
            }
        } else {
            tunnel?.close()
        }
    }
}

private fun SavedHost.endpoint(): Endpoint = Endpoint(host, port, plaintext, sshUser = sshUser.takeIf { it.isNotBlank() })

/**
 * Runs a session's teardown after whatever started it is gone. The disown it waits for is one
 * round trip with a time limit, so nothing here outlives the process by more than that.
 */
private val teardown = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** How long after a connection goes live the path is measured: Tailscale needs a moment to go direct. */
private const val PATH_SETTLE_MS = 3_000L

/** How long a notification's Reply or Interrupt waits for a connection that is redialing. */
private const val ACTION_CONNECT_MS = 6_000L

/** How long a sent file stays on screen as green before the pill clears itself. */
private const val UPLOAD_DONE_MS = 1_500L

/** How long a connection waits for the tailnet node to come up before dialing anyway. */
internal const val NODE_READY_MS = 30_000L

/** How long a running node may take to list the host as a peer before it is dialed anyway. */
private const val PEER_READY_MS = 10_000L

/** How long a connection waits for a host its node's device list does not have: a device added a moment ago. */
private const val PEER_LISTED_MS = 2_000L

/** What a failure says in the report: its kind, then whatever the exception carried. */
/** Why the tailnet node did not start: what the node said, when it said anything ([tailnetFailureText]). */
data class TailnetFailure(val detail: String?)

private fun Problem.describe(): String = trouble.toString() + (detail?.let { ": $it" } ?: "")

/** Connection diagnostics: `adb logcat -s RexPocketConn`. */
internal const val CONN_TAG = "RexPocketConn"

/** The saved-state key that says the terminal was on screen; see [RexViewModel.restore]. */
internal const val KEY_TERMINAL_OPEN = "terminal_open"
