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
import me.leaver.rexpocket.core.AlertEvent
import me.leaver.rexpocket.core.AlertPrefs
import me.leaver.rexpocket.core.HostBackground
import me.leaver.rexpocket.core.QuickReplies
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.with
import me.leaver.rexpocket.data.HostStore
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

    /** A second host, on a Rex of its own, for the tests of two hosts at once. */
    private val second by lazy { RexOnDesk(sessions = 1, hostId = "second-host", hostLabel = "second") }
    private var secondUsed = false

    @After
    fun stop() {
        if (::model.isInitialized) compose.runOnUiThread { model.disconnectAll() }
        Alerts.enabled.value = false
        desk.close()
        if (secondUsed) second.close()
    }

    /** A pane's notification tag: its host and its id. */
    private fun tag(block: String) = Alerts.tagOf(desk.host.id, block)

    /** The pane's notification, once it is up and [ok]. */
    private fun paneNotification(block: String, ok: (Notification) -> Boolean = { true }): Notification {
        var found: Notification? = null
        await(5_000) {
            found = manager.activeNotifications.firstOrNull { it.tag == tag(block) && it.id == Alerts.PANE_ID }?.notification?.takeIf(ok)
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
        await(5_000) { manager.activeNotifications.none { it.tag == tag(block) && it.id == Alerts.PANE_ID } }
    } catch (e: AssertionError) {
        throw AssertionError("still up: " + manager.activeNotifications.filter { it.tag == tag(block) }.map { it.notification.text() }, e)
    }

    /**
     * An action went through: the pane's notification says [what], quietly, with no actions, and
     * goes by itself after a few seconds (Android's setTimeoutAfter, which Robolectric does not run).
     */
    private fun confirmed(block: String, what: String) {
        val n = paneNotification(block) { it.text() == what }
        assertTrue("no actions on a confirmation", n.actions.isNullOrEmpty())
        assertEquals(Alerts.CONFIRM_MS, n.timeoutAfter)
        // The pane going back to work, as it does once answered, leaves the confirmation to go by itself.
        desk.rex.report(block, null)
        pause(300)
        assertEquals(what, paneNotification(block).text())
    }

    /** The intent a notification's tap delivers to the app. */
    private fun tapped(n: Notification): Intent = shadowOf(n.contentIntent).savedIntent

    /**
     * After Android killed and restarted the process, the app has no record of a notification it
     * posted: a Reply that cannot be sent still comes back saying so, from the action's own words,
     * so its spinner stops; and with a sound this once, as the person is waiting for it.
     */
    @Test
    fun aReplyTheAppHasNoRecordOfStillSaysItWasNotSent() {
        val block = start().first()
        away()
        desk.rex.report(block, "blocked", kind = "question", app = "claude", msg = "Which branch?")
        val n = paneNotification(block)
        val reply = n.actionNamed(label(R.string.alerts_reply))!!
        val input = reply.remoteInputs.single()
        val filled = Intent(sent(reply.actionIntent))
        RemoteInput.addResultsToIntent(arrayOf(input), filled, Bundle().apply { putCharSequence(input.resultKey, "main") })
        // As after a restart: no record of the alert, and nothing to send with.
        Alerts.settle(app, desk.host.id, block)
        gone(block)
        val handler = Alerts.actions
        Alerts.actions = null
        try {
            AlertActionReceiver().onReceive(app, filled)
            val back = paneNotification(block) { it.text() == label(R.string.alerts_not_sent) }
            assertEquals(n.title(), back.title())
            assertEquals("desk", back.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        } finally {
            Alerts.actions = handler
        }
    }

    /**
     * A notification's tap names its pane with the install's signature over it, so it still opens
     * the pane after a restart (the key read again from app-private storage), and any other link
     * of the app's scheme - an old one, a forged one - is an expired alert, never a pane.
     */
    @Test
    fun aTapOpensItsPaneAfterARestartAndAForgedOneDoesNot() {
        val block = start().first()
        away()
        desk.rex.report(block, "blocked", kind = "question", app = "claude", msg = "Which branch?")
        val intent = tapped(paneNotification(block))
        val target = AlertTarget(desk.host.id, sid, block)
        assertEquals(me.leaver.rexpocket.alerts.AlertLink.Pane(target), Alerts.linkOf(app, intent))
        Alerts.forgetKey()
        assertEquals("after a restart", me.leaver.rexpocket.alerts.AlertLink.Pane(target), Alerts.linkOf(app, intent))
        assertTrue("the key is in storage no backup holds", java.io.File(app.noBackupFilesDir, "alert-link.key").length() >= 32)

        val forged = Intent(intent).setData(intent.data!!.buildUpon().clearQuery()
            .appendQueryParameter("h", desk.host.id).appendQueryParameter("s", sid).appendQueryParameter("b", "other-block")
            .appendQueryParameter("sig", intent.data!!.getQueryParameter("sig")).build())
        assertEquals(me.leaver.rexpocket.alerts.AlertLink.Expired, Alerts.linkOf(app, forged))
        val old = Intent().setData(android.net.Uri.parse("rexpocket-alert://open/0f8e2c1a-5d1b-4c4e-9a52-1a2b3c4d5e6f"))
        assertEquals(me.leaver.rexpocket.alerts.AlertLink.Expired, Alerts.linkOf(app, old))
        assertEquals(null, Alerts.linkOf(app, Intent().setData(android.net.Uri.parse("https://example.com/"))))

        // The host's group summary opens the app at that host.
        val summary = manager.activeNotifications.first { it.tag == "host/${desk.host.id}" }.notification
        assertEquals(me.leaver.rexpocket.alerts.AlertLink.Host(desk.host.id), Alerts.linkOf(app, tapped(summary)))
        compose.runOnUiThread { model.openHostAlerts(desk.host.id) }
        assertEquals(Route.Home, model.route.value)
        assertEquals(HomeFilter.Activity, model.homeFilter.value)
    }

    /** An alert of a host removed since says so and goes, rather than doing nothing. */
    @Test
    fun anAlertOfARemovedHostSaysSoAndGoes() {
        val block = start().first()
        away()
        desk.rex.report(block, "blocked", kind = "question", app = "claude", msg = "Which branch?")
        paneNotification(block)
        compose.runOnUiThread {
            model.disconnectAll()
            HostStore(app).delete(desk.host.id)
            model.openAlert(AlertTarget(desk.host.id, sid, block))
        }
        gone(block)
        assertEquals(app.getString(R.string.alerts_host_gone), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertEquals(Route.Home, model.route.value)
    }

    /**
     * Mute on an alert, in the slot its other actions leave: the tab's panes alert no more, the
     * choice is kept with the host's, and the notification says where to undo it.
     */
    @Test
    fun muteOnAnAlertSilencesItsTab() {
        val (block, other) = start()
        away()
        desk.rex.report(block, "done", app = "cargo", msg = "built")
        val n = paneNotification(block)
        val mute = n.actionNamed(label(R.string.alerts_mute))!!
        AlertActionReceiver().onReceive(app, sent(mute.actionIntent))
        val window = model.pocket.value.views.getValue(sid).windows.first { w -> w.blocks.any { it.id == block } }.id
        await(5_000) { me.leaver.rexpocket.data.BackgroundStore(app).hosts().of(desk.host.id).mutes(window) }
        assertEquals(label(R.string.alerts_muted), paneNotification(block).text())
        desk.rex.report(block, "error", msg = "crashed")
        pause(500)
        assertEquals(label(R.string.alerts_muted), paneNotification(block).text())
        // Another tab still alerts, and the tab's ⋮ menu unmutes it.
        val otherWindow = model.pocket.value.views.getValue(sid).windows.first { w -> w.blocks.any { it.id == other } }.id
        if (otherWindow != window) {
            desk.rex.report(other, "error", msg = "also crashed")
            assertEquals(Alerts.CHANNEL_FAILED, paneNotification(other).channelId)
        }
        compose.runOnUiThread { model.muteTab(desk.host.id, window, false) }
        desk.rex.report(block, "blocked", kind = "question", msg = "Again?")
        paneNotification(block) { it.text().endsWith("Again?") }
    }

    /**
     * Failures have their own channel since waits and failures were split; made once from what the
     * person set on Needs you, so a quieted Needs you stays quiet for failures too.
     */
    @Test
    fun theFailuresChannelStartsFromWhatNeedsYouWasSetTo() {
        app = freshApp()
        Alerts.channels(app)
        manager.deleteNotificationChannel(Alerts.CHANNEL_FAILED)
        manager.deleteNotificationChannel(Alerts.CHANNEL_ATTENTION)
        manager.createNotificationChannel(
            android.app.NotificationChannel(Alerts.CHANNEL_ATTENTION, "Needs you", NotificationManager.IMPORTANCE_LOW).apply { enableVibration(false) },
        )
        Alerts.channels(app)
        val failed = manager.getNotificationChannel(Alerts.CHANNEL_FAILED)
        assertEquals(NotificationManager.IMPORTANCE_LOW, failed.importance)
        assertEquals(false, failed.shouldVibrate())
        assertEquals(app.getString(R.string.alerts_channel_failed), failed.name.toString())
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
        // Grouped by host, whatever the session: with many hosts, each is one group in the shade.
        assertEquals("host/${desk.host.id}", n.group)
        assertNotNull("a blocked program can be interrupted", n.actionNamed(app.getString(me.leaver.rexpocket.R.string.alerts_interrupt)))

        val reply = n.actionNamed(app.getString(me.leaver.rexpocket.R.string.alerts_reply))!!
        val input = reply.remoteInputs.single()
        val filled = Intent(sent(reply.actionIntent))
        RemoteInput.addResultsToIntent(arrayOf(input), filled, Bundle().apply { putCharSequence(input.resultKey, "main") })
        AlertActionReceiver().onReceive(app, filled)
        awaitInputs(listOf("paste $block main", "key $block +enter"))
        confirmed(block, app.getString(R.string.alerts_sent, "main"))
    }

    private fun progressOf(windowId: String) =
        manager.activeNotifications.firstOrNull { it.tag == Alerts.progressTag(desk.host.id, windowId) && it.id == Alerts.PROGRESS_ID }?.notification

    /**
     * With progress bars on (Notifications › Progress), a tab's OSC 9;4 bar while the app is away is
     * a quiet progress notification for the tab that follows the bar and goes with it, and when the
     * person is back. Off, the default, there is none.
     */
    @Test
    fun aProgressBarWhileAwayIsANotificationForItsTabWhenAskedFor() {
        val block = start(AlertPrefs(progress = true)).first()
        val window = model.pocket.value.views.getValue(sid).windows.first { w -> w.blocks.any { it.id == block } }.id
        away()
        desk.rex.progress(block, 1, 30)
        var n: Notification? = null
        await(5_000) { n = progressOf(window); n != null }
        assertEquals(Alerts.CHANNEL_PROGRESS, n!!.channelId)
        assertEquals(30, n!!.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(100, n!!.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(app.getString(R.string.progress_normal, 30), n!!.text())
        assertEquals("desk", n!!.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())

        desk.rex.progress(block, 3)
        await(5_000) { progressOf(window)?.extras?.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) == true }
        desk.rex.progress(block, 0)
        await(5_000) { progressOf(window) == null }

        // Back on screen, the bar is the screen's: no notification for it.
        desk.rex.progress(block, 1, 50)
        await(5_000) { progressOf(window) != null }
        compose.runOnUiThread { model.setForeground(true) }
        await(5_000) { progressOf(window) == null }
    }

    @Test
    fun progressBarsMakeNoNotificationByDefault() {
        val block = start().first()
        val window = model.pocket.value.views.getValue(sid).windows.first { w -> w.blocks.any { it.id == block } }.id
        away()
        desk.rex.progress(block, 1, 30)
        await(5_000) { model.pocket.value.progress[block] != null }
        pause(1_500)
        assertEquals(null, progressOf(window))
    }

    /**
     * Two hosts connected while the app is away: each one's program raises its own notification,
     * which names its host, and a Reply goes to the host it came from and to no other.
     */
    @Test
    fun twoHostsAlertEachWithItsHostAndAReplyGoesToItsHost() {
        val block = start().first()
        secondUsed = true
        compose.runOnUiThread {
            HostStore(app).upsert(second.host)
            model.connect(second.host)
        }
        await(15_000) { model.lives.value[second.host.id]?.state?.let { it.phase == me.leaver.rexpocket.core.Phase.Live && second.ids[0] in it.views } == true }
        val otherBlock = model.lives.value.getValue(second.host.id).state.views.getValue(second.ids[0]).allBlocks().first().id
        away()
        desk.rex.report(block, "blocked", kind = "question", app = "claude", msg = "First?")
        second.rex.report(otherBlock, "blocked", kind = "question", app = "codex", msg = "Second?")
        val first = paneNotification(block)
        var other: Notification? = null
        await(5_000) {
            other = manager.activeNotifications.firstOrNull { it.tag == Alerts.tagOf(second.host.id, otherBlock) && it.id == Alerts.PANE_ID }?.notification
            other != null
        }
        assertEquals("desk", first.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        assertEquals("second", other!!.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())

        val reply = other!!.actionNamed(app.getString(me.leaver.rexpocket.R.string.alerts_reply))!!
        val input = reply.remoteInputs.single()
        val filled = Intent(sent(reply.actionIntent))
        RemoteInput.addResultsToIntent(arrayOf(input), filled, Bundle().apply { putCharSequence(input.resultKey, "yes") })
        AlertActionReceiver().onReceive(app, filled)
        await(5_000) { second.rex.inputs.toList() == listOf("paste $otherBlock yes", "key $otherBlock +enter") }
        assertTrue("the first host got input meant for the second: ${desk.rex.inputs}", desk.rex.inputs.isEmpty())
        // The first host's notification is still up; only the answered one went.
        await(5_000) { manager.activeNotifications.any { it.tag == Alerts.tagOf(second.host.id, otherBlock) && it.notification.text() == app.getString(R.string.alerts_sent, "yes") } }
        assertEquals(1, manager.activeNotifications.count { it.tag == tag(block) && it.id == Alerts.PANE_ID })
    }

    @Test
    fun interruptSendsCtrlC() {
        val block = start().first()
        away()
        desk.rex.report(block, "blocked", kind = "permission", msg = "Run tests?")
        val n = paneNotification(block)
        AlertActionReceiver().onReceive(app, sent(n.actionNamed(app.getString(me.leaver.rexpocket.R.string.alerts_interrupt))!!.actionIntent))
        awaitInputs(listOf("key $block ctrl+key_c"))
        confirmed(block, app.getString(R.string.alerts_interrupted))
    }

    @Test
    fun aLoginIsNeverAnsweredFromTheNotification() {
        val block = start().first()
        away()
        desk.rex.report(block, "blocked", kind = "auth", msg = "Password")
        val n = paneNotification(block)
        assertEquals(null, n.actionNamed(app.getString(me.leaver.rexpocket.R.string.alerts_reply)))
    }

    private fun label(id: Int) = app.getString(id)

    /**
     * An agent the app knows (Claude Code) waiting for approval: Approve, Deny and Reply - no
     * Interrupt, as a notification shows three actions - with Approve and Deny wanting the phone
     * unlocked, and Reply offering the quick replies. Approve types the agent's key and the alert goes.
     */
    @Test
    fun anAgentsApprovalPromptOffersApproveAndDenyThatWantThePhoneUnlocked() {
        val block = start().first()
        away()
        desk.rex.report(block, "blocked", kind = "permission", app = "claude", msg = "Allow Bash: npm test?")
        val n = paneNotification(block)
        assertEquals(
            listOf(label(R.string.alerts_approve), label(R.string.alerts_deny), label(R.string.alerts_reply)),
            n.actions.map { it.title.toString() },
        )
        val approve = n.actionNamed(label(R.string.alerts_approve))!!
        val deny = n.actionNamed(label(R.string.alerts_deny))!!
        assertTrue("Approve wants the phone unlocked", approve.isAuthenticationRequired)
        assertTrue("Deny wants the phone unlocked", deny.isAuthenticationRequired)
        val reply = n.actionNamed(label(R.string.alerts_reply))!!
        assertEquals(listOf("yes", "continue", "/compact"), reply.remoteInputs.single().choices.map { it.toString() })

        AlertActionReceiver().onReceive(app, sent(approve.actionIntent))
        awaitInputs(listOf("key $block +enter"))
        confirmed(block, label(R.string.alerts_approved))
    }

    @Test
    fun denyForCodexIsEscape() {
        val block = start().first()
        away()
        desk.rex.report(block, "blocked", kind = "permission", app = "codex", msg = "Run cargo test?")
        val deny = paneNotification(block).actionNamed(label(R.string.alerts_deny))!!
        AlertActionReceiver().onReceive(app, sent(deny.actionIntent))
        awaitInputs(listOf("key $block +escape"))
        confirmed(block, label(R.string.alerts_denied))
    }

    /**
     * The program asked something else since the alert, and the phone did not hear it (Rex's
     * event was lost while it reconnected): Approve reads the record again, types nothing, and the
     * alert comes back saying the prompt changed, without Approve and Deny, its tap opening the pane.
     */
    @Test
    fun approveForAPromptThatChangedTypesNothingAndSaysSo() {
        val block = start().first()
        away()
        desk.rex.report(block, "blocked", kind = "permission", app = "claude", msg = "Allow Bash: ls?")
        val approve = paneNotification(block).actionNamed(label(R.string.alerts_approve))!!
        Thread.sleep(5)
        desk.rex.report(block, "blocked", kind = "permission", app = "claude", msg = "Allow Bash: rm -rf /?", quiet = true)
        AlertActionReceiver().onReceive(app, sent(approve.actionIntent))
        val changed = paneNotification(block) { it.text() == label(R.string.alerts_prompt_changed) }
        assertEquals(null, changed.actionNamed(label(R.string.alerts_approve)))
        assertEquals(null, changed.actionNamed(label(R.string.alerts_deny)))
        assertNotNull("its tap still opens the pane", changed.contentIntent)
        pause(300)
        assertTrue("nothing was typed: ${desk.rex.inputs}", desk.rex.inputs.isEmpty())
    }

    /** No Approve for a program the app has no keys for, for a question, or for a login. */
    @Test
    fun onlyAKnownAgentsApprovalPromptGetsApprove() {
        val (block, other) = start()
        away()
        desk.rex.report(block, "blocked", kind = "permission", app = "terraform", msg = "Apply 3 to add?")
        val unknown = paneNotification(block)
        assertEquals(null, unknown.actionNamed(label(R.string.alerts_approve)))
        assertNotNull("Interrupt stays", unknown.actionNamed(label(R.string.alerts_interrupt)))
        assertNotNull("Reply stays", unknown.actionNamed(label(R.string.alerts_reply)))
        desk.rex.report(other, "blocked", kind = "auth", app = "claude", msg = "Sign in")
        val login = paneNotification(other)
        assertEquals(null, login.actionNamed(label(R.string.alerts_approve)))
        assertEquals(null, login.actionNamed(label(R.string.alerts_reply)))
        desk.rex.report(block, "blocked", kind = "question", app = "claude", msg = "Which branch?")
        val question = paneNotification(block) { it.text().endsWith("Which branch?") }
        assertEquals(null, question.actionNamed(label(R.string.alerts_approve)))
    }

    /** Reply's choices are Settings › Quick replies, the short one-line ones, as they are now. */
    @Test
    fun replyOffersTheQuickRepliesAsSetNow() {
        val block = start().first()
        compose.runOnUiThread { model.setQuickReplies(QuickReplies(listOf("lgtm", "a longer\nprompt", "/clear"))) }
        away()
        desk.rex.report(block, "blocked", kind = "question", app = "claude", msg = "Next?")
        val reply = paneNotification(block).actionNamed(label(R.string.alerts_reply))!!
        val input = reply.remoteInputs.single()
        assertEquals(listOf("lgtm", "/clear"), input.choices.map { it.toString() })
        // A choice is sent as Reply sends text: pasted, then Return.
        val filled = Intent(sent(reply.actionIntent))
        RemoteInput.addResultsToIntent(arrayOf(input), filled, Bundle().apply { putCharSequence(input.resultKey, "/clear") })
        AlertActionReceiver().onReceive(app, filled)
        awaitInputs(listOf("paste $block /clear", "key $block +enter"))
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
        assertEquals(Alerts.CHANNEL_FAILED, paneNotification(other).channelId)
        assertEquals(0, manager.activeNotifications.count { it.tag == tag(block) })
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
        assertEquals(1, manager.activeNotifications.count { it.tag == tag(block) })
    }

    /**
     * A host's own choices win over the defaults, the rest follow them; a silenced host posts
     * nothing at all. Settings › Background and notifications, a host's page.
     */
    @Test
    fun aHostsOwnChoicesDecideItsNotifications() {
        val (block, other) = start()
        compose.runOnUiThread {
            model.setHostBackground(desk.host.id, HostBackground().with(AlertEvent.Waiting, false, AlertPrefs()))
        }
        away()
        desk.rex.report(block, "blocked", kind = "question", msg = "Which?")
        // Done is not this host's own choice: it follows the default, which is on.
        desk.rex.report(other, "done", msg = "built")
        paneNotification(other)
        assertEquals(0, manager.activeNotifications.count { it.tag == tag(block) })
        // Silenced, the host says nothing, whatever the kind.
        compose.runOnUiThread { model.setHostBackground(desk.host.id, HostBackground(notify = false)) }
        desk.rex.report(block, "error", msg = "crashed")
        pause(500)
        assertEquals(0, manager.activeNotifications.count { it.tag == tag(block) })
        assertEquals(HostBackground(notify = false), me.leaver.rexpocket.data.BackgroundStore(app).hosts().of(desk.host.id))
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
        assertEquals(0, manager.activeNotifications.count { it.tag == tag(other) })
    }
}
