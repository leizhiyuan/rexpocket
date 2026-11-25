package me.leaver.rexpocket.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.core.HostAddressParser
import me.leaver.rexpocket.ui.asSavedHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.concurrent.thread

/**
 * The host list and its SSH secrets as the store keeps them: a keystore that fails for a moment
 * loses no saved secret and says it could not open one, listing and saving hosts open none (a
 * connection does, when it asks), and two writers at once lose no host. The keystore here is a
 * plain AES key, so the same runs on the JVM (Robolectric has no Android keystore) and the emulator.
 */
@RunWith(AndroidJUnit4::class)
class HostStoreTest {
    private lateinit var app: Application

    /** This phone's key, as the keystore keeps it between processes. */
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val agents = SavedHost(
        id = "agents-id", label = "agents", host = "agents.example.com", port = 22, plaintext = true,
        sshUser = "me", sshPassword = "pw",
    )
    private val desk = SavedHost(
        id = "desk-id", label = "desk", host = "desk.example.com", port = 22, plaintext = true,
        sshUser = "me", sshPrivateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n", sshPassphrase = "words",
    )

    @Before
    fun clean() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("rexpocket", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** A store as a new process opens it, on a keystore that works. */
    private fun working() = HostStore(app, SecretBox { key })

    /** A keystore that cannot give its key the first [times] times it is asked, and then can. */
    private fun failing(times: Int, asked: AtomicInteger = AtomicInteger()) =
        SecretBox { if (asked.incrementAndGet() <= times) null else key }

    private fun HostStore.opened(id: String): SavedHost = withSecrets(find(id)!!)

    @Test
    fun aKeystoreThatFailsAtStartLosesNoSavedSecret() {
        working().apply { upsert(agents); upsert(desk) }

        // The next process starts on a keystore that is busy, and a host is saved (a rename).
        val busy = HostStore(app, failing(times = 1))
        busy.list()
        busy.upsert(busy.find("agents-id")!!.copy(label = "agents 2"))
        busy.upsert(SavedHost(id = "new-id", label = "new", host = "new.example.com", port = 7297, plaintext = true))

        val later = working()
        assertEquals("agents 2", later.find("agents-id")!!.label)
        assertEquals("pw", later.opened("agents-id").sshPassword)
        assertEquals(desk.sshPrivateKey, later.opened("desk-id").sshPrivateKey)
        assertEquals("words", later.opened("desk-id").sshPassphrase)
    }

    @Test
    fun aSecretThatCannotBeOpenedIsSaidSoAndTheNextTryOpensIt() {
        working().upsert(agents)
        val store = HostStore(app, failing(times = 1))

        val first = store.withSecrets(store.find("agents-id")!!)
        assertTrue(first.secretsUnreadable)
        assertEquals("", first.sshPassword)

        // The keystore is not given up on for the rest of the process: the next connection opens it.
        val second = store.withSecrets(store.find("agents-id")!!)
        assertFalse(second.secretsUnreadable)
        assertEquals("pw", second.sshPassword)
    }

    @Test
    fun theHostDialogSavingAHostWhoseSecretCouldNotBeOpenedKeepsIt() {
        working().upsert(agents)
        val store = HostStore(app, failing(times = 1))
        val shown = store.withSecrets(store.find("agents-id")!!)
        assertTrue(shown.secretsUnreadable)

        // The dialog shows an empty password and the person renames the host.
        val edited = asSavedHost(
            initial = shown,
            label = "renamed",
            parsed = HostAddressParser.parse("ssh://agents.example.com:22")!!,
            viaTailscale = false,
            sshUser = "me",
            sshPassword = shown.sshPassword,
        )
        store.upsert(edited)

        assertEquals("renamed", working().find("agents-id")!!.label)
        assertEquals("pw", working().opened("agents-id").sshPassword)
    }

    @Test
    fun listingFindingAndSavingHostsOpenNoSecret() {
        working().apply { upsert(agents); upsert(desk) }
        val asked = AtomicInteger()
        val store = HostStore(app, failing(times = Int.MAX_VALUE, asked))

        store.list()
        store.find("agents-id")
        store.upsert(store.find("desk-id")!!.copy(sshHostKey = "SHA256:abc"))
        store.saveAll(store.list().reversed())
        store.delete("nothing-id")
        assertEquals(0, asked.get())

        // A connection asks for them.
        store.withSecrets(store.find("agents-id")!!)
        assertTrue(asked.get() > 0)

        val later = working()
        assertEquals("SHA256:abc", later.find("desk-id")!!.sshHostKey)
        assertEquals(desk.sshPrivateKey, later.opened("desk-id").sshPrivateKey)
        assertEquals("pw", later.opened("agents-id").sshPassword)
    }

    @Test
    fun aSecretChangedOrClearedIsWrittenAsIt() {
        working().apply { upsert(agents); upsert(desk) }
        val store = working()

        store.upsert(store.opened("agents-id").copy(sshPassword = "new"))
        // From a key to a password: the key and its passphrase go.
        store.upsert(store.opened("desk-id").copy(sshPrivateKey = "", sshPassphrase = "", sshPassword = "pw2"))

        val later = working()
        assertEquals("new", later.opened("agents-id").sshPassword)
        val deskNow = later.opened("desk-id")
        assertEquals("pw2", deskNow.sshPassword)
        assertEquals("", deskNow.sshPrivateKey)
        assertEquals("", deskNow.sshPassphrase)
    }

    @Test
    fun twoWritersAtOnceLoseNoHost() {
        val store = working()
        val other = working()
        val go = CountDownLatch(1)
        val writers = listOf(store, other).mapIndexed { w, s ->
            thread {
                go.await()
                repeat(40) { i ->
                    s.upsert(SavedHost(id = "h$w-$i", label = "h$w-$i", host = "h$w-$i.example.com", port = 7297, plaintext = true))
                }
            }
        }
        go.countDown()
        writers.forEach { it.join() }
        assertEquals(80, working().list().size)
    }
}
