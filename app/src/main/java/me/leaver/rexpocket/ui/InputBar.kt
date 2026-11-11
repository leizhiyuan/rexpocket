package me.leaver.rexpocket.ui

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

// The bar under the terminal: the keys a phone keyboard does not have, in floating groups.

/**
 * The bar under the terminal, after Rex for iOS: two floating groups on the terminal's paper. The
 * keys a phone keyboard lacks share the wide pill, which shows what fits and scrolls to the rest;
 * the arrow stick and the keyboard key keep the right, always in the same place.
 *
 * The pill starts with what an agent asks for most: back out, interrupt, complete, accept, `ctrl`.
 * Paste and a slash for pi's command menu follow off the edge. C-c is red while the program works
 * or waits. `ctrl` applies to the next key, from this row or from the phone keyboard; a quick second
 * tap keeps it on, and holding it opens the other control chords. Return held is shift-Return: a
 * new line in an agent's prompt.
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
    val pill = RoundedCornerShape(50)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(GROUP_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(BAR_HEIGHT).floating(pill)) {
            Row(
                Modifier.fillMaxSize().horizontalScroll(scroll).padding(horizontal = PILL_PAD),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val key = Modifier.size(KEY_SIZE)
                Key(stringResource(R.string.key_escape), key) { onKey(KeyPress("escape", 0), "") }
                // Always named for the chord it sends; red says the program would take it as a stop.
                Key(stringResource(R.string.key_ctrl_c), key, color = if (busy) Colors.danger else Colors.ink) {
                    onKey(KeyPress("key_c", 'c'.code, text = "c"), "ctrl")
                }
                Key(stringResource(R.string.key_tab), key, icon = R.drawable.ic_tab_key) { onKey(KeyPress("tab", 0), "") }
                Key(
                    stringResource(R.string.action_enter),
                    key,
                    icon = R.drawable.ic_return,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onNewLine()
                    },
                ) { onKey(KeyPress("enter", 0), "") }
                CtrlKey(mods[Mod.Ctrl], key, onTap = { onMod(Mod.Ctrl) }) { letter ->
                    onKey(KeyPress("key_$letter", letter.code, text = letter.toString()), "ctrl")
                }
                Key(stringResource(R.string.key_paste), key, icon = R.drawable.ic_paste) { onPaste() }
                // A slash opens pi's command menu; the stick then walks it.
                Key("/", key) { onKey(KeyPress("slash", '/'.code, text = "/"), "") }
            }
            // The pill goes on under this fade, so the key half under it reads as more to swipe to.
            if (scroll.canScrollForward) {
                Box(
                    Modifier.align(Alignment.CenterEnd).width(28.dp).fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(Colors.clear, Colors.raised))),
                )
            }
            if (scroll.canScrollBackward) {
                Box(
                    Modifier.align(Alignment.CenterStart).width(28.dp).fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(Colors.raised, Colors.clear))),
                )
            }
        }
        Row(
            Modifier.height(BAR_HEIGHT).floating(pill).padding(horizontal = PILL_PAD),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArrowStick(Modifier.size(KEY_SIZE)) { code -> onKey(KeyPress(code, 0), "") }
            Key(
                stringResource(R.string.key_keyboard),
                Modifier.size(KEY_SIZE),
                icon = if (imeUp) R.drawable.ic_keyboard_hide else R.drawable.ic_keyboard,
            ) { onKeyboard() }
        }
    }
}

/**
 * The four arrows behind one key. A tap pins the arrow pad above it, four keys laid out as on a
 * desk keyboard, each sending its arrow on a tap and repeating while held; it stays until the key
 * is tapped again, so a menu can be walked one tap at a time.
 *
 * Pressed and slid, the key is a stick instead: the arrow goes once as the finger crosses into its
 * direction, and only once - back through the middle and out again sends the next. Pushed on out
 * to the rim, it repeats until the finger eases back. A slide across a diagonal keeps the arrow it
 * had a little longer, so the direction does not flicker between two.
 *
 * The stick's face shows above the bar while it is held, so the finger does not hide which way it
 * points. The gesture is the stick's own, so the bar and the pane never take it for a scroll.
 */
@Composable
private fun ArrowStick(modifier: Modifier, onArrow: (String) -> Unit) {
    var held by remember { mutableStateOf(false) }
    var steering by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(false) }
    var direction by remember { mutableStateOf<String?>(null) }
    var knob by remember { mutableStateOf(Offset.Zero) }
    val arrow by rememberUpdatedState(onArrow)
    val haptics = LocalHapticFeedback.current
    val config = LocalViewConfiguration.current
    val deadZone = with(LocalDensity.current) { STICK_DEAD_ZONE.toPx() }.coerceAtLeast(config.touchSlop)
    val label = stringResource(R.string.key_arrows)
    val pinnedState = stringResource(if (pinned) R.string.arrows_pinned else R.string.mod_off)
    val names = listOf(
        stringResource(R.string.key_left) to "arrow_left",
        stringResource(R.string.key_up) to "arrow_up",
        stringResource(R.string.key_down) to "arrow_down",
        stringResource(R.string.key_right) to "arrow_right",
    )
    val edge = with(LocalDensity.current) { POPUP_EDGE.roundToPx() }
    val above = remember(edge) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                // Kept off the screen's edge, where the system's back gesture lives.
                val x = (anchorBounds.center.x - popupContentSize.width / 2)
                    .coerceIn(edge, maxOf(edge, windowSize.width - popupContentSize.width - edge))
                val y = (anchorBounds.top - popupContentSize.height - anchorBounds.height / 3).coerceAtLeast(0)
                return IntOffset(x, y)
            }
        }
    }
    Box(
        modifier.clip(CircleShape)
            .background(if (held || pinned) Colors.keyOn else Colors.clear)
            .semantics {
                contentDescription = label
                stateDescription = pinnedState
                role = Role.Button
                onClick { pinned = !pinned; true }
                customActions = names.map { (name, code) -> CustomAccessibilityAction(name) { arrow(code); true } }
            }
            .pointerInput(deadZone) {
                coroutineScope {
                    awaitEachGesture {
                        // Taken before the parents see it, so a slide is the stick's, not a scroll.
                        val down = awaitFirstDown(pass = PointerEventPass.Initial)
                        down.consume()
                        held = true
                        steering = false
                        direction = null
                        knob = Offset.Zero
                        var repeating: Job? = null
                        var tapped = false
                        try {
                            while (true) {
                                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) {
                                    // A press that never became a slide is a tap: it pins the pad,
                                    // or puts it away.
                                    val inside = change.position.x in 0f..size.width.toFloat() &&
                                        change.position.y in 0f..size.height.toFloat()
                                    tapped = !steering && inside
                                    break
                                }
                                val delta = change.position - down.position
                                if (!steering && delta.getDistance() < config.touchSlop) continue
                                steering = true
                                knob = delta
                                val next = stickDirection(delta, deadZone, direction)
                                if (next != direction) {
                                    repeating?.cancel()
                                    repeating = null
                                    direction = next
                                    if (next == null) continue
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    arrow(next)
                                }
                                val current = direction ?: continue
                                val atRim = stickRepeats(delta, deadZone)
                                if (atRim && repeating == null) {
                                    repeating = launch {
                                        delay(STICK_REPEAT_DELAY_MS)
                                        while (isActive) {
                                            arrow(current)
                                            delay(STICK_REPEAT_MS)
                                        }
                                    }
                                } else if (!atRim && repeating != null) {
                                    repeating.cancel()
                                    repeating = null
                                }
                            }
                        } finally {
                            repeating?.cancel()
                            held = false
                            steering = false
                            direction = null
                            knob = Offset.Zero
                        }
                        if (tapped) pinned = !pinned
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(R.drawable.ic_joystick, if (held || pinned) Colors.accent else Colors.ink, size = 24.dp)
        if (steering) {
            Popup(popupPositionProvider = above, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
                StickFace(direction, knob, deadZone)
            }
        } else if (pinned) {
            // Not focusable, so the phone keyboard stays up with it; it goes when the key is tapped.
            Popup(popupPositionProvider = above, properties = PopupProperties(focusable = false)) {
                ArrowPad { code -> arrow(code) }
            }
        }
    }
}

/** The pinned arrow pad: up over left, down and right, as the arrows sit on a desk keyboard. */
@Composable
private fun ArrowPad(onArrow: (String) -> Unit) {
    Column(
        Modifier.floating(RoundedCornerShape(Radius.card)).padding(PAD_INSET),
        verticalArrangement = Arrangement.spacedBy(PAD_GAP),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PadKey("arrow_up", R.drawable.ic_arrow_up, R.string.key_up, onArrow)
        Row(horizontalArrangement = Arrangement.spacedBy(PAD_GAP)) {
            PadKey("arrow_left", R.drawable.ic_arrow_left, R.string.key_left, onArrow)
            PadKey("arrow_down", R.drawable.ic_arrow_down, R.string.key_down, onArrow)
            PadKey("arrow_right", R.drawable.ic_arrow_right, R.string.key_right, onArrow)
        }
    }
}

/**
 * One arrow of the pad: sent as the finger lands, and again and again once it has stayed down past
 * the repeat delay, until it lifts. Holding is deliberate here, so repeating is what it asks for.
 */
@Composable
private fun PadKey(code: String, icon: Int, name: Int, onArrow: (String) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val arrow by rememberUpdatedState(onArrow)
    val haptics = LocalHapticFeedback.current
    val label = stringResource(name)
    Box(
        Modifier.size(PAD_KEY).clip(RoundedCornerShape(Radius.chip))
            .background(if (pressed) Colors.keyOn else Colors.key)
            .semantics { contentDescription = label; role = Role.Button; onClick { arrow(code); true } }
            .pointerInput(code) {
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        pressed = true
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        arrow(code)
                        val repeating = launch {
                            delay(STICK_REPEAT_DELAY_MS)
                            while (isActive) {
                                arrow(code)
                                delay(STICK_REPEAT_MS)
                            }
                        }
                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                            }
                        } finally {
                            repeating.cancel()
                            pressed = false
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(icon, if (pressed) Colors.accent else Colors.ink, size = 24.dp)
    }
}

/** The arrow a slide of [delta] points at, or null inside the middle. */
internal fun stickDirection(delta: Offset, deadZone: Float, previous: String?): String? {
    if (delta.getDistance() < deadZone) return null
    val x = abs(delta.x)
    val y = abs(delta.y)
    val horizontal = previous == "arrow_left" || previous == "arrow_right"
    val vertical = previous == "arrow_up" || previous == "arrow_down"
    val across = when {
        horizontal -> x >= y * STICK_HYSTERESIS
        vertical -> y < x * STICK_HYSTERESIS
        else -> x > y
    }
    return if (across) {
        if (delta.x < 0) "arrow_left" else "arrow_right"
    } else {
        if (delta.y < 0) "arrow_up" else "arrow_down"
    }
}

/** Whether a slide of [delta] has the stick out at its rim, where the arrow it points at repeats. */
internal fun stickRepeats(delta: Offset, deadZone: Float): Boolean = delta.getDistance() >= deadZone * STICK_RIM

/** The stick seen from above: a ring, the quarter it points at in the action colour, and the knob. */
@Composable
private fun StickFace(direction: String?, knob: Offset, deadZone: Float) {
    val ring = Colors.raised
    val edge = Colors.edge
    val lit = Colors.accent
    val ink = Colors.ink
    Canvas(Modifier.size(STICK_FACE).floating()) {
        val r = size.minDimension / 2
        drawCircle(ring, r)
        if (direction != null) {
            val start = when (direction) {
                "arrow_right" -> -45f
                "arrow_down" -> 45f
                "arrow_left" -> 135f
                else -> 225f
            }
            drawArc(lit.copy(alpha = 0.35f), startAngle = start, sweepAngle = 90f, useCenter = true)
        }
        drawCircle(edge, r * 0.36f)
        // The knob follows the finger out to the rim, where the arrow repeats; it turns the action
        // colour there, so a repeat is never a surprise.
        val distance = knob.getDistance()
        val reach = r * 0.62f
        val rim = deadZone * STICK_RIM
        val travel = if (distance > 0f) knob * (minOf(distance, rim) / rim * reach / distance) else Offset.Zero
        val repeating = direction != null && distance >= rim
        drawCircle(edge, reach, style = Stroke(width = 1.dp.toPx()))
        drawCircle(if (repeating) lit else if (direction != null) lit.copy(alpha = 0.7f) else ink, r * 0.18f, center + travel)
    }
}

/**
 * The control chords a shell and an agent use, behind a held `ctrl`: end of input, suspend, history
 * search, clear, line start and end, delete a word, delete to the line start, and a newline.
 * Interrupt is not here: C-c has a key of its own in the row.
 */
internal val CTRL_CHORDS = listOf('d', 'z', 'r', 'l', 'a', 'e', 'w', 'u', 'j')

/** The bar's groups, and the keys in them: a 48 dp target each, as the platform asks. */
private val BAR_HEIGHT = 56.dp
private val KEY_SIZE = 48.dp
private val KEY_HEIGHT = KEY_SIZE
private val PILL_PAD = 4.dp
private val GROUP_GAP = 8.dp
private val KEY_PAD = 2.dp

private val STICK_FACE = 96.dp
private val POPUP_EDGE = 12.dp

/** The pinned pad's keys: larger than the bar's, since they are aimed at one after another. */
private val PAD_KEY = 56.dp
private val PAD_GAP = 6.dp
private val PAD_INSET = 8.dp

/** How far the finger moves before the stick points anywhere. */
private val STICK_DEAD_ZONE = 14.dp

/**
 * How far out the rim is, in dead zones: about 45 dp, well past where a slide that only means to
 * pick a direction stops.
 */
internal const val STICK_RIM = 3.2f

/** How long the stick waits at the rim before the arrow repeats, and how often it does then. */
private const val STICK_REPEAT_DELAY_MS = 400L
private const val STICK_REPEAT_MS = 110L

/** How much more of the other axis a slide needs before the stick leaves the arrow it has. */
private const val STICK_HYSTERESIS = 0.85f

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
    Latch.Off -> Colors.clear
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
 * One key, flat in its group, lit while pressed. A key that has a platform mark - Tab, Return,
 * paste - shows the mark and speaks its [label]; a chord such as C-c is written out in monospace,
 * since that is how a terminal user reads it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Key(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = Colors.ink,
    plate: Color = Colors.clear,
    icon: Int? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier.height(KEY_HEIGHT).clip(RoundedCornerShape(Radius.chip))
            .background(plate)
            .combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick)
            .then(if (icon != null) Modifier.semantics { contentDescription = label } else Modifier)
            .padding(horizontal = KEY_PAD),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Glyph(icon, color, size = 22.dp) else KeyText(label, color)
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
