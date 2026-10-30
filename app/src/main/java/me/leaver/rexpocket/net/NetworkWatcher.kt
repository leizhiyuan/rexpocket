package me.leaver.rexpocket.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * Calls [onChange] when the phone's default network becomes a different one: Wi-Fi to mobile data,
 * or back after a gap. A socket on the network that went away can take a minute of pings to notice,
 * so the connection is better redialed at once. The first network seen after [start] is not a change.
 */
class NetworkWatcher(context: Context, private val onChange: () -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var current: Network? = null
    private var started = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = current
            current = network
            if (started && previous != network) onChange()
            started = true
        }

        override fun onLost(network: Network) {
            if (current == network) current = null
        }
    }

    fun start() {
        runCatching { connectivity?.registerDefaultNetworkCallback(callback) }
    }

    fun stop() {
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
        started = false
        current = null
    }
}
