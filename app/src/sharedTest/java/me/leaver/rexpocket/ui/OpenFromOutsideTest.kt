package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The home opened from outside the app - the widget's tap, a host's group of notifications - while
 * a sheet was left open on it: what was asked for is what shows, not the sheet from before.
 */
@RunWith(AndroidJUnit4::class)
class OpenFromOutsideTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 1)
    private var model: RexViewModel? = null

    private fun string(id: Int) = compose.activity.getString(id)

    private fun seen(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnectAll() } }
        desk.close()
    }

    private fun start(): RexViewModel {
        val app = freshApp()
        desk.saveFor(app)
        val m = launchedModel(compose, app)
        model = m
        compose.setContent { RexPocketApp(m) }
        compose.waitForIdle()
        // Settings, left open when the person went to the launcher.
        compose.onNode(hasContentDescription(string(R.string.settings))).performClick()
        compose.waitForIdle()
        assertTrue(seen(string(R.string.settings_group_connection)))
        return m
    }

    @Test
    fun theWidgetsTapShowsActivityNotTheSheetLeftOpen() {
        val m = start()
        compose.runOnUiThread { m.openActivity() }
        compose.waitForIdle()
        assertEquals(HomeFilter.Activity, m.homeFilter.value)
        assertFalse("Settings is still over the Activity view", seen(string(R.string.settings_group_connection)))
    }

    @Test
    fun aHostsNotificationsShowActivityNotTheSheetLeftOpen() {
        val m = start()
        compose.runOnUiThread { m.openHostAlerts(desk.host.id) }
        compose.waitForIdle()
        assertEquals(HomeFilter.Activity, m.homeFilter.value)
        assertFalse("Settings is still over the Activity view", seen(string(R.string.settings_group_connection)))
    }
}
