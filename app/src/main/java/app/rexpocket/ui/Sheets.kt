package app.rexpocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rexpocket.core.PocketState
import app.rexpocket.R
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
        Group {
            state.sessions.forEach { session ->
                val active = session.id == state.activeSessionId
                val tabs = state.views[session.id]?.windows?.size
                SheetItem(
                    label = session.label,
                    color = if (active) Colors.accent else Colors.ink,
                    detail = tabs?.let { stringResource(R.string.session_window_count, it) },
                    marked = active,
                ) { onOpen(session.id) }
            }
        }
        Group { SheetItem("+  " + stringResource(R.string.session_new), Colors.accent, onClick = onCreate) }
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
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Colors.line))
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
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                )
            }
            rows.content()
        }
    }
}

/** A card of rows, with an optional caption above it. */
@Composable
internal fun SheetScope.Group(label: String? = null, content: @Composable SheetScope.() -> Unit) {
    if (label != null) {
        Text(label, color = Colors.muted, fontSize = TextStyles.label, modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp))
    } else {
        Spacer(Modifier.height(10.dp))
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(Colors.surface)) { content() }
}

@Composable
internal fun SheetScope.SheetItem(
    label: String,
    color: Color = Colors.ink,
    detail: String? = null,
    marked: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { close(onClick) }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (marked) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Colors.accent))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            label,
            color = color,
            fontSize = TextStyles.body,
            fontWeight = if (marked) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (detail != null) Text(detail, color = Colors.muted, fontSize = TextStyles.label)
    }
}
