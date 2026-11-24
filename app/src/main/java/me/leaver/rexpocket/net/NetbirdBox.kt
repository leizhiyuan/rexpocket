package me.leaver.rexpocket.net

import android.content.Context
import android.util.Log
import me.leaver.rexpocket.BuildConfig
import me.leaver.rexpocket.go.netbird.Netbird
import me.leaver.rexpocket.go.netbird.Node
import me.leaver.rexpocket.go.netbird.Platform
import java.io.File

/**
 * A Netbird node running inside the app - Netbird's embeddable client, through the `netbird` Go
 * module - and a SOCKS5 proxy on loopback that dials through it. Like the Tailscale node it runs in
 * userspace and never takes the phone's VPN slot. See [TailnetBox] for the lifecycle both share.
 *
 * Netbird's embedded client has no interactive login, so a node joins with a setup key from its
 * dashboard; once joined, the registration is kept and the key is not needed again.
 */
class NetbirdBox(context: Context) : TailnetBox(context) {
    companion object {
        /** Whether this build of the app has the Netbird node; see tools/build-tailnet.sh. */
        fun available(): Boolean = Netbird.available()
    }

    override val name: String = "Netbird"

    override val stateDirectory: File get() = File(context.filesDir, "netbird")

    override fun open(config: TailnetConfig, hostname: String, events: NodeEvents): RunningNode {
        val netbird = config as? NetbirdConfig ?: throw IllegalArgumentException("Netbird needs a Netbird config")
        val node = Netbird.start(
            directoryFor(config.profile).absolutePath,
            hostname,
            netbird.setupKey,
            netbird.managementUrl,
            platform(events),
        )
        return NetbirdRunning(node)
    }

    private fun platform(events: NodeEvents): Platform = object : Platform {
        override fun log(line: String) {
            if (BuildConfig.DEBUG) Log.d(LOG_TAG, "Netbird: $line")
        }

        override fun stateChanged() = events.stateChanged()
    }

    private class NetbirdRunning(private val node: Node) : RunningNode {
        override val proxyPort: Int get() = node.proxyPort()
        override fun state(): String = node.state()
        override fun ping(ip: String, timeoutMillis: Int): String = node.ping(ip, timeoutMillis)
        override fun dialError(host: String): String = node.dialError(host)

        // Netbird's engine follows the network on its own, so there is nothing to hand it.
        override fun networkChanged() = Unit

        override fun close() = node.close()
    }
}
