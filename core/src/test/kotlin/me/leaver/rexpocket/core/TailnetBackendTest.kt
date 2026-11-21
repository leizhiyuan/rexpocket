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
}
