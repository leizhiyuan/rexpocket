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
class SecretBox {
    private val key: SecretKey? by lazy { loadKey() }

    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val secret = key ?: return ""
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

    fun open(sealed: String): String {
        if (sealed.isEmpty()) return ""
        if (!sealed.startsWith(PREFIX)) return ""
        val secret = key ?: return ""
        return runCatching {
            val raw = Base64.getDecoder().decode(sealed.removePrefix(PREFIX))
            if (raw.size <= IV_BYTES) return ""
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        }.getOrElse {
            // A file restored from another phone, or a key the system reset: ask for the credential again.
            Log.w(TAG, "Could not decrypt a saved SSH credential", it)
            ""
        }
    }

    private fun loadKey(): SecretKey? = runCatching {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generate()
    }.getOrElse {
        Log.e(TAG, "Android keystore is not available; SSH credentials will not be saved", it)
        null
    }

    private fun generate(): SecretKey {
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

    private companion object {
        const val TAG = "SecretBox"
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "rexpocket-ssh"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "v1:"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
