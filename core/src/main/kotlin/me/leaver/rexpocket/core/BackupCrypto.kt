package me.leaver.rexpocket.core

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The SSH credentials a settings backup carries when the person asks for them, sealed with a
 * passphrase they choose. Standard pieces only, the ones every Android since 8.0 and every JVM has:
 *
 * - **Key:** PBKDF2 (RFC 8018) with HMAC-SHA-256, [ITERATIONS] rounds, a fresh random salt of
 *   [SALT_BYTES], a 256-bit key. The passphrase is NFC-normalised and taken as UTF-8, so the same
 *   words typed on another keyboard give the same key. PBKDF2 is written out here on `HmacSHA256`
 *   rather than taken from `SecretKeyFactory`, whose Android providers have differed in how they
 *   turn a passphrase's characters into bytes.
 * - **Cipher:** AES-256-GCM with a fresh random 96-bit nonce and a 128-bit tag; the associated data
 *   is the format's name, so the box cannot be lifted into another kind of file.
 *
 * A wrong passphrase and a changed byte fail the same way: the tag does not match. See
 * docs/design/settings-backup.md.
 */
object BackupCrypto {
    const val KDF = "PBKDF2-HMAC-SHA256"
    const val CIPHER = "AES-256-GCM"
    const val ITERATIONS = 600_000

    /** A file may name other rounds, within these: fewer is too weak, more is a phone stuck for minutes. */
    const val MIN_ITERATIONS = 100_000
    const val MAX_ITERATIONS = 5_000_000
    const val SALT_BYTES = 16
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128
    const val KEY_BYTES = 32
    const val MIN_PASSPHRASE = 8

    /** At most this much sealed: a few hundred keys. */
    const val MAX_SEALED_BYTES = 512 * 1024

    private val random = SecureRandom()

    fun seal(plain: ByteArray, passphrase: String, aad: ByteArray, iterations: Int = ITERATIONS): SealedBox {
        require(iterations in 1..MAX_ITERATIONS)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val key = derive(passphrase, salt, iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            return SealedBox(iterations, salt, nonce, cipher.doFinal(plain))
        } finally {
            key.fill(0)
        }
    }

    /** The plaintext, or [WrongPassphraseException] for a wrong passphrase or a box that was changed. */
    fun open(box: SealedBox, passphrase: String, aad: ByteArray): ByteArray {
        val key = derive(passphrase, box.salt, box.iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, box.nonce))
            cipher.updateAAD(aad)
            return cipher.doFinal(box.data)
        } catch (e: AEADBadTagException) {
            throw WrongPassphraseException()
        } catch (e: GeneralSecurityException) {
            throw WrongPassphraseException()
        } finally {
            key.fill(0)
        }
    }

    /** PBKDF2-HMAC-SHA256 (RFC 8018, 5.2) of [passphrase] for a [KEY_BYTES] key: one block. */
    fun derive(passphrase: String, salt: ByteArray, iterations: Int, length: Int = KEY_BYTES): ByteArray {
        val password = Normalizer.normalize(passphrase, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        // SecretKeySpec refuses an empty key; HMAC pads a short key with zeros to its block, so a
        // block of zeros is the same key.
        mac.init(SecretKeySpec(if (password.isEmpty()) ByteArray(64) else password, "HmacSHA256"))
        password.fill(0)
        val hLen = mac.macLength
        val blocks = (length + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        val u = ByteArray(hLen)
        val t = ByteArray(hLen)
        for (block in 1..blocks) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            mac.doFinal(u, 0)
            u.copyInto(t)
            for (i in 2..iterations) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (j in 0 until hLen) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            t.copyInto(out, (block - 1) * hLen)
        }
        u.fill(0)
        t.fill(0)
        return out.copyOf(length).also { out.fill(0) }
    }

    internal fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    internal fun unb64(text: String): ByteArray? = runCatching { Base64.getDecoder().decode(text) }.getOrNull()
}

/** What [BackupCrypto.seal] made: the rounds and salt of the key, the nonce, and the ciphertext with its tag. */
class SealedBox(val iterations: Int, val salt: ByteArray, val nonce: ByteArray, val data: ByteArray) {
    override fun toString(): String = "SealedBox($iterations rounds, ${data.size} bytes)"
}

/** The passphrase does not open the box, or the box was changed: GCM cannot tell the two apart. */
class WrongPassphraseException : Exception("The passphrase does not open these keys")
