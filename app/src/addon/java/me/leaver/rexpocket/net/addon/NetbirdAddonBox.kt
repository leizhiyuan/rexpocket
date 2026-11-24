package me.leaver.rexpocket.net.addon

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.os.Bundle
import me.leaver.rexpocket.alerts.Alerts
import me.leaver.rexpocket.core.CheckRoute
import me.leaver.rexpocket.core.DialedSocketFactory
import me.leaver.rexpocket.core.RexClient
import me.leaver.rexpocket.core.StreamDialer
import me.leaver.rexpocket.core.TailnetBackend
import me.leaver.rexpocket.net.NetbirdBox
import me.leaver.rexpocket.net.NetbirdConfig
import me.leaver.rexpocket.net.NodeEvents
import me.leaver.rexpocket.net.RunningNode
import me.leaver.rexpocket.net.TailnetBox
import me.leaver.rexpocket.net.TailnetConfig
import me.leaver.rexpocket.net.TailnetEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.leaver.rexpocket.diagnostics.Diagnostics
import me.leaver.rexpocket.net.LOG_TAG
import me.leaver.rexpocket.netbird.api.NetbirdAddon
import java.io.File

/** The add-on's name for account [profile]: the first account (blank, or the backend's id) is its first. */
internal fun addonProfile(profile: String): String =
    if (profile.isBlank() || profile == TailnetBackend.NetbirdAddon.storageKey) NetbirdAddon.FIRST_PROFILE else profile

/** Where the box follows background mode: for as long as the process lives, as the box does. */
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
    private val client: AddonClient = AddonClient(
        ServiceAddonLink(context.applicationContext),
        AddonTexts.of(context),
        forgets = ForgetQueue.Stored(context.applicationContext),
    ),
) : TailnetBox(context) {
    override val name: String = "Netbird add-on"

    // The add-on keeps the registrations, one per account; nothing of the node's is kept in this app.
    // The directory's name is the first account's id, so directoryFor knows the first account.
    override val stateDirectory: File get() = File(context.cacheDir, TailnetBackend.NetbirdAddon.storageKey)

    private val _keepsAccounts = MutableStateFlow(client.keepsAccounts())

    /** Accounts need an add-on of the revision that has them; an older one runs the first account only. */
    override val keepsAccounts: StateFlow<Boolean> = _keepsAccounts

    override val dialer: StreamDialer = StreamDialer { host, port, timeout -> FdStream(client.dial(host, port, timeout)) }

    // No proxy: the Test connection dials through the add-on, as a connection does.
    override fun checkRoute(port: Int): CheckRoute = CheckRoute(
        sockets = DialedSocketFactory(dialer),
        ssh = { it.copy(dialer = dialer) },
        http = { RexClient.defaultHttp(0, dialer) },
    )

    private val foregroundWatch = ForegroundWatch()

    init {
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(foregroundWatch)
        // Started by Android without a screen (a boot, the watch restarted) with background mode on:
        // no activity stops to say so, so the wish follows background mode from the start.
        scope.launch { Alerts.enabled.collect { foregroundWatch.backgroundModeChanged(it) } }
        // The add-on installed, updated or removed: what it can do may have changed.
        val packages = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.data?.schemeSpecificPart == NetbirdAddon.PACKAGE) refresh()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        runCatching { ContextCompat.registerReceiver(context.applicationContext, packages, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
    }

    private fun refresh() {
        _keepsAccounts.value = client.keepsAccounts()
    }

    override fun open(config: TailnetConfig, hostname: String, events: NodeEvents): RunningNode {
        val netbird = config as? NetbirdConfig ?: throw IllegalArgumentException("Netbird needs a Netbird config")
        client.onStateChanged = {
            refresh()
            events.stateChanged()
        }
        try {
            client.start(addonProfile(netbird.profile), hostname, netbird.setupKey, netbird.managementUrl)
        } finally {
            refresh()
        }
        return AddonRunning(client)
    }

    /** The add-on removes the account's registration, binding it for that if need be; off the caller's thread. */
    override fun forgetProfile(profile: String) {
        val addon = addonProfile(profile)
        if (addon == NetbirdAddon.FIRST_PROFILE) return
        Thread({ client.forget(addon) }, "netbird-addon-forget").start()
        Diagnostics.event(LOG_TAG, "$name: an account's login is being removed")
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
        @Volatile private var started = 0

        /** Background mode turned on or off: it matters while no activity is started. */
        fun backgroundModeChanged(on: Boolean) {
            if (started == 0) client.keepInBackground(on)
        }

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
