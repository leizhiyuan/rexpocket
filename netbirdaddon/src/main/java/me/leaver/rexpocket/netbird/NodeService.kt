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
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.util.Log
import me.leaver.rexpocket.go.netbird.Netbird
import me.leaver.rexpocket.go.netbird.Node
import me.leaver.rexpocket.go.netbird.Platform
import me.leaver.rexpocket.netbird.api.INetbirdNode
import me.leaver.rexpocket.netbird.api.INetbirdNodeListener
import me.leaver.rexpocket.netbird.api.NetbirdAddon
import java.io.File

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
    private val lock = Any()

    @Volatile private var node: Node? = null
    @Volatile private var running: Pair<String, String>? = null
    @Volatile private var keepRunning = false
    @Volatile private var foreground = false

    private val stateDirectory: File get() = File(filesDir, "netbird")

    private val platform = object : Platform {
        override fun log(line: String) {
            if (BuildConfig.DEBUG) Log.d(TAG, line)
        }

        override fun stateChanged() = notifyListeners()
    }

    private val binder = object : INetbirdNode.Stub() {
        override fun version(): Int {
            enforce()
            return NetbirdAddon.VERSION
        }

        override fun start(hostname: String, setupKey: String, managementUrl: String) {
            enforce()
            startNode(hostname, setupKey, managementUrl)
        }

        override fun stop() {
            enforce()
            stopNode()
        }

        override fun state(): String {
            enforce()
            val current = node ?: return STOPPED
            return failing { current.state() }
        }

        override fun ping(ip: String, timeoutMillis: Int): String {
            enforce()
            return failing { running().ping(ip, timeoutMillis) }
        }

        override fun dialError(host: String): String {
            enforce()
            return node?.dialError(host).orEmpty()
        }

        override fun dial(host: String, port: Int, timeoutMillis: Int): ParcelFileDescriptor {
            enforce()
            val fd = failing { running().dialFd(host, port, timeoutMillis) }
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
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** RexPocket is gone, or let go of the node: nothing else may use it, so it stops. */
    override fun onUnbind(intent: Intent?): Boolean {
        keepRunning = false
        stopNode()
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
        stopNode()
        listeners.kill()
        super.onDestroy()
    }

    private fun enforce() {
        if (checkCallingPermission(NetbirdAddon.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("the caller does not hold ${NetbirdAddon.PERMISSION}")
        }
    }

    private fun running(): Node = node ?: throw IllegalStateException("the Netbird node is not running")

    /** Runs [block], turning the Go library's error into one Binder carries to RexPocket. */
    private inline fun <T> failing(block: () -> T): T = try {
        block()
    } catch (e: IllegalStateException) {
        throw e
    } catch (e: Exception) {
        throw IllegalStateException(e.message?.ifBlank { null } ?: "Netbird failed")
    }

    private fun startNode(hostname: String, setupKey: String, managementUrl: String) = synchronized(lock) {
        val config = hostname to managementUrl
        if (node != null && running == config) return@synchronized
        closeLocked()
        stateDirectory.mkdirs()
        node = failing { Netbird.startPrivate(stateDirectory.absolutePath, hostname, setupKey, managementUrl, platform) }
        running = config
        updateForeground()
        notifyListeners()
    }

    private fun stopNode() = synchronized(lock) {
        closeLocked()
        updateForeground()
        notifyListeners()
    }

    private fun closeLocked() {
        val current = node ?: return
        node = null
        running = null
        runCatching { current.close() }
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

    private fun wantsForeground(): Boolean = keepRunning && node != null

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
    }
}
