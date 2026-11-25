package me.leaver.rexpocket.alerts

import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.leaver.rexpocket.R
import me.leaver.rexpocket.diagnostics.Diagnostics
import me.leaver.rexpocket.ui.Connections

/**
 * The Quick Settings tile for background mode itself, apart from the three host and tailnet tiles:
 * a tap turns it on or off, as Settings › Background and notifications' switch does, and the tile
 * shows which it is. Turning it on where notifications are not allowed opens that page instead,
 * since background mode cannot alert without them and the page asks for them. A long press opens
 * the page too. See docs/design/notifications.md, "Background tile".
 */
class BackgroundTile : TileService() {
    private var scope: CoroutineScope? = null

    private val connections: Connections get() = Connections.of(application as Application)

    override fun onStartListening() {
        super.onStartListening()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        val c = connections
        s.launch { combine(Alerts.enabled, c.lives) { _, _ -> }.collect { show() } }
        show()
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val c = connections
        if (Alerts.enabled.value) {
            Diagnostics.event(TAG, "background tile: off")
            // As the watch's Stop, without its note: the tile shows the way back.
            Alerts.enabled.value = false
            c.backgroundStopped()
        } else if (!Alerts.allowed(this)) {
            open(Alerts.notificationsPage(this))
        } else {
            Diagnostics.event(TAG, "background tile: on")
            Alerts.enabled.value = true
            Alerts.backOn(this)
            // The hosts kept in the background connect, as after a restart; the watch holds them.
            if (c.links.value.isNotEmpty()) Alerts.watch(this) else Alerts.restore(this)
        }
        show()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun show() {
        val tile = qsTile ?: return
        val on = Alerts.enabled.value
        tile.label = getString(R.string.tile_background_label)
        tile.icon = Icon.createWithResource(this, if (on) R.drawable.ic_notifications else R.drawable.ic_notifications_off)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val subtitle = subtitle(this, on, connections.lives.value.size)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle
        tile.contentDescription = "${tile.label}, $subtitle"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.stateDescription = subtitle
        tile.updateTile()
    }

    companion object {
        private const val TAG = "RexPocketTile"

        /**
         * "On · 2 hosts", "Off", or "Allow notifications" where Android does not let it alert: off,
         * or on with notifications taken away since, when it connects but no alert can show.
         */
        internal fun subtitle(context: Context, on: Boolean, hosts: Int): String = when {
            !Alerts.allowed(context) -> context.getString(R.string.tile_background_allow)
            !on -> context.getString(R.string.alerts_off)
            hosts == 0 -> context.getString(R.string.alerts_on)
            else -> context.getString(R.string.alerts_on) + " · " + context.resources.getQuantityString(R.plurals.tile_background_hosts, hosts, hosts)
        }

        /** Asks Android to redraw the tile: background mode changed from elsewhere. */
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, android.content.ComponentName(context, BackgroundTile::class.java)) }
        }
    }
}
