package me.leaver.rexpocket.net.addon

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import me.leaver.rexpocket.alerts.Alerts
import me.leaver.rexpocket.core.StreamDialer
import me.leaver.rexpocket.core.TailnetBackend
import me.leaver.rexpocket.net.NetbirdBox
import me.leaver.rexpocket.net.NetbirdConfig
import me.leaver.rexpocket.net.NodeEvents
import me.leaver.rexpocket.net.RunningNode
import me.leaver.rexpocket.net.TailnetBox
import me.leaver.rexpocket.net.TailnetConfig
import me.leaver.rexpocket.net.TailnetEntry
import java.io.File

/**
 * The backends the default build adds to TailnetNodes: Netbird run by the add-on. The embedded
 * Netbird node ([netbird]) is not this build's, so it is not used here.
 */
internal fun addonEntries(context: Context, @Suppress("UNUSED_PARAMETER") netbird: NetbirdBox): List<TailnetEntry> =
    listOf(TailnetEntry(TailnetBackend.NetbirdAddon, NetbirdAddonBox(context)))

/**
 * A Netbird node run by the Netbird add-on, a separate app (`:netbirdaddon`), through its bound
 * service. It has no proxy: a connection is a [dialer] call that the add-on answers with a socket.
 * The lifecycle, peers and probing are [TailnetBox]'s, as for the built-in nodes.
 *
 * While RexPocket is on screen, its binding keeps the add-on alive. When RexPocket goes to the
 * background with its background alerts on, which keep the connection open, the add-on is asked
 * to keep running too, and becomes a foreground service for that time.
 */
internal class NetbirdAddonBox(
    context: Context,
    private val client: AddonClient = AddonClient(ServiceAddonLink(context.applicationContext), AddonTexts.of(context)),
) : TailnetBox(context) {
    override val name: String = "Netbird add-on"

    // The add-on keeps the registration; nothing of the node's is kept in this app.
    override val stateDirectory: File get() = File(context.cacheDir, "netbird-addon")

    override val dialer: StreamDialer = StreamDialer { host, port, timeout -> FdStream(client.dial(host, port, timeout)) }

    init {
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(ForegroundWatch())
    }

    override fun open(config: TailnetConfig, hostname: String, events: NodeEvents): RunningNode {
        val netbird = config as? NetbirdConfig ?: throw IllegalArgumentException("Netbird needs a Netbird config")
        client.onStateChanged = { events.stateChanged() }
        client.start(hostname, netbird.setupKey, netbird.managementUrl)
        return AddonRunning(client)
    }

    private class AddonRunning(private val client: AddonClient) : RunningNode {
        override val proxyPort: Int get() = 0
        override fun state(): String = client.state()
        override fun ping(ip: String, timeoutMillis: Int): String = client.ping(ip, timeoutMillis)
        override fun dialError(host: String): String = client.dialError(host)

        // Netbird's engine follows the network on its own, as in the embedded node.
        override fun networkChanged() = Unit

        override fun close() = client.stop()
    }

    /** Whether any of RexPocket's activities is started, to tell the add-on when it is on its own. */
    private inner class ForegroundWatch : Application.ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: Activity) {
            if (started++ == 0) client.keepInBackground(false)
        }

        override fun onActivityStopped(activity: Activity) {
            if (--started == 0) client.keepInBackground(Alerts.enabled.value)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
