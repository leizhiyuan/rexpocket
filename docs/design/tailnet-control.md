# Connecting hosts and tailnets

The person asked for "more control over connection state of tailnets", per tailnet, with a great
UX, and then that tailnets and hosts share one design: "matching notifications and quick tiles for
tailnet and host… if Tailnet has a quick tile, hosts should also have one". This note is that
design: one model for the two things the phone connects to, and what each control does for each.

Code: `core/…/Connectable.kt` (the model and the plan, pure and unit-tested in
`ConnectableTest`), `ui/TailnetControl.kt` (the only place a tailnet node is started or stopped),
`data/TailnetPolicyStore.kt`, `ui/ConnectablePages.kt` (the host's and the tailnet's page), and
`Connections` (which hands the control its users and its situation).

## One model for hosts and tailnets

A **connectable** is a host (one Rex) or a tailnet (the node the app runs to reach hosts on it),
named `host:<id>` or `tailnet:<id>` wherever it is kept apart from the others (a tile's binding, a
notification action, a page). Both have:

- a **state** in the same five words (`LinkState`): Off, Connecting…, Connected, Needs sign-in, Not
  connected. A host's comes from its connection's phase, a tailnet's from its stage;
- **Connect and Disconnect by hand**, kept for the next start;
- the same **background choice** (`BackgroundKeep`): Follow (the default), Always, Let go;
- **notifications** while the app is away;
- the same **page**, **sheet items**, **tile** and **notification line**.

A tailnet adds one thing a host has no use for: **when it is up while the app is open**
(`TailnetUse`) and how long it **lingers** once no host uses it. A host's need is the person
opening it; a tailnet's need is its hosts.

| Control | Host | Tailnet |
| --- | --- | --- |
| State words | from its connection: Off, Connecting…, Connected, Needs sign-in (its tailnet's), Not connected | from its stage: the same five; Off covers "disconnected by you" and "waiting for a host" |
| Connect | its row's Connect, its sheet, its page, its tile | the home ⋮ (Connect Tailscale), its sheet, its page, its tile; connecting a host on it does it too |
| Disconnect | at once; snackbar "Disconnected mac" with Reconnect | at once with no host on it; otherwise a confirm that names the hosts that go with it; the same snackbar, whose Reconnect brings the hosts back too |
| Retry | Retry now after a failure | Retry now after a failed start (the card's Try again stays) |
| Kept off | a Disconnect is kept (`connected_hosts`) | a Disconnect is kept (`tailnet.policies` `off`), and its hosts stay off with it: nothing connects them on its own until the tailnet or one of them is connected by hand |
| While open | connected while connected | **While the app is open** (default) or **Only when a host needs it**, with **Stop after** 1, 5 (default) or 15 minutes, or Never |
| In the background: Follow | stays connected if it was | stays up while a host on it stays connected; stops after its linger once none does |
| In the background: Always | connects at every start, even after a Disconnect, and stays | stays up while background mode is on, even with no host |
| In the background: Let go | let go a minute after the app leaves the screen, back with it | the same, and its hosts go with it |
| Notifications | its programs' alerts, each kind over the defaults | "Sign-in and failures": a notification when, while away, it needs a sign-in or stops working |
| Page | Settings › Background and notifications › the host; its sheet's "Connection and notifications" | Settings › Background and notifications › the tailnet; its sheet's "Connection and notifications" |
| Tile | a Quick Settings tile bound to it | the same tiles, bound to it |
| Watch notification | a line with its state; Disconnect when there is room | the same |

### The plan

`planTailnet(policy, situation)` says, at any moment, whether a tailnet is **Up**, **Down**, or
kept up only until a time (**Linger**). It reads:

1. Not joined, or disconnected by hand: down.
2. **Away** (background mode on, and the app off the screen past the park delay, a minute, or
   started by Android without a screen): the background choice. Always: up. Let go: down. Follow:
   up while a host uses it, else the linger.
3. **Open**: up while a host uses it (connecting ones count). Otherwise While open: up; Only when
   needed: the linger.

The linger keeps a running node until the last use plus Stop after (a host leaving, a Connect by
hand, the host dialog's Test connection all count as a use); it never starts a node, so a tailnet
nobody needs does not come up only to stop again. Never: kept up until something else stops it.

`TailnetControl` acts on the plan whenever its inputs change (the settings, the connected hosts,
the away flag, background mode, the choices) and at a linger's end. It is the only caller of a
node's `start` and `stop`; two asks at once (a connection and the plan) start the node once, and a
changed setting (a new name) starts it again. It acts only from the first screen's start or a
restart without one (`begin`), and stops everything with the screen when background mode is off,
or with the watch's Stop (`stopAll`).

### Defaults and what they keep

The defaults are the app's behaviour before this change: a joined tailnet is up while the app is
open (it used to start at launch and stay), and in the background it follows its hosts (it used to
stay up as long as the process did, which with background mode on was as long as any host was
kept). New is only that it now stops once its last kept host is gone, after the linger.

## Screens

- **Home chip.** Shown once the tailnet is connected, and now also while it is disconnected (by
  hand, or waiting for a host): quiet, with a grey dot instead of green. A tap opens its sheet. The
  card stays for the stages that need the person (sign in, approval, a failed start).
- **The tailnet's sheet** (the chip, Settings › Tailnet once joined, a tailnet host's "Tailnet
  details"). Under its status, the line "Used by 2 hosts" or "No host uses it · stops in 4
  minutes", then Connect, Cancel, Retry now and Disconnect as a host's sheet has them, then
  "Connection and notifications" (its page) and "Tailnet settings" (the join dialog).
- **Home ⋮.** "Connect Tailscale" or "Disconnect Tailscale", beside Tailnet.
- **Pages.** A host's page and a tailnet's page are one layout (`ConnectablePages.kt`): the state
  row and its action; While RexPocket is open (a tailnet's Keep connected and Stop after); In the
  background (While you are away: the three choices in each one's words, with a line when
  background mode is off); Notifications; Quick Settings. Reached from Settings › Background and
  notifications (Tailnets, then Hosts) and from each one's sheet.
- **Confirm.** "Disconnect Tailscale?" — "mac is connected through it and disconnects too. It stays
  off until you connect it, or a host on it."

## Tiles and the watch notification

Both are in [notifications.md](notifications.md), "The watch notification and the tiles". In
short: three Quick Settings tiles, each bound to a host or a tailnet (a picker on the first tap, or
a page's Add a tile), a tap to connect or disconnect, its state as the subtitle, a long press to
its page; and in the watch notification one line per host and tailnet, Stop, and one action each
while all of them fit in the two slots left.

## Battery

What costs battery is the node's own traffic while it is up: WireGuard keepalives to the peers it
talks to, the control-plane long poll (Tailscale's map request), DERP's keepalive, and the wakeups
of the network stack in the Go runtime. A Rex connection on top adds OkHttp's 30 s ping and Rex's
status events. Without a measurement on a phone, the shape of the cost is:

- **Tailnet up, no host** costs the node's idle traffic: one long-poll connection to the control
  plane and one to the home DERP region, each with a keepalive of the order of a minute, and
  nothing per peer once no connection uses one (WireGuard sends no keepalive on an idle session
  unless asked to). This is what "Always" keeps up in the background, so the first host connects
  in the time a Rex handshake takes instead of the node's start (a few seconds, more on a cold
  network). It is cheaper than keeping hosts connected, which adds each host's WebSocket, its 30 s
  ping, a WireGuard session kept warm to that peer, and Rex's status traffic.
- **Follow** (the default) costs nothing beyond the hosts the person keeps: the node goes once they
  do, after the linger.
- **Let go** costs nothing while away.
- **Only when needed** saves the node's idle traffic while the app is open with no host connected,
  at the price of a node start on the first connect.

The phone should be measured before any number goes here: `adb shell dumpsys batterystats
--charged me.leaver.rexpocket` over an hour each with the tailnet Always and no host, with one host
kept, and Let go, screen off, on Wi-Fi and on mobile data (mobile radio tail time dominates there).
That is in "Checks on a real phone" below.

## Checks on a real phone

- Disconnect and Connect the tailnet from the home ⋮, the chip's sheet and Settings, with a host
  connected through it: the confirm, the snackbar's Reconnect.
- Only when needed: the first connect after a launch waits for the node; the sheet's "stops in"
  counts down; the node stops (Settings › Diagnostics shows "tailnet tailscale: stopping (plan)").
- Background Always with no host: the watch stays, the next open connects a host at once.
- A reboot with Always: the watch comes back with the tailnet up.
- Battery as above.

## Tests

- `core`: `ConnectableTest`: keys; not joined or off by hand; while open; only when needed and each
  linger; a Connect by hand lingering from then; away with Follow, Always and Let go; a boot without
  a screen; the stored choices; hosts sharing the background choice; the state words; the stage of
  a tailnet disconnected by hand or waiting.
- `app` (sharedTest, JVM and emulator): `TailnetControlTest`: Disconnect from the home ⋮ confirms
  and names the host, the node stops, the host goes and is not connected again on its own, and
  Reconnect brings both; a tailnet disconnected stays off at the next start until a host on it is
  connected by hand; only when needed waits for its first host and lingers after it; the page's
  Keep connected.
- `app` (JVM): `BackgroundTailnetTest`: the watch restarted with no screen holds a tailnet kept
  Always with no host; a following tailnet does not come up without a host and does with one; a
  tailnet let go keeps its hosts from connecting; one disconnected by hand stays off.
