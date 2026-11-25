package me.leaver.rexpocket.ui

import me.leaver.rexpocket.ui.guide.GuideSpot
import me.leaver.rexpocket.ui.guide.guideTarget
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.CtrlCKey
import me.leaver.rexpocket.core.DoubleTapAction
import me.leaver.rexpocket.core.GestureSettings
import me.leaver.rexpocket.core.KeyArrangement
import me.leaver.rexpocket.core.LongPressAction
import me.leaver.rexpocket.core.TapAction
import kotlin.math.roundToInt

// Settings › Terminal controls: what the pane's gestures do and how long they take, and which keys
// the key bar has. See docs/design/terminal-controls.md.

private enum class Timing { DoubleTap, LongPress }

/** The pages of Settings › Terminal controls: the controls, and Arrange keys opened from them. */
internal enum class ControlsPage { Controls, ArrangeKeys }

/**
 * Terminal controls and its Arrange keys page, as Settings opens them: [page] is the one showing,
 * if any. Arranging the keys goes back to the controls it was opened from. The values are the app's
 * own (the view model's, collected in RexApp); the UI tests show this same function with the same
 * view model, so what they see is what a person sees. See docs/design/testing.md.
 */
@Composable
internal fun TerminalControlsPages(
    page: ControlsPage?,
    onPage: (ControlsPage?) -> Unit,
    gestures: GestureSettings,
    onGestures: (GestureSettings) -> Unit,
    keyBar: Boolean,
    onKeyBar: (Boolean) -> Unit,
    keyBarCtrlC: CtrlCKey,
    onKeyBarCtrlC: (CtrlCKey) -> Unit,
    keyArrangement: KeyArrangement,
    onKeyArrangement: (KeyArrangement) -> Unit,
) {
    when (page) {
        ControlsPage.Controls -> ControlsSheet(
            onDismiss = { onPage(null) },
            gestures = gestures,
            onGestures = onGestures,
            keyBar = keyBar,
            onKeyBar = onKeyBar,
            keyBarCtrlC = keyBarCtrlC,
            onKeyBarCtrlC = onKeyBarCtrlC,
            onArrangeKeys = { onPage(ControlsPage.ArrangeKeys) },
        )
        ControlsPage.ArrangeKeys -> ArrangeKeysSheet(
            arrangement = keyArrangement,
            onArrangement = onKeyArrangement,
            onDismiss = { onPage(ControlsPage.Controls) },
        )
        null -> Unit
    }
}

@Composable
internal fun ControlsSheet(
    onDismiss: () -> Unit,
    gestures: GestureSettings,
    onGestures: (GestureSettings) -> Unit,
    keyBar: Boolean,
    onKeyBar: (Boolean) -> Unit,
    keyBarCtrlC: CtrlCKey,
    onKeyBarCtrlC: (CtrlCKey) -> Unit,
    /** Opens Arrange keys, where the bar's keys are ordered, shown and hidden. */
    onArrangeKeys: () -> Unit,
) {
    var timing by remember { mutableStateOf<Timing?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val systemDoubleTap = ViewConfiguration.getDoubleTapTimeout()
    val systemLongPress = ViewConfiguration.getLongPressTimeout()
    ActionSheet(onDismiss = onDismiss, title = stringResource(R.string.controls_title)) {
        Group(stringResource(R.string.controls_group_gestures)) {
            // Each row opens a picker of its choices (Pickers.kt); the sheet stays open under it, so
            // the detail shows the change. The icon is the action chosen, not the gesture, so it
            // changes with it (choice(), below).
            ChoiceRow(stringResource(R.string.gesture_tap), TapAction.entries, gestures.tap, ::choice, modifier = Modifier.guideTarget(GuideSpot.GestureTap)) {
                onGestures(gestures.copy(tap = it))
            }
            ChoiceRow(stringResource(R.string.gesture_double_tap), DoubleTapAction.entries, gestures.doubleTap, ::choice) {
                onGestures(gestures.copy(doubleTap = it))
            }
            ChoiceRow(stringResource(R.string.gesture_long_press), LongPressAction.entries, gestures.longPress, ::choice) {
                onGestures(gestures.copy(longPress = it))
            }
            // The rest are about time: an hourglass for the wait, a timer for the two durations.
            ChoiceRow(
                label = stringResource(R.string.gesture_wait), options = ON_OFF, current = gestures.waitForDoubleTap,
                choice = ::waitChoice, icon = R.drawable.ic_hourglass,
                supporting = stringResource(waitChoice(gestures.waitForDoubleTap).description),
            ) { onGestures(gestures.copy(waitForDoubleTap = it)) }
            SheetItem(
                label = stringResource(R.string.gesture_double_tap_time), icon = R.drawable.ic_timer,
                detail = msLabel(gestures.doubleTapMs, systemDoubleTap), keepOpen = true, opens = true,
            ) { timing = Timing.DoubleTap }
            SheetItem(
                label = stringResource(R.string.gesture_long_press_time), icon = R.drawable.ic_timer,
                detail = msLabel(gestures.longPressMs, systemLongPress), keepOpen = true, opens = true,
            ) { timing = Timing.LongPress }
        }
        Group(stringResource(R.string.controls_group_keys)) {
            ChoiceRow(
                label = stringResource(R.string.keybar_title), options = ON_OFF, current = keyBar,
                choice = ::keyBarChoice, icon = R.drawable.ic_keyboard, onPick = onKeyBar,
            )
            // Which keys show - Shift, Alt and C-c among them - and in what order.
            SheetItem(
                label = stringResource(R.string.arrange_title), icon = R.drawable.ic_drag_handle,
                supporting = stringResource(R.string.arrange_hint),
                opens = true,
                modifier = Modifier.guideTarget(GuideSpot.ArrangeKeys),
                onClick = onArrangeKeys,
            )
            // Tapped, or held unless the program is busy. Whether the key shows at all is Arrange
            // keys': a key stored hidden shows as the tap it sends here.
            ChoiceRow(
                stringResource(R.string.keybar_ctrl_c), listOf(CtrlCKey.Tap, CtrlCKey.Hold),
                if (keyBarCtrlC == CtrlCKey.Hold) CtrlCKey.Hold else CtrlCKey.Tap, ::choice, onPick = onKeyBarCtrlC,
            )
        }
        // Everything on this page as the app comes, after a confirm; Arrange keys has its own Reset.
        val atDefaults = gestures == GestureSettings() && !keyBar && keyBarCtrlC != CtrlCKey.Hold
        Group {
            SheetItem(
                stringResource(R.string.controls_reset),
                if (atDefaults) Colors.muted else Colors.accent,
                icon = R.drawable.ic_restart,
                supporting = stringResource(R.string.controls_reset_body),
                keepOpen = true,
                enabled = !atDefaults,
                modifier = Modifier.testTag(CONTROLS_RESET_TAG),
            ) { confirmReset = true }
        }
    }
    if (confirmReset) {
        ResetDialog(
            title = stringResource(R.string.controls_reset_title),
            body = stringResource(R.string.controls_reset_confirm_body),
            onDismiss = { confirmReset = false },
        ) {
            onGestures(GestureSettings())
            onKeyBar(false)
            onKeyBarCtrlC(CtrlCKey.Tap)
        }
    }
    when (timing) {
        Timing.DoubleTap -> TimingDialog(
            title = stringResource(R.string.gesture_double_tap_time),
            hint = stringResource(R.string.gesture_double_tap_time_hint),
            current = gestures.doubleTapMs,
            system = systemDoubleTap,
            range = GestureSettings.DOUBLE_TAP_RANGE,
            step = 25,
            onDismiss = { timing = null },
        ) { onGestures(gestures.copy(doubleTapMs = it)) }
        Timing.LongPress -> TimingDialog(
            title = stringResource(R.string.gesture_long_press_time),
            hint = stringResource(R.string.gesture_long_press_time_hint),
            current = gestures.longPressMs,
            system = systemLongPress,
            range = GestureSettings.LONG_PRESS_RANGE,
            step = 50,
            onDismiss = { timing = null },
        ) { onGestures(gestures.copy(longPressMs = it)) }
        null -> Unit
    }
}

@Composable
private fun msLabel(ms: Int, system: Int): String =
    if (ms <= 0) stringResource(R.string.gesture_time_system, system) else stringResource(R.string.gesture_time_ms, ms)

/*
 * What a choice row shows for its value, and its picker for each option: the words, the icon of the
 * action chosen and what it does ([Choice]). One function per setting, so a row's detail and its
 * icon come from the same place and cannot disagree. An action that does nothing has the neutral
 * "block" mark rather than no icon, so the group's labels keep their keyline.
 */

internal const val CONTROLS_RESET_TAG = "controls-reset"

/**
 * Asks before a page's settings go back to how the app comes: what goes is in [body]. Reset is in
 * red, as it cannot be undone from here.
 */
@Composable
internal fun ResetDialog(title: String, body: String, onDismiss: () -> Unit, onReset: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Colors.surface,
        title = { Text(title, color = Colors.ink) },
        text = { Text(body, color = Colors.muted) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onReset()
            }, modifier = Modifier.testTag(RESET_CONFIRM_TAG)) { Text(stringResource(R.string.settings_reset_confirm), color = Colors.danger) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Colors.muted) } },
    )
}

internal const val RESET_CONFIRM_TAG = "reset-confirm"

/** A setting that is on or off, in the order its picker lists them. */
internal val ON_OFF = listOf(true, false)

internal fun choice(action: TapAction): Choice = when (action) {
    TapAction.Keyboard -> Choice(R.string.gesture_open_keyboard, R.drawable.ic_keyboard, R.string.gesture_open_keyboard_hint)
    TapAction.Toggle -> Choice(R.string.gesture_toggle_keyboard, R.drawable.ic_keyboard, R.string.gesture_toggle_keyboard_hint)
    TapAction.Nothing -> Choice(R.string.gesture_nothing, R.drawable.ic_block, R.string.gesture_tap_nothing_hint)
}

internal fun choice(action: DoubleTapAction): Choice = when (action) {
    DoubleTapAction.FullScreen -> Choice(R.string.action_full_screen, R.drawable.ic_fullscreen, R.string.gesture_full_screen_hint)
    DoubleTapAction.Keyboard -> Choice(R.string.gesture_toggle_keyboard, R.drawable.ic_keyboard, R.string.gesture_toggle_keyboard_hint)
    DoubleTapAction.Nothing -> Choice(R.string.gesture_nothing, R.drawable.ic_block, R.string.gesture_double_tap_nothing_hint)
}

internal fun choice(action: LongPressAction): Choice = when (action) {
    LongPressAction.Select -> Choice(R.string.gesture_select, R.drawable.ic_text_select, R.string.gesture_select_hint)
    LongPressAction.FullScreen -> Choice(R.string.action_full_screen, R.drawable.ic_fullscreen, R.string.gesture_full_screen_hint)
    LongPressAction.Keyboard -> Choice(R.string.gesture_toggle_keyboard, R.drawable.ic_keyboard, R.string.gesture_toggle_keyboard_hint)
}

/**
 * The C-c key: a finger tapping, or a finger held. Hidden is no choice of this row (whether the key
 * shows is Arrange keys'): a key stored hidden from an old build is shown as the tap it sends.
 */
internal fun choice(key: CtrlCKey): Choice = when (key) {
    CtrlCKey.Hold -> Choice(R.string.keybar_ctrl_c_hold, R.drawable.ic_touch_long, R.string.keybar_ctrl_c_hold_hint)
    CtrlCKey.Tap, CtrlCKey.Hidden -> Choice(R.string.keybar_ctrl_c_tap, R.drawable.ic_touch_app, R.string.keybar_ctrl_c_tap_hint)
}

/** Wait for a double tap: an hourglass for the wait, a finger for a tap that acts at once. */
internal fun waitChoice(on: Boolean): Choice =
    if (on) Choice(R.string.gesture_on, R.drawable.ic_hourglass, R.string.gesture_wait_on)
    else Choice(R.string.gesture_off, R.drawable.ic_touch_app, R.string.gesture_wait_off)

/** The key bar with a desk keyboard: shown, or hidden (the keyboard has the keys). */
internal fun keyBarChoice(shown: Boolean): Choice =
    if (shown) Choice(R.string.keybar_always, R.drawable.ic_keyboard, R.string.keybar_always_hint)
    else Choice(R.string.keybar_auto, R.drawable.ic_keyboard_hide, R.string.keybar_auto_hint)

/**
 * A time picked on a slider, the way a keyboard app sets its long press. Starts at the phone's own
 * time when none was chosen, and "Use the phone's" goes back to following it.
 */
@Composable
private fun TimingDialog(
    title: String,
    hint: String,
    current: Int,
    system: Int,
    range: IntRange,
    step: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    var value by remember { mutableFloatStateOf((if (current > 0) current else system).coerceIn(range).toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Colors.surface,
        title = { Text(title, color = Colors.ink) },
        text = {
            Column {
                Text(hint, color = Colors.muted, fontSize = TextStyles.label)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.gesture_time_ms, value.roundToInt()), color = Colors.ink, fontSize = TextStyles.body)
                Slider(
                    value = value,
                    onValueChange = { value = (it / step).roundToInt() * step.toFloat() },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    steps = (range.last - range.first) / step - 1,
                    colors = SliderDefaults.colors(thumbColor = Colors.accent, activeTrackColor = Colors.accent),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onPick(value.roundToInt())
                onDismiss()
            }) { Text(stringResource(R.string.action_save), color = Colors.accent) }
        },
        dismissButton = {
            TextButton(onClick = {
                onPick(0)
                onDismiss()
            }) { Text(stringResource(R.string.gesture_time_use_system), color = Colors.muted) }
        },
    )
}
