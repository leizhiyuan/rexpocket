package me.leaver.rexpocket.alerts

import android.Manifest
import android.app.NotificationChannel
import android.content.pm.PackageManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import me.leaver.rexpocket.MainActivity
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.AlertTokens

// Background alerts: a quiet notification that keeps the app connected while it is away, and a
// loud one when a program in a pane needs the person.

/** The pane an alert opens. */
data class AlertTarget(val hostId: String, val sessionId: String, val blockId: String)

/** One alert, already in the person's words. */
data class Alert(val target: AlertTarget, val title: String, val text: String, val host: String)

object Alerts {
    /**
     * Whether alerts are on, for the whole process: the watch notification's Stop turns them off
     * from outside any screen, and the screens follow.
     */
    val enabled = MutableStateFlow(false)

    private val tokens = AlertTokens<AlertTarget>()

    /** Notifications are on for the app, and on Android 13 and later the person has allowed them. */
    fun allowed(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Starts watching [host]; called while the app is on screen, which is when Android allows it. */
    fun watch(context: Context, host: String) {
        ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java).putExtra(EXTRA_HOST, host))
    }

    fun unwatch(context: Context) {
        context.stopService(Intent(context, WatchService::class.java))
    }

    fun raise(context: Context, alert: Alert) {
        // Notifications can be turned off in system settings while alerts are on; then there is no one to tell.
        if (!allowed(context)) return
        channels(context)
        val token = tokens.issue(alert.target)
        val open = Intent(context, MainActivity::class.java)
            .setData(Uri.Builder().scheme(SCHEME).authority("open").appendPath(token).build())
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(context, token.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setSubText(alert.host)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            NotificationManagerCompat.from(context).notify(alert.target.blockId, ATTENTION_ID, notification)
        }
    }

    /** The pane no longer waits, or the person went to it: its alert has said what it had to. */
    fun settle(context: Context, blockId: String) {
        NotificationManagerCompat.from(context).cancel(blockId, ATTENTION_ID)
    }

    /** The pane an alert's intent names, once; null for anything this process did not issue. */
    fun consume(intent: Intent?): AlertTarget? {
        val uri = intent?.data ?: return null
        if (uri.scheme != SCHEME || uri.authority != "open" || uri.pathSegments.size != 1) return null
        return tokens.consume(uri.lastPathSegment)
    }

    internal fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_WATCH, context.getString(R.string.alerts_channel_watch), NotificationManager.IMPORTANCE_MIN)
                .apply { description = context.getString(R.string.alerts_channel_watch_body) },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ATTENTION, context.getString(R.string.alerts_channel_attention), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.alerts_channel_attention_body) },
        )
    }

    internal const val CHANNEL_WATCH = "watch"
    internal const val CHANNEL_ATTENTION = "attention"
    internal const val EXTRA_HOST = "host"
    internal const val ACTION_STOP = "me.leaver.rexpocket.alerts.STOP"
    private const val ATTENTION_ID = 2
    private const val SCHEME = "rexpocket-alert"
}

/**
 * Holds the app in the foreground while it is away, so its connection - and the tailnet node it
 * runs over - stay up and Rex's status events keep arriving. It watches nothing itself: the
 * connection the screens already have does, and this only keeps it alive. It ends with the task.
 */
class WatchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Alerts.ACTION_STOP) {
            Alerts.enabled.value = false
            stopSelf()
            return START_NOT_STICKY
        }
        Alerts.channels(this)
        val host = intent?.getStringExtra(Alerts.EXTRA_HOST).orEmpty()
        val stop = PendingIntent.getService(
            this, 0, Intent(this, WatchService::class.java).setAction(Alerts.ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, Alerts.CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(getString(R.string.alerts_title))
            .setContentText(getString(R.string.alerts_watching, host))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .addAction(0, getString(R.string.alerts_stop), stop)
            .build()
        ServiceCompat.startForeground(this, WATCH_ID, notification, foregroundType())
        // Killed for memory, the connection is gone with the process; the app restarts it, not this.
        return START_NOT_STICKY
    }

    // Keeping a remote terminal connection open fits none of the platform's named types; the app is
    // installed from its releases, not a store, so the special-use type says what it is instead.
    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

    private companion object {
        const val WATCH_ID = 1
    }
}
