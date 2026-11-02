package me.leaver.rexpocket.ui

import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sagernet.libghostty.GhosttyTerminalSession
import io.github.sagernet.libghostty.GhosttyTerminalView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.BlockInfo
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.PocketSession
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.ProgramRecord
import me.leaver.rexpocket.core.SessionView
import me.leaver.rexpocket.core.TerminalFeed
import me.leaver.rexpocket.core.WindowInfo
import me.leaver.rexpocket.core.toProblem

// The terminal, built like the terminal app it replaces: a header that says what this is and who
// holds it, the pane on a white card, and a dock of tabs and keys under the thumb.

/**
 * The line under the header. A connection that is down stays there with a way to retry; an action
 * that failed shows for a few seconds and goes, or goes when tapped, so it never outlives its cause.
 */
@Composable
internal fun TermBanner(state: PocketState, onRetry: () -> Unit, onDismiss: (Long) -> Unit) {
    val problem = rememberSettledProblem(state.phase, state.problem.takeIf { state.phase != Phase.Live })
    val notice = state.notice
    LaunchedEffect(notice?.id) {
        if (notice != null) {
            delay(NOTICE_MS)
            onDismiss(notice.id)
        }
    }
    val (text, action) = when {
        problem != null -> problemText(problem, state.endpointLabel) to onRetry
        notice != null -> problemText(notice.problem, state.endpointLabel) to { onDismiss(notice.id) }
        else -> return
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = action).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Colors.danger, fontSize = TextStyles.label, modifier = Modifier.weight(1f))
        Text(
            stringResource(if (problem != null) R.string.action_retry_now else R.string.action_dismiss),
            color = if (problem != null) Colors.accent else Colors.muted,
            fontSize = TextStyles.label,
        )
    }
}

/** Something long-pressed or picked from the menu that needs a rename or a confirmation. */
internal data class Rename(val kind: Level, val id: String, val current: String)

internal data class Confirm(val kind: Level, val id: String, val name: String)

internal enum class Level { Session, Tab, Pane }

/** The bottom sheet open over the terminal. */
internal sealed interface Sheet {
    data object Sessions : Sheet
    data object Actions : Sheet
    data object Navigator : Sheet
    data class TabActions(val windowId: String) : Sheet
    data class PaneActions(val blockId: String) : Sheet
}

@Composable
internal fun TermScreen(
    state: PocketState,
    fontScale: Float,
    onFont: (Float) -> Unit,
    onBack: () -> Unit,
    onHosts: () -> Unit,
    onOpenSession: (String) -> Unit,
    watchPane: (String) -> Flow<TerminalFeed>,
    onRetry: () -> Unit,
    onDismissNotice: (Long) -> Unit,
    onAct: (suspend PocketSession.() -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val ime = LocalSoftwareKeyboardController.current
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var ctrl by remember { mutableStateOf(false) }
    var ctrlLock by remember { mutableStateOf(false) }
    var lastCtrl by remember { mutableLongStateOf(0L) }
    var dpad by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Rename?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var termSession by remember { mutableStateOf<GhosttyTerminalSession?>(null) }
    var termView by remember { mutableStateOf<GhosttyTerminalView?>(null) }
    val view = state.view
    val tab = view?.activeWindow()
    val focused = view?.focusedBlock()
    val status = focused?.let { state.blockStatus[it.id] }
    val panes = tab?.blocks.orEmpty()

    // Typing goes straight to the terminal, as it does on the desktop: the keyboard key raises the
    // IME for the pane, and the key row stays above it. There is no composer between them.
    fun toggleKeyboard() {
        val target = termView ?: return
        val ime = context.getSystemService(InputMethodManager::class.java)
        // Whether the keyboard is up, not whether the pane has focus: the IME can be showing while
        // focus sits in its own window, and then the same key must put it away.
        val showing = ViewCompat.getRootWindowInsets(target)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (showing) {
            ime?.hideSoftInputFromWindow(target.windowToken, 0)
            target.clearFocus()
        } else {
            target.requestFocus()
            target.showIme()
        }
    }

    // An open sheet handles Back itself and closes.
    BackHandler(onBack = onBack)

    // The keyboard belongs to the pane that is on screen: leaving the terminal drops it, so the
    // session list does not come back with a keyboard over it.
    DisposableEffect(Unit) {
        onDispose {
            termView?.clearFocus()
            ime?.hide()
        }
    }

    Column(Modifier.fillMaxSize().background(Colors.bg).statusBarsPadding().imePadding()) {
        SessionHeader(
            state = state,
            view = view,
            tab = tab,
            status = status,
            onSwitchSession = { sheet = Sheet.Sessions },
            onNavigate = { if (view != null) sheet = Sheet.Navigator },
            onControl = {
                // The button is the state: filled "Release" while this phone holds the pane, and
                // outlined "Take over" once it has been handed back to the desktop.
                if (state.owning) onAct { releasePane() } else onAct { takeOver() }
            },
            onMore = { sheet = Sheet.Actions },
        )
        TermBanner(state, onRetry = onRetry, onDismiss = onDismissNotice)
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
            // The pane's own paper: one white card, the same surface as the dock, with the terminal
            // inset far enough that no row of text runs into its rounded corners.
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(Radius.dock)).background(Colors.surface)) {
                val shown = focused?.id
                key(shown) {
                    TerminalPane(
                        blockId = shown.orEmpty(),
                        feeds = watchPane,
                        fontScale = fontScale,
                        onFont = onFont,
                        onInput = { bytes -> onAct { writeBytes(bytes) } },
                        onResize = { cols, rows, cw, ch -> onAct { setSize(cols, rows, cw, ch) } },
                        onSession = { termSession = it },
                        onView = { termView = it },
                        // A horizontal swipe on the pane moves to the pane beside it, as the dots
                        // under the card say there is one.
                        onSwipe = { forward ->
                            if (panes.size > 1) {
                                val index = panes.indexOfFirst { it.id == focused?.id }
                                val next = (index + if (forward) 1 else -1).coerceIn(0, panes.lastIndex)
                                if (next != index) onAct { focusBlock(panes[next].id) }
                            }
                        },
                        onError = { onAct { note(it.toProblem()) } },
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
        PanePager(tab = tab, focused = focused, onSelect = { block -> onAct { focusBlock(block.id) } })
        if (dpad) Dpad(onAct)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.dock)).background(Colors.surface),
        ) {
            if (view != null) {
                TabDock(
                    view = view,
                    onSelect = { w -> if (w.id != view.activeWindowId) onAct { focusWindow(w.id) } },
                    onLongPress = { w -> sheet = Sheet.TabActions(w.id) },
                    onNew = { onAct { newWindow() } },
                    paneCount = tab?.blocks?.size ?: 0,
                    onNavigate = { sheet = Sheet.Navigator },
                )
            }
        }
        KeyDock(
            ctrl = ctrl || ctrlLock,
            locked = ctrlLock,
            dpad = dpad,
            stopping = status?.state == "working" || status?.state == "blocked",
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
            onKeyboard = { toggleKeyboard() },
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
                    SheetItem(stringResource(R.string.menu_close_pane), Colors.danger) {
                        confirm = Confirm(Level.Pane, focused.id, focused.label)
                    }
                }
            }
            Group(stringResource(R.string.level_tab)) {
                SheetItem(stringResource(R.string.menu_new_tab)) { onAct { newWindow() } }
                if (view != null && tab != null) {
                    val title = tabTitle(view, tab)
                    SheetItem(stringResource(R.string.menu_rename_tab)) { rename = Rename(Level.Tab, tab.id, tab.label) }
                    SheetItem(stringResource(R.string.menu_close_tab), Colors.danger) { confirm = Confirm(Level.Tab, tab.id, title) }
                }
            }
            Group(stringResource(R.string.level_session)) {
                SheetItem(stringResource(R.string.header_sessions)) { sheet = Sheet.Sessions }
                SheetItem(stringResource(R.string.header_hosts)) { onHosts() }
                if (view != null) {
                    SheetItem(stringResource(R.string.menu_rename_session)) {
                        rename = Rename(Level.Session, view.sessionId, view.label)
                    }
                    SheetItem(stringResource(R.string.menu_kill_session), Colors.danger) {
                        confirm = Confirm(Level.Session, view.sessionId, view.label)
                    }
                }
            }
        }
        Sheet.Navigator -> if (view != null) {
            ActionSheet(onDismiss = { sheet = null }, title = stringResource(R.string.menu_navigate)) {
                Group(label = stringResource(R.string.group_tabs)) {
                    view.windows.forEach { window ->
                        SheetItem(
                            label = tabTitle(view, window),
                            detail = window.blocks.size.takeIf { it > 1 }?.let { pluralStringResource(R.plurals.pane_count, it, it) },
                            marked = window.id == tab?.id,
                        ) {
                            if (window.id != view.activeWindowId) onAct { focusWindow(window.id) }
                        }
                    }
                }
                if (panes.size > 1) {
                    Group(label = stringResource(R.string.group_panes)) {
                        panes.forEachIndexed { index, block ->
                            SheetItem(
                                label = "${index + 1}  ${paneTitle(block, state.blockStatus[block.id])}",
                                detail = state.blockStatus[block.id]?.let { statusWord(it) },
                                marked = block.id == focused?.id,
                            ) {
                                if (block.id != focused?.id) onAct { focusBlock(block.id) }
                            }
                        }
                    }
                }
                Group {
                    SheetItem("+  " + stringResource(R.string.menu_new_tab)) { onAct { newWindow() } }
                    SheetItem("+  " + stringResource(R.string.menu_split_right)) { onAct { split(vertical = false) } }
                    SheetItem("+  " + stringResource(R.string.menu_split_below)) { onAct { split(vertical = true) } }
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
                        SheetItem(stringResource(R.string.menu_close_tab), Colors.danger) { confirm = Confirm(Level.Tab, window.id, title) }
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
                        SheetItem(stringResource(R.string.menu_close_pane), Colors.danger) { confirm = Confirm(Level.Pane, block.id, block.label) }
                    }
                }
            }
        }
    }

    rename?.let { target ->
        var text by remember(target) { mutableStateOf(target.current) }
        AlertDialog(
            onDismissRequest = { rename = null },
            containerColor = Colors.surface,
            title = {
                Text(
                    when (target.kind) {
                        Level.Tab -> stringResource(R.string.menu_rename_tab)
                        Level.Pane -> stringResource(R.string.menu_rename_pane)
                        Level.Session -> stringResource(R.string.menu_rename_session)
                    },
                    color = Colors.ink,
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
                }) { Text(stringResource(R.string.action_save), color = Colors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { rename = null }) { Text(stringResource(R.string.action_cancel), color = Colors.muted) }
            },
        )
    }

    confirm?.let { target ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Colors.surface,
            title = {
                Text(
                    when (target.kind) {
                        Level.Tab -> stringResource(R.string.confirm_close_tab, target.name)
                        Level.Pane -> stringResource(R.string.confirm_close_pane, target.name)
                        Level.Session -> stringResource(R.string.confirm_kill_session, target.name)
                    },
                    color = Colors.ink,
                )
            },
            text = {
                Text(
                    when (target.kind) {
                        Level.Tab -> stringResource(R.string.confirm_close_tab_body)
                        Level.Pane -> stringResource(R.string.confirm_close_pane_body)
                        Level.Session -> stringResource(R.string.confirm_kill_session_body)
                    },
                    color = Colors.muted,
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
                }) { Text(stringResource(R.string.action_close), color = Colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel), color = Colors.muted) }
            },
        )
    }
}

/**
 * Identity, then state, then control. The tile is the app's one dark mark; the name is the pane,
 * with its directory set as a path rather than as prose; the line under it is the session and what
 * the program is doing, and it opens the session list. On the right, the only question a phone
 * sharing a session has to answer: is this pane mine, or the desk's?
 */
@Composable
private fun SessionHeader(
    state: PocketState,
    view: SessionView?,
    tab: WindowInfo?,
    status: ProgramRecord?,
    onSwitchSession: () -> Unit,
    onNavigate: () -> Unit,
    onControl: () -> Unit,
    onMore: () -> Unit,
) {
    val title = when {
        view == null -> state.endpointLabel.ifBlank { stringResource(R.string.app_name) }
        tab != null -> tabTitle(view, tab)
        else -> view.label
    }
    val path = state.pwd?.let { shortPath(it) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalTile()
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.chip))
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.menu_navigate), onClick = onNavigate)
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    color = Colors.ink,
                    fontSize = TextStyles.body,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (path != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        path,
                        color = Colors.muted,
                        fontSize = TextStyles.label,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(0.75f, fill = false),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.chip))
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.header_sessions), onClick = onSwitchSession)
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(status?.let { statusTint(it) } ?: phaseTint(state.phase)))
                Spacer(Modifier.width(6.dp))
                Text(
                    view?.label ?: phaseText(state),
                    color = Colors.muted,
                    fontSize = TextStyles.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    " · " + (status?.let { statusWord(it) } ?: phaseText(state)),
                    color = status?.let { statusTint(it) } ?: phaseTint(state.phase),
                    fontSize = TextStyles.label,
                    maxLines = 1,
                )
                Text("⌄", color = Colors.muted, fontSize = TextStyles.label, modifier = Modifier.padding(start = 4.dp))
            }
        }
        if (state.phase == Phase.Live) {
            ControlPill(owning = state.owning, onTakeOver = onControl, onRelease = onControl)
        }
        HeaderButton("⋮", color = Colors.ink, size = 20.sp, spoken = stringResource(R.string.action_more), onClick = onMore)
    }
}

/** A dark tile with a prompt on it: the one mark the app shares with a desk terminal. */
@Composable
private fun TerminalTile(size: Dp = 38.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.24f)).background(Colors.badge),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            ">_",
            color = Colors.badgeInk,
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Whose pane this is. Filled while this phone holds it - tapping hands it back to the desktop and
 * stops taking it back - and outlined once it belongs to the desktop, so the way back is one tap.
 */
@Composable
private fun ControlPill(owning: Boolean, onTakeOver: () -> Unit, onRelease: () -> Unit) {
    val shape = RoundedCornerShape(99.dp)
    Box(Modifier.sizeIn(minHeight = 44.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(if (owning) R.string.action_release else R.string.action_take_over),
            color = if (owning) Colors.onAccent else Colors.accent,
            fontSize = TextStyles.label,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier
                .clip(shape)
                .then(if (owning) Modifier.background(Colors.accent) else Modifier)
                .border(1.dp, Colors.accent, shape)
                .clickable(role = Role.Button, onClick = if (owning) onRelease else onTakeOver)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * The panes of the shown tab, as the pager a phone already knows: one blue bar where you are, a dot
 * for each pane beside it. Tap one to go there; a horizontal swipe on the pane does the same.
 */
@Composable
private fun PanePager(tab: WindowInfo?, focused: BlockInfo?, onSelect: (BlockInfo) -> Unit) {
    val blocks = tab?.blocks.orEmpty()
    Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.Center) {
        if (blocks.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                blocks.forEach { block ->
                    val on = block.id == focused?.id
                    Box(
                        Modifier.size(24.dp).clip(RoundedCornerShape(99.dp))
                            .clickable(role = Role.Button) { if (!on) onSelect(block) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.height(6.dp).width(if (on) 22.dp else 6.dp).clip(RoundedCornerShape(99.dp))
                                .background(if (on) Colors.accent else Colors.line),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The tabs of the session, under the pane instead of over it: a phone is held at the bottom, and
 * the row that moves you between terminals is the one your thumb reaches. The shown tab is marked
 * by a rule under its name, so the row stays one line high however many tabs there are.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TabDock(
    view: SessionView,
    onSelect: (WindowInfo) -> Unit,
    onLongPress: (WindowInfo) -> Unit,
    onNew: () -> Unit,
    paneCount: Int,
    onNavigate: () -> Unit,
) {
    val list = rememberLazyListState()
    val motion = !rememberReducedMotion()
    val navigateLabel = stringResource(R.string.menu_navigate)
    val newTabLabel = stringResource(R.string.menu_new_tab)
    val active = view.activeWindow()
    val activeIndex = view.windows.indexOfFirst { it.id == active?.id }
    // Keep the tab in use in the middle of the row: a row of chips that always starts at the left
    // edge makes a session with ten tabs impossible to read.
    LaunchedEffect(activeIndex, view.windows.size) { list.centerOn(activeIndex, animate = motion) }
    Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
        ScrollRow(list, Modifier.weight(1f), fade = Colors.surface) {
            items(view.windows, key = { it.id }) { window ->
                TabChip(
                    view = view,
                    window = window,
                    on = window.id == active?.id,
                    onSelect = { onSelect(window) },
                    onLongPress = { onLongPress(window) },
                )
            }
        }
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(99.dp)).clickable(role = Role.Button, onClick = onNavigate)
                .semantics { contentDescription = navigateLabel },
            contentAlignment = Alignment.Center,
        ) {
            LayoutIcon(if (paneCount > 1) Colors.ink else Colors.muted)
        }
        Box(
            Modifier.padding(end = 8.dp).size(44.dp).clip(CircleShape).background(Colors.key)
                .clickable(role = Role.Button, onClick = onNew)
                .semantics { contentDescription = newTabLabel },
            contentAlignment = Alignment.Center,
        ) {
            Text("+", color = Colors.ink, fontSize = 24.sp, fontWeight = FontWeight.Normal)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabChip(
    view: SessionView,
    window: WindowInfo,
    on: Boolean,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(Modifier.fillMaxHeight().drawBehind {
        // The rule sits on the card's bottom edge, under the tab it belongs to.
        if (on) drawRect(Colors.accent, topLeft = Offset(0f, size.height - 3.dp.toPx()), size = Size(size.width, 3.dp.toPx()))
    }) {
        Row(
            Modifier.fillMaxHeight().combinedClickable(onClick = onSelect, onLongClick = onLongPress)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(Colors.badge),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    glyph(tabTitle(view, window)),
                    color = Colors.badgeInk,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                tabTitle(view, window),
                color = if (on) Colors.ink else Colors.muted,
                fontSize = 15.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
            if (window.blocks.size > 1) {
                Spacer(Modifier.width(5.dp))
                Text("${window.blocks.size}", color = if (on) Colors.accent else Colors.muted, fontSize = 13.sp)
            }
        }
    }
}

/**
 * The tile's one mark: the tab's number when its name carries one ("Tab 2", "标签 2"), else the
 * first letter of the program, so a row of tabs is told apart at a glance.
 */
private fun glyph(title: String): String {
    Regex("\\d+").findAll(title).lastOrNull()?.value?.let { return it.takeLast(2) }
    return title.trim().firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: "›"
}

/**
 * A row of chips that fades out where it continues, so a chip cut by the edge reads as "there is
 * more" instead of as a broken layout. [fade] is the surface the row sits on.
 */
@Composable
internal fun ScrollRow(
    state: LazyListState,
    modifier: Modifier = Modifier,
    fade: Color = Colors.bg,
    content: LazyListScope.() -> Unit,
) {
    val moreBefore by remember { derivedStateOf { state.canScrollBackward } }
    val moreAfter by remember { derivedStateOf { state.canScrollForward } }
    Box(modifier) {
        LazyRow(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 6.dp, end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (moreBefore) {
            Box(
                Modifier.align(Alignment.CenterStart).width(18.dp).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(fade, Colors.clear))),
            )
        }
        if (moreAfter) {
            Box(
                Modifier.align(Alignment.CenterEnd).width(18.dp).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Colors.clear, fade))),
            )
        }
    }
}

/** Scrolls [index] to the middle of the row when it is already on screen, else to the left edge. */
internal suspend fun LazyListState.centerOn(index: Int, animate: Boolean) {
    if (index < 0) return
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    val offset = if (item != null) item.offset - (info.viewportSize.width - item.size) / 2 else 0
    if (animate) animateScrollToItem(index, offset) else scrollToItem(index, offset)
}

/** Rex names an unlabeled window "Window " plus an id tail; the tab's position reads better. */
@Composable
internal fun tabTitle(view: SessionView, window: WindowInfo): String {
    val number = view.windows.indexOf(window) + 1
    return if (GENERATED_WINDOW_LABEL.matches(window.label)) stringResource(R.string.tab_number, number) else window.label
}

/** A pane left at the default "shell" label is named after the program running in it. */
internal fun paneTitle(block: BlockInfo, status: ProgramRecord?): String {
    val app = status?.app?.takeIf { it.isNotBlank() }
    return if (block.label == "shell" && app != null) app else block.label
}

/** What the program in the pane is doing, in the app's words; null when Rex has not said. */
@Composable
internal fun statusWord(status: ProgramRecord): String? = when (status.state) {
    "working" -> stringResource(R.string.state_running)
    "blocked" -> when (status.kind) {
        "permission" -> stringResource(R.string.state_waiting_approval)
        "question" -> stringResource(R.string.state_waiting_answer)
        "auth" -> stringResource(R.string.state_waiting_login)
        else -> stringResource(R.string.state_waiting_you)
    }
    "done" -> stringResource(R.string.state_done)
    "error" -> stringResource(R.string.state_failed)
    "idle" -> stringResource(R.string.state_idle)
    else -> null
}

/** Running is green, waiting on a person is amber, failure is red, the rest is quiet grey. */
internal fun statusTint(status: ProgramRecord): Color = when {
    status.state == "working" -> Colors.ok
    status.state == "blocked" -> Colors.amber
    status.state == "error" -> Colors.danger
    else -> Colors.muted
}

/** The connection's own state, for when no program has reported one. */
internal fun phaseTint(phase: Phase): Color = when (phase) {
    Phase.Live -> Colors.ok
    Phase.Failed -> Colors.danger
    Phase.Connecting, Phase.Reconnecting -> Colors.amber
    else -> Colors.muted
}

/**
 * A path as a phone can show it: the home directory as `~`, and a long path cut to its last
 * segments, because the directory being worked in is the end of it.
 */
internal fun shortPath(path: String): String {
    var p = path.trim().trimEnd('/')
    if (p.isEmpty()) return path
    // Rex reports a directory as a file URI, sometimes with the machine as its authority
    // (`file://host/Users/me`). The header wants the path inside it, not the URI.
    p = p.removePrefix("file:")
    p = when {
        p.startsWith("///") -> "/" + p.trimStart('/')
        p.startsWith("//") -> "/" + p.trimStart('/').substringAfter('/', "")
        p.startsWith("/") -> p
        else -> "/" + p.substringAfter('/', "")
    }
    if (p == "/") return "/"
    val home = Regex("^/(Users|home)/[^/]+").find(p)?.value
    val short = if (home != null) "~" + p.removePrefix(home) else p
    if (short.length <= 24) return short
    // The last directory is the one being worked in; the ones above it only cost width.
    val segments = short.split('/').filter { it.isNotEmpty() }
    return "…/" + segments.last()
}

/**
 * The navigator's mark: two panes side by side. Drawn rather than set, so it keeps its weight at
 * every font scale.
 */
@Composable
private fun LayoutIcon(tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val gap = 3.dp.toPx()
        val width = (size.width - gap) / 2
        val top = 3.dp.toPx()
        val height = size.height - 6.dp.toPx()
        val radius = CornerRadius(2.dp.toPx())
        drawRoundRect(tint, Offset(0f, top), Size(width, height), radius)
        drawRoundRect(tint, Offset(width + gap, top), Size(width, height), radius)
    }
}

/** How long a failed action stays on the terminal screen. */
internal const val NOTICE_MS = 4_000L

internal val GENERATED_WINDOW_LABEL = Regex("Window [0-9a-f]{6,}")
