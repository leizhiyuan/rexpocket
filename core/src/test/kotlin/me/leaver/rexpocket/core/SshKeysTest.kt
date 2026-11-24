package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The host dialog's Generate key and Show public key: a fresh key, and the public half of any key. */
class SshKeysTest {
    @Test
    fun aGeneratedKeysPublicLineIsReadBackFromItsPrivateKey() {
        val key = SshKeys.generate("rexpocket:mac")
        assertTrue(key.publicKey.startsWith("ssh-ed25519 "))
        assertTrue(key.publicKey.endsWith(" rexpocket:mac"))
        assertEquals(key.publicKey, SshKeys.publicKeyOf(key.privateKey))
        assertFalse("the public line never carries the private key", key.publicKey.contains("PRIVATE"))
    }

    @Test
    fun eachKeyIsNew() {
        assertNotEquals(SshKeys.generate("a").publicKey.split(' ')[1], SshKeys.generate("a").publicKey.split(' ')[1])
    }

    @Test
    fun aKeyWithoutACommentTakesTheOneGiven() {
        val key = SshKeys.generate("")
        assertEquals(key.publicKey + " rexpocket:studio", SshKeys.publicKeyOf(key.privateKey, comment = "rexpocket:studio"))
    }

    @Test
    fun anRsaKeyFromElsewhereIsReadToo() {
        val pair = com.jcraft.jsch.KeyPair.genKeyPair(com.jcraft.jsch.JSch(), com.jcraft.jsch.KeyPair.RSA, 2048)
        val out = java.io.ByteArrayOutputStream()
        pair.writePrivateKey(out)
        val line = SshKeys.publicKeyOf(out.toString())!!
        assertTrue(line, line.startsWith("ssh-rsa "))
        // The same as the key's own public line, comment aside.
        val own = java.io.ByteArrayOutputStream().also { pair.writePublicKey(it, "") }.toString().trim()
        assertEquals(own.split(' ')[1], line.split(' ')[1])
    }

    @Test
    fun somethingThatIsNotAKeyHasNoPublicHalf() {
        assertNull(SshKeys.publicKeyOf("hello"))
        assertNull(SshKeys.publicKeyOf(""))
    }
}
