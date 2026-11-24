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
import me.leaver.rexpocket.core.BackgroundHosts
import me.leaver.rexpocket.core.HostBackground
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
import me.leaver.rexpocket.data.QuickReplyStore
import me.leaver.rexpocket.core.PromptAnswer
import me.leaver.rexpocket.core.QuickReplies
import me.leaver.rexpocket.data.FontFiles
import me.leaver.rexpocket.data.FontCatalog
import me.leaver.rexpocket.data.SYSTEM_FONT
import me.leaver.rexpocket.data.TerminalFontState
import me.leaver.rexpocket.data.downloadedTypeface
import me.leaver.rexpocket.data.SavedHost
import me.leaver.rexpocket.data.HomeFolds
import me.leaver.rexpocket.data.HomeMarks
import me.leaver.rexpocket.core.mergeMarks
import me.leaver.rexpocket.core.activityDigest
import me.leaver.rexpocket.core.seenMark
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

// The app state the screens share: hosts, the tailnet node, and which connected host the terminal
// shows. The connections themselves are the process's ([Connections]); see docs/design/multi-host.md.

/** What the session list is waiting on: one of its rows, or a session still being created. */
sealed interface Opening {
    /** A session of the host [hostId]: two hosts (the same Rex saved twice) can share session ids. */
    data class Existing(val id: String, val hostId: String = "") : Opening
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
    /**
     * The tailnet nodes; a test gives its own, since the real ones need the Go library. Null takes
     * the process's: the nodes, like the connections, belong to [Connections], not to this screen.
     */
    tailnetNodes: TailnetNodes? = null,
) : AndroidViewModel(app) {
    private val store = HostStore(app)

    /** Every connected host's connection, which outlives this model; see docs/design/multi-host.md. */
    private val connections = Connections.of(app, tailnetNodes)
    private val nodes: TailnetNodes get() = connections.nodes

    /** True when this process was started again after Android killed it while the terminal was on screen. */
    private val terminalWasOpen = saved.get<Boolean>(KEY_TERMINAL_OPEN) == true
    private val updater = Updater(app)

    /** The newer build the app found, from the check through to the downloaded APK. */
    val update: StateFlow<UpdateState> = updater.state

    /** Which builds the updater offers: finished ones only, or the newest published as well. */
    val updateChannel: StateFlow<UpdateChannel> = updater.channel
    private var updateJob: Job? = null
    val hosts = MutableStateFlow(store.list())

    /** Import hosts, from a host list or a connected Rex's; see HostImport.kt. */
    val hostImport = HostImporter(
        app,
        store,
        viewModelScope,
        onChanged = { hosts.value = store.list() },
        rexHosts = { id -> connections[id]?.session?.hostList() },
    )

    /**
     * Settings › Backup: every setting and host to one file, and back; see SettingsBackup.kt. Its
     * hosts go through Import hosts' plan, and its new keys to Import hosts' keys page.
     */
    val settingsBackup = SettingsBackupper(
        app,
        store,
        viewModelScope,
        read = {
            PhoneSettings(
                appearance = appearance.value,
                fontScale = fontScale.value,
                alertPrefs = alertPrefs.value,
                backgroundMode = Alerts.enabled.value,
                filter = homeFilter.value,
                reopenLastTab = reopenLastTab.value,
                folds = folds.value,
                background = background.value,
                quickReplies = quickReplies.value.items,
            )
        },
        write = { writeSettings(it) },
        onHostsChanged = { hosts.value = store.list() },
        showKeys = { hostImport.showKeys(it) },
        backgroundMode = { Alerts.enabled.value },
    )

    /** The host dialog's Test connection: a tailnet host goes through the joined node, as its connection would. */
    val hostTester = HostTester(viewModelScope) { host -> connections.checkRoute(host) }
    val tailnet: MutableStateFlow<TailnetSettings> = connections.tailnet.also { it.value = store.tailnet() }

    /** The backends this build can run; see [TailnetNodes.of]. */
    val tailnetBackends: List<TailnetBackend> by lazy { nodes.available }

    /** The status of the backend that is selected, so the home screen follows the choice. */
    val tailnetStatus: StateFlow<TailnetStatus> = connections.tailnetStatus

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
     * The host the terminal shows, or showed last: the one a session or tab was opened on, or the
     * one connected last. The other connected hosts are on the home list, live, beside it.
     */
    private val focusId = MutableStateFlow<String?>(null)
    private val focusedLink: StateFlow<Connections.Link?> = combine(focusId, connections.links) { id, links -> id?.let { links[it] } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val focused: Connections.Link? get() = connections[focusId.value]

    /** Every connected host, live: its connection's state, its steps while connecting, its path. */
    val lives: StateFlow<Map<String, HostLive>> = connections.lives

    /**
     * How far the connection to the focused host has come, step by step, while it is on its way;
     * null once it is live, and for a host that is not being connected.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val connectProgress: StateFlow<ConnectProgress?> = focusedLink.flatMapLatest { it?.progress ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The node of [backend]: only one of them runs at a time. */
    private fun nodeFor(backend: TailnetBackend): TailnetNode = nodes[backend]

    /**
     * How the focused tailnet host is reached right now: direct, through a peer relay, or through a
     * DERP server. Null for a host on the phone's own network, and while it has not been measured.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val tailnetPath: StateFlow<TailnetPath?> = focusedLink.flatMapLatest { it?.path ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Measures the path to [hostId] (the focused host by default); a no-op unless it goes through the tailnet. */
    fun probeTailnetPath(hostId: String? = null) {
        connections.probePath(hostId ?: focusId.value ?: return)
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

    /** Opens or folds [hostId]: the person's choice, which wins over the default (open while connected) from now on. */
    fun setHostOpen(hostId: String, open: Boolean) = setFolds(folds.value.withHost(hostId, open))

    fun setSessionFolded(hostId: String, sessionId: String, folded: Boolean) = setSessionsFolded(hostId, listOf(sessionId), folded)

    /** Folds or opens each of [sessionIds] of [hostId]: the host sheet's Collapse all and Expand all sessions. */
    fun setSessionsFolded(hostId: String, sessionIds: List<String>, folded: Boolean) {
        val current = folds.value
        val keys = sessionIds.map { HomeFolds.sessionKey(hostId, it) }
        setFolds(current.copy(sessions = if (folded) current.sessions + keys else current.sessions - keys.toSet()))
    }

    /** Every fold at once: Collapse all and Expand all, and the Undo that puts the folds back as they were. */
    fun setFolds(next: HomeFolds) {
        if (next == folds.value) return
        store.setFolds(next)
        folds.value = next
    }

    /** Which done and failed panes were seen, by host and pane: on this phone, from the home, or at a desk. */
    val seenMarks: StateFlow<Map<String, String>> = connections.marks.seen

    /**
     * Marks [blockIds] of [hostId] seen, for the reports they have now; returns the marks they had
     * before, for an Undo.
     */
    fun markSeen(hostId: String, blockIds: Collection<String>): Map<String, String?> {
        val st = connections[hostId]?.pocket?.value ?: return emptyMap()
        // Every report each pane has now; the marks it had stay, kept to the reports still there.
        val marks = blockIds.mapNotNull { block ->
            val records = st.records[block] ?: return@mapNotNull null
            val key = HomeMarks.key(hostId, block)
            mergeMarks(seenMarks.value[key], seenMark(records), records)?.let { key to it }
        }.toMap()
        val before = marks.keys.associateWith { seenMarks.value[it] }
        connections.marks.mark(marks)
        // Seen is seen: a notification about it has done its job.
        for (block in blockIds) Alerts.settle(getApplication(), hostId, block)
        return before
    }

    /** Puts the seen marks back as [previous] had them: Mark as seen's Undo. */
    fun restoreSeen(previous: Map<String, String?>) = connections.marks.restore(previous)

    /** The home's filter: All, Needs you or Running, kept for the next start. */
    val homeFilter = MutableStateFlow(HomeFilter.of(connections.marks.filter()))

    fun setHomeFilter(filter: HomeFilter) {
        connections.marks.setFilter(filter.name)
        homeFilter.value = filter
    }

    /** A Needs you row's Reply: [text] and Enter to the pane, on its host's connection. */
    fun reply(hostId: String, sessionId: String, blockId: String, text: String) {
        userActed()
        viewModelScope.launch { connections.fleet.reply(hostId, sessionId, blockId, text) }
    }

    /** A Needs you row's Interrupt: Ctrl+C to the pane, on its host's connection. */
    fun interrupt(hostId: String, sessionId: String, blockId: String) {
        userActed()
        viewModelScope.launch { connections.fleet.interrupt(hostId, sessionId, blockId) }
    }

    /** The composer's send from Needs you: [text] pasted into the pane, then Return when [enter]. */
    fun sendPrompt(hostId: String, sessionId: String, blockId: String, text: String, enter: Boolean) {
        userActed()
        viewModelScope.launch { connections.fleet.reply(hostId, sessionId, blockId, text, enter = enter) }
    }

    /**
     * A Needs you row's Approve or Deny, for the prompt [stamp] names. When the pane asks something
     * else by now, nothing is sent and the home says so, then opens the pane to show what it asks.
     */
    fun answerPrompt(hostId: String, sessionId: String, windowId: String, blockId: String, stamp: String, approve: Boolean) {
        userActed()
        viewModelScope.launch {
            val answer = runCatching { connections.fleet.answerPrompt(hostId, sessionId, blockId, stamp, approve) }
                .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else PromptAnswer.NotSent }
            when (answer) {
                // Answered: its notification has done its job.
                PromptAnswer.Sent -> Alerts.settle(getApplication(), hostId, blockId)
                PromptAnswer.Changed -> _homeNotices.tryEmit(HomeNotice.PromptChanged(hostId, sessionId, windowId, blockId))
                PromptAnswer.NotSent -> _homeNotices.tryEmit(HomeNotice.NotAnswered(hostId))
            }
        }
    }

    private val quickReplyStore = QuickReplyStore(app)

    /** Settings › Quick replies, in the person's order. */
    val quickReplies = MutableStateFlow(quickReplyStore.get())

    fun setQuickReplies(next: QuickReplies) {
        quickReplyStore.set(next)
        quickReplies.value = next
    }

    /** The prompt composer's drafts, by host and pane, while the app runs. */
    val drafts = PromptDrafts()

    /** Drops the folds of [hostId]'s sessions that are not in [alive], so gone sessions do not pile up. */
    private fun pruneFolds(hostId: String, alive: Collection<String>) {
        val current = folds.value
        val next = current.keepSessions(hostId, alive)
        if (next == current) return
        store.setFolds(next)
        folds.value = next
    }
    /** The focused host's connection, as the terminal shows it; empty when no host is focused. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pocket: StateFlow<PocketState> = focusedLink.flatMapLatest { it?.pocket ?: flowOf(PocketState()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PocketState())

    /** The focused host's state right now, without waiting for [pocket] to follow it. */
    private val focusedState: PocketState get() = focused?.pocket?.value ?: PocketState()

    /** A connected SSH host that waits for the person to confirm its host key, if any. */
    val sshKeyQuestion: StateFlow<HostLive?> = connections.lives.map { lives ->
        lives.values.firstOrNull { live ->
            val trouble = live.state.problem?.trouble
            live.target.viaSsh && (trouble == Trouble.SshHostKeyUnknown || trouble == Trouble.SshHostKeyChanged)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
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
    val alertPrefs: MutableStateFlow<AlertPrefs> = connections.alertPrefs.also { it.value = store.alertPrefs() }

    /** Each host's background choices: Settings › Background, a host's page. */
    val background: StateFlow<BackgroundHosts> = connections.background

    /** The focused host: the one whose terminal is on screen, or was last. */
    val host: SavedHost? get() = focused?.target

    private val session: PocketSession? get() = focused?.session
    private var foreground = true

    /** Waits for a resumed connection to go back to its pane; see [resumeInto]. */
    private var resumeJob: Job? = null
    private var tailnetJob: Job? = null
    private val _tailnetError = MutableStateFlow<TailnetFailure?>(null)
    val tailnetError: StateFlow<TailnetFailure?> = _tailnetError

    /** The session being opened or created from the list, shown as busy until it is on screen. */
    private val _opening = MutableStateFlow<Opening?>(null)
    val opening: StateFlow<Opening?> = _opening

    init {
        connections.attachScreen()
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
        // What is connected is what the list says: a host deleted or given a new address since
        // these connections were made does not keep the old one.
        connections.reconcile(store.list())
        // The watch notification's Stop turns alerts off from outside the app; the setting follows.
        viewModelScope.launch {
            Alerts.enabled.collect { on ->
                Diagnostics.event(CONN_TAG, "alerts ${if (on) "on" else "off"}")
                store.setAlerts(on)
            }
        }
        followFocus()
        // Only a list a live connection read prunes the folds, never the cached one drawn while connecting.
        viewModelScope.launch {
            connections.lives.collect { lives ->
                for ((id, live) in lives) {
                    if (live.state.phase == Phase.Live && live.state.sessions.isNotEmpty()) pruneFolds(id, live.state.sessions.map { it.id })
                }
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

    /** [hostId]'s background choices; an always-connected host connects at once. */
    fun setHostBackground(hostId: String, next: HostBackground) {
        connections.setBackground(hostId, next)
    }

    /**
     * A settings backup's preferences, each as its own switch would set it. Background mode goes on
     * only where notifications are allowed (it cannot alert otherwise): false when it stayed off.
     */
    private fun writeSettings(w: SettingsWrite): Boolean {
        w.appearance?.let(::setAppearance)
        w.fontScale?.let(::setFont)
        w.alertPrefs?.let(::setAlertPrefs)
        w.filter?.let(::setHomeFilter)
        w.reopenLastTab?.let(::setReopenLastTab)
        w.folds?.let(::setFolds)
        w.background.forEach { (id, b) -> setHostBackground(id, b) }
        w.quickReplies?.let { setQuickReplies(QuickReplies(it)) }
        val on = w.backgroundMode ?: return true
        if (on == Alerts.enabled.value) return true
        if (on && !Alerts.allowed(getApplication())) return false
        setAlerts(on)
        return true
    }

    /** Background alerts on or off; [Connections] starts the watch for every connected host. */
    fun setAlerts(on: Boolean) {
        Alerts.enabled.value = on
    }

    /**
     * The screen is going away. With Background alerts on the connections stay, since they are
     * what the alerts come from (see docs/design/multi-host.md); otherwise they close, as before,
     * and the hosts that were connected connect again at the next start.
     */
    override fun onCleared() {
        connections.shown.value = null
        connections.detachScreen()
        if (!Alerts.enabled.value) {
            // The panes go back to the desktop's size.
            connections.disconnectAll(forget = false)
            nodes.all.forEach { it.stopAsync() }
            Alerts.unwatch(getApplication())
        }
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
                runCatching { nodeFor(settings.backend).start(connections.configFor(settings, setupKey)) }
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
        disconnect(id)
        store.delete(id)
        hosts.value = store.list()
        setFolds(folds.value.withoutHost(id))
        connections.marks.forget(id)
    }

    /**
     * Remembers the SSH host key the person confirmed for [hostId] and connects it again. An unknown
     * key and a changed one take the same path: the dialog says which it was before this is called.
     */
    fun trustSshHostKey(hostId: String, fingerprint: String) {
        val current = connections[hostId]?.target ?: return
        if (!current.viaSsh || fingerprint.isBlank()) return
        val updated = current.copy(sshHostKey = fingerprint)
        saveHost(updated)
        Diagnostics.event(CONN_TAG, "ssh host key confirmed for ${current.host}")
        connect(updated, resume = focusId.value == hostId && resumeAgain())
    }

    /**
     * Connects [target] and, when [resume] is set, goes back to the session and pane the app was
     * showing before the process died. A plain connect stops at the session list. Every other
     * connected host stays connected; a host connected already starts over.
     *
     * [target] becomes the focused host unless the terminal shows another host's pane, which stays.
     */
    fun connect(target: SavedHost, resume: Boolean = false) {
        val refocus = route.value != Route.Term || focusId.value == target.id || focused == null
        if (focusId.value == target.id) {
            cancelResume()
            uploadJob?.cancel()
            clearUpload()
        }
        val link = connections.connect(target, resume = resume)
        if (refocus) {
            focus(target.id)
            route.value = Route.Home
            if (resume) {
                resumeOn(link)
            } else {
                resumePending = false
                connectedToResume = false
            }
        }
    }

    /** Makes [hostId] the focused host, the one the terminal and the store's last host follow. */
    private fun focus(hostId: String) {
        if (focusId.value == hostId) return
        if (focusId.value != null) {
            // A file going to the other host's pane goes on; its pill is that pane's.
            cancelResume()
        }
        focusId.value = hostId
        store.setLastHost(hostId)
    }

    private fun cancelResume() {
        resumeJob?.cancel()
        resumeJob = null
    }

    /** Goes back to the pane remembered for [link]'s host once it is live, unless the person moved first. */
    private fun resumeOn(link: Connections.Link) {
        cancelResume()
        resumePending = true
        connectedToResume = true
        val previousSession = store.lastSession(link.id)
        resumeJob = viewModelScope.launch {
            val s = link.ready.await()
            s.state.first { it.phase == Phase.Live && it.sessions.isNotEmpty() }
            resumeInto(link, s, previousSession)
        }
    }

    /**
     * What follows the focused host, which is what the terminal shows: the session and pane it
     * showed, kept for the next start; a session gone from Rex taking the terminal back to the list;
     * the pane on screen, whose news is seen as it comes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun followFocus() {
        viewModelScope.launch {
            var lastSession: Pair<String, String>? = null
            var lastPane: Triple<String, String, String>? = null
            var shownLabel: String? = null
            combine(focusedLink.flatMapLatest { l -> l?.pocket?.map { l to it } ?: flowOf(null) }, route) { pair, r -> pair to r }
                .collect { (pair, r) ->
                    val (link, st) = pair ?: run {
                        connections.shown.value = null
                        return@collect
                    }
                    val onTerm = r == Route.Term
                    // Only a pane that was on the terminal screen counts: connecting selects a session
                    // on its own, and its Rex focus must not replace the pane remembered for it.
                    val shown = if (onTerm) shownPane(st) else null
                    connections.shown.value = shown?.let { (sid, block) -> AlertTarget(link.id, sid, block) }
                    if (onTerm) {
                        st.activeSessionId?.let { sid ->
                            if (lastSession != link.id to sid) {
                                lastSession = link.id to sid
                                store.setLastSession(link.id, sid)
                            }
                        }
                        if (shown != null && lastPane != Triple(link.id, shown.first, shown.second)) {
                            lastPane = Triple(link.id, shown.first, shown.second)
                            store.setLastPane(link.id, shown.first, shown.second, st.sessions.map { it.id })
                        }
                        st.activeSessionId?.let { sid -> st.sessions.firstOrNull { it.id == sid }?.label?.let { shownLabel = it } }
                        // A session that is no longer on Rex leaves nothing to show: back to the list,
                        // which says where it went.
                        if (st.phase == Phase.Live && st.activeSessionId == null) {
                            route.value = Route.Home
                            shownLabel?.let { _homeNotices.tryEmit(HomeNotice.Ended(it, link.target.label)) }
                            shownLabel = null
                        }
                    }
                }
        }
    }

    /** Set once an alert has opened a pane, so the start-up resume does not replace it. */
    private var openedFromAlert = false

    /** Opens the pane an alert names, connecting its host first when it is not connected. */
    fun openAlert(target: AlertTarget) {
        val saved = store.find(target.hostId) ?: return
        openedFromAlert = true
        val link = connections.ensure(saved)
        enterSession(link, Opening.Existing(target.sessionId, saved.id)) { openSession(target.sessionId, target.blockId) }
    }

    private fun shownPane(st: PocketState): Pair<String, String>? {
        val sid = st.activeSessionId ?: return null
        val block = st.view?.takeIf { it.sessionId == sid }?.focusedBlock()?.id ?: return null
        return sid to block
    }

    /**
     * Reopens the remembered session on its remembered pane; stays on the list if it is gone, and
     * stays wherever the person went if they did anything before the connection was up.
     */
    private suspend fun resumeInto(link: Connections.Link, s: PocketSession, sessionId: String?) {
        if (!resumePending) return
        val target = sessionId?.takeIf { id -> s.state.value.sessions.any { it.id == id } }
        if (target == null) {
            resumePending = false
            return
        }
        try {
            s.openSession(target, rememberedPane(link.id, target))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            resumePending = false
            return
        }
        // Opening took a round trip, and the person may have moved in it.
        if (resumePending && route.value == Route.Home && _opening.value == null && focusId.value == link.id) route.value = Route.Term
        resumePending = false
    }

    private fun rememberedPane(hostId: String, sessionId: String): String? = store.lastPane(hostId, sessionId)

    /**
     * Opens a session of the focused host on the pane the phone showed there last time, or, the
     * first time and when that pane is gone, on the one Rex has focused, which is where the desktop
     * is. Only that one pane is drawn; the tab bar and the pane strip move between the others.
     */
    fun openSession(id: String) {
        val link = focused ?: return
        enterSession(link, Opening.Existing(id, link.id)) { openSession(id, rememberedPane(link.id, id)) }
    }

    /**
     * Opens a session on [target] from the home list, connecting that host first when it is not
     * connected. The row and the terminal both say *Opening* while it waits.
     */
    fun openSessionOn(target: SavedHost, id: String) {
        val link = connections.ensure(target)
        enterSession(link, Opening.Existing(id, target.id)) { openSession(id, rememberedPane(target.id, id)) }
    }

    /** Makes a session on [target], connecting it first when it is not connected. */
    fun createSessionOn(target: SavedHost) {
        enterSession(connections.ensure(target), Opening.New) { createShell(null) }
    }

    /** Opens one tab of [target]'s session, connecting the host first when it is not connected. */
    fun openTabOn(target: SavedHost, sessionId: String, windowId: String) {
        val link = connections.ensure(target)
        // One open on the chosen tab, rather than opening the session and then switching tabs: each
        // step is a round trip, and the second one used to flash the session's other tab first.
        enterSession(link, Opening.Existing(sessionId, target.id)) { openSession(sessionId, rememberedPane(target.id, sessionId), windowId) }
    }

    /**
     * Opens [blockId] in its tab [windowId] of [target]'s session: what an activity row does, so
     * the pane whose program reported is the one on screen, not the tab's other pane.
     */
    fun openPaneOn(target: SavedHost, sessionId: String, windowId: String, blockId: String) {
        val link = connections.ensure(target)
        enterSession(link, Opening.Existing(sessionId, target.id)) { openSession(sessionId, blockId, windowId) }
    }

    /** Opens a tab of the session on screen, or of another session of the focused host. */
    fun openTab(sessionId: String, windowId: String) {
        val link = focused ?: return
        enterSession(link, Opening.Existing(sessionId, link.id)) { openSession(sessionId, rememberedPane(link.id, sessionId), windowId) }
    }

    /** Adds a tab to [target]'s session and shows it; connects the host first when needed. */
    fun newTabOn(target: SavedHost, sessionId: String) {
        val link = connections.ensure(target)
        enterSession(link, Opening.Existing(sessionId, target.id)) {
            openSession(sessionId, rememberedPane(target.id, sessionId))
            newWindow()
        }
    }

    /** The sessions this host had last time, for a host that is not connected. */
    fun cachedSessions(hostId: String): List<ListedSession> = store.cachedSessions(hostId)

    /** Creates a shell session on the focused host and shows it once Rex has made it. */
    fun createSession(label: String? = null) {
        val link = focused ?: return
        enterSession(link, Opening.New) { createShell(label) }
    }

    /**
     * Runs [open] once [link]'s connection is up, then shows the terminal on that host. A tap on the
     * list from the cache, or while the tailnet is still coming up, waits for the connection instead
     * of being dropped; the terminal only appears for the session that was asked for, and the host
     * it is on becomes the focused one only then, so the pane on screen stays until it does.
     */
    private fun enterSession(link: Connections.Link, what: Opening, open: suspend PocketSession.() -> Unit) {
        if (BuildConfig.DEBUG) Log.i(CONN_TAG, "open $what (busy=${_opening.value}, phase=${link.pocket.value.phase})")
        if (_opening.value != null) return
        // A host that cannot be dialed at all (a tailnet host with no joined node) fails before a
        // connection exists; waiting for one would leave the row saying *Opening* forever.
        if (link.pocket.value.phase == Phase.Failed) return
        val waiting = link.ready
        _opening.value = what
        val tappedAt = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            try {
                val s = waiting.await()
                s.awaitReady()
                s.open()
                if (BuildConfig.DEBUG) Log.i(CONN_TAG, "opened $what after ${SystemClock.elapsedRealtime() - tappedAt}ms")
                if (connections[link.id] === link && link.session === s) {
                    focus(link.id)
                    route.value = Route.Term
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.i(CONN_TAG, "open $what failed: ${e.javaClass.simpleName}: ${e.message}")
                link.session?.note(e.toProblem())
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
        if (openedFromAlert || openedFromShare || route.value != Route.Home || focusId.value != null) return
        // Every host the person connected and did not disconnect, the one open last first, and
        // the hosts set to be always connected.
        val last = store.lastHost()?.let { store.find(it) }
        val saved = store.list()
        val wanted = background.value.atLaunch(last?.id, store.connectedHosts(), saved.map { it.id })
            .mapNotNull { id -> saved.firstOrNull { it.id == id } }
        if (wanted.isEmpty()) return
        val resume = terminalWasOpen || reopenLastTab.value
        Diagnostics.event(CONN_TAG, "launch: ${wanted.size} hosts, ${if (resume) "back to the last tab" else "the list"} (killed on the terminal: $terminalWasOpen)")
        for (target in wanted.drop(1)) connections.ensure(target)
        val first = wanted.first()
        val existing = connections[first.id]?.takeIf { it.target == first }
        if (existing != null) {
            // Background alerts kept it connected while the screen was gone.
            focus(first.id)
            if (resume && last?.id == first.id) resumeOn(existing)
        } else {
            connect(first, resume = resume && last?.id == first.id)
        }
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

    /** Retries [hostId]'s connection now (the focused host's by default). */
    fun retryNow(hostId: String? = null) {
        val id = hostId ?: focusId.value ?: return
        Diagnostics.event(CONN_TAG, "retry asked for")
        val resume = id == focusId.value && resumeAgain()
        val link = connections[id] ?: return
        val finished = connections.fleet.member(id)?.job?.isCompleted ?: true
        if (finished && id == focusId.value) {
            // Connecting again from scratch: the same wish to go back to the terminal, or none.
            connect(hosts.value.firstOrNull { it.id == id } ?: link.target, resume = resume)
            return
        }
        connections.retry(id, hosts.value.firstOrNull { it.id == id }, resume)
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
        foreground = visible
        connections.setForeground(visible)
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
            // What each connected host's programs reported, by place and without their words:
            // states, kinds, apps, owner names, ages and seen marks (activityDigest).
            hosts.value.forEachIndexed { i, known ->
                val st = connections[known.id]?.pocket?.value ?: return@forEachIndexed
                if (st.phase != Phase.Live) return@forEachIndexed
                val digest = activityDigest(st, { block -> seenMarks.value[HomeMarks.key(known.id, block)] }, System.currentTimeMillis())
                for (line in digest) add(ReportedFact("activity host ${i + 1}", line))
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
                            append(if (connections[known.id] != null) " · connected" else "")
                            append(if (known.id == open?.id) " · focused" else "")
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
            add(ReportedFact("text size", appearance.value.text.pref))
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

    /**
     * Disconnects [hostId] (the focused host by default) and keeps it disconnected at the next
     * start; every other host stays connected.
     */
    fun disconnect(hostId: String? = null) {
        val id = hostId ?: focusId.value ?: return
        Diagnostics.event(CONN_TAG, "disconnect")
        connections.disconnect(id)
        if (focusId.value == id) {
            cancelResume()
            uploadJob?.cancel()
            clearUpload()
            _opening.value = null
            focusId.value = null
            route.value = Route.Home
        }
        // The next start focuses a host still connected, if there is one.
        if (store.lastHost() == id) store.setLastHost(connections.links.value.keys.firstOrNull())
    }

    /** Disconnects every host. */
    fun disconnectAll() {
        connections.links.value.keys.toList().forEach { disconnect(it) }
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
        val sessionId = focusedState.activeSessionId ?: return
        val blockId = focusedState.view?.focusedBlock()?.id ?: return
        val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
        startUploads(session ?: return, listOf(uri to name), sessionId, blockId)
    }

    /**
     * Sends [files] to one pane, one after another, through the pill. The first that fails stays on
     * the pill, and the ones after it are not sent.
     */
    private fun startUploads(s: PocketSession, files: List<Pair<Uri, String>>, sessionId: String, blockId: String) {
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
        act(session ?: return, block)
    }

    private fun act(s: PocketSession, block: suspend PocketSession.() -> Unit) {
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

    /** The home's session and tab sheets: on [hostId] (the focused host by default), without attaching the session. */
    fun manage(action: HomeAction, hostId: String? = null) {
        userActed()
        val id = hostId ?: focusId.value ?: return
        val s = connections[id]?.session ?: return
        viewModelScope.launch {
            try {
                manageOn(s, action)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Said on the home, with a Retry: the terminal's banner is not where the person is.
                _homeNotices.tryEmit(HomeNotice.Failed(id, action, e.toProblem().detail ?: e.message.orEmpty()))
            }
        }
    }

    private suspend fun manageOn(s: PocketSession, action: HomeAction) {
        s.apply {
            when (action) {
                is HomeAction.RenameSession -> renameSession(action.label, action.sessionId)
                is HomeAction.NewTab -> newWindowIn(action.sessionId)
                is HomeAction.EndSession -> destroySession(action.sessionId)
                is HomeAction.RenameTab -> renameWindow(action.label, action.windowId, action.sessionId)
                is HomeAction.CloseTab -> closeWindow(action.windowId, action.sessionId)
                is HomeAction.MoveSession -> moveSession(action.sessionId, action.to)
                is HomeAction.MoveTab -> moveWindow(action.sessionId, action.windowId, action.to)
            }
        }
    }

    private val _homeNotices = kotlinx.coroutines.flow.MutableSharedFlow<HomeNotice>(extraBufferCapacity = 8)

    /** What the home says in a snackbar that it did not ask for: an action that failed, a session ended elsewhere. */
    val homeNotices: kotlinx.coroutines.flow.SharedFlow<HomeNotice> = _homeNotices

    /** Moves [hostId] to place [to] in the phone's own order of its hosts: hosts are the phone's, not Rex's. */
    fun moveHost(hostId: String, to: Int) {
        val list = store.list().toMutableList()
        val from = list.indexOfFirst { it.id == hostId }
        if (from < 0) return
        val host = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), host)
        store.saveAll(list)
        hosts.value = store.list()
    }

    /**
     * Removes [hostId]: disconnected first, then forgotten with its cached list, folds and seen
     * marks. Gives back what [restoreHost] needs to bring it back, not connected.
     */
    fun removeHost(hostId: String): RemovedHost? {
        val list = store.list()
        val index = list.indexOfFirst { it.id == hostId }
        if (index < 0) return null
        val removed = RemovedHost(list[index], index, store.cachedSessions(hostId), folds.value)
        deleteHost(hostId)
        return removed
    }

    /** Remove host's Undo: the host back in its place, with its cached list and folds; it stays disconnected. */
    fun restoreHost(removed: RemovedHost) {
        val list = store.list().filter { it.id != removed.host.id }.toMutableList()
        list.add(removed.index.coerceIn(0, list.size), removed.host)
        store.saveAll(list)
        if (removed.cached.isNotEmpty()) store.setCachedSessions(removed.host.id, removed.cached)
        hosts.value = store.list()
        setFolds(removed.folds)
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
        if (saved != null) shareConnect(saved)
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
        shareConnect(saved)
    }

    /** The picker's host row: connects to it when it is not, and shows its sessions. */
    fun shareConnect(target: SavedHost) {
        connections.ensure(target)
        if (focusId.value != target.id) {
            focus(target.id)
            route.value = Route.Home
        }
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
        val link = connections[target.hostId] ?: return
        if (link.pocket.value.phase != Phase.Live) return
        if (offer.request.files.any { (it.size ?: 0) > FileUpload.MAX_BYTES }) return
        _share.value = null
        val text = offer.request.text
        enterSession(link, Opening.Existing(target.sessionId, target.hostId)) {
            openSession(target.sessionId, target.blockId)
            if (!text.isNullOrEmpty()) paste(text, target.sessionId, target.blockId)
        }
        val s = link.session ?: return
        startUploads(s, offer.request.files.map { it.uri to it.name }, target.sessionId, target.blockId)
    }

    /** Set once something shared came in, so the start-up resume does not jump past its sheet. */
    private var openedFromShare = false

}

/**
 * Runs a file's clean-up after whatever started it is gone.
 */
private val teardown = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** How long a sent file stays on screen as green before the pill clears itself. */
private const val UPLOAD_DONE_MS = 1_500L

/** What a failure says in the report: its kind, then whatever the exception carried. */
/** Why the tailnet node did not start: what the node said, when it said anything ([tailnetFailureText]). */
data class TailnetFailure(val detail: String?)

private fun Problem.describe(): String = trouble.toString() + (detail?.let { ": $it" } ?: "")

/** A removed host, for Remove host's Undo: where it stood, its cached list and the folds before. */
data class RemovedHost(val host: SavedHost, val index: Int, val cached: List<ListedSession>, val folds: HomeFolds)

/** What the home tells in a snackbar of its own accord. */
sealed interface HomeNotice {
    /** A session open on the phone, or in a sheet, was ended on another device. */
    data class Ended(val session: String, val host: String) : HomeNotice

    /** A sheet's action that Rex refused or that did not get through; Retry sends it again. */
    data class Failed(val hostId: String, val action: HomeAction, val detail: String) : HomeNotice

    /** Approve or Deny found the pane asking something else: nothing was sent, and the pane opens. */
    data class PromptChanged(val hostId: String, val sessionId: String, val windowId: String, val blockId: String) : HomeNotice

    /** Approve or Deny could not reach the pane. */
    data class NotAnswered(val hostId: String) : HomeNotice
}

/** Connection diagnostics: `adb logcat -s RexPocketConn`. */
internal const val CONN_TAG = "RexPocketConn"

/** The saved-state key that says the terminal was on screen; see [RexViewModel.restore]. */
internal const val KEY_TERMINAL_OPEN = "terminal_open"
