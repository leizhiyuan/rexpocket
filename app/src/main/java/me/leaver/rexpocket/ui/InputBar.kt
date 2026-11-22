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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import me.leaver.rexpocket.core.BarKey
import me.leaver.rexpocket.core.CtrlCKey
import me.leaver.rexpocket.core.KeyArrangement
import me.leaver.rexpocket.core.KeyGroup
import androidx.compose.runtime.saveable.rememberSaveable
import me.leaver.rexpocket.core.typedPress
import me.leaver.rexpocket.core.metaState
import me.leaver.rexpocket.terminal.TerminalSession
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import me.leaver.rexpocket.core.Pill
import me.leaver.rexpocket.core.DeskKey
import me.leaver.rexpocket.core.KeyModifiers
import me.leaver.rexpocket.core.symbolPress
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
 * Paste, the paperclip and a slash for pi's command menu follow off the edge. C-c is red while the
 * program works or waits; Settings can make it wait for a hold while the program is idle, where a
 * stray tap would wipe a half-written prompt, or take it off the bar, leaving Ctrl then `c`. `ctrl`
 * applies to the next key, from this row or from the phone keyboard; a quick second tap keeps it
 * on, and holding it opens the other control chords. Return held is shift-Return: a new line in an
 * agent's prompt.
 *
 * Fn pins a panel of the rest of a desk keyboard - Home, End, the page keys, Delete, F1 to F12 -
 * and the symbols a phone keyboard keeps a page away. The modifiers held in the row apply to them.
 *
 * The paperclip is the dock's way to send a file or an image: it opens the platform's picker, and
 * goes quiet while the file picked last is still on its way.
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
    onAttach: () -> Unit,
    /** False while an upload is running: a second file would only be dropped. */
    attachReady: Boolean,
    onKeyboard: () -> Unit,
    /** The keys of each pill in the person's order, and which show. */
    arrangement: KeyArrangement = KeyArrangement(),
    ctrlC: CtrlCKey = CtrlCKey.Tap,
    onDeskKey: (DeskKey) -> Unit = {},
    /** Full screen, from its key in the small pill; null leaves the key out. */
    onFullScreen: (() -> Unit)? = null,
) {
    val scroll = rememberScrollState()
    val pill = RoundedCornerShape(50)
    val keys = arrangement.shown(Pill.Keys)
    // One group's panel at a time; it goes when its key leaves the bar.
    // Kept across a rotation, as the screen's other state is.
    var pinned by rememberSaveable { mutableStateOf<String?>(null) }
    val openGroup = pinned?.takeIf { id -> keys.any { it is BarKey.Group && it.id == id } }
    val actions = KeyActions(mods, busy, ctrlC, onMod, onKey, onNewLine, onPaste, onAttach, attachReady, onDeskKey)
    val restKeys = arrangement.shown(Pill.Rest).filter { it != BarKey.FullScreen || onFullScreen != null }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        // How many keys each pill has decides how they share the width, so both show their hint
        // and the small pill's last key shows whole where it fits.
        val widths = keyBarWidths(maxWidth.value, keys.size, restKeys.size, KEY_SIZE.value, PILL_PAD.value, GROUP_GAP.value)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GROUP_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // With every key of the wide pill hidden, the small one keeps its place at the right.
            if (keys.isEmpty()) Spacer(Modifier.weight(1f))
            // The key pill takes what the small pill leaves, so together they always fill the bar.
            if (keys.isNotEmpty()) Box(Modifier.weight(1f).height(BAR_HEIGHT).floating(pill).testTag(KEY_PILL_TAG)) {
                Row(
                    Modifier.fillMaxSize().horizontalScroll(scroll).padding(horizontal = PILL_PAD),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val key = Modifier.size(KEY_SIZE)
                    for (barKey in keys) {
                        if (barKey is BarKey.Group) {
                            val group = arrangement.group(barKey.id) ?: continue
                            GroupKey(group, key, open = openGroup == group.id, actions = actions) {
                                pinned = if (openGroup == group.id) null else group.id
                            }
                        } else {
                            BarKeyFace(barKey, key, Colors.clear, actions)
                        }
                    }
                }
                // The pill goes on under this fade, so the key part under it reads as more to swipe to.
                if (scroll.canScrollForward) {
                    Box(
                        Modifier.align(Alignment.CenterEnd).width(KEY_FADE).fillMaxHeight()
                            .background(Brush.horizontalGradient(listOf(Colors.clear, Colors.raised))),
                    )
                }
                if (scroll.canScrollBackward) {
                    Box(
                        Modifier.align(Alignment.CenterStart).width(KEY_FADE).fillMaxHeight()
                            .background(Brush.horizontalGradient(listOf(Colors.raised, Colors.clear))),
                    )
                }
            }
            // The stick and the keyboard key, as always; past them the full-screen key, whole where the
            // bar has room, otherwise a part of it showing at the edge as the wide pill shows its further
            // keys, a swipe away. Whatever the order, the last key is the one that gives way. The stick
            // takes its own drags before this scroll sees them, so steering never scrolls the pill.
            // Whole, the pill wraps its keys, so rounding the widths to pixels never leaves it a pixel
            // short and scrolling.
            val rest = rememberScrollState()
            Box(
                Modifier.height(BAR_HEIGHT)
                    .then(if (widths.restWhole) Modifier else Modifier.width(widths.rest.dp))
                    .floating(pill)
                    .testTag(REST_PILL_TAG),
            ) {
                Row(
                    Modifier.fillMaxHeight().horizontalScroll(rest).padding(horizontal = PILL_PAD),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (barKey in restKeys) when (barKey) {
                        BarKey.Arrows -> ArrowStick(Modifier.size(KEY_SIZE)) { code -> onKey(KeyPress(code, 0), "") }
                        BarKey.Keyboard -> Key(
                            stringResource(R.string.key_keyboard),
                            Modifier.size(KEY_SIZE),
                            icon = if (imeUp) R.drawable.ic_keyboard_hide else R.drawable.ic_keyboard,
                        ) { onKeyboard() }
                        BarKey.FullScreen -> if (onFullScreen != null) {
                            Key(stringResource(R.string.action_full_screen), Modifier.size(KEY_SIZE), icon = R.drawable.ic_fullscreen) { onFullScreen() }
                        }
                        else -> Unit
                    }
                }
                if (rest.canScrollForward) {
                    Box(
                        Modifier.align(Alignment.CenterEnd).width(16.dp).fillMaxHeight()
                            .background(Brush.horizontalGradient(listOf(Colors.clear, Colors.raised))),
                    )
                }
                if (rest.canScrollBackward) {
                    Box(
                        Modifier.align(Alignment.CenterStart).width(16.dp).fillMaxHeight()
                            .background(Brush.horizontalGradient(listOf(Colors.raised, Colors.clear))),
                    )
                }
            }
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
    var pinned by rememberSaveable { mutableStateOf(false) }
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

/** What the keys of the bar and of its panels act on: the row's modifiers and the screen's actions. */
private class KeyActions(
    val mods: KeyModifiers,
    val busy: Boolean,
    val ctrlC: CtrlCKey,
    val onMod: (Mod) -> Unit,
    val onKey: (key: KeyPress, own: String) -> Unit,
    val onNewLine: () -> Unit,
    val onPaste: () -> Unit,
    val onAttach: () -> Unit,
    val attachReady: Boolean,
    val onDeskKey: (DeskKey) -> Unit,
)

/**
 * One key of the key pill or of a group's panel, on its [plate]: it does the same wherever it sits,
 * with the modifiers the row holds. Group keys and the right pill's keys are drawn by the bar itself.
 */
@Composable
private fun BarKeyFace(barKey: BarKey, modifier: Modifier, plate: Color, a: KeyActions) {
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    when (barKey) {
        BarKey.Escape -> Key(stringResource(R.string.key_escape), modifier, plate = plate) { a.onKey(KeyPress("escape", 0), "") }
        // Always named for the chord it sends; red says the program would take it as a stop.
        BarKey.CtrlC -> {
            val holdHint = stringResource(R.string.key_ctrl_c_hold_hint)
            val interrupt = { a.onKey(KeyPress("key_c", 'c'.code, text = "c"), "ctrl") }
            Key(
                stringResource(R.string.key_ctrl_c),
                modifier,
                color = if (a.busy) Colors.danger else Colors.ink,
                plate = plate,
                onLongClick = if (a.ctrlC == CtrlCKey.Hold) {
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        interrupt()
                    }
                } else {
                    null
                },
            ) {
                // Held unless the program is busy: then a tap is still the quick stop.
                if (a.ctrlC.tapSends(a.busy)) interrupt() else toast(context, holdHint)
            }
        }
        BarKey.Tab -> Key(stringResource(R.string.key_tab), modifier, plate = plate, icon = R.drawable.ic_tab_key) { a.onKey(KeyPress("tab", 0), "") }
        BarKey.Enter -> Key(
            stringResource(R.string.action_enter),
            modifier,
            plate = plate,
            icon = R.drawable.ic_return,
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                a.onNewLine()
            },
        ) { a.onKey(KeyPress("enter", 0), "") }
        BarKey.Ctrl -> CtrlKey(a.mods[Mod.Ctrl], modifier, plate, onTap = { a.onMod(Mod.Ctrl) }) { letter ->
            a.onKey(KeyPress("key_$letter", letter.code, text = letter.toString()), "ctrl")
        }
        BarKey.Shift, BarKey.Alt -> {
            val mod = if (barKey == BarKey.Shift) Mod.Shift else Mod.Alt
            val isShift = mod == Mod.Shift
            val latch = a.mods[mod]
            val description = modState(latch)
            Key(
                label = stringResource(if (isShift) R.string.key_shift else R.string.key_alt),
                modifier = modifier.semantics { stateDescription = description },
                color = modInk(latch),
                plate = if (latch == Latch.Off) plate else modPlate(latch),
                icon = if (isShift) R.drawable.ic_shift else null,
            ) { a.onMod(mod) }
        }
        BarKey.Paste -> Key(stringResource(R.string.key_paste), modifier, plate = plate, icon = R.drawable.ic_paste) { a.onPaste() }
        BarKey.Attach -> Key(
            stringResource(R.string.upload_attach),
            modifier,
            plate = plate,
            icon = R.drawable.ic_attach_file,
            color = if (a.attachReady) Colors.ink else Colors.muted,
        ) { if (a.attachReady) a.onAttach() }
        // A slash opens pi's command menu; the stick then walks it.
        BarKey.Slash -> Key("/", modifier, plate = plate) { a.onKey(KeyPress("slash", '/'.code, text = "/"), "") }
        is BarKey.Desk -> Key(deskLabel(barKey.key), modifier, plate = plate) { a.onDeskKey(barKey.key) }
        is BarKey.Arrow -> {
            val (icon, name) = arrowFace(barKey.code)
            RepeatKey(modifier, icon, stringResource(name), plate) { a.onKey(KeyPress(barKey.code, 0), "") }
        }
        is BarKey.Chord -> Key(chordLabel(barKey.letter), modifier, plate = plate) {
            a.onKey(KeyPress("key_${barKey.letter}", barKey.letter.code, text = barKey.letter.toString()), "ctrl")
        }
        is BarKey.Typed -> {
            val c = barKey.char
            Key(
                typedFace(c),
                modifier,
                plate = plate,
                description = if (c == ' ') stringResource(R.string.key_space) else null,
            ) { typedPress(c)?.let { a.onKey(it, "") } }
        }
        is BarKey.Group, BarKey.Arrows, BarKey.Keyboard, BarKey.FullScreen -> Unit
    }
}

/** What a desk key's face says: the short names a desk keyboard prints, F1 to F12 as they are. */
@Composable
internal fun deskLabel(key: DeskKey): String = when (key) {
    DeskKey.Home -> stringResource(R.string.key_home)
    DeskKey.End -> stringResource(R.string.key_end)
    DeskKey.PageUp -> stringResource(R.string.key_page_up)
    DeskKey.PageDown -> stringResource(R.string.key_page_down)
    DeskKey.Insert -> stringResource(R.string.key_insert)
    DeskKey.Delete -> stringResource(R.string.key_delete)
    else -> key.name
}

/** A typed key's face: its character, space as the open box a keyboard legend uses. */
internal fun typedFace(c: Char): String = if (c == ' ') "␣" else c.toString()

/** An arrow key's mark and spoken name. */
internal fun arrowFace(code: String): Pair<Int, Int> = when (code) {
    "arrow_up" -> R.drawable.ic_arrow_up to R.string.key_up
    "arrow_down" -> R.drawable.ic_arrow_down to R.string.key_down
    "arrow_left" -> R.drawable.ic_arrow_left to R.string.key_left
    else -> R.drawable.ic_arrow_right to R.string.key_right
}

/** The tag of [group]'s key on the bar, and of its panel. Fn keeps the tags it had. */
internal fun groupKeyTag(id: String): String = if (id == BarKey.FN_GROUP) MORE_KEYS_TAG else "group-key-$id"
internal fun groupPanelTag(id: String): String = if (id == BarKey.FN_GROUP) MORE_PANEL_TAG else "group-panel-$id"

/**
 * A group's key - Fn, or one the person made: a tap pins the group's panel above the bar, and a
 * second tap puts it away. The panel is not focusable, so the phone keyboard stays up under it, and
 * the bar's modifiers can still be tapped while it is open: Shift then End selects, Ctrl then F5 is
 * C-F5. The key shows the group's name, made smaller to fit.
 */
@Composable
private fun GroupKey(group: KeyGroup, modifier: Modifier, open: Boolean, actions: KeyActions, onToggle: () -> Unit) {
    val label = stringResource(R.string.key_group, group.name)
    val state = stringResource(if (open) R.string.arrows_pinned else R.string.mod_off)
    val density = LocalDensity.current
    val edge = with(density) { POPUP_EDGE.roundToPx() }
    // The room above the key, in the window: the panel scrolls rather than run off the top.
    var top by remember { mutableIntStateOf(0) }
    val above = remember(edge) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = (anchorBounds.center.x - popupContentSize.width / 2)
                    .coerceIn(edge, maxOf(edge, windowSize.width - popupContentSize.width - edge))
                val y = (anchorBounds.top - popupContentSize.height - edge).coerceAtLeast(0)
                return IntOffset(x, y)
            }
        }
    }
    Box(
        modifier.height(KEY_HEIGHT)
            .onGloballyPositioned { top = it.boundsInWindow().top.toInt() }
            .clip(RoundedCornerShape(Radius.chip))
            .background(if (open) Colors.keyOn else Colors.clear)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = label; stateDescription = state }
            .testTag(groupKeyTag(group.id))
            .padding(horizontal = KEY_PAD),
        contentAlignment = Alignment.Center,
    ) {
        FittedKeyText(group.name, if (open) Colors.accent else Colors.ink)
        if (open) {
            // At least two rows, so a panel over a bar with little above it still has keys to scroll.
            val room = with(density) { (top - 2 * edge).toDp() }.coerceAtLeast(MORE_PANEL_MIN)
            Popup(popupPositionProvider = above, properties = PopupProperties(focusable = false)) {
                GroupPanel(group, if (top > 0) room else Dp.Infinity, actions)
            }
        }
    }
}

/** A group's keys in rows of six, or seven of one character each (see [panelRows]); it scrolls when tall. */
@Composable
private fun GroupPanel(group: KeyGroup, maxHeight: Dp, a: KeyActions) {
    Column(
        Modifier.width(MORE_PANEL_WIDTH).heightIn(max = maxHeight).floating(RoundedCornerShape(Radius.card))
            .verticalScroll(rememberScrollState())
            .padding(PAD_INSET)
            .testTag(groupPanelTag(group.id)),
        verticalArrangement = Arrangement.spacedBy(CHORD_GAP),
    ) {
        for (row in panelRows(group.keys)) {
            Row(Modifier.fillMaxWidth().height(MORE_KEY_HEIGHT), horizontalArrangement = Arrangement.spacedBy(CHORD_GAP)) {
                for (k in row.keys) BarKeyFace(k, Modifier.weight(1f).fillMaxHeight(), Colors.key, a)
                repeat(row.slots - row.keys.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A key's name in one line, as large as the bar's labels where it fits and smaller where not. */
@Composable
private fun FittedKeyText(label: String, color: Color) {
    val measurer = rememberTextMeasurer()
    val room = with(LocalDensity.current) { (KEY_SIZE - KEY_PAD * 2 - 4.dp).toPx() }
    val full = TextStyles.label.value
    val size = remember(label, room) {
        generateSequence(full) { it - 0.5f }.takeWhile { it >= 8f }.firstOrNull { s ->
            measurer.measure(label, TextStyle(fontSize = s.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium), softWrap = false)
                .size.width <= room
        } ?: 8f
    }
    Text(
        label,
        color = color,
        fontSize = size.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
    )
}

/** The symbols a phone keyboard buries that a shell line is full of: Fn's last row by default. */
internal const val MORE_SYMBOLS = "|~`\\/-_"

internal const val MORE_KEYS_TAG = "more_keys"
internal const val MORE_PANEL_TAG = "more_keys_panel"
private val MORE_PANEL_WIDTH = 336.dp
private val MORE_KEY_HEIGHT = 44.dp
private val MORE_PANEL_MIN = MORE_KEY_HEIGHT * 2 + 4.dp + 16.dp

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
    val arrow by rememberUpdatedState(onArrow)
    RepeatKey(Modifier.size(PAD_KEY), icon, stringResource(name), Colors.key, iconSize = 24.dp) { arrow(code) }
}

/**
 * A key that acts as the finger lands, and again and again once it has stayed down past the repeat
 * delay, until it lifts: the arrow pad's keys and the single arrow keys.
 */
@Composable
private fun RepeatKey(modifier: Modifier, icon: Int, label: String, plate: Color, iconSize: Dp = 22.dp, onPress: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val press by rememberUpdatedState(onPress)
    val haptics = LocalHapticFeedback.current
    Box(
        modifier.clip(RoundedCornerShape(Radius.chip))
            .background(if (pressed) Colors.keyOn else plate)
            .semantics { contentDescription = label; role = Role.Button; onClick { press(); true } }
            .pointerInput(Unit) {
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        pressed = true
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        press()
                        val repeating = launch {
                            delay(STICK_REPEAT_DELAY_MS)
                            while (isActive) {
                                press()
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
        Glyph(icon, if (pressed) Colors.accent else Colors.ink, size = iconSize)
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
 * Interrupt is not here: C-c has a key of its own in the row, and with that key turned off in
 * Settings, a tap on `ctrl` and then `c` from the phone keyboard sends it.
 */
internal val CTRL_CHORDS = listOf('d', 'z', 'r', 'l', 'a', 'e', 'w', 'u', 'j')

/** The bar's groups, and the keys in them: a 48 dp target each, as the platform asks. */
private val BAR_HEIGHT = 56.dp
private val KEY_SIZE = 48.dp
private val KEY_HEIGHT = KEY_SIZE
private val PILL_PAD = 4.dp
private val GROUP_GAP = 8.dp

/** The wide pill's fade at a scrolled edge: narrower than a hint, so a part of the key shows clear. */
private val KEY_FADE = 16.dp

/** Test tags of the bar's two pills. */
internal const val KEY_PILL_TAG = "key-pill"
internal const val REST_PILL_TAG = "rest-pill"
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
private fun CtrlKey(latch: Latch, modifier: Modifier, plate: Color, onTap: () -> Unit, onChord: (Char) -> Unit) {
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
            .background(if (pressed || open) Colors.keyOn else if (latch == Latch.Off) plate else modPlate(latch))
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
    /** What TalkBack says for a key whose face is a mark rather than its name. */
    description: String? = null,
    onClick: () -> Unit,
) {
    val spoken = description ?: label.takeIf { icon != null }
    Box(
        modifier.height(KEY_HEIGHT).clip(RoundedCornerShape(Radius.chip))
            .background(plate)
            .combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick)
            .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier)
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

/**
 * The bytes a desk key - Home, PgDn, F10 - makes in the pane's [terminal] with the row's [mods]:
 * libghostty encodes it in the modes the program set, as for a keyboard on the desk. TermScreen
 * sends them; the device tests check them.
 */
internal fun deskKeyBytes(terminal: TerminalSession, key: DeskKey, mods: KeyModifiers): ByteArray? =
    terminal.encodeKey(key.keyCode, false, mods.metaState(), null)
