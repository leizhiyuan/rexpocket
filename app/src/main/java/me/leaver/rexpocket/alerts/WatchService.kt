package me.leaver.rexpocket.alerts

import android.app.Application
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.leaver.rexpocket.MainActivity
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.Connectable
import me.leaver.rexpocket.core.ConnectableKind
import me.leaver.rexpocket.core.LinkState
import me.leaver.rexpocket.core.hostLinkState
import me.leaver.rexpocket.diagnostics.Diagnostics
import me.leaver.rexpocket.ui.Connections

/**
 * Background mode's anchor: a foreground service that holds the process, and with it the
 * process's [Connections], for as long as background mode is on - past the screen, past the task
 * being swiped away, and (START_STICKY) past Android killing the process, after which Android
 * starts it again and it connects the hosts kept in the background from what was stored. The
 * connections themselves are [Connections]'; this keeps them alive and shows them in its quiet
 * notification, with Stop and, for one or two hosts, a Disconnect for each.
 * See docs/design/multi-host.md, "Background".
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var follow: Job? = null
    private lateinit var connections: Connections
    private var lines: List<ItemLine> = emptyList()

    /** One host or tailnet as the watch notification shows it: its name and its state. */
    private data class ItemLine(val item: Connectable, val label: String, val state: LinkState) {
        val host: Boolean get() = item.kind == ConnectableKind.Host
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        // Started by Android with no activity, the journal is not open yet; a restart belongs in it.
        Diagnostics.install(this)
        Alerts.channels(this)
        connections = Connections.of(application as Application)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Diagnostics.event(TAG, "watch ${action?.substringAfterLast('.') ?: if (intent == null) "restarted by Android" else "start"}")
        when (action) {
            Alerts.ACTION_STOP -> {
                Alerts.enabled.value = false
                connections.backgroundStopped()
                stopSelf()
                return START_NOT_STICKY
            }
            Alerts.ACTION_DISCONNECT -> intent.getStringExtra(Alerts.EXTRA_HOST_ID)?.let { connections.disconnect(it) }
            Alerts.ACTION_TAILNET -> Connectable.parse(intent.getStringExtra(TileStore.EXTRA_ITEM))?.let { item ->
                if (intent.getBooleanExtra(EXTRA_CONNECT, false)) connections.connectItem(item) else connections.disconnectItem(item)
            }
        }
        // Every start asked for with startForegroundService must be answered with startForeground.
        if (!foreground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Android started the process again for this (intent null), or a boot or update did.
        if (intent == null || action == Alerts.ACTION_RESTORE) {
            if (!Alerts.enabled.value || connections.restoreBackground() == 0) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (follow == null) follow = scope.launch {
            connections.changes
                .map {
                    // The joined tailnet first, as on the home, then each connected host.
                    val tailnets = connections.connectables().filter { it.kind == ConnectableKind.Tailnet }
                        .mapNotNull { t -> connections.nameOf(t)?.let { ItemLine(t, it, connections.stateOf(t)) } }
                    tailnets + connections.lives.value.values.map {
                        ItemLine(Connectable.host(it.target.id), it.target.label, hostLinkState(it.state.phase, it.state.problem))
                    }
                }
                .distinctUntilChanged()
                .collect { next ->
                    lines = next
                    if (next.any { it.host || it.state != LinkState.Off }) repost()
                }
        }
        return START_STICKY
    }

    /** Swiped away from recents: with background mode on the watch stays, and so do the connections. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Diagnostics.event(TAG, "task removed, background ${if (Alerts.enabled.value) "stays" else "stops"}")
        if (!Alerts.enabled.value) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    private fun foreground(): Boolean = try {
        ServiceCompat.startForeground(this, WATCH_ID, notification(), foregroundType())
        true
    } catch (e: RuntimeException) {
        // Android 12 and later: a start it does not allow from the background
        // (ForegroundServiceStartNotAllowedException), which a restart can be when the app's battery
        // use is restricted. Say so, rather than going quietly.
        Diagnostics.event(TAG, "watch could not go to the foreground: ${e.javaClass.simpleName}")
        stopped()
        false
    }

    /** The notification again, now the hosts or their states changed. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun repost() {
        if (Alerts.allowed(this)) NotificationManagerCompat.from(this).notify(WATCH_ID, notification())
    }

    /** Tells the person background mode was stopped by Android, and where to fix it. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun stopped() {
        if (!Alerts.allowed(this)) return
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, Alerts.CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(getString(R.string.bg_stopped_title))
            .setContentText(getString(R.string.bg_stopped_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.bg_stopped_body)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(STOPPED_ID, n)
    }

    private fun notification(): android.app.Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, WatchService::class.java).setAction(Alerts.ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val hosts = lines.filter { it.host }
        val up = lines.filter { !it.host && it.state != LinkState.Off }
        val names = hosts.joinToString(", ") { it.label }
        val builder = NotificationCompat.Builder(this, Alerts.CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(
                when {
                    hosts.isNotEmpty() -> resources.getQuantityString(R.plurals.bg_watch_title, hosts.size, hosts.size)
                    up.isNotEmpty() -> getString(R.string.bg_watch_tailnet_title, up.first().label)
                    else -> getString(R.string.alerts_title)
                },
            )
            .setContentText(
                when {
                    names.isNotEmpty() -> getString(R.string.alerts_watching, names)
                    up.isNotEmpty() -> getString(R.string.bg_watch_tailnet_body)
                    else -> getString(R.string.bg_watch_starting)
                },
            )
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.alerts_stop), stop)
        // One line per host and tailnet, in the state words the tiles and pages use, once there
        // is more than one: the same treatment for both.
        if (lines.size > 1) {
            val inbox = NotificationCompat.InboxStyle()
            for (line in lines) inbox.addLine(getString(R.string.bg_watch_line, line.label, getString(stateWord(line.state)).lowercase()))
            builder.setStyle(inbox)
        }
        // Three actions at most. Stop always; then one each for the tailnets and hosts, tailnets
        // first, while all of them fit in the two left; with more, none, so no host or tailnet
        // gets an action another lacks (the tiles and the app have them all).
        if (lines.size <= 2) {
            for (line in lines) builder.addAction(0, actionLabel(line), actionIntent(line))
        }
        return builder.build()
    }

    /** "Connect Tailscale" for a tailnet that is off, "Disconnect mac" otherwise. */
    private fun actionLabel(line: ItemLine): String = when {
        line.host -> getString(R.string.bg_disconnect_host, line.label)
        line.state == LinkState.Off -> getString(R.string.tailnet_connect_item, line.label)
        else -> getString(R.string.tailnet_disconnect_item, line.label)
    }

    /**
     * What an item's action does. A host disconnects; a tailnet connects or disconnects, and one
     * that hosts are connected through asks first, in the app, naming them, as everywhere else.
     */
    private fun actionIntent(line: ItemLine): PendingIntent {
        val item = line.item
        if (line.host) {
            val intent = Intent(this, WatchService::class.java).setAction(Alerts.ACTION_DISCONNECT).putExtra(Alerts.EXTRA_HOST_ID, item.id)
            return PendingIntent.getService(this, item.id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val connect = line.state == LinkState.Off
        if (!connect && connections.hostsOn(item.id).isNotEmpty()) {
            val ask = Intent(this, MainActivity::class.java).setAction(TileStore.ACTION_CONFIRM_OFF).putExtra(TileStore.EXTRA_ITEM, item.key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(this, item.key.hashCode(), ask, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val intent = Intent(this, WatchService::class.java).setAction(Alerts.ACTION_TAILNET)
            .putExtra(TileStore.EXTRA_ITEM, item.key).putExtra(EXTRA_CONNECT, connect)
        return PendingIntent.getService(this, item.key.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    // Keeping a remote terminal connection open fits none of the platform's named types; the app is
    // installed from its releases, not a store, so the special-use type says what it is instead.
    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

    companion object {
        /** Whether the watch is up in this process: the connections start it only when it is not. */
        @Volatile var running = false
            private set

        internal const val WATCH_ID = 1
        private const val EXTRA_CONNECT = "connect"
        private const val STOPPED_ID = 4
        private const val TAG = "RexPocketWatch"
    }
}
