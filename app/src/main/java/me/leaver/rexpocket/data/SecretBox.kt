package me.leaver.rexpocket.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The SSH passwords and private keys, sealed with a key that never leaves Android's keystore.
 *
 * The settings file is plain SharedPreferences and is included in Android's backup; a private key
 * must not be readable from either. The keystore key is not backed up, so a restored file holds
 * ciphertext nothing on the new phone can open, which the app treats as "no credentials yet".
 */
class SecretBox(private val keys: () -> SecretKey? = { androidKey() }) {
    /** The key once the keystore gave it; a keystore that failed is asked again next time. */
    @Volatile private var key: SecretKey? = null
    @Volatile private var failedOnce = false

    private fun key(): SecretKey? = key ?: synchronized(this) {
        key ?: keys().also { key = it }
    }

    /** [plain] sealed, or "" when the keystore cannot seal it: then nothing is saved. */
    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val secret = key() ?: return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secret)
            val iv = cipher.iv
            val bytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.getEncoder().encodeToString(iv + bytes)
        }.getOrElse {
            Log.e(TAG, "Could not encrypt an SSH credential", it)
            ""
        }
    }

    /**
     * What [sealed] holds, "" for nothing saved, or null when it cannot be opened: the keystore is
     * not available now, or the text is another phone's (a restored file) or its key was reset.
     * A null is not "no secret": the store keeps the sealed text as it is.
     */
    fun open(sealed: String): String? {
        if (sealed.isEmpty()) return ""
        if (!sealed.startsWith(PREFIX)) return null
        val secret = key() ?: return null
        return runCatching {
            val raw = Base64.getDecoder().decode(sealed.removePrefix(PREFIX))
            require(raw.size > IV_BYTES) { "too short" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        }.getOrElse {
            // A file restored from another phone, or a key the system reset: ask for the credential again.
            if (!failedOnce) Log.w(TAG, "Could not decrypt a saved SSH credential", it)
            failedOnce = true
            null
        }
    }

    private companion object {
        fun androidKey(): SecretKey? = runCatching {
            val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generate()
        }.getOrElse {
            Log.e(TAG, "Android keystore is not available; SSH credentials will not be saved", it)
            null
        }

        fun generate(): SecretKey {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }

        const val TAG = "SecretBox"
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "rexpocket-ssh"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "v1:"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
