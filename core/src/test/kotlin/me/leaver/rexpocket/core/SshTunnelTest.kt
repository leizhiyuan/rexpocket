package me.leaver.rexpocket.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class SshTunnelTest {
    // RFC 8032, section 7.1, test 1: an empty message and the key pair from the vectors.
    private val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    private val publicKey = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    private val signature = hex(
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
            "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
    )

    @Test
    fun ed25519SignsTheRfcVector() {
        val signer = Ed25519Signature()
        signer.init()
        signer.setPrvKey(seed)
        signer.update(ByteArray(0))
        assertArrayEquals(signature, signer.sign())

        val verifier = Ed25519Signature()
        verifier.init()
        verifier.setPubKey(publicKey)
        verifier.update(ByteArray(0))
        assertTrue(verifier.verify(signature))

        // SSH carries a signature as two strings; JSch hands the blob to verify.
        val wrapped = sshString("ssh-ed25519".toByteArray()) + sshString(signature)
        val overBlob = Ed25519Signature()
        overBlob.init()
        overBlob.setPubKey(publicKey)
        overBlob.update(ByteArray(0))
        assertTrue(overBlob.verify(wrapped))

        val wrong = Ed25519Signature()
        wrong.init()
        wrong.setPubKey(publicKey)
        wrong.update(byteArrayOf(1))
        assertFalse(wrong.verify(signature))
    }

    @Test
    fun ed25519PublicKeyComesFromItsSeed() {
        val generator = Ed25519KeyPairGen()
        generator.init("Ed25519", seed)
        assertArrayEquals(publicKey, generator.getPub())
        assertArrayEquals(seed, generator.getPrv())
    }

    @Test
    fun aFingerprintReadsLikeOpenssh() {
        // GitHub's published Ed25519 host key and the fingerprint ssh-keygen prints for it.
        val blob = hex("0000000b7373682d6564323535313900000020e32aaa7915ceb9b449d1ba50ea2a28bb1a6e01f90bda245a2d1d87697d18a265")
        assertEquals("SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU", SshFingerprint.of(blob))
    }

    @Test
    fun aMissingRexIsRecognisedFromTheRemoteShell() {
        val notes = mutableListOf<String>()
        val stderr = SshTunnel.Stderr { notes += it }
        stderr.write("rex was not found on this machine\n".toByteArray())
        assertEquals(listOf("rex was not found on this machine"), notes)

        val quiet = mutableListOf<String>()
        SshTunnel.Stderr { quiet += it }.write("a login shell said hello\n".toByteArray())
        assertTrue(quiet.isEmpty())
    }

    @Test
    fun aHostKeyFailureSaysWhichFailureItIs() {
        val unknown = SshHostKeyException("SHA256:new", changed = false).toProblem()
        assertEquals(Trouble.SshHostKeyUnknown, unknown.trouble)
        assertEquals("SHA256:new", unknown.detail)
        assertTrue(SshHostKeyException("SHA256:new", changed = false).isFatalConnectionProblem())

        val changed = SshHostKeyException("SHA256:other", changed = true).toProblem()
        assertEquals(Trouble.SshHostKeyChanged, changed.trouble)

        assertEquals(Trouble.SshAuth, SshAuthException("The password was refused").toProblem().trouble)
        assertEquals(Trouble.SshCommand, SshCommandException("rex was not found").toProblem().trouble)
    }

    /**
     * A real host, when one is named: `REX_SSH_HOST`, `REX_SSH_USER`, `REX_SSH_KEY` (a private key
     * file), and optionally `REX_SSH_PORT` and `REX_SSH_HOST_KEY` (a `SHA256:` fingerprint). Without
     * the key, the first connection must ask, and the test pins what it saw and connects again.
     */
    @Test
    fun reachesRexOverARealHost() {
        val host = System.getenv("REX_SSH_HOST")
        val user = System.getenv("REX_SSH_USER")
        val keyFile = System.getenv("REX_SSH_KEY")?.let(::File)
        val password = System.getenv("REX_SSH_PASSWORD")?.takeIf { it.isNotEmpty() }
        assumeTrue(
            "set REX_SSH_HOST, REX_SSH_USER and REX_SSH_KEY or REX_SSH_PASSWORD to run this against a real SSH host",
            !host.isNullOrBlank() && !user.isNullOrBlank() && (keyFile?.isFile == true || password != null),
        )
        val port = System.getenv("REX_SSH_PORT")?.toIntOrNull() ?: 22
        val settings = SshSettings(
            host = host!!,
            port = port,
            user = user!!,
            credentials = SshCredentials(
                privateKey = keyFile?.takeIf { it.isFile }?.readText(),
                passphrase = System.getenv("REX_SSH_KEY_PASSPHRASE")?.takeIf { it.isNotEmpty() },
                password = password,
            ),
            hostKey = System.getenv("REX_SSH_HOST_KEY")?.takeIf { it.isNotBlank() },
            socksPort = System.getenv("REX_SSH_SOCKS")?.toIntOrNull() ?: 0,
        )

        val pinned = settings.hostKey ?: run {
            val first = SshTunnel(settings)
            val seen = try {
                first.http().newCall(Request.Builder().url("http://127.0.0.1:7297/llms.txt").build()).execute().use { it.code }
                null
            } catch (e: Exception) {
                e
            } finally {
                first.close()
            }
            assertTrue("expected the host key to need confirmation, got $seen", seen is SshHostKeyException)
            (seen as SshHostKeyException).fingerprint
        }

        // A wrong pin must reach the screen as a host-key problem, through OkHttp and RexClient.
        val wrongPin = SshTunnel(settings.copy(hostKey = "SHA256:not-the-key"))
        val wrongClient = RexClient.forSsh(wrongPin)
        try {
            val failure = runCatching {
                runBlocking { wrongClient.connect(Endpoint(host, port, plaintext = true, sshUser = user)) }
            }.exceptionOrNull()
            assertTrue("expected a host-key failure, got $failure", failure != null)
            assertEquals(Trouble.SshHostKeyChanged, failure!!.toProblem().trouble)
            assertTrue(failure.isFatalConnectionProblem())
        } finally {
            wrongClient.close()
            wrongPin.close()
        }

        val tunnel = SshTunnel(settings.copy(hostKey = pinned))
        val client = RexClient.forSsh(tunnel)
        try {
            runBlocking {
                withTimeout(TimeUnit.SECONDS.toMillis(30)) {
                    val welcome = client.connect(Endpoint(host, port, plaintext = true, sshUser = user))
                    assertTrue(welcome.clientId.isNotBlank())
                    val boot = client.call("session.bootstrap", JSONObject().put("event_scope", "all"))
                    assertTrue(boot.has("attach_all"))
                }
            }
        } finally {
            client.close()
            tunnel.close()
        }
    }

    private fun sshString(bytes: ByteArray): ByteArray =
        byteArrayOf(
            (bytes.size ushr 24).toByte(),
            (bytes.size ushr 16).toByte(),
            (bytes.size ushr 8).toByte(),
            bytes.size.toByte(),
        ) + bytes
}

private fun hex(text: String): ByteArray =
    text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
