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
        for (size in TextSize.entries) assertEquals(size, TextSize.fromPref(size.pref))
        assertEquals(TextSize.System, TextSize.fromPref(null))
        assertEquals(TextSize.System, TextSize.fromPref("200"))
    }

    @Test
    fun theTextSizeIsAStepOnTopOfThePhones() {
        assertEquals(1.3f, TextSize.System.fontScale(1.3f), 0f)
        assertEquals(0.85f, TextSize.Smaller.fontScale(1f), 1e-6f)
        assertEquals(1.5f, TextSize.Largest.fontScale(1f), 1e-6f)
        assertEquals(1.495f, TextSize.Large.fontScale(1.15f), 1e-6f)
        // No further than Android goes; a phone already past it is left as it is.
        assertEquals(2f, TextSize.Largest.fontScale(1.5f), 0f)
        assertEquals(2.2f, TextSize.Largest.fontScale(2.2f), 0f)
        assertEquals(1.87f, TextSize.Smaller.fontScale(2.2f), 1e-6f)
    }
}
