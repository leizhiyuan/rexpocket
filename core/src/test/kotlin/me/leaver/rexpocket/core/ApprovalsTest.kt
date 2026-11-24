package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which prompts get Approve and Deny, with which keys, and what makes a prompt a new one. */
class ApprovalsTest {
    private fun record(state: String = "blocked", kind: String? = "permission", app: String? = "claude", msg: String? = "Allow Bash?", at: Long? = 1_000L) =
        ProgramRecord(id = "", state = state, kind = kind, app = app, title = null, msg = msg, progress = null, updatedAt = at)

    @Test
    fun theKnownAgentsAndTheirKeys() {
        assertEquals(listOf(PromptKey("enter")), approvalKeys(record(app = "claude"))!!.approve)
        assertEquals(listOf(PromptKey("escape")), approvalKeys(record(app = "claude"))!!.deny)
        assertEquals(listOf(PromptKey("key_y", "y")), approvalKeys(record(app = "codex"))!!.approve)
        assertEquals(listOf(PromptKey("escape")), approvalKeys(record(app = "codex"))!!.deny)
        assertEquals(listOf(PromptKey("enter")), approvalKeys(record(app = "omp", msg = "Allow bash: npm test?"))!!.approve)
        assertEquals(listOf(PromptKey("escape")), approvalKeys(record(app = "omp", msg = "Allow bash: npm test?"))!!.deny)
        assertEquals('y'.code, PromptKey("key_y", "y").codepoint)
        assertEquals(0, PromptKey("enter").codepoint)
    }

    @Test
    fun anAgentIsKnownByItsFirstWord() {
        for (name in listOf("claude", "Claude", "claude-code", "Claude Code", " claude ", "/usr/local/bin/claude")) {
            assertEquals(name, ApprovalKeys.CLAUDE, ApprovalKeys.forApp(name))
        }
        assertEquals(ApprovalKeys.CODEX, ApprovalKeys.forApp("codex"))
        assertEquals(ApprovalKeys.CODEX, ApprovalKeys.forApp("codex-cli"))
    }

    @Test
    fun ompsToolApprovalAndConfirmsAreAnsweredButNotItsPlanReview() {
        assertEquals(ApprovalKeys.OMP, approvalKeys(record(app = "omp", msg = "Allow bash: rm -rf build?")))
        assertEquals("a Yes/No confirm", ApprovalKeys.OMP, approvalKeys(record(app = "omp", msg = "Install skill with scripts? setup.sh")))
        assertNull("its plan review takes other keys", approvalKeys(record(app = "omp", msg = "Review plan: Split the parser")))
        assertNull(approvalKeys(record(app = "omp", msg = " Review plan: x")))
        assertNull("an ask wants words", approvalKeys(record(app = "omp", kind = "question", msg = "Which branch?")))
        assertEquals("only omp skips it", ApprovalKeys.CLAUDE, approvalKeys(record(app = "claude", msg = "Review plan: x")))
    }

    @Test
    fun anyOtherProgramGetsNoButtons() {
        for (name in listOf(null, "", "terraform", "brew", "gemini", "opencode", "pi", "ompx", "claudette", "deploy")) {
            assertNull(name, ApprovalKeys.forApp(name))
        }
    }

    @Test
    fun onlyAnApprovalPromptIsAnswered() {
        assertNull("a login is typed into the pane", approvalKeys(record(kind = "auth")))
        assertNull("a question wants words", approvalKeys(record(kind = "question")))
        assertNull("a blocked program that says nothing more", approvalKeys(record(kind = null)))
        assertNull(approvalKeys(record(state = "working", kind = null)))
        assertNull(approvalKeys(record(state = "done", kind = "permission")))
        assertNull(approvalKeys(null))
    }

    @Test
    fun aPromptAskedAgainIsANewPrompt() {
        val first = record()
        assertEquals(promptStamp(first), promptStamp(first.copy(title = "animated title")))
        assertNotEquals(promptStamp(first), promptStamp(first.copy(updatedAt = 2_000L)))
        assertNotEquals(promptStamp(first), promptStamp(first.copy(msg = "Allow Edit?")))
        assertNotEquals(promptStamp(first), promptStamp(first.copy(app = "codex")))
        assertNotEquals(promptStamp(first), promptStamp(first.copy(id = "child")))
        assertNotEquals(promptStamp(first), promptStamp(first.copy(kind = "question")))
    }
}
