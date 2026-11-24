package me.leaver.rexpocket.netbird.api

import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The interface between RexPocket and the add-on stays readable by both sides' older builds: each
 * call keeps its transaction code (an old app or add-on knows the calls by them), and an account's
 * directory name is the same on both sides.
 */
class InterfaceTest {
    @Test
    fun revisionOnesCallsKeepTheirCodesAndLaterOnesComeAfter() {
        val codes = listOf(
            INetbirdNode.Stub.TRANSACTION_version,
            INetbirdNode.Stub.TRANSACTION_start,
            INetbirdNode.Stub.TRANSACTION_stop,
            INetbirdNode.Stub.TRANSACTION_state,
            INetbirdNode.Stub.TRANSACTION_ping,
            INetbirdNode.Stub.TRANSACTION_dialError,
            INetbirdNode.Stub.TRANSACTION_dial,
            INetbirdNode.Stub.TRANSACTION_setListener,
            INetbirdNode.Stub.TRANSACTION_keepRunning,
            // Revision 2.
            INetbirdNode.Stub.TRANSACTION_revision,
            INetbirdNode.Stub.TRANSACTION_startProfile,
            INetbirdNode.Stub.TRANSACTION_forgetProfile,
        )
        assertEquals((0 until codes.size).map { IBinder.FIRST_CALL_TRANSACTION + it }, codes)
    }

    @Test
    fun theBaseVersionStaysWhileCallsAreOnlyAdded() {
        assertEquals(1, NetbirdAddon.VERSION)
        assertEquals(2, NetbirdAddon.REVISION)
        assertEquals(NetbirdAddon.REVISION, NetbirdAddon.REVISION_ACCOUNTS)
    }

    @Test
    fun anAccountsDirectoryIsItsCleanedName() {
        assertEquals("netbird-addon-0f3a", NetbirdAddon.profileDirectory("netbird-addon-0F3A"))
        assertEquals("etc-passwd", NetbirdAddon.profileDirectory("../etc/passwd"))
        assertEquals(63, NetbirdAddon.profileDirectory("a".repeat(100))!!.length)
        assertNull(NetbirdAddon.profileDirectory(""))
        assertNull(NetbirdAddon.profileDirectory("../.."))
    }
}
