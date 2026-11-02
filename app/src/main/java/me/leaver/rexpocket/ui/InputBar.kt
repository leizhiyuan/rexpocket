package me.leaver.rexpocket.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.PocketSession

// The key row under the terminal: the keys a phone keyboard does not have, at the foot of the dock.

/**
 * The keys that steer the program in the pane, in one row where a thumb already rests. The row
 * scrolls when there are more keys than width; the keyboard key waits outside the scroll, so it is
 * always in the same place. This is the same arrangement a desk terminal gives you: the pane keeps
 * the screen, the keys keep the bottom.
 */
@Composable
internal fun KeyDock(
    ctrl: Boolean,
    locked: Boolean,
    dpad: Boolean,
    stopping: Boolean,
    onCtrl: () -> Unit,
    onKey: (code: String, mods: String, codepoint: Int) -> Unit,
    onPaste: () -> Unit,
    onCopy: () -> Unit,
    onDpad: () -> Unit,
    onKeyboard: () -> Unit,
) {
    val keyboardLabel = stringResource(R.string.key_keyboard)
    val scroll = rememberScrollState()
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Bounded, or the fade inside would take the whole screen's height and starve the pane.
        Box(Modifier.weight(1f).height(44.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Key(stringResource(R.string.key_escape)) { onKey("escape", "", 0) }
            Key(
                stringResource(R.string.key_ctrl),
                color = when {
                    locked -> Colors.onAccent
                    ctrl -> Colors.accent
                    else -> Colors.muted
                },
                plate = when {
                    locked -> Colors.accent
                    ctrl -> Colors.keyOn
                    else -> Colors.key
                },
                onClick = onCtrl,
            )
            Key(stringResource(R.string.key_tab)) { onKey("tab", "", 0) }
            Key(stringResource(R.string.key_enter)) { onKey("enter", "", 0) }
            // A slash opens pi's command menu; the arrows then walk it.
            Key("/") { onKey("slash", "", '/'.code) }
            Key(stringResource(R.string.key_left)) { onKey("arrow_left", "", 0) }
            Key(stringResource(R.string.key_up)) { onKey("arrow_up", "", 0) }
            Key(stringResource(R.string.key_down)) { onKey("arrow_down", "", 0) }
            Key(stringResource(R.string.key_right)) { onKey("arrow_right", "", 0) }
            // While a program works or waits for an answer, this is the key that stops it, so it is
            // named after what it does then rather than after the chord it sends.
            Key(
                stringResource(if (stopping) R.string.header_stop else R.string.key_ctrl_c),
                color = if (stopping) Colors.danger else Colors.muted,
            ) { onKey("key_c", "ctrl", 99) }
            Key(stringResource(R.string.key_ctrl_d)) { onKey("key_d", "ctrl", 100) }
            Key(stringResource(R.string.key_ctrl_z)) { onKey("key_z", "ctrl", 122) }
            Key(stringResource(R.string.key_ctrl_l)) { onKey("key_l", "ctrl", 108) }
            Key(stringResource(R.string.key_paste)) { onPaste() }
            Key(stringResource(R.string.key_copy)) { onCopy() }
            Key(
                stringResource(if (dpad) R.string.key_dpad_on else R.string.key_dpad),
                color = if (dpad) Colors.accent else Colors.muted,
                plate = if (dpad) Colors.keyOn else Colors.key,
                onClick = onDpad,
            )
        }
            // The row continues under this fade, so a half-visible key reads as scrollable.
            if (scroll.canScrollForward) {
                Box(
                    Modifier.align(Alignment.CenterEnd).width(16.dp).fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(Colors.clear, Colors.bg))),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.sizeIn(minWidth = 40.dp, minHeight = 44.dp)
                .clip(RoundedCornerShape(Radius.chip))
                .clickable(role = Role.Button, onClick = onKeyboard)
                .semantics { contentDescription = keyboardLabel },
            contentAlignment = Alignment.Center,
        ) {
            KeyboardIcon(Colors.ink)
        }
    }
}

@Composable
internal fun Dpad(onAct: (suspend PocketSession.() -> Unit) -> Unit) {
    fun key(code: String) = onAct { sendKey(code) }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Key(stringResource(R.string.key_backspace), width = 64.dp) { key("backspace") }
            Key(stringResource(R.string.key_up)) { key("arrow_up") }
            Key(stringResource(R.string.key_enter)) { key("enter") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Key(stringResource(R.string.key_left)) { key("arrow_left") }
            Key(stringResource(R.string.key_down)) { key("arrow_down") }
            Key(stringResource(R.string.key_right)) { key("arrow_right") }
        }
    }
}

/** One key: a soft plate in the key colour, with a label in the quiet grey the desk uses. */
@Composable
internal fun Key(
    label: String,
    color: Color = Colors.muted,
    plate: Color = Colors.key,
    width: Dp = 40.dp,
    onClick: () -> Unit,
) {
    Box(
        Modifier.height(44.dp).widthIn(min = width).clip(RoundedCornerShape(Radius.chip)).background(plate)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = color,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}

/**
 * The keyboard key, drawn rather than set as a glyph: a soft outline with keys in it, the same mark
 * the platform's own keyboard switch uses.
 */
@Composable
internal fun KeyboardIcon(tint: Color) {
    Canvas(Modifier.size(26.dp)) {
        drawRoundRect(
            color = tint,
            topLeft = Offset(1.5.dp.toPx(), 5.dp.toPx()),
            size = Size(size.width - 3.dp.toPx(), 16.dp.toPx()),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = Stroke(1.6.dp.toPx()),
        )
        for (row in 0..1) {
            for (col in 0..4) {
                drawCircle(
                    color = tint,
                    radius = 0.9.dp.toPx(),
                    center = Offset((5.5f + col * 3.9f).dp.toPx(), (9.5f + row * 3.2f).dp.toPx()),
                )
            }
        }
        drawRoundRect(
            color = tint,
            topLeft = Offset(8.dp.toPx(), 15.5.dp.toPx()),
            size = Size(10.dp.toPx(), 2.dp.toPx()),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
    }
}
