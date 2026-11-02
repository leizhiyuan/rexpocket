package me.leaver.rexpocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.R
import kotlinx.coroutines.launch

// Bottom sheets: the session switcher and the grouped action menus.

/**
 * Every session on this Rex, nothing more. Picking one opens it on the pane the phone showed
 * there last time; the tab bar and pane strip take over from there.
 */
@Composable
internal fun SessionSheet(
    state: PocketState,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
) {
    ActionSheet(onDismiss = onDismiss, title = stringResource(R.string.sessions)) {
        Group(icons = false) {
            state.sessions.forEach { session ->
                val active = session.id == state.activeSessionId
                val tabs = state.views[session.id]?.windows?.size
                SheetItem(
                    label = session.label,
                    detail = tabs?.let { pluralStringResource(R.plurals.session_window_count, it, it) },
                    marked = active,
                ) { onOpen(session.id) }
            }
        }
        Group { SheetItem(stringResource(R.string.session_new), Colors.accent, icon = R.drawable.ic_add, onClick = onCreate) }
    }
}

/**
 * Lets a sheet row close its sheet. The row's action runs once the sheet has gone: a dialog it opens
 * would otherwise ask for the keyboard while the sheet's window still holds it, and lose it when that
 * window is removed.
 */
internal class SheetScope(val close: (() -> Unit) -> Unit)

/**
 * A bottom sheet in the app's colours: rows grouped on cards over the dark background, the same
 * look as the host and session lists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActionSheet(onDismiss: () -> Unit, title: String? = null, content: @Composable SheetScope.() -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val rows = SheetScope { action ->
        scope.launch { state.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = Colors.bg,
        contentColor = Colors.ink,
        scrimColor = Colors.scrim,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 4.dp).size(width = 36.dp, height = 5.dp).clip(CircleShape).background(Colors.line))
        },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
        ) {
            if (title != null) {
                Text(
                    title,
                    color = Colors.ink,
                    fontSize = TextStyles.title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                )
            }
            rows.content()
        }
    }
}

/**
 * A card of rows, with an optional caption above it. The rows are separated by hairlines inset to
 * where their text starts, as the platform's grouped lists are: past the icon slot when the rows
 * have icons, at the margin when they do not.
 */
@Composable
internal fun SheetScope.Group(
    label: String? = null,
    icons: Boolean = true,
    content: @Composable SheetScope.() -> Unit,
) {
    if (label != null) {
        Text(
            label,
            color = Colors.muted,
            fontSize = TextStyles.label,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp),
        )
    } else {
        Spacer(Modifier.height(12.dp))
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(Colors.surface)) {
        GroupRows(this@Group, if (icons) ICON_INSET else 16.dp, content)
    }
}

/** Lays the rows of a group out with a hairline between each pair. */
@Composable
private fun GroupRows(scope: SheetScope, inset: Dp, content: @Composable SheetScope.() -> Unit) {
    androidx.compose.ui.layout.SubcomposeLayout { constraints ->
        val rows = subcompose("rows") { scope.content() }.map { it.measure(constraints.copy(minHeight = 0)) }
        val line = 1.dp.roundToPx()
        val height = rows.sumOf { it.height } + line * (rows.size - 1).coerceAtLeast(0)
        val divider = subcompose("lines") {
            repeat((rows.size - 1).coerceAtLeast(0)) { Box(Modifier.fillMaxWidth().height(1.dp).background(Colors.line)) }
        }.map { it.measure(constraints.copy(minHeight = 0, minWidth = 0, maxWidth = constraints.maxWidth - inset.roundToPx())) }
        layout(constraints.maxWidth, height) {
            var y = 0
            rows.forEachIndexed { i, row ->
                if (i > 0) {
                    divider[i - 1].place(inset.roundToPx(), y)
                    y += line
                }
                row.place(0, y)
                y += row.height
            }
        }
    }
}

/** Where a row's text starts when it has an icon: the margin, the 24 dp slot and its gap. */
private val ICON_INSET = 56.dp

/**
 * One row of a sheet: an icon in a 24 dp slot, the label, a detail or a check at the end. The row is
 * 56 dp high, the platform's one-line list item. A group's rows either all have icons or none do,
 * so their labels share one keyline.
 */
@Composable
internal fun SheetScope.SheetItem(
    label: String,
    color: Color = Colors.ink,
    detail: String? = null,
    marked: Boolean = false,
    icon: Int? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { close(onClick) }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Glyph(icon, if (color == Colors.ink) Colors.muted else color, size = 24.dp)
            Spacer(Modifier.width(16.dp))
        }
        Text(
            label,
            color = color,
            fontSize = TextStyles.body,
            fontWeight = if (marked) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (detail != null) {
            Spacer(Modifier.width(12.dp))
            Text(detail, color = Colors.muted, fontSize = TextStyles.label, maxLines = 1)
        }
        if (marked) {
            Spacer(Modifier.width(12.dp))
            Glyph(R.drawable.ic_check, Colors.accent, size = 20.dp)
        }
    }
}
