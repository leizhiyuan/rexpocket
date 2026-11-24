package me.leaver.rexpocket.netbird

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.util.Log
import me.leaver.rexpocket.go.netbird.Netbird
import me.leaver.rexpocket.go.netbird.Node
import me.leaver.rexpocket.go.netbird.Platform
import me.leaver.rexpocket.netbird.api.INetbirdNode
import me.leaver.rexpocket.netbird.api.INetbirdNodeListener
import me.leaver.rexpocket.netbird.api.NetbirdAddon

/**
 * The Netbird node the add-on runs for RexPocket, and the bound service RexPocket reaches it by.
 *
 * The node listens on no port (`Netbird.startPrivate`): every connection is a [INetbirdNode.dial]
 * from a caller holding [NetbirdAddon.PERMISSION], a signature permission, so only an app signed
 * with the add-on's key gets one.
 *
 * In the background: while RexPocket is on screen its binding keeps this process as important as
 * RexPocket itself, so nothing more is needed. When RexPocket goes to the background and keeps its
 * connection open (its background alerts), it calls [INetbirdNode.keepRunning], and this service
 * becomes a foreground service of type specialUse for as long as the node runs. When RexPocket
 * unbinds, or its process ends, the node stops and so does the service.
 */
class NodeService : Service() {
    private val listeners = RemoteCallbackList<INetbirdNodeListener>()

    @Volatile private var keepRunning = false
    @Volatile private var foreground = false

    private val platform = object : Platform {
        override fun log(line: String) {
            if (BuildConfig.DEBUG) Log.d(TAG, line)
        }

        override fun stateChanged() = notifyListeners()
    }

    private val nodes by lazy {
        Nodes(filesDir, engine = { dir, hostname, setupKey, managementUrl ->
            GoNode(Netbird.startPrivate(dir.absolutePath, hostname, setupKey, managementUrl, platform))
        }) {
            updateForeground()
            notifyListeners()
        }
    }

    private val binder = object : INetbirdNode.Stub() {
        override fun version(): Int {
            enforce()
            return BuildConfig.ADDON_VERSION
        }

        override fun start(hostname: String, setupKey: String, managementUrl: String) {
            enforce()
            failing { nodes.start(NetbirdAddon.FIRST_PROFILE, hostname, setupKey, managementUrl) }
        }

        override fun stop() {
            enforce()
            nodes.stop()
        }

        override fun state(): String {
            enforce()
            val current = nodes.node ?: return STOPPED
            return failing { current.state() }
        }

        override fun ping(ip: String, timeoutMillis: Int): String {
            enforce()
            return failing { nodes.running().ping(ip, timeoutMillis) }
        }

        override fun dialError(host: String): String {
            enforce()
            return nodes.node?.dialError(host).orEmpty()
        }

        override fun dial(host: String, port: Int, timeoutMillis: Int): ParcelFileDescriptor {
            enforce()
            val fd = failing { nodes.running().dialFd(host, port, timeoutMillis) }
            return ParcelFileDescriptor.adoptFd(fd)
        }

        override fun setListener(listener: INetbirdNodeListener?) {
            enforce()
            synchronized(listeners) {
                val count = listeners.beginBroadcast()
                val old = (0 until count).map { listeners.getBroadcastItem(it) }
                listeners.finishBroadcast()
                old.forEach { listeners.unregister(it) }
                listener?.let { listeners.register(it) }
            }
        }

        override fun keepRunning(background: Boolean) {
            enforce()
            keepRunning = background
            updateForeground()
        }

        override fun revision(clientRevision: Int): Int {
            enforce()
            Log.i(TAG, "RexPocket speaks revision $clientRevision, the add-on ${BuildConfig.ADDON_REVISION}")
            return BuildConfig.ADDON_REVISION
        }

        override fun startProfile(profile: String, hostname: String, setupKey: String, managementUrl: String) {
            enforce()
            failing { nodes.start(profile, hostname, setupKey, managementUrl) }
        }

        override fun forgetProfile(profile: String) {
            enforce()
            failing { nodes.forget(profile) }
            Log.i(TAG, "an account's registration was removed")
        }

        // An add-on built as an older revision (the tests' -Prexpocket.addonRevision) has none of the
        // later calls, as a real one of that revision: such a call is not answered.
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (BuildConfig.ADDON_REVISION < NetbirdAddon.REVISION_ACCOUNTS && code in FIRST_REVISION_2_CALL..IBinder.LAST_CALL_TRANSACTION) {
                return false
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** RexPocket is gone, or let go of the node: nothing else may use it, so it stops. */
    override fun onUnbind(intent: Intent?): Boolean {
        keepRunning = false
        nodes.stop()
        stopSelf()
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started only by updateForeground, with startForegroundService: go foreground at once, as
        // Android requires, then step down again if the node stopped in the meantime.
        goForeground()
        if (!wantsForeground()) leaveForeground()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        nodes.stop()
        listeners.kill()
        super.onDestroy()
    }

    private fun enforce() {
        if (checkCallingPermission(NetbirdAddon.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("the caller does not hold ${NetbirdAddon.PERMISSION}")
        }
    }

    /** Runs [block], turning the Go library's error into one Binder carries to RexPocket. */
    private inline fun <T> failing(block: () -> T): T = try {
        block()
    } catch (e: IllegalStateException) {
        throw e
    } catch (e: Exception) {
        throw IllegalStateException(e.message?.ifBlank { null } ?: "Netbird failed")
    }

    private fun notifyListeners() {
        synchronized(listeners) {
            val count = listeners.beginBroadcast()
            try {
                for (i in 0 until count) runCatching { listeners.getBroadcastItem(i).stateChanged() }
            } finally {
                listeners.finishBroadcast()
            }
        }
    }

    private fun wantsForeground(): Boolean = keepRunning && nodes.node != null

    private fun updateForeground() {
        if (wantsForeground() == foreground) return
        if (!foreground) {
            // Allowed while RexPocket, which binds this service, may start one itself; see the class.
            try {
                startForegroundService(Intent(this, NodeService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "could not stay in the foreground: ${e.message}")
            }
        } else {
            leaveForeground()
        }
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.addon_channel), NotificationManager.IMPORTANCE_MIN),
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.addon_running_title))
            .setContentText(getString(R.string.addon_running_text))
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foreground = true
        } catch (e: Exception) {
            Log.w(TAG, "could not go to the foreground: ${e.message}")
        }
    }

    private fun leaveForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        // Still bound: the service stays while RexPocket holds it, only no longer started.
        stopSelf()
    }

    private companion object {
        const val TAG = "RexPocketNetbird"
        const val CHANNEL = "node"
        const val NOTIFICATION_ID = 1

        /** The state of a node that is not running, in the shape [Node.state] reports. */
        const val STOPPED = """{"backendState":"Stopped","peers":[]}"""

        /** The transaction code of revision 2's first call, revision(): after revision 1's nine. */
        const val FIRST_REVISION_2_CALL = IBinder.FIRST_CALL_TRANSACTION + 9
    }
}

/** The Go library's node, as [Nodes] uses one. */
private class GoNode(private val node: Node) : EngineNode {
    override fun state(): String = node.state()
    override fun ping(ip: String, timeoutMillis: Int): String = node.ping(ip, timeoutMillis)
    override fun dialError(host: String): String = node.dialError(host)
    override fun dialFd(host: String, port: Int, timeoutMillis: Int): Int = node.dialFd(host, port, timeoutMillis)
    override fun close() = node.close()
}
