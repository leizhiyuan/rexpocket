package me.leaver.rexpocket.core

// Settings › Appearance: the app's light or dark, the terminal's apart from it, and the size of the
// app's text. Stored by name in the app's preferences (`app_theme`, `terminal_colors`,
// `text_size`); a missing or unknown name is the first entry, which is what the app did before
// there was a setting.

/** The settings of Settings › Appearance. */
data class Appearance(
    val app: AppTheme = AppTheme.System,
    val terminal: TerminalColors = TerminalColors.MatchApp,
    val text: TextSize = TextSize.System,
)

/**
 * The size of the app's own text, a step on top of the phone's font size: [System] is Android's size
 * as it is, the others multiply it by [scale]. The terminal's text is not the app's: it keeps its own
 * size (Terminal font, a pinch on the pane), which already follows Android's.
 */
enum class TextSize(val pref: String, val scale: Float) {
    System("system", 1f), Smaller("85", 0.85f), Larger("115", 1.15f), Large("130", 1.3f), Largest("150", 1.5f);

    /**
     * The font scale the app draws at when the phone's own is [systemScale]. This setting takes it
     * no further than [MAX_FONT_SCALE], as far as Android itself goes; a phone already past it stays.
     */
    fun fontScale(systemScale: Float): Float =
        if (this == System) systemScale else (systemScale * scale).coerceAtMost(maxOf(systemScale, MAX_FONT_SCALE))

    companion object {
        const val MAX_FONT_SCALE = 2f

        fun fromPref(value: String?): TextSize = entries.firstOrNull { it.pref == value } ?: System
    }
}

/** The app's appearance: the phone's, or always one of the two. */
enum class AppTheme(val pref: String) {
    System("system"), Light("light"), Dark("dark");

    /** Whether the app draws dark, when the phone itself is [systemDark]. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }

    companion object {
        fun fromPref(value: String?): AppTheme = entries.firstOrNull { it.pref == value } ?: System
    }
}

/**
 * The terminal's default colours: the app's paper and ink, or always dark or always light whatever
 * the app is. A program's own colours are its own either way.
 */
enum class TerminalColors(val pref: String) {
    MatchApp("app"), Dark("dark"), Light("light");

    /** Whether the pane draws dark, when the app is [appDark]. */
    fun isDark(appDark: Boolean): Boolean = when (this) {
        MatchApp -> appDark
        Dark -> true
        Light -> false
    }

    companion object {
        fun fromPref(value: String?): TerminalColors = entries.firstOrNull { it.pref == value } ?: MatchApp
    }
}
