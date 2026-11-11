package me.leaver.rexpocket.ui

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
 * The bar under the terminal, one row as on a desk terminal. Every key is the same size: the
 * first screenful is seven keys and the edge of the next, so a key is as large as the width allows,
 * and the rest of the row scrolls into view. The keyboard key stays pinned at the end, outside the scroll.
 *
 * The first screenful is what an agent asks for most: back out, interrupt, walk its menu, accept.
 * C-c is a key of its own, red while the program works or waits. Then tab, `ctrl` - held, it opens
 * the other control chords - paste and a slash for pi's command menu. `ctrl` applies to the next
 * key, from this row or from the phone keyboard; a quick second tap keeps it on. Return held is
 * shift-Return: a new line in an agent's prompt.
 */
@Composable
internal fun KeyBar(
    mods: KeyModifiers,
    /** True while the program in the pane works or waits: C-c is then the key that stops it. */
    busy: Boolean,
    imeUp: Boolean,
    onMod: (Mod) -> Unit,
    onKey: (key: KeyPress, own: String) -> Unit,
    onNewLine: () -> Unit,
    onPaste: () -> Unit,
    onKeyboard: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Colors.edge))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoxWithConstraints(Modifier.weight(1f).height(KEY_HEIGHT)) {
                // Seven whole keys and a sliver of the eighth, so the row says it goes on.
                val key = Modifier.width((maxWidth - KEY_GAP * KEYS_WHOLE) / (KEYS_WHOLE + KEY_PEEK))
                Row(
                    Modifier.fillMaxSize().horizontalScroll(scroll),
                    horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Key(stringResource(R.string.key_escape), key) { onKey(KeyPress("escape", 0), "") }
                    // Always named for the chord it sends; red says the program would take it as a stop.
                    Key(stringResource(R.string.key_ctrl_c), key, color = if (busy) Colors.danger else Colors.ink) {
                        onKey(KeyPress("key_c", 'c'.code, text = "c"), "ctrl")
                    }
                    Key(stringResource(R.string.key_left), key, icon = R.drawable.ic_arrow_left, repeat = true) { onKey(KeyPress("arrow_left", 0), "") }
                    Key(stringResource(R.string.key_up), key, icon = R.drawable.ic_arrow_up, repeat = true) { onKey(KeyPress("arrow_up", 0), "") }
                    Key(stringResource(R.string.key_down), key, icon = R.drawable.ic_arrow_down, repeat = true) { onKey(KeyPress("arrow_down", 0), "") }
                    Key(stringResource(R.string.key_right), key, icon = R.drawable.ic_arrow_right, repeat = true) { onKey(KeyPress("arrow_right", 0), "") }
                    Key(
                        stringResource(R.string.action_enter),
                        key,
                        icon = R.drawable.ic_return,
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onNewLine()
                        },
                    ) { onKey(KeyPress("enter", 0), "") }
                    Key(stringResource(R.string.key_tab), key, icon = R.drawable.ic_tab_key) { onKey(KeyPress("tab", 0), "") }
                    CtrlKey(mods[Mod.Ctrl], key, onTap = { onMod(Mod.Ctrl) }) { letter ->
                        onKey(KeyPress("key_$letter", letter.code, text = letter.toString()), "ctrl")
                    }
                    Key(stringResource(R.string.key_paste), key, icon = R.drawable.ic_paste) { onPaste() }
                    // A slash opens pi's command menu; the arrows then walk it.
                    Key("/", key) { onKey(KeyPress("slash", '/'.code, text = "/"), "") }
                }
                // The row continues under this fade, so a half-hidden key reads as scrollable.
                if (scroll.canScrollForward) {
                    Box(
                        Modifier.align(Alignment.CenterEnd).width(20.dp).fillMaxHeight()
                            .background(Brush.horizontalGradient(listOf(Colors.clear, Colors.terminal))),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Key(
                stringResource(R.string.key_keyboard),
                Modifier.width(KEYBOARD_KEY_WIDTH),
                plate = Colors.keyOn,
                icon = if (imeUp) R.drawable.ic_keyboard_hide else R.drawable.ic_keyboard,
            ) { onKeyboard() }
        }
    }
}

/**
 * The control chords a shell and an agent use, behind a held `ctrl`: end of input, suspend, history
 * search, clear, line start and end, delete a word, delete to the line start, and a newline.
 * Interrupt is not here: C-c has a key of its own in the row.
 */
internal val CTRL_CHORDS = listOf('d', 'z', 'r', 'l', 'a', 'e', 'w', 'u', 'j')

private val KEY_HEIGHT = 44.dp
private val KEY_GAP = 5.dp
private val KEY_PAD = 2.dp
private val KEYBOARD_KEY_WIDTH = 52.dp

/** How many whole keys the first screenful of the row holds; the rest scroll in after them. */
private const val KEYS_WHOLE = 7

/** How much of the next key shows at the edge, as a fraction of a key. */
private const val KEY_PEEK = 0.35f

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
private fun CtrlKey(latch: Latch, modifier: Modifier, onTap: () -> Unit, onChord: (Char) -> Unit) {
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
        modifier.height(KEY_HEIGHT)
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
            .padding(horizontal = KEY_PAD),
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Key(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = Colors.ink,
    plate: Color = Colors.key,
    icon: Int? = null,
    repeat: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val send = onClick
    val press = if (repeat) {
        Modifier
            .semantics { role = Role.Button; onClick { send(); true } }
            .repeating(send) { pressed = it }
    } else {
        Modifier.combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick)
    }
    Box(
        modifier.height(KEY_HEIGHT).clip(RoundedCornerShape(Radius.chip))
            .background(if (pressed) Colors.keyOn else plate)
            .then(press)
            .then(if (icon != null) Modifier.semantics { contentDescription = label } else Modifier)
            .padding(horizontal = KEY_PAD),
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
        fontSize = TextStyles.label,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
    )
}
