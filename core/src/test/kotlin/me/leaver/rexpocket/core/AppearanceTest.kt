package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceTest {
    @Test
    fun theAppFollowsThePhoneUnlessToldOtherwise() {
        assertEquals(true, AppTheme.System.isDark(systemDark = true))
        assertEquals(false, AppTheme.System.isDark(systemDark = false))
        assertEquals(false, AppTheme.Light.isDark(systemDark = true))
        assertEquals(true, AppTheme.Dark.isDark(systemDark = false))
    }

    @Test
    fun theTerminalFollowsTheAppUnlessToldOtherwise() {
        assertEquals(true, TerminalColors.MatchApp.isDark(appDark = true))
        assertEquals(false, TerminalColors.MatchApp.isDark(appDark = false))
        assertEquals(true, TerminalColors.Dark.isDark(appDark = false))
        assertEquals(false, TerminalColors.Light.isDark(appDark = true))
    }

    @Test
    fun storedNamesReadBackAndAnythingElseIsTheOldBehaviour() {
        for (theme in AppTheme.entries) assertEquals(theme, AppTheme.fromPref(theme.pref))
        for (colors in TerminalColors.entries) assertEquals(colors, TerminalColors.fromPref(colors.pref))
        assertEquals(AppTheme.System, AppTheme.fromPref(null))
        assertEquals(AppTheme.System, AppTheme.fromPref("sepia"))
        assertEquals(TerminalColors.MatchApp, TerminalColors.fromPref(null))
        assertEquals(TerminalColors.MatchApp, TerminalColors.fromPref("desk"))
    }
}
