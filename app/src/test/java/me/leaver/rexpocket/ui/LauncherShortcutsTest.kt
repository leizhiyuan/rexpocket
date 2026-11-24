package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowToast

/**
 * Launcher shortcuts to the panes opened last: one per tab, its intent read back, taken off the
 * list when its host goes, and a tap on one whose host is gone says so instead of opening nothing.
 */
@RunWith(AndroidJUnit4::class)
class LauncherShortcutsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val pane = PaneShortcut("mac", "s1", "w1", "b1")

    @Test
    fun aShortcutsIntentNamesItsPane() {
        val app = freshApp()
        assertEquals(pane, LauncherShortcuts.read(LauncherShortcuts.intent(app, pane)))
        assertNull(LauncherShortcuts.read(android.content.Intent(android.content.Intent.ACTION_MAIN)))
    }

    @Test
    fun oneShortcutPerTabNewestFirstAndGoneWithItsHost() {
        val app = freshApp()
        LauncherShortcuts.opened(app, pane, "claude", "work", "mac")
        LauncherShortcuts.opened(app, pane.copy(blockId = "b2"), "claude", "work", "mac")
        LauncherShortcuts.opened(app, PaneShortcut("pi", "s9", "w9", null), "htop", "ops", "pi")
        val ids = ShortcutManagerCompat.getDynamicShortcuts(app).map { it.id }.toSet()
        assertEquals(setOf(pane.id, "pane:pi:s9:w9"), ids)
        val mac = ShortcutManagerCompat.getDynamicShortcuts(app).single { it.id == pane.id }
        assertEquals("claude", mac.shortLabel.toString())
        assertEquals("claude · work · mac", mac.longLabel.toString())
        LauncherShortcuts.hostRemoved(app, "mac")
        assertEquals(listOf("pane:pi:s9:w9"), ShortcutManagerCompat.getDynamicShortcuts(app).map { it.id })
    }

    @Test
    fun aShortcutToARemovedHostSaysSo() {
        val app = freshApp()
        val m = launchedModel(compose, app)
        compose.runOnUiThread { m.openShortcut(pane) }
        compose.waitForIdle()
        assertEquals(app.getString(R.string.shortcut_host_gone), ShadowToast.getTextOfLatestToast())
        assertTrue(m.route.value == Route.Home)
    }
}
