package me.leaver.rexpocket.core

// Settings › Appearance: the app's light or dark, and the terminal's apart from it. Stored by name
// in the app's preferences (`app_theme`, `terminal_colors`); a missing or unknown name is the
// first entry, which is what the app did before there was a setting.

/** Both settings of Settings › Appearance. */
data class Appearance(val app: AppTheme = AppTheme.System, val terminal: TerminalColors = TerminalColors.MatchApp)

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
