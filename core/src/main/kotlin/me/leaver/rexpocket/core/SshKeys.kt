package me.leaver.rexpocket.core

import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64

/**
 * A key the phone made for one host: [privateKey] is the OpenSSH private key the app keeps (sealed,
 * like a pasted one), [publicKey] the `authorized_keys` line to install on the host. Its [toString]
 * names the public half only, so a log line or a test failure never prints the private one.
 */
class GeneratedKey(val privateKey: String, val publicKey: String, val comment: String) {
    /** `SHA256:...` of the public key, as `ssh-keygen -lf` prints it. */
    val fingerprint: String get() = SshFingerprint.of(Base64.getDecoder().decode(publicKey.split(' ')[1]))

    override fun toString(): String = "GeneratedKey($fingerprint $comment)"
}

/** Ed25519 keys for SSH, made on the phone: each host gets its own, and none is ever reused. */
object SshKeys {
    private const val TYPE = "ssh-ed25519"
    private val random = SecureRandom()

    /** A fresh Ed25519 key with [comment] (one line: no control characters). */
    fun generate(comment: String): GeneratedKey {
        val clean = comment.filterNot { it.isISOControl() }.trim()
        val seed = ByteArray(32).also(random::nextBytes)
        val key = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)))
        val pub = key.abyte
        val blob = publicBlob(pub)
        val line = "$TYPE " + Base64.getEncoder().encodeToString(blob) + if (clean.isEmpty()) "" else " $clean"
        val private = openSshPrivateKey(seed, pub, blob, clean)
        seed.fill(0)
        return GeneratedKey(private, line, clean)
    }

    /**
     * The comment of an unencrypted OpenSSH private key, which Export hosts names a host's key by; null
     * for any other key (an encrypted one keeps its comment inside the encryption).
     */
    fun commentOf(privateKey: String): String? = runCatching {
        val body = privateKey.trim().lines().filter { !it.startsWith("-----") }.joinToString("")
        val buf = ByteBuffer.wrap(Base64.getMimeDecoder().decode(body))
        val magic = ByteArray(MAGIC.size).also { buf.get(it) }
        if (!magic.contentEquals(MAGIC)) return null
        if (string(buf).decodeToString() != "none") return null
        string(buf)
        string(buf)
        if (buf.int != 1) return null
        string(buf)
        val private = ByteBuffer.wrap(string(buf))
        if (private.int != private.int) return null
        if (string(private).decodeToString() != TYPE) return null
        string(private)
        string(private)
        string(private).decodeToString().takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun string(buf: ByteBuffer): ByteArray = ByteArray(buf.int).also { buf.get(it) }

    private fun publicBlob(pub: ByteArray): ByteArray = bytes {
        writeString(TYPE.toByteArray())
        writeString(pub)
    }

    /** `openssh-key-v1`, unencrypted: the format `ssh-keygen -t ed25519` writes and JSch reads. */
    private fun openSshPrivateKey(seed: ByteArray, pub: ByteArray, blob: ByteArray, comment: String): String {
        val check = random.nextInt()
        val private = bytes {
            writeInt(check)
            writeInt(check)
            writeString(TYPE.toByteArray())
            writeString(pub)
            writeString(seed + pub)
            writeString(comment.toByteArray())
            var pad = 1
            while (size() % 8 != 0) write(pad++)
        }
        val all = bytes {
            write(MAGIC)
            writeString("none".toByteArray())
            writeString("none".toByteArray())
            writeString(ByteArray(0))
            writeInt(1)
            writeString(blob)
            writeString(private)
        }
        private.fill(0)
        val b64 = Base64.getEncoder().encodeToString(all)
        all.fill(0)
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n" + b64.chunked(70).joinToString("\n") + "\n-----END OPENSSH PRIVATE KEY-----\n"
    }

    private val MAGIC = "openssh-key-v1\u0000".toByteArray()

    private fun DataOutputStream.writeString(b: ByteArray) {
        writeInt(b.size)
        write(b)
    }

    private fun bytes(block: DataOutputStream.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use(block)
        return out.toByteArray()
    }
}

/**
 * The public keys of the hosts an import added, for the person to install: as `authorized_keys` text
 * and as `rexpocket-public-keys/1` JSON a provisioning tool can install itself. Public keys only.
 */
object PublicKeyList {
    const val FORMAT = "rexpocket-public-keys/1"

    data class Entry(val name: String, val publicKey: String)

    /** A `# name` line over each key's line, which `authorized_keys` reads as a comment. */
    fun authorizedKeys(entries: List<Entry>): String =
        entries.joinToString("\n", postfix = "\n") { "# ${it.name.filterNot { c -> c.isISOControl() }}\n${it.publicKey}" }

    fun json(entries: List<Entry>): String {
        val hosts = org.json.JSONArray()
        entries.forEach { hosts.put(org.json.JSONObject().put("name", it.name).put("publicKey", it.publicKey)) }
        return org.json.JSONObject().put("format", FORMAT).put("hosts", hosts).toString(2)
    }
}
