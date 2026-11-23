package me.leaver.rexpocket.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.emptyFlow
import me.leaver.rexpocket.core.BlockInfo
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.PocketSession
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.SessionView
import me.leaver.rexpocket.core.WindowInfo

// The terminal screen as RexApp shows it: TermScreen, with the key bar's settings collected from the
// app's own view model, and Back going to the model's showHome. Only the session's state is given,
// since it comes from Rex; see docs/design/testing.md.

/**
 * TermScreen fed as RexApp feeds it, from [model]; [state] stands in for what Rex sent. While the
 * model's route is the terminal it is shown, and Back from it goes home the way the app's does.
 */
@Composable
internal fun TermOnModel(
    model: RexViewModel,
    state: PocketState,
    upload: UploadStatus? = null,
    onAct: (suspend PocketSession.() -> Unit) -> Unit = {},
    onOpenSession: (String) -> Unit = {},
    onOpenTab: (String, String) -> Unit = { _, _ -> },
) {
    val route by model.route.collectAsState()
    val keyBar by model.keyBar.collectAsState()
    val arrangement by model.keyArrangement.collectAsState()
    val ctrlC by model.keyBarCtrlC.collectAsState()
    val gestures by model.gestures.collectAsState()
    val quickReplies by model.quickReplies.collectAsState()
    RexTheme {
        if (route == Route.Term) {
            TermScreen(
                state = state,
                fontScale = 1f,
                terminalTypeface = Typeface.MONOSPACE,
                onFont = {},
                onBack = model::showHome,
                onOpenSession = onOpenSession,
                onOpenTab = onOpenTab,
                watchPane = { emptyFlow() },
                onRetry = {},
                onDismissNotice = {},
                onAct = onAct,
                upload = upload,
                onAttach = {},
                onCancelUpload = {},
                onDismissUpload = {},
                onRetryUpload = {},
                keyBarWithKeyboard = keyBar,
                keyArrangement = arrangement,
                keyBarCtrlC = ctrlC,
                gestures = gestures,
                quickReplies = quickReplies.items,
                drafts = model.drafts,
            )
        }
    }
}

/** The model of an app with nothing stored, on the terminal screen. With [keyBar] the key bar stays with a desk keyboard, as the emulator reports one. */
internal fun termModel(keyBar: Boolean = true): RexViewModel = RexViewModel(freshApp()).also {
    it.setKeyBar(keyBar)
    it.route.value = Route.Term
}

internal const val TEST_SESSION = "session:test"

internal fun testPane(id: String, label: String = id.substringAfter(':')) = BlockInfo("block:$id", label, null, null, null)

internal fun tabOf(vararg panes: BlockInfo, id: String = "window:build", label: String = "build") =
    WindowInfo(id = id, label = label, focusedBlockId = panes.firstOrNull()?.id, zoomedBlockId = null, blocks = panes.toList())

/** A live session showing [tab], held by the phone unless [released]. */
internal fun liveState(tab: WindowInfo = tabOf(testPane("left")), released: Boolean = false, phase: Phase = Phase.Live) = PocketState(
    phase = phase,
    activeSessionId = TEST_SESSION,
    view = SessionView(revision = 2, sessionId = TEST_SESSION, label = "work", activeWindowId = tab.id, windows = listOf(tab)),
    owning = !released,
    released = released,
)

/** True on the JVM, where no window manager sends insets and the tests send them instead. */
internal val onRobolectric: Boolean get() = Build.FINGERPRINT == "robolectric"

private const val READ_CLIPBOARD_IN_BACKGROUND = "android.permission.READ_CLIPBOARD_IN_BACKGROUND"

/**
 * Runs [block] holding READ_CLIPBOARD_IN_BACKGROUND, adopted from the shell, so that the clipboard
 * reads in it, the test's and the app's own (Paste), do not depend on the window focus. From
 * Android 13 a copy brings up the system's copied overlay, which takes the focus, and only the app
 * in focus may read the clipboard ("Denying clipboard access ... not in focus"); on the API 34
 * emulator the focus sometimes does not come back for more than 30 s. The clipboard read is still
 * the real one: only the focus rule is lifted. The JVM has no such rule and no shell.
 */
internal fun <T> clipboardInBackground(context: Context, block: () -> T): T {
    if (onRobolectric || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return block()
    val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
    ui.adoptShellPermissionIdentity(READ_CLIPBOARD_IN_BACKGROUND)
    try {
        check(context.checkSelfPermission(READ_CLIPBOARD_IN_BACKGROUND) == PackageManager.PERMISSION_GRANTED) {
            "the shell did not lend $READ_CLIPBOARD_IN_BACKGROUND"
        }
        return block()
    } finally {
        ui.dropShellPermissionIdentity()
    }
}

/**
 * Sends [view]'s window the insets a phone with its bars [shown] would: a status bar of [statusPx]
 * and a navigation bar of [navPx], whose sizes stay known while they are hidden, as Android's do.
 */
internal fun sendBars(view: View, shown: Boolean, statusPx: Int, navPx: Int, imePx: Int = 0) {
    val status = WindowInsetsCompat.Type.statusBars()
    val nav = WindowInsetsCompat.Type.navigationBars()
    val ime = WindowInsetsCompat.Type.ime()
    val insets = WindowInsetsCompat.Builder()
        .setInsets(status, if (shown) Insets.of(0, statusPx, 0, 0) else Insets.NONE)
        .setInsets(nav, if (shown) Insets.of(0, 0, 0, navPx) else Insets.NONE)
        .setInsetsIgnoringVisibility(status, Insets.of(0, statusPx, 0, 0))
        .setInsetsIgnoringVisibility(nav, Insets.of(0, 0, 0, navPx))
        .setVisible(status or nav, shown)
        .setInsets(ime, Insets.of(0, 0, 0, imePx))
        .setVisible(ime, imePx > 0)
        .build()
    ViewCompat.dispatchApplyWindowInsets(view, insets)
}

/** One line of an open sheet as the person reads it, top to bottom: a row, or a group's caption as "# Caption". */
internal data class SheetLine(val text: String, val enabled: Boolean, val top: Float)

/** The lines of the open sheet, in the order they are on screen. */
internal fun ComposeTestRule.sheetLines(): List<SheetLine> =
    onAllNodes(hasAnyAncestor(hasTestTag(ACTION_SHEET_TAG)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
        .fetchSemanticsNodes()
        .map { node ->
            val text = node.config[SemanticsProperties.Text].joinToString(" ") { it.text }
            val row = node.config.getOrNull(SemanticsActions.OnClick) != null || node.config.getOrNull(SemanticsProperties.Disabled) != null
            SheetLine(if (row) text else "# $text", node.config.getOrNull(SemanticsProperties.Disabled) == null, node.positionInRoot.y)
        }
        .sortedBy { it.top }

internal fun ComposeTestRule.bounds(tag: String): Rect =
    onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().single().boundsInRoot

/** Compose's own view of the window's insets, as a composable reads them, in pixels. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun composeInsets(): String {
    val d = androidx.compose.ui.platform.LocalDensity.current
    val w = androidx.compose.foundation.layout.WindowInsets
    return "ime=${w.ime.getBottom(d)} imeVisible=${w.isImeVisible} nav=${w.navigationBars.getBottom(d)} " +
        "navAlways=${w.navigationBarsIgnoringVisibility.getBottom(d)} status=${w.statusBars.getTop(d)} " +
        "statusAlways=${w.statusBarsIgnoringVisibility.getTop(d)} cutoutBottom=${w.displayCutout.getBottom(d)}"
}

/**
 * Edge to edge as MainActivity is, for an activity that is already on screen. MainActivity calls
 * enableEdgeToEdge before its window is added; here the window is up, and the camera cutout mode
 * it writes into the window's attributes (ALWAYS from Android 11) only applies once they are set
 * again. Without it the window shrinks off the cutout's band as the status bar hides.
 */
internal fun androidx.activity.ComponentActivity.edgeToEdgeAsTheApp() {
    enableEdgeToEdge()
    window.attributes = window.attributes
}
