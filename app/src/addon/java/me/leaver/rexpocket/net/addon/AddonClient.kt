package me.leaver.rexpocket.net.addon

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import me.leaver.rexpocket.core.AppVersion
import me.leaver.rexpocket.diagnostics.Diagnostics
import me.leaver.rexpocket.net.LOG_TAG
import me.leaver.rexpocket.net.TailnetException
import me.leaver.rexpocket.netbird.api.INetbirdNode
import me.leaver.rexpocket.netbird.api.INetbirdNodeListener
import me.leaver.rexpocket.netbird.api.NetbirdAddon
import java.io.IOException

/** What is known of the installed add-on without binding it: what its package says. */
internal data class AddonPackage(
    val installed: Boolean,
    /** Signed with RexPocket's key. An add-on that is not is never bound. */
    val trusted: Boolean = installed,
    val versionName: String = "",
    /** The revision its service declares; 1 for an add-on from before revisions were declared. */
    val revision: Int = if (installed) 1 else 0,
) {
    /**
     * Whether the add-on is older than RexPocket: of an older revision, so without calls this app
     * would use, or of an older version name. A newer one is no reason, nor one that is not ours.
     */
    fun olderThan(appVersionName: String): Boolean {
        if (!installed || !trusted) return false
        if (revision < NetbirdAddon.REVISION) return true
        val mine = AppVersion.parse(versionName) ?: return false
        val app = AppVersion.parse(appVersionName) ?: return false
        return mine < app
    }

    companion object {
        val MISSING = AddonPackage(installed = false)
    }
}

/**
 * The binding to the add-on's service, as [AddonClient] uses it. [ServiceAddonLink] is the real one;
 * a test hands in its own and plays the add-on.
 */
internal interface AddonLink {
    /** What the add-on's package says: whether it is on the phone, who signed it, its revision. */
    fun inspect(): AddonPackage

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

    override fun inspect(): AddonPackage = inspectAddon(context)

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

/**
 * Reads the add-on's package: installed or not, signed with this app's key or not (an app of
 * another key under the add-on's name would otherwise be handed setup keys and connections), its
 * version name, and the revision its service declares.
 */
internal fun inspectAddon(context: Context): AddonPackage {
    val pm = context.packageManager
    val info = runCatching { pm.getPackageInfo(NetbirdAddon.PACKAGE, 0) }.getOrNull() ?: return AddonPackage.MISSING
    val trusted = pm.checkSignatures(context.packageName, NetbirdAddon.PACKAGE) == PackageManager.SIGNATURE_MATCH
    val revision = runCatching {
        pm.getServiceInfo(ComponentName(NetbirdAddon.PACKAGE, "${NetbirdAddon.PACKAGE}.NodeService"), PackageManager.GET_META_DATA)
            .metaData?.getInt(NetbirdAddon.META_REVISION, 1)
    }.getOrNull() ?: 1
    return AddonPackage(installed = true, trusted = trusted, versionName = info.versionName.orEmpty(), revision = revision.coerceAtLeast(1))
}

/** What the client says when the add-on cannot be used, in the app's own words. */
internal class AddonTexts(
    val notInstalled: String,
    val notAllowed: String,
    val noAnswer: String,
    val restarting: String,
    /** The add-on speaks an older base version than this app: it needs updating. */
    val addonOlder: (addon: Int, app: Int) -> String,
    /** The add-on speaks a newer base version than this app: RexPocket needs updating. */
    val addonNewer: (addon: Int, app: Int) -> String,
    /** The add-on is of a revision without accounts, and an account other than the first runs. */
    val accountsNeedUpdate: String,
) {
    companion object {
        fun of(context: Context) = AddonTexts(
            notInstalled = context.getString(me.leaver.rexpocket.R.string.netbird_addon_missing, NetbirdAddon.APK_NAME),
            notAllowed = context.getString(me.leaver.rexpocket.R.string.netbird_addon_not_allowed),
            noAnswer = context.getString(me.leaver.rexpocket.R.string.netbird_addon_no_answer),
            restarting = context.getString(me.leaver.rexpocket.R.string.netbird_addon_restarting),
            addonOlder = { addon, app -> context.getString(me.leaver.rexpocket.R.string.netbird_addon_version, addon, app) },
            addonNewer = { addon, app -> context.getString(me.leaver.rexpocket.R.string.netbird_addon_version_newer, addon, app) },
            accountsNeedUpdate = context.getString(me.leaver.rexpocket.R.string.netbird_addon_accounts_update),
        )
    }
}

/** Where the client keeps the accounts whose registration the add-on still has to delete. */
internal interface ForgetQueue {
    fun all(): Set<String>
    fun add(profile: String)
    fun remove(profile: String)

    /** Kept in memory only: for the tests. */
    class InMemory : ForgetQueue {
        private val set = LinkedHashSet<String>()
        @Synchronized override fun all(): Set<String> = set.toSet()
        @Synchronized override fun add(profile: String) {
            set += profile
        }
        @Synchronized override fun remove(profile: String) {
            set -= profile
        }
    }

    /** Kept with the app's settings, so a removal made while the add-on was away is done later. */
    class Stored(context: Context) : ForgetQueue {
        private val prefs = context.getSharedPreferences("netbird_addon", Context.MODE_PRIVATE)
        @Synchronized override fun all(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()
        @Synchronized override fun add(profile: String) {
            prefs.edit().putStringSet(KEY, all() + profile).apply()
        }
        @Synchronized override fun remove(profile: String) {
            prefs.edit().putStringSet(KEY, all() - profile).apply()
        }

        private companion object {
            const val KEY = "forget"
        }
    }
}

/**
 * RexPocket's side of the Netbird add-on: binds its service, checks it speaks [NetbirdAddon.VERSION]
 * and which revision, and passes the node's calls on. When the add-on's process dies, the state says
 * so and Android binds it again; the node that was running is then started again with the same
 * account and settings (the add-on kept its registration, so no setup key is needed). A dead binding
 * (the add-on was updated) is made again, and the new add-on asked again what it speaks.
 */
internal class AddonClient(
    private val link: AddonLink,
    private val texts: AddonTexts,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    /** Where the node is started again after the add-on came back: off the binding's thread. */
    private val background: (() -> Unit) -> Unit = { Thread(it, "netbird-addon").start() },
    private val forgets: ForgetQueue = ForgetQueue.InMemory(),
) {
    /** Told whenever the node's state may have changed: the add-on said so, or it came or went. */
    @Volatile var onStateChanged: () -> Unit = {}

    private val lock = Object()
    private var node: INetbirdNode? = null
    private var bound = false

    /** The bound add-on's revision; 0 while none is bound. */
    private var revision = 0

    /** Why the bound add-on cannot be used: it speaks another version, or did not answer. */
    private var problem: String? = null

    /** The node RexPocket started, so it can be started again when the add-on comes back. */
    private var wanted: Wanted? = null

    /** The add-on went away while [wanted] ran on it. */
    private var lost = false

    /** Whether RexPocket asked the node to keep running while it is in the background. */
    private var keepInBackground = false

    private data class Wanted(val profile: String, val hostname: String, val managementUrl: String)

    private val listener = object : INetbirdNodeListener.Stub() {
        override fun stateChanged() = onStateChanged()
    }

    private val events = object : AddonLink.Events {
        override fun connected(node: INetbirdNode) {
            val version = runCatching { node.version() }.getOrNull()
            // An add-on of revision 1 has no such call and answers 0.
            val revision = if (version == NetbirdAddon.VERSION) {
                runCatching { node.revision(NetbirdAddon.REVISION) }.getOrDefault(0).coerceAtLeast(1)
            } else 0
            var reopen: Wanted? = null
            synchronized(lock) {
                if (version == NetbirdAddon.VERSION) {
                    problem = null
                    this@AddonClient.node = node
                    this@AddonClient.revision = revision
                    runCatching {
                        node.setListener(listener)
                        if (keepInBackground) node.keepRunning(true)
                    }
                    if (lost) {
                        lost = false
                        reopen = wanted
                    }
                    val name = runCatching { link.inspect().versionName }.getOrDefault("")
                    Diagnostics.event(LOG_TAG, "Netbird add-on ${name.ifBlank { "?" }}: bound, version $version revision $revision")
                } else {
                    this@AddonClient.node = null
                    this@AddonClient.revision = 0
                    problem = when {
                        version == null -> texts.noAnswer
                        version < NetbirdAddon.VERSION -> texts.addonOlder(version, NetbirdAddon.VERSION)
                        else -> texts.addonNewer(version, NetbirdAddon.VERSION)
                    }
                    Diagnostics.event(LOG_TAG, "Netbird add-on: version ${version ?: "?"}, this app speaks ${NetbirdAddon.VERSION}")
                }
                lock.notifyAll()
            }
            reopen?.let { w ->
                Diagnostics.event(LOG_TAG, "Netbird add-on: back; starting its node again")
                background {
                    runCatching { startOn(node, revision, w, "") }
                        .onFailure { Diagnostics.event(LOG_TAG, "Netbird add-on: restart failed: ${it.message}") }
                    onStateChanged()
                }
            }
            if (version == NetbirdAddon.VERSION && revision >= NetbirdAddon.REVISION_ACCOUNTS && forgets.all().isNotEmpty()) {
                background { flushForgets(node) }
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
                revision = 0
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

    /** What the add-on's package says; see [AddonPackage]. */
    fun inspect(): AddonPackage = link.inspect()

    /** Whether the add-on is on the phone at all. */
    fun installed(): Boolean = link.inspect().installed

    /** Whether the add-on is bound and speaks this app's version right now. */
    val connected: Boolean get() = synchronized(lock) { node != null }

    /** The revision of the add-on: the bound one's answer, or what its package declares. */
    fun revision(): Int = synchronized(lock) { revision.takeIf { node != null && it > 0 } } ?: link.inspect().revision

    /** Whether the add-on can keep several accounts: one signed with this app's key, of a revision that has them. */
    fun keepsAccounts(): Boolean {
        val pkg = link.inspect()
        return pkg.installed && pkg.trusted && revision() >= NetbirdAddon.REVISION_ACCOUNTS
    }

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
        val pkg = link.inspect()
        if (!pkg.installed) throw TailnetException(texts.notInstalled)
        if (!pkg.trusted) throw TailnetException(texts.notAllowed)
        val ok = try {
            link.bind(events)
        } catch (e: SecurityException) {
            throw TailnetException(texts.notAllowed, e)
        }
        if (!ok) throw TailnetException(texts.notAllowed)
        bound = true
    }

    /**
     * Starts the add-on's node for account [profile] ([NetbirdAddon.FIRST_PROFILE] for the first),
     * binding it first. Blocking. An add-on without accounts runs the first account only.
     */
    fun start(profile: String, hostname: String, setupKey: String, managementUrl: String) {
        val node = connect()
        val revision = synchronized(lock) { revision }
        val w = Wanted(profile, hostname, managementUrl)
        startOn(node, revision, w, setupKey)
        synchronized(lock) {
            wanted = w
            lost = false
        }
    }

    private fun startOn(node: INetbirdNode, revision: Int, w: Wanted, setupKey: String) {
        if (w.profile == NetbirdAddon.FIRST_PROFILE) {
            remote { node.start(w.hostname, setupKey, w.managementUrl) }
        } else {
            // An older add-on would take the call for one it has not and do nothing: never made.
            if (revision < NetbirdAddon.REVISION_ACCOUNTS) throw TailnetException(texts.accountsNeedUpdate)
            remote { node.startProfile(w.profile, w.hostname, setupKey, w.managementUrl) }
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
            revision = 0
        }
    }

    /**
     * Has the add-on delete account [profile]'s registration, which is not running: the account was
     * removed. Blocking. An add-on that is away, or of a revision without accounts, does it once it
     * is bound again; until then it is kept in [forgets].
     */
    fun forget(profile: String) {
        if (profile == NetbirdAddon.FIRST_PROFILE) return
        forgets.add(profile)
        val idle = synchronized(lock) { wanted == null && !bound }
        try {
            flushForgets(connect())
        } catch (e: TailnetException) {
            Diagnostics.event(LOG_TAG, "Netbird add-on: an account's registration is removed once the add-on is back")
        } finally {
            // Bound only for this: let go again, as nothing runs on it.
            if (idle) synchronized(lock) {
                if (wanted == null) {
                    link.unbind()
                    bound = false
                    node = null
                    revision = 0
                }
            }
        }
    }

    private fun flushForgets(node: INetbirdNode) {
        if (synchronized(lock) { revision } < NetbirdAddon.REVISION_ACCOUNTS) return
        for (profile in forgets.all()) {
            try {
                node.forgetProfile(profile)
                forgets.remove(profile)
                Diagnostics.event(LOG_TAG, "Netbird add-on: an account's registration was removed")
            } catch (e: IllegalStateException) {
                // Refused (it runs, or is not an account): asking again would not change that.
                forgets.remove(profile)
                Diagnostics.event(LOG_TAG, "Netbird add-on: an account's removal was refused: ${e.message}")
            } catch (e: RemoteException) {
                return
            }
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
            // Unchanged: a bound add-on has it already, and a new binding is told it on connecting.
            if (keepInBackground == keep) return
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
