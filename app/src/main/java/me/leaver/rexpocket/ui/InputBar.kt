package me.leaver.rexpocket.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight


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
) {
    val scroll = rememberScrollState()
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Bounded, or the fade inside would take the whole screen's height and starve the pane.
        Box(Modifier.weight(1f).height(KEY_HEIGHT)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Key(stringResource(R.string.key_escape)) { onKey("escape", "", 0) }
            Key(
                stringResource(R.string.key_ctrl),
                color = when {
                    locked -> Colors.onAccent
                    ctrl -> Colors.accent
                    else -> Colors.ink
                },
                plate = when {
                    locked -> Colors.accent
                    ctrl -> Colors.keyOn
                    else -> Colors.key
                },
                onClick = onCtrl,
            )
            Key(stringResource(R.string.key_tab), icon = R.drawable.ic_tab_key) { onKey("tab", "", 0) }
            // A slash opens pi's command menu; the arrows then walk it.
            Key("/") { onKey("slash", "", '/'.code) }
            Key(stringResource(R.string.key_left), icon = R.drawable.ic_arrow_left) { onKey("arrow_left", "", 0) }
            Key(stringResource(R.string.key_up), icon = R.drawable.ic_arrow_up) { onKey("arrow_up", "", 0) }
            Key(stringResource(R.string.key_down), icon = R.drawable.ic_arrow_down) { onKey("arrow_down", "", 0) }
            Key(stringResource(R.string.key_right), icon = R.drawable.ic_arrow_right) { onKey("arrow_right", "", 0) }
            // While a program works or waits for an answer, this is the key that stops it, so it is
            // named after what it does then rather than after the chord it sends.
            Key(
                stringResource(if (stopping) R.string.header_stop else R.string.key_ctrl_c),
                color = if (stopping) Colors.danger else Colors.ink,
            ) { onKey("key_c", "ctrl", 99) }
            Key(stringResource(R.string.key_ctrl_d)) { onKey("key_d", "ctrl", 100) }
            Key(stringResource(R.string.key_ctrl_z)) { onKey("key_z", "ctrl", 122) }
            Key(stringResource(R.string.key_ctrl_l)) { onKey("key_l", "ctrl", 108) }
            Key(stringResource(R.string.key_paste), icon = R.drawable.ic_paste) { onPaste() }
            Key(stringResource(R.string.key_copy), icon = R.drawable.ic_copy) { onCopy() }
            Key(
                stringResource(if (dpad) R.string.key_dpad_on else R.string.key_dpad),
                color = if (dpad) Colors.accent else Colors.ink,
                plate = if (dpad) Colors.keyOn else Colors.key,
                icon = R.drawable.ic_dpad,
                onClick = onDpad,
            )
        }
            // The row continues under this fade, so a half-visible key reads as scrollable.
            if (scroll.canScrollForward) {
                Box(
                    Modifier.align(Alignment.CenterEnd).width(24.dp).fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(Colors.clear, Colors.terminal))),
                )
            }
        }
    }
}

@Composable
internal fun Dpad(onAct: (suspend PocketSession.() -> Unit) -> Unit) {
    fun key(code: String) = onAct { sendKey(code) }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Key(stringResource(R.string.key_backspace), width = DPAD_KEY, icon = R.drawable.ic_backspace) { key("backspace") }
            Key(stringResource(R.string.key_up), width = DPAD_KEY, icon = R.drawable.ic_arrow_up) { key("arrow_up") }
            Key(stringResource(R.string.key_enter), width = DPAD_KEY, icon = R.drawable.ic_return) { key("enter") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Key(stringResource(R.string.key_left), width = DPAD_KEY, icon = R.drawable.ic_arrow_left) { key("arrow_left") }
            Key(stringResource(R.string.key_down), width = DPAD_KEY, icon = R.drawable.ic_arrow_down) { key("arrow_down") }
            Key(stringResource(R.string.key_right), width = DPAD_KEY, icon = R.drawable.ic_arrow_right) { key("arrow_right") }
        }
    }
}

private val KEY_HEIGHT = 44.dp
private val DPAD_KEY = 64.dp

/**
 * One key: a soft plate in the key colour. A key that has a platform mark - an arrow, Tab, Return,
 * paste - shows the mark and speaks its [label]; a chord such as C-c is written out in monospace,
 * since that is how a terminal user reads it.
 */
@Composable
internal fun Key(
    label: String,
    color: Color = Colors.ink,
    plate: Color = Colors.key,
    width: Dp = 48.dp,
    icon: Int? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier.height(KEY_HEIGHT).widthIn(min = width).clip(RoundedCornerShape(Radius.chip)).background(plate)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (icon != null) Modifier.semantics { contentDescription = label } else Modifier)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Glyph(icon, color, size = 20.dp)
        } else {
            Text(
                label,
                color = color,
                fontSize = TextStyles.subhead,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}
