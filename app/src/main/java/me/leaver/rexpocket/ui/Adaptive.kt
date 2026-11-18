package me.leaver.rexpocket.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowHeightSizeClass
import androidx.window.core.layout.WindowWidthSizeClass

// What the window has room for. The app is one column on a phone, one column held to a readable
// width on a small tablet or an unfolded foldable, and a list beside its pane on a tablet or a desk
// window - including a window resized to either side of the lines.

/** The window's width, in the platform's three classes. */
internal enum class WindowWidth { Compact, Medium, Expanded }

/** The window's height, in the platform's three classes. */
internal enum class WindowHeight { Compact, Medium, Expanded }

/** What the window is, as the platform classifies it. */
internal data class WindowSize(val width: WindowWidth, val height: WindowHeight) {
    /**
     * Room for the list beside its pane: a tablet or a desk window, but not a phone held sideways,
     * which is wide enough by the class and has no height to spare for two panes.
     */
    val twoPanes: Boolean get() = width == WindowWidth.Expanded && height != WindowHeight.Compact
}

@Composable
internal fun windowSize(): WindowSize {
    val sizeClass = currentWindowAdaptiveInfo().windowSizeClass
    return WindowSize(
        width = when (sizeClass.windowWidthSizeClass) {
            WindowWidthSizeClass.EXPANDED -> WindowWidth.Expanded
            WindowWidthSizeClass.MEDIUM -> WindowWidth.Medium
            else -> WindowWidth.Compact
        },
        height = when (sizeClass.windowHeightSizeClass) {
            WindowHeightSizeClass.EXPANDED -> WindowHeight.Expanded
            WindowHeightSizeClass.MEDIUM -> WindowHeight.Medium
            else -> WindowHeight.Compact
        },
    )
}

/** The list beside the pane: wide enough for a title, never more than this share of the window. */
private const val LIST_SHARE = 0.4f
private val LIST_MIN = 320.dp
private val LIST_MAX = 420.dp

/** How wide the list beside a pane is drawn, in a window this wide. */
internal fun listPaneWidth(window: Dp): Dp = (window * LIST_SHARE).coerceIn(LIST_MIN, LIST_MAX)

/** One column in a window too wide for one: the width a line of text and a row still read at. */
internal val COLUMN_MAX = 560.dp

/** How the app lays itself out in a window. */
internal enum class WindowLayout {
    /** One column, edge to edge: a phone. */
    OneColumn,

    /** One column held to [COLUMN_MAX] and centred: too wide for one line, too narrow for two. */
    CappedColumn,

    /** The list on the left, the open pane on the right. */
    ListDetail,
}

/**
 * What a window is laid out as, given what there is to show. A pane that is open takes the whole
 * window when there is no room beside it, and a host list with nothing in it has nothing to put
 * beside a pane either.
 */
internal fun layoutFor(size: WindowSize, hasHosts: Boolean, terminalOpen: Boolean): WindowLayout = when {
    size.twoPanes && hasHosts -> WindowLayout.ListDetail
    terminalOpen -> WindowLayout.OneColumn
    size.width != WindowWidth.Compact -> WindowLayout.CappedColumn
    else -> WindowLayout.OneColumn
}

/** The widest a terminal's dock is drawn: one hand's reach, however wide the window is. */
internal val DOCK_MAX = 720.dp

/**
 * A row of controls held to the width two thumbs can reach across and centred, so a terminal's dock
 * does not run the whole width of a desk window. On a phone it is the window, edge to edge.
 */
@Composable
internal fun ReachableWidth(max: Dp = DOCK_MAX, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = max).fillMaxWidth()) { content() }
    }
}
