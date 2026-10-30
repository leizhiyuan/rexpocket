package me.leaver.rexpocket.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import android.system.OsConstants
import me.leaver.rexpocket.BuildConfig
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TailscaleStatusHandler
import io.nekohasekai.libbox.TailscaleStatusSubscription
import io.nekohasekai.libbox.TailscaleStatusUpdate
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/** What the user filled in for the built-in Tailscale node. */
data class TailscaleConfig(val authKey: String, val hostname: String)

/** What the node reports about itself; Tailscale's own state machine, read from libbox. */
data class TailscaleStatus(
    val backendState: String = "",
    val text: String = "",
    val authUrl: String = "",
    val networkName: String = "",
)

class TailscaleException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Tag shared by the tailnet diagnostics; `adb logcat -s RexPocketTS` shows the node's own log. */
const val LOG_TAG = "RexPocketTS"

/**
 * A sing-box box running inside the app: one Tailscale endpoint joined to the user's tailnet and
 * one `mixed` inbound on loopback. The Rex connection is then pointed at that inbound as a SOCKS5
 * proxy, so a tailnet name such as `host.tailnet-xxxx.ts.net` is resolved and dialed by Tailscale
 * instead of by the phone.
 *
 * Nothing here touches VpnService: the phone keeps its own network, and only the sockets this app
 * opens for Rex go through the tunnel. That is why the Tailscale app does not have to run, and
 * why this composes with whatever VPN the phone already uses.
 */
class TailscaleBox(private val context: Context) {
    private val _status = MutableStateFlow(TailscaleStatus())
    /** Live node state, so the home screen can say whether it still needs a login. */
    val status: StateFlow<TailscaleStatus> = _status

    @Volatile private var server: CommandServer? = null
    @Volatile private var running: TailscaleConfig? = null
    @Volatile private var port: Int = 0
    @Volatile private var commandClient: CommandClient? = null
    @Volatile private var subscription: TailscaleStatusSubscription? = null

    /** Port of the local mixed inbound while the box runs, 0 otherwise. */
    val proxyPort: Int get() = port

    /**
     * Starts the box for [config] and returns the SOCKS5 port, or reuses the running one when the
     * config has not changed. Blocking: call from an IO dispatcher.
     */
    @Synchronized
    fun start(config: TailscaleConfig): Int {
        running?.let {
            if (it == config && server != null) {
                Log.i(LOG_TAG, "reusing the node on port $port (hostname=${config.hostname.ifBlank { "default" }})")
                return port
            }
            Log.i(LOG_TAG, "restarting the node: config changed (hostname=${config.hostname.ifBlank { "default" }})")
        }
        Log.i(LOG_TAG, "starting the node (hostname=${config.hostname.ifBlank { "default" }})")
        stopLocked()
        setupLibbox()
        val listenPort = try {
            Libbox.availablePort(FIRST_PORT)
        } catch (e: Exception) {
            throw TailscaleException(e.message ?: "No free local port", e)
        }
        val created = try {
            Libbox.newCommandServer(Handler(::onStopped), Platform())
        } catch (e: Exception) {
            throw TailscaleException(e.message ?: "Could not create the Tailscale service", e)
        }
        try {
            created.start()
            created.startOrReloadService(configJson(config, listenPort), OverrideOptions())
        } catch (e: Exception) {
            runCatching { created.closeService() }
            runCatching { created.close() }
            throw TailscaleException(tailscaleMessage(e), e)
        }
        server = created
        running = config
        port = listenPort
        _status.value = TailscaleStatus()
        watchStatus()
        return listenPort
    }

    /**
     * Reads the node's state over libbox's local command socket. Without it a node that still
     * needs a login looks exactly like a broken tunnel: the Rex connection just keeps retrying.
     */
    private fun watchStatus() {
        runCatching {
            // A command client needs a handler, even when it only carries the Tailscale status
            // stream; the standalone constructor leaves that nil and would panic on connect.
            val client = Libbox.newCommandClient(CommandHandler(), CommandClientOptions())
            client.connect()
            val handle = client.subscribeTailscaleStatus(object : TailscaleStatusHandler {
                override fun onError(message: String) {
                    _status.value = _status.value.copy(text = message)
                }

                override fun onStatusUpdate(update: TailscaleStatusUpdate) {
                    val endpoints = update.endpoints()
                    while (endpoints.hasNext()) {
                        val endpoint = endpoints.next()
                        if (endpoint.endpointTag != ENDPOINT_TAG) continue
                        val next = TailscaleStatus(
                            backendState = endpoint.backendState.orEmpty(),
                            text = endpoint.stateText.orEmpty(),
                            authUrl = endpoint.authURL.orEmpty(),
                            networkName = endpoint.networkName.orEmpty(),
                        )
                        if (next != _status.value) {
                            Log.i(LOG_TAG, "node state ${next.backendState} ${next.text}".trim())
                        }
                        _status.value = next
                        return
                    }
                }
            })
            commandClient = client
            subscription = handle
        }.onFailure {
            _status.value = _status.value.copy(text = it.message.orEmpty())
        }
    }

    @Synchronized
    fun stop() {
        if (server != null) Log.i(LOG_TAG, "stopping the node on port $port")
        runCatching { stopLocked() }.onFailure { Log.w(LOG_TAG, "stop failed: ${it.message}") }
    }

    /**
     * Tears the box down off the caller's thread; closing it talks to the node. A failure here
     * must not reach the default handler: that would kill the app over a stop it wanted anyway.
     */
    fun stopAsync() {
        Thread({
            runCatching { stop() }.onFailure { Log.w(LOG_TAG, "stop failed: ${it.message}") }
        }, "tailscale-stop").start()
    }

    private fun stopLocked() {
        val handle = subscription
        subscription = null
        runCatching { handle?.close() }
        val client = commandClient
        commandClient = null
        runCatching { client?.disconnect() }
        _status.value = TailscaleStatus()
        val current = server ?: return
        server = null
        running = null
        port = 0
        runCatching { current.closeService() }
        runCatching { current.close() }
    }

    /** The box stops itself when an internal error ends the service. */
    private fun onStopped() = stopAsync()

    private fun setupLibbox() {
        if (setupDone) return
        synchronized(Libbox::class.java) {
            if (setupDone) return
            val base = context.filesDir.apply { mkdirs() }
            val working = File(base, "libbox").apply { mkdirs() }
            val temp = context.cacheDir.apply { mkdirs() }
            val version = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }.getOrNull()
            Libbox.setup(
                SetupOptions().also {
                    it.basePath = base.absolutePath
                    it.workingPath = working.absolutePath
                    it.tempPath = temp.absolutePath
                    // Go's stack traceback on Android needs the workaround sing-box ships for it.
                    it.fixAndroidStack = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    it.logMaxLines = 300
                    it.debug = false
                    it.crashReportSource = "rexpocket"
                    it.appVersion = version?.longVersionCode?.toString() ?: ""
                    it.appMarketingVersion = version?.versionName ?: ""
                    it.oomKillerEnabled = false
                    it.oomKillerDisabled = false
                    it.oomMemoryLimit = 0
                    it.powerReportEnabled = false
                    it.platformMetadata = JSONObject()
                        .put("os", "Android " + Build.VERSION.RELEASE)
                        .put("sdk", Build.VERSION.SDK_INT)
                        .put("manufacturer", Build.MANUFACTURER)
                        .put("model", Build.MODEL)
                        .toString()
                },
            )
            setupDone = true
        }
    }

    private fun stateDirectory(): File = File(context.filesDir, "tailscale")

    /**
     * Tailnet names are MagicDNS names, so the tailscale DNS server answers for them and the
     * phone's own resolver answers everything else - starting with the control plane hostname the
     * endpoint itself has to reach. `domain_resolver` on the endpoint names that resolver
     * explicitly, or the endpoint could not dial its own control server through this route.
     */
    private fun configJson(config: TailscaleConfig, listenPort: Int): String {
        val endpoint = JSONObject()
            .put("type", "tailscale")
            .put("tag", ENDPOINT_TAG)
            .put("state_directory", stateDirectory().apply { mkdirs() }.absolutePath)
            .put("hostname", sanitizeHostname(config.hostname).ifBlank { defaultHostname() })
            .put("domain_resolver", LOCAL_DNS_TAG)
        if (config.authKey.isNotBlank()) endpoint.put("auth_key", config.authKey)
        val dns = JSONObject()
            .put(
                "servers",
                JSONArray()
                    .put(JSONObject().put("type", "local").put("tag", LOCAL_DNS_TAG))
                    .put(
                        JSONObject()
                            .put("type", "tailscale")
                            .put("tag", TAILNET_DNS_TAG)
                            .put("endpoint", ENDPOINT_TAG)
                            .put("accept_search_domain", true),
                    ),
            )
            // Only tailnet names go to MagicDNS; everything else, such as the control plane,
            // uses the phone's resolver.
            .put(
                "rules",
                JSONArray().put(
                    JSONObject()
                        .put("preferred_by", TAILNET_DNS_TAG)
                        .put("action", "route")
                        .put("server", TAILNET_DNS_TAG),
                ),
            )
            .put("final", LOCAL_DNS_TAG)
            .put("strategy", "prefer_ipv4")
        return JSONObject()
            .put("log", JSONObject().put("level", "info").put("timestamp", true))
            .put("dns", dns)
            .put("endpoints", JSONArray().put(endpoint))
            .put(
                "inbounds",
                JSONArray().put(
                    JSONObject()
                        .put("type", "mixed")
                        .put("tag", INBOUND_TAG)
                        .put("listen", "127.0.0.1")
                        .put("listen_port", listenPort),
                ),
            )
            .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put(
                "route",
                JSONObject()
                    .put(
                        "rules",
                        JSONArray().put(
                            JSONObject()
                                .put("inbound", JSONArray().put(INBOUND_TAG))
                                .put("action", "route")
                                .put("outbound", ENDPOINT_TAG),
                        ),
                    )
                    .put("final", ENDPOINT_TAG)
                    .put("default_domain_resolver", LOCAL_DNS_TAG),
            )
            .toString()
    }

    private fun tailscaleMessage(e: Exception): String {
        val text = e.message.orEmpty()
        return if (text.contains("auth", ignoreCase = true) || text.contains("login", true)) {
            "$text (check the Tailscale auth key)"
        } else {
            text.ifBlank { "Tailscale could not start" }
        }
    }

    /** Answers the phone's own DNS. MagicDNS is not consulted here: that is the tailscale server. */
    private object LocalDns : LocalDNSTransport {
        override fun raw(): Boolean = false

        override fun exchange(ctx: ExchangeContext, message: ByteArray) {
            throw UnsupportedOperationException("only the lookup path is used")
        }

        override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
            val addresses = try {
                InetAddress.getAllByName(domain.trimEnd('.'))
            } catch (_: UnknownHostException) {
                ctx.errorCode(RCODE_NXDOMAIN)
                return
            }
            val want4 = network.endsWith("4")
            val want6 = network.endsWith("6")
            val picked = addresses.filter { address ->
                when {
                    want4 -> address is Inet4Address
                    want6 -> address is Inet6Address
                    else -> true
                }
            }
            val hosts = picked.mapNotNull { it.hostAddress }
            if (hosts.isEmpty()) {
                ctx.errorCode(RCODE_NXDOMAIN)
            } else {
                ctx.success(hosts.joinToString("\n"))
            }
        }
    }

    private inner class Platform : PlatformInterface {
        private val connectivity = context.getSystemService(ConnectivityManager::class.java)
        @Volatile private var listener: InterfaceUpdateListener? = null
        @Volatile private var callback: ConnectivityManager.NetworkCallback? = null

        override fun localDNSTransport(): LocalDNSTransport = LocalDns

        /** The box is not a VPN, so it must not bind sockets to an interface of ours. */
        override fun usePlatformAutoDetectInterfaceControl(): Boolean = false

        override fun autoDetectInterfaceControl(fd: Int) = Unit

        override fun openTun(options: TunOptions): Int =
            throw UnsupportedOperationException("this build has no tun inbound")

        override fun findConnectionOwner(
            ipProtocol: Int,
            sourceAddress: String,
            sourcePort: Int,
            destinationAddress: String,
            destinationPort: Int,
        ): ConnectionOwner = throw UnsupportedOperationException("no tun inbound to attribute")

        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
            this.listener = listener
            val manager = connectivity ?: return
            if (callback != null) {
                publishDefault(manager.activeNetwork)
                return
            }
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    publishDefault(network)
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    publishDefault(network)
                }

                override fun onLost(network: Network) {
                    this@Platform.listener?.updateDefaultInterface("", -1, false, false)
                }
            }
            runCatching {
                manager.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    cb,
                )
            }.onSuccess {
                callback = cb
                publishDefault(manager.activeNetwork)
            }
        }

        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
            this.listener = null
            val cb = callback ?: return
            callback = null
            runCatching { connectivity?.unregisterNetworkCallback(cb) }
        }

        private fun publishDefault(network: Network?) {
            val manager = connectivity ?: return
            val listener = listener ?: return
            if (network == null) return
            val link = runCatching { manager.getLinkProperties(network) }.getOrNull() ?: return
            val name = link.interfaceName ?: return
            val index = runCatching { java.net.NetworkInterface.getByName(name)?.index }.getOrNull() ?: -1
            val expensive = runCatching {
                manager.getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
            }.getOrDefault(false)
            listener.updateDefaultInterface(name, index, expensive, false)
        }

        @Suppress("DEPRECATION") // allNetworks is the only listing that also covers VPNs here.
        override fun getInterfaces(): NetworkInterfaceIterator {
            val manager = connectivity ?: return InterfaceArray(emptyList())
            val list = ArrayList<io.nekohasekai.libbox.NetworkInterface>()
            for (network in manager.allNetworks) {
                val link = runCatching { manager.getLinkProperties(network) }.getOrNull() ?: continue
                val caps = runCatching { manager.getNetworkCapabilities(network) }.getOrNull() ?: continue
                val name = link.interfaceName ?: continue
                val java = runCatching { java.net.NetworkInterface.getByName(name) }.getOrNull() ?: continue
                val boxed = io.nekohasekai.libbox.NetworkInterface()
                boxed.name = name
                boxed.index = java.index
                boxed.mtu = runCatching { java.mtu }.getOrDefault(DEFAULT_MTU)
                boxed.addresses = StringArray(
                    link.linkAddresses.map { addr -> "${addr.address.hostAddress}/${addr.prefixLength}" },
                )
                boxed.dnsServer = StringArray(link.dnsServers.mapNotNull { it.hostAddress })
                boxed.gateway = StringArray(
                    link.routes
                        .filter { it.destination?.prefixLength == 0 }
                        .mapNotNull { it.gateway?.hostAddress },
                )
                boxed.type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                boxed.metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                var flags = 0
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                }
                if (runCatching { java.isLoopback }.getOrDefault(false)) {
                    flags = flags or OsConstants.IFF_LOOPBACK
                }
                if (runCatching { java.isPointToPoint }.getOrDefault(false)) {
                    flags = flags or OsConstants.IFF_POINTOPOINT
                }
                if (runCatching { java.supportsMulticast() }.getOrDefault(false)) {
                    flags = flags or OsConstants.IFF_MULTICAST
                }
                boxed.flags = flags
                list.add(boxed)
            }
            return InterfaceArray(list)
        }

        override fun underNetworkExtension(): Boolean = false

        override fun includeAllNetworks(): Boolean = false

        override fun readWIFIState(): WIFIState? = null

        override fun clearDNSCache() = Unit

        override fun sendNotification(notification: Notification) = Unit

        override fun cancelNotification(identifier: String, typeID: Int) = Unit

        override fun startNeighborMonitor(listener: NeighborUpdateListener) = Unit

        override fun closeNeighborMonitor(listener: NeighborUpdateListener) = Unit

        override fun registerMyInterface(name: String) = Unit

        override fun usePlatformShell(): Boolean = false

        override fun checkPlatformShell() =
            throw UnsupportedOperationException("no platform shell")

        override fun openShellSession(
            user: PlatformUser?,
            command: String?,
            environ: StringIterator?,
            term: String?,
            rows: Int,
            cols: Int,
        ): ShellSession = throw UnsupportedOperationException("no platform shell")

        override fun lookupUser(username: String): PlatformUser =
            throw UnsupportedOperationException("no platform users")

        override fun lookupSFTPServer(): String = throw UnsupportedOperationException("no sftp")

        override fun readSystemSSHHostKey(): String = throw UnsupportedOperationException("no ssh server")

        /** Tailscale falls back to this as the node name when the config has no hostname. */
        override fun tailscaleHostname(): String = defaultHostname()

        override fun usePlatformBridge(): Boolean = false

        override fun createBridge(options: BridgeOptions): BridgeSession =
            throw UnsupportedOperationException("no bridge")
    }

    /** No widget in the app reads logs, groups or connections; only the Tailscale status is used. */
    private class CommandHandler : CommandClientHandler {
        override fun clearLogs() = Unit

        override fun connected() = Unit

        override fun disconnected(message: String) = Unit

        override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit

        override fun setDefaultLogLevel(level: Int) = Unit

        override fun updateClashMode(newMode: String) = Unit

        override fun writeConnectionEvents(events: ConnectionEvents) = Unit

        override fun writeGroups(message: OutboundGroupIterator) = Unit

        /**
         * libbox's own log carries the Tailscale side of the story: DERP home, netmap updates,
         * handshake failures. Without it a dead tunnel and a dead peer look the same.
         */
        override fun writeLogs(messageList: LogIterator) {
            if (!BuildConfig.DEBUG) return
            while (messageList.hasNext()) {
                val entry = messageList.next()
                Log.d(LOG_TAG, "${entry.level} ${entry.message}")
            }
        }

        override fun writeOutbounds(message: OutboundGroupItemIterator) = Unit

        override fun writeStatus(message: StatusMessage) = Unit
    }

    private class Handler(private val onStop: () -> Unit) : CommandServerHandler {
        override fun connectSSHAgent(): Int = throw UnsupportedOperationException("no ssh agent")

        override fun getSystemProxyStatus(): SystemProxyStatus? = null

        override fun serviceReload() {
            // Nothing outside the box reloads it; the app restarts it when settings change.
        }

        override fun serviceStop() {
            onStop()
        }

        override fun setSystemProxyEnabled(enabled: Boolean) = Unit

        override fun triggerNativeCrash() = Unit

        override fun writeDebugMessage(message: String) = Unit
    }

    private class StringArray(items: Collection<String>) : StringIterator {
        private val iterator = items.iterator()

        override fun len(): Int = 0

        override fun hasNext(): Boolean = iterator.hasNext()

        override fun next(): String = iterator.next()
    }

    private class InterfaceArray(items: Collection<io.nekohasekai.libbox.NetworkInterface>) :
        NetworkInterfaceIterator {
        private val iterator = items.iterator()

        override fun hasNext(): Boolean = iterator.hasNext()

        override fun next(): io.nekohasekai.libbox.NetworkInterface = iterator.next()
    }

    companion object {
        private const val INBOUND_TAG = "rex-mixed"
        private const val ENDPOINT_TAG = "rex-tailscale"
        private const val LOCAL_DNS_TAG = "local"
        private const val TAILNET_DNS_TAG = "tailnet"
        private const val RCODE_NXDOMAIN = 3
        private const val DEFAULT_MTU = 1500
        private const val FIRST_PORT = 10800

        @Volatile private var setupDone = false
    }
}

/** Tailscale only accepts lowercase letters, digits and dashes in a node name. */
fun sanitizeHostname(raw: String): String = raw.lowercase()
    .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
    .joinToString("")
    .trim('-')
    .take(63)

/** Node name Tailscale registers when the settings leave the hostname empty. */
fun defaultHostname(): String = sanitizeHostname(
    listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString("-"),
).ifBlank { "rexpocket" }
