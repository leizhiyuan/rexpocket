package me.leaver.rexpocket.net.addon

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import me.leaver.rexpocket.diagnostics.Diagnostics
import me.leaver.rexpocket.net.LOG_TAG
import me.leaver.rexpocket.net.TailnetException
import me.leaver.rexpocket.netbird.api.INetbirdNode
import me.leaver.rexpocket.netbird.api.INetbirdNodeListener
import me.leaver.rexpocket.netbird.api.NetbirdAddon
import java.io.IOException

/**
 * The binding to the add-on's service, as [AddonClient] uses it. [ServiceAddonLink] is the real one;
 * a test hands in its own and plays the add-on.
 */
internal interface AddonLink {
    /** Whether the add-on app is on the phone. */
    fun installed(): Boolean

    /**
     * Binds the add-on's service; [events] hear how it goes. False when Android would not bind it.
     * Throws SecurityException when this app does not hold [NetbirdAddon.PERMISSION].
     */
    fun bind(events: Events): Boolean

    fun unbind()

    interface Events {
        /** The service is bound, or bound again after its process came back. */
        fun connected(node: INetbirdNode)

        /** The add-on's process ended; Android binds it again by itself, and [connected] follows. */
        fun disconnected()

        /** The binding is dead for good (the add-on was updated or removed): it has to be made again. */
        fun died()
    }
}

/** The add-on's service, bound by its action and package. */
internal class ServiceAddonLink(private val context: Context) : AddonLink {
    @Volatile private var connection: ServiceConnection? = null

    override fun installed(): Boolean =
        runCatching { context.packageManager.getPackageInfo(NetbirdAddon.PACKAGE, 0) }.isSuccess

    override fun bind(events: AddonLink.Events): Boolean {
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) events.died() else events.connected(INetbirdNode.Stub.asInterface(binder))
            }

            override fun onServiceDisconnected(name: ComponentName?) = events.disconnected()
            override fun onBindingDied(name: ComponentName?) = events.died()
            override fun onNullBinding(name: ComponentName?) = events.died()
        }
        val intent = Intent(NetbirdAddon.ACTION).setPackage(NetbirdAddon.PACKAGE)
        val bound = context.bindService(intent, c, Context.BIND_AUTO_CREATE)
        if (bound) connection = c else runCatching { context.unbindService(c) }
        return bound
    }

    override fun unbind() {
        val c = connection ?: return
        connection = null
        runCatching { context.unbindService(c) }
    }
}

/** What the client says when the add-on cannot be used, in the app's own words. */
internal class AddonTexts(
    val notInstalled: String,
    val notAllowed: String,
    val noAnswer: String,
    val restarting: String,
    val versionMismatch: (addon: Int, app: Int) -> String,
) {
    companion object {
        fun of(context: Context) = AddonTexts(
            notInstalled = context.getString(me.leaver.rexpocket.R.string.netbird_addon_missing, NetbirdAddon.APK_NAME),
            notAllowed = context.getString(me.leaver.rexpocket.R.string.netbird_addon_not_allowed),
            noAnswer = context.getString(me.leaver.rexpocket.R.string.netbird_addon_no_answer),
            restarting = context.getString(me.leaver.rexpocket.R.string.netbird_addon_restarting),
            versionMismatch = { addon, app -> context.getString(me.leaver.rexpocket.R.string.netbird_addon_version, addon, app) },
        )
    }
}

/**
 * RexPocket's side of the Netbird add-on: binds its service, checks it speaks [NetbirdAddon.VERSION],
 * and passes the node's calls on. When the add-on's process dies, the state says so and Android
 * binds it again; the node that was running is then started again with the same settings (the
 * add-on kept its registration, so no setup key is needed). A dead binding is made again.
 */
internal class AddonClient(
    private val link: AddonLink,
    private val texts: AddonTexts,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    /** Where the node is started again after the add-on came back: off the binding's thread. */
    private val background: (() -> Unit) -> Unit = { Thread(it, "netbird-addon").start() },
) {
    /** Told whenever the node's state may have changed: the add-on said so, or it came or went. */
    @Volatile var onStateChanged: () -> Unit = {}

    private val lock = Object()
    private var node: INetbirdNode? = null
    private var bound = false

    /** Why the bound add-on cannot be used: it speaks another version, or did not answer. */
    private var problem: String? = null

    /** The node RexPocket started, so it can be started again when the add-on comes back. */
    private var wanted: Wanted? = null

    /** The add-on went away while [wanted] ran on it. */
    private var lost = false

    /** Whether RexPocket asked the node to keep running while it is in the background. */
    private var keepInBackground = false

    private data class Wanted(val hostname: String, val managementUrl: String)

    private val listener = object : INetbirdNodeListener.Stub() {
        override fun stateChanged() = onStateChanged()
    }

    private val events = object : AddonLink.Events {
        override fun connected(node: INetbirdNode) {
            val version = runCatching { node.version() }.getOrNull()
            var reopen: Wanted? = null
            synchronized(lock) {
                if (version == NetbirdAddon.VERSION) {
                    problem = null
                    this@AddonClient.node = node
                    runCatching {
                        node.setListener(listener)
                        if (keepInBackground) node.keepRunning(true)
                    }
                    if (lost) {
                        lost = false
                        reopen = wanted
                    }
                } else {
                    this@AddonClient.node = null
                    problem = version?.let { texts.versionMismatch(it, NetbirdAddon.VERSION) } ?: texts.noAnswer
                    Diagnostics.event(LOG_TAG, "Netbird add-on: version ${version ?: "?"}, this app speaks ${NetbirdAddon.VERSION}")
                }
                lock.notifyAll()
            }
            reopen?.let { w ->
                Diagnostics.event(LOG_TAG, "Netbird add-on: back; starting its node again")
                background {
                    runCatching { node.start(w.hostname, "", w.managementUrl) }
                        .onFailure { Diagnostics.event(LOG_TAG, "Netbird add-on: restart failed: ${it.message}") }
                    onStateChanged()
                }
            }
            onStateChanged()
        }

        override fun disconnected() {
            Diagnostics.event(LOG_TAG, "Netbird add-on: its process ended")
            synchronized(lock) {
                node = null
                if (wanted != null) lost = true
                lock.notifyAll()
            }
            onStateChanged()
        }

        override fun died() {
            Diagnostics.event(LOG_TAG, "Netbird add-on: the binding died; binding again")
            val again = synchronized(lock) {
                node = null
                // A new add-on may speak another version: it is asked again.
                problem = null
                if (wanted != null) lost = true
                link.unbind()
                bound = false
                lock.notifyAll()
                wanted != null
            }
            onStateChanged()
            if (again) background { runCatching { synchronized(lock) { bindLocked() } } }
        }
    }

    /** Whether the add-on is on the phone at all. */
    fun installed(): Boolean = link.installed()

    /** Whether the add-on is bound and speaks this app's version right now. */
    val connected: Boolean get() = synchronized(lock) { node != null }

    /** The bound node, binding it first and waiting for it. Throws [TailnetException] with the reason. */
    private fun connect(): INetbirdNode = synchronized(lock) {
        node?.let { return it }
        if (!bound) bindLocked()
        val deadline = System.currentTimeMillis() + connectTimeoutMs
        while (node == null && problem == null) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw TailnetException(texts.noAnswer)
            lock.wait(left)
        }
        problem?.let { throw TailnetException(it) }
        node!!
    }

    private fun bindLocked() {
        if (bound) return
        if (!link.installed()) throw TailnetException(texts.notInstalled)
        val ok = try {
            link.bind(events)
        } catch (e: SecurityException) {
            throw TailnetException(texts.notAllowed, e)
        }
        if (!ok) throw TailnetException(texts.notAllowed)
        bound = true
    }

    /** Starts the add-on's node, binding it first. Blocking. */
    fun start(hostname: String, setupKey: String, managementUrl: String) {
        val node = connect()
        remote { node.start(hostname, setupKey, managementUrl) }
        synchronized(lock) {
            wanted = Wanted(hostname, managementUrl)
            lost = false
        }
    }

    /** Stops the node and lets go of the add-on, so Android may stop it too. */
    fun stop() {
        val node = synchronized(lock) {
            wanted = null
            lost = false
            node
        }
        runCatching {
            node?.setListener(null)
            node?.stop()
        }
        synchronized(lock) {
            link.unbind()
            bound = false
            this.node = null
            problem = null
        }
    }

    /**
     * The node's state JSON. While the add-on is away it is a state of its own: starting again,
     * with the reason as a health line the status shows.
     */
    fun state(): String {
        val (current, missing) = synchronized(lock) { node to (problem ?: texts.restarting.takeIf { wanted != null }) }
        if (current == null) return missing?.let { stateJson("Starting", it) } ?: STOPPED
        return try {
            current.state()
        } catch (e: RemoteException) {
            stateJson("Starting", texts.restarting)
        } catch (e: IllegalStateException) {
            throw TailnetException(e.message ?: texts.noAnswer, e)
        }
    }

    fun ping(ip: String, timeoutMillis: Int): String = remote { current().ping(ip, timeoutMillis) }

    fun dialError(host: String): String = runCatching { synchronized(lock) { node }?.dialError(host) }.getOrNull().orEmpty()

    /** Dials [host]:[port] through the add-on's node; the caller owns the descriptor. */
    fun dial(host: String, port: Int, timeoutMillis: Int): ParcelFileDescriptor {
        val node = synchronized(lock) { node } ?: throw IOException(problem() ?: texts.restarting)
        return try {
            node.dial(host, port, timeoutMillis) ?: throw IOException(texts.noAnswer)
        } catch (e: IllegalStateException) {
            throw IOException(e.message ?: texts.noAnswer, e)
        } catch (e: RemoteException) {
            throw IOException(texts.restarting, e)
        }
    }

    /** Whether the node must keep running while RexPocket is in the background. */
    fun keepInBackground(keep: Boolean) {
        val node = synchronized(lock) {
            keepInBackground = keep
            node
        }
        runCatching { node?.keepRunning(keep) }
    }

    private fun problem(): String? = synchronized(lock) { problem }

    private fun current(): INetbirdNode = synchronized(lock) { node } ?: throw TailnetException(problem() ?: texts.restarting)

    /** Runs a call on the add-on, with its failure as the app shows it. */
    private inline fun <T> remote(block: () -> T): T = try {
        block()
    } catch (e: IllegalStateException) {
        throw TailnetException(e.message ?: texts.noAnswer, e)
    } catch (e: RemoteException) {
        throw TailnetException(texts.restarting, e)
    }

    companion object {
        /** How long the add-on has to bind: it starts its process, but not yet its node. */
        const val CONNECT_TIMEOUT_MS = 10_000L

        /** A node that is not running, in the shape the node's state takes. */
        const val STOPPED = """{"backendState":"Stopped","peers":[]}"""

        /** A state the add-on did not report: [backendState] and one health line. */
        fun stateJson(backendState: String, health: String): String =
            """{"backendState":${quote(backendState)},"health":[${quote(health)}],"peers":[]}"""

        private fun quote(s: String): String = buildString {
            append('"')
            for (c in s) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < ' ' -> append(String.format("\\u%04x", c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
    }
}
