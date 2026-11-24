package me.leaver.rexpocket.net

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import me.leaver.rexpocket.MainActivity
import me.leaver.rexpocket.core.TailnetBackend
import me.leaver.rexpocket.core.parseTailnetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs on a device or emulator with the native library. Needs the netbird build of the app
 * (-Prexpocket.netbird=true): the stub build reports Netbird as unavailable.
 *
 * These checks stay offline on purpose: the node is never asked to reach a management server.
 */
@RunWith(AndroidJUnit4::class)
class TailnetNodeInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before fun freshNodeState() {
        // A joined node would keep its registration; each run starts from nothing.
        File(context.filesDir, "netbird").deleteRecursively()
    }

    @Test fun theAppLaunchesAndSettlesOnHome() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test fun theNetbirdLibraryLoadsOnThisDevice() {
        assertTrue("this build has no Netbird node", NetbirdBox.available())
        assertTrue(TailnetBackend.entries.contains(TailnetBackend.Netbird))
    }

    @Test fun aNetbirdStartWithoutAKeyFailsWithTheReason() {
        try {
            NetbirdBox(context).start(NetbirdConfig(setupKey = "", hostname = "ci-smoke", managementUrl = ""))
            fail("a node that never joined must not start without a setup key")
        } catch (e: TailnetException) {
            assertTrue("message was: ${e.message}", e.message.orEmpty().contains("setup key"))
        }
    }

    @Test fun aNodeStateIsReadableJsonWithNoPeers() {
        val snapshot = parseTailnetState("""{"backendState":"Starting","peers":[]}""")
        assertEquals("Starting", snapshot.backendState)
        assertEquals(0, snapshot.peerCount)
    }
}
