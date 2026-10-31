package me.leaver.rexpocket.ui

import android.app.Application
import android.util.Log
import me.leaver.rexpocket.BuildConfig
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import me.leaver.rexpocket.core.ComposerDrafts
import me.leaver.rexpocket.core.Endpoint
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
import me.leaver.rexpocket.data.SavedHost
import me.leaver.rexpocket.data.TailscaleSettings
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

// The app state: hosts, the tailnet node, and the one Rex connection the screens share.

/** What the session list is waiting on: one of its rows, or a session still being created. */
sealed interface Opening {
    data class Existing(val id: String) : Opening
    data object New : Opening
}

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
            if (BuildConfig.DEBUG) Log.i(CONN_TAG, "tailnet path to ${target.host}: $path")
        }
    }
    val route = MutableStateFlow<Route>(Route.Home)
    private val _pocket = MutableStateFlow(PocketState())
    val pocket: StateFlow<PocketState> = _pocket
    val fontScale = MutableStateFlow(store.fontScale())
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

    /** What the composer holds per pane, and the lines sent; kept for as long as the app runs. */
    val drafts = ComposerDrafts()

    /** The session being opened or created from the list, shown as busy until it is on screen. */
    private val _opening = MutableStateFlow<Opening?>(null)
    val opening: StateFlow<Opening?> = _opening

    /** Becomes the connection's session once it exists, so a tap before that can wait for it. */
    private var sessionReady = CompletableDeferred<PocketSession>()

    /** Wi-Fi to mobile data and back: redial at once instead of waiting for the old socket to die. */
    private val network = NetworkWatcher(app) {
        viewModelScope.launch { session?.reconnectNow() }
    }.also { it.start() }

    override fun onCleared() {
        network.stop()
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
                problem = Problem(Trouble.TailnetNotJoined, null),
            )
            return
        }
        val viaTailscale = target.viaTailscale
        if (BuildConfig.DEBUG) {
            Log.i(CONN_TAG, "connect ${target.host}:${target.port} tailnet=$viaTailscale plaintext=${target.plaintext}")
        }
        // Draw the list the host had last time straight away; the live one replaces it below.
        val cached = if (resume) emptyList() else store.cachedSessions(target.id)
        _pocket.value = PocketState(
            phase = Phase.Connecting,
            endpointLabel = "${target.host}:${target.port}",
            sessions = cached,
        )
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
                withTimeoutOrNull(NODE_READY_MS) {
                    tailscaleBox.status.first {
                        it.backendState in setOf("Running", "NeedsLogin", "NeedsMachineAuth", "Stopped")
                    }
                }
                port
            } else {
                0
            }
            val s = PocketSession(
                Endpoint(target.host, target.port, target.plaintext),
                clientFactory = { RexClient.forSocksPort(socksPort) },
                cachedSessions = cached,
                // The terminal stream takes the same route as the control connection.
                socksPort = socksPort,
            )
            session = s
            sessionReady.complete(s)
            s.setActive(foreground)
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
                if (phase == Phase.Live && active == null && route.value == Route.Term) route.value = Route.Picker
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

    /** Creates a shell session and shows it once Rex has made it, never the session before it. */
    fun createSession(label: String? = null) = enterSession(Opening.New) { createShell(label) }

    /**
     * Runs [open] once the connection is up, then shows the terminal. A tap on the list from the
     * cache, or while the tailnet is still coming up, waits for the connection instead of being
     * dropped; the terminal only appears for the session that was asked for.
     */
    private fun enterSession(what: Opening, open: suspend PocketSession.() -> Unit) {
        if (_opening.value != null) return
        val waiting = sessionReady
        _opening.value = what
        viewModelScope.launch {
            try {
                val s = waiting.await()
                s.awaitReady()
                s.open()
                if (session === s) route.value = Route.Term
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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
        if (route.value != Route.Home || session != null) return
        val target = store.lastHost()?.let { store.find(it) } ?: return
        connect(target, resume = true)
    }

    /** The shown pane's bytes for as long as the screen collects them; see [PocketSession.watchPane]. */
    fun watchPane(blockId: String): Flow<TerminalFeed> = session?.watchPane(blockId) ?: emptyFlow()

    fun retryNow() {
        session?.retryNow()
    }

    fun dismissNotice(id: Long) {
        session?.dismissNotice(id)
    }

    /** Polling a screen nobody sees only drains the battery; the connection itself stays up. */
    fun setForeground(visible: Boolean) {
        foreground = visible
        session?.setActive(visible)
    }

    fun disconnect() {
        disconnectQuiet()
        store.setLastHost(null)
        route.value = Route.Home
    }

    fun showPicker() {
        route.value = Route.Picker
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

/** How long a connection waits for the tailnet node to come up before dialing anyway. */
internal const val NODE_READY_MS = 30_000L

/** Connection diagnostics: `adb logcat -s RexPocketConn`. */
internal const val CONN_TAG = "RexPocketConn"
