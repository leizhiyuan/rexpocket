package me.leaver.rexpocket.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.alerts.Alerts
import me.leaver.rexpocket.alerts.BackgroundChecks
import me.leaver.rexpocket.core.AlertPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Background mode on, its notification hidden: the foreground service runs without the
 * notifications permission on Android 13 and later, and with its channel switched off, so the
 * person sees no notification and no buttons. The checklist says so, with a Fix that opens the
 * right page.
 */
@RunWith(AndroidJUnit4::class)
class WatchHiddenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = compose.activity.getString(id)

    private fun sheet() {
        compose.setContent { RexTheme { NotificationsSheet(onDismiss = {}, alerts = true, onAlerts = {}, prefs = AlertPrefs(), onPrefs = {}) } }
        compose.waitForIdle()
    }

    private fun watchRow() = compose.onNode(hasText(string(R.string.bg_watch_check)) and hasClickAction() and hasAnyAncestor(hasTestTag(ACTION_SHEET_TAG)))

    @Test
    fun withoutThePermissionTheNotificationIsHiddenAndFixOpensTheAppsNotifications() {
        shadowOf(compose.activity.application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(Alerts.watchHidden(compose.activity))
        assertEquals(WatchCheck.Hidden, watchCheck(true, BackgroundChecks.read(compose.activity)))
        sheet()
        compose.onNode(hasText(string(R.string.bg_watch_check_hidden), substring = true), useUnmergedTree = true).performScrollTo()
        watchRow().performScrollTo().performClick()
        val opened = shadowOf(compose.activity.application).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, opened.action)
    }

    @Test
    fun aSwitchedOffChannelHidesItTooAndFixOpensThatChannel() {
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Alerts.channels(compose.activity)
        val manager = compose.activity.getSystemService(NotificationManager::class.java)
        val watch = manager.getNotificationChannel(Alerts.CHANNEL_WATCH)
        manager.createNotificationChannel(NotificationChannel(watch.id, watch.name, NotificationManager.IMPORTANCE_NONE))
        try {
            assertTrue(Alerts.watchHidden(compose.activity))
            sheet()
            watchRow().performScrollTo().performClick()
            val opened = shadowOf(compose.activity.application).nextStartedActivity
            assertEquals(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, opened.action)
            assertEquals(Alerts.CHANNEL_WATCH, opened.getStringExtra(android.provider.Settings.EXTRA_CHANNEL_ID))
        } finally {
            manager.deleteNotificationChannel(Alerts.CHANNEL_WATCH)
        }
    }

    @Test
    fun allowedItIsShown() {
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(Alerts.watchHidden(compose.activity))
        sheet()
        compose.onNode(hasText(string(R.string.bg_watch_check_shown), substring = true), useUnmergedTree = true).performScrollTo()
    }
}
