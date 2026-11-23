package me.leaver.rexpocket.core

// Approve and Deny for a program blocked on an approval prompt: which keys answer it, per agent, and
// how the phone makes sure the prompt it answers is still the one the person saw. See
// docs/design/notifications.md, "Approve and Deny".

/** One key press as `client.key` sends it: Rex's name for the key, and the character it types. */
data class PromptKey(val code: String, val text: String = "") {
    val codepoint: Int get() = text.firstOrNull()?.code ?: 0
}

/**
 * The keys that answer one agent's approval prompt. Rex's Program Status Protocol says a program is
 * `blocked` with `kind=permission` ("approval to do something") but not how to answer it; that is
 * the program's own, so the phone only offers Approve and Deny for the agents in [ApprovalKeys.table].
 */
data class ApprovalKeys(val agent: String, val approve: List<PromptKey>, val deny: List<PromptKey>) {
    fun keys(approve: Boolean): List<PromptKey> = if (approve) this.approve else deny

    companion object {
        /**
         * Claude Code's permission prompt is a menu, "1. Yes" first and selected, with "No, and tell
         * Claude what to do differently (esc)" last: Return takes the selected Yes, Esc says no.
         */
        val CLAUDE = ApprovalKeys("claude", approve = listOf(PromptKey("enter")), deny = listOf(PromptKey("escape")))

        /**
         * Codex's approval prompt names a key for each answer: "Yes, proceed (y)" and "No, and tell
         * Codex what to do differently (esc)".
         */
        val CODEX = ApprovalKeys("codex", approve = listOf(PromptKey("key_y", "y")), deny = listOf(PromptKey("escape")))

        /** Every agent the phone can answer, by the `app` it reports (docs:program-status, Keys). */
        val table: List<ApprovalKeys> = listOf(CLAUDE, CODEX)

        /**
         * The keys for the program that reported [app], or null for one the phone does not know: a
         * guess would answer the wrong thing (a `y` typed into a prompt that wanted Return). The name
         * is matched by its first word, so `claude`, `claude-code` and `Claude Code` agree.
         */
        fun forApp(app: String?): ApprovalKeys? {
            val name = app?.trim()?.lowercase()?.substringAfterLast('/')?.split(' ', '-', '_', '.')?.firstOrNull()
                ?.takeIf { it.isNotEmpty() } ?: return null
            return table.firstOrNull { it.agent == name }
        }
    }
}

/**
 * The keys that answer [record]'s prompt: only a `blocked` record of kind `permission` from an agent
 * the phone knows. Never for `auth` (a login is typed into the pane) or `question` (it wants words).
 */
fun approvalKeys(record: ProgramRecord?): ApprovalKeys? {
    if (record == null || record.state != "blocked" || record.kind != "permission") return null
    return ApprovalKeys.forApp(record.app)
}

/**
 * What identifies one prompt: the record's id, state, kind, program, message and when Rex stamped
 * it. A program that asks again - even the same question - gets a new `updated_at`, so an answer
 * meant for the first ask is not taken as the answer to the second.
 */
fun promptStamp(record: ProgramRecord): String =
    listOf(record.id, record.state, record.kind.orEmpty(), record.app.orEmpty(), record.msg.orEmpty(), record.updatedAt?.toString().orEmpty())
        .joinToString("\u0000")

/** How an Approve or Deny went. */
enum class PromptAnswer {
    /** The pane's prompt was the one the person saw, and Rex has the keys. */
    Sent,

    /** The pane's record is no longer that prompt: nothing was sent. */
    Changed,

    /** The pane is gone, or the host could not be reached: nothing was sent. */
    NotSent,
}
