package me.leaver.rexpocket.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.leaver.rexpocket.BuildConfig
import me.leaver.rexpocket.R
import me.leaver.rexpocket.alerts.Alert
import me.leaver.rexpocket.alerts.AlertAction
import me.leaver.rexpocket.alerts.AlertOutcome
import me.leaver.rexpocket.alerts.ProgressNote
import me.leaver.rexpocket.core.tabsWithProgress
import me.leaver.rexpocket.core.PromptAnswer
import me.leaver.rexpocket.core.approvalKeys
import me.leaver.rexpocket.core.promptStamp
import me.leaver.rexpocket.data.QuickReplyStore
import me.leaver.rexpocket.alerts.AlertTarget
import me.leaver.rexpocket.alerts.Alerts
import me.leaver.rexpocket.core.ActivityGroup
import me.leaver.rexpocket.core.AlertKind
import me.leaver.rexpocket.core.seenStamp
import me.leaver.rexpocket.data.HomeMarks
import me.leaver.rexpocket.core.AlertPrefs
import me.leaver.rexpocket.core.HostBackground
import me.leaver.rexpocket.core.AlertMove
import me.leaver.rexpocket.core.BellMove
import me.leaver.rexpocket.core.CheckFailure
import me.leaver.rexpocket.core.CheckRoute
import me.leaver.rexpocket.core.CheckRouteException
import me.leaver.rexpocket.core.ConnectProgress
import me.leaver.rexpocket.core.ConnectStep
import me.leaver.rexpocket.core.Endpoint
import me.leaver.rexpocket.core.FileUpload
import me.leaver.rexpocket.core.Fleet
import me.leaver.rexpocket.core.HostSignal
import me.leaver.rexpocket.core.PaneSignal
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.PocketSession
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.Problem
import me.leaver.rexpocket.core.RexClient
import me.leaver.rexpocket.core.SshCredentials
import me.leaver.rexpocket.core.SshSettings
import me.leaver.rexpocket.core.SshTunnel
import me.leaver.rexpocket.core.TailnetBackend
import me.leaver.rexpocket.core.TailnetPath
import me.leaver.rexpocket.core.Trouble
import me.leaver.rexpocket.core.explainTailnetProblem
import me.leaver.rexpocket.core.plainText
import me.leaver.rexpocket.data.BackgroundStore
import me.leaver.rexpocket.data.HostStore
import me.leaver.rexpocket.data.SavedHost
import me.leaver.rexpocket.data.TailnetSettings
import me.leaver.rexpocket.diagnostics.Diagnostics
import me.leaver.rexpocket.net.NetbirdConfig
import me.leaver.rexpocket.net.NetworkWatcher
import me.leaver.rexpocket.net.TailnetConfig
import me.leaver.rexpocket.net.TailnetNode
import me.leaver.rexpocket.net.TailnetNodes
import me.leaver.rexpocket.net.TailnetStatus
import me.leaver.rexpocket.net.TailscaleConfig
import okhttp3.OkHttpClient

// The connections to the hosts, one per connected host, and what they tell the person while the
// app is away. Process-scoped, not the screen's: the view model only watches it, so a connection
// (and its alerts) can outlive the activity. See docs/design/multi-host.md.

/** What the home shows of one connected host: its connection, its steps while on its way, its path. */
data class HostLive(
    val target: SavedHost,
    val state: PocketState,
    val progress: ConnectProgress?,
    val path: TailnetPath?,
)

class Connections private constructor(private val app: Application, val nodes: TailnetNodes) {
    private val store = HostStore(app)

    /** Which done and failed panes were seen: on this phone, or at a desk (docs/design/home.md, "Seen"). */
    val marks = HomeMarks(app)

    /** Everything here runs on the main thread, as the view model's coroutines do. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val fleet = Fleet(scope)

    /** The tailnet settings; the view model's Tailnet dialog changes them. */
    val tailnet = MutableStateFlow(store.tailnet())

    /** Which kinds of alert are on: the Notifications page's switches, every host's defaults. */
    val alertPrefs = MutableStateFlow(store.alertPrefs())

    private val backgroundStore = BackgroundStore(app)

    /** Each host's background choices: kept connected, always connected, its own notifications. */
    val background = MutableStateFlow(backgroundStore.hosts().keep(store.list().map { it.id }))

    /** The choices that apply to each host: the defaults with the host's own on top. */
    private val prefsOf: StateFlow<(String) -> AlertPrefs> = combine(alertPrefs, background) { base, bg -> bg.prefsOf(base) }
        .stateIn(scope, SharingStarted.Eagerly, background.value.prefsOf(alertPrefs.value))

    /** The status of the backend that is selected. */
    val tailnetStatus: StateFlow<TailnetStatus> = combine(
        tailnet,
        combine(nodes.registered.map { backend -> nodes[backend].status.map { backend to it } }) { it.toMap() },
    ) { settings, statuses ->
        statuses[settings.backend] ?: statuses.getValue(TailnetBackend.Tailscale)
    }.stateIn(scope, SharingStarted.Eagerly, TailnetStatus())

    /** One connected host: from the first step (the tailnet node) to its live PocketSession. */
    inner class Link(var target: SavedHost) {
        val pocket = MutableStateFlow(PocketState())
        val progress = MutableStateFlow<ConnectProgress?>(null)
        val path = MutableStateFlow<TailnetPath?>(null)
        var session: PocketSession? = null
            internal set
        internal var tunnel: SshTunnel? = null
        internal var connectJob: Job? = null
        internal var followJob: Job? = null
        internal var pathJob: Job? = null

        /** Becomes the connection's session once it exists, so a tap before that can wait for it. */
        val ready = CompletableDeferred<PocketSession>()
        val id: String get() = target.id
    }

    private val _links = MutableStateFlow<Map<String, Link>>(emptyMap())

    /** The connected hosts, in the order they were connected. */
    val links: StateFlow<Map<String, Link>> = _links

    operator fun get(hostId: String?): Link? = hostId?.let { _links.value[it] }

    /** Every connected host's state, for the home. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val lives: StateFlow<Map<String, HostLive>> = _links.flatMapLatest { links ->
        if (links.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(links.values.map { l -> combine(l.pocket, l.progress, l.path) { st, p, path -> HostLive(l.target, st, p, path) } }) { all ->
                all.associateBy { it.target.id }
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val foreground = MutableStateFlow(true)

    /** Whether a screen has said whether it is visible yet: a start without one is in the background. */
    private var screenSeen = false

    /**
     * The hosts let go while the app is away because they are not kept in the background
     * ([HostBackground.stay] off); they come back with the screen. Still in `connected_hosts`.
     */
    private val parked = LinkedHashSet<String>()
    private var parkJob: Job? = null

    /** The pane on the terminal screen, while it is on screen: its news is seen as it comes. */
    val shown = MutableStateFlow<AlertTarget?>(null)

    /** Wi-Fi to mobile data and back: every host redials at once instead of waiting for its old socket to die. */
    private val network = NetworkWatcher(app) { scope.launch { fleet.reconnectAll() } }.also { it.start() }

    private val alertActions: suspend (AlertTarget, AlertAction) -> AlertOutcome = { target, action -> onAlertAction(target, action) }

    /** The person's quick replies, offered as a notification's Reply choices. */
    private val quickReplies = QuickReplyStore(app)

    init {
        Alerts.actions = alertActions
        // Background mode is the process's, not a screen's: the watch reads it when Android starts
        // the app again without one, and its Stop turns it off with no screen to save that.
        Alerts.enabled.value = store.alerts()
        scope.launch {
            Alerts.enabled.collect { on ->
                fleet.setWatching(on)
                if (store.alerts() != on) store.setAlerts(on)
            }
        }
        // The watch runs while background mode is on and a host is connected, and goes with the
        // last of them. The first look is skipped when nothing is connected yet: a watch Android
        // started again is about to connect them (see [restoreBackground]).
        scope.launch {
            var first = true
            combine(Alerts.enabled, _links) { on, links -> on && links.isNotEmpty() }
                .distinctUntilChanged()
                .collect { watch ->
                    if (watch) Alerts.watch(app) else if (!first) Alerts.unwatch(app)
                    first = false
                }
        }
        scope.launch {
            fleet.alertMovesBy(prefsOf) { !foreground.value && Alerts.enabled.value }.collect { (hostId, st, moves) ->
                for (move in moves) {
                    when (move) {
                        is AlertMove.Cancel -> Alerts.settle(app, hostId, move.block)
                        is AlertMove.Post -> alertFor(st, hostId, move)?.let { Alerts.raise(app, it) }
                    }
                }
            }
        }
        // Progress bars as notifications while away, when asked for (off by default): one per tab
        // with a bar, changed as the bar moves - at most once a second, as Android drops updates
        // that come faster - and gone with the bar or when the app is back. Off the main thread, so
        // the wait between two changes is the clock's and holds up nothing.
        scope.launch(Dispatchers.Default) {
            val posted = HashMap<String, ProgressNote>()
            combine(lives, foreground, Alerts.enabled, alertPrefs, background) { all, visible, on, prefs, bg ->
                if (visible || !on || !prefs.progress) {
                    emptyMap()
                } else {
                    all.filter { (hostId, live) -> bg.of(hostId).notify && live.state.phase == Phase.Live }
                        .flatMap { (hostId, live) -> progressNotes(hostId, live) }
                        .associateBy { Alerts.progressTag(it.target.hostId, it.windowId) }
                }
            }.distinctUntilChanged().conflate().collect { want ->
                for ((tag, note) in posted.entries.toList()) {
                    if (tag !in want) {
                        Alerts.clearProgress(app, note.target.hostId, note.windowId)
                        posted.remove(tag)
                    }
                }
                for ((tag, note) in want) {
                    if (posted[tag] != note) {
                        Alerts.progress(app, note)
                        posted[tag] = note
                    }
                }
                delay(PROGRESS_NOTE_MS)
            }
        }
        // What is on screen is seen: its notification goes, and the news it had is not told later.
        scope.launch {
            combine(shown, foreground, fleet.states) { pane, visible, _ -> pane.takeIf { visible } }
                .collect { pane -> if (pane != null) seen(pane.hostId, pane.blockId) }
        }
        scope.launch { fleet.signals.collect { onSignal(it) } }
        // What a desk looked at is seen here too: marked, and its notification goes. A live host's
        // marks of panes that have no report any more are dropped.
        scope.launch {
            lives.collect { all ->
                for ((hostId, live) in all) {
                    val st = live.state
                    for ((block, stamp) in st.deskSeen) {
                        val record = st.blockStatus[block] ?: continue
                        if (seenStamp(record) == stamp && marks.seen.value[HomeMarks.key(hostId, block)] != stamp) seen(hostId, block)
                    }
                    if (st.phase == Phase.Live) marks.prune(hostId, st.blockStatus.keys)
                }
            }
        }
    }

    fun setForeground(visible: Boolean) {
        screenSeen = true
        val returning = visible && !foreground.value
        foreground.value = visible
        if (returning && _links.value.values.any { it.target.viaTailscale }) {
            scope.launch(Dispatchers.IO) { nodeFor(tailnet.value.backend).resetNetwork() }
        }
        fleet.setActive(visible)
        parkJob?.cancel()
        if (visible) unpark() else if (Alerts.enabled.value) parkJob = scope.launch {
            // A trip to another app and back keeps them; a while away lets them go.
            delay(PARK_MS)
            park()
        }
    }

    /** How many screens (view models) watch these connections; none after the task was swiped away. */
    private var screens = 0

    fun attachScreen() {
        screens++
    }

    fun detachScreen() {
        screens = (screens - 1).coerceAtLeast(0)
    }

    /**
     * The watch notification's Stop turned background mode off. With no screen left, nothing is
     * watching the connections: they close, as a screen closing with background mode off closes
     * them, and connect again at the next start.
     */
    fun backgroundStopped() {
        if (screens > 0) return
        disconnectAll(forget = false)
        nodes.all.forEach { it.stopAsync() }
    }

    /** Lets go of the connected hosts that are not kept in the background; see [parked]. */
    private fun park() {
        val onScreen = shown.value?.hostId
        for (id in background.value.toPark(_links.value.keys, onScreen)) {
            Diagnostics.event(CONN_TAG, "background: letting ${id.take(6)} go while away")
            parked += id
            disconnect(id, forget = false)
        }
    }

    /** The hosts let go while away connect again, now the screen is back. */
    private fun unpark() {
        if (parked.isEmpty()) return
        val ids = parked.toList()
        parked.clear()
        val saved = store.list()
        for (id in ids) saved.firstOrNull { it.id == id }?.let { ensure(it) }
    }

    /**
     * Android started the app without a screen: the watch was restarted after its process was
     * killed (START_STICKY), or the phone booted or the app was updated with background mode on.
     * Connects the hosts that were connected and are kept in the background, and the
     * always-connected ones; returns how many hosts are connected now.
     */
    fun restoreBackground(): Int {
        // No screen has said it is on screen: news is for notifications, and no screen polls run.
        if (!screenSeen) {
            foreground.value = false
            fleet.setActive(false)
        }
        val saved = store.list()
        val wanted = background.value.inBackground(Alerts.enabled.value, store.connectedHosts(), saved.map { it.id })
        Diagnostics.event(CONN_TAG, "background restore: ${wanted.size} hosts, ${_links.value.size} connected")
        for (id in wanted) {
            if (this[id] != null) continue
            saved.firstOrNull { it.id == id }?.let { connect(it) }
        }
        return _links.value.size
    }

    /** Changes [hostId]'s background choices, and acts on them now. */
    fun setBackground(hostId: String, next: HostBackground) {
        val all = background.value.with(hostId, next)
        backgroundStore.setHosts(all)
        background.value = all
        // An always-connected host connects at once, as it would at the next start.
        if (next.always && this[hostId] == null) store.list().firstOrNull { it.id == hostId }?.let { connect(it) }
    }

    private fun nodeFor(backend: TailnetBackend): TailnetNode = nodes[backend]

    /** The credentials and settings a backend's node starts with. */
    fun configFor(settings: TailnetSettings, setupKey: String = ""): TailnetConfig = when (settings.backend) {
        TailnetBackend.Tailscale -> TailscaleConfig(authKey = "", hostname = settings.hostname)
        TailnetBackend.Netbird, TailnetBackend.NetbirdAddon -> NetbirdConfig(
            setupKey = setupKey,
            hostname = settings.hostname,
            managementUrl = settings.managementUrl,
        )
    }

    private fun remember() = store.setConnectedHosts(_links.value.keys.toList() + parked)

    /**
     * Connects [target], and keeps every other host as it is. A host that was connected already
     * starts over. [resume] draws no cached list: the terminal is what comes back.
     */
    fun connect(target: SavedHost, resume: Boolean = false): Link {
        drop(target.id)
        val link = Link(target)
        _links.value = LinkedHashMap(_links.value).apply { put(target.id, link) }
        remember()
        start(link, resume)
        return link
    }

    /** [target]'s connection: the one it has, or a new one. */
    fun ensure(target: SavedHost): Link = this[target.id]?.takeIf { it.connectJob != null } ?: connect(target)

    /** Disconnects [hostId]; with [forget] the next start does not connect it again. */
    fun disconnect(hostId: String, forget: Boolean = true) {
        if (forget) parked -= hostId
        if (this[hostId] == null) return
        Diagnostics.event(CONN_TAG, "disconnect ${hostId.take(6)}")
        drop(hostId)
        if (forget) remember()
    }

    /** Disconnects every host; with [forget] false they connect again at the next start. */
    fun disconnectAll(forget: Boolean) {
        _links.value.keys.toList().forEach { drop(it) }
        if (forget) remember()
    }

    /**
     * Drops the connections whose host is no longer saved, or was saved differently since (a new
     * address): what was connected must be what the list says.
     */
    fun reconcile(saved: List<SavedHost>) {
        for (link in _links.value.values.toList()) {
            val now = saved.firstOrNull { it.id == link.id }
            if (now == null) disconnect(link.id) else if (now.withoutKey() != link.target.withoutKey()) disconnect(link.id, forget = false)
        }
    }

    private fun SavedHost.withoutKey() = copy(sshHostKey = "")

    /** Skips the wait before the next attempt of [hostId], or starts a stopped one again. */
    fun retry(hostId: String, saved: SavedHost?, resume: Boolean) {
        val link = this[hostId] ?: return
        val finished = fleet.member(hostId)?.job?.isCompleted ?: true
        // A connection stopped by a wrong key or an unconfirmed host key has no loop left to wake.
        // The retry starts a new one from the host as it is saved now, after an edit.
        if (finished) connect(saved ?: link.target, resume) else link.session?.retryNow()
    }

    /** Measures the path to [hostId]; a no-op unless that host goes through the tailnet. */
    fun probePath(hostId: String) {
        val link = this[hostId]?.takeIf { it.target.viaTailscale } ?: return
        link.pathJob?.cancel()
        link.pathJob = scope.launch {
            val path = withContext(Dispatchers.IO) { nodeFor(tailnet.value.backend).probePath(link.target.host) }
            link.path.value = path
            Diagnostics.event(CONN_TAG, "tailnet path to ${link.target.host}: $path")
        }
    }

    /**
     * The tailnet route for the host dialog's Test connection: the joined node, started as a
     * connection starts it, and the way the node says a connection goes through it. It changes no
     * setting and keeps nothing; the node stays up, as it would for the host's own connection.
     */
    suspend fun checkRoute(host: String): CheckRoute {
        val settings = store.tailnet()
        if (!settings.enabled) throw CheckRouteException(CheckFailure.TailnetNotJoined, null)
        val node = nodeFor(settings.backend)
        val port = try {
            withContext(Dispatchers.IO) { node.start(configFor(settings)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CheckRouteException(CheckFailure.TailnetDown, e.message)
        }
        val settled = withTimeoutOrNull(NODE_READY_MS) {
            node.status.first { it.backendState in setOf("Running", "NeedsLogin", "NeedsMachineAuth", "Stopped") }
        }?.backendState ?: node.status.value.backendState
        if (settled != "Running") throw CheckRouteException(CheckFailure.TailnetDown, settled.ifBlank { null })
        // A name the netmap does not list yet fails in the node's DNS; a connection waits as long.
        node.awaitPeer(host, if (node.status.value.peersKnown) PEER_LISTED_MS else PEER_READY_MS)
        return node.checkRoute(port)
    }

    private fun drop(hostId: String) {
        val link = _links.value[hostId] ?: return
        _links.value = LinkedHashMap(_links.value).apply { remove(hostId) }
        link.connectJob?.cancel()
        link.followJob?.cancel()
        link.pathJob?.cancel()
        // A tap waiting for the connection must not open anything on the next one.
        link.ready.cancel()
        val closing = fleet.remove(hostId)
        val tunnel = link.tunnel
        link.pocket.value = PocketState()
        // The teardown waits for a disown round trip; leaving the screen must not wait with it.
        // The SSH session closes after that round trip, which may still need it.
        teardown.launch {
            if (closing != null) runCatching { closing.stop() }
            tunnel?.close()
        }
    }

    /** Moves [link] on to [step]; a connection that is not on its way stays as it is. */
    private fun progress(link: Link, step: ConnectStep) {
        while (true) {
            val current = link.progress.value ?: return
            val next = current.next(step, System.currentTimeMillis())
            if (next === current) return
            if (link.progress.compareAndSet(current, next)) {
                Diagnostics.event(CONN_TAG, "connect step $step after ${current.step} ${next.took.last().second}ms")
                return
            }
        }
    }

    /** The state with its problem told from what the tailnet node knows; see [explainTailnetProblem]. */
    private fun explained(st: PocketState, target: SavedHost): PocketState {
        val problem = st.problem ?: return st
        if (!target.viaTailscale || st.phase == Phase.Live) return st
        val status = tailnetStatus.value
        val dial = runCatching { nodeFor(tailnet.value.backend).dialError(target.host) }.getOrNull()
        val better = explainTailnetProblem(problem, target.host, target.port, status.backendState, status.peers, status.peersKnown, dial)
        return if (better == problem) st else st.copy(problem = better)
    }

    private fun start(link: Link, resume: Boolean) {
        val target = link.target
        val previousSession = store.lastSession(target.id)
        val settings = store.tailnet()
        val endpoint = target.endpoint()
        if (target.viaTailscale && !settings.enabled) {
            // The host is saved either way; without a joined node there is no route to it.
            Diagnostics.event(CONN_TAG, "${target.label}: the tailnet is not joined")
            link.pocket.value = PocketState(
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
        link.pocket.value = PocketState(phase = Phase.Connecting, endpointLabel = endpoint.label(), sessions = cached)
        link.progress.value = ConnectProgress(
            host = target.host,
            viaTailnet = viaTailscale,
            step = if (viaTailscale) ConnectStep.StartTailnet else ConnectStep.Reach,
            startedAt = System.currentTimeMillis(),
        )
        link.connectJob = scope.launch {
            // The node has to be up before Rex is dialed, because the Rex connection itself is
            // what goes through it. Joining the tailnet takes a moment on the first connect.
            val node = nodeFor(settings.backend)
            val socksPort = if (viaTailscale) {
                val port = try {
                    withContext(Dispatchers.IO) { node.start(configFor(settings)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    link.pocket.value = PocketState(phase = Phase.Failed, endpointLabel = endpoint.label(), problem = Problem(Trouble.Tailnet, e.message))
                    return@launch
                }
                // The node reports Running only once its WireGuard session is up; dialing before
                // that just gets a SOCKS failure, which looks like a broken tunnel.
                val settled = withTimeoutOrNull(NODE_READY_MS) {
                    node.status.first { it.backendState in setOf("Running", "NeedsLogin", "NeedsMachineAuth", "Stopped") }
                }
                // A MagicDNS name dialed before the netmap lists that peer fails in the node's DNS.
                if (settled?.backendState == "Running") {
                    progress(link, ConnectStep.FindPeer)
                    // A node that has its device list and no such name is not going to learn it soon.
                    val wait = if (node.status.value.peersKnown) PEER_LISTED_MS else PEER_READY_MS
                    if (!node.awaitPeer(target.host, wait)) {
                        Diagnostics.event(CONN_TAG, "${target.host} is not in the netmap yet; dialing anyway")
                    }
                }
                progress(link, ConnectStep.Reach)
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
                ).also { link.tunnel = it }
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
            link.session = s
            fleet.add(target.id, s, previousSession)
            link.ready.complete(s)
            link.followJob = scope.launch { follow(link, s) }
        }
    }

    /** What follows one host's connection, here rather than on a screen: the store, the steps, the path. */
    private suspend fun follow(link: Link, s: PocketSession) = coroutineScope {
        val target = link.target
        // The node's view of the host can change while the connection waits to retry, so the
        // problem is told again on either.
        launch {
            combine(s.state, tailnetStatus) { st, _ -> st }.collect { st ->
                link.pocket.value = explained(st, target)
                // Rex answered the connection: what is left is its sessions.
                if (st.clientId != null && st.phase != Phase.Live) progress(link, ConnectStep.OpenRex)
                if (st.phase == Phase.Live) {
                    link.progress.value?.let { p ->
                        Diagnostics.event(CONN_TAG, "connected in ${System.currentTimeMillis() - p.startedAt}ms: " + p.took.joinToString { "${it.first} ${it.second}ms" })
                    }
                    link.progress.value = null
                }
            }
        }
        launch {
            s.state.map { it.sessions }.filter { it.isNotEmpty() }.distinctUntilChanged()
                .collect { store.setCachedSessions(target.id, it) }
        }
        // Measured once the connection is up, when Tailscale has had a chance to find a direct path.
        launch {
            s.state.first { it.phase == Phase.Live }
            delay(PATH_SETTLE_MS)
            probePath(target.id)
        }
        if (BuildConfig.DEBUG) launch {
            val startedAt = SystemClock.elapsedRealtime()
            var timedLive = false
            s.state.map { it.phase to it.problem }.distinctUntilChanged().collect { (phase, problem) ->
                Log.i(CONN_TAG, "${target.label}: phase=$phase problem=${problem ?: "-"}")
                if (!timedLive && phase == Phase.Live) {
                    timedLive = true
                    Log.i(CONN_TAG, "first live after ${SystemClock.elapsedRealtime() - startedAt}ms")
                }
            }
        }
    }

    /** The progress notifications [live]'s tabs with a bar want, named as the home names a tab. */
    private fun progressNotes(hostId: String, live: HostLive): List<ProgressNote> {
        val st = live.state
        return tabsWithProgress(st.views.values, st.progress).mapNotNull { tab ->
            val view = st.views[tab.sessionId] ?: return@mapNotNull null
            val window = view.windows.firstOrNull { it.id == tab.windowId } ?: return@mapNotNull null
            val name = if (!GENERATED_WINDOW_LABEL.matches(window.label)) {
                window.label
            } else {
                st.titles[tab.blockId] ?: st.programs[tab.blockId] ?: app.getString(R.string.tab_number, view.windows.indexOf(window) + 1)
            }
            ProgressNote(AlertTarget(hostId, tab.sessionId, tab.blockId), tab.windowId, "$name · ${view.label}", live.target.label, tab.progress)
        }
    }

    /** The person is looking at [block] of [hostId]: whatever its notification said, they see it now. */
    private fun seen(hostId: String, block: String) {
        fleet.member(hostId)?.planner?.seen(block)
        Alerts.settle(app, hostId, block)
        val record = this[hostId]?.pocket?.value?.blockStatus?.get(block) ?: return
        if (ActivityGroup.of(record.state)?.needsSeeing == true) marks.mark(mapOf(HomeMarks.key(hostId, block) to seenStamp(record)))
    }

    /** A pane's bell or desktop notification: a buzz on screen, a notification while away. */
    private fun onSignal(hostSignal: HostSignal) {
        val (hostId, signal) = hostSignal
        val planner = fleet.member(hostId)?.planner ?: return
        val st = this[hostId]?.pocket?.value ?: return
        val prefs = prefsOf.value(hostId)
        val away = !foreground.value && Alerts.enabled.value
        when (signal) {
            is PaneSignal.Bell -> {
                val onScreen = foreground.value && shown.value?.let { it.hostId == hostId && it.blockId == signal.blockId } == true
                when (planner.bell(signal.blockId, onScreen, away, prefs)) {
                    BellMove.Vibrate -> Alerts.buzz(app)
                    BellMove.Notify -> {
                        val text = app.getString(R.string.alerts_bell)
                        alertFor(st, hostId, AlertMove.Post(signal.blockId, AlertKind.Message, loud = true, bell = true), text)?.let { Alerts.raise(app, it) }
                    }
                    BellMove.Nothing -> Unit
                }
            }
            is PaneSignal.Note -> {
                val move = planner.message(signal.blockId, away, prefs) as? AlertMove.Post ?: return
                val title = plainText(signal.title, max = 80)
                val body = plainText(signal.body) ?: title ?: return
                val text = if (title != null && title != body) "$title: $body" else body
                alertFor(st, hostId, move, text)?.let { Alerts.raise(app, it) }
            }
        }
    }

    /**
     * The notification for [move]: titled by the pane and tab, which are the app's own words, so a
     * program cannot pass itself off as another pane's (Program Status Protocol, Security). Its
     * host is the subtext, so two hosts' panes are told apart.
     */
    private fun alertFor(st: PocketState, hostId: String, move: AlertMove.Post, message: String? = null): Alert? {
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
        // Approve and Deny for an agent's approval prompt, with the prompt they are for: a status
        // alert only, as a message has no record to answer (notifications.md, "Approve and Deny").
        val answer = record?.takeIf { move.kind == AlertKind.Waiting && message == null && approvalKeys(it) != null }?.let(::promptStamp)
        return Alert(
            target = AlertTarget(hostId, view.sessionId, block),
            title = "$name · ${view.label}",
            text = text,
            host = this[hostId]?.target?.label.orEmpty(),
            kind = move.kind,
            loud = move.loud,
            group = session,
            // A credential is typed into the app, never into a notification that shows what was sent.
            reply = when (move.kind) {
                AlertKind.Waiting -> record?.kind != "auth"
                AlertKind.Done, AlertKind.Message -> record?.takeIf { it.state == "blocked" }?.kind != "auth"
                AlertKind.Failed -> false
            },
            interrupt = block in st.busy,
            answer = answer,
            choices = quickReplies.get().choices(),
        )
    }

    /** A notification's Reply, Interrupt, Approve or Deny, sent on the connection to the host it came from. */
    private suspend fun onAlertAction(target: AlertTarget, action: AlertAction): AlertOutcome {
        val outcome = when (action) {
            is AlertAction.Reply -> fleet.reply(target.hostId, target.sessionId, target.blockId, action.text, ACTION_CONNECT_MS).outcome()
            AlertAction.Interrupt -> fleet.interrupt(target.hostId, target.sessionId, target.blockId, ACTION_CONNECT_MS).outcome()
            is AlertAction.Answer -> when (fleet.answerPrompt(target.hostId, target.sessionId, target.blockId, action.stamp, action.approve, ACTION_CONNECT_MS)) {
                PromptAnswer.Sent -> AlertOutcome.Sent
                PromptAnswer.Changed -> AlertOutcome.Changed
                PromptAnswer.NotSent -> AlertOutcome.NotSent
            }
        }
        val what = when (action) {
            is AlertAction.Reply -> "reply"
            AlertAction.Interrupt -> "interrupt"
            is AlertAction.Answer -> if (action.approve) "approve" else "deny"
        }
        Diagnostics.event(CONN_TAG, "alert $what: $outcome")
        if (outcome == AlertOutcome.Sent) fleet.member(target.hostId)?.planner?.seen(target.blockId)
        return outcome
    }

    private fun Boolean.outcome() = if (this) AlertOutcome.Sent else AlertOutcome.NotSent

    /** Stops everything this holds: for a test that wants a new one, and nothing else. */
    private fun close() {
        disconnectAll(forget = false)
        network.stop()
        if (Alerts.actions === alertActions) Alerts.actions = null
        scope.cancel()
    }

    companion object {
        @Volatile private var current: Connections? = null

        /**
         * The process's connections, made with [nodes] the first time (the real ones when null).
         * Only a test passes its own nodes; that starts a new set of connections on them.
         */
        @Synchronized
        fun of(app: Application, nodes: TailnetNodes? = null): Connections {
            val existing = current
            if (existing != null && existing.app === app && (nodes == null || nodes === existing.nodes)) return existing
            existing?.close()
            return Connections(app, nodes ?: TailnetNodes.of(app)).also { current = it }
        }
    }
}

internal fun SavedHost.endpoint(): Endpoint = Endpoint(host, port, plaintext, sshUser = sshUser.takeIf { it.isNotBlank() })

/**
 * Runs a session's teardown after whatever started it is gone. The disown it waits for is one
 * round trip with a time limit, so nothing here outlives the process by more than that.
 */
private val teardown = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** How long after a connection goes live the path is measured: Tailscale needs a moment to go direct. */
private const val PATH_SETTLE_MS = 3_000L

/** The least time between two changes to the progress notifications. */
private const val PROGRESS_NOTE_MS = 1_000L

/** How long the app is away before the hosts not kept in the background are let go. */
private const val PARK_MS = 60_000L

/** How long a notification's Reply or Interrupt waits for a connection that is redialing. */
private const val ACTION_CONNECT_MS = 6_000L

/** How long a connection waits for the tailnet node to come up before dialing anyway. */
internal const val NODE_READY_MS = 30_000L

/** How long a running node may take to list the host as a peer before it is dialed anyway. */
private const val PEER_READY_MS = 10_000L

/** How long a connection waits for a host its node's device list does not have: a device added a moment ago. */
private const val PEER_LISTED_MS = 2_000L
