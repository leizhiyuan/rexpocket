package me.leaver.rexpocket.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

enum class Phase { Idle, Connecting, Live, Reconnecting, Failed }

data class TermSize(val cols: Int, val rows: Int, val cellWidthPx: Int, val cellHeightPx: Int)

data class PocketState(
    val phase: Phase = Phase.Idle,
    /** host:port of the server this state belongs to, shown while connecting. */
    val endpointLabel: String = "",
    /** True when the server accepted our reconnect token and kept the session. */
    val resumed: Boolean = false,
    /** Why the connection is not live right now; kept while the next attempt runs, cleared once live. */
    val problem: Problem? = null,
    /** The last action that failed while the connection was fine; the UI shows it for a moment. */
    val notice: Notice? = null,
    val clientId: String? = null,
    val sessions: List<ListedSession> = emptyList(),
    val activeSessionId: String? = null,
    val view: SessionView? = null,
    val views: Map<String, SessionView> = emptyMap(),
    /**
     * What each pane's program says it is doing, summed up in Rex's Activity order (see
     * [summarizeStatus]); kept current by Rex's events.
     */
    val blockStatus: Map<String, ProgramRecord> = emptyMap(),
    /**
     * Every record of each pane, as Rex lists them: root first, each parent before its children.
     * Rex sums nothing up; [blockStatus] is the phone's summary of these, and the home decides
     * Running and Needs you from all of them ([tabAttention]).
     */
    val records: Map<String, List<ProgramRecord>> = emptyMap(),
    /**
     * The panes with a program at work or waiting: any record `working` or `blocked`, whatever the
     * summary shows. A pane whose child record is done while its root still works is both done in
     * Activity and busy here.
     */
    val busy: Set<String> = emptySet(),
    /**
     * When each pane's summarized state began, in [System.currentTimeMillis] time: the `updated_at`
     * Rex stamps on the record that speaks for the pane (no later than now, as clocks differ), or,
     * from a Rex that sends none, when the phone saw the change.
     */
    val statusSince: Map<String, Long> = emptyMap(),
    /** The shown pane's working directory. */
    val pwd: String? = null,
    /** What each pane runs, by name - an agent such as `pi`, an editor, or a shell. */
    val programs: Map<String, String> = emptyMap(),
    /** The title each pane's program set, such as `π - rexpocket`; absent until one is set. */
    val titles: Map<String, String> = emptyMap(),
    /** True while Rex has the shown pane at this phone's size: this phone is the one controlling it. */
    val owning: Boolean = false,
    /** True after the pane was handed back to the desktop, so taking it again has to be asked for. */
    val released: Boolean = false,
    /**
     * The pane the phone showed, closed by someone else: the desktop, another client, or its program
     * exiting. The screen says so in its place until the person moves on; see [ClosedPane].
     */
    val closed: ClosedPane? = null,
    /**
     * Done and failed panes a desk looked at, by pane, with the [seenMark] of the reports it saw: an
     * inference from what Rex tells, as Rex keeps no seen state of its own. See [PocketSession]'s
     * `noteDeskLooks` and docs/design/home.md, "Seen".
     */
    val deskSeen: Map<String, String> = emptyMap(),
    /**
     * Each pane's progress bar, as its program reported it with OSC 9;4 and Rex forwarded it
     * (`progress_report`). Rex has no way to read it back, so a pane shows one only from the first
     * report this connection hears; a bar goes when the program removes it, its program exits, the
     * pane closes, or no report has come for [PROGRESS_STALE_MS].
     */
    val progress: Map<String, PaneProgress> = emptyMap(),
) {
    /**
     * Who the screen says holds the shown pane. A pane is taken on sight, so it is the phone's from
     * the moment it is shown - also while that take is on its way - unless the person handed it
     * back or the desk took it: then it is the desk's until Take over. Saying "Take over" for a pane
     * that is about to be taken anyway asked for a tap nothing needed.
     */
    val holder: Holder get() = if (released) Holder.Desk else Holder.Phone
}

enum class Holder { Phone, Desk }

/**
 * A pane that was on the phone's screen and is gone from its session. [tabClosed] is true when its
 * whole tab went with it. The view has already moved to what Rex shows instead; the screen holds
 * that back, so nobody types into a pane they did not pick, until the person moves on.
 */
data class ClosedPane(
    val sessionId: String,
    val blockId: String,
    val tabClosed: Boolean,
    /** How its program ended, when that is what closed it; null when it was closed on another device. */
    val exit: PaneExit? = null,
)

/**
 * Rex's `terminal.child_exited` for a pane: the program's exit [code] (-1 when it has none, as when
 * a signal ended it) and Rex's words for a failure, such as `exit status 3`.
 */
data class PaneExit(val code: Int, val error: String?)

/** A pane's output as text; [cut] when it was longer than asked for and only its newest part is here. */
data class PaneText(val text: String, val cut: Boolean)

/** Text the program in pane [blockId] copied to the clipboard with OSC 52; see [PocketSession.copies]. */
data class PaneCopy(val blockId: String, val text: String)

/**
 * A program in a pane calling for the person outside its status: its bell (`terminal.bell`), or a
 * desktop notification it asked for with OSC 9 or OSC 777 (`terminal.desktop_notification`; OSC 9
 * has no title, which Rex sends as ""). Both are as the program wrote them: untrusted text.
 */
sealed interface PaneSignal {
    val sessionId: String
    val blockId: String

    data class Bell(override val sessionId: String, override val blockId: String) : PaneSignal

    data class Note(override val sessionId: String, override val blockId: String, val title: String, val body: String) : PaneSignal
}

/**
 * Phone-side session: bootstrap, sidebar model, and a polled VT screen.
 * Owning the focused block makes Rex use this client's size, the same way a
 * phone client takes the pane while it is on screen. Leaving disowns it.
 */
class PocketSession(
    /** The host's address; with [routes], only what is shown until the first dial. */
    private val mainEndpoint: Endpoint,
    /** The list this host had last time, shown until the live one arrives with the bootstrap. */
    cachedSessions: List<ListedSession> = emptyList(),
    /** The app's own Tailscale inbound, when a terminal stream has to go through it like the control one. */
    private val socksPort: Int = 0,
    /** Diagnostics for the terminal stream and the calls a pane waits on; silent by default. */
    private val trace: (String) -> Unit = {},
    /** Every Rex connection of this host: direct, through the tailnet, or through SSH. */
    private val httpFactory: () -> OkHttpClient = { RexClient.defaultHttp(socksPort) },
    /** Uploads speak API revision 11 on their own connection, so the live one keeps its contract. */
    private val uploadClientFactory: () -> RexClient = { RexClient(httpFactory(), apiRevision = FileUpload.API_REVISION) },
    /** The time a state change is stamped with; a test's own clock in tests. */
    private val clock: () -> Long = System::currentTimeMillis,
    /** How long a progress bar stays without a new report; see [PocketState.progress]. */
    private val progressStaleMs: Long = PROGRESS_STALE_MS,
    /**
     * The host's addresses, when it has others to try: each dial uses the cursor's current one,
     * and one that could not be reached moves it on at once (multi-host.md, "Other addresses").
     * The factories read the same cursor.
     */
    private val routes: RouteCursor? = null,
    /** Readies what [HostRoute] needs before it is dialed (the tailnet node); throws when it cannot. */
    private val prepare: suspend (HostRoute) -> Unit = {},
    // Last, so a trailing lambda is the client factory, as callers and tests write it.
    private val clientFactory: () -> RexClient = { RexClient(httpFactory()) },
) {
    /** The address in use now. */
    private val endpoint: Endpoint get() = routes?.current?.endpoint ?: mainEndpoint

    private val _state = MutableStateFlow(PocketState(sessions = cachedSessions))
    val state: StateFlow<PocketState> = _state

    private val _copies = MutableSharedFlow<PaneCopy>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * What a program copied with OSC 52 (`clipboard_written`), for the phone's clipboard: Rex may run
     * on another machine, so only the client can put it there. Only copies made in the pane on
     * screen while the app is in the foreground come through; a pane in the background, or a
     * program running while the phone is in a pocket, does not get to change the clipboard.
     */
    val copies: SharedFlow<PaneCopy> = _copies

    private val _signals = MutableSharedFlow<PaneSignal>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every pane's bells and desktop notifications, from any session; what to do with them is the app's. */
    val signals: SharedFlow<PaneSignal> = _signals

    private val clientRef = AtomicReference<RexClient?>(null)
    private val sizeRef = AtomicReference(TermSize(80, 24, 8, 16))
    private var ownedBlock: String? = null
    /** The client this phone was when it took [ownedBlock]: each control connection is a new one. */
    @Volatile private var ownerClient: String? = null
    /** Session [ownedBlock] lives in; it can differ from the active one right after a switch. */
    private var ownedSession: String? = null
    /**
     * The panes the user handed back to the desktop. A release belongs to the pane, not to the
     * screen: showing that pane again, in this session or another one, must not take it back. Only
     * Take over, closing the pane or the connection ends it.
     */
    private val releasedBlocks: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Serializes owning and disowning, so a release cannot be undone by a take that is in flight. */
    private val ownership = Mutex()

    /**
     * Each pane's status records and directory as Rex last reported them. Rex sends a change as an
     * event, so a pane is read once and then followed; [statusRead] remembers which ones were.
     */
    private val statusRecords = java.util.concurrent.ConcurrentHashMap<String, Map<String, ProgramRecord>>()
    private val pwds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val foregrounds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val titleOf = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val statusRead: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** When each pane's summarized state began, as far as the phone saw it; see [PocketState.statusSince]. */
    private val sinceOf = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * The pane the phone shows in each session. Rex has one active tab and pane per session for all
     * its clients, so following Rex's choice made the phone jump wherever the desktop went, and the
     * desktop jump wherever the phone went - each then took the pane the other was on. The phone's
     * place is its own; Rex's focus is left to the desktop.
     */
    private val places = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The last `child_exited` of each pane, and when it came. Rex sends it before the pane's
     * `block_closed` (and its tab's `window_closed`), so the closed note can say the program exited.
     */
    private val exits = java.util.concurrent.ConcurrentHashMap<String, Pair<PaneExit, Long>>()

    /** Each pane's progress bar and its session, with the report's count, which a stale timer checks. */
    private val progressOf = java.util.concurrent.ConcurrentHashMap<String, Triple<String, PaneProgress, Long>>()
    private val progressReports = java.util.concurrent.atomic.AtomicLong()

    /** Panes Rex reported on while a read of them was in flight; that read is older and must not win. */
    private val reportedDuringRead: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Panes and sessions this phone is closing itself. Their going is what was asked for, so the
     * screen moves on to what is left rather than saying the pane was closed.
     */
    private val closingHere: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Wakes the status reader when a pane it has not read yet comes into view. */
    private val statusWake = Channel<Unit>(Channel.CONFLATED)

    /**
     * Sessions whose program status this connection hears of as events: the ones it attached
     * (`session.bootstrap`'s and each `session.attach`), and any a status event came from. Rex's
     * guide says `event_scope` `all` brings every session's block events, and a Linux Rex does; on
     * a person's Mac the phone heard only the sessions it had opened. The panes of every other
     * session are read again on a timer ([sweepStatus]). Both start empty with each connection.
     */
    private val attachedSessions: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val heardStatusFrom: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private var lastResize: TermSize? = null
    private var preferred: String? = null
    private var reconnectToken: String? = null
    private val ready = CompletableDeferred<Unit>()
    private var runner: Job? = null
    @Volatile private var runnerScope: CoroutineScope? = null

    /** The pane streams being watched now, so coming back can hurry the ones that are redialing. */
    private val streams: MutableSet<TerminalStream> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * True while the control connection in use has told Rex which session this client is on, which
     * is what a data ticket and every pane call need. A pane that starts before that is refused,
     * and then waits out its backoff while the person looks at an empty pane.
     */
    private val attached = MutableStateFlow(false)

    /**
     * Every keystroke, paste and typed text, in the order it was typed. Rex runs `client.key` and
     * `block.event` in order but `terminal.write` whenever it arrives, so two writes sent without
     * waiting can swap places. One sender drains this queue and waits for each reply, which keeps
     * the order, and nothing else waits on it, so input never queues behind another call.
     */
    private val input = Channel<InputCall>(Channel.UNLIMITED)

    private class InputCall(val method: String, val payload: JSONObject) {
        val done = CompletableDeferred<Unit>()
    }

    /** Wakes the wait between connection attempts. */
    private val retry = Channel<Unit>(Channel.CONFLATED)
    private val noticeIds = java.util.concurrent.atomic.AtomicLong()

    /** False while nobody can see the screen; the polls wait instead of reading it. */
    private val active = MutableStateFlow(true)

    /**
     * True while background alerts want each pane's status kept current. The status reader then
     * also runs with the screen off - it reads a pane once, after which Rex's events keep it - and
     * the loops that take and size panes still wait for someone to look.
     */
    private val watching = MutableStateFlow(false)

    fun start(scope: CoroutineScope, preferredSessionId: String? = null): Job {
        preferred = preferredSessionId
        runnerScope = scope
        val job = scope.launch {
            var backoff = MIN_BACKOFF_MS
            while (isActive) {
                try {
                    runConnection()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isActive) break
                    trace("connection ended: " + generateSequence(e as Throwable) { it.cause }.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" })
                    // A connection that was up starts over from the shortest wait.
                    if (_state.value.phase == Phase.Live) backoff = MIN_BACKOFF_MS
                    val problem = e.toProblem()
                    updateState {
                        it.copy(
                            phase = if (it.phase == Phase.Live) Phase.Reconnecting else Phase.Connecting,
                            problem = problem,
                        )
                    }
                    // This address could not be reached: the next one is tried now, not after a wait.
                    if (routes?.failed(problem) == true) {
                        trace("trying the next address (${routes.routes.indexOf(routes.current) + 1} of ${routes.routes.size})")
                        continue
                    }
                    val woken = withTimeoutOrNull(backoff) { retry.receive() } != null
                    // Away from the screen (background mode) a host that stays down is tried less
                    // often: a network change, or the screen coming back, wakes the wait at once.
                    val cap = if (active.value) MAX_BACKOFF_MS else MAX_AWAY_BACKOFF_MS
                    backoff = if (woken) MIN_BACKOFF_MS else (backoff * 2).coerceAtMost(cap)
                    // A wrong key or an unconfirmed host key stays wrong until the person changes it.
                    if (e.isFatalConnectionProblem()) break
                }
            }
        }
        runner = job
        return job
    }

    suspend fun awaitReady() {
        ready.await()
    }

    /**
     * Pauses the screen and status polls while the app is in the background, and reads the screen
     * at once when it comes back.
     *
     * Android cuts an app's network soon after it leaves the screen unless a foreground service
     * holds it, so the sockets are often dead on return - and a connection that died quietly looks
     * open until OkHttp's ping notices, half a minute later. Coming back therefore skips any
     * backoff, and checks a connection that still claims to be live with one quick call, replacing
     * it at once when that goes unanswered. That is the wait a pane used to sit through.
     */
    fun setActive(visible: Boolean) {
        val wasVisible = active.value
        active.value = visible
        if (!visible || wasVisible) return
        // A pane stream that is redialing may be stuck on a dial made while the network was cut.
        streams.forEach { it.retryNow() }
        if (_state.value.phase != Phase.Live) {
            // A dial in flight was made while the network may have been cut; start a fresh one.
            if (_state.value.phase == Phase.Connecting || _state.value.phase == Phase.Reconnecting) {
                clientRef.get()?.close()
            }
            retry.trySend(Unit)
            return
        }
        val client = clientRef.get() ?: return
        val scope = runnerScope ?: return
        scope.launch {
            val alive = runCatching { client.call("session.list", null, timeoutMs = LIVENESS_MS) }.isSuccess
            if (!alive && clientRef.get() === client) {
                trace("control connection did not answer after the app came back; reconnecting")
                reconnectNow()
            }
        }
    }

    fun setWatching(on: Boolean) {
        watching.value = on
    }

    /** Skips the rest of the wait before the next connection attempt. Nothing to skip while live. */
    fun retryNow() {
        if (_state.value.phase != Phase.Live) retry.trySend(Unit)
    }

    /**
     * Drops the connection and makes a new one at once: the network it ran on went away, and a
     * socket on a vanished network can take a minute of pings to notice. Rex resumes it: the drop
     * sends no close frame, which would end the client and close its streams with 1001.
     */
    fun reconnectNow() {
        clientRef.get()?.drop()
        // The pane streams ran on the same network, so they are as dead as the control connection.
        streams.forEach { it.reconnectNow() }
        retry.trySend(Unit)
    }

    /**
     * Shows [problem] for a moment. With [blockId] it is that pane's: it goes as soon as the pane is
     * no longer the one on screen, or recovers - see [settled].
     */
    fun note(problem: Problem, blockId: String? = null) {
        note(problem, blockId, PaneTrouble.Other)
    }

    private fun note(problem: Problem, blockId: String?, about: PaneTrouble) {
        val notice = Notice(noticeIds.incrementAndGet(), problem, blockId)
        noticeAbout = notice.id to about
        updateState { it.copy(notice = notice) }
    }

    /**
     * What on [blockId] works again - its stream, or the phone holding it - so a failure of that
     * is over and its notice goes. A failure of the other stays: a picture that arrives does not
     * mean Rex let the phone have the pane.
     */
    private fun resolved(blockId: String, about: PaneTrouble) {
        updateState {
            val notice = it.notice
            if (notice?.blockId == blockId && noticeAbout == notice.id to about) it.copy(notice = null) else it
        }
    }

    /** What a pane's notice is about, so only that working again clears it. */
    private enum class PaneTrouble { Stream, Hold, Other }

    @Volatile private var noticeAbout: Pair<Long, PaneTrouble>? = null

    /** Every change to the state goes through here, so no state keeps a notice its pane outlived. */
    private inline fun updateState(change: (PocketState) -> PocketState) {
        _state.update { settled(change(it)) }
    }

    fun dismissNotice(id: Long) {
        updateState { if (it.notice?.id == id) it.copy(notice = null) else it }
    }

    /**
     * Tears the session down: cancel, then wait for the connection's NonCancellable finally to
     * disown its pane before the socket closes. Suspending, because that wait is a network round
     * trip and callers used to pay for it on the main thread.
     */
    suspend fun stop() {
        val job = runner
        runner = null
        releasedBlocks.clear()
        job?.cancel()
        // Clearing clientRef first would skip the disown, so the join comes first.
        if (job != null) withTimeoutOrNull(4_000) { job.join() }
        clientRef.getAndSet(null)?.close()
        updateState { it.copy(phase = Phase.Idle, resumed = false) }
    }

    fun setSize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        val next = TermSize(
            cols.coerceIn(20, 400),
            rows.coerceIn(4, 200),
            cellWidthPx.coerceIn(1, 128),
            cellHeightPx.coerceIn(1, 128),
        )
        if (sizeRef.get() != next) {
            sizeRef.set(next)
            lastResize = null
        }
    }

    /**
     * Opens a session on [rememberedBlock], the pane the phone showed there last time, when it
     * still exists; otherwise on the pane Rex has focused. With [windowId] it opens on that tab
     * instead, on its remembered or focused pane. The pane is focused before the view is published,
     * so the terminal never shows another pane first, and one focus call moves the tab and the pane.
     */
    suspend fun openSession(sessionId: String, rememberedBlock: String? = null, windowId: String? = null) {
        preferred = sessionId
        // The pane left behind goes back to the desktop, or it stays at the phone's size.
        if (ownedSession != sessionId) disownQuietly()
        // Released panes stay released across sessions; only the shown pane's state is reset here.
        updateState { it.copy(owning = false, released = false) }
        val payload = rpc("session.attach", JSONObject().put("session_id", sessionId))
        attachedSessions += sessionId
        var view = parseView(payload.getJSONObject("view"))
        // The phone's own place: the asked-for tab, else where it was last time. Rex's focus is not
        // moved, so opening a tab here leaves the desktop on whatever it is showing.
        val pane = view.paneToShow(rememberedBlock ?: places[sessionId], windowId)
        if (pane != null) places[sessionId] = pane.id
        view = view.placedAt(places[sessionId])
        // applyView only replaces the shown view when this session is already active.
        // Opening another session has to force it, or the old view stays on screen.
        applyView(view)
        val shown = view.focusedBlock()?.id
        val shownPwd = shown?.let { pwds[it] }
        updateState {
            it.copy(
                activeSessionId = sessionId,
                view = view,
                pwd = shownPwd,
                closed = null,
                released = shown != null && shown in releasedBlocks,
                views = if (view.sessionId.isEmpty()) it.views else it.views + (view.sessionId to view),
                sessions = mergeSession(it.sessions, ListedSession(sessionId, view.label)),
            )
        }
    }

    suspend fun createShell(label: String?) {
        val body = JSONObject().put(
            "initial_windows",
            JSONArray().put(
                JSONObject().put(
                    "layout",
                    JSONObject().put(
                        "block",
                        JSONObject().put("flavor", "com.superlogical.terminal.shell"),
                    ),
                ),
            ),
        )
        if (!label.isNullOrBlank()) body.put("label", label.trim())
        val payload = rpc("session.create", body)
        val id = payload.getString("session_id")
        val createdLabel = label?.trim().takeUnless { it.isNullOrEmpty() } ?: id
        updateState { it.copy(sessions = mergeSession(it.sessions, ListedSession(id, createdLabel))) }
        openSession(id)
    }

    /** Makes a tab and shows it on the phone; the desktop stays on the tab it has. */
    suspend fun newWindow() {
        val sid = requireSession()
        val made = rpc(
            "session.new_window",
            JSONObject()
                .put("session_id", sid)
                .put("focus", false)
                .put(
                    "layout",
                    JSONObject().put(
                        "block",
                        JSONObject().put("flavor", "com.superlogical.terminal.shell"),
                    ),
                ),
        )
        val block = made.optJSONArray("block_ids")?.optString(0)?.takeIf { it.isNotEmpty() } ?: return
        places[sid] = block
        updateState { it.showing(block) }
        // The view event that announces the tab may already have come; this one surely has it.
        refreshView(sid)
    }

    /**
     * Makes a tab in [sessionId] without attaching it or showing the tab: the home's session sheet.
     * A caller that is not attached does not become the new pane's owner (only an attached one
     * does), so the desktop's panes keep their size, and `focus: false` keeps the desktop's tab.
     */
    suspend fun newWindowIn(sessionId: String) {
        rpc(
            "session.new_window",
            JSONObject()
                .put("session_id", sessionId)
                .put("focus", false)
                .put("layout", JSONObject().put("block", JSONObject().put("flavor", "com.superlogical.terminal.shell"))),
        )
        refreshView(sessionId)
    }

    suspend fun split(vertical: Boolean) {
        val sid = requireSession()
        val anchor = _state.value.view?.focusedBlock()?.id
        val body = JSONObject()
            .put("session_id", sid)
            .put("direction", if (vertical) "vertical" else "horizontal")
            .put("side", "after")
            .put("ratio", 0.5)
            .put("focus", false)
            .put(
                "layout",
                JSONObject().put(
                    "block",
                    JSONObject().put("flavor", "com.superlogical.terminal.shell"),
                ),
            )
        if (anchor != null) body.put("anchor_id", anchor)
        val made = rpc("session.new_split", body)
        // The new pane is shown here; the desktop keeps the focus it has.
        val block = made.optJSONArray("block_ids")?.optString(0)?.takeIf { it.isNotEmpty() } ?: return
        places[sid] = block
        updateState { it.showing(block) }
        refreshView(sid)
    }

    /**
     * Shows a pane, and so its tab, on the phone. Rex's focus is the desktop's and is not touched:
     * moving it made the desktop follow the phone onto the same pane, and the two then took that
     * pane from each other, resizing it every time.
     */
    suspend fun focusBlock(blockId: String) {
        val sid = requireSession()
        places[sid] = blockId
        updateState { it.showing(blockId) }
        showLocally { it.placedAt(blockId) }
    }

    /**
     * The screen moves to [block]: no closed note any more, and the holder says what that pane's
     * state is - the phone's unless it was handed back - not what the pane before it had.
     */
    private fun PocketState.showing(block: String): PocketState = copy(
        closed = null,
        owning = ownedBlock == block,
        released = block in releasedBlocks,
    )

    /**
     * Leaves the note that the shown pane was closed, for the pane the view moved to: the one Rex
     * shows in that session. It becomes the phone's own place there.
     */
    fun leaveClosedPane() {
        updateState { st ->
            val sid = st.closed?.sessionId ?: return@updateState st
            val next = st.view?.takeIf { it.sessionId == sid }?.focusedBlock()?.id
                ?: return@updateState st.copy(closed = null)
            places[sid] = next
            st.showing(next)
        }
    }

    /** Shows a tab on the phone, on the pane the phone last showed there or the tab's own focus. */
    suspend fun focusWindow(windowId: String) {
        val sid = requireSession()
        val view = _state.value.view ?: return
        val pane = view.paneToShow(places[sid], windowId) ?: return
        focusBlock(pane.id)
    }

    /** Closes a pane, the shown one by default. Closing a tab's last pane closes the tab. */
    suspend fun closeBlock(blockId: String? = null) {
        val sid = requireSession()
        val block = blockId ?: _state.value.view?.focusedBlock()?.id ?: return
        closingHere += block
        try {
            onPane(sid, block) { rpc("block.close", JSONObject().put("session_id", sid).put("block_id", block)) }
        } catch (e: Exception) {
            closingHere -= block
            throw e
        }
    }

    /**
     * Closes a tab and every pane in it, the shown tab by default. With [sessionId] it closes a tab
     * of any session, attached or not: the bootstrapped connection may (rex-parity.md, "Managing
     * sessions from the home"), and nothing on the desktop is resized for it.
     */
    suspend fun closeWindow(windowId: String? = null, sessionId: String? = null) {
        val sid = sessionId ?: requireSession()
        val view = _state.value.view?.takeIf { it.sessionId == sid } ?: _state.value.views[sid]
        val window = windowId ?: view?.activeWindow()?.id
        val body = JSONObject().put("session_id", sid)
        if (window != null) body.put("window_id", window)
        val blocks = view?.windows?.firstOrNull { it.id == window }?.blocks.orEmpty().map { it.id }
        closingHere += blocks
        try {
            rpc("session.close_window", body)
        } catch (e: RexCallException) {
            // Closed already, elsewhere: what was asked for has happened.
            if (window != null && windowGone(sid, window)) return
            closingHere -= blocks.toSet()
            throw e
        } catch (e: Exception) {
            closingHere -= blocks.toSet()
            throw e
        }
    }

    /**
     * Ends a session: the shown one by default, or [sessionId], which need not be attached. Rex
     * closes its panes one by one and sends `session_destroyed` last, before its answer.
     */
    suspend fun destroySession(sessionId: String? = null) {
        val sid = sessionId ?: requireSession()
        // Rex's news of it can come before its answer; this phone asked for it, so it moves on.
        closingHere += sid
        try {
            rpc("session.destroy", JSONObject().put("session_id", sid))
        } catch (e: Exception) {
            closingHere -= sid
            throw e
        }
        if (sid != _state.value.activeSessionId) {
            // Not the session the phone shows: the phone stays where it is.
            dropSession(sid)
            closingHere -= sid
            return
        }
        updateState {
            val rest = it.sessions.filter { s -> s.id != sid }
            it.copy(
                sessions = rest,
                activeSessionId = rest.firstOrNull()?.id,
                view = null,
                closed = null,
            )
        }
        closingHere -= sid
        // Its blocks are gone with it; there is nothing left to disown.
        ownedBlock = null
        ownedSession = null
        val next = _state.value.activeSessionId
        if (next != null) openSession(next)
    }

    /** Renames the shown session, or [sessionId] without attaching it. */
    suspend fun renameSession(label: String, sessionId: String? = null) {
        val sid = sessionId ?: requireSession()
        rpc("session.set_label", JSONObject().put("session_id", sid).put("label", label))
    }

    /**
     * Moves [sessionId] to place [to] in Rex's session order, the one every client shows
     * (`session.move`). The bootstrapped connection may move any session without attaching it.
     * The list here changes with Rex's `session_moved`, which every client gets, this one too: one
     * order, applied in the order Rex made the moves, whoever made them.
     */
    suspend fun moveSession(sessionId: String, to: Int) {
        val order = _state.value.sessions.map { it.id }
        val place = placement(order, sessionId, to) ?: return
        val body = JSONObject().put("session_id", sessionId)
        if (place.before != null) body.put("before_session_id", place.before)
        rpc("session.move", body)
    }

    /**
     * Moves tab [windowId] of [sessionId] to place [to] in its session's tab order
     * (`session.move_window`), the order of Rex's tab bar; the active tab and the panes stay.
     */
    suspend fun moveWindow(sessionId: String, windowId: String, to: Int) {
        val view = _state.value.views[sessionId] ?: return
        val place = placement(view.windows.map { it.id }, windowId, to) ?: return
        val body = JSONObject().put("session_id", sessionId).put("window_id", windowId)
        if (place.before != null) body.put("before_window_id", place.before)
        val revision = rpc("session.move_window", body).optLong("revision", -1)
        // The answer can come before session_view_changed; the view is read again unless it came.
        if ((_state.value.views[sessionId]?.revision ?: -1) < revision) refreshView(sessionId)
    }

    private fun reorderSessions(id: String, before: String?) {
        updateState { it.copy(sessions = it.sessions.movedBefore(id, before) { s -> s.id }) }
    }

    /** Renames a tab of the shown session, or of [sessionId] without attaching it. */
    suspend fun renameWindow(label: String, windowId: String? = null, sessionId: String? = null) {
        val sid = sessionId ?: requireSession()
        val window = windowId ?: _state.value.view?.activeWindow()?.id ?: return
        rpc(
            "session.set_window_label",
            JSONObject().put("session_id", sid).put("window_id", window).put("label", label),
        )
    }

    suspend fun renameBlock(label: String, blockId: String? = null) {
        val sid = requireSession()
        val block = blockId ?: _state.value.view?.focusedBlock()?.id ?: return
        onPane(sid, block) {
            rpc(
                "session.set_block_label",
                JSONObject().put("session_id", sid).put("block_id", block).put("label", label),
            )
        }
    }

    /** Types [text] into the shown pane, after everything typed before it. */
    suspend fun sendText(text: String) {
        if (text.isEmpty()) return
        val call = write(text)
        val (sid, block) = call.target()
        onPane(sid, block) { input(call).await() }
    }

    /**
     * Keystrokes and mouse reports from the terminal view arrive as UTF-8 bytes. They join the
     * input queue without waiting, so a swipe is never throttled by the caller.
     */
    fun writeBytes(data: ByteArray) {
        if (data.isEmpty()) return
        input(write(String(data, Charsets.UTF_8)))
    }

    /** [writeBytes], with a way to wait until Rex has the bytes; for tests. */
    internal fun writeBytesAwaitable(data: ByteArray): CompletableDeferred<Unit> = input(write(String(data, Charsets.UTF_8)))

    /**
     * Sends [file] to a pane with Rex's native upload. [sessionId] and [blockId] fix the target when
     * the file is picked, so moving around the app while it is staged or sent cannot deliver it
     * somewhere else. The flow reports every phase and ends with the final one; cancelling it
     * stops the upload, and a stop after the half-close is reported as [UploadPhase.Uncertain]
     * rather than retried.
     */
    fun upload(
        file: File,
        name: String,
        sessionId: String? = null,
        blockId: String? = null,
    ): Flow<UploadProgress> = channelFlow {
        val sid = sessionId ?: requireSession()
        val block = blockId
            ?: _state.value.view?.focusedBlock()?.id
            ?: throw NotReadyException("No terminal in this window")
        val uploader = FileUpload(
            endpoint,
            httpFactory,
            uploadClientFactory,
            trace,
        )
        uploader.upload(sid, block, file, name) { trySend(it) }
    }.flowOn(Dispatchers.IO)

    /**
     * Pastes [text] into the shown pane, or into [blockId] of [sessionId]: Rex's paste event, so
     * the program gets it as a paste (bracketed when it asks) and nothing is run by it.
     */
    suspend fun paste(text: String, sessionId: String? = null, blockId: String? = null) {
        if (text.isEmpty()) return
        val (sid, block) = if (sessionId != null && blockId != null) sessionId to blockId else requireBlock()
        onPane(sid, block) { input(pasteCall(sid, block, text)).await() }
    }

    /**
     * Rex's Clear Terminal (`com.superlogical.terminal.clear`): the screen and scrollback go, the
     * line being typed stays at the top. Nothing changes under a full-screen program.
     */
    suspend fun clearPane() {
        val (sid, block) = requireBlock()
        paneMethod(sid, block, "clear")
    }

    /** Rex's Reset Terminal (`…reset`): the terminal starts over, cleared; the program keeps running. */
    suspend fun resetPane() {
        val (sid, block) = requireBlock()
        paneMethod(sid, block, "reset")
    }

    /**
     * A folder of the host, as the pane on screen's program would resolve [path]
     * (`com.superlogical.terminal.list_dir`): empty is the program's working directory, `~` its
     * home. Files are listed too, marked. Null when the pane went away meanwhile.
     */
    suspend fun listDir(path: String, files: Boolean = true, limit: Int = LIST_DIR_LIMIT): DirListing? {
        val (sid, block) = requireBlock()
        val args = JSONObject().put("path", path).put("files", files).put("limit", limit)
        return paneMethod(sid, block, "list_dir", args)?.let(DirListing::parse)
    }

    /**
     * Rex's host list (`hosts.list`): the other Rex servers it knows, read as hosts the phone could
     * import (see [RexHostList]). Nothing on the phone changes.
     */
    suspend fun hostList(): HostImportParse = RexHostList.read(rpc("hosts.list", JSONObject()))

    /**
     * The pane on screen's output, screen and scrollback, as plain text with each line as the
     * program wrote it (`format` with `trim` and `unwrap`). Rex hands a large one over in parts
     * (`blob_read`); past [maxBytes] only the newest output is kept, from the start of a line.
     * Null when the pane went away meanwhile.
     */
    suspend fun paneText(maxBytes: Int = MAX_TEXT_BYTES): PaneText? {
        val (sid, block) = requireBlock()
        val args = JSONObject().put("format", "text").put("trim", true).put("unwrap", true).put("max_bytes", TEXT_PART_BYTES)
        val first = paneMethod(sid, block, "format", args) ?: return null
        val head = first.optString("content").toByteArray()
        val id = first.optLong("id")
        if (id <= 0) return PaneText(String(head, Charsets.UTF_8), cut = false)
        val total = first.optLong("total", -1)
        val cut = total > maxBytes
        val text = java.io.ByteArrayOutputStream()
        var offset: Long
        if (cut) {
            offset = total - maxBytes
        } else {
            text.write(head)
            offset = head.size.toLong()
        }
        while (true) {
            val part = paneMethod(
                sid,
                block,
                "blob_read",
                JSONObject().put("id", id).put("offset", offset).put("max_bytes", TEXT_PART_BYTES),
            ) ?: return null
            val data = part.optString("data").toByteArray()
            text.write(data)
            offset += data.size
            if (part.optBoolean("eof", true) || data.isEmpty()) break
        }
        var out = text.toString(Charsets.UTF_8.name())
        // Read from the middle, the first line may start part-way, even part-way through a character.
        if (cut) out = out.substringAfter('\n', out)
        return PaneText(out, cut)
    }

    private suspend fun paneMethod(sid: String, block: String, method: String, args: JSONObject? = null): JSONObject? =
        onPane(sid, block) {
            rpc(
                "block.method",
                JSONObject().put("session_id", sid).put("block_id", block).put("method", method).apply { args?.let { put("args", it) } },
            )
        }

    suspend fun sendKey(
        code: String,
        mods: String = "",
        text: String = "",
        codepoint: Int = text.firstCodePoint(),
        consumedMods: String = "",
    ) {
        val (sid, block) = requireBlock()
        onPane(sid, block) { input(keyCall(sid, block, code, mods, text, codepoint, consumedMods)).await() }
    }

    /**
     * Answers the program in [blockId] of [sessionId] - shown or not - from a notification: [text]
     * pasted, as the key bar's Paste does, so the terminal brackets it when the program asked for
     * that, then Enter. False when the pane is gone. The bootstrap lets this connection send keys
     * to any session's panes (rex-parity.md, verified on preview.22).
     */
    suspend fun replyTo(sessionId: String, blockId: String, text: String, enter: Boolean = true): Boolean =
        onPane(sessionId, blockId) {
            if (text.isNotEmpty()) input(pasteCall(sessionId, blockId, text)).await()
            if (enter) input(keyCall(sessionId, blockId, "enter", "", "", 0, "")).await()
            true
        } ?: false

    /**
     * Approve or Deny for the approval prompt in [blockId] of [sessionId]: the pane's records are
     * read again from Rex first, and the agent's keys ([approvalKeys]) go only when its prompt is
     * still the one [stamp] names ([promptStamp]) - the one the person saw. Anything else, a new
     * question, a record gone or a program that stopped asking, is [PromptAnswer.Changed] and
     * nothing is typed.
     */
    suspend fun answerPrompt(sessionId: String, blockId: String, stamp: String, approve: Boolean): PromptAnswer {
        val args = JSONObject().put("session_id", sessionId).put("block_id", blockId).put("args", JSONObject())
        val read = onPane(sessionId, blockId) { rpc("com.superlogical.terminal.program_status", args) } ?: return PromptAnswer.NotSent
        val now = summarizeStatus(parseProgramRecords(read))
        if (now == null || promptStamp(now) != stamp) return PromptAnswer.Changed
        val keys = approvalKeys(now)?.keys(approve) ?: return PromptAnswer.Changed
        val sent = onPane(sessionId, blockId) {
            for (key in keys) input(keyCall(sessionId, blockId, key.code, "", key.text, key.codepoint, "")).await()
            true
        }
        return if (sent == true) PromptAnswer.Sent else PromptAnswer.NotSent
    }

    /** Ctrl+C to the program in [blockId] of [sessionId]: a notification's Interrupt. False when the pane is gone. */
    suspend fun interrupt(sessionId: String, blockId: String): Boolean =
        onPane(sessionId, blockId) {
            input(keyCall(sessionId, blockId, "key_c", "ctrl", "c", 'c'.code, "")).await()
            true
        } ?: false

    private fun pasteCall(sid: String, block: String, text: String) = InputCall(
        "block.event",
        JSONObject()
            .put("session_id", sid)
            .put("block_id", block)
            .put("event", JSONObject().put("type", "paste").put("payload", JSONObject().put("text", text))),
    )

    private fun keyCall(sid: String, block: String, code: String, mods: String, text: String, codepoint: Int, consumedMods: String) = InputCall(
        "client.key",
        JSONObject()
            .put("session_id", sid)
            .put("block_id", block)
            .put(
                "key",
                JSONObject()
                    .put("key", code)
                    .put("mods", mods)
                    .put("consumed_mods", consumedMods)
                    .put("text", text)
                    .put("unshifted_codepoint", codepoint)
                    .put("composing", false)
                    .put("action", "press"),
            ),
    )

    private fun write(text: String): InputCall {
        val (sid, block) = requireBlock()
        return InputCall(
            WRITE_METHOD,
            JSONObject().put("session_id", sid).put("block_id", block).put("args", JSONObject().put("data", text)),
        )
    }

    /** Queues [call] behind everything typed before it; the result completes once Rex has it. */
    private fun input(call: InputCall): CompletableDeferred<Unit> {
        if (clientRef.get() == null) throw NotReadyException("Not connected yet")
        input.trySend(call)
        return call.done
    }

    /**
     * Sends queued input one call at a time, each after the reply to the one before. Writes to the
     * same pane that queued up while the last call was out go together as one write: a swipe over a
     * full-screen program makes a report per row, and one round trip each held a fling for seconds
     * over a tailnet. The bytes keep their order, so this changes nothing the program sees.
     */
    private suspend fun inputLoop(client: RexClient) {
        var held: InputCall? = null
        while (true) {
            val first = held ?: input.receive()
            held = null
            val batch = arrayListOf(first)
            if (first.isWrite()) {
                while (true) {
                    val next = input.tryReceive().getOrNull() ?: break
                    if (next.isWrite() && next.target() == first.target() && batch.dataLength() < MAX_WRITE_BATCH) {
                        batch += next
                    } else {
                        // A key or a paste, or another pane: it goes after this batch, in its turn.
                        held = next
                        break
                    }
                }
            }
            val payload = if (batch.size == 1) first.payload else first.withData(batch.joinToString("") { it.data() })
            try {
                client.call(first.method, payload)
                batch.forEach { it.done.complete(Unit) }
            } catch (e: CancellationException) {
                batch.forEach { it.done.completeExceptionally(e) }
                held?.done?.completeExceptionally(e)
                throw e
            } catch (e: Exception) {
                batch.forEach { it.done.completeExceptionally(e) }
            }
        }
    }

    private fun InputCall.isWrite() = method == WRITE_METHOD

    private fun InputCall.target() = payload.optString("session_id") to payload.optString("block_id")

    private fun InputCall.data(): String = payload.optJSONObject("args")?.optString("data").orEmpty()

    private fun InputCall.withData(data: String): JSONObject =
        JSONObject(payload.toString()).put("args", JSONObject().put("data", data))

    private fun List<InputCall>.dataLength() = sumOf { it.data().length }

    private suspend fun runConnection() {
        routes?.let { prepare(it.current) }
        val client = clientFactory()
        clientRef.set(client)
        // The last failure stays on screen while the next attempt runs: a picker that only says
        // "connecting" while a tunnel is down gives no way to tell retrying from hanging.
        updateState {
            it.copy(phase = Phase.Connecting, endpointLabel = endpoint.label())
        }
        try {
            val welcome = client.connect(endpoint, reconnectToken)
            reconnectToken = welcome.reconnectToken
            // Events sent while the connection was down are lost, so every pane is read again.
            statusRead.clear()
            attachedSessions.clear()
            heardStatusFrom.clear()
            updateState { it.copy(clientId = welcome.clientId, resumed = welcome.resumed) }
            coroutineScope {
                launch {
                    client.events.collect { event ->
                        try {
                            onEvent(event)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            note(e.toProblem())
                        }
                    }
                }
                bootstrap()
                if (!ready.isCompleted) ready.complete(Unit)
                updateState { it.copy(phase = Phase.Live, problem = null) }
                routes?.live()
                // bootstrap is what attaches this connection to its session; only after it are pane
                // calls taken. Panes that came back meanwhile start watching at once.
                attached.value = true
                streams.forEach { it.retryNow() }
                launch { inputLoop(client) }
                launch { loadSessionViews(_state.value.sessions.map { it.id }, skip = _state.value.activeSessionId) }
                launch { statusLoop() }
                val cause = client.awaitClosed()
                throw ConnectionDroppedException(cause?.message ?: "Control connection dropped", cause)
            }
        } finally {
            attached.value = false
            withContext(NonCancellable) {
                try {
                    disownQuietly()
                } catch (_: Exception) {
                }
            }
            client.close()
            if (clientRef.get() === client) clientRef.set(null)
            // Input typed for this connection is not replayed on the next one: the pane may be gone.
            while (true) {
                val left = input.tryReceive().getOrNull() ?: break
                left.done.completeExceptionally(ConnectionDroppedException("Connection dropped before the input was sent", null))
            }
            ownedBlock = null
            lastResize = null
        }
    }

    private suspend fun bootstrap() {
        // 'all' adds block events - a program's status, the shell's directory - which the screen
        // would otherwise have to poll for, one round trip each.
        val body = JSONObject().put("event_scope", "all")
        val preferredId = preferred
        if (!preferredId.isNullOrBlank()) body.put("preferred_session_id", preferredId)
        val payload = rpc("session.bootstrap", body)
        val sessions = parseSessions(payload.optJSONObject("attach_all")?.optJSONArray("sessions"))
        val selected = payload.optJSONObject("selected_session")
        val view = selected?.optJSONObject("view")?.let { parseView(it) }?.let { it.placedAt(places[it.sessionId]) }
        val active = view?.sessionId ?: selected?.optJSONObject("session")?.optNullableString("session_id")
        active?.let { attachedSessions += it }
        updateState {
            it.copy(
                sessions = sessions,
                activeSessionId = active,
                view = view,
                views = if (view != null && view.sessionId.isNotEmpty()) it.views + (view.sessionId to view) else it.views,
                // A pane that closed while the connection was down is as closed as any other.
                closed = view?.let { v -> closedBy(it, v) } ?: it.closed,
            )
        }
        view?.let(::forgetClosedHere)
        preferred = active
    }

    /**
     * Fetches the views of [ids], so the home can name their tabs before they are opened. They are
     * asked for together and after the connection is live: one at a time in front of it, a tailnet
     * with a second of round trip held the whole app at *Connecting* for a second per session.
     * session.view neither attaches nor moves the desktop's focus.
     */
    private suspend fun loadSessionViews(ids: List<String>, skip: String? = null) = coroutineScope {
        for (id in ids) {
            if (id == skip) continue
            launch { runCatching { refreshView(id) } }
        }
    }

    private suspend fun onEvent(event: JSONObject) {
        when (event.optString("type")) {
            "session_created" -> {
                val session = event.optJSONObject("payload")?.optJSONObject("session") ?: return
                val id = session.optNullableString("session_id") ?: return
                val label = session.optNullableString("label") ?: shortId(id)
                updateState { it.copy(sessions = mergeSession(it.sessions, ListedSession(id, label))) }
                runCatching { refreshView(id) }
            }
            "session_destroyed" -> {
                val id = event.optJSONObject("payload")?.optNullableString("session_id")
                    ?: event.optNullableString("session_id")
                    ?: return
                dropSession(id)
            }
            // Applied as it says when both sessions are known here; otherwise the list is read again.
            "session_moved" -> {
                val payload = event.optJSONObject("payload") ?: JSONObject()
                val id = payload.optNullableString("session_id")
                val before = payload.optNullableString("before_session_id")
                val known = _state.value.sessions.map { it.id }
                if (id != null && id in known && (before == null || before in known)) reorderSessions(id, before) else refreshSessionList()
            }
            "session_event" -> {
                val payload = event.optJSONObject("payload") ?: return
                val sid = payload.optNullableString("session_id") ?: return
                val inner = payload.optJSONObject("event") ?: return
                onSessionEvent(sid, inner)
            }
        }
    }

    private suspend fun onSessionEvent(sid: String, event: JSONObject) = try {
        sessionEvent(sid, event)
    } catch (e: RexCallException) {
        // A session ending closes its panes one by one before `session_destroyed`: reading its view
        // for one of them may find it gone already, which is no failure of anything asked for.
        if (e.code != "session_not_found") throw e else Unit
    }

    private suspend fun sessionEvent(sid: String, event: JSONObject) {
        val payload = event.optJSONObject("payload") ?: JSONObject()
        when (event.optString("type")) {
            "session_view_changed" -> {
                val revision = payload.optLong("revision")
                val known = _state.value.views[sid]?.revision ?: -1
                if (revision > known) refreshView(sid)
            }
            "session_label_changed" -> {
                val label = payload.optNullableString("label") ?: return
                updateState {
                    it.copy(
                        sessions = it.sessions.map { s -> if (s.id == sid) s.copy(label = label) else s },
                        view = if (it.view?.sessionId == sid) it.view.copy(label = label) else it.view,
                    )
                }
            }
            "session_destroyed" -> dropSession(sid)
            "block_event" -> onBlockEvent(sid, payload)
            // Which client owns a pane decides its size, so a change here redraws it as owned or not.
            "block_owner_changed" -> {
                if (payload.optString("reason") == "take") {
                    val block = payload.optNullableString("block_id")
                    val owner = payload.optNullableString("owner")
                    if (block != null && owner != null) tookOnDesk(block, owner)
                }
                refreshView(sid)
            }
            "block_closed", "window_closed", "window_created", "active_window_changed" ->
                refreshView(sid)
        }
    }

    /** Sessions whose view is being read because one of their panes reported before it was known. */
    private val viewsAsked: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * A pane of [sid] reported a status while the phone has no view of that session - its first
     * read failed, or has not come back yet - so its tab could not be on the home, in Running or
     * anywhere: the view is read now, once at a time.
     */
    private fun viewFor(sid: String) {
        if (_state.value.views.containsKey(sid) || _state.value.sessions.none { it.id == sid }) return
        if (!viewsAsked.add(sid)) return
        runnerScope?.launch {
            try {
                runCatching { refreshView(sid) }
            } finally {
                viewsAsked -= sid
            }
        }
    }

    /** A pane's own news: a program's status, or the directory its shell moved to. */
    private fun onBlockEvent(sid: String, event: JSONObject) {
        val block = event.optNullableString("block_id") ?: return
        // Rex sends a bell with an empty payload; an event with none at all is the same bell.
        if (event.optString("name") == "bell") {
            _signals.tryEmit(PaneSignal.Bell(sid, block))
            return
        }
        val body = event.optJSONObject("payload") ?: return
        when (event.optString("name")) {
            "desktop_notification" -> {
                // OSC 9;4 is a progress bar, which shares OSC 9's number: never a notification.
                if (body.optString("title").isEmpty() && OSC_PROGRESS.matches(body.optString("body"))) return
                _signals.tryEmit(PaneSignal.Note(sid, block, body.optString("title"), body.optString("body")))
            }
            "progress_report" -> setProgress(sid, block, parseProgressReport(body))
            "program_status_changed" -> {
                heardStatusFrom += sid
                changeStatus(block) { applyStatusEvent(it, body) }
                reportedDuringRead += block
                publishStatus()
                viewFor(sid)
            }
            // Rex drops records on its own - working and blocked ones when the program exits or the
            // shell prompts again - and says so with this event, not with a changed record.
            "program_status_removed" -> {
                heardStatusFrom += sid
                changeStatus(block) { removeStatusRecords(it, body) }
                reportedDuringRead += block
                publishStatus()
            }
            "pwd_changed" -> {
                val pwd = body.optNullableString("pwd") ?: return
                pwds[block] = pwd.removePrefix("file://")
                reportedDuringRead += block
                publishStatus()
            }
            "process_changed" -> {
                val program = foregroundProgram(body)
                if (program == null) foregrounds -= block else foregrounds[block] = program
                reportedDuringRead += block
                publishStatus()
            }
            "title_changed" -> {
                val title = programTitle(body)
                if (title == null) titleOf -= block else titleOf[block] = title
                reportedDuringRead += block
                publishStatus()
            }
            "clipboard_written" -> onCopied(block, body)
            "child_exited" -> {
                val now = clock()
                exits.values.removeIf { now - it.second > EXIT_CLOSES_MS }
                val exit = PaneExit(body.optInt("exit_code", -1), body.optNullableString("error"))
                exits[block] = exit to now
                // A program that ended has no work left to show.
                setProgress(sid, block, null)
                // The pane's stream can end before this event arrives, and the note go up first.
                updateState { st ->
                    val closed = st.closed
                    if (closed != null && closed.blockId == block && closed.exit == null) st.copy(closed = closed.copy(exit = exit)) else st
                }
            }
        }
    }

    /**
     * Puts [block]'s progress bar up, changes it, or takes it down ([progress] null). A bar that
     * hears nothing for [progressStaleMs] goes, as Ghostty's does: Rex keeps no bar to read back,
     * and a program that died mid-way would otherwise leave its bar for good.
     */
    private fun setProgress(sid: String, block: String, progress: PaneProgress?) {
        val count = progressReports.incrementAndGet()
        if (progress == null) {
            if (progressOf.remove(block) == null) return
        } else {
            progressOf[block] = Triple(sid, progress, count)
            runnerScope?.launch {
                delay(progressStaleMs)
                if (progressOf[block]?.third == count && progressOf.remove(block) != null) publishProgress()
            }
        }
        publishProgress()
    }

    private fun publishProgress() {
        val now = progressOf.mapValues { it.value.second }
        updateState { if (it.progress == now) it else it.copy(progress = now) }
    }

    /** Drops the bars of panes no longer in [view]'s session. */
    private fun forgetGoneProgress(view: SessionView) {
        if (progressOf.isEmpty()) return
        val present = view.allBlocks().mapTo(HashSet()) { it.id }
        if (progressOf.entries.removeIf { (block, v) -> v.first == view.sessionId && block !in present }) publishProgress()
    }

    /**
     * A program in [block] copied text. The event carries it unless it is too large, and then Rex
     * keeps the copy for `clipboard_read` for about a minute.
     */
    private fun onCopied(block: String, body: JSONObject) {
        val now = _state.value
        val view = now.view ?: return
        if (!active.value || now.closed?.sessionId == view.sessionId || view.focusedBlock()?.id != block) return
        body.optNullableString("text")?.let {
            _copies.tryEmit(PaneCopy(block, it))
            return
        }
        val size = body.optLong("bytes")
        if (size <= 0) return
        if (size > MAX_COPY_BYTES) {
            trace("pane $block: a copy of $size bytes is too large for the clipboard")
            return
        }
        val scope = runnerScope ?: return
        scope.launch {
            try {
                _copies.emit(PaneCopy(block, readCopy(view.sessionId, block, body.optLong("id"))))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                trace("pane $block: clipboard_read failed: ${e.message}")
            }
        }
    }

    /** The whole of copy [id], read in parts from Rex's `clipboard_read`. */
    private suspend fun readCopy(sid: String, block: String, id: Long): String {
        val text = java.io.ByteArrayOutputStream()
        var copy = id
        while (true) {
            val args = JSONObject().put("offset", text.size())
            if (copy > 0) args.put("id", copy)
            val part = rpc(
                "com.superlogical.terminal.clipboard_read",
                JSONObject().put("session_id", sid).put("block_id", block).put("args", args),
            )
            copy = part.optLong("id", copy)
            text.write(part.optString("data").toByteArray())
            if (part.optBoolean("eof", true) || text.size() > MAX_COPY_BYTES) return text.toString(Charsets.UTF_8.name())
        }
    }

    /**
     * Applies [change] to [block]'s records, stamping the time when the state they sum up to is a
     * new one: that is when, say, a program failed, as far as anyone on the phone can know.
     */
    private fun changeStatus(block: String, change: (Map<String, ProgramRecord>) -> Map<String, ProgramRecord>) {
        var before: String? = null
        var after: ProgramRecord? = null
        statusRecords.compute(block) { _, old ->
            // Marked under the same lock a first read writes under, so that read cannot put an
            // older answer over this change after checking for it.
            reportedDuringRead += block
            before = summarizeStatus(old.orEmpty().values.toList())?.state
            change(old.orEmpty()).also { after = summarizeStatus(it.values.toList()) }
        }
        val now = after
        when {
            now == null -> sinceOf -= block
            now.state != before -> sinceOf[block] = sinceFor(now)
        }
    }

    /** When [record]'s state began: Rex's stamp on it, or now when it has none. */
    private fun sinceFor(record: ProgramRecord): Long {
        val now = clock()
        return record.updatedAt?.coerceAtMost(now) ?: now
    }

    private fun publishStatus() {
        updateState { st ->
            // What Rex holds, plus a working record where only the program's title says it works.
            val held = LinkedHashMap<String, Map<String, ProgramRecord>>()
            for (block in statusRecords.keys + titleOf.keys) {
                val records = statusRecords[block].orEmpty()
                val app = summarizeStatus(records.values.toList())?.app ?: foregrounds[block]
                held[block] = withTitleWork(records, titleOf[block], app)
            }
            val summary = LinkedHashMap<String, ProgramRecord>()
            for ((block, records) in held) summarizeStatus(records.values.toList())?.let { summary[block] = it }
            // A program that reported itself names the pane; otherwise the foreground process does.
            val programs = LinkedHashMap<String, String>()
            for (block in statusRecords.keys + foregrounds.keys) {
                paneProgram(summary[block], foregrounds[block])?.let { programs[block] = it }
            }
            val busy = held.filterValues { records -> records.values.any { it.state == "working" || it.state == "blocked" } }.keys
            val all = LinkedHashMap<String, List<ProgramRecord>>()
            for ((block, records) in held) if (records.isNotEmpty()) all[block] = rootFirst(records.values)
            st.copy(
                blockStatus = summary,
                records = all,
                busy = HashSet(busy),
                statusSince = HashMap(sinceOf),
                pwd = st.view?.focusedBlock()?.id?.let { pwds[it] },
                programs = programs,
                titles = HashMap(titleOf),
                deskSeen = st.deskSeen.filterKeys { it in summary },
            )
        }
        noteDeskLooks()
    }

    /** [records] in Rex's order: the root record first, each parent before its children (ids sort that way). */
    private fun rootFirst(records: Collection<ProgramRecord>): List<ProgramRecord> = records.sortedBy { it.id }

    /** Rex's own view of each session, before the phone's place is put on it: where Rex's focus is. */
    private val rexViews = java.util.concurrent.ConcurrentHashMap<String, SessionView>()

    /** Whether each other client is a desk app, by client id, as `client.inspect` said. */
    private val desks = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val asking: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Done or failed panes another client took, with the report they had then, until that client is known. */
    private val takes = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    /**
     * Another client took [block]: when it is a desk and the pane's report was done or failed by
     * then, the desk looked at that report. Rex.app takes the pane of the tab it shows.
     */
    private fun tookOnDesk(block: String, owner: String) {
        if (owner == _state.value.clientId) return
        val records = _state.value.records[block] ?: return
        if (records.none(::markable)) return
        takes[block] = owner to (seenMark(records) ?: return)
        noteDeskLooks()
    }

    /**
     * Which done and failed panes a desk has looked at, inferred from what Rex says, since it keeps
     * no seen state: a pane a desk client took after it finished, and the pane that is the session's
     * active one while a desk client owns it. A desk client is one that says it is an app on a
     * computer (`client.inspect`: kind `app`, a platform other than `other`); the phone, another
     * phone and Rex's CLI are not. A desk asleep on that tab counts as having seen it.
     */
    private fun noteDeskLooks() {
        val st = _state.value
        val me = st.clientId ?: return
        val looked = HashMap<String, String>()
        fun desk(owner: String): Boolean? = if (owner == me) false else desks[owner] ?: run { askAbout(owner); null }
        for (view in rexViews.values) {
            val window = view.windows.firstOrNull { it.id == view.activeWindowId } ?: continue
            val block = window.focusedBlock() ?: continue
            val owner = block.owner ?: continue
            val records = st.records[block.id] ?: continue
            if (records.none(::markable)) continue
            if (desk(owner) == true) looked[block.id] = seenMark(records) ?: continue
        }
        for ((block, take) in takes.entries.toList()) {
            when (desk(take.first)) {
                true -> {
                    looked[block] = take.second
                    takes.remove(block)
                }
                false -> takes.remove(block)
                null -> Unit
            }
        }
        if (looked.isEmpty()) return
        updateState { s ->
            // A look adds to what the desk saw before, kept to the reports the pane still has.
            val merged = s.deskSeen + looked.filterKeys { it in s.blockStatus }.mapNotNull { (block, mark) ->
                mergeMarks(s.deskSeen[block], mark, s.records[block].orEmpty())?.let { block to it }
            }
            if (merged == s.deskSeen) s else s.copy(deskSeen = merged)
        }
    }

    /** Asks Rex what [client] is, once; a client Rex no longer knows is no desk. */
    private fun askAbout(client: String) {
        if (!asking.add(client)) return
        val scope = runnerScope ?: return
        scope.launch {
            val desk = try {
                val info = rpc("client.inspect", JSONObject().put("client_id", client)).optJSONObject("client")?.optJSONObject("info")
                val platform = info?.optNullableString("platform")
                info?.optNullableString("kind") == "app" && platform != null && platform != "other"
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            desks[client] = desk
            asking.remove(client)
            noteDeskLooks()
        }
    }

    /**
     * A session that is gone from Rex. When it is the one on screen there is nothing left to show,
     * so no session is active, which takes the app back to the list. Moving to another session here
     * put that session on screen without attaching it, and Rex then refused every call on its panes
     * with "block ... is not in the session". A session this phone destroyed moves on in
     * [destroySession], which opens the next one properly.
     */
    private fun dropSession(id: String) {
        val ours = id in closingHere
        rexViews.remove(id)
        places.remove(id)
        forgetGonePanes(SessionView(Long.MAX_VALUE, id, "", null, emptyList()))
        updateState {
            val rest = it.sessions.filter { s -> s.id != id }
            val view = if (it.view?.sessionId == id) null else it.view
            val active = when {
                it.activeSessionId != id -> it.activeSessionId
                ours -> rest.firstOrNull()?.id
                else -> null
            }
            it.copy(
                sessions = rest,
                views = it.views - id,
                view = view,
                activeSessionId = active,
                closed = it.closed?.takeIf { c -> c.sessionId != id },
            )
        }
    }

    private suspend fun refreshSessionList() {
        val payload = rpc("session.list", null)
        val sessions = parseSessions(payload.optJSONArray("sessions"))
        updateState { it.copy(sessions = sessions) }
        val known = _state.value.views.keys
        loadSessionViews(sessions.map { it.id }.filter { it !in known }, skip = _state.value.activeSessionId)
    }

    private suspend fun refreshView(sid: String) {
        val payload = rpc("session.view", JSONObject().put("session_id", sid))
        applyView(parseView(payload))
    }

    /**
     * Mirrors a focus change the server just accepted. The change carries the revision the call
     * returned, so a view fetched before it cannot overwrite it, while any later change still does.
     */
    private fun showLocally(change: (SessionView) -> SessionView?) {
        updateState { st ->
            val next = st.view?.let(change) ?: return@updateState st
            // The directory belongs to the pane on screen, so it moves with the focus.
            val pwd = next.focusedBlock()?.id?.let { pwds[it] }
            st.copy(view = next, views = st.views + (next.sessionId to next), pwd = pwd)
        }
    }

    private fun applyView(fromRex: SessionView) {
        if (fromRex.sessionId.isEmpty()) return
        rexViews.merge(fromRex.sessionId, fromRex) { old, new -> if (new.revision >= old.revision) new else old }
        if (fromRex.allBlocks().any { it.id !in statusRead }) statusWake.trySend(Unit)
        // A pane the phone was on that has closed gives the place back to Rex's choice.
        val place = places[fromRex.sessionId]
        if (place != null && fromRex.allBlocks().none { it.id == place }) places.remove(fromRex.sessionId)
        forgetGonePanes(fromRex)
        val view = fromRex.placedAt(places[fromRex.sessionId])
        updateState {
            val known = it.views[view.sessionId]?.revision ?: -1
            if (view.revision < known) return@updateState it
            val views = it.views + (view.sessionId to view)
            // Only the session this connection attached is shown. A view of another one, adopted
            // while none was active, put panes on screen that Rex does not count as in the session,
            // and refused every call on them.
            val active = it.activeSessionId == view.sessionId
            it.copy(
                views = views,
                view = if (active) view else it.view,
                sessions = mergeSession(it.sessions, ListedSession(view.sessionId, view.label)),
                closed = if (active) closedBy(it, view) else it.closed,
            )
        }
        forgetClosedHere(view)
        noteDeskLooks()
    }

    /** The panes this phone closed that [view] no longer has: their close has been seen through. */
    private fun forgetClosedHere(view: SessionView) {
        val left = view.allBlocks().map { it.id }.toSet()
        val inOtherSessions = _state.value.views.filterKeys { it != view.sessionId }.values.flatMap { v -> v.allBlocks().map { it.id } }.toSet()
        closingHere.removeAll { it.startsWith("block:") && it !in left && it !in inOtherSessions && _state.value.closed?.blockId != it }
    }

    /**
     * What the phone remembers about panes of [view]'s session that are gone from it now. The pane
     * it held has nobody to hand back to: asking Rex to disown it is refused, and that refusal,
     * met on the way to the next pane, was the error that stayed until Take over. A release
     * belongs to its pane and goes with it.
     */
    private fun forgetGonePanes(view: SessionView) {
        forgetGoneProgress(view)
        val present = view.allBlocks().mapTo(HashSet()) { it.id }
        val owned = ownedBlock
        if (owned != null && ownedSession == view.sessionId && owned !in present) {
            trace("pane $owned: gone while held; forgotten")
            ownedBlock = null
            ownedSession = null
            lastResize = null
        }
        if (releasedBlocks.isEmpty()) return
        val known = HashSet(present)
        _state.value.views.forEach { (sid, v) -> if (sid != view.sessionId) v.allBlocks().forEach { known += it.id } }
        releasedBlocks.retainAll(known)
    }

    /**
     * Runs [call] on [block]. When Rex refuses it because the pane is no longer there - closed on
     * the desk, by another client or by its program - nothing is left to do and nothing failed: the
     * screen says the pane closed instead. A refusal for a pane that is still there is an error.
     */
    private suspend fun <T> onPane(sid: String, block: String, call: suspend () -> T): T? = try {
        call()
    } catch (e: RexCallException) {
        if (paneGone(sid, block)) {
            trace("pane $block: ${e.message}; it is gone, so nothing to report")
            null
        } else {
            throw e
        }
    }

    /** True when [windowId] is no longer in [sid], read again from Rex. */
    private suspend fun windowGone(sid: String, windowId: String): Boolean {
        try {
            refreshView(sid)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return _state.value.sessions.none { it.id == sid }
        }
        return _state.value.views[sid]?.windows?.none { it.id == windowId } == true
    }

    /**
     * What the screen says once [next] replaces the shown view: that the pane it showed is gone,
     * when nobody here asked for that. A pane the phone closed itself moves on to what is left.
     */
    private fun closedBy(st: PocketState, next: SessionView): ClosedPane? {
        val before = st.view?.takeIf { it.sessionId == next.sessionId } ?: return st.closed
        // Already closed: the note stays until the person moves on, whatever Rex shows meanwhile.
        if (st.closed?.sessionId == next.sessionId) return st.closed
        val shown = before.focusedBlock() ?: return st.closed
        if (next.allBlocks().any { it.id == shown.id }) return st.closed
        // Only looked at: this runs inside a state update, which can run more than once when two
        // changes race - Rex tells a close before it answers it - and a second run must still know
        // the phone closed the pane itself. [forgetClosedHere] drops it once the view has it gone.
        if (shown.id in closingHere) return st.closed
        val tab = before.activeWindow()?.id
        // A program that ended a while ago left its pane open (it failed at once, or the pane keeps
        // its program's end on screen); then it was closed by someone, not by the exit.
        val exit = exits[shown.id]?.takeIf { clock() - it.second <= EXIT_CLOSES_MS }?.first
        return ClosedPane(next.sessionId, shown.id, tabClosed = next.windows.none { it.id == tab }, exit = exit)
    }

    /**
     * True when [blockId] is no longer in [sid] as Rex has it now. The view is read again, since the
     * event that said so may not have come yet, and a session whose view cannot be read is looked
     * for in the list. A refusal for a pane that is still there is a real error, and stays one.
     */
    private suspend fun paneGone(sid: String, blockId: String): Boolean {
        try {
            refreshView(sid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RexCallException) {
            try {
                refreshSessionList()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return false
            }
            if (_state.value.sessions.any { it.id == sid }) return false
            dropSession(sid)
            return true
        } catch (_: Exception) {
            return false
        }
        return _state.value.views[sid]?.allBlocks()?.none { it.id == blockId } == true
    }

    /**
     * The shown pane's bytes, for as long as this flow is collected: Rex's snapshot of it first,
     * then its output as it comes. Taking the pane, so Rex sizes it for this phone, and sizing it
     * happen here too, because all three belong to watching it. Ending the collection stops it.
     */
    fun watchPane(blockId: String): Flow<TerminalFeed> = flow {
        var backoff = MIN_BACKOFF_MS
        while (true) {
            // A data connection needs a ticket from the control connection: while that one is down,
            // asking for a pane only spins. This waits for it to come back.
            val live = _state.first { it.phase == Phase.Live }
            // The pane belongs to one session, which is not always the active one by now.
            val sid = live.views.entries.firstOrNull { (_, v) -> v.allBlocks().any { it.id == blockId } }?.key
                ?: live.activeSessionId
            if (sid == null) {
                // Nothing to watch until a session is open; the connection may still be coming up.
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(PANE_RETRY_MAX_MS)
                continue
            }
            try {
                coroutineScope {
                    // The picture is what the person is waiting for, so its stream is asked for at
                    // once; taking the pane and sizing it run beside it rather than in front of it.
                    // All three wait for the connection to be attached, though: that is what a
                    // ticket and a pane call need, and a refusal here costs the stream its whole
                    // retry cycle - the wait a pane used to sit through after the app came back.
                    attached.first { it }
                    launch {
                        // A pane is taken on sight, unless the user handed this one back: then it
                        // stays the desktop's until they ask for it again - every time it is shown.
                        if (blockId in releasedBlocks) {
                            if (_state.value.owning || !_state.value.released) markReleased()
                        } else {
                            if (_state.value.released) updateState { it.copy(released = false) }
                            try {
                                ensureOwned(sid, blockId)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // The picture does not wait on the pane being held: the stream
                                // goes on, and the ownership loop below takes the pane again.
                                trace("pane $blockId: not taken: ${e.message}")
                                if (!paneGone(sid, blockId)) note(e.toProblem(), blockId, PaneTrouble.Hold)
                            }
                        }
                        launch { sizeLoop(sid, blockId) }
                        ownershipLoop(sid, blockId)
                    }
                    val stream = TerminalStream({ endpoint }, httpFactory, trace) { ticket(sid, blockId) }
                    streams += stream
                    try {
                        var flowing = false
                        stream.feeds().collect {
                            // Bytes on screen are the pane working; whatever failed before is over.
                            if (!flowing) {
                                flowing = true
                                resolved(blockId, PaneTrouble.Stream)
                            }
                            emit(it)
                        }
                    } finally {
                        streams -= stream
                        stream.close()
                    }
                }
            } catch (e: CancellationException) {
                trace("pane $blockId: no longer watched")
                throw e
            } catch (e: PaneClosedException) {
                // Not a failure: the pane was closed, and the screen says so in its place.
                trace("pane $blockId: closed")
                return@flow
            } catch (e: RexCallException) {
                trace("pane $blockId: ${e.code} ${e.message}")
                // Whatever Rex calls the refusal, the view is what says whether the pane is gone.
                if (paneGone(sid, blockId)) {
                    trace("pane $blockId: closed")
                    return@flow
                }
                note(e.toProblem(), blockId, PaneTrouble.Stream)
                // The pane or its session is gone; watching it again cannot help.
                if (e.code == "not_found" || e.code == "session_not_found") return@flow
            } catch (e: Exception) {
                trace("pane $blockId: ${e.javaClass.simpleName}: ${e.message}")
                // Rex ends a closed pane's stream (close 4000, or a refused HELLO) at about the
                // time its news comes; whichever comes first, a pane that is gone is not an error.
                if (paneGone(sid, blockId)) {
                    trace("pane $blockId: closed")
                    return@flow
                }
                note(e.toProblem(), blockId, PaneTrouble.Stream)
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(PANE_RETRY_MAX_MS)
        }
    }

    /** Applies the phone's size to the pane whenever it changes, while this phone owns it. */
    private suspend fun sizeLoop(sid: String, blockId: String) {
        while (true) {
            val size = sizeRef.get()
            // A released pane is the desktop's, at the desktop's size. Sending ours would resize it
            // behind the user's back, which is the one thing release promises not to do.
            if (blockId in releasedBlocks) {
                delay(RELEASE_WAIT_MS)
                continue
            }
            if (lastResize != size) {
                try {
                    rpc(
                        "com.superlogical.terminal.resize",
                        JSONObject().put("session_id", sid).put("block_id", blockId).put(
                            "args",
                            JSONObject()
                                .put("columns", size.cols)
                                .put("rows", size.rows)
                                .put("cell_width_px", size.cellWidthPx)
                                .put("cell_height_px", size.cellHeightPx),
                        ),
                    )
                    lastResize = size
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Nothing else starts this loop again, so it must not end here. A call cut short by
                    // a dropped connection waits for the next one, which forgets lastResize and so
                    // sends the size again; one Rex turned down is tried again in a moment.
                    if (attached.value) delay(RESIZE_RETRY_MS) else attached.first { it }
                    continue
                }
            }
            // The wait for a new size. It is compared with the size wanted now, not with lastResize
            // alone: a size set while the call above was out is forgotten by setSize before the
            // answer lands, and the answer then records the older size as sent. Waiting on that
            // left the pane at the old grid - full screen's bands - until the next size, a rotation.
            while (lastResize != null && lastResize == sizeRef.get()) delay(100)
        }
    }

    /**
     * A one-time ticket for a new stream on this pane, once the connection has attached: asked for
     * before that, Rex refuses it, and the stream then waits out its backoff while the pane stays
     * empty - the wait a pane used to sit through after the app came back.
     */
    private suspend fun ticket(sid: String, blockId: String): String {
        attached.first { it }
        val payload = try {
            rpc(
                "data.connect",
                JSONObject()
                    .put("session_id", sid)
                    .put("block_id", blockId)
                    .put("data_ref_id", "terminal")
                    .put("subprotocol", TerminalStream.PROTOCOL),
            )
        } catch (e: RexCallException) {
            // A closed pane ends its stream, and asking again would only be refused again.
            if (paneGone(sid, blockId)) throw PaneClosedException("$blockId was closed")
            throw e
        }
        return payload.getString("ticket")
    }

    private suspend fun ensureOwned(sid: String, block: String) {
        ownership.withLock {
            if (block in releasedBlocks) {
                markReleased()
                return@withLock
            }
            if (ownedBlock == block && ownedSession == sid) {
                markOwned()
                return@withLock
            }
            // The pane left behind goes back to the desktop before the new one is taken.
            val previous = ownedBlock
            val previousSession = ownedSession
            if (previous != null && previous != block) {
                try {
                    rpc(
                        "session.disown_block",
                        JSONObject().put("session_id", previousSession ?: sid).put("block_ids", JSONArray().put(previous)),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A pane that closed has nobody to hand back to, and Rex refuses the disown.
                    // Neither that nor any other failure here is a reason to keep the pane asked
                    // for off this phone: that refusal is what stayed on screen until Take over.
                    trace("pane $previous: not handed back: ${e.message}")
                }
                ownedBlock = null
                ownedSession = null
            }
            takePaneLocked(sid, block)
        }
    }

    /** Takes the pane, after which Rex applies this phone's size to it instead of the old owner's. */
    private suspend fun takePane(sid: String, block: String) {
        ownership.withLock { takePaneLocked(sid, block) }
    }

    /** Caller holds [ownership]. */
    private suspend fun takePaneLocked(sid: String, block: String) {
        if (block in releasedBlocks) {
            markReleased()
            return
        }
        rpc(
            "session.own_block",
            JSONObject()
                .put("session_id", sid)
                .put("block_ids", JSONArray().put(block))
                .put("mode", "take"),
        )
        // A release can land while own_block is on its way; it wins, so the pane goes straight back.
        if (block in releasedBlocks) {
            rpc(
                "session.disown_block",
                JSONObject().put("session_id", sid).put("block_ids", JSONArray().put(block)),
            )
            ownedBlock = null
            ownedSession = null
            lastResize = null
            markReleased()
            return
        }
        ownedBlock = block
        ownedSession = sid
        ownerClient = _state.value.clientId
        // The new owner has to ask for its size again; Rex does not carry the old one over.
        lastResize = null
        markOwned()
        resolved(block, PaneTrouble.Hold)
    }

    /**
     * Hands the shown pane back to the desktop, and stops taking it back: the desktop then gives it
     * its own size and keyboard. The stream stays open, so the phone keeps showing the pane. The
     * release outlives this screen; it belongs to the pane until Take over.
     */
    suspend fun releasePane() {
        val sid = ownedSession ?: requireSession()
        val block = ownedBlock ?: requireBlock().second
        trace("pane $block: release asked")
        // Marked before the lock, so an own_block already in flight sees it and gives the pane back.
        releasedBlocks.add(block)
        markReleased()
        ownership.withLock {
            onPane(sid, block) {
                rpc(
                    "session.disown_block",
                    JSONObject().put("session_id", sid).put("block_ids", JSONArray().put(block)),
                )
            }
            if (ownedBlock == block) {
                ownedBlock = null
                ownedSession = null
            }
            lastResize = null
            markReleased()
        }
    }

    /** Takes a released pane back, and starts keeping it at this phone's size again. */
    suspend fun takeOver() {
        val (sid, block) = requireBlock()
        trace("pane $block: take over asked")
        releasedBlocks.remove(block)
        onPane(sid, block) { takePane(sid, block) }
    }

    private fun markOwned() {
        trace("pane: owned")
        updateState { it.copy(owning = true, released = false) }
    }

    private fun markReleased() {
        if (!_state.value.released) trace("pane: released")
        updateState { it.copy(owning = false, released = true) }
    }

    /**
     * Keeps the pane's ownership honest. A pane the phone has not got yet is taken. A pane the phone
     * had and the desktop then took is left to the desktop: the desktop takes a pane when its own
     * window is focused, and taking it back made the two trade it every few seconds, each trade a
     * resize that left the program's prompt half drawn. The phone then shows the desktop's screen,
     * like a released pane, and Take over asks for it again.
     */
    private suspend fun ownershipLoop(sid: String, blockId: String) {
        var wait = 0L
        while (true) {
            // Nothing to keep while nobody is looking; this asks Rex a question every few seconds.
            active.first { it }
            // The owner of a terminal is asked of the terminal itself: the view of a session does not
            // always follow an owner change, and a stale answer here means a pane that stays clipped.
            // A released pane is the desktop's on purpose; the loop still watches, so the button
            // can say who holds it, but it never takes it back on its own.
            if (blockId in releasedBlocks) {
                if (_state.value.owning || !_state.value.released) markReleased()
                delay(OWNERSHIP_CHECK_MS)
                continue
            }
            val mine = _state.value.clientId
            // Only a pane this connection took can be taken from it. A new connection is a new
            // client to Rex, which owns nothing yet: the pane it finds held by the client this phone
            // was before is taken again, not handed to "the desktop".
            val hadIt = _state.value.owning
            val ownedBy = ownerClient
            val owner = ownerOnServer(sid, blockId)
            if (owner != null && mine != null) {
                val owning = owner == Owner.Client(mine)
                if (_state.value.owning != owning) updateState { it.copy(owning = owning) }
            }
            if (owner == null || mine == null || owner == Owner.Client(mine)) {
                wait = 0L
            } else if (owner is Owner.Client && hadIt && ownedBy == mine) {
                trace("pane $blockId: taken by ${owner.id}; leaving it there")
                yieldToDesktop(blockId)
            } else {
                trace("pane $blockId: held by $owner (had=$hadIt by=$ownedBy, now $mine); taking it")
                delay(wait)
                wait = if (wait == 0L) FIRST_TAKE_BACK_MS else (wait * 2).coerceAtMost(TAKE_BACK_MAX_MS)
                if (blockId !in releasedBlocks) runCatching { takePane(sid, blockId) }
            }
            delay(OWNERSHIP_CHECK_MS)
        }
    }

    /** The desktop took [blockId] from this phone: it stays there until the phone asks for it. */
    private suspend fun yieldToDesktop(blockId: String) {
        ownership.withLock {
            releasedBlocks.add(blockId)
            if (ownedBlock == blockId) {
                ownedBlock = null
                ownedSession = null
            }
            lastResize = null
            markReleased()
        }
    }

    /**
     * Who Rex says owns [blockId]: [Owner.Nobody] for a pane no client holds, which is what our own
     * old connection leaves behind once Rex lets it go; null when the terminal could not be asked.
     */
    private suspend fun ownerOnServer(sid: String, blockId: String): Owner? = runCatching {
        val owner = rpc("com.superlogical.terminal.size", JSONObject().put("session_id", sid).put("block_id", blockId))
            .optNullableString("owner")
        if (owner == null) Owner.Nobody else Owner.Client(owner)
    }.getOrNull()

    private sealed interface Owner {
        data object Nobody : Owner
        data class Client(val id: String) : Owner
    }

    private suspend fun disownQuietly() {
        val block = ownedBlock ?: return
        val sid = ownedSession ?: return
        val client = clientRef.get() ?: return
        try {
            client.call(
                "session.disown_block",
                JSONObject().put("session_id", sid).put("block_ids", JSONArray().put(block)),
            )
        } catch (_: Exception) {
        }
        ownedBlock = null
        ownedSession = null
    }

    /**
     * Reads each pane's status and directory once, the first time it is in a known view; after that
     * Rex's events keep them current. Over a slow tailnet every call is a round trip, so polling
     * them used to fill the one control connection and push the user's own calls back by seconds.
     * Only the panes of sessions no status event is known to come from are read again, a status
     * call each on a slow timer ([sweepStatus]).
     */
    private suspend fun statusLoop() {
        var lastSweep = System.nanoTime()
        var failedSweeps = 0
        while (true) {
            combine(active, watching) { visible, watched -> visible || watched }.first { it }
            val state = _state.value
            val shown = state.view?.sessionId
            // The shown session first: those are the panes a person is about to look at.
            val order = state.views.entries.sortedBy { if (it.key == shown) 0 else 1 }
            var read = false
            for ((sid, view) in order) {
                for (block in view.allBlocks()) {
                    if (block.id in statusRead) continue
                    if (block.creator != null && block.creator != "com.superlogical.terminal") {
                        statusRead += block.id
                        continue
                    }
                    readStatus(sid, block.id)
                    read = true
                }
            }
            if (read) publishStatus()
            // A session whose view could not be read is read again, so its tabs - and what their
            // programs report - reach the home without the person opening it.
            val missing = _state.value.sessions.map { it.id }.filter { it !in _state.value.views }
            if (missing.isNotEmpty()) loadSessionViews(missing, skip = _state.value.activeSessionId)
            // Panes whose news may not come as events are read again, every few seconds on screen
            // and once a minute behind it, backing off while the reads fail.
            val every = sweepInterval(failedSweeps)
            var wait = STATUS_IDLE_MS
            if (unheardPanes().isNotEmpty()) {
                val since = (System.nanoTime() - lastSweep) / 1_000_000
                if (since >= every) {
                    failedSweeps = if (sweepStatus()) 0 else failedSweeps + 1
                    lastSweep = System.nanoTime()
                    wait = sweepInterval(failedSweeps)
                } else {
                    wait = every - since
                }
            }
            // New panes appear with a view; the wait ends early when one does.
            withTimeoutOrNull(wait.coerceIn(STATUS_MIN_WAIT_MS, STATUS_IDLE_MS)) { statusWake.receive() }
        }
    }

    private fun sweepInterval(failed: Int): Long {
        val base = if (active.value) STATUS_SWEEP_MS else STATUS_SWEEP_AWAY_MS
        return (base shl failed.coerceAtMost(4)).coerceAtMost(STATUS_SWEEP_MAX_MS)
    }

    /** Terminal panes already read once in sessions this connection may not hear status events of. */
    private fun unheardPanes(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for ((sid, view) in _state.value.views) {
            if (sid in attachedSessions || sid in heardStatusFrom) continue
            for (block in view.allBlocks()) {
                if (block.id !in statusRead) continue
                if (block.creator != null && block.creator != "com.superlogical.terminal") continue
                out += sid to block.id
            }
        }
        return out
    }

    /**
     * Reads again what each unheard pane's program reports, and its session's view, so a tab never
     * opened on the phone still has its state on the home. Rex has no call that answers for many
     * panes, so the calls go out together, one round trip in all. True when any was answered.
     */
    private suspend fun sweepStatus(): Boolean = coroutineScope {
        val panes = unheardPanes()
        val views = panes.map { it.first }.distinct().map { sid -> async { runCatching { refreshView(sid) }.isSuccess } }
        val reads = panes.map { (sid, block) -> async { pollStatus(sid, block) } }
        val answered = (reads + views).map { it.await() }
        publishStatus()
        answered.isEmpty() || answered.any { it }
    }

    /** One pane's program status read again; an event that lands meanwhile still wins. */
    private suspend fun pollStatus(sid: String, block: String): Boolean {
        reportedDuringRead -= block
        val payload = JSONObject().put("session_id", sid).put("block_id", block).put("args", JSONObject())
        val records = try {
            coroutineScope {
                // The title too: a spinner in it is how some agents say they work (withTitleWork).
                val title = async { runCatching { rpc("com.superlogical.terminal.title", payload) }.getOrNull() }
                val status = parseProgramRecords(rpc("com.superlogical.terminal.program_status", payload)).associateBy { it.id }
                title.await()?.let { answer ->
                    val named = programTitle(answer)
                    if (block !in reportedDuringRead) if (named == null) titleOf -= block else titleOf[block] = named
                }
                status
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        storeRead(block, records)
        return true
    }

    /**
     * Keeps [records], a read of [block], unless a status event came while it was in flight. The
     * check and the write are one step on the pane's entry, which a status event also changes in
     * one step (changeStatus). True when the read was kept.
     */
    private fun storeRead(block: String, records: Map<String, ProgramRecord>): Boolean {
        var wrote = false
        var before: String? = null
        statusRecords.compute(block) { _, old ->
            if (block in reportedDuringRead) {
                old
            } else {
                wrote = true
                before = old?.let { summarizeStatus(it.values.toList())?.state }
                records
            }
        }
        if (wrote) {
            // The time a state began comes with its record; from a Rex that stamps none, a
            // state that was already there (or changed while the phone was away) has no time.
            val now = summarizeStatus(records.values.toList())
            when {
                now?.updatedAt != null -> sinceOf[block] = sinceFor(now)
                before != now?.state -> sinceOf -= block
            }
        }
        return wrote
    }

    /**
     * One pane's first read: what its program reports, where its shell is, what runs in front and
     * what title it set. The four are asked together; after this, events keep each of them current.
     */
    private suspend fun readStatus(sid: String, block: String) {
        reportedDuringRead -= block
        fun args() = JSONObject().put("session_id", sid).put("block_id", block).put("args", JSONObject())
        try {
            coroutineScope {
                val status = async { rpc("com.superlogical.terminal.program_status", args()) }
                val pwd = async { rpc("com.superlogical.terminal.pwd", args()) }
                val process = async { runCatching { rpc("com.superlogical.terminal.process", args()) }.getOrNull() }
                val title = async { runCatching { rpc("com.superlogical.terminal.title", args()) }.getOrNull() }
                val records = parseProgramRecords(status.await()).associateBy { it.id }
                val dir = pwd.await().optNullableString("pwd")?.removePrefix("file://")
                val program = process.await()?.let { foregroundProgram(it) }
                val named = title.await()?.let { programTitle(it) }
                // An event that landed while this was in flight is newer than the answer; it wins.
                if (storeRead(block, records)) {
                    if (dir != null) pwds[block] = dir
                    if (program != null) foregrounds[block] = program
                    if (named != null) titleOf[block] = named
                }
            }
            statusRead += block
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Left unread, so the next sweep asks again; sweeps are a wake or half a minute apart.
        }
    }

    /**
     * One call on the control connection. Calls are not queued behind each other: Rex runs them
     * side by side and matches each answer to its request, so over a tailnet with a second of round
     * trip, a pane opening no longer waits for whatever else the app was asking. A caller that
     * depends on an earlier answer awaits it, as every sequence here does; only typed input has an
     * order Rex must keep, and it has its own queue.
     */
    private suspend fun rpc(method: String, payload: JSONObject?): JSONObject {
        val client = clientRef.get() ?: throw NotReadyException("Not connected yet")
        val sent = System.nanoTime()
        try {
            return client.call(method, payload)
        } finally {
            trace("rpc $method took ${(System.nanoTime() - sent) / 1_000_000}ms")
        }
    }

    private fun requireSession(): String =
        _state.value.activeSessionId ?: throw NotReadyException("No open session")

    private fun requireBlock(): Pair<String, String> {
        val sid = requireSession()
        val block = _state.value.view?.focusedBlock()?.id ?: throw NotReadyException("No terminal in this window")
        return sid to block
    }

    private fun mergeSession(list: List<ListedSession>, item: ListedSession): List<ListedSession> {
        val idx = list.indexOfFirst { it.id == item.id }
        if (idx < 0) return list + item
        return list.map { if (it.id == item.id) item else it }
    }
}

/** First wait before a connection attempt is retried, and the wait after a connection that was up. */
private const val MIN_BACKOFF_MS = 400L

private const val MAX_BACKOFF_MS = 8_000L

/** The longest wait between attempts while the app is away: a minute, so a host that is off costs little. */
private const val MAX_AWAY_BACKOFF_MS = 60_000L

/** How long to wait before taking a pane the first time, and the longest wait after retries. */
private const val FIRST_TAKE_BACK_MS = 1_000L

private const val TAKE_BACK_MAX_MS = 30_000L

/** Longest wait between the status reader's sweeps; a new pane in a view ends it sooner. */
private const val STATUS_IDLE_MS = 30_000L

/** How often panes Rex may send no status events for are read again: on screen, and behind it. */
private const val STATUS_SWEEP_MS = 12_000L

private const val STATUS_SWEEP_AWAY_MS = 60_000L

/** The longest wait between those reads while they fail. */
private const val STATUS_SWEEP_MAX_MS = 120_000L

private const val STATUS_MIN_WAIT_MS = 250L

/** How often a watched pane asks Rex who owns it, so it can take itself back when the desk does. */
private const val OWNERSHIP_CHECK_MS = 3_000L

/** Wait before a resize Rex turned down is sent again. */
private const val RESIZE_RETRY_MS = 1_000L

/** How often a released pane looks again at whether it may resume sizing itself. */
private const val RELEASE_WAIT_MS = 250L

/** Wait before the screen tries its pane again, after the connection or the stream let go. */
private const val PANE_RETRY_MAX_MS = 5_000L

/**
 * How long a call may take when the app comes back before its connection counts as dead. A tailnet
 * relay abroad answers in well under a second, so this leaves room for a slow path but not for the
 * half-minute a dead socket takes to be noticed otherwise.
 */
private const val LIVENESS_MS = 4_000L

/** How soon after its program's exit a pane closing is taken to be closed by that exit. */
private const val EXIT_CLOSES_MS = 5_000L

/** An OSC 9;4 progress report's body, as an OSC 9 notification would carry it: `4;state[;value]`. */
private val OSC_PROGRESS = Regex("4;[0-4](;\\d*)?")

/** The largest copy put on the phone's clipboard: Android hands a clip to other apps through Binder, which takes about 1 MB. */
private const val MAX_COPY_BYTES = 512 * 1024L

/** The most of a pane's output Copy output and Share output take: what a clip or a share can carry. */
const val MAX_TEXT_BYTES = 512 * 1024

/** The most entries one folder of Insert path lists: a phone's list, not a desk's. */
const val LIST_DIR_LIMIT = 1000

/** How much of a pane's text one call reads: Rex's default for blob_read. */
private const val TEXT_PART_BYTES = 48 * 1024

private const val WRITE_METHOD = "com.superlogical.terminal.write"

/**
 * Most characters one combined write carries. Rex takes a control message of up to 64 KiB, and a
 * character can take a few bytes of UTF-8 and JSON escaping, so this stays well under it.
 */
private const val MAX_WRITE_BATCH = 8_000

private fun String.firstCodePoint(): Int =
    if (isEmpty()) 0 else codePointAt(0)

/**
 * The state as the screen may show it. A notice that belongs to a pane lasts only while that pane
 * is the one on screen: once it closes, is replaced or is left, its failure means nothing to the
 * person - it was the stale line that stayed over the next tab until Take over.
 */
internal fun settled(st: PocketState): PocketState {
    val pane = st.notice?.blockId ?: return st
    val shown = if (st.closed != null) null else st.view?.takeIf { it.sessionId == st.activeSessionId }?.focusedBlock()?.id
    return if (shown == pane) st else st.copy(notice = null)
}
