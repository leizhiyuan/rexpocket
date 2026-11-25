package me.leaver.rexpocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.toggleable

import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.isWebLink
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
 * A link long-pressed in the pane: open it, copy it or share it. Only a web link can be opened; any
 * other address a program printed is still there to copy, but is not handed to another app.
 */
@Composable
internal fun LinkSheet(url: String, onDismiss: () -> Unit, onOpen: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit) {
    ActionSheet(onDismiss = onDismiss, title = url) {
        Group {
            if (isWebLink(url)) SheetItem(stringResource(R.string.link_open), Colors.accent, icon = R.drawable.ic_open_in_new, onClick = onOpen)
            SheetItem(stringResource(R.string.link_copy), icon = R.drawable.ic_copy, onClick = onCopy)
            if (isWebLink(url)) SheetItem(stringResource(R.string.link_share), icon = R.drawable.ic_share, onClick = onShare)
        }
    }
}

/** The content of an open sheet, for the screen tests to read its rows in order. */
internal const val ACTION_SHEET_TAG = "action-sheet"

/**
 * Lets a sheet row close its sheet. The row's action runs once the sheet has gone: a dialog it opens
 * would otherwise ask for the keyboard while the sheet's window still holds it, and lose it when that
 * window is removed.
 */
internal class SheetScope(val close: (() -> Unit) -> Unit)

/**
 * A bottom sheet in the app's colours: rows grouped on cards over the dark background, the same
 * look as the host and session lists. A page reached from another sheet has [onBack]: an arrow
 * before its title that goes back up, as swiping the sheet down does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActionSheet(onDismiss: () -> Unit, title: String? = null, onBack: (() -> Unit)? = null, content: @Composable SheetScope.() -> Unit) {
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
    ) { AppTextScale { me.leaver.rexpocket.ui.guide.GuideLayer {
        Column(
            Modifier.fillMaxWidth().testTag(ACTION_SHEET_TAG).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
        ) {
            if (onBack != null) {
                val back = stringResource(R.string.action_back)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape)
                            .clickable(role = Role.Button, onClick = onBack)
                            .semantics { contentDescription = back },
                        contentAlignment = Alignment.Center,
                    ) { Glyph(R.drawable.ic_back, Colors.ink) }
                    if (title != null) {
                        Text(
                            title,
                            color = Colors.ink,
                            fontSize = TextStyles.title,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                }
            } else if (title != null) {
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
    } } }
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

/**
 * Lays the rows of a group out with a hairline between each pair. A plain layout of the rows as
 * they are composed: a row that moves (Arrange keys' rows, by key) moves on screen at once. It was a
 * SubcomposeLayout before, which recomposed a moved row in its slot without measuring the group
 * again, so the list kept its old order on screen while the setting changed underneath.
 */
@Composable
private fun GroupRows(scope: SheetScope, inset: Dp, content: @Composable SheetScope.() -> Unit) {
    // Where each hairline goes, from the last layout; drawn behind the rows, in the gaps between them.
    var lines by remember { mutableStateOf(IntArray(0)) }
    val color = Colors.line
    Layout(
        content = { scope.content() },
        modifier = Modifier.drawBehind {
            val start = inset.toPx()
            val thickness = 1.dp.toPx()
            val (from, to) = if (layoutDirection == LayoutDirection.Rtl) 0f to size.width - start else start to size.width
            for (y in lines) drawRect(color, topLeft = Offset(from, y.toFloat()), size = Size(to - from, thickness))
        },
    ) { measurables, constraints ->
        val rows = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val line = 1.dp.roundToPx()
        val height = rows.sumOf { it.height } + line * (rows.size - 1).coerceAtLeast(0)
        layout(constraints.maxWidth, height) {
            val at = IntArray((rows.size - 1).coerceAtLeast(0))
            var y = 0
            rows.forEachIndexed { i, row ->
                if (i > 0) {
                    at[i - 1] = y
                    y += line
                }
                row.place(0, y)
                y += row.height
            }
            if (!at.contentEquals(lines)) lines = at
        }
    }
}

/** The most a row's detail takes, so a long one wraps rather than crowding out the label. */
private val DETAIL_MAX = 152.dp

/** How much of a row that cannot act right now shows: the platform's disabled content. */
private const val DISABLED_ALPHA = 0.38f

/** Where a row's text starts when it has an icon: the margin, the 24 dp slot and its gap. */
private val ICON_INSET = 56.dp

/**
 * One row of a sheet: an icon in a 24 dp slot, the label, a detail or a check at the end. The row is
 * at least 56 dp high, the platform's one-line list item, and grows as its text wraps: a label takes
 * up to three lines and a detail two, so neither is cut off on a narrow phone or with large text.
 * The detail keeps to the end, at most [DETAIL_MAX] wide, so the label always has the most room.
 * [supporting] is a line under the label that says what the setting does. A group's rows either all
 * have icons or none do, so their labels share one keyline.
 *
 * A row that only changes what its own detail shows - a switch, a pick between two - stays open
 * ([keepOpen]): closing the sheet would take away the one place the change is visible.
 *
 * A row that [opens] another page or dialog - Terminal controls, Arrange keys, Diagnostics - ends in
 * a chevron, as "Terminal controls ›"; a row that steps through values or acts at once has none.
 *
 * A row that cannot act right now - Send a file while a file is on its way - stays in its place,
 * greyed out and not [enabled], so the rows around it do not jump as the state changes.
 *
 * To TalkBack a row is a button; a row that turns something on and off ([checked] given) is a
 * switch, and ends in a Material switch in place of its [detail]'s "On" or "Off"; a [marked] row -
 * the session on screen, the account in use - is selected.
 */
@Composable
internal fun SheetScope.SheetItem(
    label: String,
    color: Color = Colors.ink,
    detail: String? = null,
    marked: Boolean = false,
    icon: Int? = null,
    keepOpen: Boolean = false,
    supporting: String? = null,
    enabled: Boolean = true,
    opens: Boolean = false,
    /** Whether the switch this row is is on; null for a row that is not one. */
    checked: Boolean? = null,
    /** For a test to find the row by: a tag, say. */
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // A switch shows itself in place of its "On" or "Off".
    val words = detail.takeIf { checked == null }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Beside the label while both fit there; under it once the detail would wrap or the label would
        // have to break a word to make room for it, as large text on a narrow phone makes them.
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val stacked = words != null && with(density) {
            val trailing = (if (marked) 32.dp else 0.dp) + (if (opens) 28.dp else 0.dp)
            val room = maxWidth - 32.dp - (if (icon != null) 40.dp else 0.dp) - trailing
            val detailWidth = measurer.measure(words, TextStyle(fontSize = TextStyles.label), maxLines = 1).size.width.toDp()
            val labelRoom = room - 12.dp - detailWidth
            val labelStyle = TextStyle(fontSize = TextStyles.body, fontWeight = if (marked) FontWeight.SemiBold else FontWeight.Normal)
            val widestWord = label.split(' ').maxOf { word -> measurer.measure(word, labelStyle, maxLines = 1).size.width }.toDp()
            detailWidth > minOf(DETAIL_MAX, room / 2) || widestWord > labelRoom ||
                measurer.measure(label, labelStyle, constraints = Constraints(maxWidth = labelRoom.roundToPx().coerceAtLeast(1))).lineCount > 3
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .then(
                    if (checked != null) {
                        Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch) { if (keepOpen) onClick() else close(onClick) }
                    } else {
                        Modifier.clickable(enabled = enabled, role = Role.Button) { if (keepOpen) onClick() else close(onClick) }
                    },
                )
                .semantics { if (marked) selected = true }
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Glyph(icon, if (color == Colors.ink) Colors.muted else color, size = 24.dp)
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    color = color,
                    fontSize = TextStyles.body,
                    fontWeight = if (marked) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = if (stacked) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (supporting != null) {
                    Text(supporting, color = Colors.muted, fontSize = TextStyles.label, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (stacked && words != null) {
                    Text(words, color = Colors.muted, fontSize = TextStyles.label)
                }
            }
            if (words != null && !stacked) {
                Spacer(Modifier.width(12.dp))
                Text(
                    words,
                    color = Colors.muted,
                    fontSize = TextStyles.label,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = DETAIL_MAX),
                )
            }
            if (checked != null) {
                Spacer(Modifier.width(12.dp))
                // The row is the toggle (its semantics above), so the switch itself only shows it.
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled,
                    colors = SwitchDefaults.colors(checkedTrackColor = Colors.accent, checkedThumbColor = Colors.surface),
                    modifier = Modifier.testTag(SHEET_SWITCH_TAG),
                )
            }
            if (marked) {
                Spacer(Modifier.width(12.dp))
                Glyph(R.drawable.ic_check, Colors.accent, size = 20.dp)
            }
            if (opens) {
                Spacer(Modifier.width(8.dp))
                Glyph(R.drawable.ic_chevron_right, Colors.muted, size = 20.dp)
            }
        }
    }
}

/** The Material switch a switch row ([SheetItem]'s checked) ends in, for the screen tests. */
internal const val SHEET_SWITCH_TAG = "sheet-switch"
