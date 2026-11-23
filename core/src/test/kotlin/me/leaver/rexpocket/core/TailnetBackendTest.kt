package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TailnetBackendTest {
    @Test
    fun theStoredNameReadsBackToItsBackend() {
        for (backend in TailnetBackend.entries) {
            assertEquals(backend, TailnetBackend.fromStorage(backend.storageKey))
        }
    }

    @Test
    fun anUnknownOrMissingNameIsTailscale() {
        // Tailscale is the backend the app shipped with, so a settings file from before Netbird reads as it.
        assertEquals(TailnetBackend.Tailscale, TailnetBackend.fromStorage(null))
        assertEquals(TailnetBackend.Tailscale, TailnetBackend.fromStorage(""))
        assertEquals(TailnetBackend.Tailscale, TailnetBackend.fromStorage("wireguard"))
    }

    @Test
    fun aBackendThatSignsInShowsTheSignInStep() {
        for (backend in TailnetBackend.entries) {
            assertEquals(backend.signsIn, TailnetStep.SignIn in tailnetSteps(backend))
            assertEquals(TailnetStep.Start, tailnetSteps(backend).first())
            assertEquals(TailnetStep.Connect, tailnetSteps(backend).last())
        }
    }

    @Test
    fun everyBackendHasItsOwnNameAndStorageKey() {
        assertEquals(TailnetBackend.entries.size, TailnetBackend.entries.map { it.displayName }.toSet().size)
        assertEquals(TailnetBackend.entries.size, TailnetBackend.entries.map { it.storageKey }.toSet().size)
        assertEquals("Tailscale", TailnetBackend.Tailscale.displayName)
        assertEquals(true, TailnetBackend.Tailscale.signsIn)
    }
}
