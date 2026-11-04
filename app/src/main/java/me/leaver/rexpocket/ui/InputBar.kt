package me.leaver.rexpocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.KeyModifiers
import me.leaver.rexpocket.core.KeyPress
import me.leaver.rexpocket.core.Latch
import me.leaver.rexpocket.core.Mod
import kotlin.math.abs

// The key row under the terminal: the keys a phone keyboard does not have, at the foot of the dock.

/**
 * The keys that steer the program in the pane, in one row where a thumb already rests. The row
 * scrolls when there are more keys than width; the keyboard key waits outside the scroll, so it is
 * always in the same place. This is the same arrangement a desk terminal gives you: the pane keeps
 * the screen, the keys keep the bottom.
 *
 * The keys are ordered by how often a thumb wants them, so the first screenful - esc, C-c and the
 * arrows - is the one an agent asks for most: back out, interrupt, walk its menu. Return already
 * sits on the dock below, so the row does not repeat it. The arrows repeat while held; holding
 * `ctrl` opens the other control chords. `shift`, `ctrl` and `alt` apply to the next key, from this
 * row or from the phone keyboard; a quick second tap keeps one on.
 */
@Composable
internal fun KeyDock(
    mods: KeyModifiers,
    /** True while the program in the pane works or waits: C-c is then the key that stops it. */
    busy: Boolean,
    onMod: (Mod) -> Unit,
    onKey: (key: KeyPress, own: String) -> Unit,
    onPaste: () -> Unit,
    onCopy: () -> Unit,
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
            Key(stringResource(R.string.key_escape)) { onKey(KeyPress("escape", 0), "") }
            // Always named for the chord it sends; red says the program would take it as a stop.
            Key(stringResource(R.string.key_ctrl_c), color = if (busy) Colors.danger else Colors.ink) {
                onKey(KeyPress("key_c", 'c'.code, text = "c"), "ctrl")
            }
            Key(stringResource(R.string.key_left), icon = R.drawable.ic_arrow_left, repeat = true) { onKey(KeyPress("arrow_left", 0), "") }
            Key(stringResource(R.string.key_up), icon = R.drawable.ic_arrow_up, repeat = true) { onKey(KeyPress("arrow_up", 0), "") }
            Key(stringResource(R.string.key_down), icon = R.drawable.ic_arrow_down, repeat = true) { onKey(KeyPress("arrow_down", 0), "") }
            Key(stringResource(R.string.key_right), icon = R.drawable.ic_arrow_right, repeat = true) { onKey(KeyPress("arrow_right", 0), "") }
            Key(stringResource(R.string.key_tab), icon = R.drawable.ic_tab_key) { onKey(KeyPress("tab", 0), "") }
            ModKey(stringResource(R.string.key_shift), mods[Mod.Shift]) { onMod(Mod.Shift) }
            CtrlKey(mods[Mod.Ctrl], onTap = { onMod(Mod.Ctrl) }) { letter ->
                onKey(KeyPress("key_$letter", letter.code, text = letter.toString()), "ctrl")
            }
            ModKey(stringResource(R.string.key_alt), mods[Mod.Alt]) { onMod(Mod.Alt) }
            Key(stringResource(R.string.key_paste), icon = R.drawable.ic_paste) { onPaste() }
            // A slash opens pi's command menu; the arrows then walk it.
            Key("/") { onKey(KeyPress("slash", '/'.code, text = "/"), "") }
            Key(stringResource(R.string.key_copy), icon = R.drawable.ic_copy) { onCopy() }
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

/**
 * The control chords a shell and an agent use, behind a held `ctrl`: interrupt, end of input,
 * suspend, history search, clear, line start and end, delete a word, and a newline.
 */
internal val CTRL_CHORDS = listOf('c', 'd', 'z', 'r', 'l', 'a', 'e', 'w', 'j')

private val KEY_HEIGHT = 44.dp

/** A modifier key: lit while it is on for the next key, filled while it stays on. */
@Composable
private fun ModKey(label: String, latch: Latch, onTap: () -> Unit) {
    val state = modState(latch)
    Key(
        label,
        color = modInk(latch),
        plate = modPlate(latch),
        modifier = Modifier.semantics { stateDescription = state },
        onClick = onTap,
    )
}

@Composable
private fun modState(latch: Latch): String = stringResource(
    when (latch) {
        Latch.Off -> R.string.mod_off
        Latch.Once -> R.string.mod_once
        Latch.Locked -> R.string.mod_locked
    },
)

private fun modInk(latch: Latch): Color = when (latch) {
    Latch.Locked -> Colors.onAccent
    Latch.Once -> Colors.accent
    Latch.Off -> Colors.ink
}

private fun modPlate(latch: Latch): Color = when (latch) {
    Latch.Locked -> Colors.accent
    Latch.Once -> Colors.keyOn
    Latch.Off -> Colors.key
}

/**
 * `ctrl`: a tap turns it on for the next key, like the other modifiers. Held, it opens a grid of the
 * control chords; sliding onto one and lifting sends it, lifting anywhere else sends nothing.
 */
@Composable
private fun CtrlKey(latch: Latch, onTap: () -> Unit, onChord: (Char) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var highlighted by remember { mutableIntStateOf(-1) }
    var anchor by remember { mutableStateOf(Rect.Zero) }
    var popup by remember { mutableStateOf(IntRect.Zero) }
    val tap by rememberUpdatedState(onTap)
    val chord by rememberUpdatedState(onChord)
    val scope = rememberCoroutineScope()
    val config = LocalViewConfiguration.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val pad = with(density) { CHORD_PAD.toPx() }
    val gap = with(density) { CHORD_GAP.toPx() }
    val state = modState(latch)
    val label = stringResource(R.string.key_ctrl)
    val chordsLabel = stringResource(R.string.key_ctrl_chords)

    fun dismiss() {
        open = false
        highlighted = -1
    }

    fun choose(index: Int) {
        if (index in CTRL_CHORDS.indices) chord(CTRL_CHORDS[index])
        dismiss()
    }

    /** The chord under a point in window coordinates, or -1 off the grid. */
    fun hit(x: Float, y: Float): Int {
        val px = x - popup.left - pad
        val py = y - popup.top - pad
        val w = (popup.width - 2 * pad - 2 * gap) / 3
        val h = (popup.height - 2 * pad - 2 * gap) / 3
        if (w <= 0 || h <= 0 || px < 0 || py < 0) return -1
        val col = (px / (w + gap)).toInt()
        val row = (py / (h + gap)).toInt()
        val inside = col in 0..2 && row in 0..2 && px - col * (w + gap) < w && py - row * (h + gap) < h
        return if (inside) row * 3 + col else -1
    }

    val placement = remember(pad) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val edge = pad.toInt()
                val x = (anchorBounds.center.x - popupContentSize.width / 2)
                    .coerceIn(edge, maxOf(edge, windowSize.width - popupContentSize.width - edge))
                val above = anchorBounds.top - popupContentSize.height - edge
                val y = (if (above >= edge) above else anchorBounds.bottom + edge)
                    .coerceIn(edge, maxOf(edge, windowSize.height - popupContentSize.height - edge))
                return IntOffset(x, y).also { popup = IntRect(it, popupContentSize) }
            }
        }
    }

    Box(
        Modifier.height(KEY_HEIGHT).widthIn(min = 48.dp)
            .onGloballyPositioned { anchor = it.boundsInWindow() }
            .clip(RoundedCornerShape(Radius.chip))
            .background(if (pressed || open) Colors.keyOn else modPlate(latch))
            .semantics {
                contentDescription = label
                stateDescription = state
                role = Role.Button
                onClick { tap(); true }
                onLongClick(label = chordsLabel) { open = true; true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressed = true
                    var held = false
                    var moved = false
                    var released = false
                    val timer = scope.launch {
                        delay(config.longPressTimeoutMillis)
                        held = true
                        open = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            val dx = abs(change.position.x - down.position.x)
                            val dy = abs(change.position.y - down.position.y)
                            if (!held && (dx > config.touchSlop || dy > config.touchSlop)) {
                                // A drag before the hold is the row scrolling; let it have the gesture.
                                moved = true
                                timer.cancel()
                                break
                            }
                            if (held) {
                                change.consume()
                                val at = hit(change.position.x + anchor.left, change.position.y + anchor.top)
                                if (at != highlighted && at >= 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                highlighted = at
                            }
                            if (!change.pressed) {
                                released = true
                                timer.cancel()
                                if (held) {
                                    choose(highlighted)
                                } else {
                                    change.consume()
                                    tap()
                                }
                                break
                            }
                        }
                    } finally {
                        timer.cancel()
                        pressed = false
                        // Cancelled mid-hold, such as by the sheet coming up: nothing is sent.
                        if (held && !released) dismiss()
                        if (moved) dismiss()
                    }
                }
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        KeyText(label, modInk(latch))
        if (open) {
            Popup(
                popupPositionProvider = placement,
                onDismissRequest = { dismiss() },
                properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
            ) {
                Column(
                    Modifier.width(CHORD_GRID_WIDTH).floating(RoundedCornerShape(Radius.card))
                        .padding(CHORD_PAD)
                        .semantics { contentDescription = chordsLabel },
                    verticalArrangement = Arrangement.spacedBy(CHORD_GAP),
                ) {
                    for (row in 0 until 3) {
                        Row(Modifier.fillMaxWidth().height(KEY_HEIGHT), horizontalArrangement = Arrangement.spacedBy(CHORD_GAP)) {
                            for (col in 0 until 3) {
                                val index = row * 3 + col
                                val on = index == highlighted
                                val name = chordLabel(CTRL_CHORDS[index])
                                Box(
                                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(Radius.chip))
                                        .background(if (on) Colors.accent else Colors.key)
                                        .clickable(role = Role.Button) { choose(index) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    KeyText(name, if (on) Colors.onAccent else Colors.ink)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** C-r, as a terminal user writes the chord. */
internal fun chordLabel(letter: Char): String = "C-$letter"

private val CHORD_PAD = 8.dp
private val CHORD_GAP = 4.dp
private val CHORD_GRID_WIDTH = 232.dp

/**
 * Sends [onKey] while the key is held: once on the tap, then over and over once the hold passes the
 * long-press time, as a desk keyboard repeats. A drag before that is the row scrolling, and sends
 * nothing; so does a press that ends outside the key.
 */
private fun Modifier.repeating(onKey: () -> Unit, onPressed: (Boolean) -> Unit): Modifier = composed {
    val scope = rememberCoroutineScope()
    val config = LocalViewConfiguration.current
    val latest by rememberUpdatedState(onKey)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            onPressed(true)
            var repeated = false
            val repeater = scope.launch {
                delay(config.longPressTimeoutMillis)
                repeated = true
                while (isActive) {
                    latest()
                    delay(REPEAT_MS)
                }
            }
            try {
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    val dx = abs(change.position.x - down.position.x)
                    val dy = abs(change.position.y - down.position.y)
                    // Moves are left unconsumed, so the row can scroll from a key as well.
                    if (!repeated && (dx > config.touchSlop || dy > config.touchSlop)) break
                    if (!change.pressed) {
                        val inside = change.position.x in 0f..size.width.toFloat() && change.position.y in 0f..size.height.toFloat()
                        if (!repeated && inside) latest()
                        change.consume()
                        break
                    }
                }
            } finally {
                repeater.cancel()
                onPressed(false)
            }
        }
    }
}

/** About fifteen a second: quick enough to cross a menu, slow enough to stop on the right row. */
private const val REPEAT_MS = 65L

/**
 * One key: a soft plate in the key colour. A key that has a platform mark - an arrow, Tab, Return,
 * paste - shows the mark and speaks its [label]; a chord such as C-c is written out in monospace,
 * since that is how a terminal user reads it. A [repeat] key sends again while it is held.
 */
@Composable
internal fun Key(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = Colors.ink,
    plate: Color = Colors.key,
    width: Dp = 48.dp,
    icon: Int? = null,
    repeat: Boolean = false,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val send = onClick
    val press = if (repeat) {
        Modifier
            .semantics { role = Role.Button; onClick { send(); true } }
            .repeating(send) { pressed = it }
    } else {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    }
    Box(
        modifier.height(KEY_HEIGHT).widthIn(min = width).clip(RoundedCornerShape(Radius.chip))
            .background(if (pressed) Colors.keyOn else plate)
            .then(press)
            .then(if (icon != null) Modifier.semantics { contentDescription = label } else Modifier)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Glyph(icon, color, size = 20.dp) else KeyText(label, color)
    }
}

@Composable
private fun KeyText(label: String, color: Color) {
    Text(
        label,
        color = color,
        fontSize = TextStyles.subhead,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
    )
}
