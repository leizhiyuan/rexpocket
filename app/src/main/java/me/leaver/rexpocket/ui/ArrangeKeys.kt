package me.leaver.rexpocket.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.BarKey
import me.leaver.rexpocket.core.KeyArrangement
import me.leaver.rexpocket.core.Pill
import kotlin.math.roundToInt

// Settings › Terminal controls › Arrange keys: the order of the key bar's keys and which show.
// See docs/design/terminal-controls.md.

/**
 * Each pill's keys in their order, one row each: a handle to drag it to its place, up and down for
 * one step at a time (and for TalkBack, where a drag is awkward), and an eye to show or hide it. The
 * keyboard key has no eye: it is the one way back to the phone keyboard. Every change applies at
 * once; Reset puts the bar back as it first came.
 */
@Composable
internal fun ArrangeKeysSheet(arrangement: KeyArrangement, onArrangement: (KeyArrangement) -> Unit, onDismiss: () -> Unit) {
    val current by rememberUpdatedState(arrangement)
    val change by rememberUpdatedState(onArrangement)
    ActionSheet(onDismiss = onDismiss, title = stringResource(R.string.arrange_title)) {
        for (pill in Pill.entries) {
            Group(stringResource(if (pill == Pill.Keys) R.string.arrange_group_keys else R.string.arrange_group_rest)) {
                val order = arrangement.order(pill)
                order.forEachIndexed { index, barKey ->
                    key(barKey) {
                        ArrangeRow(
                            barKey = barKey,
                            shown = arrangement.isShown(barKey),
                            first = index == 0,
                            last = index == order.lastIndex,
                            onMove = { by -> change(current.move(barKey, by)) },
                            onShow = { change(current.show(barKey, it)) },
                        )
                    }
                }
            }
        }
        Group {
            SheetItem(stringResource(R.string.arrange_reset), Colors.accent, keepOpen = true) { change(KeyArrangement()) }
        }
    }
}

@Composable
private fun ArrangeRow(barKey: BarKey, shown: Boolean, first: Boolean, last: Boolean, onMove: (Int) -> Unit, onShow: (Boolean) -> Unit) {
    val name = barKeyLabel(barKey)
    val rowPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
    var offset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val move by rememberUpdatedState(onMove)
    val canUp by rememberUpdatedState(!first)
    val canDown by rememberUpdatedState(!last)
    Row(
        Modifier.fillMaxWidth().height(ROW_HEIGHT)
            .graphicsLayer { translationY = if (dragging) offset else 0f }
            .testTag("arrange-${barKey.pref}")
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Dragged, the row follows the finger and takes the place of each row it passes half of.
        val dragLabel = stringResource(R.string.arrange_drag, name)
        Box(
            Modifier.size(48.dp)
                .semantics { contentDescription = dragLabel }
                .pointerInput(rowPx) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            offset = 0f
                        },
                        onDragEnd = { dragging = false; offset = 0f },
                        onDragCancel = { dragging = false; offset = 0f },
                    ) { change, dy ->
                        change.consume()
                        offset += dy
                        val steps = (offset / rowPx).roundToInt()
                        val allowed = when {
                            steps < 0 && !canUp -> 0
                            steps > 0 && !canDown -> 0
                            else -> steps.coerceIn(-1, 1)
                        }
                        if (allowed != 0) {
                            move(allowed)
                            offset -= allowed * rowPx
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Glyph(R.drawable.ic_drag_handle, Colors.muted) }
        Text(
            name,
            color = if (shown) Colors.ink else Colors.muted,
            fontSize = TextStyles.body,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        StepButton(R.drawable.ic_arrow_up, stringResource(R.string.arrange_move_up, name), enabled = !first) { onMove(-1) }
        StepButton(R.drawable.ic_arrow_down, stringResource(R.string.arrange_move_down, name), enabled = !last) { onMove(1) }
        if (barKey.hideable) {
            val label = stringResource(if (shown) R.string.arrange_hide else R.string.arrange_show, name)
            val state = stringResource(if (shown) R.string.arrange_shown else R.string.arrange_hidden)
            Box(
                Modifier.size(44.dp)
                    .clickable { onShow(!shown) }
                    .semantics {
                        contentDescription = label
                        stateDescription = state
                        role = Role.Switch
                    },
                contentAlignment = Alignment.Center,
            ) { Glyph(if (shown) R.drawable.ic_eye_filled else R.drawable.ic_eye, if (shown) Colors.accent else Colors.muted) }
        } else {
            // The keyboard key stays: without it a keyboard put away has no way back.
            val always = stringResource(R.string.arrange_always)
            Box(Modifier.size(44.dp).semantics { contentDescription = always }, contentAlignment = Alignment.Center) {
                Glyph(R.drawable.ic_eye_filled, Colors.line)
            }
        }
    }
}

@Composable
private fun StepButton(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Glyph(icon, if (enabled) Colors.muted else Colors.line) }
}

/** A key's name in the list: what TalkBack calls it on the bar. */
@Composable
internal fun barKeyLabel(key: BarKey): String = stringResource(
    when (key) {
        BarKey.Escape -> R.string.key_escape
        BarKey.CtrlC -> R.string.key_ctrl_c
        BarKey.Tab -> R.string.key_tab
        BarKey.Enter -> R.string.action_enter
        BarKey.Ctrl -> R.string.key_ctrl
        BarKey.Shift -> R.string.key_shift
        BarKey.Alt -> R.string.key_alt
        BarKey.More -> R.string.key_more
        BarKey.Paste -> R.string.key_paste
        BarKey.Attach -> R.string.upload_attach
        BarKey.Slash -> R.string.arrange_slash
        BarKey.Arrows -> R.string.key_arrows
        BarKey.Keyboard -> R.string.key_keyboard
        BarKey.FullScreen -> R.string.action_full_screen
    },
)

private val ROW_HEIGHT = 56.dp
