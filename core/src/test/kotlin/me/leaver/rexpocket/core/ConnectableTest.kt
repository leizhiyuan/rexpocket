package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The model hosts and tailnets share: the background choice, the state words, and when a tailnet is
 * up (by hand, while the app is open, only when needed with its linger, in the background, at a
 * boot). See docs/design/tailnet-control.md.
 */
class ConnectableTest {
    private val open = TailnetSituation(joined = true)
    private val needed = TailnetPolicy(use = TailnetUse.WhenNeeded)

    @Test
    fun keysRoundTrip() {
        assertEquals(Connectable.host("a1"), Connectable.parse(Connectable.host("a1").key))
        assertEquals(Connectable.tailnet("tailscale"), Connectable.parse("tailnet:tailscale"))
        assertNull(Connectable.parse("host:"))
        assertNull(Connectable.parse("pane:x"))
        assertNull(Connectable.parse(null))
    }

    @Test
    fun aTailnetNotJoinedOrDisconnectedByHandIsDown() {
        assertEquals(TailnetPlan.Down, planTailnet(TailnetPolicy(), open.copy(joined = false)))
        // Off by hand wins over hosts using it, background Always, everything.
        val always = TailnetPolicy(keep = BackgroundKeep.Always)
        assertEquals(TailnetPlan.Down, planTailnet(always, open.copy(off = true, users = 2, running = true)))
        assertEquals(TailnetPlan.Down, planTailnet(always, open.copy(off = true, away = true)))
    }

    @Test
    fun whileOpenIsUpWithOrWithoutHosts() {
        assertEquals(TailnetPlan.Up, planTailnet(TailnetPolicy(), open))
        assertEquals(TailnetPlan.Up, planTailnet(TailnetPolicy(), open.copy(users = 1)))
    }

    @Test
    fun onlyWhenNeededStartsWithAHostAndLingersAfterTheLast() {
        // Nobody uses it and it is not running: it does not start.
        assertEquals(TailnetPlan.Down, planTailnet(needed, open.copy(now = 1_000)))
        // A host connects: up.
        assertEquals(TailnetPlan.Up, planTailnet(needed, open.copy(users = 1, now = 1_000)))
        // The last host goes at 10 s: kept for the linger, five minutes by default.
        val idle = open.copy(running = true, idleSince = 10_000)
        assertEquals(TailnetPlan.Linger(310_000), planTailnet(needed, idle.copy(now = 20_000)))
        assertEquals(TailnetPlan.Down, planTailnet(needed, idle.copy(now = 310_000)))
        // Each linger choice, and never.
        assertEquals(TailnetPlan.Linger(70_000), planTailnet(needed.copy(lingerMs = 60_000), idle.copy(now = 20_000)))
        assertEquals(TailnetPlan.Up, planTailnet(needed.copy(lingerMs = null), idle.copy(now = 99_000_000)))
        // A linger never starts a node that is down.
        assertEquals(TailnetPlan.Down, planTailnet(needed.copy(lingerMs = null), open.copy(now = 5)))
    }

    @Test
    fun aConnectByHandLingersFromThatMoment() {
        // Connect by hand sets idleSince to then: a tailnet only needed by hosts stays the linger.
        val connected = open.copy(running = true, idleSince = 50_000, now = 50_000)
        assertEquals(TailnetPlan.Linger(350_000), planTailnet(needed, connected))
    }

    @Test
    fun inTheBackgroundFollowStaysWhileAHostDoes() {
        val away = open.copy(away = true, running = true)
        assertEquals(TailnetPlan.Up, planTailnet(TailnetPolicy(), away.copy(users = 1)))
        // The last kept host went at 0: the linger, then down - also for a tailnet up while open.
        assertEquals(TailnetPlan.Linger(TailnetPolicy.DEFAULT_LINGER_MS), planTailnet(TailnetPolicy(), away.copy(idleSince = 0, now = 1)))
        assertEquals(TailnetPlan.Down, planTailnet(TailnetPolicy(), away.copy(idleSince = 0, now = TailnetPolicy.DEFAULT_LINGER_MS)))
    }

    @Test
    fun inTheBackgroundAlwaysStaysUpWithNoHostAndOffLetsGo() {
        val away = open.copy(away = true)
        assertEquals(TailnetPlan.Up, planTailnet(TailnetPolicy(keep = BackgroundKeep.Always), away))
        assertEquals(TailnetPlan.Up, planTailnet(needed.copy(keep = BackgroundKeep.Always), away))
        assertEquals(TailnetPlan.Down, planTailnet(TailnetPolicy(keep = BackgroundKeep.Off), away.copy(users = 2, running = true)))
    }

    @Test
    fun aBootWithoutAScreenIsAway() {
        // Android starts the app with background mode on: nothing runs yet, nothing is open.
        val boot = TailnetSituation(joined = true, away = true, running = false, now = 0)
        assertEquals(TailnetPlan.Down, planTailnet(TailnetPolicy(), boot))
        assertEquals(TailnetPlan.Up, planTailnet(TailnetPolicy(), boot.copy(users = 1)))
        assertEquals(TailnetPlan.Up, planTailnet(TailnetPolicy(keep = BackgroundKeep.Always), boot))
    }

    @Test
    fun policiesKeepOnlyWhatIsNotTheDefault() {
        val p = TailnetPolicies().with("t", needed).with("u", TailnetPolicy())
        assertEquals(setOf("t"), p.policies.keys)
        assertEquals(TailnetPolicy(), p.of("u"))
        val off = p.switched("t", on = false)
        assertEquals(true, off.isOff("t"))
        assertEquals(false, off.switched("t", on = true).isOff("t"))
        assertEquals(BackgroundKeep.Follow, BackgroundKeep.fromStorage("nonsense"))
        assertEquals(TailnetUse.WhenNeeded, TailnetUse.fromStorage("needed"))
    }

    @Test
    fun hostsShareTheBackgroundChoice() {
        assertEquals(BackgroundKeep.Follow, HostBackground().keep)
        assertEquals(BackgroundKeep.Off, HostBackground(stay = false).keep)
        assertEquals(BackgroundKeep.Always, HostBackground(always = true).keep)
        for (k in BackgroundKeep.entries) assertEquals(k, HostBackground().withKeep(k).keep)
        // Off keeps the host's notifications as they were.
        assertEquals(false, HostBackground(notify = false).withKeep(BackgroundKeep.Off).notify)
    }

    @Test
    fun hostsAndTailnetsShareTheStateWords() {
        assertEquals(LinkState.Off, hostLinkState(null))
        assertEquals(LinkState.Connecting, hostLinkState(Phase.Reconnecting))
        assertEquals(LinkState.Connected, hostLinkState(Phase.Live))
        assertEquals(LinkState.Failed, hostLinkState(Phase.Failed, Problem(Trouble.Unreachable, null)))
        assertEquals(LinkState.NeedsSignIn, hostLinkState(Phase.Failed, Problem(Trouble.TailnetNeedsLogin, null)))
        assertEquals(LinkState.Off, TailnetStage.Disconnected(byYou = true).linkState())
        assertEquals(LinkState.NeedsSignIn, TailnetStage.SigningIn("").linkState())
        assertEquals(LinkState.Connected, TailnetStage.Connected("p", "n").linkState())
        assertEquals(LinkState.Failed, TailnetStage.Failed("x").linkState())
    }

    @Test
    fun theStageSaysWhetherItWasDisconnectedByHand() {
        assertEquals(TailnetStage.Disconnected(byYou = true), tailnetStage(joined = true, backendState = "Running", off = true))
        assertEquals(TailnetStage.Disconnected(byYou = false), tailnetStage(joined = true, backendState = "", running = false))
        // A start that failed is shown, running or not.
        assertEquals(TailnetStage.Failed("x"), tailnetStage(joined = true, backendState = "", error = "x", running = false))
        assertEquals(TailnetStage.Off, tailnetStage(joined = false, backendState = "", off = true))
    }
}
