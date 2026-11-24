package me.leaver.rexpocket.core

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// Which panes need the person: the news an alert is made of. See docs/design/notifications.md.

/** What changed since the last look: panes that started waiting, and panes that stopped. */
data class AttentionChanges(val raised: List<String>, val settled: List<String>)

/**
 * Remembers which report each pane's alert is about, so one report is news once. A pane has news
 * while it is blocked on the person, has failed, or is done - "the result is ready to look at and
 * the user has not seen it yet" (Program Status Protocol, States); running and idle never do. The
 * title is left out of what makes a report new, because a program animates its title while it waits.
 */
class AttentionLedger {
    private val stamps = HashMap<String, String>()

    fun observe(statuses: Map<String, ProgramRecord>): AttentionChanges {
        val raised = ArrayList<String>()
        val settled = ArrayList<String>()
        for ((block, record) in statuses) {
            val stamp = stampOf(record)
            val before = stamps[block]
            if (stamp == null) {
                if (stamps.remove(block) != null) settled += block
            } else if (stamp != before) {
                stamps[block] = stamp
                raised += block
            }
        }
        // A pane that is gone, or stopped reporting, waits on no one.
        val gone = stamps.keys.filter { it !in statuses }
        for (block in gone) {
            stamps.remove(block)
            settled += block
        }
        return AttentionChanges(raised, settled)
    }

    private fun stampOf(record: ProgramRecord): String? = when (alertKindOf(record.state)) {
        null -> null
        else -> listOf(record.state, record.kind.orEmpty(), record.msg.orEmpty()).joinToString("\u0000")
    }
}

/** What an alert is about; each has its own switch and its own Android channel. */
enum class AlertKind {
    /** `blocked`: approval, an answer or a login. */
    Waiting,

    /** `error`: failed and stopped. */
    Failed,

    /** `done`: a result is ready. */
    Done,

    /** A program's own desktop notification (OSC 9, OSC 777) or, opted in, its bell. */
    Message,
}

/** The alert a program status earns, or null for one that earns none (working, idle, unknown). */
fun alertKindOf(state: String): AlertKind? = when (state) {
    "blocked" -> AlertKind.Waiting
    "error" -> AlertKind.Failed
    "done" -> AlertKind.Done
    else -> null
}

/** The person's choices on the Notifications page. */
data class AlertPrefs(
    val waiting: Boolean = true,
    val failed: Boolean = true,
    /** On: Rex's own meaning of done is a result nobody has seen yet. Its channel is quieter. */
    val done: Boolean = true,
    /** Desktop notifications a program asks for. */
    val messages: Boolean = true,
    /** A bell in a pane not on screen; off, as shells ring it for a mistyped Tab. */
    val bells: Boolean = false,
    /** A bell in the pane on screen vibrates, as the system's own vibration settings allow. */
    val vibrate: Boolean = true,
    /** A tab's progress bar (OSC 9;4) as a progress notification while the app is away; off by default. */
    val progress: Boolean = false,
) {
    fun allows(kind: AlertKind): Boolean = when (kind) {
        AlertKind.Waiting -> waiting
        AlertKind.Failed -> failed
        AlertKind.Done -> done
        AlertKind.Message -> messages
    }
}

/** What to do about a pane's notification. Each pane has at most one, whatever it is about. */
sealed interface AlertMove {
    val block: String

    /**
     * Show (or replace) [block]'s notification as [kind]. [loud] is false when it carries on an
     * alert the person was just given - the same event said twice, or a program saying it again
     * too soon - so it changes what the notification says without a second sound.
     */
    data class Post(override val block: String, val kind: AlertKind, val loud: Boolean, val bell: Boolean = false) : AlertMove

    data class Cancel(override val block: String) : AlertMove
}

/** What a bell does. */
enum class BellMove { Vibrate, Notify, Nothing }

/**
 * Decides every notification: Rex's program status, the desktop notifications programs ask for, and
 * bells. One notification per pane, so a program that reports `blocked` and also sends OSC 9 - as
 * agents do - is one alert: whichever came second within [foldMs] of the first changes it quietly
 * (a status replaces a message, since it knows more and offers Reply; a message after a status is
 * dropped). Messages from one pane alert at most once per [quietMs] (Program Status Protocol,
 * Security: rate-limit what a program causes outside the terminal); one sooner updates the text.
 */
class AlertPlanner(
    private val clock: () -> Long = System::currentTimeMillis,
    private val foldMs: Long = FOLD_MS,
    private val quietMs: Long = QUIET_MS,
) {
    private val ledger = AttentionLedger()

    private class Shown(val kind: AlertKind, val at: Long)

    private val shown = HashMap<String, Shown>()
    private val lastMessage = HashMap<String, Long>()
    private val lastBell = HashMap<String, Long>()

    /**
     * Rex's status of every pane, summed up. Always noted, so a report that came while the person
     * was looking is not news later; only [away] - the app off screen - posts anything.
     */
    @Synchronized
    fun status(statuses: Map<String, ProgramRecord>, away: Boolean, prefs: AlertPrefs): List<AlertMove> {
        val now = clock()
        val changes = ledger.observe(statuses)
        val moves = ArrayList<AlertMove>()
        for (block in changes.settled) {
            if (shown[block]?.kind?.let { it != AlertKind.Message } == true) {
                shown.remove(block)
                moves += AlertMove.Cancel(block)
            }
        }
        for (block in changes.raised) {
            val kind = alertKindOf(statuses.getValue(block).state) ?: continue
            val before = shown[block]
            if (!away || !prefs.allows(kind)) {
                // An alert about the report before this one is out of date.
                if (before != null && before.kind != AlertKind.Message) {
                    shown.remove(block)
                    moves += AlertMove.Cancel(block)
                }
                continue
            }
            val folded = before?.kind == AlertKind.Message && now - before.at < foldMs
            shown[block] = Shown(kind, if (folded) before!!.at else now)
            moves += AlertMove.Post(block, kind, loud = !folded)
        }
        return moves
    }

    /** A program in [block] asked for a desktop notification. */
    @Synchronized
    fun message(block: String, away: Boolean, prefs: AlertPrefs): AlertMove? {
        if (!away || !prefs.messages) return null
        return note(block, bell = false)
    }

    /** A program in [block] rang the bell; [onScreen] when the person is looking at that pane. */
    @Synchronized
    fun bell(block: String, onScreen: Boolean, away: Boolean, prefs: AlertPrefs): BellMove {
        val now = clock()
        if (onScreen && !away) {
            if (!prefs.vibrate) return BellMove.Nothing
            val last = lastBell[block]
            if (last != null && now - last < BELL_GAP_MS) return BellMove.Nothing
            lastBell[block] = now
            return BellMove.Vibrate
        }
        if (!away || !prefs.bells) return BellMove.Nothing
        return if (note(block, bell = true) != null) BellMove.Notify else BellMove.Nothing
    }

    private fun note(block: String, bell: Boolean): AlertMove? {
        val now = clock()
        val before = shown[block]
        // A status alert that just went up says it already.
        if (before != null && before.kind != AlertKind.Message && now - before.at < foldMs) return null
        val last = lastMessage[block]
        val soon = last != null && now - last < quietMs
        // A bell says nothing new the second time; a message may.
        if (soon && bell) return null
        if (!soon) lastMessage[block] = now
        shown[block] = Shown(AlertKind.Message, now)
        return AlertMove.Post(block, AlertKind.Message, loud = !soon, bell = bell)
    }

    /** The person opened [block], or answered from the notification: its alert has done its job. */
    @Synchronized
    fun seen(block: String): AlertMove.Cancel? = shown.remove(block)?.let { AlertMove.Cancel(block) }

    companion object {
        /** A status and a message about one event arrive within this of each other. */
        const val FOLD_MS = 10_000L

        /** The least time between two messages from one pane that both make a sound. */
        const val QUIET_MS = 30_000L

        /** Rex already joins bells in quick succession; this keeps the phone from buzzing on. */
        const val BELL_GAP_MS = 1_000L
    }
}

/**
 * Text a program wrote, made safe to show outside the terminal (Program Status Protocol, Security):
 * control characters become spaces, text direction overrides and other invisible formatting go,
 * runs of space collapse, and it is cut to [max] characters. Null when nothing is left.
 */
fun plainText(text: String?, max: Int = 300): String? {
    if (text == null) return null
    val out = StringBuilder()
    for (ch in text) {
        when {
            Character.isISOControl(ch) -> out.append(' ')
            Character.getType(ch) == Character.FORMAT.toInt() -> Unit
            else -> out.append(ch)
        }
    }
    val flat = out.toString().replace(Regex("\\s+"), " ").trim()
    if (flat.isEmpty()) return null
    return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
}

/**
 * Signs what an alert's tap opens. The activity an alert starts is exported, so it opens only what
 * an intent carries together with a signature made with the app's own key - kept in app-private
 * storage, never in a backup or the diagnostics - not a pane named by whoever sent the intent:
 * opening a pane takes it, and that resizes it on the desktop. Unlike a token held in memory, a
 * signature still opens its pane after Android killed and restarted the process, and after any
 * number of later alerts.
 *
 * The first field names what is signed (an alert's pane, a host's group), so one kind of link
 * cannot be passed off as another.
 */
class AlertSigner(key: ByteArray) {
    private val key = key.copyOf()

    init {
        require(key.size >= MIN_KEY_BYTES) { "the key is too short" }
    }

    /** The signature of [fields], URL-safe. */
    fun sign(vararg fields: String): String = B64.encodeToString(mac(fields))

    /** Whether [signature] is this key's for exactly [fields], compared in constant time. */
    fun verify(signature: String?, vararg fields: String): Boolean {
        if (signature.isNullOrEmpty()) return false
        val given = runCatching { Base64.getUrlDecoder().decode(signature) }.getOrNull() ?: return false
        return MessageDigest.isEqual(given, mac(fields))
    }

    private fun mac(fields: Array<out String>): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        // Each field with its length first, so ("ab", "c") and ("a", "bc") differ.
        for (f in fields) {
            val bytes = f.toByteArray(Charsets.UTF_8)
            mac.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            mac.update(bytes)
        }
        return mac.doFinal()
    }

    /** Names no key. */
    override fun toString(): String = "AlertSigner"

    companion object {
        const val MIN_KEY_BYTES = 32
        private const val ALGORITHM = "HmacSHA256"
        private val B64 = Base64.getUrlEncoder().withoutPadding()

        /** A new random key, for a new install. */
        fun newKey(): ByteArray = ByteArray(MIN_KEY_BYTES).also { SecureRandom().nextBytes(it) }
    }
}
