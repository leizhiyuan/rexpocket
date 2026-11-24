package me.leaver.rexpocket.core

// One model for the two things the phone connects to: a host (one Rex) and a tailnet (the node the
// app runs to reach hosts on it). Both are switched on and off by hand, both have the same
// background choice, the same state words, the same tile, notification line and settings page; a
// tailnet adds "on only when needed" and its linger. See docs/design/tailnet-control.md.

/** Which kind of thing a [Connectable] is. */
enum class ConnectableKind(val prefix: String) { Host("host"), Tailnet("tailnet") }

/**
 * A host or a tailnet, named the same way everywhere it is kept apart from the others: a Quick
 * Settings tile's binding, a notification action, a settings page. [key] is `host:<id>` or
 * `tailnet:<id>`.
 */
data class Connectable(val kind: ConnectableKind, val id: String) {
    val key: String get() = "${kind.prefix}:$id"

    companion object {
        fun host(id: String) = Connectable(ConnectableKind.Host, id)
        fun tailnet(id: String) = Connectable(ConnectableKind.Tailnet, id)

        /** The [key] read back; null for anything else. */
        fun parse(key: String?): Connectable? {
            val kind = ConnectableKind.entries.firstOrNull { key?.startsWith(it.prefix + ":") == true } ?: return null
            val id = key!!.substring(kind.prefix.length + 1)
            return if (id.isEmpty()) null else Connectable(kind, id)
        }
    }
}

/**
 * What a host or a tailnet does while the app is away (background mode on, the app off the screen
 * for a while, or started by Android without a screen). The same three choices for both:
 *
 * - [Follow], the default. A host stays connected if it was connected; a tailnet stays up while a
 *   host on it stays connected, and stops (after its linger) once none does.
 * - [Always]. A host connects at every launch and restart, even after a Disconnect, and stays; a
 *   tailnet stays up while background mode is on, even with no host on it, so a host connects at
 *   once when the app opens.
 * - [Off]. Let go a minute after the app leaves the screen, and back with the screen. A tailnet let
 *   go takes its hosts with it, since they cannot be reached without it.
 */
enum class BackgroundKeep(val storageKey: String) {
    Follow("follow"),
    Always("always"),
    Off("off");

    companion object {
        fun fromStorage(value: String?): BackgroundKeep = entries.firstOrNull { it.storageKey == value } ?: Follow
    }
}

/** How a tailnet is kept up while the app is open. Hosts have no such choice: opening one is its need. */
enum class TailnetUse(val storageKey: String) {
    /** Up whenever the app is open (the app before this always did this). */
    WhileOpen("open"),

    /** Started when a host on it connects, stopped [TailnetPolicy.lingerMs] after no host uses it. */
    WhenNeeded("needed");

    companion object {
        fun fromStorage(value: String?): TailnetUse = entries.firstOrNull { it.storageKey == value } ?: WhileOpen
    }
}

/** The state a host or a tailnet is in, in the words every screen, tile and notification shares. */
enum class LinkState { Off, Connecting, Connected, NeedsSignIn, Failed }

/** A host's state from its connection's phase and problem. */
fun hostLinkState(phase: Phase?, problem: Problem? = null): LinkState = when (phase) {
    null, Phase.Idle -> LinkState.Off
    Phase.Connecting, Phase.Reconnecting ->
        if (problem?.trouble == Trouble.TailnetNeedsLogin) LinkState.NeedsSignIn else LinkState.Connecting
    Phase.Live -> LinkState.Connected
    Phase.Failed -> if (problem?.trouble == Trouble.TailnetNeedsLogin) LinkState.NeedsSignIn else LinkState.Failed
}

/** A tailnet's state from its stage. */
fun TailnetStage.linkState(): LinkState = when (this) {
    TailnetStage.Off, is TailnetStage.Disconnected -> LinkState.Off
    TailnetStage.Starting, TailnetStage.Connecting -> LinkState.Connecting
    is TailnetStage.SigningIn, TailnetStage.NeedsApproval -> LinkState.NeedsSignIn
    is TailnetStage.Connected -> LinkState.Connected
    TailnetStage.Stopped, is TailnetStage.Failed -> LinkState.Failed
}

/**
 * One tailnet's choices. [use] and [lingerMs] (null: never stop) say when it is up while the app is
 * open; [keep] what it does while the app is away; [notify] whether it tells the person, while
 * away, that it needs a sign-in or stopped working.
 */
data class TailnetPolicy(
    val use: TailnetUse = TailnetUse.WhileOpen,
    val lingerMs: Long? = DEFAULT_LINGER_MS,
    val keep: BackgroundKeep = BackgroundKeep.Follow,
    val notify: Boolean = true,
) {
    val isDefault: Boolean get() = this == TailnetPolicy()

    companion object {
        const val DEFAULT_LINGER_MS = 5 * 60_000L

        /** The linger choices the page offers, the last one "never". */
        val LINGER_CHOICES: List<Long?> = listOf(60_000L, DEFAULT_LINGER_MS, 15 * 60_000L, null)
    }
}

/** Every tailnet's [TailnetPolicy], and the tailnets the person switched off by hand, by tailnet id. */
data class TailnetPolicies(
    val policies: Map<String, TailnetPolicy> = emptyMap(),
    val off: Set<String> = emptySet(),
) {
    fun of(id: String): TailnetPolicy = policies[id] ?: TailnetPolicy()

    fun with(id: String, next: TailnetPolicy): TailnetPolicies =
        copy(policies = if (next.isDefault) policies - id else policies + (id to next))

    fun isOff(id: String): Boolean = id in off

    /** [id] switched off (Disconnect) or back on (Connect, or connecting a host on it). */
    fun switched(id: String, on: Boolean): TailnetPolicies = copy(off = if (on) off - id else off + id)
}

/** What a tailnet should be doing now. */
sealed interface TailnetPlan {
    /** Up: started if it is not. */
    data object Up : TailnetPlan

    /** Kept up only until [until] (epoch ms), when it is looked at again; never started for it. */
    data class Linger(val until: Long) : TailnetPlan

    /** Stopped. */
    data object Down : TailnetPlan
}

/**
 * Where a tailnet is: [joined] (the setting), [off] (Disconnect by hand), [users] (connected hosts on
 * it, connecting ones too), [away] (background mode on and the app away long enough to let things
 * go, or started without a screen), [running] (its node is up or starting), [idleSince] (the last
 * moment a host used it or the person connected it by hand), [now].
 */
data class TailnetSituation(
    val joined: Boolean,
    val off: Boolean = false,
    val users: Int = 0,
    val away: Boolean = false,
    val running: Boolean = false,
    val idleSince: Long? = null,
    val now: Long = 0,
)

/**
 * The tailnet's plan. Off by hand or not joined: down. Away: [BackgroundKeep] decides (Follow: up
 * while a host uses it, then the linger). Open: up while a host uses it; otherwise [TailnetUse]
 * decides (WhileOpen: up; WhenNeeded: the linger). The linger only keeps a running node; it never
 * starts one, so a tailnet nobody needs does not come up to stop again.
 */
fun planTailnet(policy: TailnetPolicy, s: TailnetSituation): TailnetPlan = when {
    !s.joined || s.off -> TailnetPlan.Down
    s.away -> when (policy.keep) {
        BackgroundKeep.Always -> TailnetPlan.Up
        BackgroundKeep.Off -> TailnetPlan.Down
        BackgroundKeep.Follow -> if (s.users > 0) TailnetPlan.Up else linger(policy, s)
    }
    s.users > 0 -> TailnetPlan.Up
    policy.use == TailnetUse.WhileOpen -> TailnetPlan.Up
    else -> linger(policy, s)
}

private fun linger(policy: TailnetPolicy, s: TailnetSituation): TailnetPlan {
    if (!s.running) return TailnetPlan.Down
    val linger = policy.lingerMs ?: return TailnetPlan.Up
    val until = (s.idleSince ?: s.now) + linger
    return if (s.now < until) TailnetPlan.Linger(until) else TailnetPlan.Down
}
