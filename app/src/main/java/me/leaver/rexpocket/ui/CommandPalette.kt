package me.leaver.rexpocket.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.PaletteEntry
import me.leaver.rexpocket.core.rankPalette

// The command palette: a sheet over the terminal with a search field and one ranked list of the
// screen's actions, the host's tabs and its sessions. See docs/design/command-palette.md.

/** What a palette row does when it runs, and whether it is where the phone already is. */
internal class PaletteRun(val marked: Boolean = false, val run: () -> Unit)

internal const val PALETTE_FIELD_TAG = "palette-field"

/** Test tag of the palette's row at [index] of what is shown. */
internal fun paletteRowTag(index: Int) = "palette-row-$index"

/**
 * True for the keys that open the palette from a keyboard: Ctrl+Shift+P, and Meta+Shift+P, which is
 * what a Mac keyboard's Command+Shift+P - Rex's own shortcut on the Mac - sends to Android.
 */
internal fun opensPalette(event: android.view.KeyEvent): Boolean =
    event.action == android.view.KeyEvent.ACTION_DOWN && shortcutOf(event) == me.leaver.rexpocket.core.AppShortcut.Palette

/** The most rows shown at once; a query narrows the rest. */
private const val PALETTE_MAX_ROWS = 80

/**
 * The palette over the screen. The field is focused when it opens, so typing searches at once; Up
 * and Down move the selection, Return runs the selected row and Escape closes it. A row closes the
 * palette before it runs, so a dialog it opens gets the keyboard.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CommandPalette(entries: List<PaletteEntry<PaletteRun>>, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(0) }
    val shown = remember(entries, query) { rankPalette(entries, query).take(PALETTE_MAX_ROWS) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ActionSheet(onDismiss = onDismiss, title = stringResource(R.string.palette_title)) {
        val sheet = this
        Column(Modifier.imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp).clip(RoundedCornerShape(24.dp)).background(Colors.surface)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(R.drawable.ic_search, Colors.muted, size = 20.dp)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(stringResource(R.string.palette_hint), color = Colors.muted, fontSize = TextStyles.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            selected = 0
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = Colors.ink, fontSize = TextStyles.body),
                        cursorBrush = SolidColor(Colors.accent),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(PALETTE_FIELD_TAG)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionDown -> {
                                        if (shown.isNotEmpty()) selected = (selected + 1).coerceAtMost(shown.lastIndex)
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        selected = (selected - 1).coerceAtLeast(0)
                                        true
                                    }
                                    Key.Enter, Key.NumPadEnter -> {
                                        shown.getOrNull(selected)?.let { sheet.close(it.value.run) }
                                        true
                                    }
                                    Key.Escape -> {
                                        onDismiss()
                                        true
                                    }
                                    else -> false
                                }
                            },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (shown.isEmpty()) {
                Text(
                    stringResource(R.string.search_none),
                    color = Colors.muted,
                    fontSize = TextStyles.subhead,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
            } else {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(Colors.surface)) {
                    shown.forEachIndexed { index, entry ->
                        PaletteRow(entry, index, selected = index == selected) { sheet.close(entry.value.run) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaletteRow(entry: PaletteEntry<PaletteRun>, index: Int, selected: Boolean, onClick: () -> Unit) {
    val into = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) { if (selected) runCatching { into.bringIntoView() } }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .bringIntoViewRequester(into)
            .background(if (selected) Colors.keyOn else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .testTag(paletteRowTag(index))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            entry.label,
            color = if (entry.value.marked) Colors.accent else Colors.ink,
            fontSize = TextStyles.body,
            fontWeight = if (entry.value.marked) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            listOfNotNull(entry.group, entry.detail).joinToString(" · "),
            color = Colors.muted,
            fontSize = TextStyles.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 168.dp),
        )
    }
}
