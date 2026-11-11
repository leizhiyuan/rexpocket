package me.leaver.rexpocket.terminal

/** The colours the app gives a terminal; ARGB. Every palette entry stays the program's own. */
internal data class TerminalTheme(
    val foreground: Int,
    val background: Int,
    val cursor: Int,
    val selection: Int,
)
