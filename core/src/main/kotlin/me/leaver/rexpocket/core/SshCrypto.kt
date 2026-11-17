package me.leaver.rexpocket.core

import com.jcraft.jsch.KeyPairGenEdDSA
import com.jcraft.jsch.SignatureEdDSA
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Ed25519 for JSch on a JVM without its Java 15 classes, which is every Android phone: JSch's own
 * implementation and X25519 come from `java.security` additions Android does not carry. The
 * signature is RFC 8032 over the pure-Java eddsa library that ships with the app.
 */
class Ed25519Signature : SignatureEdDSA {
    private val params: EdDSAParameterSpec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
    private val message = ByteArrayOutputStream()
    private var engine: EdDSAEngine? = null

    override fun init() {
        engine = EdDSAEngine(MessageDigest.getInstance(params.hashAlgorithm)).apply {
            setParameter(EdDSAEngine.ONE_SHOT_MODE)
        }
        message.reset()
    }

    override fun setPubKey(y: ByteArray) {
        val engine = engine ?: fresh()
        message.reset()
        engine.initVerify(EdDSAPublicKey(EdDSAPublicKeySpec(y, params)))
    }

    override fun setPrvKey(bytes: ByteArray) {
        val engine = engine ?: fresh()
        message.reset()
        engine.initSign(EdDSAPrivateKey(EdDSAPrivateKeySpec(bytes, params)))
    }

    override fun update(foo: ByteArray) {
        message.write(foo)
    }

    override fun sign(): ByteArray {
        val engine = engine ?: fresh()
        val data = message.toByteArray()
        message.reset()
        return engine.signOneShot(data)
    }

    override fun verify(sig: ByteArray): Boolean {
        val engine = engine ?: fresh()
        // SSH wraps a signature in two strings: its algorithm's name, then the raw signature.
        // JSch also calls this with a bare 64-byte signature, so only a well-formed name is unwrapped.
        var raw = sig
        val nameLength = if (raw.size >= 4) {
            ((raw[0].toInt() and 0xff) shl 24) or ((raw[1].toInt() and 0xff) shl 16) or
                ((raw[2].toInt() and 0xff) shl 8) or (raw[3].toInt() and 0xff)
        } else {
            -1
        }
        if (nameLength in 1..raw.size - 4) {
            val name = String(raw, 4, nameLength, Charsets.UTF_8)
            if (name == "ssh-ed25519") {
                val lengthAt = 4 + nameLength
                if (lengthAt + 4 > raw.size) return false
                val length = ((raw[lengthAt].toInt() and 0xff) shl 24) or
                    ((raw[lengthAt + 1].toInt() and 0xff) shl 16) or
                    ((raw[lengthAt + 2].toInt() and 0xff) shl 8) or
                    (raw[lengthAt + 3].toInt() and 0xff)
                val start = lengthAt + 4
                if (length < 0 || start + length > raw.size) return false
                raw = raw.copyOfRange(start, start + length)
            }
        }
        val data = message.toByteArray()
        message.reset()
        return engine.verifyOneShot(data, raw)
    }

    private fun fresh(): EdDSAEngine {
        init()
        return engine!!
    }
}

/** The key half of [Ed25519Signature]: JSch derives a public key from an OpenSSH private key here. */
class Ed25519KeyPairGen : KeyPairGenEdDSA {
    private val params: EdDSAParameterSpec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
    private var publicKey: ByteArray? = null
    private var privateKey: ByteArray? = null

    override fun init(name: String, keylen: Int) {
        require(name == "Ed25519") { "Unsupported curve $name" }
        val seed = ByteArray(32)
        SecureRandom().nextBytes(seed)
        derive(seed)
    }

    override fun init(name: String, prv: ByteArray) {
        require(name == "Ed25519") { "Unsupported curve $name" }
        derive(prv)
    }

    override fun getPub(): ByteArray = publicKey ?: throw IllegalStateException("No Ed25519 key")

    override fun getPrv(): ByteArray = privateKey ?: throw IllegalStateException("No Ed25519 key")

    private fun derive(seed: ByteArray) {
        val key = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, params))
        privateKey = key.seed
        publicKey = key.abyte
    }
}
