package me.leaver.rexpocket.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.AppTheme
import me.leaver.rexpocket.core.Appearance
import me.leaver.rexpocket.core.TerminalColors
import me.leaver.rexpocket.core.TextScale
import me.leaver.rexpocket.core.TextSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import me.leaver.rexpocket.core.UpdateChannel

// Settings › Appearance, and the choices of the other Settings rows that pick from named options.
// Each row opens a picker (Pickers.kt), as Terminal controls' do. See terminal-controls.md, 8.

/** The open Appearance page, for the screen tests. */
internal const val APPEARANCE_TAG = "appearance"

/** Appearance's Terminal text size row. */
internal const val TERMINAL_TEXT_SIZE_TAG = "terminal-text-size"

/**
 * Settings › Appearance: the app's theme, the terminal's colours apart from it, and the app's text
 * size. A pick shows at once, under the sheet too, since RexTheme reads the same value the rows do.
 * The text size says under its label that the terminal keeps its own size.
 */
@Composable
internal fun AppearanceSheet(
    appearance: Appearance,
    onAppearance: (Appearance) -> Unit,
    onDismiss: () -> Unit,
    /** The terminal's text size for new tabs; see TextSize.kt. */
    terminalTextSize: Float = TextScale.DEFAULT,
    onTerminalTextSize: (Float) -> Unit = {},
) {
    var sizeOpen by rememberSaveable { mutableStateOf(false) }
    if (sizeOpen) DefaultTextSizeDialog(terminalTextSize, onTerminalTextSize) { sizeOpen = false }
    ActionSheet(onDismiss = onDismiss, title = stringResource(R.string.appearance_title)) {
        Group {
            ChoiceRow(stringResource(R.string.appearance_app), AppTheme.entries, appearance.app, ::choice) {
                onAppearance(appearance.copy(app = it))
            }
            ChoiceRow(stringResource(R.string.appearance_terminal), TerminalColors.entries, appearance.terminal, ::choice) {
                onAppearance(appearance.copy(terminal = it))
            }
            ChoiceRow(
                stringResource(R.string.appearance_text_size),
                TextSize.entries,
                appearance.text,
                ::choice,
                icon = R.drawable.ic_text_fields,
                supporting = stringResource(R.string.appearance_text_size_hint),
            ) {
                onAppearance(appearance.copy(text = it))
            }
            // The terminal's own size, which the app's text size above leaves alone.
            SheetItem(
                stringResource(R.string.appearance_terminal_text_size),
                detail = stringResource(R.string.text_size_percent, TextScale.percent(terminalTextSize)),
                icon = R.drawable.ic_text_fields,
                supporting = stringResource(R.string.appearance_terminal_text_size_short),
                keepOpen = true,
                opens = true,
                modifier = Modifier.testTag(TERMINAL_TEXT_SIZE_TAG),
            ) { sizeOpen = true }
        }
    }
}

internal fun choice(theme: AppTheme): Choice = when (theme) {
    AppTheme.System -> Choice(R.string.appearance_system, R.drawable.ic_contrast, R.string.appearance_system_hint)
    AppTheme.Light -> Choice(R.string.appearance_light, R.drawable.ic_light_mode, R.string.appearance_light_hint)
    AppTheme.Dark -> Choice(R.string.appearance_dark, R.drawable.ic_dark_mode, R.string.appearance_dark_hint)
}

internal fun choice(colors: TerminalColors): Choice = when (colors) {
    TerminalColors.MatchApp -> Choice(R.string.appearance_match_app, R.drawable.ic_contrast, R.string.appearance_match_app_hint)
    TerminalColors.Dark -> Choice(R.string.appearance_always_dark, R.drawable.ic_dark_mode, R.string.appearance_always_dark_hint)
    TerminalColors.Light -> Choice(R.string.appearance_always_light, R.drawable.ic_light_mode, R.string.appearance_always_light_hint)
}

internal fun choice(size: TextSize): Choice = when (size) {
    TextSize.System -> Choice(R.string.appearance_system, R.drawable.ic_text_fields, R.string.text_size_system_hint)
    TextSize.Smaller -> Choice(R.string.text_size_85, R.drawable.ic_text_fields, R.string.text_size_85_hint)
    TextSize.Larger -> Choice(R.string.text_size_115, R.drawable.ic_text_fields, R.string.text_size_115_hint)
    TextSize.Large -> Choice(R.string.text_size_130, R.drawable.ic_text_fields, R.string.text_size_130_hint)
    TextSize.Largest -> Choice(R.string.text_size_150, R.drawable.ic_text_fields, R.string.text_size_150_hint)
}

/** Reopen the last tab on launch: every launch, or only after Android closed the app on it. */
internal fun reopenChoice(on: Boolean): Choice =
    if (on) Choice(R.string.gesture_on, R.drawable.ic_history, R.string.reopen_last_tab_on)
    else Choice(R.string.gesture_off, R.drawable.ic_sessions, R.string.reopen_last_tab_off)

/** The update channel: finished releases, or alphas and betas too. */
internal fun channelChoice(channel: UpdateChannel): Choice = when (channel) {
    UpdateChannel.Stable -> Choice(R.string.update_channel_stable, R.drawable.ic_check, R.string.update_channel_switched_stable)
    UpdateChannel.Preview -> Choice(R.string.update_channel_preview, R.drawable.ic_bug_report, R.string.update_channel_switched_preview)
}
