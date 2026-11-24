package me.leaver.rexpocket.netbird

import me.leaver.rexpocket.netbird.api.NetbirdAddon
import java.io.File

/** One running node as the add-on uses it: the Go library's, or a test's. */
internal interface EngineNode {
    fun state(): String
    fun ping(ip: String, timeoutMillis: Int): String
    fun dialError(host: String): String

    /** One end of a socket pair piped to the connection; the caller owns it. */
    fun dialFd(host: String, port: Int, timeoutMillis: Int): Int
    fun close()
}

/** Starts a node with its registration in [dir]. Throws with the node's reason when it cannot. */
internal fun interface Engine {
    fun start(dir: File, hostname: String, setupKey: String, managementUrl: String): EngineNode
}

/**
 * The add-on's one node and its accounts: which account runs, with which settings, and where each
 * account keeps its registration. The first account ([NetbirdAddon.FIRST_PROFILE]) is in
 * `files/netbird`, as before accounts, so an add-on that joined keeps its registration; another is
 * in `files/netbird-profiles/<name>`, under a name [NetbirdAddon.profileDirectory] cleaned, so no
 * caller can reach outside that directory. One account runs at a time.
 *
 * Kept apart from the service so the JVM tests run it without the Go library.
 */
internal class Nodes(
    private val filesDir: File,
    private val engine: Engine,
    /** Told after the node started or stopped. */
    private val changed: () -> Unit = {},
) {
    private data class Running(val directory: File, val hostname: String, val managementUrl: String)

    private val lock = Any()

    @Volatile var node: EngineNode? = null
        private set

    @Volatile private var running: Running? = null

    /** Where account [profile] keeps its registration. Throws for a name no account has. */
    fun directoryFor(profile: String): File {
        if (profile == NetbirdAddon.FIRST_PROFILE) return File(filesDir, "netbird")
        val name = NetbirdAddon.profileDirectory(profile) ?: throw IllegalStateException("\"$profile\" is not an account name")
        return File(filesDir, "netbird-profiles/$name")
    }

    /**
     * Starts account [profile]'s node, or keeps the running one when it is the same account with
     * the same settings. A node of another account, or other settings, stops first.
     */
    fun start(profile: String, hostname: String, setupKey: String, managementUrl: String) {
        synchronized(lock) {
            val wanted = Running(directoryFor(profile), hostname, managementUrl)
            if (node != null && running == wanted) return
            closeLocked()
            wanted.directory.mkdirs()
            node = engine.start(wanted.directory, hostname, setupKey, managementUrl)
            running = wanted
        }
        changed()
    }

    fun stop() {
        synchronized(lock) { closeLocked() }
        changed()
    }

    /**
     * Deletes account [profile]'s registration: its node key and config go, and nothing of them is
     * read or logged. Not the first account, nor the one running.
     */
    fun forget(profile: String) = synchronized(lock) {
        if (profile == NetbirdAddon.FIRST_PROFILE) throw IllegalStateException("the first account cannot be removed")
        val dir = directoryFor(profile)
        if (node != null && running?.directory == dir) throw IllegalStateException("the account is running: switch to another first")
        if (dir.exists() && !dir.deleteRecursively()) throw IllegalStateException("the account's registration could not be removed")
    }

    /** The running node, or a failure that says there is none. */
    fun running(): EngineNode = node ?: throw IllegalStateException("the Netbird node is not running")

    private fun closeLocked() {
        val current = node ?: return
        node = null
        running = null
        runCatching { current.close() }
    }
}
