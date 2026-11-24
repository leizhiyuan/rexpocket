package me.leaver.rexpocket.core

// The terminal screen's own keys on a desk keyboard. Everything else a keyboard sends goes to the
// program. See docs/design/terminal-controls.md, "A desk keyboard".

/** What the terminal screen does for a key of its own. */
enum class AppShortcut {
    Find, Copy, Paste, NewTab, NextTab, PreviousTab, CloseTab, FontBigger, FontSmaller, FontReset, Palette, Home, Help,
}

/**
 * A key and its modifiers, as the screen lists a shortcut: [keyCode] is Android's, the modifiers
 * Android's meta flags ([META_CTRL_ON], [META_SHIFT_ON], [META_META_ON]).
 */
data class ShortcutKey(val keyCode: Int, val meta: Int)

/** android.view.KeyEvent's codes for the keys the shortcuts use. */
object KeyCodes {
    const val TAB = 61
    const val ZERO = 7
    const val C = 31
    const val F = 34
    const val H = 36
    const val P = 44
    const val T = 48
    const val V = 50
    const val W = 51
    const val MINUS = 69
    const val EQUALS = 70
    const val LEFT_BRACKET = 71
    const val RIGHT_BRACKET = 72
    const val SLASH = 76
    const val PLUS = 81
    const val NUMPAD_0 = 144
    const val NUMPAD_SUBTRACT = 156
    const val NUMPAD_ADD = 157
}

/** android.view.KeyEvent's flag for the Meta key: Command on a Mac keyboard, the Windows or Search key elsewhere. */
const val META_META_ON = 0x10000

/**
 * The screen's shortcut for a key, or null when it is the program's. The rule: the screen takes
 * Ctrl+Shift with a letter or a symbol, which a terminal cannot tell from Ctrl alone (C-S-c is
 * C-c to every program but one using the Kitty protocol), and leaves Ctrl+Shift+- to the program,
 * as it is how a keyboard types C-_ (undo in a shell). It also takes Ctrl with `=`, `-`, `0` and Tab
 * (no byte of their own, and what Ghostty and browsers use for the font and tabs), and Command
 * (Meta) with the letters a Mac uses for the same, which no terminal program reads. Anything with
 * Alt, and every other chord, goes to the program.
 */
fun appShortcut(keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean): AppShortcut? {
    if (alt) return null
    if (ctrl && shift && !meta) return when (keyCode) {
        KeyCodes.F -> AppShortcut.Find
        KeyCodes.C -> AppShortcut.Copy
        KeyCodes.V -> AppShortcut.Paste
        KeyCodes.T -> AppShortcut.NewTab
        KeyCodes.W -> AppShortcut.CloseTab
        KeyCodes.P -> AppShortcut.Palette
        KeyCodes.H -> AppShortcut.Home
        KeyCodes.SLASH -> AppShortcut.Help
        KeyCodes.RIGHT_BRACKET -> AppShortcut.NextTab
        KeyCodes.LEFT_BRACKET, KeyCodes.TAB -> AppShortcut.PreviousTab
        KeyCodes.EQUALS, KeyCodes.PLUS -> AppShortcut.FontBigger
        else -> null
    }
    if (ctrl && !shift && !meta) return when (keyCode) {
        KeyCodes.TAB -> AppShortcut.NextTab
        KeyCodes.EQUALS, KeyCodes.PLUS, KeyCodes.NUMPAD_ADD -> AppShortcut.FontBigger
        KeyCodes.MINUS, KeyCodes.NUMPAD_SUBTRACT -> AppShortcut.FontSmaller
        KeyCodes.ZERO, KeyCodes.NUMPAD_0 -> AppShortcut.FontReset
        else -> null
    }
    if (meta && !ctrl) return when (keyCode) {
        KeyCodes.F -> AppShortcut.Find.takeIf { !shift }
        KeyCodes.C -> AppShortcut.Copy.takeIf { !shift }
        KeyCodes.V -> AppShortcut.Paste.takeIf { !shift }
        KeyCodes.T -> AppShortcut.NewTab.takeIf { !shift }
        KeyCodes.W -> AppShortcut.CloseTab.takeIf { !shift }
        KeyCodes.P -> AppShortcut.Palette.takeIf { shift }
        KeyCodes.RIGHT_BRACKET -> AppShortcut.NextTab.takeIf { shift }
        KeyCodes.LEFT_BRACKET -> AppShortcut.PreviousTab.takeIf { shift }
        KeyCodes.EQUALS, KeyCodes.PLUS, KeyCodes.NUMPAD_ADD -> AppShortcut.FontBigger
        KeyCodes.MINUS, KeyCodes.NUMPAD_SUBTRACT -> AppShortcut.FontSmaller.takeIf { !shift }
        KeyCodes.ZERO, KeyCodes.NUMPAD_0 -> AppShortcut.FontReset.takeIf { !shift }
        else -> null
    }
    return null
}

/**
 * The keys each shortcut is listed under, first the one a help sheet shows: every one of them is
 * one [appShortcut] takes. In the order the help sheet lists them.
 */
val SHORTCUT_KEYS: List<Pair<AppShortcut, List<ShortcutKey>>> = run {
    val cs = META_CTRL_ON or META_SHIFT_ON
    val c = META_CTRL_ON
    val m = META_META_ON
    val ms = META_META_ON or META_SHIFT_ON
    listOf(
        AppShortcut.Palette to listOf(ShortcutKey(KeyCodes.P, cs), ShortcutKey(KeyCodes.P, ms)),
        AppShortcut.Find to listOf(ShortcutKey(KeyCodes.F, cs), ShortcutKey(KeyCodes.F, m)),
        AppShortcut.Copy to listOf(ShortcutKey(KeyCodes.C, cs), ShortcutKey(KeyCodes.C, m)),
        AppShortcut.Paste to listOf(ShortcutKey(KeyCodes.V, cs), ShortcutKey(KeyCodes.V, m)),
        AppShortcut.NewTab to listOf(ShortcutKey(KeyCodes.T, cs), ShortcutKey(KeyCodes.T, m)),
        AppShortcut.NextTab to listOf(ShortcutKey(KeyCodes.TAB, c), ShortcutKey(KeyCodes.RIGHT_BRACKET, cs), ShortcutKey(KeyCodes.RIGHT_BRACKET, ms)),
        AppShortcut.PreviousTab to listOf(ShortcutKey(KeyCodes.TAB, cs), ShortcutKey(KeyCodes.LEFT_BRACKET, cs), ShortcutKey(KeyCodes.LEFT_BRACKET, ms)),
        AppShortcut.CloseTab to listOf(ShortcutKey(KeyCodes.W, cs), ShortcutKey(KeyCodes.W, m)),
        AppShortcut.FontBigger to listOf(ShortcutKey(KeyCodes.EQUALS, c), ShortcutKey(KeyCodes.EQUALS, m)),
        AppShortcut.FontSmaller to listOf(ShortcutKey(KeyCodes.MINUS, c), ShortcutKey(KeyCodes.MINUS, m)),
        AppShortcut.FontReset to listOf(ShortcutKey(KeyCodes.ZERO, c), ShortcutKey(KeyCodes.ZERO, m)),
        AppShortcut.Home to listOf(ShortcutKey(KeyCodes.H, cs)),
        AppShortcut.Help to listOf(ShortcutKey(KeyCodes.SLASH, cs)),
    )
}

/** Whether a shortcut acts again while its key is held: only the font size, which a held key steps through. */
val AppShortcut.repeats: Boolean get() = this == AppShortcut.FontBigger || this == AppShortcut.FontSmaller
