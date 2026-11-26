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
import me.leaver.rexpocket.core.AlertSigner
import me.leaver.rexpocket.core.Connectable
import me.leaver.rexpocket.core.PaneProgress
import me.leaver.rexpocket.core.ProgressState
import me.leaver.rexpocket.data.BackgroundStore
import java.io.File

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
 * Reply offers. [windowId] is the pane's tab, which Mute silences.
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
    val windowId: String? = null,
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

/** What a tapped notification asks the app to open, once its signature is checked. */
sealed interface AlertLink {
    /** An alert's pane. */
    data class Pane(val target: AlertTarget) : AlertLink

    /** A host's group summary: the app at that host. */
    data class Host(val hostId: String) : AlertLink

    /** An alert link whose signature this install cannot check: made before a reinstall, or by someone else. */
    data object Expired : AlertLink
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

    /**
     * What mutes a tab from a notification's Mute: the connections set it, so their choices change
     * at once. Null while there are none; the choice is then written to the store directly.
     */
    @Volatile var mute: ((hostId: String, windowId: String) -> Unit)? = null

    /** What is up, by pane, so a failed reply can be shown again and a group knows when it is empty. */
    private val posted = java.util.concurrent.ConcurrentHashMap<String, Alert>()

    /** Panes whose notification now says how an action went, and goes by itself (setTimeoutAfter). */
    private val confirming = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile private var signer: AlertSigner? = null

    /**
     * The install's key for alert links: made once, kept in app-private storage that no backup and
     * no export holds (noBackupFilesDir), never logged.
     */
    @Synchronized
    private fun signer(context: Context): AlertSigner {
        val file = File(context.applicationContext.noBackupFilesDir, KEY_FILE)
        // The key in memory while its file is there: one that was cleared under the process is made again.
        signer?.takeIf { file.exists() }?.let { return it }
        val key = runCatching { file.readBytes() }.getOrNull()?.takeIf { it.size >= AlertSigner.MIN_KEY_BYTES } ?: AlertSigner.newKey().also { k ->
            val tmp = File(file.parentFile, "$KEY_FILE.tmp")
            tmp.writeBytes(k)
            tmp.renameTo(file)
        }
        return AlertSigner(key).also { signer = it }
    }

    /** Forgets the key read into memory, as a new process would: for a test of what outlives one. */
    @androidx.annotation.VisibleForTesting
    internal fun forgetKey() {
        synchronized(this) { signer = null }
    }

    /** Notifications are on for the app, and on Android 13 and later the person has allowed them. */
    fun allowed(context: Context): Boolean = permitted(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** On Android 13 and later, whether the person allowed notifications; always true before. */
    fun permitted(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Whether the person turned [kind]'s channel off in Android's settings. */
    fun channelBlocked(context: Context, kind: AlertKind): Boolean = channelOff(context, channelOf(kind))

    /** Whether the person turned channel [id] off in Android's settings. */
    fun channelOff(context: Context, id: String): Boolean {
        channels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.getNotificationChannel(id)?.importance == NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Whether Android hides the watch notification: notifications not allowed, or the Background
     * connection channel switched off. The foreground service runs either way (Android 13 and
     * later let it run without the permission); only its notification and its buttons are gone.
     */
    fun watchHidden(context: Context): Boolean {
        if (!allowed(context)) return true
        channels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.getNotificationChannel(CHANNEL_WATCH)?.importance == NotificationManager.IMPORTANCE_NONE
    }

    /** Where to show the watch notification again: the app's notifications, or the watch's channel. */
    fun watchSettings(context: Context): Intent {
        channels(context)
        val intent = if (!allowed(context)) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        } else {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_WATCH)
        }
        return intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Android's page for the app's notifications, or for one [kind]'s channel: sound, vibration, lock screen. */
    fun systemSettings(context: Context, kind: AlertKind? = null): Intent = channelSettings(context, kind?.let(::channelOf))

    /** Android's page for the app's notifications, or for channel [id]. */
    fun channelSettings(context: Context, id: String?): Intent {
        channels(context)
        val intent = if (id == null) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        } else {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, id)
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

    /** Opens Settings › Background and notifications in the app: [MainActivity]'s action. */
    fun notificationsPage(context: Context): Intent =
        Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_NOTIFICATIONS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /**
     * Background mode was turned on again, from anywhere: the notes that said it had stopped go,
     * and so does Android's stop the page shows.
     */
    fun backOn(context: Context) {
        NotificationManagerCompat.from(context).cancel(STOPPED_ID)
        clearAndroidStop(context)
    }

    /** When Android last stopped background mode and could not start it again, or null. */
    fun androidStop(context: Context): Long? =
        prefs(context).getLong(KEY_ANDROID_STOP, 0L).takeIf { it > 0L }

    /** Android stopped background mode: the page says so with its fix, even with notifications off. */
    fun recordAndroidStop(context: Context, at: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_ANDROID_STOP, at).commit()
    }

    fun clearAndroidStop(context: Context) {
        if (prefs(context).contains(KEY_ANDROID_STOP)) prefs(context).edit().remove(KEY_ANDROID_STOP).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("rexpocket", Context.MODE_PRIVATE)

    /**
     * A tailnet that, while the app is away, needs a sign-in ([signIn]) or stopped working: one
     * notification per tailnet (tag `tailnet/<id>`), which opens the tailnet's page. Its "Sign-in
     * and failures" choice turns it off.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun tailnetTrouble(context: Context, id: String, name: String, signIn: Boolean) {
        if (!allowed(context)) return
        channels(context)
        val page = Intent(context, MainActivity::class.java).setAction(TileStore.ACTION_OPEN_PAGE)
            .putExtra(TileStore.EXTRA_ITEM, Connectable.tailnet(id).key)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(context, "tailnet/$id".hashCode(), page, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = context.getString(if (signIn) R.string.tailnet_alert_sign_in else R.string.tailnet_alert_failed, name)
        val body = context.getString(if (signIn) R.string.tailnet_alert_sign_in_body else R.string.tailnet_alert_failed_body)
        val n = NotificationCompat.Builder(context, if (signIn) CHANNEL_ATTENTION else CHANNEL_FAILED)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify("tailnet/$id", TAILNET_ID, n)
    }

    fun settleTailnet(context: Context, id: String) {
        NotificationManagerCompat.from(context).cancel("tailnet/$id", TAILNET_ID)
    }

    /**
     * Posts [alert]. With [note] it is the alert again saying why an action did not go through,
     * quiet unless [sound]: a failed send alerts once, as the person is waiting for it.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun raise(context: Context, alert: Alert, note: String? = null, sound: Boolean = false) {
        // Notifications can be turned off in system settings while alerts are on; then there is no one to tell.
        if (!allowed(context)) return
        channels(context)
        val target = alert.target
        val tap = PendingIntent.getActivity(context, tagOf(target).hashCode(), openIntent(context, target), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
            .setSilent(if (note != null) !sound else !alert.loud)
            .setGroup(group)
            // Only the pane's own notification sounds; its group's summary stays quiet.
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        var actions = 0
        // Approve and Deny answer an agent's approval prompt with its own keys. They want the phone
        // unlocked (Android 12 and later ask for it on a lock screen): an approval runs something
        // on the computer. A notification shows three actions, so they take Interrupt's place;
        // Deny stops the agent's action as Interrupt would.
        val answer = alert.answer
        if (answer != null) {
            builder.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_check, context.getString(R.string.alerts_approve), actionIntent(context, alert, ACTION_APPROVE, mutable = false, stamp = answer))
                    .setShowsUserInterface(false)
                    .setAuthenticationRequired(true)
                    .build(),
            )
            builder.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_block, context.getString(R.string.alerts_deny), actionIntent(context, alert, ACTION_DENY, mutable = false, stamp = answer))
                    .setShowsUserInterface(false)
                    .setAuthenticationRequired(true)
                    .build(),
            )
            actions += 2
        }
        if (alert.reply) {
            val input = RemoteInput.Builder(KEY_REPLY).setLabel(context.getString(R.string.alerts_reply_hint))
                .apply { if (alert.choices.isNotEmpty()) setChoices(alert.choices.toTypedArray<CharSequence>()) }
                .build()
            builder.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_return, context.getString(R.string.alerts_reply), actionIntent(context, alert, ACTION_REPLY, mutable = true))
                    .addRemoteInput(input)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setShowsUserInterface(false)
                    .setAllowGeneratedReplies(false)
                    .build(),
            )
            actions++
        }
        if (alert.interrupt && answer == null) {
            builder.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_stop, context.getString(R.string.alerts_interrupt), actionIntent(context, alert, ACTION_INTERRUPT, mutable = false))
                    .setShowsUserInterface(false)
                    .build(),
            )
            actions++
        }
        // Mute in the slot left, if one is: the tab's ⋮ menu mutes it too, and always has room.
        if (alert.windowId != null && actions < MAX_ACTIONS) {
            builder.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_notifications_off, context.getString(R.string.alerts_mute), actionIntent(context, alert, ACTION_MUTE, mutable = false))
                    .setShowsUserInterface(false)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MUTE)
                    .build(),
            )
        }
        posted[tagOf(target)] = alert
        confirming.remove(tagOf(target))
        val manager = NotificationManagerCompat.from(context)
        manager.notify(tagOf(target), PANE_ID, builder.build())
        summarize(context, target.hostId, alert)
    }

    /** The pane no longer waits, or the person went to it: its alert has said what it had to. */
    fun settle(context: Context, hostId: String, blockId: String) {
        val tag = tagOf(hostId, blockId)
        // A note saying how an action went goes by itself, in a few seconds; the pane going back
        // to work, as it does once it has the answer, leaves it.
        if (!confirming.contains(tag) || posted.containsKey(tag)) NotificationManagerCompat.from(context).cancel(tag, PANE_ID)
        val gone = posted.remove(tag) ?: return
        val group = groupOf(gone.target)
        val left = posted.values.filter { it.target.hostId == hostId }
        if (left.isEmpty()) NotificationManagerCompat.from(context).cancel(group, SUMMARY_ID) else summarize(context, hostId, left.last())
    }

    /** Every notification of host [hostId]: a host no longer saved. */
    fun settleHost(context: Context, hostId: String) {
        val manager = NotificationManagerCompat.from(context)
        val prefix = "$hostId/"
        runCatching { manager.activeNotifications }.getOrDefault(emptyList())
            .filter { it.tag?.startsWith(prefix) == true || it.tag == "host/$hostId" || it.tag?.startsWith("progress/$prefix") == true }
            .forEach { manager.cancel(it.tag, it.id) }
        posted.keys.filter { it.startsWith(prefix) }.forEach { posted.remove(it) }
    }

    /**
     * Puts the pane's alert back up, saying [why] an action on it did not go through, with a sound
     * this once. After a restart the app has no record of it: [fallback], made from the action's
     * own intent, stands in, so the reply's spinner always stops.
     */
    fun failed(context: Context, target: AlertTarget, why: String, fallback: Alert? = null) {
        val alert = posted[tagOf(target)] ?: fallback ?: return
        raise(context, alert, note = why, sound = true)
    }

    /**
     * Approve or Deny found the pane asking something else, or nothing: the alert comes back,
     * quietly, saying so, without Approve and Deny, and its tap opens the pane to see what it asks.
     */
    fun changed(context: Context, target: AlertTarget, fallback: Alert? = null) {
        val alert = posted[tagOf(target)] ?: fallback ?: return
        raise(context, alert.copy(answer = null), note = context.getString(R.string.alerts_prompt_changed))
    }

    /**
     * An action went through: the pane's notification says so ([what]: "Approved", "Sent: yes"),
     * quietly, with nothing to tap but the pane, and goes by itself after [CONFIRM_MS]. Replacing
     * the notification is also what stops the reply's spinner.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun confirm(context: Context, target: AlertTarget, what: String, fallback: Alert? = null) {
        val tag = tagOf(target)
        val alert = posted[tag] ?: fallback
        settle(context, target.hostId, target.blockId)
        if (alert == null || !allowed(context)) return
        val tap = PendingIntent.getActivity(context, tag.hashCode(), openIntent(context, target), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, channelOf(alert.kind))
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(alert.title)
            .setContentText(what)
            .setSubText(alert.host)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(CONFIRM_MS)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        confirming += tag
        NotificationManagerCompat.from(context).notify(tag, PANE_ID, n)
    }

    /**
     * One summary per host, so the system shows a host's panes as one group, whatever session they
     * are in: with many hosts each is one line in the shade, which opens to its panes. It lists
     * them, goes with the last of them, and its tap opens the app at that host.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun summarize(context: Context, hostId: String, alert: Alert) {
        val group = groupOf(alert.target)
        val alerts = posted.values.filter { it.target.hostId == hostId }
        val count = context.resources.getQuantityString(R.plurals.alerts_summary, alerts.size, alerts.size)
        val inbox = NotificationCompat.InboxStyle().setSummaryText(alert.host)
        alerts.takeLast(SUMMARY_LINES).forEach { inbox.addLine("${it.title}: ${it.text}") }
        val tap = PendingIntent.getActivity(context, group.hashCode(), hostIntent(context, hostId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
            .setContentIntent(tap)
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
        val tap = PendingIntent.getActivity(context, tag.hashCode(), openIntent(context, target), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
            .setProgress(100, if (busy) 0 else percent, busy && p.state == ProgressState.Indeterminate)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(tag, PROGRESS_ID, notification)
    }

    /** The tab's bar went, or the person is back: its progress notification goes. */
    fun clearProgress(context: Context, hostId: String, windowId: String) {
        NotificationManagerCompat.from(context).cancel(progressTag(hostId, windowId), PROGRESS_ID)
    }

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

    /**
     * What a tap on a pane's notification opens: the pane, named in the intent with the install's
     * signature over it, so it opens after a restart too, and only what the app itself signed.
     */
    internal fun openIntent(context: Context, target: AlertTarget): Intent {
        val sig = signer(context).sign(LINK_PANE, target.hostId, target.sessionId, target.blockId)
        val uri = Uri.Builder().scheme(SCHEME).authority(LINK_PANE)
            .appendQueryParameter(Q_HOST, target.hostId).appendQueryParameter(Q_SESSION, target.sessionId)
            .appendQueryParameter(Q_BLOCK, target.blockId).appendQueryParameter(Q_SIG, sig).build()
        return Intent(context, MainActivity::class.java).setData(uri)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    /** What a tap on a host's group summary opens: the app at that host, signed as [openIntent] is. */
    internal fun hostIntent(context: Context, hostId: String): Intent {
        val sig = signer(context).sign(LINK_HOST, hostId)
        val uri = Uri.Builder().scheme(SCHEME).authority(LINK_HOST)
            .appendQueryParameter(Q_HOST, hostId).appendQueryParameter(Q_SIG, sig).build()
        return Intent(context, MainActivity::class.java).setData(uri)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    /**
     * What an alert's intent asks to open, once its signature is checked; [AlertLink.Expired] for
     * an alert link this install did not sign (one from before a reinstall, or anyone else's), and
     * null for an intent that is no alert's.
     */
    fun linkOf(context: Context, intent: Intent?): AlertLink? {
        val uri = intent?.data ?: return null
        if (uri.scheme != SCHEME) return null
        val sig = uri.getQueryParameter(Q_SIG)
        val host = uri.getQueryParameter(Q_HOST)
        return when (uri.authority) {
            LINK_PANE -> {
                val session = uri.getQueryParameter(Q_SESSION)
                val block = uri.getQueryParameter(Q_BLOCK)
                if (host == null || session == null || block == null || !signer(context).verify(sig, LINK_PANE, host, session, block)) {
                    AlertLink.Expired
                } else {
                    AlertLink.Pane(AlertTarget(host, session, block))
                }
            }
            LINK_HOST -> if (host == null || !signer(context).verify(sig, LINK_HOST, host)) AlertLink.Expired else AlertLink.Host(host)
            else -> AlertLink.Expired
        }
    }

    private fun actionIntent(context: Context, alert: Alert, action: String, mutable: Boolean, stamp: String? = null): PendingIntent {
        val target = alert.target
        // Explicit, to a receiver nothing outside the app can reach; a reply's text is filled in by
        // the system. The alert's words go with it, so a process started again can still say how it went.
        val intent = Intent(context, AlertActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_HOST_ID, target.hostId)
            .putExtra(EXTRA_SESSION, target.sessionId)
            .putExtra(EXTRA_BLOCK, target.blockId)
            .putExtra(EXTRA_TITLE, alert.title)
            .putExtra(EXTRA_HOST, alert.host)
            .putExtra(EXTRA_KIND, alert.kind.name)
            .apply {
                if (stamp != null) putExtra(EXTRA_STAMP, stamp)
                if (alert.windowId != null) putExtra(EXTRA_WINDOW, alert.windowId)
            }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, (tagOf(target) + action).hashCode(), intent, flags)
    }

    /** The alert an action's [intent] came from, from its own words: for a process that has no record of it. */
    internal fun alertOf(intent: Intent, target: AlertTarget): Alert = Alert(
        target = target,
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
        text = "",
        host = intent.getStringExtra(EXTRA_HOST).orEmpty(),
        kind = AlertKind.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_KIND) } ?: AlertKind.Waiting,
        windowId = intent.getStringExtra(EXTRA_WINDOW),
    )

    /** Tab [windowId] of [hostId] muted from a notification: through the connections when there are some. */
    internal fun muteTab(context: Context, hostId: String, windowId: String) {
        val handler = mute
        if (handler != null) {
            handler(hostId, windowId)
        } else {
            val store = BackgroundStore(context)
            val all = store.hosts()
            store.setHosts(all.with(hostId, all.of(hostId).withMuted(windowId, true)))
        }
    }

    internal fun channelOf(kind: AlertKind) = when (kind) {
        AlertKind.Waiting -> CHANNEL_ATTENTION
        AlertKind.Failed -> CHANNEL_FAILED
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
        failedChannel(context, manager)
        channel(CHANNEL_DONE, R.string.alerts_channel_done, R.string.alerts_channel_done_body, NotificationManager.IMPORTANCE_DEFAULT)
        channel(CHANNEL_MESSAGES, R.string.alerts_channel_messages, R.string.alerts_channel_messages_body, NotificationManager.IMPORTANCE_DEFAULT)
        channel(CHANNEL_PROGRESS, R.string.alerts_channel_progress, R.string.alerts_channel_progress_body, NotificationManager.IMPORTANCE_LOW)
    }

    /**
     * Failures have their own channel since failures and waits were split. Made once, from what
     * the person set on Needs you, which failures went on before: its importance (off stays off),
     * sound, vibration, lights, badge and lock screen. Do Not Disturb's override is Android's to
     * give, so it starts unset, as on any new channel.
     */
    private fun failedChannel(context: Context, manager: NotificationManager) {
        val existing = manager.getNotificationChannel(CHANNEL_FAILED)
        if (existing != null) {
            // Only the name and description follow the app's language; the rest is the person's.
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_FAILED, context.getString(R.string.alerts_channel_failed), existing.importance)
                    .apply { description = context.getString(R.string.alerts_channel_failed_body) },
            )
            return
        }
        val from = manager.getNotificationChannel(CHANNEL_ATTENTION)
        val channel = NotificationChannel(CHANNEL_FAILED, context.getString(R.string.alerts_channel_failed), from?.importance ?: NotificationManager.IMPORTANCE_HIGH)
        channel.description = context.getString(R.string.alerts_channel_failed_body)
        if (from != null) {
            channel.setSound(from.sound, from.audioAttributes)
            channel.enableVibration(from.shouldVibrate())
            from.vibrationPattern?.let { channel.vibrationPattern = it }
            channel.enableLights(from.shouldShowLights())
            channel.lightColor = from.lightColor
            channel.lockscreenVisibility = from.lockscreenVisibility
            channel.setShowBadge(from.canShowBadge())
        }
        manager.createNotificationChannel(channel)
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
    internal const val CHANNEL_FAILED = "failed"
    internal const val CHANNEL_DONE = "done"
    internal const val CHANNEL_MESSAGES = "messages"
    internal const val CHANNEL_PROGRESS = "progress"
    internal const val EXTRA_HOST = "host"
    internal const val EXTRA_HOST_ID = "host_id"
    internal const val EXTRA_SESSION = "session"
    internal const val EXTRA_BLOCK = "block"
    internal const val EXTRA_STAMP = "stamp"
    internal const val EXTRA_TITLE = "title"
    internal const val EXTRA_KIND = "kind"
    internal const val EXTRA_WINDOW = "window"
    internal const val KEY_REPLY = "reply"
    internal const val ACTION_STOP = "me.leaver.rexpocket.alerts.STOP"
    internal const val ACTION_RESTORE = "me.leaver.rexpocket.alerts.RESTORE"
    internal const val ACTION_RESUME = "me.leaver.rexpocket.alerts.RESUME"
    internal const val ACTION_DISCONNECT = "me.leaver.rexpocket.alerts.DISCONNECT"
    internal const val ACTION_TAILNET = "me.leaver.rexpocket.alerts.TAILNET"
    internal const val ACTION_REPLY = "me.leaver.rexpocket.alerts.REPLY"
    internal const val ACTION_INTERRUPT = "me.leaver.rexpocket.alerts.INTERRUPT"
    internal const val ACTION_APPROVE = "me.leaver.rexpocket.alerts.APPROVE"
    internal const val ACTION_DENY = "me.leaver.rexpocket.alerts.DENY"
    internal const val ACTION_MUTE = "me.leaver.rexpocket.alerts.MUTE"

    /** Opens Settings › Background and notifications: [notificationsPage]. */
    const val ACTION_OPEN_NOTIFICATIONS = "me.leaver.rexpocket.OPEN_NOTIFICATIONS"
    internal const val PANE_ID = 2
    internal const val TAILNET_ID = 5
    internal const val PROGRESS_ID = 4

    /** Background mode stopped, by the person (Stop) or by Android: a note with the way back. */
    internal const val STOPPED_ID = 6

    /** How long a note saying how an action went stays. */
    internal const val CONFIRM_MS = 4_000L
    private const val SUMMARY_ID = 3
    private const val SUMMARY_LINES = 6
    private const val MAX_ACTIONS = 3
    private const val BUZZ_MS = 60L
    private const val SCHEME = "rexpocket-alert"
    private const val LINK_PANE = "open"
    private const val LINK_HOST = "host"
    private const val Q_HOST = "h"
    private const val Q_SESSION = "s"
    private const val Q_BLOCK = "b"
    private const val Q_SIG = "sig"
    private const val KEY_FILE = "alert-link.key"
    private const val KEY_ANDROID_STOP = "watch.android_stop"
}

/**
 * A notification's Reply, Interrupt, Approve, Deny or Mute. It hands the input to the connection
 * the app has open and waits for Rex to take it: then the notification says so for a moment, as
 * the person has answered; otherwise the alert comes back saying it was not sent.
 */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val target = AlertTarget(
            intent.getStringExtra(Alerts.EXTRA_HOST_ID) ?: return,
            intent.getStringExtra(Alerts.EXTRA_SESSION) ?: return,
            intent.getStringExtra(Alerts.EXTRA_BLOCK) ?: return,
        )
        val app = context.applicationContext
        val fallback = Alerts.alertOf(intent, target)
        if (intent.action == Alerts.ACTION_MUTE) {
            val window = intent.getStringExtra(Alerts.EXTRA_WINDOW) ?: return
            Alerts.muteTab(app, target.hostId, window)
            Alerts.confirm(app, target, app.getString(R.string.alerts_muted), fallback)
            return
        }
        val action = when (intent.action) {
            Alerts.ACTION_REPLY -> AlertAction.Reply(RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Alerts.KEY_REPLY)?.toString() ?: return)
            Alerts.ACTION_INTERRUPT -> AlertAction.Interrupt
            Alerts.ACTION_APPROVE, Alerts.ACTION_DENY ->
                AlertAction.Answer(intent.action == Alerts.ACTION_APPROVE, intent.getStringExtra(Alerts.EXTRA_STAMP) ?: return)
            else -> return
        }
        // Null only when something calls onReceive itself, as a test does.
        val pending: PendingResult? = goAsync()
        scope.launch {
            try {
                val handler = Alerts.actions
                val outcome = handler?.let { withTimeoutOrNull(SEND_MS) { runCatching { it(target, action) }.getOrDefault(AlertOutcome.NotSent) } }
                when (outcome) {
                    AlertOutcome.Sent -> Alerts.confirm(app, target, done(app, action), fallback)
                    AlertOutcome.Changed -> Alerts.changed(app, target, fallback)
                    AlertOutcome.NotSent, null -> Alerts.failed(app, target, app.getString(R.string.alerts_not_sent), fallback)
                }
            } finally {
                pending?.finish()
            }
        }
    }

    /** What an action that went through says. */
    private fun done(context: Context, action: AlertAction): String = when (action) {
        is AlertAction.Reply -> context.getString(R.string.alerts_sent, me.leaver.rexpocket.core.plainText(action.text, max = 80) ?: action.text)
        AlertAction.Interrupt -> context.getString(R.string.alerts_interrupted)
        is AlertAction.Answer -> context.getString(if (action.approve) R.string.alerts_approved else R.string.alerts_denied)
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** A broadcast may run for 10 s; the input has that, less a margin to put the alert back. */
        const val SEND_MS = 8_000L
    }
}
