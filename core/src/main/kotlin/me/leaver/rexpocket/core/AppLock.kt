package me.leaver.rexpocket.core

// Settings › Security: an optional lock (the phone's fingerprint, face or screen lock) and an
// optional screenshot block. Both are off until the person turns them on. See
// docs/design/app-lock.md.

/**
 * The person's security choices. [lockAfterSeconds] is how long the app may be away before it asks
 * again; 0 asks every time it comes back. A minute at first, so a trip to the file picker or the
 * share sheet does not lock it.
 */
data class SecurityPrefs(
    val lock: Boolean = false,
    val lockAfterSeconds: Int = 60,
    val blockScreenshots: Boolean = false,
) {
    companion object {
        /** The waits Settings › Security offers, in seconds: at once, 1, 5 and 15 minutes, an hour. */
        val LOCK_AFTER = listOf(0, 60, 300, 900, 3600)

        /** A stored or imported wait that is not one of [LOCK_AFTER] is the next shorter one, never longer. */
        fun cleanLockAfter(seconds: Int): Int = LOCK_AFTER.lastOrNull { it <= seconds } ?: 0
    }
}

/**
 * Whether the app is locked, from what it is told: it starts locked when the lock is on, locks
 * again when it comes back after [SecurityPrefs.lockAfterSeconds] away, and unlocks only when the
 * phone said the person is who they are. It knows no clock: each call gives the time.
 */
class AppLockState(prefs: SecurityPrefs) {
    var prefs: SecurityPrefs = prefs
        private set

    /** A process that starts with the lock on starts locked. */
    var locked: Boolean = prefs.lock
        private set

    private var awayAt: Long? = null

    /**
     * The phone's own prompt is up. Its PIN screen can be an activity of its own, which takes the
     * app off screen: that is not the person leaving, and does not lock it again.
     */
    var asking: Boolean = false
        private set

    /** The app went to the background (or the screen went off) at [now]. */
    fun away(now: Long) {
        if (asking) return
        if (awayAt == null) awayAt = now
    }

    /** The app is on screen again at [now]: locked when the lock is on and it was away long enough. */
    fun back(now: Long) {
        val since = awayAt
        awayAt = null
        if (asking || !prefs.lock || since == null) return
        if (now - since >= prefs.lockAfterSeconds * 1000L) locked = true
    }

    /** The phone's prompt went up. */
    fun ask() {
        asking = true
    }

    /** The phone's prompt went away without the person confirmed: still locked. */
    fun notConfirmed() {
        asking = false
    }

    /** The phone confirmed the person. */
    fun unlocked() {
        asking = false
        locked = false
        awayAt = null
    }

    /**
     * New choices. Turning the lock on does not lock the app the person is using (they have just
     * proved they can, or they would not have got here); turning it off unlocks it.
     */
    fun update(next: SecurityPrefs) {
        prefs = next
        if (!next.lock) {
            locked = false
            asking = false
        }
    }
}
