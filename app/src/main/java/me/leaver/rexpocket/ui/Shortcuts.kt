package me.leaver.rexpocket.ui

import android.content.Context
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.AppShortcut
import me.leaver.rexpocket.core.KeyCodes
import me.leaver.rexpocket.core.META_CTRL_ON
import me.leaver.rexpocket.core.META_META_ON
import me.leaver.rexpocket.core.META_SHIFT_ON
import me.leaver.rexpocket.core.SHORTCUT_KEYS
import me.leaver.rexpocket.core.ShortcutKey
import me.leaver.rexpocket.core.appShortcut

// The terminal screen's own keys on a desk keyboard, in the app's words: the help sheet, and the
// list Android's own keyboard shortcuts helper shows (Meta+/). The rule for which keys are the
// screen's is `appShortcut` in core; see docs/design/terminal-controls.md, "A desk keyboard".

/** The screen's shortcut for a key event, or null when the key is the program's. */
internal fun shortcutOf(event: KeyEvent): AppShortcut? =
    appShortcut(event.keyCode, ctrl = event.isCtrlPressed, shift = event.isShiftPressed, alt = event.isAltPressed, meta = event.isMetaPressed)

/** What a shortcut does, as its row says it. */
internal fun shortcutLabel(shortcut: AppShortcut): Int = when (shortcut) {
    AppShortcut.Find -> R.string.find_title
    AppShortcut.Copy -> R.string.shortcut_copy
    AppShortcut.Paste -> R.string.key_paste
    AppShortcut.NewTab -> R.string.menu_new_tab
    AppShortcut.NextTab -> R.string.palette_next_tab
    AppShortcut.PreviousTab -> R.string.palette_previous_tab
    AppShortcut.CloseTab -> R.string.menu_close_tab
    AppShortcut.FontBigger -> R.string.palette_font_bigger
    AppShortcut.FontSmaller -> R.string.palette_font_smaller
    AppShortcut.FontReset -> R.string.palette_font_reset
    AppShortcut.Palette -> R.string.palette_title
    AppShortcut.Home -> R.string.palette_back_home
    AppShortcut.Help -> R.string.shortcuts_title
}

/** A key as a keyboard prints it, with its modifiers: `Ctrl+Shift+F`, `Meta+=`. */
internal fun keyName(context: Context, key: ShortcutKey): String {
    val parts = ArrayList<String>()
    if (key.meta and META_CTRL_ON != 0) parts += context.getString(R.string.key_ctrl)
    if (key.meta and META_META_ON != 0) parts += context.getString(R.string.key_meta)
    if (key.meta and META_SHIFT_ON != 0) parts += context.getString(R.string.key_shift)
    parts += when (key.keyCode) {
        KeyCodes.TAB -> context.getString(R.string.key_tab)
        KeyCodes.EQUALS -> "="
        KeyCodes.MINUS -> "-"
        KeyCodes.ZERO -> "0"
        KeyCodes.LEFT_BRACKET -> "["
        KeyCodes.RIGHT_BRACKET -> "]"
        KeyCodes.SLASH -> "/"
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (key.keyCode - KeyEvent.KEYCODE_A)).toString()
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (key.keyCode - KeyEvent.KEYCODE_0)).toString()
        else -> KeyEvent.keyCodeToString(key.keyCode).removePrefix("KEYCODE_")
    }
    return parts.joinToString("+")
}

/** A shortcut's keys as its row says them: the first, or the first two. */
internal fun keyNames(context: Context, keys: List<ShortcutKey>): String {
    val names = keys.take(2).map { keyName(context, it) }
    return if (names.size == 2) context.getString(R.string.shortcut_or, names[0], names[1]) else names.first()
}

/**
 * The screen's shortcuts, one row each with its keys; a tap runs it, as the key would. Opened from
 * the ⋮ menu, the palette and Ctrl+Shift+/.
 */
@Composable
internal fun ShortcutsSheet(onDismiss: () -> Unit, onRun: (AppShortcut) -> Unit) {
    val context = LocalContext.current
    ActionSheet(onDismiss = onDismiss, title = context.getString(R.string.shortcuts_title)) {
        Group(context.getString(R.string.shortcuts_group), icons = false) {
            for ((shortcut, keys) in SHORTCUT_KEYS) {
                if (shortcut == AppShortcut.Help) continue
                SheetItem(context.getString(shortcutLabel(shortcut)), detail = keyNames(context, keys)) { onRun(shortcut) }
            }
        }
    }
}

/**
 * The shortcuts for Android's keyboard shortcuts helper, which the system shows for Meta+/ and
 * asks the activity for: one group, each shortcut under each of its keys.
 */
internal fun terminalShortcutGroup(context: Context): KeyboardShortcutGroup {
    val items = SHORTCUT_KEYS.flatMap { (shortcut, keys) ->
        keys.map { key -> KeyboardShortcutInfo(context.getString(shortcutLabel(shortcut)), key.keyCode, key.meta) }
    }
    return KeyboardShortcutGroup(context.getString(R.string.shortcuts_title), items)
}

/** True while the terminal screen is up, so the activity offers its shortcuts to Android's helper. */
internal object TerminalShortcuts {
    @Volatile var shown: Int = 0
}

/** Marks the terminal screen as up for as long as it is composed. */
@Composable
internal fun TerminalShortcutsShown() {
    DisposableEffect(Unit) {
        TerminalShortcuts.shown++
        onDispose { TerminalShortcuts.shown-- }
    }
}
