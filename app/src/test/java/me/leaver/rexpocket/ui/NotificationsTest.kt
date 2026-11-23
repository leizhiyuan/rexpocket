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
        gone(block)
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
        await(5_000) { manager.activeNotifications.none { it.tag == Alerts.tagOf(second.host.id, otherBlock) } }
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
        gone(block)
    }

    @Test
    fun denyForCodexIsEscape() {
        val block = start().first()
        away()
        desk.rex.report(block, "blocked", kind = "permission", app = "codex", msg = "Run cargo test?")
        val deny = paneNotification(block).actionNamed(label(R.string.alerts_deny))!!
        AlertActionReceiver().onReceive(app, sent(deny.actionIntent))
        awaitInputs(listOf("key $block +escape"))
        gone(block)
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
        assertEquals(Alerts.CHANNEL_ATTENTION, paneNotification(other).channelId)
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
