package me.leaver.rexpocket.core

import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import kotlin.concurrent.thread

/**
 * An SSH server that signs in `me` with `pw` and answers every exec with a pipe to Rex on
 * [rexPort], as `rex __internal dial-stdio` does; with no port, what the app's command says
 * on a machine without Rex.
 */
internal class TestSshd(rexPort: Int?) : AutoCloseable {
    private val server = SshServer.setUpDefaultServer().apply {
        port = 0
        keyPairProvider = SimpleGeneratorHostKeyProvider().apply { algorithm = KeyUtils.EC_ALGORITHM; keySize = 256 }
        setPasswordAuthenticator { user, password, _ -> user == "me" && password == "pw" }
        setCommandFactory { _, _ -> Pipe(rexPort) }
        start()
    }
    val port: Int get() = server.port
    val fingerprint: String = KeyUtils.getFingerPrint(BuiltinDigests.sha256, server.keyPairProvider.loadKeys(null).first().public)

    override fun close() = server.stop(true)
}

internal class Pipe(private val rexPort: Int?) : Command {
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private lateinit var error: OutputStream
    private lateinit var exit: ExitCallback
    @Volatile private var socket: Socket? = null

    override fun setInputStream(`in`: InputStream) { input = `in` }
    override fun setOutputStream(out: OutputStream) { output = out }
    override fun setErrorStream(err: OutputStream) { error = err }
    override fun setExitCallback(callback: ExitCallback) { exit = callback }

    override fun start(channel: ChannelSession, env: Environment) {
        if (rexPort == null) {
            error.write("rex was not found on this machine\n".toByteArray())
            error.flush()
            exit.onExit(127)
            return
        }
        val s = Socket("127.0.0.1", rexPort).also { socket = it }
        thread(isDaemon = true) { runCatching { pump(input, s.getOutputStream()) }; runCatching { s.shutdownOutput() } }
        thread(isDaemon = true) {
            runCatching { pump(s.getInputStream(), output) }
            exit.onExit(0)
        }
    }

    private fun pump(from: InputStream, to: OutputStream) {
        val buf = ByteArray(8192)
        while (true) {
            val n = from.read(buf)
            if (n < 0) return
            to.write(buf, 0, n)
            to.flush()
        }
    }

    override fun destroy(channel: ChannelSession) {
        runCatching { socket?.close() }
    }
}
