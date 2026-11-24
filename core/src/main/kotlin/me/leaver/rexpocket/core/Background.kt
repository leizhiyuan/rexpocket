package me.leaver.rexpocket.core

// Background mode: which hosts stay connected while the app is away, which connect on their own,
// and which of their programs' news becomes a notification. See docs/design/multi-host.md,
// "Background", and docs/design/notifications.md.

/** One switch of the Notifications page, which a host can set apart from the others. */
enum class AlertEvent {
    Waiting,
    Failed,
    Done,

    /** A program's desktop notification (OSC 9, OSC 777). */
    Message,

    /** A bell in a pane not on screen. */
    Bell,
}

/** Whether [event] is on in these choices. */
fun AlertPrefs.on(event: AlertEvent): Boolean = when (event) {
    AlertEvent.Waiting -> waiting
    AlertEvent.Failed -> failed
    AlertEvent.Done -> done
    AlertEvent.Message -> messages
    AlertEvent.Bell -> bells
}

/** These choices with [event] turned [on]. */
fun AlertPrefs.with(event: AlertEvent, on: Boolean): AlertPrefs = when (event) {
    AlertEvent.Waiting -> copy(waiting = on)
    AlertEvent.Failed -> copy(failed = on)
    AlertEvent.Done -> copy(done = on)
    AlertEvent.Message -> copy(messages = on)
    AlertEvent.Bell -> copy(bells = on)
}

/**
 * What one host does in the background. [stay]: it stays connected while the app is away (and
 * after the task is swiped away or Android restarted the app), so its programs can alert; off, it
 * is let go a while after the app leaves the screen and comes back with it. [always]: it connects
 * at every launch and every restart, even after a Disconnect, and stays (it implies [stay]).
 * [notify] off silences the host; otherwise each event in [overrides] replaces the page's default,
 * and the events not in it follow the default as it changes. [muted] are the tabs (Rex's window
 * ids) the person muted ("Mute this tab", or Mute on an alert): none of their panes alerts.
 */
data class HostBackground(
    val stay: Boolean = true,
    val always: Boolean = false,
    val notify: Boolean = true,
    val overrides: Map<AlertEvent, Boolean> = emptyMap(),
    val muted: Set<String> = emptySet(),
) {
    /** Whether the panes of tab [windowId] stay quiet. */
    fun mutes(windowId: String?): Boolean = windowId != null && windowId in muted

    /** Tab [windowId] muted or not; the oldest mutes go past [MAX_MUTED]. */
    fun withMuted(windowId: String, on: Boolean): HostBackground =
        copy(muted = if (on) (muted - windowId + windowId).toList().takeLast(MAX_MUTED).toSet() else muted - windowId)

    /** Whether this host is kept connected while the app is away. */
    val kept: Boolean get() = stay || always

    /** These choices as the background choice hosts and tailnets share. */
    val keep: BackgroundKeep
        get() = when {
            always -> BackgroundKeep.Always
            stay -> BackgroundKeep.Follow
            else -> BackgroundKeep.Off
        }

    /** These choices with [keep] as the background choice. */
    fun withKeep(keep: BackgroundKeep): HostBackground = when (keep) {
        BackgroundKeep.Always -> copy(stay = true, always = true)
        BackgroundKeep.Follow -> copy(stay = true, always = false)
        BackgroundKeep.Off -> copy(stay = false, always = false)
    }

    /** The choices that apply to this host: [base] (the page's defaults) with this host's on top. */
    fun prefs(base: AlertPrefs): AlertPrefs {
        if (!notify) return AlertEvent.entries.fold(base) { p, e -> p.with(e, false) }
        return overrides.entries.fold(base) { p, (e, on) -> p.with(e, on) }
    }

    /**
     * [event] turned [on] for this host. A choice that is the default's is not kept apart from it,
     * so it follows the default from then on.
     */
    fun with(event: AlertEvent, on: Boolean, base: AlertPrefs): HostBackground =
        copy(overrides = if (base.on(event) == on) overrides - event else overrides + (event to on))

    val isDefault: Boolean get() = this == HostBackground()

    companion object {
        /** The most tabs one host keeps muted: a closed tab's mute stays, so the oldest go. */
        const val MAX_MUTED = 100
    }
}

/** Every host's [HostBackground], by host id; a host not in it has the defaults. */
data class BackgroundHosts(val hosts: Map<String, HostBackground> = emptyMap()) {
    fun of(hostId: String): HostBackground = hosts[hostId] ?: HostBackground()

    fun with(hostId: String, next: HostBackground): BackgroundHosts =
        BackgroundHosts(if (next.isDefault) hosts - hostId else hosts + (hostId to next))

    /** Only the hosts still in [saved]: a deleted host's choices go with it. */
    fun keep(saved: Collection<String>): BackgroundHosts = BackgroundHosts(hosts.filterKeys { it in saved })

    /** What applies to each host, from the page's defaults. */
    fun prefsOf(base: AlertPrefs): (String) -> AlertPrefs = { id -> of(id).prefs(base) }

    /**
     * The hosts to connect when the app starts with a screen: the one open last, then the ones
     * connected when it went (never disconnected), then the always-connected, each once and only
     * if still [saved].
     */
    fun atLaunch(last: String?, connected: List<String>, saved: List<String>): List<String> =
        (listOfNotNull(last) + connected + saved.filter { of(it).always }).distinct().filter { it in saved }

    /**
     * The hosts to connect when Android starts the app without a screen - the watch restarted
     * after its process was killed, after a reboot or an update: the connected ones that stay in
     * the background, and the always-connected. With background mode off, none.
     */
    fun inBackground(on: Boolean, connected: List<String>, saved: List<String>): List<String> {
        if (!on) return emptyList()
        return (connected.filter { of(it).kept } + saved.filter { of(it).always }).distinct().filter { it in saved }
    }

    /**
     * The connected hosts to let go while the app is away: those not kept in the background,
     * except [onScreen], the host of the terminal the person left, which comes back as it was.
     */
    fun toPark(connected: Collection<String>, onScreen: String?): List<String> =
        connected.filter { !of(it).kept && it != onScreen }
}
