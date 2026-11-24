package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Accounts: the first one always there, one active per backend, a host on a named one. */
class TailnetProfilesTest {
    private val ts = TailnetBackend.Tailscale

    @Test
    fun theFirstAccountIsAlwaysThereAndActive() {
        val p = TailnetProfiles()
        assertEquals(listOf(TailnetProfiles.first(ts)), p.of(ts))
        assertEquals("tailscale", p.activeOf(ts).id)
        assertTrue(p.activeOf(ts).isFirst)
    }

    @Test
    fun anAddedAccountIsActivatedRenamedAndRemovedOnlyWhenNotActive() {
        val work = TailnetProfile(TailnetProfiles.newId(ts, "AB12-cd"), ts, "Work")
        assertEquals("tailscale-ab12cd", work.id)
        var p = TailnetProfiles().add(work)
        assertEquals(listOf("tailscale", work.id), p.of(ts).map { it.id })
        p = p.activate(work.id)
        assertEquals(work.id, p.activeOf(ts).id)
        // The active one stays: switch first.
        assertEquals(p, p.remove(work.id))
        p = p.rename(work.id, "  Office ")
        assertEquals("Office", p.find(work.id)?.name)
        assertEquals(p, p.rename(work.id, " "))
        p = p.activate("tailscale").remove(work.id)
        assertEquals(listOf("tailscale"), p.of(ts).map { it.id })
        // The last account of a backend is never removed.
        assertEquals(p, p.remove("tailscale"))
    }

    @Test
    fun namesAreKeptApart() {
        val p = TailnetProfiles().add(TailnetProfile("tailscale-a", ts, "Tailscale")).add(TailnetProfile("tailscale-b", ts, "Tailscale"))
        assertEquals(listOf("Tailscale", "Tailscale 2", "Tailscale 3"), p.of(ts).map { it.name })
    }

    @Test
    fun aHostFollowsItsAccountOrTheActiveOne() {
        val p = TailnetProfiles().add(TailnetProfile("tailscale-w", ts, "Work")).activate("tailscale-w")
        assertEquals("tailscale-w", p.profileOf(ts, "").id)
        assertEquals("tailscale", p.profileOf(ts, "tailscale").id)
        // An account removed since: the active one.
        assertEquals("tailscale-w", p.profileOf(ts, "tailscale-gone").id)
    }
}
