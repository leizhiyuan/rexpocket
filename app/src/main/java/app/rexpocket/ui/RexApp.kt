package app.rexpocket.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.toArgb
import android.widget.EditText
import android.view.inputmethod.EditorInfo
import android.text.TextWatcher
import android.text.InputType
import android.text.Editable
import android.os.SystemClock
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.rexpocket.core.BlockInfo
import app.rexpocket.core.Endpoint
import app.rexpocket.core.Phase
import app.rexpocket.core.PocketSession
import app.rexpocket.core.PocketState
import app.rexpocket.core.ProgramRecord
import app.rexpocket.core.SessionView
import app.rexpocket.core.WindowInfo
import app.rexpocket.data.HostStore
import app.rexpocket.R
import app.rexpocket.data.SavedHost
import io.github.sagernet.libghostty.GhosttyTerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
    val hosts = MutableStateFlow(store.list())
    val route = MutableStateFlow<Route>(Route.Home)
    private val _pocket = MutableStateFlow(PocketState())
    val pocket: StateFlow<PocketState> = _pocket
    val fontScale = MutableStateFlow(store.fontScale())
    var host: SavedHost? = null
        private set

    private var session: PocketSession? = null
    private var sessionJob: Job? = null
    private var watch: Job? = null


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
        val previousBlock = store.lastBlock(target.id)
        val s = PocketSession(Endpoint(target.host, target.port, target.plaintext))
        session = s
        route.value = Route.Picker
        sessionJob = s.start(viewModelScope, previousSession)
        var rememberedBlock = previousBlock
        var resumed = false
        watch = viewModelScope.launch {
            s.state.collect { st ->
                _pocket.value = st
                if (st.activeSessionId != null) store.setLastSession(target.id, st.activeSessionId)
                val focused = st.view?.focusedBlock()?.id
                if (focused != null && focused != rememberedBlock) {
                    rememberedBlock = focused
                    store.setLastBlock(target.id, focused)
                }
                if (!resume || resumed || st.phase != Phase.Live || st.sessions.isEmpty()) return@collect
                resumed = true
                resumeInto(s, target.id, previousSession, previousBlock)
            }
        }
    }

    /** Reopens the remembered session and pane; falls back to the pane chooser or the list. */
    private suspend fun resumeInto(
        s: PocketSession,
        hostId: String,
        sessionId: String?,
        blockId: String?,
    ) {
        val target = sessionId?.takeIf { id -> s.state.value.sessions.any { it.id == id } } ?: return
        try {
            s.openSession(target)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        // The remembered pane may be gone; then the session's own focused pane is shown.
        if (blockId != null && s.state.value.view?.allBlocks()?.any { it.id == blockId } == true) {
            try {
                s.focusBlock(blockId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                store.setLastBlock(hostId, null)
            }
        }
        route.value = Route.Term
    }

    /**
     * Opens a session on the tab and pane Rex already has focused, the same place the desktop
     * shows. Only that one pane is drawn; the tab bar and the pane strip move between the others.
     */
    fun openSession(id: String) {
        val s = session ?: return
        viewModelScope.launch {
            try {
                s.openSession(id)
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
        route.value = Route.Term
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
    LaunchedEffect(Unit) { model.restore() }
    Box(Modifier.fillMaxSize().background(Bg)) {
        when (route) {
            Route.Home -> HomeScreen(
                hosts = hosts,
                onSave = model::saveHost,
                onDelete = model::deleteHost,
                onOpen = model::connect,
            )
            Route.Picker -> PickerScreen(
                hostLabel = model.host?.label ?: "Rex",
                state = pocket,
                onBack = model::disconnect,
                onOpen = model::openSession,
                onCreate = { model.act { createShell(it) }; model.showTerm() },
            )
            Route.Term -> TermScreen(
                state = pocket,
                fontScale = font,
                onFont = model::setFont,
                onBack = model::showPicker,
                onAct = model::act,
            )
        }
    }
}

@Composable
private fun HomeScreen(
    hosts: List<SavedHost>,
    onSave: (SavedHost) -> Unit,
    onDelete: (String) -> Unit,
    onOpen: (SavedHost) -> Unit,
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
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card)
                            .clickable { onOpen(host) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Accent))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(host.label, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "${host.host}:${host.port} · " + stringResource(
                                    if (host.plaintext) R.string.host_transport_plain else R.string.host_transport_tls,
                                ),
                                color = Muted,
                                fontSize = 13.sp,
                            )
                        }
                        Text(stringResource(R.string.action_edit), color = Accent, modifier = Modifier.clickable { editing = host }.padding(8.dp))
                    }
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
            onDismiss = { creating = false; editing = null },
            onDelete = editing?.let { host -> { onDelete(host.id); creating = false; editing = null } },
            onSave = {
                onSave(it)
                creating = false
                editing = null
            },
        )
    }
}

@Composable
private fun HostDialog(
    initial: SavedHost?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (SavedHost) -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 7297).toString()) }
    var plain by remember { mutableStateOf(initial?.plaintext ?: true) }
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
                Field(stringResource(R.string.field_address), host) { host = it }
                Field(stringResource(R.string.field_port), port) { port = it.filter { ch -> ch.isDigit() }.take(5) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.field_plaintext), color = Ink, modifier = Modifier.weight(1f))
                    Switch(checked = plain, onCheckedChange = { plain = it })
                }
                Text(stringResource(R.string.field_plaintext_warning), color = Amber, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = port.toIntOrNull() ?: return@TextButton
                if (host.isBlank() || p !in 1..65535) return@TextButton
                onSave(
                    SavedHost(
                        id = initial?.id ?: HostStore.newId(),
                        label = label.ifBlank { host.trim() },
                        host = host.trim(),
                        port = p,
                        plaintext = plain,
                    ),
                )
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
private fun Field(hint: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(hint, color = Muted, fontSize = 12.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = Ink, fontSize = 16.sp),
            cursorBrush = SolidColor(Accent),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).border(1.dp, Line, RoundedCornerShape(8.dp)).padding(10.dp),
        )
    }
}

@Composable
private fun PickerScreen(
    hostLabel: String,
    state: PocketState,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onCreate: (String?) -> Unit,
) {
    BackHandler(onBack = onBack)
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
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card)
                            .clickable { onOpen(session.id) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(session.label, color = Ink, fontSize = 16.sp)
                            Text(
                                if (windows == null) {
                                    stringResource(R.string.session_tap_to_enter)
                                } else {
                                    stringResource(R.string.session_window_count, windows)
                                },
                                color = Muted,
                                fontSize = 12.sp,
                            )
                        }
                        Text(stringResource(R.string.session_enter), color = Accent)
                    }
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

@Composable
private fun TermScreen(
    state: PocketState,
    fontScale: Float,
    onFont: (Float) -> Unit,
    onBack: () -> Unit,
    onAct: (suspend PocketSession.() -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val drawer = rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var ctrl by remember { mutableStateOf(false) }
    var ctrlLock by remember { mutableStateOf(false) }
    var lastCtrl by remember { mutableLongStateOf(0L) }
    var menu by remember { mutableStateOf(false) }
    var dpad by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Rename?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var termSession by remember { mutableStateOf<GhosttyTerminalSession?>(null) }
    val view = state.view
    val tab = view?.activeWindow()
    val focused = view?.focusedBlock()
    val status = focused?.let { state.blockStatus[it.id] }

    BackHandler {
        if (drawer.isOpen) scope.launch { drawer.close() } else onBack()
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        // Swiping the terminal scrolls; the drawer opens from the sessions button.
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Card) {
                Sidebar(state, onAct) {
                    scope.launch { drawer.close() }
                }
            }
        },
    ) {
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
                HeaderButton(stringResource(R.string.header_sessions)) { scope.launch { drawer.open() } }
                Box {
                    HeaderButton("···") { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        MenuSection(stringResource(R.string.level_pane))
                        MenuItem(stringResource(R.string.menu_split_right)) { menu = false; onAct { split(vertical = false) } }
                        MenuItem(stringResource(R.string.menu_split_below)) { menu = false; onAct { split(vertical = true) } }
                        if (focused != null) {
                            MenuItem(stringResource(R.string.menu_rename_pane)) {
                                menu = false
                                rename = Rename(Level.Pane, focused.id, focused.label)
                            }
                            MenuItem(stringResource(R.string.menu_close_pane), Danger) {
                                menu = false
                                confirm = Confirm(Level.Pane, focused.id, focused.label)
                            }
                        }
                        HorizontalDivider(color = Line)
                        MenuSection(stringResource(R.string.level_tab))
                        MenuItem(stringResource(R.string.menu_new_tab)) { menu = false; onAct { newWindow() } }
                        if (view != null && tab != null) {
                            val title = tabTitle(view, tab)
                            MenuItem(stringResource(R.string.menu_rename_tab)) {
                                menu = false
                                rename = Rename(Level.Tab, tab.id, tab.label)
                            }
                            MenuItem(stringResource(R.string.menu_close_tab), Danger) {
                                menu = false
                                confirm = Confirm(Level.Tab, tab.id, title)
                            }
                        }
                        HorizontalDivider(color = Line)
                        MenuSection(stringResource(R.string.level_session))
                        if (view != null) {
                            MenuItem(stringResource(R.string.menu_rename_session)) {
                                menu = false
                                rename = Rename(Level.Session, view.sessionId, view.label)
                            }
                            MenuItem(stringResource(R.string.menu_kill_session), Danger) {
                                menu = false
                                confirm = Confirm(Level.Session, view.sessionId, view.label)
                            }
                        }
                    }
                }
            }
            if (view != null) {
                TabBar(
                    view = view,
                    status = state.blockStatus,
                    onSelect = { w -> if (w.id != view.activeWindowId) onAct { focusWindow(w.id) } },
                    onRename = { w -> rename = Rename(Level.Tab, w.id, w.label) },
                    onClose = { w, title -> confirm = Confirm(Level.Tab, w.id, title) },
                    onNew = { onAct { newWindow() } },
                )
                if (tab != null && tab.blocks.size > 1) {
                    PaneStrip(
                        tab = tab,
                        status = state.blockStatus,
                        onSelect = { b -> if (b.id != focused?.id) onAct { focusBlock(b.id) } },
                        onRename = { b -> rename = Rename(Level.Pane, b.id, b.label) },
                        onClose = { b -> confirm = Confirm(Level.Pane, b.id, b.label) },
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
                    if (text.isNotEmpty()) {
                        draft = ""
                        onAct { submitLine(text) }
                    }
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
            text = { Field(stringResource(R.string.field_name), text) { text = it } },
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
    onRename: (WindowInfo) -> Unit,
    onClose: (WindowInfo, String) -> Unit,
    onNew: () -> Unit,
) {
    val list = rememberLazyListState()
    val activeIndex = view.windows.indexOfFirst { it.id == view.activeWindow()?.id }
    LaunchedEffect(activeIndex) { if (activeIndex >= 0) list.animateScrollToItem(activeIndex) }
    LazyRow(
        state = list,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(view.windows, key = { it.id }) { window ->
            val on = window.id == view.activeWindow()?.id
            val title = tabTitle(view, window)
            var actions by remember { mutableStateOf(false) }
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp))
                        .background(if (on) Accent else Card)
                        .combinedClickable(onClick = { onSelect(window) }, onLongClick = { actions = true })
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(window.status(status), on)
                    Text(
                        title,
                        color = if (on) Bg else Ink,
                        fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
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
                DropdownMenu(expanded = actions, onDismissRequest = { actions = false }) {
                    MenuItem(stringResource(R.string.menu_rename_tab)) { actions = false; onRename(window) }
                    MenuItem(stringResource(R.string.menu_close_tab), Danger) { actions = false; onClose(window, title) }
                }
            }
        }
        item(key = "new-tab") {
            Text(
                "+",
                color = Accent,
                fontSize = 16.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Card).clickable(onClick = onNew)
                    .padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }
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
    onRename: (BlockInfo) -> Unit,
    onClose: (BlockInfo) -> Unit,
    onNew: () -> Unit,
) {
    val list = rememberLazyListState()
    val current = tab.focusedBlock()?.id
    val index = tab.focusedIndex()
    LaunchedEffect(tab.id, index) { if (index >= 0) list.animateScrollToItem(index) }
    LazyRow(
        state = list,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(tab.blocks, key = { it.id }) { block ->
            val on = block.id == current
            val record = status[block.id]
            var actions by remember { mutableStateOf(false) }
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(99.dp))
                        .border(1.dp, if (on) Accent else Line, RoundedCornerShape(99.dp))
                        .combinedClickable(onClick = { onSelect(block) }, onLongClick = { actions = true })
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
                    Text(paneTitle(block, record), color = if (on) Accent else Ink, fontSize = 12.sp, maxLines = 1)
                }
                DropdownMenu(expanded = actions, onDismissRequest = { actions = false }) {
                    MenuItem(stringResource(R.string.menu_rename_pane)) { actions = false; onRename(block) }
                    MenuItem(stringResource(R.string.menu_close_pane), Danger) { actions = false; onClose(block) }
                }
            }
        }
        item(key = "new-pane") {
            Text(
                "+",
                color = Accent,
                fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(99.dp)).border(1.dp, Line, RoundedCornerShape(99.dp))
                    .clickable(onClick = onNew).padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
    }
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

/** Machine (sessions) → session's tabs → a tab's panes, with the shown pane highlighted. */
@Composable
private fun Sidebar(state: PocketState, onAct: (suspend PocketSession.() -> Unit) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Text(stringResource(R.string.sidebar_machine), color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.session_new),
            color = Accent,
            modifier = Modifier.clickable {
                onAct { createShell(null) }
                onClose()
            }.padding(vertical = 6.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            items(state.sessions, key = { it.id }) { session ->
                val active = session.id == state.activeSessionId
                val current = state.view?.takeIf { active && it.sessionId == session.id }
                Column {
                    Text(
                        session.label,
                        color = if (active) Accent else Ink,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (!active) onAct { openSession(session.id) }
                            onClose()
                        }.padding(vertical = 8.dp),
                    )
                    if (current != null) {
                        val shownTab = current.activeWindow()?.id
                        val shownPane = current.focusedBlock()?.id
                        current.windows.forEach { window ->
                            val onTab = window.id == shownTab
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    if (!onTab) onAct { focusWindow(window.id) }
                                    onClose()
                                }.padding(start = 12.dp, top = 5.dp, bottom = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                StatusDot(window.status(state.blockStatus), false)
                                Text(
                                    tabTitle(current, window),
                                    color = if (onTab) Ink else Muted,
                                    fontWeight = if (onTab) FontWeight.Medium else FontWeight.Normal,
                                    fontSize = 14.sp,
                                )
                            }
                            if (window.blocks.size > 1) {
                                window.blocks.forEachIndexed { i, block ->
                                    BlockRow(i + 1, block, state.blockStatus[block.id], block.id == shownPane) {
                                        if (block.id != shownPane) onAct { focusBlock(block.id) }
                                        onClose()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockRow(number: Int, block: BlockInfo, status: ProgramRecord?, focused: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 28.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor(status)))
        Spacer(Modifier.width(8.dp))
        Column {
            Text("$number  ${paneTitle(block, status)}", color = if (focused) Accent else Ink, fontSize = 14.sp)
            val detail = status?.msg ?: status?.title
            if (!detail.isNullOrBlank()) Text(detail, color = Muted, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** Rex names an unlabeled window "Window " plus an id tail; the tab's position reads better. */
@Composable
private fun tabTitle(view: SessionView, window: WindowInfo): String {
    val number = view.windows.indexOf(window) + 1
    return if (GENERATED_WINDOW_LABEL.matches(window.label)) stringResource(R.string.tab_number, number) else window.label
}

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
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    maxLines = 4
                    isSingleLine = false
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            onDraft(s?.toString().orEmpty())
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
private fun MenuItem(label: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label, color = color) }, onClick = onClick)
}

@Composable
private fun MenuSection(label: String) {
    Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
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
