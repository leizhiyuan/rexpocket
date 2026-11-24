package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the app lock locks, and what does not lock it. See docs/design/app-lock.md. */
class AppLockTest {
    private val on = SecurityPrefs(lock = true, lockAfterSeconds = 60)

    @Test
    fun offNeverLocks() {
        val s = AppLockState(SecurityPrefs())
        assertFalse(s.locked)
        s.away(0)
        s.back(10_000_000)
        assertFalse(s.locked)
    }

    @Test
    fun aStartWithTheLockOnIsLockedAndOnlyThePhoneUnlocksIt() {
        val s = AppLockState(on)
        assertTrue(s.locked)
        s.back(1_000)
        assertTrue(s.locked)
        s.unlocked()
        assertFalse(s.locked)
    }

    @Test
    fun itLocksAfterTheWaitAndNotBefore() {
        val s = AppLockState(on).apply { unlocked() }
        s.away(0)
        s.back(59_999)
        assertFalse(s.locked)
        s.away(100_000)
        s.back(160_000)
        assertTrue(s.locked)

        val atOnce = AppLockState(on.copy(lockAfterSeconds = 0)).apply { unlocked() }
        atOnce.away(0)
        atOnce.back(1)
        assertTrue(atOnce.locked)
    }

    @Test
    fun thePhonesOwnPromptIsNotLeaving() {
        val s = AppLockState(on.copy(lockAfterSeconds = 0))
        s.ask()
        s.away(0)
        s.back(5_000)
        assertTrue(s.locked)
        s.unlocked()
        assertFalse(s.locked)
        // A prompt that came down without a yes: still locked, and leaving counts again.
        val t = AppLockState(on.copy(lockAfterSeconds = 0))
        t.ask()
        t.notConfirmed()
        t.unlocked()
        t.away(0)
        t.back(1)
        assertTrue(t.locked)
    }

    @Test
    fun turningItOnDoesNotLockAndOffUnlocks() {
        val s = AppLockState(SecurityPrefs())
        s.update(on)
        assertFalse(s.locked)
        s.away(0)
        s.back(120_000)
        assertTrue(s.locked)
        s.update(SecurityPrefs())
        assertFalse(s.locked)
    }

    @Test
    fun aWaitThatIsNotOneOfTheChoicesIsTheNextShorter() {
        assertEquals(0, SecurityPrefs.cleanLockAfter(-5))
        assertEquals(60, SecurityPrefs.cleanLockAfter(299))
        assertEquals(3600, SecurityPrefs.cleanLockAfter(1_000_000))
    }
}
