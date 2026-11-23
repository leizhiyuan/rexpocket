package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LoginLinkGateTest {
    private val link = "https://login.tailscale.com/a/1a2b"

    @Test
    fun aLinkThatAppearsWithoutAnAskOnlyShows() {
        val gate = LoginLinkGate()
        assertNull(gate.linkToOpen(TailnetStage.Starting))
        assertNull(gate.linkToOpen(TailnetStage.SigningIn(link)))
    }

    @Test
    fun joinOpensTheNewNodesLinkOnce() {
        val gate = LoginLinkGate()
        gate.asked(TailnetStage.Off)
        assertNull(gate.linkToOpen(TailnetStage.Off))
        assertNull(gate.linkToOpen(TailnetStage.Starting))
        assertNull(gate.linkToOpen(TailnetStage.SigningIn("")))
        assertEquals(link, gate.linkToOpen(TailnetStage.SigningIn(link)))
        assertNull(gate.linkToOpen(TailnetStage.SigningIn(link)))
        assertFalse(gate.waiting)
    }

    @Test
    fun theStaleLinkOfTheStageAtTheTapIsNotOpened() {
        val gate = LoginLinkGate()
        gate.asked(TailnetStage.SigningIn("https://old"))
        assertNull(gate.linkToOpen(TailnetStage.SigningIn("https://old")))
        assertNull(gate.linkToOpen(TailnetStage.Starting))
        assertEquals(link, gate.linkToOpen(TailnetStage.SigningIn(link)))
    }

    @Test
    fun signInBeforeTheLinkOpensItWhenItComes() {
        val gate = LoginLinkGate()
        gate.asked(TailnetStage.SigningIn(""))
        assertNull(gate.linkToOpen(TailnetStage.SigningIn("")))
        assertEquals(link, gate.linkToOpen(TailnetStage.SigningIn(link)))
    }

    @Test
    fun aSignInThatWasNotNeededEndsTheAsk() {
        val gate = LoginLinkGate()
        gate.asked(TailnetStage.Off)
        assertNull(gate.linkToOpen(TailnetStage.Connected("pixel-8", "example.com")))
        // Days later the key expires: its link shows on the card, and does not open by itself.
        assertNull(gate.linkToOpen(TailnetStage.SigningIn(link)))
    }

    @Test
    fun cancelForgetsTheAsk() {
        val gate = LoginLinkGate()
        gate.asked(TailnetStage.Off)
        gate.cancel()
        assertNull(gate.linkToOpen(TailnetStage.SigningIn(link)))
    }
}
