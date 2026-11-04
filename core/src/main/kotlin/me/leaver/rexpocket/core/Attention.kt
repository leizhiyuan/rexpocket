package me.leaver.rexpocket.core

import java.util.UUID

// Which panes need the person: the news an alert is made of.

/** What changed since the last look: panes that started waiting, and panes that stopped. */
data class AttentionChanges(val raised: List<String>, val settled: List<String>)

/**
 * Remembers which request each pane is waiting on, so one request is news once. A pane needs the
 * person while it is blocked on them or has failed; running, done and idle never do. The title is
 * left out of what makes a request new, because a program animates its title while it waits.
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

    private fun stampOf(record: ProgramRecord): String? = when (record.state) {
        "blocked", "error" -> listOf(record.state, record.kind.orEmpty(), record.msg.orEmpty()).joinToString("\u0000")
        else -> null
    }
}

/**
 * One-use tokens for what an alert opens. The activity an alert starts is exported, so it accepts
 * only a token this process issued, not a pane named by whoever sent the intent: opening a pane
 * takes it, and that resizes it on the desktop.
 */
class AlertTokens<T>(private val limit: Int = 64) {
    private val issued = LinkedHashMap<String, T>()

    @Synchronized
    fun issue(target: T): String {
        while (issued.size >= limit) issued.remove(issued.keys.first())
        val token = UUID.randomUUID().toString()
        issued[token] = target
        return token
    }

    @Synchronized
    fun consume(token: String?): T? = token?.let { issued.remove(it) }
}
