# Several hosts at once

The first step towards using RexPocket with a fleet of agents: every saved Rex host can be connected
at the same time, each on its own connection, and the home, Activity and Background alerts cover
all of them. The home keeps its layout; a redesign comes next, built on this.

## Model

- **`Fleet`** (core, `Fleet.kt`) holds one `PocketSession` per connected host, by host id. Each has
  its own control connection, reconnect and backoff, session list, views, statuses and alert ledger
  (`AlertPlanner`); nothing one does reaches another. On screen or not, and Background alerts on or
  off, apply to every member and to one added later. Its bells and notifications come out tagged
  with their host (`HostSignal`), its status changes as alert moves per host (`alertMoves`), and
  `reply`/`interrupt` go to the host they name.
- **`Connections`** (app, `ui/Connections.kt`) is process-scoped: one per process
  (`Connections.of(app)`), not the screen's. It owns the fleet, the tailnet nodes, each host's
  `Link` (the steps before Rex answers: the tailnet node, the SSH tunnel; the connect progress and
  the tailnet path), the store of what was connected, the network watcher, and everything Background
  alerts do: posting, settling, Reply and Interrupt (`Alerts.actions`), the watch notification.
- **`RexViewModel`** only watches it. It keeps what belongs to the screen: the route, the **focused
  host** (the one the terminal shows, or showed last), the session being opened, the resume after a
  restart, uploads, the share sheet. `pocket` is the focused host's state; `lives` is every
  connected host's (`HostLive`: state, connect steps, path) for the home.
- Persisted state stays keyed by host id and session id, as before: folds (`hostId/sessionId`),
  cached session lists, the last session and pane per host. New: `connected_hosts`, the hosts
  connected and not disconnected, in order. Notification tags are `hostId/blockId`, so two hosts
  (the same Rex saved twice, say) never settle each other's alerts.

## Lifecycle

- **Connect** is explicit: the Connect button on a host that is not connected, or Connect in its ⋮
  sheet. Opening something on a host (a session, a tab, an activity row, an alert, a share) connects
  it if needed. A host's row tap folds it in any state; it never connects.
- **Disconnect** is in the host's ⋮ sheet. Only that host goes; the others keep their connection
  (same client, no new hello). It stays disconnected at the next start.
- **Start**: the hosts in `connected_hosts` connect again, the one open last first and focused (it
  is the one the resume after Android killed the app goes back to). An app from before this had
  only the last host; that stands in for the list until one is written, so a single-host user sees
  what they saw.
- **Focus** moves to a host when a session or tab opened on it is shown, not at the tap: the pane on
  screen stays until the new one is there. Connecting a host from the list while another host's
  terminal is open does not take the terminal away.
- **Home**: a connected host shows its live sessions and status as before; one that is not shows one
  line, "Not connected · N sessions" (the count it had last time), with Connect under it. The eye
  lists every connected host's activity, each under its name when there are several.

## Background

Background alerts work as notifications.md says, for every connected host: the watch notification
names them all ("Watching mac, linux for programs that need you"), each notification has its host
as its subtext, and Reply and Interrupt are sent on the connection of the host they came from. While
the app is away every connection pauses its screen polls; with alerts on, each keeps its panes'
status current (the status reader reads a pane once, then Rex's events keep it), as the single
connection did. A network change redials every host at once.

**The seam for alerts that outlive the app.** The connections are not the activity's: with Background
alerts on, clearing the view model (the task swiped away) leaves them, and their alerts, running for
as long as the process lives; with alerts off they close as before, and connect again at the next
start. Not done here, and the follow-up for ntfy-like alerts: `WatchService` `START_STICKY`,
starting `Connections` (and connecting `connected_hosts`) from the service when the process comes
back without an activity, after a reboot or an update, and asking for the battery-optimization
exemption. `Connections.of(app)` is what such a service would call.

## Limits

- One terminal at a time: the focused host's. The others are live on the home, not on screen, so
  the phone takes no pane on them and changes no desktop's size.
- Each connected host is one control connection and its status events; a pane stream only for the
  pane on screen. Hosts the person does not use can be disconnected.
- One tailnet node serves every tailnet host, as before.
- A host saved twice (LAN and tailnet address of the same Rex) is two connections to one Rex, and
  its alerts come twice, once per host.
- Seen state is not shared with the desktop: Rex has none, and does not say what other clients show
  (rex-parity.md, "What other clients show").

## Tests

- `FleetTest` (core, two ScriptedRex): each host connects and disconnects on its own, both raise
  alerts and keep their own ledger, signals carry their host, a reply reaches the host it names, one
  host reconnecting leaves the other's connection alone.
- `MultiHostTest` (app, shared: JVM and emulator): two hosts live on the home, a tab on one leaves
  the other connected; Disconnect in the ⋮ sheet, the one line with Connect, the row's tap folding
  and not connecting, Connect beside the other host, `connected_hosts`.
- `NotificationsTest.twoHostsAlertEachWithItsHostAndAReplyGoesToItsHost` (app, JVM).
