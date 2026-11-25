package me.leaver.rexpocket.ui

import me.leaver.rexpocket.core.tabDetails
import android.os.Build
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import me.leaver.rexpocket.terminal.TerminalSession
import kotlinx.coroutines.delay
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.BlockInfo
import me.leaver.rexpocket.core.ClosedPane
import me.leaver.rexpocket.core.BarKey
import me.leaver.rexpocket.core.CtrlCKey
import me.leaver.rexpocket.core.KeyArrangement
import me.leaver.rexpocket.core.DeskKey
import me.leaver.rexpocket.core.GestureSettings
import me.leaver.rexpocket.core.KeyModifiers
import me.leaver.rexpocket.core.metaState
import me.leaver.rexpocket.core.KeyPress
import me.leaver.rexpocket.core.keyPressFor
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.PocketSession
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.PaletteEntry
import me.leaver.rexpocket.core.AppShortcut
import me.leaver.rexpocket.core.PaneProgress
import me.leaver.rexpocket.core.repeats
import me.leaver.rexpocket.core.tabProgress
import me.leaver.rexpocket.core.QuickReplies
import me.leaver.rexpocket.core.TabTextSizes
import me.leaver.rexpocket.core.DirListing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import me.leaver.rexpocket.core.TextScale
import me.leaver.rexpocket.core.ProgramMark
import me.leaver.rexpocket.core.programMark
import me.leaver.rexpocket.core.ProgramRecord
import me.leaver.rexpocket.core.SessionView
import me.leaver.rexpocket.core.Holder
import me.leaver.rexpocket.core.TerminalFeed
import me.leaver.rexpocket.core.UploadPhase
import me.leaver.rexpocket.core.WindowInfo
import me.leaver.rexpocket.core.toProblem

// The terminal, built like the reference: a floating top bar that says what this is and who holds
// it, the pane edge to edge on its own paper, and a floating dock under the thumb.

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
    data object Palette : Sheet

    /** What a keyboard on the desk can do here: the screen's own keys. */
    data object Shortcuts : Sheet

    /** The tab's Status details: what Rex reports of its programs and what the phone made of it. */
    data class StatusDetails(val windowId: String) : Sheet

    /** The prompt composer, for the pane that was on screen when it opened. */
    data class Composer(val sessionId: String, val blockId: String, val title: String) : Sheet

    /** Insert path: the host's folders, from where the pane's program is. */
    data object InsertPath : Sheet

    /** Settings › Quick replies, reached from the key bar's panel and the composer. */
    data object QuickReplies : Sheet
}

/** Clear and Reset terminal, which ask first: what they erase does not come back. */
internal enum class Wipe { Clear, Reset }

@Composable
internal fun TermScreen(
    state: PocketState,
    /** The text size new tabs start at, and every tab follows until its own is changed. */
    fontScale: Float,
    terminalTypeface: android.graphics.Typeface,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    watchPane: (String) -> Flow<TerminalFeed>,
    onRetry: () -> Unit,
    onDismissNotice: (Long) -> Unit,
    onAct: (suspend PocketSession.() -> Unit) -> Unit,
    upload: UploadStatus?,
    onAttach: (android.net.Uri) -> Unit,
    onCancelUpload: () -> Unit,
    onDismissUpload: () -> Unit,
    onRetryUpload: () -> Unit,
    /** True when the key bar is kept even with a keyboard on the desk. */
    keyBarWithKeyboard: Boolean = false,
    /** The key bar's keys in the person's order, and which show. */
    keyArrangement: KeyArrangement = KeyArrangement(),
    keyBarCtrlC: CtrlCKey = CtrlCKey.Tap,
    /** What a tap, a double tap and a long press on the pane do; full screen is in the menu and the pill either way. */
    gestures: GestureSettings = GestureSettings(),
    /** True when this pane shares the window with the list, which is then the way back. */
    embedded: Boolean = false,
    /** Shows a tab of this host: of the session on screen, or of another one, which is opened on it. */
    onOpenTab: (sessionId: String, windowId: String) -> Unit = { sessionId, _ -> onOpenSession(sessionId) },
    /** What the program in a pane copies with OSC 52; see [PocketSession.copies]. */
    paneCopies: (String) -> Flow<String> = { emptyFlow() },
    /** The person's quick replies: the key bar's Quick replies key, the palette and the composer. */
    quickReplies: List<String> = emptyList(),
    /** The composer's drafts, by host and pane; [draftHost] is the host on screen. */
    drafts: PromptDrafts = remember { PromptDrafts() },
    draftHost: String? = null,
    /** A pane's seen mark on this phone, for Status details. */
    seenMark: (blockId: String) -> String? = { null },
    /** Each tab's own text size, which a pinch, the Text size row and the palette change. */
    textSizes: TabTextSizeState = remember { TabTextSizeState() },
    /** True until the one-time "Pinch to change text size" hint has been seen. */
    pinchHint: Boolean = false,
    onPinchHintSeen: () -> Unit = {},
    /** Changes the quick replies, for Edit quick replies on the key bar's panel and in the composer; null leaves it out. */
    onQuickReplies: ((QuickReplies) -> Unit)? = null,
    /** Whether tab (window) [windowId] of this host is muted, and muting or unmuting it: the tab's ⋮ menu. */
    tabMuted: (windowId: String) -> Boolean = { false },
    onMuteTab: (windowId: String, muted: Boolean) -> Unit = { _, _ -> },
    /** The host's own Rex actions for the palette, read again each time it opens; and running one. */
    rexActions: List<me.leaver.rexpocket.core.RexAction> = emptyList(),
    onPaletteOpen: () -> Unit = {},
    onRexAction: (me.leaver.rexpocket.core.RexAction) -> Unit = {},
) {
    val context = LocalContext.current
    val ime = LocalSoftwareKeyboardController.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onAttach(uri)
    }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    // A modifier taken off the bar while held would stay held with no key to let it go.
    val shift = keyArrangement.isShown(BarKey.Shift)
    val alt = keyArrangement.isShown(BarKey.Alt)
    var mods by remember(shift, alt) { mutableStateOf(KeyModifiers()) }
    var link by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf<Rename?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var wipe by remember { mutableStateOf<Wipe?>(null) }
    var termSession by remember { mutableStateOf<TerminalSession?>(null) }
    // The pane's editor: what the keyboard key raises, and what Back and the session list drop.
    var keyboard by remember { mutableStateOf<TerminalImeView?>(null) }
    val view = state.view
    // The pane on screen was closed elsewhere. The view has moved on to what Rex shows instead, but
    // that is held back behind a note - no pane, no keys, no tab - until the person chooses it.
    val closed = state.closed?.takeIf { view != null && it.sessionId == view.sessionId }
    val tab = if (closed != null) null else view?.activeWindow()
    val focused = if (closed != null) null else view?.focusedBlock()
    val status = focused?.let { state.blockStatus[it.id] }
    val panes = tab?.blocks.orEmpty()

    // The tab's own text size, or the default; see TextSize.kt. A change is made to this tab only.
    val sizeKey = if (view != null && tab != null) TabTextSizes.key(draftHost, view.sessionId, tab.id) else null
    val tabScale = sizeKey?.let { textSizes.sizes.of(it, fontScale) } ?: fontScale
    fun setTabScale(next: Float) {
        val key = sizeKey ?: return
        textSizes.sizes = textSizes.sizes.with(key, next, fontScale)
        // Whoever changes the size has found it: the hint has done its job.
        if (pinchHint) onPinchHintSeen()
    }
    fun resetTabScale() {
        val key = sizeKey ?: return
        textSizes.sizes = textSizes.sizes.reset(key)
    }
    // The grid the pane fits at that size, for the Text size row.
    var grid by remember(focused?.id) { mutableStateOf<Pair<Int, Int>?>(null) }

    // A program's OSC 52 copy goes on the phone's clipboard, from the pane on screen only and only
    // while the app is in front: a copy is the program speaking for the person at the keyboard.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(focused?.id) {
        val pane = focused?.id ?: return@LaunchedEffect
        paneCopies(pane).collect { text ->
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@collect
            clipboardSet(context, text)
            // Android 13 and later say so themselves.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(context, context.getString(R.string.toast_program_copied))
        }
    }

    /**
     * A key or a paste that goes to the program through Rex rather than through the pane's own
     * terminal: the pane follows it to the newest output, as typing does, so whoever had scrolled
     * back sees what it did.
     */
    fun followNewest() {
        termSession?.scrollToBottom()
    }

    /** Puts what the clipboard holds into the program, where a key on the bar or the menu asks for it. */
    fun pasteClipboard() {
        val text = clipboard(context)
        if (text.isNullOrEmpty()) {
            toast(context, context.getString(R.string.toast_clipboard_empty))
        } else {
            followNewest()
            onAct { paste(text) }
        }
    }

    /** The composer for the pane on screen; it keeps that pane while it is open. */
    fun openComposer() {
        val v = view ?: return
        val pane = focused ?: return
        sheet = Sheet.Composer(v.sessionId, pane.id, paneTitle(pane, state.blockStatus[pane.id], state.programs[pane.id]))
    }

    /** The pane's whole output, screen and scrollback, on the clipboard: the palette's, the menu's and the selection's Copy output. */
    fun copyOutput() {
        onAct {
            val out = paneText() ?: return@onAct
            when {
                out.text.isBlank() -> toast(context, context.getString(R.string.toast_output_empty))
                else -> {
                    clipboardSet(context, out.text)
                    // Android 13 and later say a copy was made themselves.
                    if (out.cut) {
                        toast(context, context.getString(R.string.toast_output_copied_cut))
                    } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        toast(context, context.getString(R.string.toast_output_copied))
                    }
                }
            }
        }
    }

    fun shareOutput() {
        onAct {
            val out = paneText() ?: return@onAct
            if (out.text.isBlank()) toast(context, context.getString(R.string.toast_output_empty)) else shareText(context, out.text)
        }
    }

    /**
     * Starts a selection on the pane on screen, its last line selected with handles to drag: the
     * way to select when a long press is set to do something else.
     */
    fun selectText() {
        if (keyboard?.pane?.startSelection() != true) toast(context, context.getString(R.string.select_text_nothing))
    }

    /** A folder for Insert path, read through the session; null when it could not be in time. */
    suspend fun listFolder(path: String): DirListing? {
        val answer = CompletableDeferred<DirListing?>()
        onAct {
            try {
                answer.complete(listDir(path))
            } finally {
                answer.complete(null)
            }
        }
        return withTimeoutOrNull(LIST_DIR_WAIT_MS) { answer.await() }
    }

    /** The path Insert path picked, typed into the pane as a paste: nothing runs until Return. */
    fun insertPath(word: String) {
        followNewest()
        onAct { paste(word) }
    }

    /** A quick reply from the key bar or the palette: pasted into the pane on screen, then Return. */
    fun sendQuickReply(text: String) {
        val v = view ?: return
        val pane = focused ?: return
        followNewest()
        onAct { replyTo(v.sessionId, pane.id, text) }
    }

    /** Sends a key with the modifiers the key row holds; the ones on for one key are then used up. */
    fun press(key: KeyPress, own: String = "") {
        val e = mods.event(key, own)
        followNewest()
        onAct { sendKey(e.code, mods = e.mods, text = e.text, codepoint = e.codepoint, consumedMods = e.consumedMods) }
        mods = mods.consume()
    }

    /**
     * Home, Page Up, F5 and the rest, as a keyboard on the desk sends them: the pane's terminal
     * encodes the key in the modes the program set, with the modifiers the key row holds, and the
     * bytes go the way a desk keyboard's do.
     */
    fun pressDesk(key: DeskKey) {
        val terminal = termSession ?: return
        deskKeyBytes(terminal, key, mods)?.let(terminal::send)
        mods = mods.consume()
    }

    /**
     * A character from the phone keyboard while a modifier is on becomes that key with the modifier,
     * so C-r or Alt-b can be typed with the keyboard's letters. True when it was taken here.
     */
    fun typed(bytes: ByteArray, flushFirst: () -> Unit): Boolean {
        if (!mods.any) return false
        val key = keyPressFor(String(bytes, Charsets.UTF_8)) ?: run {
            // An IME commit is still the next input, even when it is not a single key.
            mods = mods.afterTextCommit(String(bytes, Charsets.UTF_8))
            return false
        }
        flushFirst()
        press(key)
        return true
    }

    // Typing goes straight to the terminal, as it does on the desktop: the keyboard key raises the
    // IME for the pane, and the key row stays above it. There is no composer between them.
    fun toggleKeyboard() {
        val target = keyboard ?: return
        val ime = context.getSystemService(InputMethodManager::class.java)
        // Whether the keyboard is up, not whether the pane has focus: the IME can be showing while
        // focus sits in its own window, and then the same key must put it away.
        val showing = ViewCompat.getRootWindowInsets(target)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (showing) {
            ime?.hideSoftInputFromWindow(target.windowToken, 0)
            target.clearFocus()
        } else {
            target.showIme()
        }
    }

    /** The holder chip's one tap, which the menu repeats: hand the pane back, or take it over. */
    fun toggleHolder() {
        if (state.holder == Holder.Phone) onAct { releasePane() } else onAct { takeOver() }
    }

    val shownTabTitle = if (view != null && tab != null) tabTitle(view, tab, state) else ""

    // Find in the pane's scrollback: a bar over the pane, its matches drawn on it.
    var findOpen by rememberSaveable { mutableStateOf(false) }
    var findAgain by remember { mutableIntStateOf(0) }
    val find = remember { FindModel() }
    FindEffects(find, findOpen && focused != null, termSession)

    /** Opens Find, or puts the keys in its field again; a selection of one line is what it looks for. */
    fun openFind() {
        if (focused == null) return
        val picked = termSession?.selectionText().orEmpty()
        if (picked.isNotBlank() && '\n' !in picked) find.query = androidx.compose.ui.text.input.TextFieldValue(picked.trim())
        findOpen = true
        findAgain++
    }

    fun closeFind() {
        findOpen = false
        // The keys of a desk keyboard go back to the pane.
        keyboard?.pane?.takeIf { it.hardwareKeys }?.requestFocus()
    }

    /** Next or previous tab of the session on screen, round the ends. */
    fun stepTab(forward: Boolean) {
        val windows = view?.windows ?: return
        val at = windows.indexOfFirst { it.id == tab?.id }
        if (windows.size < 2 || at < 0) return
        val next = windows[(at + (if (forward) 1 else -1) + windows.size) % windows.size]
        onAct { focusWindow(next.id) }
    }

    /** What the screen does for one of its own keys on a desk keyboard (terminal-controls.md, "A desk keyboard"). */
    fun runShortcut(shortcut: AppShortcut) {
        when (shortcut) {
            AppShortcut.Find -> openFind()
            AppShortcut.Copy -> {
                val text = termSession?.selectionText().orEmpty()
                if (text.isEmpty()) {
                    toast(context, context.getString(R.string.toast_nothing_selected))
                } else {
                    clipboardSet(context, text)
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(context, context.getString(R.string.toast_copied))
                }
            }
            AppShortcut.Paste -> if (focused != null) pasteClipboard()
            AppShortcut.NewTab -> if (view != null) onAct { newWindow() }
            AppShortcut.NextTab -> stepTab(forward = true)
            AppShortcut.PreviousTab -> stepTab(forward = false)
            AppShortcut.CloseTab -> if (view != null && tab != null) confirm = Confirm(Level.Tab, tab.id, shownTabTitle)
            AppShortcut.FontBigger -> setTabScale(TextScale.bigger(tabScale))
            AppShortcut.FontSmaller -> setTabScale(TextScale.smaller(tabScale))
            AppShortcut.FontReset -> resetTabScale()
            AppShortcut.Palette -> sheet = Sheet.Palette
            AppShortcut.Home -> onBack()
            AppShortcut.Help -> sheet = Sheet.Shortcuts
        }
    }

    /** A key from a desk keyboard, offered before the program gets it: true when it was the screen's. */
    fun onShortcutKey(event: android.view.KeyEvent, fromFind: Boolean = false): Boolean {
        if (event.action != android.view.KeyEvent.ACTION_DOWN) return false
        val shortcut = shortcutOf(event) ?: return false
        // Copy and paste in Find's field are the field's own.
        if (fromFind && (shortcut == AppShortcut.Copy || shortcut == AppShortcut.Paste)) return false
        // A held key does not open tab after tab; only the font steps on.
        if (event.repeatCount > 0 && !shortcut.repeats) return true
        runShortcut(shortcut)
        return true
    }
    TerminalShortcutsShown()

    /** Who holds the pane shows while there is one on screen to hold: the chip, and the menu's row. */
    val showHolder = holderShown(state, tab)
    val imeUp = imeShown()

    // Full screen is this screen's own: it survives a rotation, and is gone once the terminal is left.
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    val toggleFullScreen = { fullScreen = TermChrome(fullScreen).toggled().fullScreen }
    val short = LocalConfiguration.current.screenHeightDp < SHORT_WINDOW_DP
    val chrome = TermChrome(fullScreen, short = short, imeUp = imeShown())
    SystemBars(shown = chrome.systemBars)

    // An open sheet handles Back itself and closes. In full screen Back first brings the bars back:
    // the later handler is the one asked first.
    BackHandler(onBack = onBack)
    BackHandler(enabled = chrome.fullScreen) { TermChrome(fullScreen).back()?.let { fullScreen = it.fullScreen } }

    // The keyboard belongs to the pane that is on screen: leaving the terminal drops it, so the
    // session list does not come back with a keyboard over it.
    DisposableEffect(Unit) {
        onDispose {
            keyboard?.clearFocus()
            ime?.hide()
        }
    }

    // One paper for the whole screen, the terminal's: the top bar and the dock float on it, as they
    // do in the reference, instead of sitting on bands of a second grey.
    //
    // The keyboard takes no room from the terminal. The pane keeps the grid it has and slides up by
    // what the keyboard covers - the bottom of the terminal, the rows being typed into, stays where
    // it was, unless the cursor is too near the top for that - while the dock and the rest of the
    // bottom chrome rise over it. A program is therefore
    // never resized by the screen keyboard opening, so it never redraws or reflows for it.
    val hardwareKeys = rememberHardwareKeyboard()
    // Rex keeps a session whose last tab closed, with no windows at all (seen on 0.1.0-preview.22):
    // there is no pane to type into, so the screen says so and offers a new tab, without keys.
    val empty = closed == null && view != null && view.windows.isEmpty()
    val dock = chrome.keyBar(keyBarShown(hardwareKeys, keyBarWithKeyboard)) && closed == null && !empty
    var bottomPx by remember { mutableIntStateOf(0) }
    var panePx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // Where the pane's cursor is, so the slide never takes its row out of view; a new pane starts
    // with none until it has drawn.
    var cursorTopPx by remember(focused?.id) { mutableStateOf<Int?>(null) }
    // And where what it has drawn ends, so a short screen is moved only as far as it needs.
    var drawnBottomPx by remember(focused?.id) { mutableStateOf<Int?>(null) }
    val slide = imeShift(imeCover(), panePx, cursorTopPx, drawnBottomPx)

    // In full screen the phone's bars are hidden, so the pane reaches the edges; a swipe brings them
    // over it for a moment without taking room. A camera cutout keeps the text out from under it at
    // the sides, where it would hide the start of every line; at the top of a phone held upright it
    // would cost a whole band of rows for a punch hole.
    //
    // The room for the bars follows full screen itself, not the bars' insets as last heard: Compose
    // drops the insets sent while a bar animation is being prepared and takes them at its end only
    // when it had a duration, so with animations off, or a bar that goes without one, the old insets
    // stayed - a band at the bottom - until the keyboard sent new ones. The bars' sizes while hidden
    // are known all along, so leaving full screen makes room for them at once too.
    val cutout = if (chrome.fullScreen) WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal) else WindowInsets.displayCutout
    val bars = if (chrome.fullScreen) Modifier else Modifier.windowInsetsPadding(systemBarsRoom())
    Box(Modifier.fillMaxSize().background(if (chrome.fullScreen) Colors.pane else Colors.terminal).then(bars).windowInsetsPadding(cutout)) {
        Column(Modifier.fillMaxSize().padding(bottom = with(density) { bottomPx.toDp() })) {
            if (chrome.topBar) {
                SessionTopBar(
                    state = state,
                    view = view,
                    tab = tab,
                    status = status,
                    onBack = onBack,
                    showBack = !embedded,
                    onNavigate = { if (view != null) sheet = Sheet.Navigator },
                    onHolder = ::toggleHolder,
                    onMore = { sheet = Sheet.Actions },
                    panes = if (chrome.panesInTopBar && panes.size > 1) {
                        {
                            PaneBar(
                                tab = tab,
                                focused = focused,
                                status = state.blockStatus,
                                busyPanes = state.busy,
                                programs = state.programs,
                                onSelect = { block -> onAct { focusBlock(block.id) } },
                                modifier = Modifier.widthIn(max = 420.dp),
                                progress = state.progress,
                            )
                        }
                    } else {
                        null
                    },
                    progress = tab?.let { tabProgress(it, state.progress) },
                )
            } else if (chrome.cornerMenu) {
                // The bar steps aside for the key bar, but its room is kept: the pane keeps its
                // grid, so the program is not resized each time the keyboard comes and goes.
                Spacer(Modifier.height(TOP_BAR_HEIGHT))
            }
            TermBanner(state, onRetry = onRetry, onDismiss = onDismissNotice)
            // The pane sits on its own paper: white in the day, the page at night, edge to edge, the
            // way a terminal fills the screen. A terminal on it has the terminal's colours, which
            // Settings › Appearance can set apart from the app's; the notes in its place are the app's.
            val shown = focused?.id
            val paper = if (closed != null && view != null || empty) Colors.terminal else Colors.pane
            Box(Modifier.weight(1f).fillMaxWidth().background(paper).clipToBounds().testTag(PANE_TAG)) {
                if (closed != null && view != null) {
                    ClosedPaneNote(
                        closed = closed,
                        next = view.activeWindow()?.let { tabTitle(view, it, state) },
                        onShowNext = { onAct { leaveClosedPane() } },
                        onNewTab = { onAct { newWindow() } },
                        onSessions = onBack.takeIf { !embedded },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (empty) {
                    EmptySessionNote(
                        onNewTab = { onAct { newWindow() } },
                        onSessions = onBack.takeIf { !embedded },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else key(shown) {
                    TerminalPane(
                        blockId = shown.orEmpty(),
                        feeds = watchPane,
                        fontScale = tabScale,
                        terminalTypeface = terminalTypeface,
                        onFont = ::setTabScale,
                        onGrid = { columns, rows -> grid = columns to rows },
                        onCopyOutput = ::copyOutput,
                        onInput = { bytes -> onAct { writeBytes(bytes) } },
                        onTyped = ::typed,
                        onLink = { link = it },
                        onPaste = { pasteClipboard() },
                        onShare = { shareText(context, it) },
                        onResize = { cols, rows, cw, ch -> onAct { setSize(cols, rows, cw, ch) } },
                        onSession = { termSession = it },
                        onKeyboard = { keyboard = it },
                        onCursorTop = { cursorTopPx = it },
                        onDrawnBottom = { drawnBottomPx = it },
                        hardwareKeyboard = hardwareKeys,
                        // A horizontal swipe on the pane moves to the pane beside it, as the dots
                        // under it say there is one.
                        onSwipe = { forward ->
                            if (panes.size > 1) {
                                val index = panes.indexOfFirst { it.id == focused?.id }
                                val next = (index + if (forward) 1 else -1).coerceIn(0, panes.lastIndex)
                                if (next != index) onAct { focusBlock(panes[next].id) }
                            }
                        },
                        gestures = gestures,
                        fullScreen = chrome.fullScreen,
                        onFullScreen = toggleFullScreen,
                        onError = { onAct { note(it.toProblem()) } },
                        // The screen's own keys: Ctrl+Shift+P for the palette (Cmd+Shift+P on a Mac
                        // keyboard), Ctrl+Shift+F for Find and the rest of the shortcut table.
                        onShortcut = { event -> onShortcutKey(event) },
                        modifier = Modifier.fillMaxSize()
                            // Moved, not resized: see imeShift. An offset keeps the pane's measured size
                            // - and so the grid the program was given - exactly as it was.
                            .offset { IntOffset(0, -slide.roundToInt()) }
                            .padding(horizontal = 3.dp, vertical = 2.dp)
                            .onSizeChanged { panePx = it.height },
                    )
                }
                // Find, over the pane's top rows rather than taking a row from it: the program keeps
                // its size while the person looks through what it printed.
                if (findOpen && focused != null) {
                    key(findAgain) {
                        FindBar(
                            find,
                            onClose = ::closeFind,
                            onShortcut = { onShortcutKey(it, fromFind = true) },
                            modifier = Modifier.align(Alignment.TopCenter).padding(end = if (chrome.fullScreen) 44.dp else 0.dp),
                        )
                    }
                }
                // Once, over the foot of the pane: the gestures nothing on screen names.
                if (pinchHint && focused != null && sheet == null) {
                    PinchHint(onDismiss = onPinchHintSeen, modifier = Modifier.align(Alignment.BottomCenter))
                }
                // The way back out of full screen, always on screen while it is on: small, in the
                // corner a video player uses, and over the pane rather than taking a row from it.
                if (chrome.fullScreen) {
                    RoundButton(
                        R.drawable.ic_fullscreen_exit,
                        stringResource(R.string.action_exit_full_screen),
                        { fullScreen = TermChrome(fullScreen).toggled().fullScreen },
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                        plate = Colors.key.copy(alpha = 0.85f),
                        size = 36.dp,
                        iconSize = 20.dp,
                    )
                }
            }
        }
        // The bottom of the screen, on its own: the pane's size is measured above without it, so the
        // keyboard can lift this stack without the terminal ever changing shape.
        Box(Modifier.align(Alignment.BottomCenter).imePadding()) {
            Column(Modifier.onSizeChanged { bottomPx = it.height }.testTag(DOCK_TAG)) {
                if (panes.size > 1 && chrome.panesBelow) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        PaneBar(
                            tab = tab,
                            focused = focused,
                            status = state.blockStatus,
                            busyPanes = state.busy,
                            programs = state.programs,
                            onSelect = { block -> onAct { focusBlock(block.id) } },
                            progress = state.progress,
                        )
                    }
                }
                upload?.let {
                    ReachableWidth {
                        UploadPill(
                            status = it,
                            onCancel = onCancelUpload,
                            onDismiss = onDismissUpload,
                            onRetry = onRetryUpload,
                        )
                    }
                }
                if (dock) {
                    // The dock is one hand's reach wide, whatever the window is: a tablet or a desk
                    // window keeps the keys together in the middle instead of spreading them across
                    // the bottom edge.
                    ReachableWidth {
                        KeyBar(
                            mods = mods,
                            busy = focused?.id?.let { it in state.busy } == true,
                            imeUp = imeShown(),
                            onMod = { mods = mods.tap(it, SystemClock.uptimeMillis()) },
                            onKey = { key, own -> press(key, own) },
                            arrangement = keyArrangement,
                            ctrlC = keyBarCtrlC,
                            onDeskKey = { pressDesk(it) },
                            onNewLine = { press(KeyPress("enter", 0), "shift") },
                            onPaste = { pasteClipboard() },
                            onAttach = { filePicker.launch("*/*") },
                            attachReady = upload?.running != true,
                            onKeyboard = { toggleKeyboard() },
                            onFullScreen = toggleFullScreen,
                            quickReplies = quickReplies,
                            onQuickReply = ::sendQuickReply,
                            onCompose = ::openComposer,
                            onEditReplies = if (onQuickReplies != null) { { sheet = Sheet.QuickReplies } } else null,
                        )
                    }
                }
            }
        }
        // A short window typing has given the top bar's place to the key bar; its menu stays here,
        // over everything, in the corner the exit-full-screen button uses.
        if (chrome.cornerMenu) {
            RoundButton(
                R.drawable.ic_more,
                stringResource(R.string.action_more),
                { sheet = Sheet.Actions },
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                plate = Colors.key.copy(alpha = 0.85f),
                size = 36.dp,
                iconSize = 20.dp,
            )
        }
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
            // The key bar's keys that act on the phone rather than send a key, in the bar's order,
            // whatever the bar shows: a keyboard on the desk takes the bar away, Arrange keys can
            // hide them, and full screen hides it all. See terminal-controls.md, 4.
            Group {
                // Everything below, and every tab and session, searchable: see command-palette.md.
                SheetItem(stringResource(R.string.palette_title), icon = R.drawable.ic_command) { sheet = Sheet.Palette }
                if (focused != null) SheetItem(stringResource(R.string.find_title), icon = R.drawable.ic_search) { openFind() }
                SheetItem(stringResource(R.string.shortcuts_title), icon = R.drawable.ic_keyboard, opens = true) { sheet = Sheet.Shortcuts }
                for (key in PHONE_ACTIONS) {
                    when (key) {
                        // Paste, Send a file and the keyboard act on the pane on screen.
                        BarKey.Paste -> if (focused != null) {
                            SheetItem(stringResource(R.string.key_paste), icon = R.drawable.ic_paste) { pasteClipboard() }
                        }
                        // One file at a time, as the bar's key: greyed out while one is on its way,
                        // so the rows below do not move up and down with the upload.
                        BarKey.Attach -> if (focused != null) {
                            SheetItem(
                                stringResource(R.string.upload_attach),
                                icon = R.drawable.ic_attach_file,
                                enabled = upload?.running != true,
                            ) { filePicker.launch("*/*") }
                        }
                        BarKey.Keyboard -> if (focused != null) {
                            SheetItem(
                                stringResource(if (imeUp) R.string.menu_hide_keyboard else R.string.menu_show_keyboard),
                                icon = if (imeUp) R.drawable.ic_keyboard_hide else R.drawable.ic_keyboard,
                            ) { toggleKeyboard() }
                        }
                        BarKey.FullScreen -> SheetItem(stringResource(R.string.action_full_screen), icon = R.drawable.ic_fullscreen) { toggleFullScreen() }
                        BarKey.Compose -> if (focused != null) {
                            SheetItem(stringResource(R.string.composer_title), icon = R.drawable.ic_edit) { openComposer() }
                        }
                        else -> Unit
                    }
                }
                // The tab's text size, which only a pinch reached before: − / +, Reset, the grid.
                if (focused != null && sizeKey != null) {
                    TextSizeRow(tabScale, fontScale, grid, onScale = ::setTabScale, onReset = ::resetTabScale)
                }
            }
            Group(stringResource(R.string.level_pane)) {
                // The top bar's holder chip, which full screen and a short window's keyboard hide.
                if (showHolder) {
                    SheetItem(
                        stringResource(if (state.holder == Holder.Phone) R.string.action_release else R.string.action_take_over),
                        // A hand handing the pane back, or one reaching out for it.
                        icon = if (state.holder == Holder.Phone) R.drawable.ic_back_hand else R.drawable.ic_front_hand,
                    ) { toggleHolder() }
                }
                SheetItem(stringResource(R.string.menu_split_right), icon = R.drawable.ic_split_right) { onAct { split(vertical = false) } }
                SheetItem(stringResource(R.string.menu_split_below), icon = R.drawable.ic_split_below) { onAct { split(vertical = true) } }
                if (focused != null) {
                    SheetItem(stringResource(R.string.menu_rename_pane), icon = R.drawable.ic_edit) { rename = Rename(Level.Pane, focused.id, focused.label) }
                    // Selecting without a long press, which may be set to do something else.
                    SheetItem(stringResource(R.string.select_text), icon = R.drawable.ic_text_select) { selectText() }
                    SheetItem(stringResource(R.string.palette_copy_output), icon = R.drawable.ic_copy) { copyOutput() }
                    SheetItem(stringResource(R.string.palette_share_output), icon = R.drawable.ic_share) { shareOutput() }
                    SheetItem(stringResource(R.string.insert_path), icon = R.drawable.ic_folder, opens = true) { sheet = Sheet.InsertPath }
                    // A tab's last pane closing closes the tab on Rex: that is Close tab, below.
                    if (panes.size > 1) {
                        SheetItem(stringResource(R.string.menu_close_pane), Colors.danger, icon = R.drawable.ic_close) {
                            confirm = Confirm(Level.Pane, focused.id, focused.label)
                        }
                    }
                }
            }
            // Rex's own two Pane actions that erase what the pane shows: each asks first.
            if (focused != null) {
                Group(stringResource(R.string.group_pane_more)) {
                    SheetItem(stringResource(R.string.palette_clear_terminal), Colors.danger, icon = R.drawable.ic_clear_all) { wipe = Wipe.Clear }
                    SheetItem(stringResource(R.string.palette_reset_terminal), Colors.danger, icon = R.drawable.ic_restart) { wipe = Wipe.Reset }
                }
            }
            Group(stringResource(R.string.level_tab)) {
                SheetItem(stringResource(R.string.menu_new_tab), icon = R.drawable.ic_add) { onAct { newWindow() } }
                if (view != null && tab != null) {
                    val title = tabTitle(view, tab, state)
                    SheetItem(stringResource(R.string.menu_rename_tab), icon = R.drawable.ic_edit) { rename = Rename(Level.Tab, tab.id, tab.label) }
                    SheetItem(stringResource(R.string.status_details), icon = R.drawable.ic_eye) { sheet = Sheet.StatusDetails(tab.id) }
                    // Its panes' notifications off, or on again: the host's page lists the muted tabs.
                    val muted = tabMuted(tab.id)
                    SheetItem(
                        stringResource(if (muted) R.string.menu_unmute_tab else R.string.menu_mute_tab),
                        icon = if (muted) R.drawable.ic_notifications else R.drawable.ic_notifications_off,
                        supporting = if (muted) stringResource(R.string.menu_tab_muted) else null,
                    ) { onMuteTab(tab.id, !muted) }
                    SheetItem(stringResource(R.string.menu_close_tab), Colors.danger, icon = R.drawable.ic_close) { confirm = Confirm(Level.Tab, tab.id, title) }
                }
            }
            Group(stringResource(R.string.level_session)) {
                SheetItem(stringResource(R.string.header_sessions), icon = R.drawable.ic_sessions) { sheet = Sheet.Sessions }
                if (view != null) {
                    SheetItem(stringResource(R.string.menu_rename_session), icon = R.drawable.ic_edit) {
                        rename = Rename(Level.Session, view.sessionId, view.label)
                    }
                    SheetItem(stringResource(R.string.menu_kill_session), Colors.danger, icon = R.drawable.ic_delete) {
                        confirm = Confirm(Level.Session, view.sessionId, view.label)
                    }
                }
            }
        }
        Sheet.Palette -> {
            val paneGroup = stringResource(R.string.level_pane)
            val tabGroup = stringResource(R.string.level_tab)
            val sessionGroup = stringResource(R.string.level_session)
            val phoneGroup = stringResource(R.string.palette_group_phone)
            val copied = stringResource(R.string.toast_copied)
            val entries = buildList {
                fun action(label: String, group: String, rexName: String? = null, run: () -> Unit) =
                    add(PaletteEntry(label, group, null, PaletteRun(run = run), keywords = rexName))
                // Actions, in the ⋮ menu's order: Pane, Tab, Session, then the phone's own.
                if (focused != null) {
                    if (showHolder) {
                        action(stringResource(if (state.holder == Holder.Phone) R.string.action_release else R.string.action_take_over), paneGroup) { toggleHolder() }
                    }
                }
                action(stringResource(R.string.menu_split_right), paneGroup, "Split Pane Right pane.split.right") { onAct { split(vertical = false) } }
                action(stringResource(R.string.menu_split_below), paneGroup, "Split Pane Down pane.split.down") { onAct { split(vertical = true) } }
                if (focused != null) {
                    val index = panes.indexOfFirst { it.id == focused.id }
                    if (panes.size > 1) {
                        val next = panes[(index + 1) % panes.size]
                        val previous = panes[(index - 1 + panes.size) % panes.size]
                        action(stringResource(R.string.palette_next_pane), paneGroup, "Focus Next Pane pane.focus_next") { onAct { focusBlock(next.id) } }
                        action(stringResource(R.string.palette_previous_pane), paneGroup, "Focus Previous Pane pane.focus_previous") { onAct { focusBlock(previous.id) } }
                    }
                    action(stringResource(R.string.menu_rename_pane), paneGroup, "Rename Pane pane.rename") { rename = Rename(Level.Pane, focused.id, focused.label) }
                    if (panes.size > 1) {
                        action(stringResource(R.string.menu_close_pane), paneGroup, "Close Pane pane.close") { confirm = Confirm(Level.Pane, focused.id, focused.label) }
                    }
                    // Rex's own two Pane actions (client.keymap): the pane's terminal methods.
                    action(
                        stringResource(R.string.palette_clear_terminal),
                        paneGroup,
                        "Clear Terminal com.superlogical.terminal.clear screen scrollback erase cmd+k ctrl+l",
                    ) { wipe = Wipe.Clear }
                    action(
                        stringResource(R.string.palette_reset_terminal),
                        paneGroup,
                        "Reset Terminal com.superlogical.terminal.reset clear scrollback emulator ris",
                    ) { wipe = Wipe.Reset }
                    // The whole output, screen and scrollback, each line as the program wrote it.
                    action(stringResource(R.string.palette_copy_output), paneGroup, "Copy Output terminal format text scrollback") { copyOutput() }
                    action(stringResource(R.string.palette_share_output), paneGroup, "Share Output terminal format text scrollback send") { shareOutput() }
                    action(stringResource(R.string.select_text), paneGroup, "Select Text selection copy mark highlight") { selectText() }
                    action(stringResource(R.string.insert_path), paneGroup, "Insert Path… terminal.list_dir folder directory browse cd file") { sheet = Sheet.InsertPath }
                    action(stringResource(R.string.find_title), paneGroup, "Find client.find.show search scrollback ctrl+shift+f") { openFind() }
                    action(stringResource(R.string.palette_copy_pane_id), paneGroup, "Copy Block ID pane.copy_id") {
                        clipboardSet(context, focused.id)
                        toast(context, copied)
                    }
                }
                action(stringResource(R.string.menu_new_tab), tabGroup, "New Tab client.tab.new") { onAct { newWindow() } }
                if (view != null && tab != null) {
                    val windows = view.windows
                    val at = windows.indexOfFirst { it.id == tab.id }
                    if (windows.size > 1 && at >= 0) {
                        val next = windows[(at + 1) % windows.size]
                        val previous = windows[(at - 1 + windows.size) % windows.size]
                        action(stringResource(R.string.palette_next_tab), tabGroup, "Show Next Tab client.tab.next") { onAct { focusWindow(next.id) } }
                        action(stringResource(R.string.palette_previous_tab), tabGroup, "Show Previous Tab client.tab.previous") { onAct { focusWindow(previous.id) } }
                    }
                    val title = tabTitle(view, tab, state)
                    action(stringResource(R.string.menu_rename_tab), tabGroup, "Rename Tab client.tab.rename") { rename = Rename(Level.Tab, tab.id, tab.label) }
                    action(stringResource(R.string.menu_close_tab), tabGroup, "Close Tab client.tab.close") { confirm = Confirm(Level.Tab, tab.id, title) }
                }
                action(stringResource(R.string.session_new), sessionGroup, "New Session session.new") { onAct { createShell(null) } }
                val sessions = state.sessions
                val here = sessions.indexOfFirst { it.id == state.activeSessionId }
                if (sessions.size > 1 && here >= 0) {
                    val next = sessions[(here + 1) % sessions.size]
                    val previous = sessions[(here - 1 + sessions.size) % sessions.size]
                    action(stringResource(R.string.palette_next_session), sessionGroup, "Next Session session.next") { onOpenSession(next.id) }
                    action(stringResource(R.string.palette_previous_session), sessionGroup, "Previous Session session.previous") { onOpenSession(previous.id) }
                }
                if (view != null) {
                    action(stringResource(R.string.menu_rename_session), sessionGroup, "Rename Session session.rename") { rename = Rename(Level.Session, view.sessionId, view.label) }
                    action(stringResource(R.string.menu_kill_session), sessionGroup, "Close Session session.close") { confirm = Confirm(Level.Session, view.sessionId, view.label) }
                }
                if (focused != null) {
                    action(stringResource(R.string.key_paste), phoneGroup, "Paste into Pane pane.paste") { pasteClipboard() }
                    if (upload?.running != true) action(stringResource(R.string.upload_attach), phoneGroup, "Insert File pane.insert_file") { filePicker.launch("*/*") }
                    action(stringResource(if (imeUp) R.string.menu_hide_keyboard else R.string.menu_show_keyboard), phoneGroup, "Show Keyboard client.keyboard.show Hide Keyboard client.keyboard.hide") { toggleKeyboard() }
                }
                action(stringResource(if (chrome.fullScreen) R.string.palette_leave_full_screen else R.string.action_full_screen), phoneGroup, "Toggle Full Screen client.window.fullscreen.toggle") { toggleFullScreen() }
                if (sizeKey != null) {
                    action(stringResource(R.string.palette_font_bigger), phoneGroup, "Increase Font Size client.font.increase zoom") { setTabScale(TextScale.bigger(tabScale)) }
                    action(stringResource(R.string.palette_font_smaller), phoneGroup, "Decrease Font Size client.font.decrease zoom") { setTabScale(TextScale.smaller(tabScale)) }
                    action(stringResource(R.string.palette_font_reset), phoneGroup, "Reset Font Size client.font.reset zoom") { resetTabScale() }
                }
                action(stringResource(R.string.palette_back_home), phoneGroup) { onBack() }
                action(stringResource(R.string.shortcuts_title), phoneGroup, "Keyboard Shortcuts keys help ctrl+shift+/") { sheet = Sheet.Shortcuts }
                if (focused != null) {
                    // Rex's own Compose Input… opens its composer; the phone's sends one paste.
                    action(stringResource(R.string.composer_title), phoneGroup, "Compose Input client.composer.open prompt message") { openComposer() }
                    if (onQuickReplies != null) action(stringResource(R.string.quick_replies_edit_all), phoneGroup, "Quick Replies settings edit") { sheet = Sheet.QuickReplies }
                    val replyGroup = stringResource(R.string.palette_group_quick_reply)
                    for (reply in quickReplies) {
                        add(PaletteEntry(QuickReplies.short(reply), replyGroup, null, PaletteRun { sendQuickReply(reply) }, keywords = "send reply snippet $reply"))
                    }
                }
                // Where to go: the tabs of the session on screen, then of the other sessions the
                // phone has loaded, then every session.
                val ordered = listOfNotNull(view) + state.sessions.mapNotNull { s -> state.views[s.id]?.takeIf { it.sessionId != view?.sessionId } }
                for (v in ordered) {
                    val sessionLabel = state.sessions.firstOrNull { it.id == v.sessionId }?.label ?: v.label
                    for (window in v.windows) {
                        val current = v.sessionId == view?.sessionId && window.id == tab?.id
                        val word = window.blocks.firstNotNullOfOrNull { state.blockStatus[it.id] }?.let { statusWord(it) }
                        add(
                            PaletteEntry(
                                tabTitle(v, window, state),
                                tabGroup,
                                listOfNotNull(sessionLabel, word).joinToString(" · "),
                                PaletteRun(marked = current) {
                                    when {
                                        current -> Unit
                                        v.sessionId == view?.sessionId -> onAct { focusWindow(window.id) }
                                        else -> onOpenTab(v.sessionId, window.id)
                                    }
                                },
                                keywords = "Go to Tab client.tab.goto",
                            ),
                        )
                    }
                }
                for (s in state.sessions) {
                    val active = s.id == state.activeSessionId
                    add(PaletteEntry(s.label, sessionGroup, null, PaletteRun(marked = active) { if (!active) onOpenSession(s.id) }, "Go to Session session.select"))
                }
                // The configuration file's own actions, under their category as Rex's palette files them.
                for (a in rexActions) {
                    add(PaletteEntry(a.title, a.category, a.description, PaletteRun { onRexAction(a) }, keywords = listOfNotNull(a.name, a.keywords).joinToString(" ")))
                }
            }
            LaunchedEffect(Unit) { onPaletteOpen() }
            CommandPalette(entries, onDismiss = { sheet = null })
        }
        Sheet.Shortcuts -> ShortcutsSheet(onDismiss = { sheet = null }, onRun = ::runShortcut)
        Sheet.Navigator -> if (view != null) {
            ActionSheet(onDismiss = { sheet = null }, title = view.label) {
                Group {
                    SheetItem(stringResource(R.string.header_sessions), detail = pluralStringResource(R.plurals.session_count, state.sessions.size, state.sessions.size), icon = R.drawable.ic_sessions) {
                        sheet = Sheet.Sessions
                    }
                }
                Group(label = stringResource(R.string.group_tabs), icons = false) {
                    view.windows.forEach { window ->
                        SheetItem(
                            label = tabTitle(view, window, state),
                            detail = window.blocks.size.takeIf { it > 1 }?.let { pluralStringResource(R.plurals.pane_count, it, it) },
                            marked = window.id == tab?.id,
                        ) {
                            if (window.id != tab?.id) onAct { focusWindow(window.id) }
                        }
                    }
                }
                if (panes.size > 1) {
                    Group(label = stringResource(R.string.group_panes), icons = false) {
                        panes.forEachIndexed { index, block ->
                            SheetItem(
                                label = "${index + 1}  ${paneTitle(block, state.blockStatus[block.id], state.programs[block.id])}",
                                detail = state.blockStatus[block.id]?.let { statusWord(it) },
                                marked = block.id == focused?.id,
                            ) {
                                if (block.id != focused?.id) onAct { focusBlock(block.id) }
                            }
                        }
                    }
                }
                Group {
                    SheetItem(stringResource(R.string.menu_new_tab), icon = R.drawable.ic_add) { onAct { newWindow() } }
                    SheetItem(stringResource(R.string.menu_split_right), icon = R.drawable.ic_split_right) { onAct { split(vertical = false) } }
                    SheetItem(stringResource(R.string.menu_split_below), icon = R.drawable.ic_split_below) { onAct { split(vertical = true) } }
                }
            }
        }
        is Sheet.StatusDetails -> {
            // Gone with its tab, as the tab's sheet is.
            val details = tabDetails(state, open.windowId, seenMark)
            if (view != null && details != null) {
                StatusDetailsSheet(tabTitle(view, details.window, state), details) { sheet = null }
            } else {
                LaunchedEffect(open) { sheet = null }
            }
        }
        is Sheet.Composer -> {
            val key = PromptDrafts.key(draftHost, open.blockId)
            PromptComposer(
                target = open.title,
                draft = drafts[key],
                onDraft = { drafts[key] = it },
                quickReplies = quickReplies,
                // Return after, as the person last chose it on this host.
                enterAfter = drafts.enterAfter(draftHost),
                onEnterAfter = { drafts.setEnterAfter(draftHost, it) },
                onSend = { text, enter ->
                    followNewest()
                    // The draft stays until Rex has taken the prompt: a send that fails keeps it.
                    onAct { if (replyTo(open.sessionId, open.blockId, text, enter) && drafts[key] == text) drafts[key] = "" }
                },
                onEditReplies = if (onQuickReplies != null) { { sheet = Sheet.QuickReplies } } else null,
                clearOnSend = false,
                onDismiss = { sheet = null },
            )
        }
        Sheet.InsertPath -> if (focused != null) {
            InsertPathSheet(
                list = { path -> listFolder(path) },
                onInsert = ::insertPath,
                onDismiss = { sheet = null },
            )
        }
        Sheet.QuickReplies -> if (onQuickReplies != null) {
            QuickRepliesSheet(QuickReplies(quickReplies), onQuickReplies, onDismiss = { sheet = null })
        }
    }

    link?.let { url ->
        LinkSheet(
            url = url,
            onDismiss = { link = null },
            onOpen = { openLink(context, url, copied = R.string.toast_link_copied) },
            onCopy = {
                clipboardSet(context, url)
                toast(context, context.getString(R.string.toast_copied))
            },
            onShare = { shareText(context, url) },
        )
    }

    rename?.let { target ->
        var text by remember(target) { mutableStateOf(target.current) }
        fun save() {
            rename = null
            val label = text.trim()
            if (label.isEmpty() || label == target.current) return
            onAct {
                when (target.kind) {
                    Level.Tab -> renameWindow(label, target.id)
                    Level.Pane -> renameBlock(label, target.id)
                    Level.Session -> renameSession(label)
                }
            }
        }
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
            // Enter on the keyboard saves, as the button does.
            text = { Field(stringResource(R.string.field_name), text, autoFocus = true, imeAction = ImeAction.Done, onDone = { save() }) { text = it } },
            confirmButton = {
                TextButton(onClick = { save() }) { Text(stringResource(R.string.action_save), color = Colors.accent) }
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

    wipe?.let { target ->
        AlertDialog(
            onDismissRequest = { wipe = null },
            containerColor = Colors.surface,
            title = {
                Text(
                    stringResource(if (target == Wipe.Clear) R.string.confirm_clear_terminal else R.string.confirm_reset_terminal),
                    color = Colors.ink,
                )
            },
            text = {
                Text(
                    stringResource(if (target == Wipe.Clear) R.string.confirm_clear_terminal_body else R.string.confirm_reset_terminal_body),
                    color = Colors.muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    wipe = null
                    followNewest()
                    onAct { if (target == Wipe.Clear) clearPane() else resetPane() }
                }) {
                    Text(stringResource(if (target == Wipe.Clear) R.string.action_clear else R.string.confirm_reset_action), color = Colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { wipe = null }) { Text(stringResource(R.string.action_cancel), color = Colors.muted) }
            },
        )
    }
}

/**
 * The floating top bar of the reference: a round back button, the pane's tile, the tab with the
 * directory it is in, the session under it, and the menu. No bar behind it; the paper is the
 * background. The title block opens the tabs and panes of the session.
 */
@Composable
private fun SessionTopBar(
    state: PocketState,
    view: SessionView?,
    tab: WindowInfo?,
    status: ProgramRecord?,
    onBack: () -> Unit,
    /** False while the list stands beside this pane: there the pane has no way back to leave. */
    showBack: Boolean,
    onNavigate: () -> Unit,
    onHolder: () -> Unit,
    onMore: () -> Unit,
    /** The pane switcher, where a short window puts it: in this row rather than over the pane. */
    panes: (@Composable () -> Unit)? = null,
    /** The tab's progress bar, drawn along the bar's foot without taking a row from the pane. */
    progress: PaneProgress? = null,
) {
    val title = when {
        view == null -> state.endpointLabel.ifBlank { stringResource(R.string.app_name) }
        tab != null -> tabTitle(view, tab, state)
        else -> view.label
    }
    val path = state.pwd?.takeIf { it.isNotBlank() }?.let { shortPath(it) }
    Box(Modifier.fillMaxWidth().height(TOP_BAR_HEIGHT)) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                RoundButton(R.drawable.ic_back, stringResource(R.string.action_back), onBack, plate = Colors.key, size = 40.dp)
            } else {
                Spacer(Modifier.width(4.dp))
            }
            Row(
                Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(Radius.card))
                    .clickable(enabled = view != null, role = Role.Button, onClickLabel = stringResource(R.string.menu_navigate), onClick = onNavigate)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProgramTile(tab?.focusedBlock()?.id?.let { state.programs[it] }, size = 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
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
                            Spacer(Modifier.width(6.dp))
                            Text(path, color = Colors.muted, fontSize = TextStyles.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(status?.let { statusTint(it) } ?: phaseTint(state.phase), 6.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            view?.label ?: phaseText(state),
                            color = Colors.muted,
                            fontSize = TextStyles.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Glyph(R.drawable.ic_expand, Colors.muted, size = 18.dp)
                    }
                }
            }
            if (panes != null) {
                panes()
                Spacer(Modifier.width(8.dp))
            }
            // Who holds the pane, while there is one on screen to hold.
            if (holderShown(state, tab)) {
                HolderChip(state.holder == Holder.Phone, onHolder)
                Spacer(Modifier.width(4.dp))
            }
            RoundButton(R.drawable.ic_more, stringResource(R.string.action_more), onMore, plate = Colors.key, size = 40.dp)
        }
        if (progress != null) {
            ProgressLine(progress, Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 2.dp))
        }
    }
}

/**
 * In place of a pane that was closed elsewhere: what happened, in calm words, and where to go next -
 * the tab Rex shows now, a new tab when the session has none left, or back to the sessions.
 */
@Composable
private fun ClosedPaneNote(
    closed: ClosedPane,
    /** The title of the tab the session shows now; null when it has no tab left. */
    next: String?,
    onShowNext: () -> Unit,
    onNewTab: () -> Unit,
    /** Null where the list already stands beside the pane. */
    onSessions: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Glyph(R.drawable.ic_close, Colors.muted, size = 36.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(if (closed.tabClosed) R.string.closed_tab_title else R.string.closed_pane_title),
            color = Colors.ink,
            fontSize = TextStyles.title,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(8.dp))
        val exit = closed.exit
        Text(
            when {
                exit == null -> stringResource(R.string.closed_body_elsewhere)
                exit.code >= 0 -> stringResource(R.string.closed_body_exited, exit.code)
                // No code: a signal ended it, and Rex says which.
                else -> stringResource(R.string.closed_body_ended, exit.error ?: "-1")
            },
            color = Colors.muted,
            fontSize = TextStyles.label,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(24.dp))
        Column(Modifier.widthIn(max = 360.dp)) {
            if (next != null) {
                PrimaryButton(stringResource(R.string.closed_show, next), onClick = onShowNext)
            } else {
                PrimaryButton(stringResource(R.string.menu_new_tab), onClick = onNewTab)
            }
            if (onSessions != null) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(Radius.card))
                        .clickable(role = Role.Button, onClick = onSessions),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.closed_sessions), color = Colors.accent, fontSize = TextStyles.body)
                }
            }
        }
    }
}

/**
 * In place of a pane when the session has no tab: Rex keeps a session after its last tab closes, so
 * opening it from the list lands here. A new tab, or back to the sessions.
 */
@Composable
private fun EmptySessionNote(
    onNewTab: () -> Unit,
    /** Null where the list already stands beside the pane. */
    onSessions: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(horizontal = 32.dp).testTag(EMPTY_SESSION_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.empty_session_title),
            color = Colors.ink,
            fontSize = TextStyles.title,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_session_body),
            color = Colors.muted,
            fontSize = TextStyles.label,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(24.dp))
        Column(Modifier.widthIn(max = 360.dp)) {
            PrimaryButton(stringResource(R.string.menu_new_tab), onClick = onNewTab)
            if (onSessions != null) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(Radius.card))
                        .clickable(role = Role.Button, onClick = onSessions),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.closed_sessions), color = Colors.accent, fontSize = TextStyles.body)
                }
            }
        }
    }
}

/** The note shown for a session with no tabs. */
internal const val EMPTY_SESSION_TAG = "empty-session"

/**
 * Who holds the pane, and the one tap that changes it. While the desk holds it the chip is the
 * action colour and says "Take over"; while this phone does it is quiet and says so, and tapping
 * it hands the pane back. The pane has the holder's size, so this is what decides how it looks.
 */
@Composable
private fun HolderChip(owning: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (owning) R.string.holder_phone else R.string.action_take_over)
    val action = stringResource(if (owning) R.string.action_release else R.string.action_take_over)
    Box(
        Modifier.height(40.dp).clip(RoundedCornerShape(50))
            .background(if (owning) Colors.key else Colors.accent)
            .clickable(role = Role.Button, onClickLabel = action, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (owning) Colors.muted else Colors.onAccent,
            fontSize = TextStyles.label,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * The panes of the shown tab, in one floating pill under the pane, as the reference draws them: a
 * segment per pane with the tile of what runs in it and its name, the one on screen on a soft plate
 * in the action colour. Tapping a segment goes there; a horizontal swipe on the pane does the same.
 * Each segment is a full 48 dp target.
 */
@Composable
private fun PaneBar(
    tab: WindowInfo?,
    focused: BlockInfo?,
    status: Map<String, ProgramRecord>,
    busyPanes: Set<String>,
    programs: Map<String, String>,
    onSelect: (BlockInfo) -> Unit,
    modifier: Modifier = Modifier,
    /** Each pane's progress bar, under its segment. */
    progress: Map<String, PaneProgress> = emptyMap(),
) {
    val blocks = tab?.blocks.orEmpty()
    val scroll = rememberScrollState()
    val selected = stringResource(R.string.state_selected)
    Row(
        modifier.floating(RoundedCornerShape(50)).horizontalScroll(scroll).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        blocks.forEachIndexed { index, block ->
            val on = block.id == focused?.id
            val record = status[block.id]
            val busy = block.id in busyPanes
            val bar = progress[block.id]
            val barWords = bar?.let { progressWords(it) }
            Box(
                Modifier.height(48.dp)
                    .clickable(role = Role.Tab) { if (!on) onSelect(block) }
                    .semantics { listOfNotNull(selected.takeIf { on }, barWords).takeIf { it.isNotEmpty() }?.let { stateDescription = it.joinToString(", ") } }
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier.fillMaxHeight().clip(RoundedCornerShape(50))
                        .then(if (on) Modifier.background(Colors.keyOn) else Modifier)
                        .padding(start = 6.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProgramTile(programs[block.id], size = 24.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        paneTitle(block, record, programs[block.id]),
                        color = if (on) Colors.accent else Colors.ink,
                        fontSize = TextStyles.subhead,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 104.dp),
                    )
                    if (busy && record != null) {
                        Spacer(Modifier.width(6.dp))
                        Dot(statusTint(record), 6.dp)
                    }
                }
                // Over the segment's plate, along its foot, as wide as the segment.
                if (bar != null) {
                    ProgressLine(
                        bar,
                        Modifier.matchParentSize().wrapContentHeight(Alignment.Bottom).padding(horizontal = 12.dp),
                        height = 2.dp,
                        merged = true,
                    )
                }
            }
        }
    }
}

/**
 * One file's way to the desktop: what it is doing, how far it has got, and the one control that
 * makes sense there - stop it while it runs, retry a failure that inserted nothing, dismiss the
 * rest. An unconfirmed finish says so and offers no retry, because Rex may already have the file.
 */
@Composable
private fun UploadPill(
    status: UploadStatus,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    val done = status.phase == UploadPhase.Done
    val failed = status.phase == UploadPhase.Failed || status.phase == UploadPhase.Uncertain || status.phase == UploadPhase.Unsupported
    val tint = when {
        done -> Colors.ok
        failed -> Colors.danger
        else -> Colors.ink
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp)
            .floating(RoundedCornerShape(Radius.card)).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                uploadText(status),
                color = tint,
                fontSize = TextStyles.subhead,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (status.phase == UploadPhase.Failed) {
                Text(
                    status.error?.let { uploadErrorText(it) }.orEmpty(),
                    color = Colors.muted,
                    fontSize = TextStyles.label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            status.phase == UploadPhase.Failed -> {
                if (status.retryable) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry), color = Colors.accent, fontSize = TextStyles.label) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss), color = Colors.muted, fontSize = TextStyles.label) }
            }
            failed -> TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_dismiss), color = Colors.muted, fontSize = TextStyles.label)
            }
            done -> Unit
            else -> TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel), color = Colors.muted, fontSize = TextStyles.label)
            }
        }
    }
}

@Composable
private fun uploadText(status: UploadStatus): String = when (status.phase) {
    UploadPhase.Preparing -> stringResource(R.string.upload_preparing, status.name)
    UploadPhase.Sending -> stringResource(R.string.upload_sending, status.name, (status.progress * 100).roundToInt())
    UploadPhase.Finishing -> stringResource(R.string.upload_finishing, status.name)
    UploadPhase.Done -> stringResource(R.string.upload_done, status.name)
    UploadPhase.Unsupported -> stringResource(R.string.upload_need_update)
    UploadPhase.Uncertain -> stringResource(R.string.upload_uncertain)
    UploadPhase.Failed -> stringResource(R.string.upload_failed, status.name)
    UploadPhase.Canceled -> stringResource(R.string.upload_canceled)
}

/**
 * The foot of the terminal, as the reference lays it out: the pane bar when the tab is split, then
 * one floating pill - keyboard, Enter, the special keys, the attach key - and the circle that makes
 * a tab. All of it is 56 dp, the same height as the home's floating controls, on the page margin.
 */
/** Who holds the pane is shown while the session is live and a tab is open: the chip and the menu's row. */
private fun holderShown(state: PocketState, tab: WindowInfo?): Boolean = state.phase == Phase.Live && tab != null

/**
 * The key bar's keys that act on the phone rather than send bytes to the program, in the bar's own
 * order: the ⋮ menu repeats each of them, whatever the bar shows. A key added to the bar has to be
 * placed here or among the keys that only type.
 */
internal val BarKey.actsOnPhone: Boolean
    get() = when (this) {
        BarKey.Paste, BarKey.Attach, BarKey.Keyboard, BarKey.FullScreen, BarKey.Compose -> true
        BarKey.Escape, BarKey.CtrlC, BarKey.Tab, BarKey.Enter, BarKey.Ctrl, BarKey.Shift, BarKey.Alt,
        BarKey.Slash, BarKey.Arrows -> false
        // Quick replies type, as the keys do; the palette lists each one too.
        BarKey.Replies -> false
        // Group keys open a panel; the rest of the catalogue types.
        is BarKey.Group, is BarKey.Desk, is BarKey.Arrow, is BarKey.Chord, is BarKey.Typed -> false
    }

/** The menu's first group: [actsOnPhone] keys, the wide pill's before the small pill's. */
internal val PHONE_ACTIONS: List<BarKey> = BarKey.catalogue.filter { it.actsOnPhone }

/**
 * The room the phone's status and navigation bars take while they are shown: their sizes whether
 * or not they are on screen this moment, which Android keeps up to date as they come and go. See
 * the bars in TermScreen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun systemBarsRoom(): WindowInsets =
    WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.navigationBarsIgnoringVisibility)

/** Whether the phone keyboard is up, which decides which way the keyboard key points. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun imeShown(): Boolean = WindowInsets.isImeVisible

/**
 * A tab's name, as the desktop's sidebar writes it. A tab someone named keeps its name; one left at
 * Rex's generated "Window" label takes the title its focused pane's program set, such as
 * `π - rexpocket`, then the program's own name, and only then its position.
 */
@Composable
internal fun tabTitle(view: SessionView, window: WindowInfo, state: PocketState? = null): String {
    if (!GENERATED_WINDOW_LABEL.matches(window.label)) return window.label
    val pane = window.focusedBlock()?.id
    val named = pane?.let { state?.titles?.get(it) }
    if (named != null) return named
    val program = pane?.let { state?.programs?.get(it) }
    if (program != null && programMark(program) != ProgramMark.Shell) return program
    return stringResource(R.string.tab_number, view.windows.indexOf(window) + 1)
}

/** What a tab's tile shows: the program in its focused pane. */
internal fun tabProgram(window: WindowInfo, state: PocketState): String? =
    window.focusedBlock()?.id?.let { state.programs[it] }

/** A pane left at the default "shell" label is named after the program running in it. */
internal fun paneTitle(block: BlockInfo, status: ProgramRecord?, program: String? = null): String {
    val app = status?.app?.takeIf { it.isNotBlank() } ?: program
    return if (block.label == "shell" && app != null) app else block.label
}

/** What the program in the pane is doing, in the app's words; null when Rex has not said. */
@Composable
internal fun statusWord(status: ProgramRecord): String? = statusWordRes(status)?.let { stringResource(it) }

/** [statusWord] as a resource, for the alerts, which are written outside a screen. */
internal fun statusWordRes(status: ProgramRecord): Int? = when (status.state) {
    "working" -> R.string.state_running
    "blocked" -> when (status.kind) {
        "permission" -> R.string.state_waiting_approval
        "question" -> R.string.state_waiting_answer
        "auth" -> R.string.state_waiting_login
        else -> R.string.state_waiting_you
    }
    "done" -> R.string.state_done
    "error" -> R.string.state_failed
    "idle" -> R.string.state_idle
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

/** How long Insert path waits for a folder before it says it could not read it. */
private const val LIST_DIR_WAIT_MS = 15_000L

/** How long a failed action stays on the terminal screen. */
internal const val NOTICE_MS = 4_000L

internal val GENERATED_WINDOW_LABEL = Regex("Window [0-9a-f]{6,}")

/** The pane's place on the terminal screen, and the stack under it, for the screen tests to measure. */
internal const val PANE_TAG = "terminal-pane"
internal const val DOCK_TAG = "terminal-dock"

/** The top bar's height, which a short window keeps as room while the bar steps aside. */
private val TOP_BAR_HEIGHT = 64.dp
