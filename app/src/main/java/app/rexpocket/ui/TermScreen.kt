package app.rexpocket.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rexpocket.core.BlockInfo
import app.rexpocket.core.ComposerDrafts
import app.rexpocket.core.Phase
import app.rexpocket.core.PocketSession
import kotlinx.coroutines.flow.Flow
import app.rexpocket.core.TerminalFeed
import app.rexpocket.core.toProblem
import app.rexpocket.core.PocketState
import app.rexpocket.core.ProgramRecord
import app.rexpocket.core.SessionView
import app.rexpocket.core.WindowInfo
import app.rexpocket.R
import io.github.sagernet.libghostty.GhosttyTerminalSession
import kotlinx.coroutines.delay

// The terminal: header, tab bar, pane strip, the pane itself, and its menus.

/**
 * The line under the tabs. A connection that is down stays there with a way to retry; an action
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
        Modifier.fillMaxWidth().clickable(onClick = action).padding(horizontal = 12.dp, vertical = 4.dp),
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
    data class TabActions(val windowId: String) : Sheet
    data class PaneActions(val blockId: String) : Sheet
}

@Composable
internal fun TermScreen(
    state: PocketState,
    composerDrafts: ComposerDrafts,
    fontScale: Float,
    onFont: (Float) -> Unit,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    watchPane: (String) -> Flow<TerminalFeed>,
    onRetry: () -> Unit,
    onDismissNotice: (Long) -> Unit,
    onAct: (suspend PocketSession.() -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var sheet by remember { mutableStateOf<Sheet?>(null) }
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
    // Each pane keeps its own draft, and drafts outlive the screen: the session list and back keeps them.
    val drafts = composerDrafts
    val pane = focused?.id.orEmpty()
    var draft by remember(pane) { mutableStateOf(drafts.draft(pane)) }

    // An open sheet handles Back itself and closes.
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().background(Colors.bg).statusBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderButton("‹", spoken = stringResource(R.string.action_back)) { onBack() }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(view?.label ?: phaseText(state), color = Colors.ink, fontSize = TextStyles.body, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    subtitleParts(state, status).forEach { part ->
                        Text(part, color = Colors.muted, fontSize = TextStyles.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (status?.state == "working" || status?.state == "blocked") {
                HeaderButton(stringResource(R.string.header_stop), Colors.amber) { onAct { sendKey("key_c", mods = "ctrl", codepoint = 99) } }
            }
            HeaderButton(stringResource(R.string.header_sessions)) { sheet = Sheet.Sessions }
            HeaderButton("···", spoken = stringResource(R.string.action_more)) { sheet = Sheet.Actions }
        }
        if (view != null) {
            TabBar(
                view = view,
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
        TermBanner(state, onRetry = onRetry, onDismiss = onDismissNotice)
        // One emulator per shown pane, fed only with that pane's stream, so switching never mixes
        // two panes' scrollback.
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
                onError = { onAct { note(it.toProblem()) } },
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp),
            )
        }
        if (dpad) Dpad(onAct)
        Composer(
            draft = draft,
            onDraft = {
                draft = it
                drafts.setDraft(pane, it)
            },
            onHistory = { older ->
                (if (older) drafts.older(pane) else drafts.newer(pane))?.let { draft = it }
            },
            onSend = {
                val text = drafts.send(pane)
                draft = ""
                // An empty composer sends the Enter key alone, which is how a full-screen program
                // such as pi confirms the item its menu has selected.
                onAct { submitLine(text) }
            },
            onSendEnter = { onAct { sendKey("enter") } },
            onChord = if (ctrl || ctrlLock) {
                { key ->
                    onAct { sendKey(key.code, mods = "ctrl", codepoint = key.codepoint) }
                    if (!ctrlLock) ctrl = false
                }
            } else {
                null
            },
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
            if (view != null) {
                Group(stringResource(R.string.level_session)) {
                    SheetItem(stringResource(R.string.menu_rename_session)) {
                        rename = Rename(Level.Session, view.sessionId, view.label)
                    }
                    SheetItem(stringResource(R.string.menu_kill_session), Colors.danger) {
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
 * Rex's windows are the tabs of a session. Every tab is a chip here; the shown one is filled.
 * A tab holding several panes carries their count, and its dot is the most urgent pane state.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TabBar(
    view: SessionView,
    onSelect: (WindowInfo) -> Unit,
    onLongPress: (WindowInfo) -> Unit,
    onNew: () -> Unit,
) {
    val list = rememberLazyListState()
    val motion = !rememberReducedMotion()
    val activeIndex = view.windows.indexOfFirst { it.id == view.activeWindow()?.id }
    // Keep the tab in use in the middle of the row: a row of chips that always starts at the left
    // edge makes a session with ten tabs impossible to read.
    LaunchedEffect(activeIndex, view.windows.size) { list.centerOn(activeIndex, animate = motion) }
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScrollRow(list, Modifier.weight(1f)) {
            items(view.windows, key = { it.id }) { window ->
                val on = window.id == view.activeWindow()?.id
                Row(
                    Modifier.clip(RoundedCornerShape(Radius.chip))
                        .background(if (on) Colors.accent else Colors.surface)
                        .combinedClickable(onClick = { onSelect(window) }, onLongClick = { onLongPress(window) })
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The header subtitle already says what the program is doing; a dot here would
                    // only repeat it, in a shape nobody can read.
                    Text(
                        tabTitle(view, window),
                        color = if (on) Colors.bg else Colors.ink,
                        fontSize = TextStyles.label,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                    if (window.blocks.size > 1) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${window.blocks.size}",
                            color = if (on) Colors.accent else Colors.muted,
                            fontSize = TextStyles.label,
                            modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(if (on) Colors.bg else Colors.line)
                                .padding(horizontal = 6.dp),
                        )
                    }
                }
            }
        }
        // The add chip stays outside the scroll, so it is in the same place however many tabs there are.
        AddChip(stringResource(R.string.menu_new_tab), RoundedCornerShape(Radius.chip), onNew)
    }
}

/**
 * The panes of the shown tab. The phone draws one pane at a time, so a split on the desktop
 * becomes a row of numbered chips here, and only the chosen one is on screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PaneStrip(
    tab: WindowInfo,
    status: Map<String, ProgramRecord>,
    onSelect: (BlockInfo) -> Unit,
    onLongPress: (BlockInfo) -> Unit,
    onNew: () -> Unit,
) {
    val list = rememberLazyListState()
    val motion = !rememberReducedMotion()
    val current = tab.focusedBlock()?.id
    val index = tab.focusedIndex()
    LaunchedEffect(tab.id, index, tab.blocks.size) { list.centerOn(index, animate = motion) }
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
                        .border(1.dp, if (on) Colors.accent else Colors.line, RoundedCornerShape(99.dp))
                        .combinedClickable(onClick = { onSelect(block) }, onLongClick = { onLongPress(block) })
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${tab.blocks.indexOf(block) + 1}",
                        color = if (on) Colors.accent else Colors.muted,
                        fontSize = TextStyles.label,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        paneTitle(block, record),
                        color = if (on) Colors.accent else Colors.ink,
                        fontSize = TextStyles.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp),
                    )
                }
            }
        }
        AddChip(stringResource(R.string.menu_split_right), RoundedCornerShape(99.dp), onNew)
    }
}

/**
 * The `+` at the end of the tab bar and the pane strip. The visible chip stays small so the row
 * stays thin; the area that takes the tap is 48dp, as Android asks of every target.
 */
@Composable
internal fun AddChip(spoken: String, shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit) {
    Box(
        Modifier.padding(end = 4.dp).sizeIn(minWidth = MIN_TOUCH, minHeight = MIN_TOUCH)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "+",
            color = Colors.accent,
            fontSize = TextStyles.body,
            modifier = Modifier.clip(shape).background(Colors.surface).padding(horizontal = 12.dp, vertical = 2.dp),
        )
    }
}

/**
 * A row of chips that fades out where it continues, so a chip cut by the edge reads as "there is
 * more" instead of as a broken layout. The add chip next to it stays put.
 */
@Composable
internal fun ScrollRow(state: LazyListState, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
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
                    .background(Brush.horizontalGradient(listOf(Colors.bg, Colors.clear))),
            )
        }
        if (moreAfter) {
            Box(
                Modifier.align(Alignment.CenterEnd).width(18.dp).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Colors.clear, Colors.bg))),
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

/** How long a failed action stays on the terminal screen. */
internal const val NOTICE_MS = 4_000L

internal val GENERATED_WINDOW_LABEL = Regex("Window [0-9a-f]{6,}")

/** A pane left at the default "shell" label is named after the program running in it. */
internal fun paneTitle(block: BlockInfo, status: ProgramRecord?): String {
    val app = status?.app?.takeIf { it.isNotBlank() }
    return if (block.label == "shell" && app != null) app else block.label
}

@Composable
internal fun subtitleParts(state: PocketState, status: ProgramRecord?): List<String> {
    // The tab bar and pane strip already name the tab and pane; the subtitle says what runs there.
    val parts = listOfNotNull(
        status?.let { statusLabel(it) },
        state.pwd?.let { path -> path.substringAfterLast('/').ifBlank { path } },
    )
    return parts.ifEmpty { listOf(phaseText(state)) }
}

@Composable
internal fun statusLabel(status: ProgramRecord): String {
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
