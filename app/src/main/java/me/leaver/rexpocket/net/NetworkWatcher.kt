package me.leaver.rexpocket.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.leaver.rexpocket.diagnostics.Diagnostics

/**
 * Calls [onChange] when the phone's default network becomes a different one: Wi-Fi to mobile data,
 * or back after a gap. A socket on the network that went away can take a minute of pings to notice,
 * so the connection is better redialed at once. The network the phone has when [start] runs is not
 * a change; one that comes after a start with none is, so hosts that failed offline dial at once.
 *
 * A move from one network straight to another (Wi-Fi handing over to mobile data, and often back a
 * moment later) is told [DEBOUNCE_MS] after the last move, and only when the network then differs
 * from the one the hosts were last told of: a flap is one redial or none, not two. A network that
 * comes after none is told at once, as nothing that was up can still be saved.
 *
 * It also says whether the default network is validated (Android checked it reaches the internet),
 * and calls [onValidated] when it becomes so: back in the foreground, the app's network is
 * "available" a moment before it is usable, and a dial in between fails.
 */
class NetworkWatcher(
    context: Context,
    private val onValidated: () -> Unit = {},
    private val onChange: () -> Unit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    // Set on the main thread by start and stop, read and moved on the system's network thread.
    @Volatile private var current: Network? = null
    /** The network [onChange] was last told of, or the one there at the start. */
    @Volatile private var settled: Network? = null

    private val handler = Handler(Looper.getMainLooper())
    private val settle = Runnable {
        val now = current
        if (now != null && now != settled) {
            settled = now
            Diagnostics.event(TAG, "network changed")
            onChange()
        }
    }

    private val _validated = MutableStateFlow(false)

    /** Whether the default network is there and validated. */
    val validated: StateFlow<Boolean> = _validated

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = current
            current = network
            Diagnostics.event(TAG, "network available (was ${previous != null})")
            if (previous != network) {
                _validated.value = isValidated(network)
                handler.removeCallbacks(settle)
                if (previous == null) {
                    settled = network
                    onChange()
                } else {
                    handler.postDelayed(settle, DEBOUNCE_MS)
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network != current) return
            val now = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val was = _validated.value
            _validated.value = now
            if (now && !was) {
                Diagnostics.event(TAG, "network validated")
                onValidated()
            }
        }

        override fun onLost(network: Network) {
            if (current == network) {
                current = null
                _validated.value = false
            }
            Diagnostics.event(TAG, "network lost")
        }
    }

    private fun isValidated(network: Network): Boolean =
        runCatching { connectivity?.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
            .getOrNull() == true

    /**
     * Waits up to [timeoutMs] for a validated default network; returns at once when there is one.
     * With none coming in that time it returns anyway: the caller tries, and is told of the next.
     */
    suspend fun awaitValidated(timeoutMs: Long) {
        if (_validated.value) return
        val ok = withTimeoutOrNull(timeoutMs) { _validated.first { it } } != null
        Diagnostics.event(TAG, if (ok) "waited for a validated network" else "no validated network after ${timeoutMs / 1000}s; starting anyway")
    }

    fun start() {
        // The callback's first call names the network there now, which is no change. Taking the
        // first call for that instead missed the one that mattered: with no network at the start,
        // the first is the network coming, and hosts that failed offline waited out their backoff.
        current = runCatching { connectivity?.activeNetwork }.getOrNull()
        settled = current
        _validated.value = current?.let(::isValidated) == true
        runCatching { connectivity?.registerDefaultNetworkCallback(callback) }
    }

    fun stop() {
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
        handler.removeCallbacks(settle)
        current = null
        settled = null
        _validated.value = false
    }

    companion object {
        private const val TAG = "RexPocketNet"

        /** How long a move between two networks has to hold before the hosts redial. */
        const val DEBOUNCE_MS = 2_000L
    }
}
