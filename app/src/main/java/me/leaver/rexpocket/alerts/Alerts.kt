package me.leaver.rexpocket.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.leaver.rexpocket.MainActivity
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.AlertKind
import me.leaver.rexpocket.core.AlertTokens
import me.leaver.rexpocket.core.PaneProgress
import me.leaver.rexpocket.core.ProgressState

// Background alerts: a quiet notification that keeps the app connected while it is away, and one
// notification per pane whose program needs the person, finished, or asked to tell them something.
// See docs/design/notifications.md.

/** The pane an alert opens. */
data class AlertTarget(val hostId: String, val sessionId: String, val blockId: String)

/**
 * One alert, already in the person's words. [group] is its session's label, under which the
 * notifications of one session sit together; [reply] and [interrupt] offer those actions. [answer]
 * is the prompt Approve and Deny answer ([me.leaver.rexpocket.core.promptStamp]), null when the
 * program is not an agent's approval prompt the app can answer; [choices] are the quick replies
 * Reply offers.
 */
data class Alert(
    val target: AlertTarget,
    val title: String,
    val text: String,
    val host: String,
    val kind: AlertKind = AlertKind.Waiting,
    val loud: Boolean = true,
    val group: String = "",
    val reply: Boolean = false,
    val interrupt: Boolean = false,
    val answer: String? = null,
    val choices: List<String> = emptyList(),
)

/** A tab's progress bar, for its notification: the pane it is from, the tab, its name and host. */
data class ProgressNote(val target: AlertTarget, val windowId: String, val title: String, val host: String, val progress: PaneProgress)

/** What a notification's action asks of the pane. */
sealed interface AlertAction {
    data class Reply(val text: String) : AlertAction
    data object Interrupt : AlertAction

    /** Approve or Deny the prompt [stamp] names: sent only while the pane's record is still that prompt. */
    data class Answer(val approve: Boolean, val stamp: String) : AlertAction
}

/** How a notification's action went. */
enum class AlertOutcome {
    /** Rex has the input: the alert has done its job. */
    Sent,

    /** The pane could not be reached. */
    NotSent,

    /** Approve or Deny found another prompt, or none, in the pane: nothing was typed. */
    Changed,
}

object Alerts {
    /**
     * Whether alerts are on, for the whole process: the watch notification's Stop turns them off
     * from outside any screen, and the screens follow.
     */
    val enabled = MutableStateFlow(false)

    /**
     * What carries out a notification's Reply, Interrupt, Approve or Deny: the connections set it,
     * and answer [AlertOutcome.Sent] once Rex has the input. Null while there is none.
     */
    @Volatile var actions: (suspend (AlertTarget, AlertAction) -> AlertOutcome)? = null

    private val tokens = AlertTokens<AlertTarget>()

    /** What is up, by pane, so a failed reply can be shown again and a group knows when it is empty. */
    private val posted = java.util.concurrent.ConcurrentHashMap<String, Alert>()

    /** Notifications are on for the app, and on Android 13 and later the person has allowed them. */
    fun allowed(context: Context): Boolean = permitted(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** On Android 13 and later, whether the person allowed notifications; always true before. */
    fun permitted(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Whether the person turned [kind]'s channel off in Android's settings. */
    fun channelBlocked(context: Context, kind: AlertKind): Boolean {
        channels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.getNotificationChannel(channelOf(kind))?.importance == NotificationManager.IMPORTANCE_NONE
    }

    /** Android's page for the app's notifications, or for one [kind]'s channel: sound, vibration, lock screen. */
    fun systemSettings(context: Context, kind: AlertKind? = null): Intent {
        channels(context)
        val intent = if (kind == null) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        } else {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, channelOf(kind))
        }
        return intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Starts the watch, which keeps the connections up while the app is away; it follows the
     * connected hosts itself from then on. Called while the app is on screen, which is when Android
     * allows it, or from the watch Android restarted.
     */
    fun watch(context: Context) {
        if (WatchService.running) return
        start(context, Intent(context, WatchService::class.java))
    }

    /**
     * Starts the watch with no screen, to connect the hosts kept in the background: after a reboot
     * or an update ([BootReceiver]), both moments Android lets an app start a foreground service from.
     */
    fun restore(context: Context) {
        start(context, Intent(context, WatchService::class.java).setAction(ACTION_RESTORE))
    }

    private fun start(context: Context, intent: Intent) {
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: IllegalStateException) {
            // Android 12 and later refuse a foreground service started from the background outside
            // the moments it allows (ForegroundServiceStartNotAllowedException); the next time the
            // app is on screen starts it.
            me.leaver.rexpocket.diagnostics.Diagnostics.event("RexPocketWatch", "watch not started: ${e.javaClass.simpleName}")
        }
    }

    fun unwatch(context: Context) {
        context.stopService(Intent(context, WatchService::class.java))
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun raise(context: Context, alert: Alert, note: String? = null) {
        // Notifications can be turned off in system settings while alerts are on; then there is no one to tell.
        if (!allowed(context)) return
        channels(context)
        val target = alert.target
        val token = tokens.issue(target)
        val open = Intent(context, MainActivity::class.java)
            .setData(Uri.Builder().scheme(SCHEME).authority("open").appendPath(token).build())
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(context, tagOf(target).hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = note ?: alert.text
        val group = groupOf(target)
        val builder = NotificationCompat.Builder(context, channelOf(alert.kind))
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(alert.title)
            .setContentText(text)
            .setSubText(alert.host)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(
                when (alert.kind) {
                    AlertKind.Failed -> NotificationCompat.CATEGORY_ERROR
                    AlertKind.Done -> NotificationCompat.CATEGORY_STATUS
                    else -> NotificationCompat.CATEGORY_MESSAGE
                },
            )
            .setPriority(if (alert.kind == AlertKind.Waiting || alert.kind == AlertKind.Failed) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setSilent(!alert.loud || note != null)
            .setGroup(group)
            // Only the pane's own notification sounds; its group's summary stays quiet.
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        // Approve and Deny answer an agent's approval prompt with its own keys. They want the phone
        // unlocked (Android 12 and later ask for it on a lock screen): an approval runs something
        // on the computer. A notification shows three actions, so they take Interrupt's place;
        // Deny stops the agent's action as Interrupt would.
        val answer = alert.answer
        if (answer != null) {
            builder.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_check, context.getString(R.string.alerts_approve), actionIntent(context, target, ACTION_APPROVE, mutable = false, stamp = answer))
                    .setShowsUserInterface(false)
                    .setAuthenticationRequired(true)
                    .build(),
            )
            builder.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_block, context.getString(R.string.alerts_deny), actionIntent(context, target, ACTION_DENY, mutable = false, stamp = answer))
                    .setShowsUserInterface(false)
                    .setAuthenticationRequired(true)
                    .build(),
            )
        }
        if (alert.reply) {
            val input = RemoteInput.Builder(KEY_REPLY).setLabel(context.getString(R.string.alerts_reply_hint))
                .apply { if (alert.choices.isNotEmpty()) setChoices(alert.choices.toTypedArray<CharSequence>()) }
                .build()
            builder.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_return, context.getString(R.string.alerts_reply), actionIntent(context, target, ACTION_REPLY, mutable = true))
                    .addRemoteInput(input)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setShowsUserInterface(false)
                    .setAllowGeneratedReplies(false)
                    .build(),
            )
        }
        if (alert.interrupt && answer == null) {
            builder.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_stop, context.getString(R.string.alerts_interrupt), actionIntent(context, target, ACTION_INTERRUPT, mutable = false))
                    .setShowsUserInterface(false)
                    .build(),
            )
        }
        posted[tagOf(target)] = alert
        val manager = NotificationManagerCompat.from(context)
        manager.notify(tagOf(target), PANE_ID, builder.build())
        summarize(context, target.hostId, alert)
    }

    /** The pane no longer waits, or the person went to it: its alert has said what it had to. */
    fun settle(context: Context, hostId: String, blockId: String) {
        val tag = tagOf(hostId, blockId)
        NotificationManagerCompat.from(context).cancel(tag, PANE_ID)
        val gone = posted.remove(tag) ?: return
        val group = groupOf(gone.target)
        val left = posted.values.filter { it.target.hostId == hostId }
        if (left.isEmpty()) NotificationManagerCompat.from(context).cancel(group, SUMMARY_ID) else summarize(context, hostId, left.last())
    }

    /** Puts the pane's alert back up, quietly, saying [why] an action on it did not go through. */
    fun failed(context: Context, hostId: String, blockId: String, why: String) {
        val alert = posted[tagOf(hostId, blockId)] ?: return
        raise(context, alert, note = why)
    }

    /**
     * Approve or Deny found the pane asking something else, or nothing: the alert comes back,
     * quietly, saying so, without Approve and Deny, and its tap opens the pane to see what it asks.
     */
    fun changed(context: Context, hostId: String, blockId: String) {
        val alert = posted[tagOf(hostId, blockId)] ?: return
        raise(context, alert.copy(answer = null), note = context.getString(R.string.alerts_prompt_changed))
    }

    /**
     * One summary per host, so the system shows a host's panes as one group, whatever session they
     * are in: with many hosts each is one line in the shade, which opens to its panes. It lists
     * them, and goes with the last of them.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun summarize(context: Context, hostId: String, alert: Alert) {
        val group = groupOf(alert.target)
        val alerts = posted.values.filter { it.target.hostId == hostId }
        val count = context.resources.getQuantityString(R.plurals.alerts_summary, alerts.size, alerts.size)
        val inbox = NotificationCompat.InboxStyle().setSummaryText(alert.host)
        alerts.takeLast(SUMMARY_LINES).forEach { inbox.addLine("${it.title}: ${it.text}") }
        val summary = NotificationCompat.Builder(context, channelOf(alert.kind))
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(alert.host)
            .setContentText(count)
            .setSubText(alert.host)
            .setStyle(inbox)
            .setGroup(group)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setSilent(true)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(group, SUMMARY_ID, summary)
    }

    /**
     * A tab's progress bar as a notification of its own, on the quiet progress channel: the tab's
     * name, the host, and the bar (Android's own, indeterminate for work with no percent). Changing
     * it says nothing new aloud; a tap opens the pane the bar is from.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun progress(context: Context, note: ProgressNote) {
        if (!allowed(context)) return
        channels(context)
        val target = note.target
        val tag = progressTag(target.hostId, note.windowId)
        // One token for the bar while it is from the same pane: a bar that moves every second must
        // not use up the tokens the panes' alerts hold.
        val token = progressTokens[tag]?.takeIf { it.first == target }?.second ?: tokens.issue(target).also { progressTokens[tag] = target to it }
        val open = Intent(context, MainActivity::class.java)
            .setData(Uri.Builder().scheme(SCHEME).authority("open").appendPath(token).build())
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(context, tag.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val p = note.progress
        val percent = p.percent
        val busy = p.state == ProgressState.Indeterminate || percent == null
        val text = when (p.state) {
            ProgressState.Normal -> context.getString(R.string.progress_normal, percent ?: 0)
            ProgressState.Indeterminate -> context.getString(R.string.progress_busy)
            ProgressState.Error -> if (percent != null) context.getString(R.string.progress_error, percent) else context.getString(R.string.progress_error_plain)
            ProgressState.Paused -> if (percent != null) context.getString(R.string.progress_paused, percent) else context.getString(R.string.progress_paused_plain)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(note.title)
            .setContentText(text)
            .setSubText(note.host)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, if (busy) 0 else percent ?: 0, busy && p.state == ProgressState.Indeterminate)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(tag, PROGRESS_ID, notification)
    }

    /** The tab's bar went, or the person is back: its progress notification goes. */
    fun clearProgress(context: Context, hostId: String, windowId: String) {
        progressTokens.remove(progressTag(hostId, windowId))
        NotificationManagerCompat.from(context).cancel(progressTag(hostId, windowId), PROGRESS_ID)
    }

    private val progressTokens = java.util.concurrent.ConcurrentHashMap<String, Pair<AlertTarget, String>>()

    /** A tab's progress notification tag: its host and its id. */
    fun progressTag(hostId: String, windowId: String) = "progress/$hostId/$windowId"

    /** A host's group: every pane of that host that has something to say, in any session. */
    internal fun groupOf(target: AlertTarget) = "host/${target.hostId}"

    /**
     * A pane's notification tag: its host and its id, since two hosts - the same Rex saved twice,
     * say - can have panes with the same id.
     */
    fun tagOf(hostId: String, blockId: String) = "$hostId/$blockId"

    private fun tagOf(target: AlertTarget) = tagOf(target.hostId, target.blockId)

    private fun actionIntent(context: Context, target: AlertTarget, action: String, mutable: Boolean, stamp: String? = null): PendingIntent {
        // Explicit, to a receiver nothing outside the app can reach; a reply's text is filled in by the system.
        val intent = Intent(context, AlertActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_HOST_ID, target.hostId)
            .putExtra(EXTRA_SESSION, target.sessionId)
            .putExtra(EXTRA_BLOCK, target.blockId)
            .apply { if (stamp != null) putExtra(EXTRA_STAMP, stamp) }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, (tagOf(target) + action).hashCode(), intent, flags)
    }

    /** The pane an alert's intent names, once; null for anything this process did not issue. */
    fun consume(intent: Intent?): AlertTarget? {
        val uri = intent?.data ?: return null
        if (uri.scheme != SCHEME || uri.authority != "open" || uri.pathSegments.size != 1) return null
        return tokens.consume(uri.lastPathSegment)
    }

    internal fun channelOf(kind: AlertKind) = when (kind) {
        AlertKind.Waiting, AlertKind.Failed -> CHANNEL_ATTENTION
        AlertKind.Done -> CHANNEL_DONE
        AlertKind.Message -> CHANNEL_MESSAGES
    }

    internal fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        fun channel(id: String, name: Int, body: Int, importance: Int) = manager.createNotificationChannel(
            NotificationChannel(id, context.getString(name), importance).apply { description = context.getString(body) },
        )
        channel(CHANNEL_WATCH, R.string.alerts_channel_watch, R.string.alerts_channel_watch_body, NotificationManager.IMPORTANCE_MIN)
        channel(CHANNEL_ATTENTION, R.string.alerts_channel_attention, R.string.alerts_channel_attention_body, NotificationManager.IMPORTANCE_HIGH)
        channel(CHANNEL_DONE, R.string.alerts_channel_done, R.string.alerts_channel_done_body, NotificationManager.IMPORTANCE_DEFAULT)
        channel(CHANNEL_MESSAGES, R.string.alerts_channel_messages, R.string.alerts_channel_messages_body, NotificationManager.IMPORTANCE_DEFAULT)
        channel(CHANNEL_PROGRESS, R.string.alerts_channel_progress, R.string.alerts_channel_progress_body, NotificationManager.IMPORTANCE_LOW)
    }

    /**
     * A bell in the pane on screen: a short buzz, as a notification's vibration, so the system's
     * own switches apply - none in silent mode, and none where notification vibration is off.
     */
    fun buzz(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio?.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        if (!vibrator.hasVibrator()) return
        val effect = VibrationEffect.createOneShot(BUZZ_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
        }
    }

    internal const val CHANNEL_WATCH = "watch"
    internal const val CHANNEL_ATTENTION = "attention"
    internal const val CHANNEL_DONE = "done"
    internal const val CHANNEL_MESSAGES = "messages"
    internal const val CHANNEL_PROGRESS = "progress"
    internal const val EXTRA_HOST = "host"
    internal const val EXTRA_HOST_ID = "host_id"
    internal const val EXTRA_SESSION = "session"
    internal const val EXTRA_BLOCK = "block"
    internal const val EXTRA_STAMP = "stamp"
    internal const val KEY_REPLY = "reply"
    internal const val ACTION_STOP = "me.leaver.rexpocket.alerts.STOP"
    internal const val ACTION_RESTORE = "me.leaver.rexpocket.alerts.RESTORE"
    internal const val ACTION_DISCONNECT = "me.leaver.rexpocket.alerts.DISCONNECT"
    internal const val ACTION_REPLY = "me.leaver.rexpocket.alerts.REPLY"
    internal const val ACTION_INTERRUPT = "me.leaver.rexpocket.alerts.INTERRUPT"
    internal const val ACTION_APPROVE = "me.leaver.rexpocket.alerts.APPROVE"
    internal const val ACTION_DENY = "me.leaver.rexpocket.alerts.DENY"
    internal const val PANE_ID = 2
    internal const val PROGRESS_ID = 4
    private const val SUMMARY_ID = 3
    private const val SUMMARY_LINES = 6
    private const val BUZZ_MS = 60L
    private const val SCHEME = "rexpocket-alert"
}

/**
 * A notification's Reply or Interrupt. It hands the input to the connection the app has open and
 * waits for Rex to take it: then the alert goes, as the person has answered; otherwise the alert
 * comes back saying it was not sent.
 */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val target = AlertTarget(
            intent.getStringExtra(Alerts.EXTRA_HOST_ID) ?: return,
            intent.getStringExtra(Alerts.EXTRA_SESSION) ?: return,
            intent.getStringExtra(Alerts.EXTRA_BLOCK) ?: return,
        )
        val action = when (intent.action) {
            Alerts.ACTION_REPLY -> AlertAction.Reply(RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Alerts.KEY_REPLY)?.toString() ?: return)
            Alerts.ACTION_INTERRUPT -> AlertAction.Interrupt
            Alerts.ACTION_APPROVE, Alerts.ACTION_DENY ->
                AlertAction.Answer(intent.action == Alerts.ACTION_APPROVE, intent.getStringExtra(Alerts.EXTRA_STAMP) ?: return)
            else -> return
        }
        val app = context.applicationContext
        // Null only when something calls onReceive itself, as a test does.
        val pending: PendingResult? = goAsync()
        scope.launch {
            try {
                val handler = Alerts.actions
                val outcome = handler?.let { withTimeoutOrNull(SEND_MS) { runCatching { it(target, action) }.getOrDefault(AlertOutcome.NotSent) } }
                when (outcome) {
                    AlertOutcome.Sent -> Alerts.settle(app, target.hostId, target.blockId)
                    AlertOutcome.Changed -> Alerts.changed(app, target.hostId, target.blockId)
                    AlertOutcome.NotSent, null -> Alerts.failed(app, target.hostId, target.blockId, app.getString(R.string.alerts_not_sent))
                }
            } finally {
                pending?.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** A broadcast may run for 10 s; the input has that, less a margin to put the alert back. */
        const val SEND_MS = 8_000L
    }
}
