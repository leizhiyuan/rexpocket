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
    fun theFirstAccountIsRemovedForGoodOnceAnotherRuns() {
        val work = TailnetProfile("tailscale-w", ts, "Work")
        var p = TailnetProfiles().add(work)
        // In use: switch first.
        assertEquals(p, p.remove("tailscale"))
        p = p.activate(work.id).remove("tailscale")
        assertEquals(listOf(work.id), p.of(ts).map { it.id })
        assertEquals(null, p.find("tailscale"))
        assertTrue(ts in p.removedFirst)
        // Gone after another account is added, and the last one stays.
        p = p.add(TailnetProfile("tailscale-h", ts, "Home"))
        assertEquals(listOf(work.id, "tailscale-h"), p.of(ts).map { it.id })
        assertEquals(work.id, p.activeOf(ts).id)
        assertEquals(p, p.remove(work.id))
        val one = p.activate("tailscale-h").remove(work.id)
        assertEquals(listOf("tailscale-h"), one.of(ts).map { it.id })
        assertEquals(one, one.remove("tailscale-h"))
        // A host that named the first account follows the one in use.
        assertEquals("tailscale-h", one.profileOf(ts, "tailscale").id)
    }

    @Test
    fun anAccountIsFoundByName() {
        val p = TailnetProfiles().add(TailnetProfile("tailscale-w", ts, "Work"))
        assertEquals("tailscale-w", p.named(ts, " work ")?.id)
        assertEquals("tailscale", p.named(ts, "Tailscale")?.id)
        assertEquals(null, p.named(ts, "Home"))
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
