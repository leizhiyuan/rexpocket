package me.leaver.rexpocket.ui

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.alerts.AlertActionReceiver
import me.leaver.rexpocket.alerts.AlertTarget
import me.leaver.rexpocket.alerts.Alerts
import me.leaver.rexpocket.core.AlertPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The notifications a program's status, desktop notification and bell make while the app is away,
 * as Android holds them (ShadowNotificationManager), and what their actions send: the whole app's
 * view model connected to ScriptedRex, the desk's program reporting. See notifications.md.
 */
@RunWith(AndroidJUnit4::class)
class NotificationsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 1)
    private lateinit var app: Application
    private lateinit var model: RexViewModel
    private val sid get() = desk.ids[0]
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    private fun start(prefs: AlertPrefs = AlertPrefs()): List<String> {
        app = freshApp()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        desk.saveFor(app, lastSession = sid)
        model = launchedModel(compose, app)
        compose.runOnUiThread {
            model.setAlertPrefs(prefs)
            model.setAlerts(true)
            model.connect(desk.host)
        }
        compose.awaitLive(model, sid)
        return model.pocket.value.views.getValue(sid).allBlocks().map { it.id }
    }

    private fun away() = compose.runOnUiThread { model.setForeground(false) }

    @After
    fun stop() {
        if (::model.isInitialized) compose.runOnUiThread { model.disconnect() }
        Alerts.enabled.value = false
        desk.close()
    }

    /** The pane's notification, once it is up and [ok]. */
    private fun paneNotification(block: String, ok: (Notification) -> Boolean = { true }): Notification {
        var found: Notification? = null
        await(5_000) {
            found = manager.activeNotifications.firstOrNull { it.tag == block && it.id == Alerts.PANE_ID }?.notification?.takeIf(ok)
            found != null
        }
        return found!!
    }

    /**
     * Waits for [condition], running the main looper in between: the view model's coroutines, the
     * connection's input among them, run there. Compose's waitForIdle, with nothing composed, takes
     * seconds a turn, longer than a notification's action may.
     */
    private fun await(ms: Long, condition: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + ms
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            if (System.currentTimeMillis() > until) throw AssertionError("still waiting after $ms ms")
            Thread.sleep(10)
        }
    }

    /** Lets [ms] pass with the main looper running, for something that must not happen. */
    private fun pause(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    private fun Notification.actionNamed(label: String) = actions.orEmpty().firstOrNull { it.title.toString() == label }

    /**
     * What tapping an action delivers: the intent its PendingIntent holds, which must be the app's
     * own receiver. (Robolectric delivers no broadcast to a manifest receiver without an intent
     * filter, so the test hands it over as Android would.)
     */
    private fun sent(action: android.app.PendingIntent): Intent {
        val shadow = shadowOf(action)
        assertTrue(shadow.isBroadcast)
        val intent = shadow.savedIntent
        assertEquals(AlertActionReceiver::class.java.name, intent.component?.className)
        return intent
    }

    private fun awaitInputs(want: List<String>) = try {
        await(5_000) { desk.rex.inputs.toList() == want }
    } catch (e: AssertionError) {
        throw AssertionError("expected $want, got ${desk.rex.inputs} (calls ${desk.rex.calls.takeLast(5)})", e)
    }

    private fun gone(block: String) = try {
        await(5_000) { manager.activeNotifications.none { it.tag == block && it.id == Alerts.PANE_ID } }
    } catch (e: AssertionError) {
        throw AssertionError("still up: " + manager.activeNotifications.filter { it.tag == block }.map { it.notification.text() }, e)
    }

    @Test
    fun aQuestionWhileAwayOffersReplyThatAnswersThePane() {
        val block = start().first()
        away()
        desk.rex.report(block, "blocked", kind = "question", app = "claude", msg = "Which branch?")
        val n = paneNotification(block)
        assertEquals(Alerts.CHANNEL_ATTENTION, n.channelId)
        assertTrue(n.title(), n.title().startsWith("claude"))
        assertEquals("${app.getString(me.leaver.rexpocket.R.string.state_waiting_answer)}: Which branch?", n.text())
        assertEquals("$sid", n.group.substringAfter('/'))
        assertNotNull("a blocked program can be interrupted", n.actionNamed(app.getString(me.leaver.rexpocket.R.string.alerts_interrupt)))

        val reply = n.actionNamed(app.getString(me.leaver.rexpocket.R.string.alerts_reply))!!
        val input = reply.remoteInputs.single()
        val filled = Intent(sent(reply.actionIntent))
        RemoteInput.addResultsToIntent(arrayOf(input), filled, Bundle().apply { putCharSequence(input.resultKey, "main") })
        AlertActionReceiver().onReceive(app, filled)
        awaitInputs(listOf("paste $block main", "key $block +enter"))
        gone(block)
    }

    @Test
    fun interruptSendsCtrlC() {
        val block = start().first()
        away()
        desk.rex.report(block, "blocked", kind = "permission", msg = "Run tests?")
        val n = paneNotification(block)
        AlertActionReceiver().onReceive(app, sent(n.actionNamed(app.getString(me.leaver.rexpocket.R.string.alerts_interrupt))!!.actionIntent))
        awaitInputs(listOf("key $block ctrl+key_c"))
        gone(block)
    }

    @Test
    fun aLoginIsNeverAnsweredFromTheNotification() {
        val block = start().first()
        away()
        desk.rex.report(block, "blocked", kind = "auth", msg = "Password")
        val n = paneNotification(block)
        assertEquals(null, n.actionNamed(app.getString(me.leaver.rexpocket.R.string.alerts_reply)))
    }

    @Test
    fun aFinishedProgramGoesOnItsQuieterChannelAndTheAlertGoesWithTheRecord() {
        val block = start().first()
        away()
        desk.rex.report(block, "done", app = "cargo", msg = "built in 3s")
        val n = paneNotification(block)
        assertEquals(Alerts.CHANNEL_DONE, n.channelId)
        assertEquals(null, n.actionNamed(app.getString(me.leaver.rexpocket.R.string.alerts_interrupt)))
        desk.rex.report(block, null)
        gone(block)
    }

    @Test
    fun finishedCanBeTurnedOff() {
        val (block, other) = start(AlertPrefs(done = false))
        away()
        desk.rex.report(block, "done", msg = "built")
        desk.rex.report(other, "error", msg = "crashed")
        assertEquals(Alerts.CHANNEL_ATTENTION, paneNotification(other).channelId)
        assertEquals(0, manager.activeNotifications.count { it.tag == block })
    }

    @Test
    fun aDesktopNotificationAndTheStatusForTheSameEventAreOneNotification() {
        val block = start().first()
        away()
        desk.rex.notify(block, "Claude Code", "Claude needs your permission to use Bash")
        val note = paneNotification(block)
        assertEquals(Alerts.CHANNEL_MESSAGES, note.channelId)
        assertEquals("Claude Code: Claude needs your permission to use Bash", note.text())
        desk.rex.report(block, "blocked", kind = "permission", msg = "Bash: rm -rf build")
        val status = paneNotification(block) { it.channelId == Alerts.CHANNEL_ATTENTION }
        assertTrue(status.text(), status.text().endsWith("Bash: rm -rf build"))
        assertEquals(1, manager.activeNotifications.count { it.tag == block })
    }

    @Test
    fun nothingIsPostedWhileTheAppIsOnScreen() {
        val block = start().first()
        desk.rex.report(block, "blocked", msg = "Proceed?")
        desk.rex.notify(block, "", "hello")
        await(5_000) { model.pocket.value.blockStatus[block]?.state == "blocked" }
        pause(300)
        assertEquals(0, manager.activeNotifications.count { it.id == Alerts.PANE_ID })
    }

    @Test
    fun aBellInThePaneOnScreenVibrates() {
        start()
        compose.runOnUiThread { model.openAlert(AlertTarget(desk.host.id, sid, model.pocket.value.views.getValue(sid).focusedBlock()!!.id)) }
        await(5_000) { model.route.value == Route.Term }
        val shown = model.pocket.value.view!!.focusedBlock()!!.id
        desk.rex.bell(shown)
        val vibrator = shadowOf(app.getSystemService(Vibrator::class.java))
        await(5_000) { vibrator.isVibrating }
    }

    @Test
    fun aBellOffScreenNotifiesOnlyWhenAskedFor() {
        val (block, other) = start(AlertPrefs(bells = true))
        away()
        desk.rex.bell(block)
        assertEquals(app.getString(me.leaver.rexpocket.R.string.alerts_bell), paneNotification(block).text())
        compose.runOnUiThread { model.setAlertPrefs(AlertPrefs()) }
        desk.rex.bell(other)
        pause(300)
        assertEquals(0, manager.activeNotifications.count { it.tag == other })
    }
}
