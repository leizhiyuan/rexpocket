package me.leaver.rexpocket.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.TabTextSizes
import me.leaver.rexpocket.core.TextScale

// The terminal's text size where a person can find it without knowing the pinch: the ⋮ menu's Text
// size row for the tab on screen, a hint shown once over the pane, and the default for new tabs in
// Settings › Appearance and the Terminal font dialog. See terminal-controls.md, "Text size".

/** Each tab's own size while the app runs, read by the screen as it draws; see [TabTextSizes]. */
class TabTextSizeState {
    var sizes by mutableStateOf(TabTextSizes())
}

internal const val TEXT_SIZE_ROW_TAG = "text-size-row"
internal const val TEXT_SIZE_SMALLER_TAG = "text-size-smaller"
internal const val TEXT_SIZE_BIGGER_TAG = "text-size-bigger"
internal const val TEXT_SIZE_RESET_TAG = "text-size-reset"
internal const val PINCH_HINT_TAG = "pinch-hint"

/**
 * A sheet row for the tab's text size: its name and size, the grid it gives the pane
 * ("110% · 52×30"), then − and + and Reset. It stays open, since what it changes shows on the row
 * itself and on the pane behind the sheet.
 */
@Composable
internal fun SheetScope.TextSizeRow(
    scale: Float,
    default: Float,
    grid: Pair<Int, Int>?,
    onScale: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val percent = TextScale.percent(scale)
    val detail = if (grid != null) stringResource(R.string.text_size_detail, percent, grid.first, grid.second) else stringResource(R.string.text_size_percent, percent)
    val spoken = if (grid != null) stringResource(R.string.text_size_state, percent, grid.first, grid.second) else stringResource(R.string.text_size_state_short, percent)
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).testTag(TEXT_SIZE_ROW_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(R.drawable.ic_text_fields, Colors.muted, size = 24.dp)
        Spacer(Modifier.width(16.dp))
        // Read as one: "Text size, 110 percent, 52 columns by 30 rows", said again as it changes.
        Column(
            Modifier.weight(1f).semantics(mergeDescendants = true) {
                stateDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            Text(stringResource(R.string.text_size_row), color = Colors.ink, fontSize = TextStyles.body, maxLines = 2)
            Text(detail, color = Colors.muted, fontSize = TextStyles.label, maxLines = 1)
        }
        TextSizeButtons(scale, default, onScale, onReset)
    }
}

/** − and +, a step each, and Reset to [default] while the size is anything else. */
@Composable
private fun TextSizeButtons(scale: Float, default: Float, onScale: (Float) -> Unit, onReset: () -> Unit) {
    RoundButton(
        R.drawable.ic_remove,
        stringResource(R.string.palette_font_smaller),
        { onScale(TextScale.smaller(scale)) },
        modifier = Modifier.testTag(TEXT_SIZE_SMALLER_TAG),
        size = 44.dp,
        enabled = scale > TextScale.MIN + 0.001f,
        tint = if (scale > TextScale.MIN + 0.001f) Colors.ink else Colors.line,
    )
    RoundButton(
        R.drawable.ic_add,
        stringResource(R.string.palette_font_bigger),
        { onScale(TextScale.bigger(scale)) },
        modifier = Modifier.testTag(TEXT_SIZE_BIGGER_TAG),
        size = 44.dp,
        enabled = scale < TextScale.MAX - 0.001f,
        tint = if (scale < TextScale.MAX - 0.001f) Colors.ink else Colors.line,
    )
    val spokenReset = stringResource(R.string.text_size_reset_a11y)
    val changed = !TextScale.same(scale, default)
    TextButton(
        onClick = onReset,
        enabled = changed,
        modifier = Modifier.widthIn(min = 48.dp).testTag(TEXT_SIZE_RESET_TAG).semantics { contentDescription = spokenReset },
    ) {
        Text(stringResource(R.string.text_size_reset), color = if (changed) Colors.accent else Colors.line, fontSize = TextStyles.label)
    }
}

/**
 * The size new tabs start at, as a line of a dialog or a page: "Text size for new tabs: 110%",
 * − and +, and Reset to the app's own 100%.
 */
@Composable
internal fun DefaultTextSize(scale: Float, onScale: (Float) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.terminal_font_size, TextScale.percent(scale)),
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextSizeButtons(scale, TextScale.DEFAULT, onScale) { onScale(TextScale.DEFAULT) }
    }
}

/** Settings › Appearance's Terminal text size: the default for new tabs, in a dialog of its own. */
@Composable
internal fun DefaultTextSizeDialog(scale: Float, onScale: (Float) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Colors.surface,
        title = { Text(stringResource(R.string.appearance_terminal_text_size), color = Colors.ink) },
        text = {
            Column {
                Text(stringResource(R.string.appearance_terminal_text_size_hint), color = Colors.muted, fontSize = TextStyles.label)
                Spacer(Modifier.padding(top = 8.dp))
                DefaultTextSize(scale, onScale)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok), color = Colors.accent) } },
    )
}

/**
 * Shown once over the foot of the pane: a pinch changes the text size, and Return held is a new
 * line - the two gestures nothing on screen names. It goes with Got it, a pinch or the Text size
 * row, and does not come back.
 */
@Composable
internal fun PinchHint(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.widthIn(max = 420.dp).padding(horizontal = 16.dp, vertical = 8.dp)
            .floating(RoundedCornerShape(Radius.card))
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .testTag(PINCH_HINT_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(R.drawable.ic_text_fields, Colors.accent, size = 20.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.pinch_hint),
            color = Colors.ink,
            fontSize = TextStyles.label,
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f, fill = false),
        )
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.pinch_hint_dismiss), color = Colors.accent, fontSize = TextStyles.label) }
    }
}
