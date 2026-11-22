package me.leaver.rexpocket.ui

import android.view.ViewConfiguration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
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

/** The next of [options] after [current], round again at the end: a row steps through its choices. */
internal fun <T> next(options: List<T>, current: T): T = options[(options.indexOf(current) + 1) % options.size]

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
    val systemDoubleTap = ViewConfiguration.getDoubleTapTimeout()
    val systemLongPress = ViewConfiguration.getLongPressTimeout()
    ActionSheet(onDismiss = onDismiss, title = stringResource(R.string.controls_title)) {
        Group(stringResource(R.string.controls_group_gestures)) {
            // Each row steps through its choices and stays open, as the C-c row does: the detail is
            // the one place the change shows.
            SheetItem(
                label = stringResource(R.string.gesture_tap), icon = R.drawable.ic_keyboard,
                detail = stringResource(tapLabel(gestures.tap)), keepOpen = true,
            ) { onGestures(gestures.copy(tap = next(TapAction.entries, gestures.tap))) }
            SheetItem(
                label = stringResource(R.string.gesture_double_tap), icon = R.drawable.ic_fullscreen,
                detail = stringResource(doubleTapLabel(gestures.doubleTap)), keepOpen = true,
            ) { onGestures(gestures.copy(doubleTap = next(DoubleTapAction.entries, gestures.doubleTap))) }
            SheetItem(
                label = stringResource(R.string.gesture_long_press), icon = R.drawable.ic_edit,
                detail = stringResource(longPressLabel(gestures.longPress)), keepOpen = true,
            ) { onGestures(gestures.copy(longPress = next(LongPressAction.entries, gestures.longPress))) }
            SheetItem(
                label = stringResource(R.string.gesture_wait), icon = R.drawable.ic_settings,
                supporting = stringResource(if (gestures.waitForDoubleTap) R.string.gesture_wait_on else R.string.gesture_wait_off),
                detail = stringResource(if (gestures.waitForDoubleTap) R.string.gesture_on else R.string.gesture_off),
                keepOpen = true,
            ) { onGestures(gestures.copy(waitForDoubleTap = !gestures.waitForDoubleTap)) }
            SheetItem(
                label = stringResource(R.string.gesture_double_tap_time), icon = R.drawable.ic_settings,
                detail = msLabel(gestures.doubleTapMs, systemDoubleTap), keepOpen = true,
            ) { timing = Timing.DoubleTap }
            SheetItem(
                label = stringResource(R.string.gesture_long_press_time), icon = R.drawable.ic_settings,
                detail = msLabel(gestures.longPressMs, systemLongPress), keepOpen = true,
            ) { timing = Timing.LongPress }
        }
        Group(stringResource(R.string.controls_group_keys)) {
            SheetItem(
                label = stringResource(R.string.keybar_title),
                icon = R.drawable.ic_keyboard,
                detail = stringResource(if (keyBar) R.string.keybar_always else R.string.keybar_auto),
                keepOpen = true,
                onClick = { onKeyBar(!keyBar) },
            )
            // Which keys show - Shift, Alt and C-c among them - and in what order.
            SheetItem(
                label = stringResource(R.string.arrange_title), icon = R.drawable.ic_drag_handle,
                supporting = stringResource(R.string.arrange_hint),
                onClick = onArrangeKeys,
            )
            // A tap steps between the two: tapped, and held unless the program is busy. Whether the
            // key shows at all is Arrange keys'.
            SheetItem(
                label = stringResource(R.string.keybar_ctrl_c), icon = R.drawable.ic_keyboard,
                detail = stringResource(
                    if (keyBarCtrlC == CtrlCKey.Hold) R.string.keybar_ctrl_c_hold else R.string.keybar_ctrl_c_tap,
                ),
                keepOpen = true,
                onClick = { onKeyBarCtrlC(if (keyBarCtrlC == CtrlCKey.Hold) CtrlCKey.Tap else CtrlCKey.Hold) },
            )
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

internal fun tapLabel(action: TapAction) = when (action) {
    TapAction.Keyboard -> R.string.gesture_open_keyboard
    TapAction.Toggle -> R.string.gesture_toggle_keyboard
    TapAction.Nothing -> R.string.gesture_nothing
}

internal fun doubleTapLabel(action: DoubleTapAction) = when (action) {
    DoubleTapAction.FullScreen -> R.string.action_full_screen
    DoubleTapAction.Keyboard -> R.string.gesture_toggle_keyboard
    DoubleTapAction.Nothing -> R.string.gesture_nothing
}

internal fun longPressLabel(action: LongPressAction) = when (action) {
    LongPressAction.Select -> R.string.gesture_select
    LongPressAction.FullScreen -> R.string.action_full_screen
    LongPressAction.Keyboard -> R.string.gesture_toggle_keyboard
}

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
